package com.tj.crypto.trading.venue.fencing;

import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionWriterLeaseServiceTest {

    @Test
    void firstOwnerReceivesTokenAndCanWrite() {
        ExecutionWriterLeaseMapper mapper = mock(ExecutionWriterLeaseMapper.class);
        when(mapper.tryAcquireOrRenew(ExecutionWriterLeaseService.GLOBAL_SCOPE, "node-a", 15_000))
                .thenReturn(1);
        when(mapper.selectState(ExecutionWriterLeaseService.GLOBAL_SCOPE)).thenReturn(row("node-a", 1));
        ExecutionWriterLeaseService service = service(mapper, new KillSwitch(), "node-a");

        assertThat(service.requireOwnership()).isEqualTo(1);
        assertThat(service.status().locallyOwned()).isTrue();
    }

    @Test
    void contenderCannotWriteAndDoesNotHaltTheCurrentOwner() {
        ExecutionWriterLeaseMapper mapper = mock(ExecutionWriterLeaseMapper.class);
        when(mapper.tryAcquireOrRenew(ExecutionWriterLeaseService.GLOBAL_SCOPE, "node-b", 15_000))
                .thenReturn(0);
        when(mapper.selectState(ExecutionWriterLeaseService.GLOBAL_SCOPE)).thenReturn(row("node-a", 1));
        KillSwitch killSwitch = new KillSwitch();

        assertThatThrownBy(() -> service(mapper, killSwitch, "node-b").requireOwnership())
                .hasMessageContaining("another execution writer");
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
    }

    @Test
    void takeoverFencesRiskUntilExplicitRecovery() {
        ExecutionWriterLeaseMapper mapper = mock(ExecutionWriterLeaseMapper.class);
        when(mapper.tryAcquireOrRenew(ExecutionWriterLeaseService.GLOBAL_SCOPE, "node-b", 15_000))
                .thenReturn(1);
        when(mapper.selectState(ExecutionWriterLeaseService.GLOBAL_SCOPE)).thenReturn(row("node-b", 2));
        KillSwitch killSwitch = new KillSwitch();

        assertThatThrownBy(() -> service(mapper, killSwitch, "node-b").requireOwnership())
                .hasMessageContaining("reconciliation");
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void heartbeatFailureAfterOwnershipForcesHalt() {
        ExecutionWriterLeaseMapper mapper = mock(ExecutionWriterLeaseMapper.class);
        when(mapper.tryAcquireOrRenew(ExecutionWriterLeaseService.GLOBAL_SCOPE, "node-a", 15_000))
                .thenReturn(1).thenThrow(new IllegalStateException("database offline"));
        when(mapper.selectState(ExecutionWriterLeaseService.GLOBAL_SCOPE)).thenReturn(row("node-a", 1));
        KillSwitch killSwitch = new KillSwitch();
        ExecutionWriterLeaseService service = service(mapper, killSwitch, "node-a");
        service.requireOwnership();

        service.renewOwnedLease();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(service.status().locallyOwned()).isFalse();
        verify(mapper, times(2)).tryAcquireOrRenew(
                ExecutionWriterLeaseService.GLOBAL_SCOPE, "node-a", 15_000);
    }

    private ExecutionWriterLeaseService service(ExecutionWriterLeaseMapper mapper,
                                                KillSwitch killSwitch, String owner) {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        return new ExecutionWriterLeaseService(mapper, properties, killSwitch, owner);
    }

    private ExecutionWriterLeaseRow row(String owner, long token) {
        ExecutionWriterLeaseRow row = new ExecutionWriterLeaseRow();
        row.setLeaseScope(ExecutionWriterLeaseService.GLOBAL_SCOPE);
        row.setOwnerId(owner);
        row.setFencingToken(token);
        row.setDatabaseNowMs(1_000);
        row.setLeaseUntilMs(20_000);
        row.setHeartbeatAtMs(1_000);
        return row;
    }
}
