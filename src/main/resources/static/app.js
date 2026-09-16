'use strict';
const $ = id => document.getElementById(id);
const state = {config: null, csrf: null, report: null, charts: []};
let noticeTimer;
function notice(message) {
  $('notice').textContent = message;
  $('notice').hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => $('notice').hidden = true, 10000);
}
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}
async function csrf() { state.csrf = await (await fetch('/api/csrf')).json(); }
async function api(path, method = 'GET', body) {
  const headers = {};
  if (method !== 'GET') {
    if (!state.csrf) await csrf();
    headers[state.csrf.headerName] = state.csrf.token;
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch('/api' + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
  if (!response.ok) {
    let error;
    try { error = await response.json(); } catch (_) {}
    throw Error(error?.error || (response.status === 401 ? '登录已过期，请刷新重新登录' : response.status === 403 ? '没有权限或CSRF令牌失效，请刷新重新登录' : '请求失败：' + response.status));
  }
  return response.status === 204 ? null : response.json();
}
function options(id, values, allLabel) {
  $(id).replaceChildren(...(allLabel ? [new Option(allLabel, '')] : []), ...values.map(v => new Option(v.label || v, v.key || v)));
}
function definition() {
  const metric = state.config.metrics.find(m => m.key === $('metric').value);
  $('definition').textContent = metric.formula + '。时间口径：' + metric.timeBasis + '。' + metric.caveat;
}
function applyPlan(plan) {
  for (const key of ['metric', 'dimension', 'startDate', 'endDate', 'region', 'category', 'channel', 'status']) $(key).value = plan[key] || '';
  definition();
}
function collectPlan() {
  const plan = {};
  for (const key of ['metric', 'dimension', 'startDate', 'endDate', 'region', 'category', 'channel', 'status']) plan[key] = $(key).value || null;
  return plan;
}
async function generate() {
  $('generate').disabled = true;
  try {
    const response = await api('/plan', 'POST', {question: $('question').value});
    applyPlan(response.plan);
    $('planNote').textContent = response.note + ` 前期：${response.previousStart} 至 ${response.previousEnd}`;
    $('mode').textContent = response.plannerMode;
    notice('计划已生成，请核对后点击“执行分析”');
  } catch (error) { notice(error.message); }
  finally { $('generate').disabled = false; }
}
async function execute() {
  $('execute').disabled = true;
  $('execute').textContent = '正在计算与生成解释…';
  try {
    const report = await api('/reports', 'POST', {plan: collectPlan(), question: $('question').value});
    render(report);
    await history();
  } catch (error) { notice(error.message); }
  finally { $('execute').disabled = false; $('execute').textContent = '执行分析'; }
}
function format(value, unit = '') {
  return value === null || value === undefined ? '不可计算' : Number(value).toLocaleString('zh-CN', {maximumFractionDigits: 2}) + unit;
}
function table(groups, container, drilldown) {
  container.replaceChildren();
  for (const group of groups) {
    const row = node('tr');
    row.append(node('td', group.name), node('td', format(group.current)), node('td', format(group.previous)), node('td', format(group.contribution)), node('td', format(group.contributionPercent, '%')));
    if (drilldown) {
      const cell = node('td'), button = node('button', '筛选重查', 'secondary');
      button.onclick = () => {
        const plan = {...state.report.analysis.plan};
        plan[plan.dimension] = group.name;
        plan.dimension = plan.dimension === 'region' ? 'category' : plan.dimension === 'category' ? 'channel' : 'region';
        applyPlan(plan);
        notice('已加入“' + group.name + '”筛选，请核对计划后执行分析');
      };
      cell.append(button); row.append(cell);
    }
    container.append(row);
  }
  if (!groups.length) {
    const row = node('tr'), cell = node('td', '无匹配分类记录');
    cell.colSpan = drilldown ? 6 : 5; row.append(cell); container.append(row);
  }
}
function render(report) {
  state.report = report;
  const c = report.analysis, metric = c.metric;
  $('empty').hidden = true; $('report').hidden = false;
  $('reportTitle').textContent = metric.label + ' · ' + state.config.dimensions[c.plan.dimension] + '拆解';
  $('period').textContent = `${c.plan.startDate} 至 ${c.plan.endDate}　对比　${c.previousStart} 至 ${c.previousEnd}`;
  $('current').textContent = format(c.current.value, metric.unit);
  $('previous').textContent = format(c.previous.value, metric.unit);
  $('difference').textContent = format(c.difference, metric.rate ? '个百分点' : metric.unit);
  $('relative').textContent = format(c.relativeChangePercent, '%');
  const comparable = c.current.rows > 0 && c.previous.rows > 0 && c.difference !== null;
  $('anomaly').textContent = !comparable ? '数据不足，不判定' : c.anomaly ? '触及波动阈值' : '未触及波动阈值';
  $('anomaly').className = 'badge' + (c.anomaly ? ' alert' : '');
  $('reportMeta').textContent = `数据版本 ${c.dataset.version} ｜ 更新时间 ${c.dataset.updatedAt} ｜ ${report.cacheHit ? '缓存命中' : '数据库计算'} ｜ 本次聚合SQL ${report.sqlCount} 条 ｜ 耗时 ${report.durationMs}ms`;
  $('reportScope').textContent = '授权区域：' + report.scope.regions.join('、') + '；筛选：' + ['region','category','channel','status'].map(k => state.config.dimensions[k] + '=' + (c.plan[k] || '全部授权值')).join('，');
  $('breakdownTitle').textContent = state.config.dimensions[c.plan.dimension] + '变化贡献';
  $('contributionNote').textContent = (metric.rate ? '贡献单位为百分点；按组分子占总体分母的变化计算，不是组内比例变化。' : '各组变化贡献之和等于总体增减金额/数量。') + '贡献率可为负数或超过100%，表示不同组互相抵消；总变化为0时不可计算。';
  table(c.primary, $('groups'), true);
  $('secondaryTitle').textContent = state.config.dimensions[c.secondaryDimension] + '独立拆解（相同筛选范围）';
  const secondTable = node('table'), head = node('thead'), header = node('tr'), body = node('tbody');
  for (const text of ['分类','当前','前期','总体变化贡献','贡献率']) header.append(node('th', text));
  head.append(header); secondTable.append(head, body); table(c.secondary, body, false); $('secondary').replaceChildren(secondTable);
  $('findings').replaceChildren(...c.findings.map(f => node('p', f)), node('p', c.anomalyRule));
  $('narrative').replaceChildren(node('p', report.narrative.summary));
  for (const hypothesis of report.narrative.hypotheses) {
    $('narrative').append(node('h3', '待验证解释 · ' + hypothesis.evidenceIds.join(' / ')), node('p', hypothesis.text), node('p', '验证方式：' + hypothesis.validation));
  }
  $('narratorMode').textContent = report.narratorMode + '。模型解释不替代计算事实或业务核验。';
  $('evidence').replaceChildren();
  for (const evidence of c.evidence) {
    const details = node('details'); details.id = evidence.id;
    details.append(node('summary', evidence.id + ' · ' + evidence.title));
    for (const query of evidence.queries) details.append(node('pre', query.sql + '\n\n参数：' + JSON.stringify(query.parameters) + '\n\n聚合结果：\n' + JSON.stringify(query.rows, null, 2)));
    $('evidence').append(details);
  }
  charts(c);
}
function charts(core) {
  state.charts.forEach(chart => chart.dispose()); state.charts = [];
  if (!window.echarts) { notice('图表库加载失败，仍可查看下方计算明细和导出报告'); return; }
  const trend = echarts.init($('trendChart'));
  trend.setOption({color: ['#188c8e', '#a4b5c6'], tooltip: {trigger:'axis', renderMode:'richText'}, legend:{data:['当前周期','前一等长周期']}, grid:{left:65,right:25,bottom:40,top:45}, xAxis:{type:'category',data:core.trend.map(t=>t.currentDate.slice(5))}, yAxis:{type:'value',name:core.metric.unit}, series:[{name:'当前周期',type:'line',data:core.trend.map(t=>t.current),connectNulls:false,symbolSize:6},{name:'前一等长周期',type:'line',data:core.trend.map(t=>t.previous),connectNulls:false,lineStyle:{type:'dashed'},symbolSize:5}]});
  const bars = echarts.init($('contributionChart'));
  bars.setOption({tooltip:{trigger:'axis',renderMode:'richText'},grid:{left:70,right:25,bottom:40,top:25},xAxis:{type:'category',data:core.primary.map(g=>g.name)},yAxis:{type:'value',name:core.metric.rate?'百分点':core.metric.unit},series:[{type:'bar',barMaxWidth:55,data:core.primary.map(g=>({value:g.contribution,itemStyle:{color:g.contribution<0?'#d4915c':'#188c8e'}}))}]});
  state.charts = [trend,bars];
}
async function history() {
  const list = await api('/reports');
  $('history').replaceChildren(new Option('选择已保存报告',''), ...list.map(r => new Option(r.createdAt.slice(0,16) + ' · ' + r.question, r.id)));
  if (state.report) $('history').value = state.report.id;
}
async function enter() {
  state.config = await api('/config'); const c = state.config;
  $('loginPanel').hidden = true; $('workspace').hidden = false;
  $('account').replaceChildren(node('span', c.username + ' · ' + c.scope.regions.join('、')));
  const logout = node('button','退出','secondary');
  logout.onclick = async () => { try { await api('/logout','POST'); location.reload(); } catch(e) { notice(e.message); } };
  $('account').append(logout);
  $('datasetInfo').textContent = `演示样本 ${c.dataset.startDate} 至 ${c.dataset.endDate} · 相对时间以样本末日为准`;
  $('mode').textContent = c.plannerMode;
  options('metric',c.metrics); options('dimension',Object.entries(c.dimensions).map(([key,label])=>({key,label})));
  options('region',c.scope.regions,'全部授权区域'); options('category',c.categories,'全部品类'); options('channel',c.channels,'全部渠道');options('status',c.statuses.map(key=>({key,label:({PENDING:'待支付',PAID:'已支付待履约',CANCELLED:'已取消',FULFILLED:'已履约'})[key]})),'全部订单状态');
  const end = new Date(c.dataset.endDate+'T00:00:00Z'); const start = new Date(end); start.setUTCDate(start.getUTCDate()-6);
  applyPlan({metric:'net_receipts',dimension:'region',startDate:start.toISOString().slice(0,10),endDate:c.dataset.endDate});
  $('adminArea').hidden = !c.admin; await history();
}
$('loginForm').onsubmit = async event => {
  event.preventDefault();
  try {
    await csrf();
    const body = new URLSearchParams({username:$('username').value,password:$('password').value,[state.csrf.parameterName]:state.csrf.token});
    const response = await fetch('/api/login',{method:'POST',body});
    if (!response.ok) throw Error('账号或密码错误');
    await csrf(); await enter();
  } catch (error) { notice(error.message); }
};
$('questionForm').onsubmit = event => {event.preventDefault();generate();};
$('planForm').onsubmit = event => {event.preventDefault();execute();};
$('metric').onchange = definition;
$('history').onchange = async () => {if (!$('history').value) return;try {const r=await api('/reports/'+$('history').value);$('question').value=r.question;applyPlan(r.analysis.plan);render(r);}catch(e){notice(e.message);}};
$('mdExport').onclick = () => {if(state.report)location.href='/api/reports/'+state.report.id+'/export?format=md';};
$('csvExport').onclick = () => {if(state.report)location.href='/api/reports/'+state.report.id+'/export?format=csv';};
$('auditButton').onclick = async () => {try{$('audit').textContent=JSON.stringify(await api('/admin/audit'),null,2);}catch(e){notice(e.message);}};
for(const button of document.querySelectorAll('[data-question]'))button.onclick=()=>{$('question').value=button.dataset.question;generate();};
window.addEventListener('resize',()=>state.charts.forEach(chart=>chart.resize()));
(async()=>{try{await csrf();await enter();}catch(_){}})();
