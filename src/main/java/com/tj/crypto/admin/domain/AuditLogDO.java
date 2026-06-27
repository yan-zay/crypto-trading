package com.tj.crypto.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tj.crypto.entity.base.PhysicsTimeBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 审计日志持久化实体。
 * 对应 admin_audit_log 表，记录配置变更的完整审计轨迹。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_audit_log")
public class AuditLogDO extends PhysicsTimeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作类型 */
    private String operationType;

    /** 配置类型 */
    private String configType;

    /** 配置键 */
    private String configKey;

    /** 版本 ID */
    private String versionId;

    /** 操作人 */
    private String operator;

    /** 操作时间 */
    private Date operationTime;

    /** 操作详情 JSON */
    private String detail;
}
