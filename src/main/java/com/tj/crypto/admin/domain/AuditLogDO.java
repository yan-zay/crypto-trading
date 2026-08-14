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

    /** Request identifier returned to the caller. */
    private String requestId;

    /** Cross-service correlation identifier. */
    private String correlationId;

    /** 操作类型 */
    private String operationType;

    /** Logical resource family, for example PAPER_TRADING. */
    private String resourceType;

    /** Resource key or path below the resource family. */
    private String resourceId;

    /** 配置类型 */
    private String configType;

    /** 配置键 */
    private String configKey;

    /** 版本 ID */
    private String versionId;

    /** 操作人 */
    private String operator;

    /** SUCCESS or FAILURE. */
    private String outcome;

    /** Direct peer address. Forwarded headers are deliberately not trusted here. */
    private String sourceIp;

    /** End-to-end controller request latency. */
    private Long latencyMs;

    /** 操作时间 */
    private Date operationTime;

    /** 操作详情 JSON */
    private String detail;

    /** Previous entry hash in the ADMIN audit chain. */
    private String previousHash;

    /** SHA-256 over the previous hash and canonical record fields. */
    private String entryHash;
}
