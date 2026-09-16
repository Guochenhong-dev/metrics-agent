package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PlanValidator {
  private final MetricCatalog catalog;
  private final SeedData data;

  public PlanValidator(MetricCatalog catalog, SeedData data) {
    this.catalog = catalog;
    this.data = data;
  }

  public Plan validate(Plan p, Scope scope) {
    if (p == null) throw ApiException.bad("请先生成或填写分析计划");
    catalog.get(p.metric());
    catalog.dimension(p.dimension());
    if (p.startDate() == null || p.endDate() == null) throw ApiException.bad("开始与结束日期必填");
    long n = ChronoUnit.DAYS.between(p.startDate(), p.endDate()) + 1;
    if (n < 1 || n > 31) throw ApiException.bad("分析周期应为1至31天");
    Dataset d = data.dataset();
    if (p.endDate().isAfter(d.endDate()) || p.startDate().minusDays(n).isBefore(d.startDate()))
      throw ApiException.bad("当前周期和前一等长周期都必须落在数据范围 " + d.startDate() + " 至 " + d.endDate() + " 内");
    String r = clean(p.region()),
        c = clean(p.category()),
        ch = clean(p.channel()),
        status = clean(p.status());
    check(r, MetricCatalog.REGIONS, "区域");
    check(c, MetricCatalog.CATEGORIES, "品类");
    check(ch, MetricCatalog.CHANNELS, "渠道");
    check(status, MetricCatalog.STATUSES, "订单状态");
    if (scope.regions() == null
        || scope.regions().isEmpty()
        || !MetricCatalog.REGIONS.containsAll(scope.regions()))
      throw new ApiException(403, "无可用数据权限");
    if (r != null && !scope.regions().contains(r)) throw new ApiException(403, "当前账号无权访问该区域");
    return new Plan(p.metric(), p.dimension(), p.startDate(), p.endDate(), r, c, ch, status);
  }

  static String clean(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private void check(String value, List<String> allowed, String field) {
    if (value != null && !allowed.contains(value)) throw ApiException.bad(field + "筛选值不在白名单内");
  }
}
