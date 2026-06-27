package com.tj.crypto.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.admin.domain.ConfigVersionDO;
import com.tj.crypto.mapper.BaseMapperX;

import java.util.List;
import java.util.Optional;

/**
 * 配置版本 Mapper。
 * 封装 admin_config_version 表的查询逻辑。
 */
@Mapper
public interface ConfigVersionMapper extends BaseMapperX<ConfigVersionDO> {

    /**
     * 查询指定类型和键的 ACTIVE 配置。
     */
    default Optional<ConfigVersionDO> selectActiveByTypeAndKey(String configType, String configKey) {
        ConfigVersionDO result = selectOne(new LambdaQueryWrapper<ConfigVersionDO>()
                .eq(ConfigVersionDO::getConfigType, configType)
                .eq(ConfigVersionDO::getConfigKey, configKey)
                .eq(ConfigVersionDO::getStatus, "ACTIVE")
                .orderByDesc(ConfigVersionDO::getUpdateTime)
                .last("LIMIT 1"));
        return Optional.ofNullable(result);
    }

    /**
     * 查询指定版本 ID。
     */
    default Optional<ConfigVersionDO> selectByVersionId(String versionId) {
        ConfigVersionDO result = selectOne(new LambdaQueryWrapper<ConfigVersionDO>()
                .eq(ConfigVersionDO::getVersionId, versionId));
        return Optional.ofNullable(result);
    }

    /**
     * 查询指定类型和键的所有版本（按创建时间升序）。
     */
    default List<ConfigVersionDO> selectHistoryByTypeAndKey(String configType, String configKey) {
        return selectList(new LambdaQueryWrapper<ConfigVersionDO>()
                .eq(ConfigVersionDO::getConfigType, configType)
                .eq(ConfigVersionDO::getConfigKey, configKey)
                .orderByAsc(ConfigVersionDO::getCreateTime));
    }

    /**
     * 查询指定类型下所有 ACTIVE 配置。
     */
    default List<ConfigVersionDO> selectActiveByType(String configType) {
        return selectList(new LambdaQueryWrapper<ConfigVersionDO>()
                .eq(ConfigVersionDO::getConfigType, configType)
                .eq(ConfigVersionDO::getStatus, "ACTIVE"));
    }

    /**
     * 查询所有 ACTIVE 配置。
     */
    default List<ConfigVersionDO> selectAllActive() {
        return selectList(new LambdaQueryWrapper<ConfigVersionDO>()
                .eq(ConfigVersionDO::getStatus, "ACTIVE"));
    }

    /**
     * 按版本 ID 更新状态。
     */
    default int updateStatusByVersionId(String versionId, String newStatus) {
        ConfigVersionDO entity = new ConfigVersionDO();
        entity.setStatus(newStatus);
        return update(entity, new LambdaQueryWrapper<ConfigVersionDO>()
                .eq(ConfigVersionDO::getVersionId, versionId));
    }
}
