package com.guo.metrics;

import static com.guo.metrics.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:metrics-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "app.ai.enabled=false",
      "app.redis-enabled=false"
    })
@AutoConfigureMockMvc
class AnalysisIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired AnalysisEngine engine;
  @Autowired ReportService reports;
  @Autowired PlannerService planner;
  @Autowired ResultCache cache;
  @Autowired PlanValidator validator;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  final Scope all = new Scope("demo", MetricCatalog.REGIONS),
      east = new Scope("east", List.of("华东"));

  @BeforeEach
  void fixture() {
    for (String table : List.of("analysis_reports", "analysis_audit", "refunds", "biz_orders"))
      db.update("DELETE FROM " + table);
    cache.clear();
    db.update("UPDATE dataset_meta SET data_version='test-v1' WHERE id=1");
    order("old-e", "华东", "2026-08-18", "FULFILLED", 10000);
    order("old-x", "华东", "2026-08-18", "CANCELLED", 0);
    order("old-n", "华北", "2026-08-18", "FULFILLED", 20000);
    order("now-e", "华东", "2026-08-25", "FULFILLED", 8000);
    order("now-n", "华北", "2026-08-25", "FULFILLED", 20000);
    order("now-x1", "华东", "2026-08-25", "CANCELLED", 0);
    order("now-x2", "华东", "2026-08-25", "CANCELLED", 0);
    order("now-p", "华南", "2026-08-25", "PENDING", 0);
    refund("r-old", "old-e", "2026-08-18", 1000);
    refund("r-new", "old-n", "2026-08-25", 5000);
  }

  void order(String id, String region, String date, String status, long amount) {
    LocalDate d = LocalDate.parse(date);
    db.update(
        "INSERT INTO biz_orders VALUES (?,?,?,?,?,?,?,?)",
        id,
        region,
        "空调",
        "广告",
        d,
        amount == 0 ? null : d,
        status,
        amount);
  }

  void refund(String id, String order, String date, long cents) {
    db.update("INSERT INTO refunds VALUES (?,?,?,?)", id, order, LocalDate.parse(date), cents);
  }

  Plan plan(String metric) {
    return new Plan(
        metric,
        "region",
        LocalDate.parse("2026-08-25"),
        LocalDate.parse("2026-08-31"),
        null,
        null,
        null);
  }

  Core calculate(String metric) {
    return engine.compute(plan(metric), all);
  }

  @Test
  void netReceiptsUsesRefundEventDate() {
    Core c = calculate("net_receipts");
    assertThat(c.current().value()).isEqualByComparingTo("230");
    assertThat(c.previous().value()).isEqualByComparingTo("290");
    assertThat(c.difference()).isEqualByComparingTo("-60");
    assertThat(c.anomaly()).isTrue();
    assertThat(c.plannedSqlCount()).isEqualTo(7);
  }

  @Test
  void paidAndRefundAmountsStaySeparate() {
    assertThat(calculate("paid_amount").current().value()).isEqualByComparingTo("280");
    assertThat(calculate("refund_amount").current().value()).isEqualByComparingTo("50");
  }

  @Test
  void additiveContributionsReconcile() {
    Core c = calculate("net_receipts");
    BigDecimal sum =
        c.primary().stream().map(Group::contribution).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(c.difference());
    assertThat(c.primary().get(0).name()).isEqualTo("华北");
    assertThat(c.primary().get(0).contribution()).isEqualByComparingTo("-50");
  }

  @Test
  void rateContributionUsesOverallDenominator() {
    Core c = calculate("cancellation_rate");
    assertThat(c.current().value()).isEqualByComparingTo("40");
    BigDecimal sum =
        c.primary().stream().map(Group::contribution).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum.subtract(c.difference()).abs()).isLessThan(new BigDecimal("0.000000001"));
    Group g = c.primary().stream().filter(x -> x.name().equals("华东")).findFirst().orElseThrow();
    assertThat(g.difference()).isNotEqualByComparingTo(g.contribution());
    assertThat(c.anomaly()).isTrue();
  }

  @Test
  void zeroTotalChangeDoesNotInventContributionShare() {
    Core c = calculate("fulfillment_rate");
    assertThat(c.difference()).isEqualByComparingTo("0");
    assertThat(c.primary()).allMatch(g -> g.contributionPercent() == null);
  }

  @Test
  void zeroDenominatorIsNull() {
    Plan p =
        new Plan(
            "cancellation_rate",
            "region",
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-07"),
            null,
            null,
            null);
    Core c = engine.compute(p, all);
    assertThat(c.current().value()).isNull();
    assertThat(c.relativeChangePercent()).isNull();
    assertThat(c.anomaly()).isFalse();
  }

  @Test
  void emptyResultDoesNotProduceCause() {
    Plan p =
        new Plan(
            "order_count",
            "region",
            LocalDate.parse("2026-06-01"),
            LocalDate.parse("2026-06-07"),
            null,
            null,
            null);
    Report r = reports.run(new RunRequest(p, "订单量"), all);
    assertThat(r.analysis().current().value()).isEqualByComparingTo("0");
    assertThat(r.narrative().hypotheses()).isEmpty();
  }

  @Test
  void crossMonthRefundAndZeroBaseline() {
    order("july", "华东", "2026-07-29", "FULFILLED", 10000);
    refund("august-refund", "july", "2026-08-01", 2500);
    Plan p =
        new Plan(
            "net_receipts",
            "region",
            LocalDate.parse("2026-08-01"),
            LocalDate.parse("2026-08-01"),
            null,
            null,
            null);
    Core c = engine.compute(p, all);
    assertThat(c.previousStart()).isEqualTo(LocalDate.parse("2026-07-31"));
    assertThat(c.current().value()).isEqualByComparingTo("-25");
    assertThat(c.previous().value()).isEqualByComparingTo("0");
    assertThat(c.relativeChangePercent()).isNull();
  }

  @Test
  void areaPermissionIsAppliedToEveryQuery() {
    Core c = engine.compute(plan("net_receipts"), east);
    assertThat(c.current().value()).isEqualByComparingTo("80");
    assertThat(c.primary()).extracting(Group::name).containsExactly("华东");
    assertThat(c.evidence().stream().flatMap(e -> e.queries().stream()).toList())
        .allMatch(q -> q.parameters().contains("华东") && !q.parameters().contains("华北"));
  }

  @Test
  void forgedRegionAndSqlFragmentsAreRejected() {
    Plan p =
        new Plan(
            "net_receipts",
            "region",
            plan("net_receipts").startDate(),
            plan("net_receipts").endDate(),
            "华北",
            null,
            null);
    assertThatThrownBy(() -> engine.compute(p, east)).isInstanceOf(ApiException.class);
    Plan injection =
        new Plan(
            "net_receipts",
            "region; DROP TABLE biz_orders",
            p.startDate(),
            p.endDate(),
            null,
            null,
            null);
    assertThatThrownBy(() -> engine.compute(injection, all)).isInstanceOf(ApiException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_orders", Integer.class)).isEqualTo(8);
  }

  @Test
  void rangeAndPriorPeriodAreValidated() {
    Plan p =
        new Plan(
            "order_count",
            "region",
            LocalDate.parse("2026-08-01"),
            LocalDate.parse("2026-08-31"),
            null,
            null,
            null);
    assertThat(validator.validate(p, all)).isEqualTo(p);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new Plan(
                        "order_count",
                        "region",
                        LocalDate.parse("2026-07-31"),
                        p.endDate(),
                        null,
                        null,
                        null),
                    all))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    new Plan(
                        "order_count",
                        "region",
                        LocalDate.parse("2026-05-04"),
                        LocalDate.parse("2026-05-05"),
                        null,
                        null,
                        null),
                    all))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void cacheIncludesOwnerPermissionsPlanAndVersion() {
    Plan p = plan("order_count");
    assertThat(cache.key(p, all, "v1"))
        .isNotEqualTo(cache.key(p, east, "v1"))
        .isNotEqualTo(cache.key(p, all, "v2"));
    Report first = reports.run(new RunRequest(p, "订单量"), all),
        second = reports.run(new RunRequest(p, "再看订单量"), all);
    assertThat(first.cacheHit()).isFalse();
    assertThat(second.cacheHit()).isTrue();
    assertThat(second.sqlCount()).isZero();
    db.update("UPDATE dataset_meta SET data_version='test-v2' WHERE id=1");
    assertThat(reports.run(new RunRequest(p, "订单量"), all).cacheHit()).isFalse();
  }

  @Test
  void reportRoundTripAndPermissionDowngrade() {
    Report r = reports.run(new RunRequest(plan("net_receipts"), "收入"), all);
    Report loaded = reports.load(r.id(), all);
    assertThat(loaded.analysis().current().value()).isEqualByComparingTo("230");
    assertThatThrownBy(() -> reports.load(r.id(), east)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> reports.load(r.id(), new Scope("demo", List.of("华东"))))
        .isInstanceOf(ApiException.class);
    assertThat(reports.markdown(r)).contains("E1", "支付按支付日", "查询依据");
    assertThat(reports.csv(r)).startsWith("\uFEFF分类");
  }

  @Test
  void plannerUsesDatasetAnchorAndExplicitDates() {
    PlanPreview p = planner.preview("最近7天净实收为什么下降，按区域分析", all);
    assertThat(p.plan().startDate()).isEqualTo(LocalDate.parse("2026-08-25"));
    PlanPreview cross = planner.preview("2026-07-29至2026-08-04退款额，按品类分析", all);
    assertThat(cross.plan().dimension()).isEqualTo("category");
    assertThat(cross.previousEnd()).isEqualTo(LocalDate.parse("2026-07-28"));
  }

  @Test
  void budgetStopsBeforeEighthQuery() {
    QueryEngine.Budget budget = new QueryEngine.Budget(7);
    for (int i = 0; i < 7; i++) budget.consume();
    assertThatThrownBy(budget::consume).isInstanceOf(ApiException.class);
    assertThat(budget.used()).isEqualTo(7);
  }

  @Test
  void narrativeRejectsInventedEvidenceAndNumbers() {
    Core c = calculate("net_receipts");
    assertThatThrownBy(() -> NarrativeService.validate(new Narrative("下降30%", List.of()), c))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                NarrativeService.validate(
                    new Narrative("相关变化", List.of(new Hypothesis("可能波动", List.of("E99"), "核对数据"))),
                    c))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void apiAuthenticationCsrfAndAdminAreEnforced() throws Exception {
    mvc.perform(get("/api/config")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/plan")
                .with(user("demo"))
                .contentType("application/json")
                .content("{\"question\":\"收入\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/audit").with(user("demo").roles("ANALYST")))
        .andExpect(status().isForbidden());
  }

  @Test
  void apiRejectsUnknownSqlAndCanCreateReport() throws Exception {
    mvc.perform(
            post("/api/reports")
                .with(user("demo").roles("ANALYST"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"plan\":{},\"question\":\"收入\",\"sql\":\"select * from biz_orders\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/reports")
                .with(user("demo").roles("ANALYST"))
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(new RunRequest(plan("net_receipts"), "收入"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.current.value").value(230));
  }

  @Test
  void realPasswordLoginWorks() throws Exception {
    mvc.perform(
            post("/api/login").with(csrf()).param("username", "demo").param("password", "Demo123!"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
    mvc.perform(
            post("/api/login").with(csrf()).param("username", "demo").param("password", "wrong"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void statusBreakdownAndFilterWork() {
    Plan p = plan("order_count");
    Core c =
        engine.compute(
            new Plan(p.metric(), "status", p.startDate(), p.endDate(), null, null, null), all);
    assertThat(c.primary())
        .extracting(Group::name)
        .containsExactlyInAnyOrder("PENDING", "CANCELLED", "FULFILLED");
    Core filtered =
        engine.compute(
            new Plan(
                p.metric(), "region", p.startDate(), p.endDate(), null, null, null, "FULFILLED"),
            all);
    assertThat(filtered.current().value()).isEqualByComparingTo("2");
  }
}
