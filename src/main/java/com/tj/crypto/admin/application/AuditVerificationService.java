package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.AuditChainHeadDO;
import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Recomputes every hashed audit entry and compares the result with the chain cursor. */
@Service
@RequiredArgsConstructor
public class AuditVerificationService {
    private static final String CHAIN_NAME = "ADMIN";

    private final AuditLogMapper auditLogMapper;

    @Transactional(readOnly = true)
    public AuditVerificationResult verify() {
        List<AuditLogDO> entries = auditLogMapper.selectHashedAscending();
        String expectedPrevious = AuditHashChain.GENESIS_HASH;
        long verified = 0;
        Long lastId = null;
        for (AuditLogDO entry : entries) {
            if (!Objects.equals(expectedPrevious, entry.getPreviousHash())) {
                return failed(verified, lastId, expectedPrevious, entry.getId(),
                        "previous_hash does not match the preceding entry");
            }
            String calculated = AuditHashChain.hash(entry);
            if (!Objects.equals(calculated, entry.getEntryHash())) {
                return failed(verified, lastId, expectedPrevious, entry.getId(),
                        "entry_hash does not match the canonical record");
            }
            expectedPrevious = entry.getEntryHash();
            lastId = entry.getId();
            verified++;
        }
        AuditChainHeadDO head = auditLogMapper.selectChainHead(CHAIN_NAME);
        boolean headMatches = head != null
                && Objects.equals(lastId, head.getLastAuditId())
                && Objects.equals(expectedPrevious, head.getLastHash());
        return new AuditVerificationResult(headMatches, verified, lastId, expectedPrevious,
                headMatches, null, headMatches ? "OK" : "audit_chain_head does not match entries");
    }

    private AuditVerificationResult failed(long verified, Long lastId, String lastHash,
                                           Long failedId, String message) {
        return new AuditVerificationResult(false, verified, lastId, lastHash,
                false, failedId, message);
    }
}
