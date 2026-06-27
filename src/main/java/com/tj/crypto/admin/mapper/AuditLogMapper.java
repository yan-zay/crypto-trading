package com.tj.crypto.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.mapper.BaseMapperX;

import java.util.List;

/**
 * 审计日志 Mapper。
 * 封装 admin_audit_log 表的查询逻辑。
 */
@Mapper
public interface AuditLogMapper extends BaseMapperX<AuditLogDO> {

    /**
     * 查询指定配置的所有审计记录（按时间降序）。
     */
    default List<AuditLogDO> selectByConfig(String configType, String configKey) {
        return selectList(new LambdaQueryWrapper<AuditLogDO>()
                .eq(AuditLogDO::getConfigType, configType)
                .eq(AuditLogDO::getConfigKey, configKey)
                .orderByDesc(AuditLogDO::getOperationTime));
    }

    /**
     * 查询指定版本的所有审计记录。
     */
    default List<AuditLogDO> selectByVersionId(String versionId) {
        return selectList(new LambdaQueryWrapper<AuditLogDO>()
                .eq(AuditLogDO::getVersionId, versionId)
                .orderByDesc(AuditLogDO::getOperationTime));
    }
}
