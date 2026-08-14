package com.tj.crypto.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.domain.AuditChainHeadDO;
import com.tj.crypto.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 审计日志 Mapper。
 * 封装 admin_audit_log 表的查询逻辑。
 */
@Mapper
public interface AuditLogMapper extends BaseMapperX<AuditLogDO> {

    @Select("SELECT chain_name, last_audit_id, last_hash, version "
            + "FROM audit_chain_head WHERE chain_name = #{chainName} FOR UPDATE")
    AuditChainHeadDO selectChainHeadForUpdate(@Param("chainName") String chainName);

    @Select("SELECT chain_name, last_audit_id, last_hash, version "
            + "FROM audit_chain_head WHERE chain_name = #{chainName}")
    AuditChainHeadDO selectChainHead(@Param("chainName") String chainName);

    @Update("UPDATE audit_chain_head SET last_audit_id = #{auditId}, last_hash = #{entryHash}, "
            + "version = version + 1 WHERE chain_name = #{chainName} AND version = #{expectedVersion}")
    int advanceChain(@Param("chainName") String chainName,
                     @Param("auditId") long auditId,
                     @Param("entryHash") String entryHash,
                     @Param("expectedVersion") long expectedVersion);

    @Select("SELECT * FROM admin_audit_log WHERE entry_hash IS NOT NULL ORDER BY id ASC")
    List<AuditLogDO> selectHashedAscending();

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
