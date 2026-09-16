package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 只执行预定义视图的聚合SELECT，绝不执行模型生成的SQL文本。 */
@Service
public class QueryEngine {
  private final JdbcTemplate db;
  private final MetricCatalog catalog;

  public QueryEngine(JdbcTemplate db, MetricCatalog catalog) {
    this.db = db;
    this.catalog = catalog;
  }

  public static class Budget {
    private int used;
    private final int limit;

    public Budget(int limit) {
      this.limit = limit;
    }

    public void consume() {
      if (used >= limit) throw new ApiException(429, "分析查询预算已用完");
      used++;
    }

    public int used() {
      return used;
    }
  }

  public QueryProof query(
      Plan p, Scope scope, LocalDate start, LocalDate end, String group, Budget budget) {
    budget.consume();
    var metric = catalog.get(p.metric());
    String column =
        group == null ? null : group.equals("biz_date") ? "biz_date" : catalog.dimension(group);
    String key = column == null ? "'TOTAL'" : column;
    String sql =
        "SELECT "
            + key
            + " AS group_key, COALESCE(SUM("
            + metric.numerator()
            + "),0) AS num, COALESCE(SUM("
            + metric.denominator()
            + "),0) AS den, COUNT(*) AS row_count FROM "
            + metric.view()
            + " WHERE biz_date >= ? AND biz_date <= ? AND region IN ("
            + String.join(",", Collections.nCopies(scope.regions().size(), "?"))
            + ")";
    List<Object> parameters = new ArrayList<>();
    parameters.add(java.sql.Date.valueOf(start));
    parameters.add(java.sql.Date.valueOf(end));
    parameters.addAll(scope.regions());
    if (p.region() != null) {
      sql += " AND region = ?";
      parameters.add(p.region());
    }
    if (p.category() != null) {
      sql += " AND category = ?";
      parameters.add(p.category());
    }
    if (p.channel() != null) {
      sql += " AND channel = ?";
      parameters.add(p.channel());
    }
    if (p.status() != null) {
      sql += " AND status = ?";
      parameters.add(p.status());
    }
    if (column != null) sql += " GROUP BY " + column + " ORDER BY " + column;
    String fixedSql = sql;
    List<Raw> rows =
        db.query(
            connection -> {
              PreparedStatement ps = connection.prepareStatement(fixedSql);
              ps.setQueryTimeout(3);
              ps.setMaxRows(201);
              for (int i = 0; i < parameters.size(); i++) ps.setObject(i + 1, parameters.get(i));
              return ps;
            },
            (rs, n) ->
                new Raw(
                    rs.getString("group_key"),
                    rs.getBigDecimal("num"),
                    rs.getBigDecimal("den"),
                    rs.getLong("row_count")));
    if (rows.size() > 200) throw ApiException.bad("聚合结果超过200行，请缩小查询范围");
    return new QueryProof(sql, parameters.stream().map(String::valueOf).toList(), rows);
  }

  public static Raw empty(String key) {
    return new Raw(key, BigDecimal.ZERO, BigDecimal.ZERO, 0);
  }
}
