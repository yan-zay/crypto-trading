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
        @DisplayName("Research Agent 整个命名空间至少需要 RESEARCHER，包括计算型 POST")
        void researchAgentNamespaceRequiresResearcher() throws Exception {
            String viewerToken = loginAs("viewer", "viewer123", "VIEWER");
            request.setRequestURI("/api/admin/research-agent/capabilities");
            request.setMethod("GET");
            request.addHeader("Authorization", "Bearer " + viewerToken);
            assertThat(interceptor.preHandle(request, response, null)).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);

            String researcherToken = loginAs("researcher", "research123", "RESEARCHER");
            request = new MockHttpServletRequest();
            request.setRequestURI("/api/admin/research-agent/query");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + researcherToken);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        }

        @Test
        @DisplayName("只读例外使用严格路径边界且不允许 RESEARCHER 绕到写接口")
        void researchReadOnlyExceptionCannotBypassWriteAuthorization() throws Exception {
            String token = loginAs("researcher", "research123", "RESEARCHER");
            for (String path : new String[]{
                    "/api/admin/research-agent-ops/query",
                    "/api/admin/research-agent/future-write",
                    "/api/admin/configs/draft",
                    "/api/admin/live-trading/orders"}) {
                request = new MockHttpServletRequest();
                request.setRequestURI(path);
                request.setMethod("POST");
                request.addHeader("Authorization", "Bearer " + token);
                MockHttpServletResponse localResponse = new MockHttpServletResponse();

                assertThat(interceptor.preHandle(request, localResponse, null)).isFalse();
                assertThat(localResponse.getStatus()).isEqualTo(403);
            }
        }

        @Test
        @DisplayName("未知和 PATCH 方法默认按写操作授权，不能伪装成 GET")
        void patchDefaultsToWriteAuthorization() throws Exception {
            String token = loginAs("viewer", "viewer123", "VIEWER");
            request.setRequestURI("/api/admin/strategies/MacdCross");
            request.setMethod("PATCH");
            request.addHeader("Authorization", "Bearer " + token);

            assertThat(interceptor.preHandle(request, response, null)).isFalse();
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
        @DisplayName("普通 OPERATOR 和 RISK_MANAGER 都不能发送实盘命令")
        void liveCommandsRequireDedicatedRole() throws Exception {
            for (String role : new String[]{"OPERATOR", "RISK_MANAGER"}) {
                String token = loginAs(role.toLowerCase(), "password", role);
                request = new MockHttpServletRequest();
                request.setRequestURI("/api/admin/live-trading/orders");
                request.setMethod("POST");
                request.addHeader("Authorization", "Bearer " + token);
                MockHttpServletResponse localResponse = new MockHttpServletResponse();

                assertThat(interceptor.preHandle(request, localResponse, null)).isFalse();
                assertThat(localResponse.getStatus()).isEqualTo(403);
            }
        }

        @Test
        @DisplayName("LIVE_TRADER 可以发送受底层门禁约束的实盘命令")
        void liveTraderCanSendLiveCommand() throws Exception {
            String token = loginAs("live", "password", "LIVE_TRADER");
            request.setRequestURI("/api/admin/live-trading/orders");
            request.setMethod("POST");
            request.addHeader("Authorization", "Bearer " + token);

            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }

        @Test
        @DisplayName("LIVE_TRADER 不能解除熔断或发布配置")
        void liveTraderCannotEscalateIntoRiskOrOperationsRoles() throws Exception {
            String token = loginAs("live", "password", "LIVE_TRADER");
            for (String path : new String[]{
                    "/api/admin/risk/kill-switch",
                    "/api/admin/configs/strategy/BTC/publish"}) {
                MockHttpServletRequest localRequest = new MockHttpServletRequest();
                localRequest.setRequestURI(path);
                localRequest.setMethod("POST");
                localRequest.addHeader("Authorization", "Bearer " + token);
                MockHttpServletResponse localResponse = new MockHttpServletResponse();

                assertThat(interceptor.preHandle(localRequest, localResponse, null)).isFalse();
                assertThat(localResponse.getStatus()).isEqualTo(403);
            }
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
