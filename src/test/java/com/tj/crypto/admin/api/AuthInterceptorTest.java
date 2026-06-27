package com.tj.crypto.admin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.application.AuthService;
import com.tj.crypto.admin.domain.Role;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.admin.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    private AuthService authService;
    private UserMapper userMapper;
    private AuthInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        authService = new AuthService(userMapper, "test-secret");
        interceptor = new AuthInterceptor(authService, new ObjectMapper());
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private UserDO createUser(long id, String username, String password, String role) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash(AuthService.hashPassword(password));
        user.setRole(role);
        user.setEnabled(true);
        user.setCreatedAt(new Date());
        return user;
    }

    private String loginAs(String username, String password, String role) {
        UserDO user = createUser(1L, username, password, role);
        when(userMapper.selectByUsername(username)).thenReturn(Optional.of(user));
        when(userMapper.selectById(1L)).thenReturn(user);
        return authService.login(username, password);
    }

    @Nested
    @DisplayName("认证")
    class Authentication {

        @Test
        @DisplayName("缺少 Authorization header 返回 401")
        void shouldReturn401WhenNoAuthHeader() throws Exception {
            request.setRequestURI("/api/admin/status");
            request.setMethod("GET");

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("无效 token 返回 401")
        void shouldReturn401WhenTokenInvalid() throws Exception {
            request.setRequestURI("/api/admin/status");
            request.setMethod("GET");
            request.addHeader("Authorization", "Bearer invalid.token");

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("有效 token 通过认证")
        void shouldPassWhenTokenValid() throws Exception {
            String token = loginAs("viewer", "viewer123", "VIEWER");
            request.setRequestURI("/api/admin/status");
            request.setMethod("GET");
            request.addHeader("Authorization", "Bearer " + token);

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isTrue();
            assertThat(request.getAttribute("currentUser")).isNotNull();
        }
    }

    @Nested
    @DisplayName("授权")
    class Authorization {

        @Test
        @DisplayName("VIEWER 可以访问 GET 端点")
        void viewerCanAccessGetEndpoints() throws Exception {
            String token = loginAs("viewer", "viewer123", "VIEWER");
            request.setRequestURI("/api/admin/status");
            request.setMethod("GET");
            request.addHeader("Authorization", "Bearer " + token);

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("VIEWER 不能访问 POST 端点")
        void viewerCannotAccessPostEndpoints() throws Exception {
            String token = loginAs("viewer", "viewer123", "VIEWER");
            request.setRequestURI("/api/admin/strategies/MacdCross/enable");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + token);

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("OPERATOR 可以启用策略")
        void operatorCanEnableStrategy() throws Exception {
            String token = loginAs("operator", "op123", "OPERATOR");
            request.setRequestURI("/api/admin/strategies/MacdCross/enable");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + token);

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("OPERATOR 不能操作 KillSwitch")
        void operatorCannotActivateKillSwitch() throws Exception {
            String token = loginAs("operator", "op123", "OPERATOR");
            request.setRequestURI("/api/admin/risk/kill-switch");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + token);

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("RISK_MANAGER 可以操作 KillSwitch")
        void riskManagerCanActivateKillSwitch() throws Exception {
            String token = loginAs("risk", "risk123", "RISK_MANAGER");
            request.setRequestURI("/api/admin/risk/kill-switch");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + token);

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("ADMIN 可以访问所有端点")
        void adminCanAccessAllEndpoints() throws Exception {
            String token = loginAs("admin", "admin123", "ADMIN");

            // GET 端点
            request = new MockHttpServletRequest();
            request.setRequestURI("/api/admin/status");
            request.setMethod("GET");
            request.addHeader("Authorization", "Bearer " + token);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();

            // POST 策略操作
            request = new MockHttpServletRequest();
            request.setRequestURI("/api/admin/strategies/MacdCross/enable");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + token);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();

            // KillSwitch 操作
            request = new MockHttpServletRequest();
            request.setRequestURI("/api/admin/risk/kill-switch");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + token);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        }
    }
}
