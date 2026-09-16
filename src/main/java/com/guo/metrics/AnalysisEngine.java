package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AnalysisEngine {
  private final QueryEngine queries;
  private final PlanValidator validator;
  private final SeedData data;
  private final MetricCatalog catalog;
  private static final MathContext MC = MathContext.DECIMAL128;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  public AnalysisEngine(
      QueryEngine queries, PlanValidator validator, SeedData data, MetricCatalog catalog) {
    this.queries = queries;
    this.validator = validator;
    this.data = data;
    this.catalog = catalog;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Core compute(Plan requested, Scope scope) {
    Plan p = validator.validate(requested, scope);
    var def = catalog.get(p.metric()).definition();
    Dataset snapshot = data.dataset();
    int days = (int) ChronoUnit.DAYS.between(p.startDate(), p.endDate()) + 1;
    LocalDate prevStart = p.startDate().minusDays(days), prevEnd = p.startDate().minusDays(1);
    QueryEngine.Budget budget = new QueryEngine.Budget(7);
    var now = queries.query(p, scope, p.startDate(), p.endDate(), null, budget);
    var old = queries.query(p, scope, prevStart, prevEnd, null, budget);
    Stats current = stats(def, now.rows().get(0)), previous = stats(def, old.rows().get(0));
    BigDecimal difference = subtract(current.value(), previous.value());
    BigDecimal relative = relative(current.value(), previous.value());
    var groupNow = queries.query(p, scope, p.startDate(), p.endDate(), p.dimension(), budget);
    var groupOld = queries.query(p, scope, prevStart, prevEnd, p.dimension(), budget);
    List<Group> primary =
        groups(def, groupNow.rows(), groupOld.rows(), current, previous, difference);
    var daily = queries.query(p, scope, prevStart, p.endDate(), "biz_date", budget);
    Map<String, Raw> byDate = map(daily.rows());
    List<Trend> trend = new ArrayList<>();
    for (int i = 0; i < days; i++) {
      LocalDate a = p.startDate().plusDays(i), b = prevStart.plusDays(i);
      trend.add(
          new Trend(
              a,
              b,
              stats(def, byDate.getOrDefault(a.toString(), QueryEngine.empty(a.toString())))
                  .value(),
              stats(def, byDate.getOrDefault(b.toString(), QueryEngine.empty(b.toString())))
                  .value()));
    }
    // 第二个维度是同一筛选范围的独立拆解，不把跨维度相关性当成联合因果。
    String second = p.dimension().equals("category") ? "channel" : "category";
    var secondNow = queries.query(p, scope, p.startDate(), p.endDate(), second, budget);
    var secondOld = queries.query(p, scope, prevStart, prevEnd, second, budget);
    List<Group> secondary =
        groups(def, secondNow.rows(), secondOld.rows(), current, previous, difference);
    boolean comparable = current.rows() > 0 && previous.rows() > 0 && difference != null;
    boolean anomaly =
        comparable
            && (def.rate()
                ? difference.abs().compareTo(BigDecimal.valueOf(5)) >= 0
                : relative != null && relative.abs().compareTo(BigDecimal.valueOf(20)) >= 0);
    String rule = def.rate() ? "绝对变化达到5个百分点时提示波动；不是显著性检验" : "相对变化绝对值达到20%时提示波动；基期为0或无记录时不判定";
    List<Evidence> evidence =
        List.of(
            new Evidence("E1", "当前周期与前一等长周期汇总", List.of(now, old)),
            new Evidence(
                "E2",
                MetricCatalog.DIMENSIONS.get(p.dimension()) + "变化拆解",
                List.of(groupNow, groupOld)),
            new Evidence("E3", "按日趋势对照", List.of(daily)),
            new Evidence(
                "E4",
                MetricCatalog.DIMENSIONS.get(second) + "独立拆解",
                List.of(secondNow, secondOld)));
    List<String> findings = new ArrayList<>();
    if (current.rows() == 0) findings.add("[E1] 当前周期没有匹配记录，不判定经营异常；比例指标显示为不可计算。");
    else
      findings.add(
          "[E1] "
              + def.label()
              + "：当前 "
              + display(current.value())
              + def.unit()
              + "，前期 "
              + display(previous.value())
              + def.unit()
              + "，变化 "
              + display(difference)
              + (def.rate() ? "个百分点" : def.unit())
              + "；相对变化 "
              + display(relative)
              + "%。");
    if (previous.value() != null && previous.value().signum() == 0)
      findings.add("[E1] 基期数值为0，不计算相对增减幅度，不能显示为增长100%。");
    if (!primary.isEmpty()) {
      Group g = primary.get(0);
      findings.add(
          "[E2] 按变化贡献绝对值排序，"
              + g.name()
              + "对总体变化的贡献为 "
              + display(g.contribution())
              + (def.rate() ? "个百分点" : def.unit())
              + "。这表示统计变化来源，不证明业务因果。");
    }
    if (def.rate()) findings.add("[E2] 比例指标贡献按各组分子占总体分母的变化计算；不能把组内比例的增减直接相加。");
    findings.add("[E4] 第二维度为同一范围的独立拆解，不是与第一维度交叉分析；可在计划中加筛选后继续下钻。");
    findings.add(def.caveat());
    return new Core(
        p,
        snapshot,
        def,
        prevStart,
        prevEnd,
        current,
        previous,
        difference,
        relative,
        anomaly,
        rule,
        primary,
        secondary,
        second,
        List.copyOf(trend),
        evidence,
        List.copyOf(findings),
        budget.used());
  }

  static Stats stats(MetricCatalog.Definition def, Raw raw) {
    BigDecimal value;
    if (def.rate())
      value =
          raw.denominator().signum() == 0
              ? null
              : raw.numerator().multiply(HUNDRED).divide(raw.denominator(), MC);
    else value = def.unit().equals("元") ? raw.numerator().divide(HUNDRED, MC) : raw.numerator();
    return new Stats(value, raw.numerator(), raw.denominator(), raw.rows());
  }

  static BigDecimal subtract(BigDecimal a, BigDecimal b) {
    return a == null || b == null ? null : a.subtract(b);
  }

  static BigDecimal relative(BigDecimal a, BigDecimal b) {
    return a == null || b == null || b.signum() == 0
        ? null
        : a.subtract(b).divide(b.abs(), MC).multiply(HUNDRED);
  }

  static String display(BigDecimal n) {
    return n == null ? "不可计算" : n.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  static Map<String, Raw> map(List<Raw> rows) {
    Map<String, Raw> m = new TreeMap<>();
    rows.forEach(r -> m.put(r.group(), r));
    return m;
  }

  static List<Group> groups(
      MetricCatalog.Definition def,
      List<Raw> a,
      List<Raw> b,
      Stats totalNow,
      Stats totalOld,
      BigDecimal totalDifference) {
    Map<String, Raw> now = map(a), old = map(b);
    Set<String> names = new TreeSet<>(now.keySet());
    names.addAll(old.keySet());
    List<Group> rows = new ArrayList<>();
    for (String name : names) {
      Raw x = now.getOrDefault(name, QueryEngine.empty(name)),
          y = old.getOrDefault(name, QueryEngine.empty(name));
      Stats sx = stats(def, x), sy = stats(def, y);
      BigDecimal contribution;
      if (def.rate())
        contribution =
            totalNow.denominator().signum() == 0 || totalOld.denominator().signum() == 0
                ? null
                : x.numerator()
                    .divide(totalNow.denominator(), MC)
                    .subtract(y.numerator().divide(totalOld.denominator(), MC))
                    .multiply(HUNDRED);
      else contribution = sx.value().subtract(sy.value());
      BigDecimal percent =
          contribution == null || totalDifference == null || totalDifference.signum() == 0
              ? null
              : contribution.divide(totalDifference, MC).multiply(HUNDRED);
      rows.add(
          new Group(
              name,
              sx.value(),
              sy.value(),
              subtract(sx.value(), sy.value()),
              contribution,
              percent));
    }
    rows.sort(
        Comparator.comparing(
                (Group g) -> g.contribution() == null ? BigDecimal.ZERO : g.contribution().abs())
            .reversed()
            .thenComparing(Group::name));
    return List.copyOf(rows);
  }
}
