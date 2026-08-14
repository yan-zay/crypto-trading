package com.tj.crypto.admin.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.application.AuditRecord;
import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.observability.slo.SloName;
import com.tj.crypto.observability.slo.TradingSloService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Secret-free request audit coverage for every admin write, including failures. */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@RequiredArgsConstructor
public class AdminAuditFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE = "auditRequestId";
    public static final String CORRELATION_ID_ATTRIBUTE = "auditCorrelationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TradingSloService sloService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !request.getRequestURI().startsWith("/api/admin/")
                || "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = idOrNew(request.getHeader("X-Request-Id"));
        String correlationId = idOrFallback(request.getHeader("X-Correlation-Id"), requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Correlation-Id", correlationId);
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            appendSafely(request, response, requestId, correlationId, started, failure);
        }
    }

    private void appendSafely(HttpServletRequest request, HttpServletResponse response,
                              String requestId, String correlationId, long started,
                              Throwable failure) {
        try {
            int status = response.getStatus();
            boolean success = failure == null && status < 400;
            String suffix = request.getRequestURI().substring("/api/admin/".length());
            String[] segments = suffix.split("/", 2);
            String resourceType = segments[0].replace('-', '_').toUpperCase(Locale.ROOT);
            String resourceId = segments.length == 2 ? truncate(segments[1], 100) : null;
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            auditService.append(new AuditRecord(requestId, correlationId,
                    "HTTP_" + request.getMethod(), resourceType, resourceId,
                    null, null, null, operator(request), success ? "SUCCESS" : "FAILURE",
                    truncate(request.getRemoteAddr(), 64), latencyMs, new Date(),
                    detail(request.getMethod(), request.getRequestURI(), status,
                            failure == null ? null : failure.getClass().getSimpleName())));
            sloService.record(SloName.AUDIT_APPEND, true, latencyMs);
            if (isPaperOrderCommand(request)) sloService.recordPaperOrder(success, latencyMs);
        } catch (RuntimeException e) {
            // The request may already be committed. The failure is surfaced to logs and metrics,
            // while explicit high-risk operation audits still fail their controller call.
            log.error("Failed to append admin request audit: requestId={}", requestId, e);
            sloService.record(SloName.AUDIT_APPEND, false,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000));
        }
    }

    private boolean isPaperOrderCommand(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "POST".equals(request.getMethod())
                && ("/api/admin/paper-trading/orders".equals(path)
                    || path.matches("/api/admin/paper-trading/orders/[^/]+/cancel"));
    }

    private String detail(String method, String path, int status, String exceptionType) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("method", method);
        value.put("path", path);
        value.put("status", status);
        if (exceptionType != null) value.put("exceptionType", exceptionType);
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"status\":" + status + "}";
        }
    }

    private String operator(HttpServletRequest request) {
        Object user = request.getAttribute("currentUser");
        return user instanceof UserDO current ? current.getUsername() : "anonymous";
    }

    private String idOrNew(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
    }

    private String idOrFallback(String candidate, String fallback) {
        return candidate != null && SAFE_ID.matcher(candidate).matches() ? candidate : fallback;
    }

    private String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
