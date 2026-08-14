package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.AuditChainHeadDO;
import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditVerificationServiceTest {

    @Test
    void verifiesEntriesAndChainHead() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditLogDO first = entry(1L, AuditHashChain.GENESIS_HASH, "START");
        AuditLogDO second = entry(2L, first.getEntryHash(), "STOP");
        AuditChainHeadDO head = new AuditChainHeadDO();
        head.setLastAuditId(2L);
        head.setLastHash(second.getEntryHash());
        when(mapper.selectHashedAscending()).thenReturn(List.of(first, second));
        when(mapper.selectChainHead("ADMIN")).thenReturn(head);

        AuditVerificationResult result = new AuditVerificationService(mapper).verify();

        assertThat(result.valid()).isTrue();
        assertThat(result.verifiedEntries()).isEqualTo(2);
        assertThat(result.chainHeadMatches()).isTrue();
    }

    @Test
    void identifiesFirstTamperedEntry() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditLogDO first = entry(1L, AuditHashChain.GENESIS_HASH, "START");
        first.setDetail("tampered");
        when(mapper.selectHashedAscending()).thenReturn(List.of(first));

        AuditVerificationResult result = new AuditVerificationService(mapper).verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.failedAuditId()).isEqualTo(1L);
        assertThat(result.message()).contains("entry_hash");
    }

    private AuditLogDO entry(long id, String previousHash, String operation) {
        AuditLogDO entry = new AuditLogDO();
        entry.setId(id);
        entry.setPreviousHash(previousHash);
        entry.setOperationType(operation);
        entry.setOperator("system");
        entry.setOutcome("SUCCESS");
        entry.setOperationTime(new Date(id * 1000));
        entry.setEntryHash(AuditHashChain.hash(entry));
        return entry;
    }
}
