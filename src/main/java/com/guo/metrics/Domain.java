package com.guo.metrics;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class Domain {
  public record Plan(
      String metric,
      String dimension,
      LocalDate startDate,
      LocalDate endDate,
      String region,
      String category,
      String channel,
      String status) {
    public Plan(
        String metric,
        String dimension,
        LocalDate startDate,
        LocalDate endDate,
        String region,
        String category,
        String channel) {
      this(metric, dimension, startDate, endDate, region, category, channel, null);
    }
  }

  public record Scope(String owner, List<String> regions) {}

  public record Dataset(String version, LocalDate startDate, LocalDate endDate, String updatedAt) {}

  public record Question(@NotBlank @Size(max = 1000) String question) {}

  public record RunRequest(@NotNull Plan plan, @NotBlank @Size(max = 1000) String question) {}

  public record PlanPreview(
      Plan plan,
      String plannerMode,
      MetricCatalog.Definition definition,
      LocalDate previousStart,
      LocalDate previousEnd,
      String scope,
      String note) {}

  public record Raw(String group, BigDecimal numerator, BigDecimal denominator, long rows) {}

  public record Stats(BigDecimal value, BigDecimal numerator, BigDecimal denominator, long rows) {}

  public record Group(
      String name,
      BigDecimal current,
      BigDecimal previous,
      BigDecimal difference,
      BigDecimal contribution,
      BigDecimal contributionPercent) {}

  public record Trend(
      LocalDate currentDate, LocalDate previousDate, BigDecimal current, BigDecimal previous) {}

  public record QueryProof(String sql, List<String> parameters, List<Raw> rows) {}

  public record Evidence(String id, String title, List<QueryProof> queries) {}

  public record Core(
      Plan plan,
      Dataset dataset,
      MetricCatalog.Definition metric,
      LocalDate previousStart,
      LocalDate previousEnd,
      Stats current,
      Stats previous,
      BigDecimal difference,
      BigDecimal relativeChangePercent,
      boolean anomaly,
      String anomalyRule,
      List<Group> primary,
      List<Group> secondary,
      String secondaryDimension,
      List<Trend> trend,
      List<Evidence> evidence,
      List<String> findings,
      int plannedSqlCount) {}

  public record Hypothesis(String text, List<String> evidenceIds, String validation) {}

  public record Narrative(String summary, List<Hypothesis> hypotheses) {}

  public record Report(
      String id,
      String owner,
      Scope scope,
      String question,
      String createdAt,
      Core analysis,
      Narrative narrative,
      String narratorMode,
      boolean cacheHit,
      int sqlCount,
      long durationMs) {}

  public record HistoryItem(String id, String question, String createdAt, String dataVersion) {}

  public record Cached(Core core, boolean hit) {}
}
