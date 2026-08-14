package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.AuditChainHeadDO;
import com.tj.crypto.admin.domain.AuditLogDO;
import com.tj.crypto.admin.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    @Test
    void appendsRecordAndAtomicallyAdvancesChainCursor() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditChainHeadDO head = new AuditChainHeadDO();
        head.setChainName("ADMIN");
        head.setLastHash(AuditHashChain.GENESIS_HASH);
        head.setVersion(4L);
        when(mapper.selectChainHeadForUpdate("ADMIN")).thenReturn(head);
        when(mapper.insert(any(AuditLogDO.class))).thenAnswer(invocation -> {
            AuditLogDO entry = invocation.getArgument(0);
            entry.setId(42L);
            return 1;
        });
        when(mapper.advanceChain(any(), any(Long.class), any(), any(Long.class))).thenReturn(1);

        AuditService service = new AuditService(mapper);
        AuditLogDO result = service.append(new AuditRecord("req-1", "corr-1", "PLACE_ORDER",
                "PAPER_ORDER", "order-1", null, null, null, "alice", "SUCCESS",
                "127.0.0.1", 12L, new Date(1_700_000_000_123L), "{\"status\":\"FILLED\"}"));

        assertThat(result.getPreviousHash()).isEqualTo(AuditHashChain.GENESIS_HASH);
        assertThat(result.getEntryHash()).hasSize(64);
        assertThat(result.getEntryHash()).isEqualTo(AuditHashChain.hash(result));
        verify(mapper).advanceChain("ADMIN", 42L, result.getEntryHash(), 4L);
    }

    @Test
    void hashChangesWhenAuditedContentIsTampered() {
        AuditLogDO entry = new AuditLogDO();
        entry.setPreviousHash(AuditHashChain.GENESIS_HASH);
        entry.setOperationType("HTTP_POST");
        entry.setOperator("alice");
        entry.setOutcome("SUCCESS");
        entry.setOperationTime(new Date(1234));
        entry.setDetail("{\"status\":200}");
        String original = AuditHashChain.hash(entry);

        entry.setOutcome("FAILURE");

        assertThat(AuditHashChain.hash(entry)).isNotEqualTo(original);
    }
}
