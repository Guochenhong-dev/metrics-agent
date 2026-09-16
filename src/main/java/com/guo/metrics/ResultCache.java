package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ResultCache {
  private record Entry(long expires, Core core) {}

  private final Map<String, Entry> local = new LinkedHashMap<>(16, .75f, true);
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final boolean enabled;

  public ResultCache(
      ObjectMapper json,
      StringRedisTemplate redis,
      @Value("${app.redis-enabled}") boolean enabled) {
    this.json = json;
    this.redis = redis;
    this.enabled = enabled;
  }

  String key(Plan p, Scope s, String version) {
    try {
      String raw = json.writeValueAsString(List.of(p, s, version));
      return "metrics:v1:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Cached get(Plan p, Scope s, String version, Supplier<Core> compute) {
    String key = key(p, s, version);
    if (enabled)
      try {
        String v = redis.opsForValue().get(key);
        if (v != null) return new Cached(json.readValue(v, Core.class), true);
      } catch (Exception ignored) {
      }
    synchronized (local) {
      Entry e = local.get(key);
      if (e != null && e.expires() > System.currentTimeMillis()) return new Cached(e.core(), true);
      local.remove(key);
    }
    Core result = compute.get();
    Duration ttl = Duration.ofSeconds(result.current().rows() == 0 ? 60 : 300);
    synchronized (local) {
      local.put(key, new Entry(System.currentTimeMillis() + ttl.toMillis(), result));
      while (local.size() > 256) local.remove(local.keySet().iterator().next());
    }
    if (enabled)
      try {
        redis.opsForValue().set(key, json.writeValueAsString(result), ttl);
      } catch (Exception ignored) {
      }
    return new Cached(result, false);
  }

  void clear() {
    synchronized (local) {
      local.clear();
    }
  }
}
