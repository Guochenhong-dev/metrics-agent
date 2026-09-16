#!/usr/bin/env python3
"""启动Java应用后运行此脚本，验证真实HTTP分析链路。只需Python标准库。"""
import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal
from pathlib import Path

BASE = os.getenv('METRICS_URL', 'http://127.0.0.1:8082').rstrip('/')
PASSWORD = os.getenv('METRICS_PASSWORD', 'Demo123!')

class Client:
    def __init__(self, username):
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.get('/csrf')
        body = urllib.parse.urlencode({'username': username, 'password': PASSWORD,
                                     self.csrf['parameterName']: self.csrf['token']}).encode()
        self.http.open(urllib.request.Request(BASE + '/api/login', data=body), timeout=15).read()
        self.csrf = self.get('/csrf')

    def raw(self, path):
        with self.http.open(BASE + '/api' + path, timeout=30) as response:
            return response.read().decode('utf-8')

    def get(self, path):
        return json.loads(self.raw(path))

    def post(self, path, body):
        headers = {'Content-Type': 'application/json', self.csrf['headerName']: self.csrf['token']}
        request = urllib.request.Request(BASE + '/api' + path, data=json.dumps(body).encode(), headers=headers)
        with self.http.open(request, timeout=240) as response:
            return json.loads(response.read())


def main():
    client = Client('demo')
    config = client.get('/config')
    assert len(config['metrics']) == 6
    preview = client.post('/plan', {'question': '最近7天净实收为什么下降，按区域分析'})
    plan = preview['plan']
    assert plan['metric'] == 'net_receipts'
    report = client.post('/reports', {'plan': plan, 'question': '最近7天净实收为什么下降，按区域分析'})
    c = report['analysis']
    assert len(c['evidence']) == 4 and len(c['trend']) == 7
    total = sum(Decimal(str(g['contribution'])) for g in c['primary'])
    assert abs(total - Decimal(str(c['difference']))) < Decimal('0.000001')
    paid = client.post('/reports', {'plan': {**plan, 'metric': 'paid_amount'}, 'question': '支付额'})
    refund = client.post('/reports', {'plan': {**plan, 'metric': 'refund_amount'}, 'question': '退款额'})
    assert abs(Decimal(str(c['current']['value'])) - Decimal(str(paid['analysis']['current']['value'])) + Decimal(str(refund['analysis']['current']['value']))) < Decimal('0.000001')
    again = client.post('/reports', {'plan': plan, 'question': '再次分析'})
    assert again['cacheHit'] and again['sqlCount'] == 0
    assert client.get('/reports/' + report['id'])['id'] == report['id']
    markdown = client.raw('/reports/' + report['id'] + '/export?format=md')
    csv = client.raw('/reports/' + report['id'] + '/export?format=csv')
    assert '查询依据' in markdown and 'E1' in markdown and '待验证' in markdown
    assert csv.startswith('\ufeff分类')
    east = Client('east')
    scoped = east.post('/reports', {'plan': plan, 'question': '华东经营指标'})
    assert all(g['name'] == '华东' for g in scoped['analysis']['primary'])
    for path in ['/reports/' + report['id'], '/reports/' + report['id'] + '/export?format=csv']:
        try:
            east.get(path)
            raise AssertionError('报告归属隔离失败')
        except urllib.error.HTTPError as error:
            assert error.code == 404
    try:
        east.post('/reports', {'plan': {**plan, 'region': '华北'}, 'question': '越权查询'})
        raise AssertionError('区域权限隔离失败')
    except urllib.error.HTTPError as error:
        assert error.code == 403
    if os.getenv('WRITE_SAMPLE') == 'true':
        docs = Path(__file__).resolve().parents[1] / 'docs'
        (docs / '样例分析报告.md').write_text(markdown, encoding='utf-8')
        (docs / '样例分析结果.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS: 登录/CSRF、计划、完整分析、财务口径对账、贡献对账、缓存、报告保存、导出、区域及报告权限')
    print('报告ID：', report['id'])
    print('解释模式：', report['narratorMode'])
    print('净实收：', c['current']['value'], '；前期：', c['previous']['value'])

if __name__ == '__main__':
    main()
