package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final PlanValidator validator;
  private final AnalysisEngine engine;
  private final ResultCache cache;
  private final SeedData data;
  private final NarrativeService narrator;

  public ReportService(
      JdbcTemplate db,
      ObjectMapper json,
      PlanValidator validator,
      AnalysisEngine engine,
      ResultCache cache,
      SeedData data,
      NarrativeService narrator) {
    this.db = db;
    this.json = json;
    this.validator = validator;
    this.engine = engine;
    this.cache = cache;
    this.data = data;
    this.narrator = narrator;
  }

  public Report run(RunRequest request, Scope scope) {
    long start = System.nanoTime();
    String id = UUID.randomUUID().toString(), outcome = "FAILED";
    int sqlCount = -1;
    try {
      Plan plan = validator.validate(request.plan(), scope);
      Dataset ds = data.dataset();
      Cached cached = cache.get(plan, scope, ds.version(), () -> engine.compute(plan, scope));
      sqlCount = cached.hit() ? 0 : cached.core().plannedSqlCount();
      var interpretation = narrator.explain(cached.core());
      Report report =
          new Report(
              id,
              scope.owner(),
              scope,
              request.question(),
              Instant.now().toString(),
              cached.core(),
              interpretation.narrative(),
              interpretation.mode(),
              cached.hit(),
              sqlCount,
              (System.nanoTime() - start) / 1_000_000);
      db.update(
          "INSERT INTO analysis_reports VALUES (?,?,?,?,?,?)",
          id,
          scope.owner(),
          request.question(),
          ds.version(),
          report.createdAt(),
          encode(report));
      outcome = "OK";
      return report;
    } finally {
      audit(id, scope.owner(), outcome, (System.nanoTime() - start) / 1_000_000, sqlCount);
    }
  }

  public Report load(String id, Scope scope) {
    var result =
        db.queryForList(
            "SELECT report_json FROM analysis_reports WHERE id=? AND owner_name=?",
            String.class,
            id,
            scope.owner());
    if (result.isEmpty()) throw ApiException.missing();
    Report r = decode(result.get(0));
    if (!scope.regions().containsAll(r.scope().regions()))
      throw new ApiException(403, "历史报告范围超过当前数据权限");
    return r;
  }

  public List<HistoryItem> history(Scope scope) {
    return db.query(
        "SELECT id,question_text,created_at,data_version FROM analysis_reports WHERE owner_name=?"
            + " ORDER BY created_at DESC LIMIT 30",
        (r, n) ->
            new HistoryItem(
                r.getString("id"),
                r.getString("question_text"),
                r.getString("created_at"),
                r.getString("data_version")),
        scope.owner());
  }

  private String encode(Report r) {
    try {
      return json.writeValueAsString(r);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Report decode(String r) {
    try {
      return json.readValue(r, Report.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void audit(String id, String owner, String outcome, long ms, int count) {
    try {
      db.update(
          "INSERT INTO analysis_audit VALUES (?,?,?,?,?,?,?)",
          id,
          owner,
          "ANALYZE",
          outcome,
          ms,
          count,
          Instant.now().toString());
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Audit write failed: {}", id);
    }
  }

  public List<Map<String, Object>> audits() {
    return db.queryForList("SELECT * FROM analysis_audit ORDER BY created_at DESC LIMIT 100");
  }

  public String markdown(Report r) {
    Core c = r.analysis();
    StringBuilder b = new StringBuilder("# 经营指标分析报告\n\n");
    b.append("问题：")
        .append(safe(r.question()))
        .append("\n\n指标：")
        .append(c.metric().label())
        .append("\n\n口径：")
        .append(c.metric().formula())
        .append("\n\n时间字段：")
        .append(c.metric().timeBasis());
    b.append("\n\n当前周期：")
        .append(c.plan().startDate())
        .append(" 至 ")
        .append(c.plan().endDate())
        .append("；前期：")
        .append(c.previousStart())
        .append(" 至 ")
        .append(c.previousEnd());
    b.append("\n\n权限区域：")
        .append(String.join("、", r.scope().regions()))
        .append("；数据版本：")
        .append(c.dataset().version())
        .append("；数据更新时间：")
        .append(c.dataset().updatedAt())
        .append("；报告生成：")
        .append(r.createdAt());
    b.append("\n\n筛选：区域=")
        .append(c.plan().region())
        .append("；品类=")
        .append(c.plan().category())
        .append("；渠道=")
        .append(c.plan().channel())
        .append("；订单状态=")
        .append(c.plan().status())
        .append("（null表示不额外筛选）");
    b.append("\n\n## 已计算发现\n\n");
    for (String f : c.findings()) b.append("- ").append(f).append("\n");
    b.append("\n## 相关解释与待验证假设\n\n").append(safe(r.narrative().summary())).append("\n\n");
    for (Hypothesis h : r.narrative().hypotheses())
      b.append("- 待验证：")
          .append(safe(h.text()))
          .append("；证据 ")
          .append(h.evidenceIds())
          .append("；验证方式：")
          .append(safe(h.validation()))
          .append("\n");
    b.append("\n## 主维度变化\n\n|分类|当前|前期|变化贡献|贡献率%|\n|---|---:|---:|---:|---:|\n");
    for (Group g : c.primary())
      b.append("|")
          .append(g.name())
          .append("|")
          .append(AnalysisEngine.display(g.current()))
          .append("|")
          .append(AnalysisEngine.display(g.previous()))
          .append("|")
          .append(AnalysisEngine.display(g.contribution()))
          .append("|")
          .append(AnalysisEngine.display(g.contributionPercent()))
          .append("|\n");
    b.append("\n## 查询依据\n\n");
    for (Evidence e : c.evidence()) {
      b.append("### ").append(e.id()).append(" ").append(e.title()).append("\n\n");
      for (QueryProof q : e.queries())
        b.append("```sql\n")
            .append(q.sql())
            .append("\n```\n\n参数：")
            .append(q.parameters())
            .append("\n\n聚合结果：")
            .append(q.rows())
            .append("\n\n");
    }
    b.append("模式：").append(r.narratorMode()).append("；阈值规则：").append(c.anomalyRule()).append("\n");
    return b.toString();
  }

  public String csv(Report r) {
    StringBuilder b = new StringBuilder("\uFEFF分类,当前值,前期值,组内变化,总体变化贡献,贡献率百分比\r\n");
    for (Group g : r.analysis().primary())
      b.append(
              String.join(
                  ",",
                  List.of(
                      cell(g.name()),
                      cell(AnalysisEngine.display(g.current())),
                      cell(AnalysisEngine.display(g.previous())),
                      cell(AnalysisEngine.display(g.difference())),
                      cell(AnalysisEngine.display(g.contribution())),
                      cell(AnalysisEngine.display(g.contributionPercent())))))
          .append("\r\n");
    return b.toString();
  }

  static String cell(String s) {
    if (s.matches("^[=+@-].*") && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  static String safe(String s) {
    return s.replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ").replace("\r", " ");
  }
}
