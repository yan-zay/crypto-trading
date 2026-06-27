package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.domain.ConfigStatus;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersion;
import com.tj.crypto.admin.domain.ConfigVersionDO;
import com.tj.crypto.admin.mapper.AuditLogMapper;
import com.tj.crypto.admin.mapper.ConfigVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 配置版本管理服务。
 * 提供配置的完整生命周期管理：Draft → Validated → Published → Active → RolledBack。
 * 使用 MySQL 持久化，每次状态变更记录审计日志。
 */
@Slf4j
@Service
public class ConfigVersionService {

    private final ConfigVersionMapper configVersionMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ConfigVersionService(ConfigVersionMapper configVersionMapper,
                                AuditLogMapper auditLogMapper,
                                ApplicationEventPublisher eventPublisher) {
        this.configVersionMapper = configVersionMapper;
        this.auditLogMapper = auditLogMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 创建草稿版本。
     */
    public ConfigVersion createDraft(ConfigType type, String configKey, String contentJson, String remark) {
        Instant now = Instant.now();
        String versionId = generateVersionId();

        ConfigVersionDO entity = new ConfigVersionDO();
        entity.setConfigType(type.getCode());
        entity.setConfigKey(configKey);
        entity.setVersionId(versionId);
        entity.setStatus(ConfigStatus.DRAFT.getCode());
        entity.setContentJson(contentJson);
        entity.setChecksum(computeChecksum(contentJson));
        entity.setCreatedBy("system");
        entity.setRemark(remark);
        entity.setCreateTime(Date.from(now));
        entity.setUpdateTime(Date.from(now));
        configVersionMapper.insert(entity);

        recordAudit("CREATE", type, configKey, versionId, "system", "Created draft config");

        log.info("Created draft config version: type={}, key={}, versionId={}", type, configKey, versionId);
        return toRecord(entity);
    }

    /**
     * 验证配置版本。状态从 DRAFT 变为 VALIDATED。
     */
    public ConfigVersion validate(String versionId) {
        ConfigVersionDO current = getVersionOrThrow(versionId);
        assertStatus(current, ConfigStatus.DRAFT, "validate");

        current.setStatus(ConfigStatus.VALIDATED.getCode());
        current.setUpdateTime(new Date());
        configVersionMapper.updateById(current);

        recordAudit("VALIDATE", ConfigType.fromCode(current.getConfigType()),
                current.getConfigKey(), versionId, "system", "Validated config version");

        log.info("Validated config version: versionId={}", versionId);
        return toRecord(current);
    }

    /**
     * 发布配置版本。
     * 状态从 VALIDATED 变为 PUBLISHED，然后自动变为 ACTIVE。
     * 如果已有 ACTIVE 版本，旧版本自动变为 ARCHIVED。
     * 发布后触发 ConfigPublishedEvent 事件，同步运行态。
     */
    public ConfigVersion publish(String versionId, String publishedBy) {
        ConfigVersionDO current = getVersionOrThrow(versionId);
        assertStatus(current, ConfigStatus.VALIDATED, "publish");

        // 归档旧的 ACTIVE 版本
        configVersionMapper.selectActiveByTypeAndKey(current.getConfigType(), current.getConfigKey())
                .ifPresent(oldActive -> {
                    oldActive.setStatus(ConfigStatus.ARCHIVED.getCode());
                    oldActive.setUpdateTime(new Date());
                    configVersionMapper.updateById(oldActive);
                    log.info("Archived previous active version: versionId={}", oldActive.getVersionId());
                });

        // 发布
        current.setStatus(ConfigStatus.PUBLISHED.getCode());
        current.setPublishedBy(publishedBy);
        current.setPublishedTime(new Date());
        current.setUpdateTime(new Date());
        configVersionMapper.updateById(current);

        // 激活
        current.setStatus(ConfigStatus.ACTIVE.getCode());
        current.setUpdateTime(new Date());
        configVersionMapper.updateById(current);

        recordAudit("PUBLISH", ConfigType.fromCode(current.getConfigType()),
                current.getConfigKey(), versionId, publishedBy, "Published and activated config");

        log.info("Published and activated config version: versionId={}, publishedBy={}", versionId, publishedBy);

        // 触发运行态同步
        eventPublisher.publishEvent(new ConfigPublishedEvent(
                this, ConfigType.fromCode(current.getConfigType()), current.getConfigKey()));

        return toRecord(current);
    }

    /**
     * 回滚到指定的目标版本。
     */
    public ConfigVersion rollback(String currentVersionId, String targetVersionId) {
        ConfigVersionDO current = getVersionOrThrow(currentVersionId);
        ConfigVersionDO target = getVersionOrThrow(targetVersionId);

        if (!current.getConfigType().equals(target.getConfigType())
                || !current.getConfigKey().equals(target.getConfigKey())) {
            throw new IllegalArgumentException(
                    "Target version belongs to a different config: current=%s:%s, target=%s:%s"
                            .formatted(current.getConfigType(), current.getConfigKey(),
                                    target.getConfigType(), target.getConfigKey()));
        }

        // 回滚当前 ACTIVE 版本
        configVersionMapper.selectActiveByTypeAndKey(current.getConfigType(), current.getConfigKey())
                .ifPresent(active -> {
                    active.setStatus(ConfigStatus.ROLLED_BACK.getCode());
                    active.setUpdateTime(new Date());
                    configVersionMapper.updateById(active);
                    log.info("Rolled back active version: versionId={}", active.getVersionId());
                });

        // 重新激活目标版本
        target.setStatus(ConfigStatus.ACTIVE.getCode());
        target.setUpdateTime(new Date());
        configVersionMapper.updateById(target);

        recordAudit("ROLLBACK", ConfigType.fromCode(current.getConfigType()),
                current.getConfigKey(), targetVersionId, "system",
                "Rolled back from %s to %s".formatted(currentVersionId, targetVersionId));

        log.info("Rollback complete: target versionId={} is now ACTIVE", targetVersionId);

        // 触发运行态同步
        eventPublisher.publishEvent(new ConfigPublishedEvent(
                this, ConfigType.fromCode(current.getConfigType()), current.getConfigKey()));

        return toRecord(target);
    }

    /**
     * 获取某配置项的当前生效版本。
     */
    public Optional<ConfigVersion> getActive(ConfigType type, String configKey) {
        return configVersionMapper.selectActiveByTypeAndKey(type.getCode(), configKey)
                .map(this::toRecord);
    }

    /**
     * 获取某配置项的全部版本历史。
     */
    public List<ConfigVersion> getHistory(ConfigType type, String configKey) {
        return configVersionMapper.selectHistoryByTypeAndKey(type.getCode(), configKey)
                .stream()
                .map(this::toRecord)
                .toList();
    }

    /**
     * 获取指定类型下所有当前生效的配置版本。
     */
    public List<ConfigVersion> getActiveByType(ConfigType type) {
        return configVersionMapper.selectActiveByType(type.getCode())
                .stream()
                .map(this::toRecord)
                .toList();
    }

    /**
     * 按 versionId 获取版本详情。
     */
    public ConfigVersion getVersion(String versionId) {
        return toRecord(getVersionOrThrow(versionId));
    }

    // ========== 内部方法 ==========

    private ConfigVersionDO getVersionOrThrow(String versionId) {
        return configVersionMapper.selectByVersionId(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Config version not found: " + versionId));
    }

    private void assertStatus(ConfigVersionDO version, ConfigStatus expected, String operation) {
        if (!expected.getCode().equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "Cannot %s version in state %s: versionId=%s"
                            .formatted(operation, version.getStatus(), version.getVersionId()));
        }
    }

    private ConfigVersion toRecord(ConfigVersionDO entity) {
        return new ConfigVersion(
                entity.getVersionId(),
                ConfigType.fromCode(entity.getConfigType()),
                entity.getConfigKey(),
                entity.getContentJson(),
                ConfigStatus.fromCode(entity.getStatus()),
                entity.getRemark(),
                entity.getPublishedBy(),
                entity.getCreateTime() != null ? entity.getCreateTime().toInstant() : null,
                entity.getUpdateTime() != null ? entity.getUpdateTime().toInstant() : null
        );
    }

    private String generateVersionId() {
        return "cv-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String computeChecksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void recordAudit(String operationType, ConfigType configType, String configKey,
                             String versionId, String operator, String detail) {
        AuditLogDO auditLog = new AuditLogDO();
        auditLog.setOperationType(operationType);
        auditLog.setConfigType(configType.getCode());
        auditLog.setConfigKey(configKey);
        auditLog.setVersionId(versionId);
        auditLog.setOperator(operator);
        auditLog.setOperationTime(new Date());
        auditLog.setDetail(detail);
        auditLogMapper.insert(auditLog);
    }
}
