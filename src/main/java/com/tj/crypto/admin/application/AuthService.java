package com.tj.crypto.admin.application;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.tj.crypto.admin.domain.Role;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.admin.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认证与授权服务。
 * 提供登录（密码验证 + token 签发）、token 校验、权限检查。
 */
@Slf4j
@Service
public class AuthService {

    private static final long DEFAULT_TOKEN_TTL_MS = 15 * 60 * 1000L;
    private static final String TOKEN_SEPARATOR = ":";
    private static final String DEFAULT_SECRET = "default-secret-change-in-production";
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_MS = 15 * 60 * 1000L; // 15 分钟

    private final UserMapper userMapper;
    private final String secretKey;
    private final long tokenTtlMs;

    @Value("${spring.profiles.active:dev}")
    private String activeProfiles = "dev";

    /** 登录失败计数：username -> attempt info */
    private final ConcurrentHashMap<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    @Autowired
    public AuthService(UserMapper userMapper,
                       @Value("${crypto.auth.secret-key:default-secret-change-in-production}") String secretKey,
                       @Value("${crypto.auth.token-ttl-ms:900000}") long tokenTtlMs) {
        this.userMapper = userMapper;
        this.secretKey = secretKey;
        if (tokenTtlMs < 60_000 || tokenTtlMs > 3_600_000) {
            throw new IllegalArgumentException("Admin token TTL must be between 1 and 60 minutes");
        }
        this.tokenTtlMs = tokenTtlMs;
    }

    /** Compatibility constructor for isolated tests. */
    public AuthService(UserMapper userMapper, String secretKey) {
        this(userMapper, secretKey, DEFAULT_TOKEN_TTL_MS);
    }

    @PostConstruct
    void validateConfig() {
        boolean production = java.util.Arrays.stream(activeProfiles.split(","))
                .map(String::trim).anyMatch("pro"::equalsIgnoreCase);
        if (production && (DEFAULT_SECRET.equals(secretKey)
                || secretKey == null || secretKey.length() < 32)) {
            throw new IllegalStateException(
                    "Production profile requires a non-default crypto.auth.secret-key of at least 32 characters");
        }
        if (DEFAULT_SECRET.equals(secretKey)) {
            log.warn("Using default auth secret; configure crypto.auth.secret-key outside development");
        }
        if (production) {
            userMapper.selectByUsername("admin").ifPresent(user -> {
                if (BCrypt.checkpw("admin123", user.getPasswordHash())) {
                    throw new IllegalStateException(
                            "Default admin password is forbidden in production");
                }
            });
            userMapper.selectByUsername("viewer").ifPresent(user -> {
                if (BCrypt.checkpw("viewer123", user.getPasswordHash())) {
                    throw new IllegalStateException(
                            "Default viewer password is forbidden in production");
                }
            });
        }
    }

    /**
     * 用户登录。
     * 验证用户名密码，成功后签发 token。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 签名 token
     * @throws IllegalArgumentException 用户名或密码错误
     */
    public String login(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 50
                || password == null || password.isBlank() || password.length() > 256) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        // 登录限流检查
        LoginAttempt attempt = loginAttempts.get(username);
        if (attempt != null && attempt.isLocked()) {
            log.warn("Login rate limited: [{}], remaining={}ms", username, attempt.remainingLockMs());
            throw new IllegalArgumentException("登录尝试过多，请稍后再试");
        }

        Optional<UserDO> userOpt = userMapper.selectByUsername(username);
        if (userOpt.isEmpty()) {
            recordFailedAttempt(username);
            log.warn("Login failed: user not found [{}]", username);
            throw new IllegalArgumentException("用户名或密码错误");
        }

        UserDO user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            log.warn("Login failed: user disabled [{}]", username);
            throw new IllegalArgumentException("用户已被禁用");
        }

        if (!BCrypt.checkpw(password, user.getPasswordHash())) {
            recordFailedAttempt(username);
            log.warn("Login failed: wrong password [{}]", username);
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 登录成功，清除失败记录
        loginAttempts.remove(username);
        String token = generateToken(user);
        log.info("User [{}] logged in, role={}", username, user.getRole());
        return token;
    }

    /**
     * 校验 token 有效性，返回对应用户。
     *
     * @param token 签名 token
     * @return 用户信息
     * @throws IllegalArgumentException token 无效或已过期
     */
    public UserDO validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("token 格式无效");
        }

        String payloadBase64 = parts[0];
        String signature = parts[1];

        // 验签（常量时间比较，防止时序攻击）
        String expectedSig = SecureUtil.hmacSha256(secretKey)
                .digestHex(payloadBase64);
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("token 签名无效");
        }

        // 解析 payload
        String payload = new String(Base64.getUrlDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
        String[] fields = payload.split(TOKEN_SEPARATOR);
        if (fields.length != 4) {
            throw new IllegalArgumentException("token payload 格式无效");
        }

        long userId = Long.parseLong(fields[0]);
        long expiry = Long.parseLong(fields[3]);
        if (System.currentTimeMillis() > expiry) {
            throw new IllegalArgumentException("token 已过期");
        }

        // 查询用户确认仍然有效
        UserDO user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalArgumentException("用户不存在或已被禁用");
        }

        return user;
    }

    /**
     * 检查 token 对应用户是否满足最低角色要求。
     *
     * @param token       签名 token
     * @param requiredRole 最低角色要求
     * @return true 如果权限足够
     */
    public boolean checkPermission(String token, Role requiredRole) {
        try {
            UserDO user = validateToken(token);
            Role userRole = Role.valueOf(user.getRole());
            return userRole.isAtLeast(requiredRole);
        } catch (IllegalArgumentException e) {
            log.debug("Permission check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 生成签名 token。
     * payload 格式：userId:username:role:expiry（Base64URL 编码）
     * 签名：HMAC-SHA256(secretKey, payloadBase64)
     */
    String generateToken(UserDO user) {
        long expiry = System.currentTimeMillis() + tokenTtlMs;
        String payload = String.join(TOKEN_SEPARATOR,
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getRole(),
                String.valueOf(expiry));

        String payloadBase64 = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        String signature = SecureUtil.hmacSha256(secretKey)
                .digestHex(payloadBase64);

        return payloadBase64 + "." + signature;
    }

    /**
     * 对明文密码进行 BCrypt 哈希。
     * 用于初始化用户数据时调用。
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希
     */
    public static String hashPassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    // ========== 登录限流 ==========

    private void recordFailedAttempt(String username) {
        loginAttempts.compute(username, (key, existing) -> {
            if (existing == null) {
                return new LoginAttempt(1, System.currentTimeMillis());
            }
            if (System.currentTimeMillis() - existing.lastAttemptTime > LOGIN_LOCKOUT_MS) {
                return new LoginAttempt(1, System.currentTimeMillis());
            }
            return new LoginAttempt(existing.attempts.incrementAndGet(), System.currentTimeMillis());
        });
    }

    /** 登录尝试记录 */
    private static class LoginAttempt {
        final AtomicInteger attempts;
        final long lastAttemptTime;

        LoginAttempt(int attempts, long lastAttemptTime) {
            this.attempts = new AtomicInteger(attempts);
            this.lastAttemptTime = lastAttemptTime;
        }

        boolean isLocked() {
            return attempts.get() >= MAX_LOGIN_ATTEMPTS
                    && System.currentTimeMillis() - lastAttemptTime < LOGIN_LOCKOUT_MS;
        }

        long remainingLockMs() {
            return LOGIN_LOCKOUT_MS - (System.currentTimeMillis() - lastAttemptTime);
        }
    }
}
