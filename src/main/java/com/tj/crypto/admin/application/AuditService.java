package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 审计日志服务。
 * 记录所有高风险操作，包括风控变更、策略启停、配置发布等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 记录操作审计日志。
     *
     * @param operator      操作人
     * @param operationType 操作类型（如 LOGIN, ENABLE_STRATEGY, KILL_SWITCH_ACTIVATE）
     * @param detail        操作详情
     */
    public void logOperation(String operator, String operationType, String detail) {
        AuditLogDO auditLog = new AuditLogDO();
        auditLog.setOperator(operator);
        auditLog.setOperationType(operationType);
        auditLog.setDetail(detail);
        auditLog.setOperationTime(new Date());

        auditLogMapper.insert(auditLog);
        log.info("Audit: operator={}, type={}, detail={}", operator, operationType, detail);
    }

    /**
     * 查询指定操作人的审计记录。
     */
    public List<AuditLogDO> getByOperator(String operator) {
        return auditLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLogDO>()
                        .eq(AuditLogDO::getOperator, operator)
                        .orderByDesc(AuditLogDO::getOperationTime));
    }

    /**
     * 查询指定操作类型的审计记录。
     */
    public List<AuditLogDO> getByOperationType(String operationType) {
        return auditLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLogDO>()
                        .eq(AuditLogDO::getOperationType, operationType)
                        .orderByDesc(AuditLogDO::getOperationTime));
    }
}
