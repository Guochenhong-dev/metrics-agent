package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeedData implements ApplicationRunner {
  private final JdbcTemplate db;

  public SeedData(JdbcTemplate db) {
    this.db = db;
  }

  public Dataset dataset() {
    return db.queryForObject(
        "SELECT * FROM dataset_meta WHERE id=1",
        (r, n) ->
            new Dataset(
                r.getString("data_version"),
                r.getDate("start_date").toLocalDate(),
                r.getDate("end_date").toLocalDate(),
                r.getString("updated_at")));
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.queryForObject("SELECT COUNT(*) FROM dataset_meta", Integer.class) > 0) {
      org.slf4j.LoggerFactory.getLogger(getClass()).info("DATA_READY");
      return;
    }
    LocalDate end = LocalDate.of(2026, 8, 31), start = end.minusDays(119);
    List<Object[]> orders = new ArrayList<>(), refunds = new ArrayList<>();
    int number = 0;
    for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
      for (String region : MetricCatalog.REGIONS)
        for (String category : MetricCatalog.CATEGORIES)
          for (String channel : MetricCatalog.CHANNELS) {
            boolean dip = !day.isBefore(end.minusDays(6)) && region.equals("华东");
            int count = dip ? (channel.equals("广告") ? 1 : 3) : 6;
            for (int i = 0; i < count; i++) {
              String id = "O" + (++number);
              int h =
                  Math.floorMod(
                      Objects.hash(day.getDayOfWeek().getValue(), region, category, channel, i),
                      100);
              boolean cancelled = h < 12 || (dip && i == 0);
              LocalDate paid = cancelled ? null : day.plusDays(i % 2);
              if (paid != null && paid.isAfter(end)) paid = null;
              long amount =
                  paid == null
                      ? 0
                      : (category.equals("空调") ? 180000 : category.equals("洗衣机") ? 130000 : 90000)
                          + (h % 5) * 10000L;
              if (dip) amount = amount * 9 / 10;
              String status =
                  cancelled
                      ? "CANCELLED"
                      : paid == null
                          ? "PENDING"
                          : paid.plusDays(2).isAfter(end) ? "PAID" : "FULFILLED";
              orders.add(new Object[] {id, region, category, channel, day, paid, status, amount});
              if (paid != null && h % 13 == 0 && !paid.plusDays(3).isAfter(end))
                refunds.add(new Object[] {"R" + number, id, paid.plusDays(3), amount / 2});
            }
          }
    }
    db.batchUpdate(
        "INSERT INTO biz_orders (id,region,category,channel,created_on,paid_on,status,paid_cents)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        orders);
    db.batchUpdate(
        "INSERT INTO refunds (id,order_id,refunded_on,amount_cents) VALUES (?,?,?,?)", refunds);
    db.update(
        "INSERT INTO dataset_meta VALUES (1,?,?,?,?)",
        "demo-v1-20260831",
        start,
        end,
        Instant.now().toString());
    org.slf4j.LoggerFactory.getLogger(getClass()).info("DATA_READY: {} demo orders", orders.size());
  }
}
