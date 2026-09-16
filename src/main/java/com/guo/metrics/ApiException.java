package com.guo.metrics;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

public class ApiException extends RuntimeException {
  final int status;

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  public static ApiException bad(String m) {
    return new ApiException(400, m);
  }

  public static ApiException missing() {
    return new ApiException(404, "报告不存在或无权查看");
  }
}

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("error", "请求字段或格式有误；不接受SQL、额外字段或超长问题"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    LoggerFactory.getLogger(ApiErrors.class).error("Analysis request failed", e);
    return ResponseEntity.internalServerError().body(Map.of("error", "分析失败，请稍后重试或查看服务日志"));
  }
}
