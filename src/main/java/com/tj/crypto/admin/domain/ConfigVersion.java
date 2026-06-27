package com.tj.crypto.admin.domain;

import java.time.Instant;

/**
 * 配置版本领域模型。
 * 不可变 record，表示某个配置项的一个版本快照。
 *
 * @param versionId    版本唯一标识
 * @param type         配置类型
 * @param configKey    配置键（如策略名、因子名）
 * @param contentJson  配置内容（JSON 字符串）
 * @param status       当前状态
 * @param remark       备注说明
 * @param publishedBy  发布人（发布时填写）
 * @param createdAt    创建时间
 * @param updatedAt    最后更新时间
 */
public record ConfigVersion(
        String versionId,
        ConfigType type,
        String configKey,
        String contentJson,
        ConfigStatus status,
        String remark,
        String publishedBy,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 创建一个状态变更后的新版本（不可变模式）。
     */
    public ConfigVersion withStatus(ConfigStatus newStatus) {
        return new ConfigVersion(
                versionId,
                type,
                configKey,
                contentJson,
                newStatus,
                remark,
                publishedBy,
                createdAt,
                Instant.now()
        );
    }

    /**
     * 创建一个带发布人信息的新版本。
     */
    public ConfigVersion withPublishedBy(String publishedBy) {
        return new ConfigVersion(
                versionId,
                type,
                configKey,
                contentJson,
                status,
                remark,
                publishedBy,
                createdAt,
                Instant.now()
        );
    }
}
