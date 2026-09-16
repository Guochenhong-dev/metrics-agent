package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final ScopePolicy scopes;
  private final SeedData data;
  private final MetricCatalog catalog;
  private final PlannerService planner;
  private final ReportService reports;

  public ApiController(
      ScopePolicy scopes,
      SeedData data,
      MetricCatalog catalog,
      PlannerService planner,
      ReportService reports) {
    this.scopes = scopes;
    this.data = data;
    this.catalog = catalog;
    this.planner = planner;
    this.reports = reports;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(HttpServletRequest req) {
    CsrfToken t = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
    return Map.of(
        "token",
        t.getToken(),
        "headerName",
        t.getHeaderName(),
        "parameterName",
        t.getParameterName());
  }

  @GetMapping("/config")
  public Map<String, Object> config(Authentication a) {
    return Map.of(
        "username",
        a.getName(),
        "scope",
        scopes.scope(a),
        "dataset",
        data.dataset(),
        "metrics",
        catalog.definitions(),
        "dimensions",
        MetricCatalog.DIMENSIONS,
        "categories",
        MetricCatalog.CATEGORIES,
        "channels",
        MetricCatalog.CHANNELS,
        "statuses",
        MetricCatalog.STATUSES,
        "plannerMode",
        planner.mode(),
        "admin",
        a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN")));
  }

  @PostMapping("/plan")
  public PlanPreview plan(Authentication a, @Valid @RequestBody Question q) {
    return planner.preview(q.question(), scopes.scope(a));
  }

  @PostMapping("/reports")
  public Report run(Authentication a, @Valid @RequestBody RunRequest req) {
    return reports.run(req, scopes.scope(a));
  }

  @GetMapping("/reports")
  public List<HistoryItem> history(Authentication a) {
    return reports.history(scopes.scope(a));
  }

  @GetMapping("/reports/{id}")
  public Report load(Authentication a, @PathVariable String id) {
    return reports.load(id, scopes.scope(a));
  }

  @GetMapping("/reports/{id}/export")
  public ResponseEntity<byte[]> export(
      Authentication a, @PathVariable String id, @RequestParam(defaultValue = "md") String format) {
    Report r = reports.load(id, scopes.scope(a));
    String body, type;
    if (format.equals("md")) {
      body = reports.markdown(r);
      type = "text/markdown;charset=UTF-8";
    } else if (format.equals("csv")) {
      body = reports.csv(r);
      type = "text/csv;charset=UTF-8";
    } else throw ApiException.bad("仅支持md或csv导出；JSON可通过报告接口获取");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(type))
        .header(
            "Content-Disposition",
            "attachment; filename=\"metrics-report-" + r.id() + "." + format + "\"")
        .body(body.getBytes(StandardCharsets.UTF_8));
  }

  @GetMapping("/admin/audit")
  public List<Map<String, Object>> audits() {
    return reports.audits();
  }
}
