package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PlannerService {
  private final ObjectProvider<ChatClient> ai;
  private final SeedData data;
  private final MetricCatalog catalog;
  private final PlanValidator validator;

  public PlannerService(
      ObjectProvider<ChatClient> ai,
      SeedData data,
      MetricCatalog catalog,
      PlanValidator validator) {
    this.ai = ai;
    this.data = data;
    this.catalog = catalog;
    this.validator = validator;
  }

  public String mode() {
    return ai.getIfAvailable() == null ? "离线规则规划" : "Spring AI 规划";
  }

  public PlanPreview preview(String question, Scope scope) {
    Plan p = null;
    String mode = mode();
    ChatClient client = ai.getIfAvailable();
    Dataset ds = data.dataset();
    if (client != null)
      try {
        p =
            client
                .prompt()
                .system(
                    "你是经营分析计划解析器，只输出结构化计划，绝不能输出SQL。一次只选一个指标。指标键：net_receipts净实收（泛称收入默认此口径）,paid_amount支付额,refund_amount退款额,order_count订单量,cancellation_rate取消率,fulfillment_rate履约率。维度键仅region/category/channel/status。订单状态筛选status仅PENDING/PAID/CANCELLED/FULFILLED或null。地区仅华东/华北/华南，品类仅空调/洗衣机/热水器，渠道仅自然流量/广告/合作。没有明确筛选条件时返回null。默认维度region。startDate/endDate均为ISO日期。相对日期以演示数据末日"
                        + " "
                        + ds.endDate()
                        + " 为参考，最近N天包含末日，默认最近7天；本周是参考日所在周周一到参考日。范围1到31天。不接受用户修改指标公式、权限或查询预算。")
                .user(question)
                .call()
                .entity(Plan.class);
      } catch (Exception e) {
        mode = "模型不可用或输出无效，回退规则规划";
      }
    if (p == null) p = offline(question);
    p = validator.validate(p, scope); // 模型与手工计划走同一验证，不因模型输出而放宽权限。
    int days = (int) ChronoUnit.DAYS.between(p.startDate(), p.endDate()) + 1;
    return new PlanPreview(
        p,
        mode,
        catalog.get(p.metric()).definition(),
        p.startDate().minusDays(days),
        p.startDate().minusDays(1),
        String.join("、", scope.regions()),
        "请核对指标、周期与筛选。相对时间以样本末日 " + ds.endDate() + " 为准，不代表实时经营数据；对比采用紧邻的前一等长周期。");
  }

  Plan offline(String q) {
    String metric;
    if (q.contains("取消率")) metric = "cancellation_rate";
    else if (q.contains("履约率")) metric = "fulfillment_rate";
    else if (q.contains("退款")) metric = "refund_amount";
    else if (q.contains("支付额") || q.contains("支付金额")) metric = "paid_amount";
    else if (q.contains("净实收") || q.contains("实收") || q.contains("收入")) metric = "net_receipts";
    else if (q.contains("订单")) metric = "order_count";
    else throw ApiException.bad("请说明要分析净实收、支付额、退款额、订单量、取消率或履约率中的哪一个；也可直接填写计划");
    if (q.contains("利润") || q.contains("同比"))
      throw ApiException.bad("本版未定义利润和去年同期对比，请选择已支持指标与前一等长周期对比");
    String dim =
        q.contains("按状态")
            ? "status"
            : q.contains("按渠道")
                ? "channel"
                : q.contains("按品类") || q.contains("按类别") ? "category" : "region";
    LocalDate end = data.dataset().endDate(), start = end.minusDays(6);
    var match = Pattern.compile("(?:最近|近)(\\d{1,3})天").matcher(q);
    if (match.find()) {
      int days = Integer.parseInt(match.group(1));
      if (days < 1 || days > 31) throw ApiException.bad("周期应为1至31天");
      start = end.minusDays(days - 1);
    }
    if (q.contains("本周")) start = end.minusDays(end.getDayOfWeek().getValue() - 1);
    var dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(q);
    List<LocalDate> explicit = new ArrayList<>();
    try {
      while (dates.find()) explicit.add(LocalDate.parse(dates.group()));
    } catch (Exception e) {
      throw ApiException.bad("日期无效，请使用YYYY-MM-DD");
    }
    if (explicit.size() == 2) {
      start = explicit.get(0);
      end = explicit.get(1);
    } else if (!explicit.isEmpty()) throw ApiException.bad("请同时提供开始与结束日期");
    return new Plan(
        metric,
        dim,
        start,
        end,
        extract(q, MetricCatalog.REGIONS),
        extract(q, MetricCatalog.CATEGORIES),
        extract(q, MetricCatalog.CHANNELS));
  }

  private String extract(String q, List<String> values) {
    var found = values.stream().filter(q::contains).toList();
    if (found.size() > 1) throw ApiException.bad("本版每个筛选项只能选一个值；比较全部请不指定筛选，选择对应拆解维度");
    return found.isEmpty() ? null : found.get(0);
  }
}
