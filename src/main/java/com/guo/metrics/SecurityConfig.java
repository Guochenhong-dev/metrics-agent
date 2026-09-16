package com.guo.metrics;

import static com.guo.metrics.Domain.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(@Value("${app.demo-password}") String pass, PasswordEncoder encoder) {
    return new InMemoryUserDetailsManager(
        User.withUsername("demo").password(encoder.encode(pass)).roles("ANALYST").build(),
        User.withUsername("east").password(encoder.encode(pass)).roles("EAST").build(),
        User.withUsername("admin").password(encoder.encode(pass)).roles("ADMIN").build());
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/index.html",
                        "/style.css",
                        "/app.js",
                        "/vendor/**",
                        "/api/csrf",
                        "/error")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/login")
                    .successHandler(
                        (req, res, auth) -> {
                          res.setContentType("application/json");
                          res.getWriter().write("{\"ok\":true}");
                        })
                    .failureHandler((req, res, e) -> res.sendError(401)))
        .logout(
            l ->
                l.logoutUrl("/api/logout")
                    .logoutSuccessHandler((req, res, a) -> res.setStatus(204)))
        .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
        .build();
  }
}

@Component
class ScopePolicy {
  Scope scope(Authentication auth) {
    boolean east =
        auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_EAST"));
    return new Scope(auth.getName(), east ? java.util.List.of("华东") : MetricCatalog.REGIONS);
  }
}
