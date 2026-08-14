package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.Role;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.admin.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserMapper userMapper;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        authService = new AuthService(userMapper, "test-secret-key");
    }

    @Test
    @DisplayName("拒绝过长或过短的管理令牌有效期")
    void shouldRejectUnsafeTokenTtl() {
        assertThatThrownBy(() -> new AuthService(userMapper, "test-secret-key", 59_999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TTL");
        assertThatThrownBy(() -> new AuthService(userMapper, "test-secret-key", 3_600_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TTL");
    }

    private UserDO createUser(long id, String username, String rawPassword, String role, boolean enabled) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash(AuthService.hashPassword(rawPassword));
        user.setRole(role);
        user.setEnabled(enabled);
        user.setCreatedAt(new Date());
        return user;
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("正确的用户名密码返回 token")
        void shouldReturnTokenWhenCredentialsCorrect() {
            UserDO user = createUser(1L, "admin", "admin123", "ADMIN", true);
            when(userMapper.selectByUsername("admin")).thenReturn(Optional.of(user));

            String token = authService.login("admin", "admin123");

            assertThat(token).isNotBlank();
            assertThat(token).contains(".");
        }

        @Test
        @DisplayName("不存在的用户名抛出异常")
        void shouldThrowWhenUserNotFound() {
            when(userMapper.selectByUsername("nonexistent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login("nonexistent", "any"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名或密码错误");
        }

        @Test
        @DisplayName("缺失凭据以统一认证错误拒绝")
        void shouldRejectMissingCredentials() {
            assertThatThrownBy(() -> authService.login(null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名或密码错误");
            assertThatThrownBy(() -> authService.login("", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名或密码错误");
        }

        @Test
        @DisplayName("错误的密码抛出异常")
        void shouldThrowWhenPasswordWrong() {
            UserDO user = createUser(1L, "admin", "admin123", "ADMIN", true);
            when(userMapper.selectByUsername("admin")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.login("admin", "wrongpassword"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用户名或密码错误");
        }

        @Test
        @DisplayName("禁用的用户抛出异常")
        void shouldThrowWhenUserDisabled() {
            UserDO user = createUser(1L, "admin", "admin123", "ADMIN", false);
            when(userMapper.selectByUsername("admin")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.login("admin", "admin123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已被禁用");
        }
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("有效的 token 返回用户信息")
        void shouldReturnUserWhenTokenValid() {
            UserDO user = createUser(1L, "admin", "admin123", "ADMIN", true);
            when(userMapper.selectByUsername("admin")).thenReturn(Optional.of(user));
            when(userMapper.selectById(1L)).thenReturn(user);

            String token = authService.login("admin", "admin123");
            UserDO validated = authService.validateToken(token);

            assertThat(validated.getId()).isEqualTo(1L);
            assertThat(validated.getUsername()).isEqualTo("admin");
            assertThat(validated.getRole()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("空 token 抛出异常")
        void shouldThrowWhenTokenEmpty() {
            assertThatThrownBy(() -> authService.validateToken(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能为空");
        }

        @Test
        @DisplayName("null token 抛出异常")
        void shouldThrowWhenTokenNull() {
            assertThatThrownBy(() -> authService.validateToken(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能为空");
        }

        @Test
        @DisplayName("格式错误的 token 抛出异常")
        void shouldThrowWhenTokenMalformed() {
            assertThatThrownBy(() -> authService.validateToken("invalid-token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("格式无效");
        }

        @Test
        @DisplayName("签名被篡改的 token 抛出异常")
        void shouldThrowWhenSignatureTampered() {
            UserDO user = createUser(1L, "admin", "admin123", "ADMIN", true);
            when(userMapper.selectByUsername("admin")).thenReturn(Optional.of(user));

            String token = authService.login("admin", "admin123");
            String tampered = token.substring(0, token.length() - 5) + "XXXXX";

            assertThatThrownBy(() -> authService.validateToken(tampered))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("签名无效");
        }

        @Test
        @DisplayName("用户被禁用后 token 失效")
        void shouldThrowWhenUserDisabledAfterLogin() {
            UserDO user = createUser(1L, "admin", "admin123", "ADMIN", true);
            when(userMapper.selectByUsername("admin")).thenReturn(Optional.of(user));

            String token = authService.login("admin", "admin123");

            // 用户被禁用
            UserDO disabledUser = createUser(1L, "admin", "admin123", "ADMIN", false);
            when(userMapper.selectById(1L)).thenReturn(disabledUser);

            assertThatThrownBy(() -> authService.validateToken(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在或已被禁用");
        }
    }

    @Nested
    @DisplayName("checkPermission")
    class CheckPermission {

        @Test
        @DisplayName("ADMIN 角色满足所有权限要求")
        void adminShouldSatisfyAllRoles() {
            UserDO user = createUser(1L, "admin", "admin123", "ADMIN", true);
            when(userMapper.selectByUsername("admin")).thenReturn(Optional.of(user));
            when(userMapper.selectById(1L)).thenReturn(user);

            String token = authService.login("admin", "admin123");

            assertThat(authService.checkPermission(token, Role.VIEWER)).isTrue();
            assertThat(authService.checkPermission(token, Role.RESEARCHER)).isTrue();
            assertThat(authService.checkPermission(token, Role.OPERATOR)).isTrue();
            assertThat(authService.checkPermission(token, Role.RISK_MANAGER)).isTrue();
            assertThat(authService.checkPermission(token, Role.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("VIEWER 角色只满足 VIEWER 权限")
        void viewerShouldOnlySatisfyViewer() {
            UserDO user = createUser(2L, "viewer", "viewer123", "VIEWER", true);
            when(userMapper.selectByUsername("viewer")).thenReturn(Optional.of(user));
            when(userMapper.selectById(2L)).thenReturn(user);

            String token = authService.login("viewer", "viewer123");

            assertThat(authService.checkPermission(token, Role.VIEWER)).isTrue();
            assertThat(authService.checkPermission(token, Role.RESEARCHER)).isFalse();
            assertThat(authService.checkPermission(token, Role.OPERATOR)).isFalse();
            assertThat(authService.checkPermission(token, Role.RISK_MANAGER)).isFalse();
            assertThat(authService.checkPermission(token, Role.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("RISK_MANAGER 满足 OPERATOR 和 VIEWER 权限")
        void riskManagerShouldSatisfyLowerRoles() {
            UserDO user = createUser(3L, "risk", "risk123", "RISK_MANAGER", true);
            when(userMapper.selectByUsername("risk")).thenReturn(Optional.of(user));
            when(userMapper.selectById(3L)).thenReturn(user);

            String token = authService.login("risk", "risk123");

            assertThat(authService.checkPermission(token, Role.VIEWER)).isTrue();
            assertThat(authService.checkPermission(token, Role.OPERATOR)).isTrue();
            assertThat(authService.checkPermission(token, Role.RISK_MANAGER)).isTrue();
            assertThat(authService.checkPermission(token, Role.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("无效 token 返回 false")
        void shouldReturnFalseForInvalidToken() {
            assertThat(authService.checkPermission("invalid.token", Role.VIEWER)).isFalse();
        }

        @Test
        @DisplayName("null token 返回 false")
        void shouldReturnFalseForNullToken() {
            assertThat(authService.checkPermission(null, Role.VIEWER)).isFalse();
        }
    }

    @Nested
    @DisplayName("Role.isAtLeast")
    class RoleHierarchy {

        @Test
        @DisplayName("角色层级关系正确")
        void shouldHaveCorrectHierarchy() {
            assertThat(Role.ADMIN.isAtLeast(Role.ADMIN)).isTrue();
            assertThat(Role.ADMIN.isAtLeast(Role.RISK_MANAGER)).isTrue();
            assertThat(Role.RISK_MANAGER.isAtLeast(Role.OPERATOR)).isTrue();
            assertThat(Role.OPERATOR.isAtLeast(Role.RESEARCHER)).isTrue();
            assertThat(Role.RESEARCHER.isAtLeast(Role.VIEWER)).isTrue();

            assertThat(Role.LIVE_TRADER.isAtLeast(Role.VIEWER)).isTrue();
            assertThat(Role.LIVE_TRADER.isAtLeast(Role.LIVE_TRADER)).isTrue();
            assertThat(Role.LIVE_TRADER.isAtLeast(Role.OPERATOR)).isFalse();
            assertThat(Role.LIVE_TRADER.isAtLeast(Role.RISK_MANAGER)).isFalse();
            assertThat(Role.RISK_MANAGER.isAtLeast(Role.LIVE_TRADER)).isFalse();

            assertThat(Role.VIEWER.isAtLeast(Role.ADMIN)).isFalse();
            assertThat(Role.VIEWER.isAtLeast(Role.OPERATOR)).isFalse();
        }
    }
}
