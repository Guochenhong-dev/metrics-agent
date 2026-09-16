package com.guo.metrics;

import java.util.*;
import org.springframework.stereotype.Component;

/** 这里是指标口径的唯一入口。模型只选择键，不能改公式、表名或时间字段。 */
@Component
public class MetricCatalog {
  public record Definition(
      String key,
      String label,
      String unit,
      boolean rate,
      String formula,
      String timeBasis,
      String caveat) {}

  public record SqlMetric(
      Definition definition, String view, String numerator, String denominator) {}

  private static final Map<String, SqlMetric> METRICS = new LinkedHashMap<>();

  static {
    add(
        "net_receipts",
        "净实收",
        "元",
        false,
        "支付额－退款额",
        "支付按支付日、退款按退款日",
        "现金流口径，不是会计确认收入。退款可来自更早支付的订单，净实收可能为负。",
        "v_finance_events",
        "paid_cents - refund_cents",
        "0");
    add(
        "paid_amount",
        "支付额",
        "元",
        false,
        "支付成功金额之和",
        "paid_on 支付日期",
        "后续退款不回写历史支付额，退款在退款日期单独统计。",
        "v_finance_events",
        "paid_cents",
        "0");
    add(
        "refund_amount",
        "退款额",
        "元",
        false,
        "实际退款金额之和",
        "refunded_on 退款日期",
        "退款关联原订单的区域、品类和渠道。",
        "v_finance_events",
        "refund_cents",
        "0");
    add(
        "order_count",
        "订单量",
        "单",
        false,
        "创建订单总数",
        "created_on 创建日期",
        "包含已取消和待支付订单，不等同于支付订单量。",
        "v_order_cohorts",
        "order_count",
        "0");
    add(
        "cancellation_rate",
        "取消率",
        "%",
        true,
        "同期创建且最终取消订单数 ÷ 同期创建订单数 × 100",
        "created_on 创建日期；状态截至数据快照",
        "零订单时比例不可计算。快照中的最终状态可能随数据更新变化。",
        "v_order_cohorts",
        "cancelled_count",
        "order_count");
    add(
        "fulfillment_rate",
        "履约率",
        "%",
        true,
        "同期创建且已履约订单数 ÷ 同期创建且已支付订单数 × 100",
        "created_on 创建日期；状态截至数据快照",
        "新订单可能尚未履约，需要留意观察期不足；已支付后退款的订单仍在分母内。",
        "v_order_cohorts",
        "fulfilled_count",
        "paid_count");
  }

  private static void add(
      String k,
      String label,
      String unit,
      boolean rate,
      String formula,
      String basis,
      String caveat,
      String view,
      String n,
      String d) {
    METRICS.put(
        k, new SqlMetric(new Definition(k, label, unit, rate, formula, basis, caveat), view, n, d));
  }

  public SqlMetric get(String key) {
    var m = METRICS.get(key);
    if (m == null) throw ApiException.bad("未知指标，请从指标目录选择");
    return m;
  }

  public List<Definition> definitions() {
    return METRICS.values().stream().map(SqlMetric::definition).toList();
  }

  public static final Map<String, String> DIMENSIONS =
      Map.of("region", "区域", "category", "品类", "channel", "渠道", "status", "订单状态");
  public static final List<String> REGIONS = List.of("华东", "华北", "华南");
  public static final List<String> CATEGORIES = List.of("空调", "洗衣机", "热水器");
  public static final List<String> STATUSES = List.of("PENDING", "PAID", "CANCELLED", "FULFILLED");
  public static final List<String> CHANNELS = List.of("自然流量", "广告", "合作");

  public String dimension(String key) {
    if (!DIMENSIONS.containsKey(key)) throw ApiException.bad("维度不在白名单中");
    return key;
  }
}
