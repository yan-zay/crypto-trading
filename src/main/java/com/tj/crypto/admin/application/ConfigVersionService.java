package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.ConfigStatus;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersion;
import com.tj.crypto.admin.service.InMemoryConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 配置版本管理服务。
 * 提供配置的完整生命周期管理：Draft → Validated → Published → Active → RolledBack。
 * 所有状态变更产生新的 ConfigVersion 实例（不可变模式）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigVersionService {

    private final InMemoryConfigRepository repository;

    /**
     * 创建草稿版本。
     *
     * @param type        配置类型
     * @param configKey   配置键
     * @param contentJson 配置内容（JSON 字符串）
     * @param remark      备注说明
     * @return 新创建的草稿版本
     */
    public ConfigVersion createDraft(ConfigType type, String configKey, String contentJson, String remark) {
        Instant now = Instant.now();
        ConfigVersion draft = new ConfigVersion(
                generateVersionId(),
                type,
                configKey,
                contentJson,
                ConfigStatus.DRAFT,
                remark,
                null,
                now,
                now
        );
        repository.save(draft);
        log.info("Created draft config version: type={}, key={}, versionId={}",
                type, configKey, draft.versionId());
        return draft;
    }

    /**
     * 验证配置版本。
     * 状态从 DRAFT 变为 VALIDATED。只有 DRAFT 状态的版本可以被验证。
     *
     * @param versionId 版本 ID
     * @return 验证后的版本
     * @throws IllegalArgumentException 版本不存在或状态不允许验证
     */
    public ConfigVersion validate(String versionId) {
        ConfigVersion current = getVersionOrThrow(versionId);
        assertStatus(current, ConfigStatus.DRAFT, "validate");

        ConfigVersion validated = current.withStatus(ConfigStatus.VALIDATED);
        repository.save(validated);
        log.info("Validated config version: versionId={}", versionId);
        return validated;
    }

    /**
     * 发布配置版本。
     * 状态从 VALIDATED 变为 PUBLISHED，然后自动变为 ACTIVE。
     * 如果已有 ACTIVE 版本，旧版本自动变为 ARCHIVED。
     *
     * @param versionId   版本 ID
     * @param publishedBy 发布人
     * @return 发布后生效的版本
     * @throws IllegalArgumentException 版本不存在或状态不允许发布
     */
    public ConfigVersion publish(String versionId, String publishedBy) {
        ConfigVersion current = getVersionOrThrow(versionId);
        assertStatus(current, ConfigStatus.VALIDATED, "publish");

        // 归档旧的 ACTIVE 版本
        repository.findActive(current.type(), current.configKey())
                .ifPresent(oldActive -> {
                    ConfigVersion archived = oldActive.withStatus(ConfigStatus.ARCHIVED);
                    repository.save(archived);
                    log.info("Archived previous active version: versionId={}", oldActive.versionId());
                });

        // 发布并激活
        ConfigVersion published = current
                .withStatus(ConfigStatus.PUBLISHED)
                .withPublishedBy(publishedBy);
        repository.save(published);

        ConfigVersion active = published.withStatus(ConfigStatus.ACTIVE);
        repository.save(active);

        log.info("Published and activated config version: versionId={}, publishedBy={}",
                versionId, publishedBy);
        return active;
    }

    /**
     * 回滚到指定的目标版本。
     * 目标版本必须是之前 ACTIVE 或 PUBLISHED 状态的历史版本。
     * 当前 ACTIVE 版本变为 ROLLED_BACK，目标版本重新变为 ACTIVE。
     *
     * @param currentVersionId 当前版本 ID（用于定位配置项）
     * @param targetVersionId  要回滚到的目标版本 ID
     * @return 回滚后重新生效的版本
     * @throws IllegalArgumentException 版本不存在或状态不允许回滚
     */
    public ConfigVersion rollback(String currentVersionId, String targetVersionId) {
        ConfigVersion current = getVersionOrThrow(currentVersionId);
        ConfigVersion target = getVersionOrThrow(targetVersionId);

        // 验证 target 是同一配置项的历史版本
        if (current.type() != target.type() || !current.configKey().equals(target.configKey())) {
            throw new IllegalArgumentException(
                    "Target version belongs to a different config: current=%s:%s, target=%s:%s"
                            .formatted(current.type(), current.configKey(), target.type(), target.configKey()));
        }

        // 归档或回滚当前 ACTIVE 版本
        repository.findActive(current.type(), current.configKey())
                .ifPresent(active -> {
                    ConfigVersion rolledBack = active.withStatus(ConfigStatus.ROLLED_BACK);
                    repository.save(rolledBack);
                    log.info("Rolled back active version: versionId={}", active.versionId());
                });

        // 将目标版本重新激活
        ConfigVersion reactivated = target.withStatus(ConfigStatus.ACTIVE);
        repository.save(reactivated);

        log.info("Rollback complete: target versionId={} is now ACTIVE", targetVersionId);
        return reactivated;
    }

    /**
     * 获取某配置项的当前生效版本。
     *
     * @param type      配置类型
     * @param configKey 配置键
     * @return 当前生效版本（可能为空）
     */
    public java.util.Optional<ConfigVersion> getActive(ConfigType type, String configKey) {
        return repository.findActive(type, configKey);
    }

    /**
     * 获取某配置项的全部版本历史。
     *
     * @param type      配置类型
     * @param configKey 配置键
     * @return 版本列表，按创建时间升序
     */
    public List<ConfigVersion> getHistory(ConfigType type, String configKey) {
        return repository.findHistory(type, configKey);
    }

    /**
     * 获取指定类型下所有当前生效的配置版本。
     *
     * @param type 配置类型
     * @return 该类型下所有生效版本
     */
    public List<ConfigVersion> getActiveByType(ConfigType type) {
        return repository.findActiveByType(type);
    }

    /**
     * 按 versionId 获取版本详情。
     *
     * @param versionId 版本 ID
     * @return 版本详情
     * @throws IllegalArgumentException 版本不存在
     */
    public ConfigVersion getVersion(String versionId) {
        return getVersionOrThrow(versionId);
    }

    private ConfigVersion getVersionOrThrow(String versionId) {
        return repository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Config version not found: " + versionId));
    }

    private void assertStatus(ConfigVersion version, ConfigStatus expected, String operation) {
        if (version.status() != expected) {
            throw new IllegalArgumentException(
                    "Cannot %s version in state %s: versionId=%s"
                            .formatted(operation, version.status(), version.versionId()));
        }
    }

    private String generateVersionId() {
        return "cv-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
