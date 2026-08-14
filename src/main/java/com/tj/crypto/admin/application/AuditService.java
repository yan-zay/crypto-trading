package com.tj.crypto.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.admin.domain.AuditChainHeadDO;
import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/** Appends structured records to the serialized ADMIN audit hash chain. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private static final String CHAIN_NAME = "ADMIN";

    private final AuditLogMapper auditLogMapper;

    /** Compatibility entry point used by existing high-risk controller operations. */
    @Transactional
    public void logOperation(String operator, String operationType, String detail) {
        append(new AuditRecord(null, null, operationType, null, null,
                null, null, null, operator, "SUCCESS", null, null,
                new Date(), detail));
    }

    /**
     * Appends one record while holding the chain-head row lock. The record and
     * cursor update commit atomically in the same database transaction.
     */
    @Transactional
    public AuditLogDO append(AuditRecord record) {
        AuditChainHeadDO head = auditLogMapper.selectChainHeadForUpdate(CHAIN_NAME);
        if (head == null) throw new IllegalStateException("ADMIN audit chain head is missing");

        AuditLogDO entry = toEntity(record);
        entry.setPreviousHash(head.getLastHash());
        entry.setEntryHash(AuditHashChain.hash(entry));
        if (auditLogMapper.insert(entry) != 1 || entry.getId() == null) {
            throw new IllegalStateException("Audit record insert failed");
        }
        int advanced = auditLogMapper.advanceChain(CHAIN_NAME, entry.getId(), entry.getEntryHash(),
                head.getVersion());
        if (advanced != 1) throw new IllegalStateException("Audit chain head advance failed");
        log.info("Audit appended: id={}, operator={}, type={}, outcome={}", entry.getId(),
                entry.getOperator(), entry.getOperationType(), entry.getOutcome());
        return entry;
    }

    public List<AuditLogDO> search(String operator, String operationType, String outcome,
                                   String resourceType, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        LambdaQueryWrapper<AuditLogDO> query = new LambdaQueryWrapper<AuditLogDO>()
                .eq(hasText(operator), AuditLogDO::getOperator, operator)
                .eq(hasText(operationType), AuditLogDO::getOperationType, operationType)
                .eq(hasText(outcome), AuditLogDO::getOutcome, outcome)
                .eq(hasText(resourceType), AuditLogDO::getResourceType, resourceType)
                .orderByDesc(AuditLogDO::getId)
                .last("LIMIT " + limit);
        return auditLogMapper.selectList(query);
    }

    public List<AuditLogDO> getByOperator(String operator) {
        return search(operator, null, null, null, 500);
    }

    public List<AuditLogDO> getByOperationType(String operationType) {
        return search(null, operationType, null, null, 500);
    }

    private AuditLogDO toEntity(AuditRecord source) {
        AuditLogDO target = new AuditLogDO();
        target.setRequestId(source.requestId());
        target.setCorrelationId(source.correlationId());
        target.setOperationType(source.operationType());
        target.setResourceType(source.resourceType());
        target.setResourceId(source.resourceId());
        target.setConfigType(source.configType());
        target.setConfigKey(source.configKey());
        target.setVersionId(source.versionId());
        target.setOperator(source.operator());
        target.setOutcome(source.outcome());
        target.setSourceIp(source.sourceIp());
        target.setLatencyMs(source.latencyMs());
        target.setOperationTime(source.operationTime());
        target.setDetail(source.detail());
        return target;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
