package com.tj.crypto.admin.service;

import com.tj.crypto.admin.domain.ConfigStatus;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersion;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存配置版本仓库。
 * 第一阶段不依赖数据库，使用 ConcurrentHashMap 存储。
 * key 格式："{type}:{configKey}"，value 为该配置项的所有版本列表。
 */
@Repository
public class InMemoryConfigRepository {

    /**
     * 主存储：type:key → 版本列表（按创建时间升序）。
     */
    private final Map<String, List<ConfigVersion>> store = new ConcurrentHashMap<>();

    /**
     * 版本索引：versionId → ConfigVersion，用于按 ID 快速查找。
     */
    private final Map<String, ConfigVersion> versionIndex = new ConcurrentHashMap<>();

    /**
     * 保存一个版本。如果 versionId 已存在则更新（替换）。
     */
    public void save(ConfigVersion version) {
        String compositeKey = toCompositeKey(version.type(), version.configKey());

        versionIndex.put(version.versionId(), version);

        store.compute(compositeKey, (key, existing) -> {
            if (existing == null) {
                return new java.util.concurrent.CopyOnWriteArrayList<>(List.of(version));
            }
            // 如果同 versionId 已存在则替换，否则追加
            boolean replaced = false;
            for (int i = 0; i < existing.size(); i++) {
                if (existing.get(i).versionId().equals(version.versionId())) {
                    existing.set(i, version);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                existing.add(version);
            }
            return existing;
        });
    }

    /**
     * 按 versionId 查找。
     */
    public Optional<ConfigVersion> findById(String versionId) {
        return Optional.ofNullable(versionIndex.get(versionId));
    }

    /**
     * 获取某配置项的当前生效版本。
     * 返回状态为 ACTIVE 的最新版本。
     */
    public Optional<ConfigVersion> findActive(ConfigType type, String configKey) {
        String compositeKey = toCompositeKey(type, configKey);
        return Optional.ofNullable(store.get(compositeKey))
                .flatMap(versions -> versions.stream()
                        .filter(v -> v.status() == ConfigStatus.ACTIVE)
                        .max(Comparator.comparing(ConfigVersion::updatedAt)));
    }

    /**
     * 获取某配置项的全部版本历史，按创建时间升序。
     */
    public List<ConfigVersion> findHistory(ConfigType type, String configKey) {
        String compositeKey = toCompositeKey(type, configKey);
        return Optional.ofNullable(store.get(compositeKey))
                .map(versions -> versions.stream()
                        .sorted(Comparator.comparing(ConfigVersion::createdAt))
                        .toList())
                .orElse(List.of());
    }

    /**
     * 获取某配置项的最新版本（任何状态）。
     */
    public Optional<ConfigVersion> findLatest(ConfigType type, String configKey) {
        String compositeKey = toCompositeKey(type, configKey);
        return Optional.ofNullable(store.get(compositeKey))
                .flatMap(versions -> versions.stream()
                        .max(Comparator.comparing(ConfigVersion::createdAt)));
    }

    /**
     * 获取所有配置项（所有类型、所有 key）的当前生效版本。
     */
    public List<ConfigVersion> findAllActive() {
        return versionIndex.values().stream()
                .filter(v -> v.status() == ConfigStatus.ACTIVE)
                .toList();
    }

    /**
     * 获取指定类型下所有配置项的当前生效版本。
     */
    public List<ConfigVersion> findActiveByType(ConfigType type) {
        return versionIndex.values().stream()
                .filter(v -> v.type() == type && v.status() == ConfigStatus.ACTIVE)
                .toList();
    }

    /**
     * 清空所有数据（测试用）。
     */
    public void clear() {
        store.clear();
        versionIndex.clear();
    }

    private String toCompositeKey(ConfigType type, String configKey) {
        return type.getCode() + ":" + configKey;
    }
}
