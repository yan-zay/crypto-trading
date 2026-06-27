package com.tj.crypto.admin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.application.AuthService;
import com.tj.crypto.admin.domain.Role;
import com.tj.crypto.admin.domain.UserDO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 认证与授权拦截器。
 * 拦截 /api/admin/* 请求，验证 token 并检查角色权限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 提取 token
        String authHeader = request.getHeader(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少 Authorization header");
            return false;
        }
        String token = authHeader.substring(BEARER_PREFIX.length());

        // 校验 token
        UserDO user;
        try {
            user = authService.validateToken(token);
        } catch (IllegalArgumentException e) {
            log.warn("Auth failed on {} {}: {}", method, path, e.getMessage());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return false;
        }

        // 确定所需最低角色
        Role requiredRole = resolveRequiredRole(path, method);

        // 检查权限
        Role userRole = Role.valueOf(user.getRole());
        if (!userRole.isAtLeast(requiredRole)) {
            log.warn("Permission denied: user={}, role={}, required={}, path={} {}",
                    user.getUsername(), userRole, requiredRole, method, path);
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "权限不足，需要 " + requiredRole + " 或更高角色");
            return false;
        }

        // 将用户信息存入 request attribute，供 Controller 使用
        request.setAttribute("currentUser", user);
        request.setAttribute("currentRole", userRole);
        return true;
    }

    /**
     * 根据请求路径和方法确定最低角色要求。
     * GET 只读端点：VIEWER
     * 策略启停、配置操作：OPERATOR
     * 风控相关写操作：RISK_MANAGER
     * 其他写操作：OPERATOR
     */
    private Role resolveRequiredRole(String path, String method) {
        boolean isWrite = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);

        if (!isWrite) {
            return Role.VIEWER;
        }

        // 风控写操作需要 RISK_MANAGER
        if (path.contains("/risk/kill-switch")) {
            return Role.RISK_MANAGER;
        }

        // 策略启停、配置发布、回填等需要 OPERATOR
        if (path.contains("/strategies/") && (path.endsWith("/enable") || path.endsWith("/disable"))) {
            return Role.OPERATOR;
        }

        if (path.contains("/configs/") || path.contains("/backfill")) {
            return Role.OPERATOR;
        }

        // 告警规则管理需要 OPERATOR
        if (path.contains("/alerts/rules")) {
            return Role.OPERATOR;
        }

        // 默认写操作需要 OPERATOR
        return Role.OPERATOR;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = Map.of(
                "success", false,
                "error", message
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
