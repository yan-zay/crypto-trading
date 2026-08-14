package com.tj.crypto.risk.persistence;

import com.tj.crypto.risk.KillSwitch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseKillSwitchStateStoreTest {
    @Mock
    private KillSwitchStateMapper mapper;

    @Test
    void mapsTheSchemaProjectionAndUpsertsByStableGlobalKey() {
        KillSwitchStateDO row = new KillSwitchStateDO();
        row.setStateKey(KillSwitchStateMapper.GLOBAL_KEY);
        row.setMode("CLOSE_ONLY");
        row.setReason("drawdown");
        row.setChangedBy("risk-engine");
        row.setChangedAtMs(123L);
        row.setStateVersion(7L);
        KillSwitchStateDO saved = new KillSwitchStateDO();
        saved.setStateKey(KillSwitchStateMapper.GLOBAL_KEY);
        saved.setMode("HALT");
        saved.setReason("operator request");
        saved.setChangedBy("alice");
        saved.setChangedAtMs(456L);
        saved.setStateVersion(8L);
        when(mapper.selectGlobal()).thenReturn(row, saved);
        when(mapper.selectGlobalForUpdate()).thenReturn(row);
        when(mapper.updateGlobalAtVersion(
                "HALT", "operator request", "alice", 456L, 7L)).thenReturn(1);
        DatabaseKillSwitchStateStore store = new DatabaseKillSwitchStateStore(mapper);

        assertThat(store.load()).hasValueSatisfying(state -> {
            assertThat(state.mode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
            assertThat(state.version()).isEqualTo(7L);
        });
        assertThat(store.save(KillSwitch.Mode.HALT, "operator request", "alice", 456L, 7L))
                .satisfies(state -> {
                    assertThat(state.mode()).isEqualTo(KillSwitch.Mode.HALT);
                    assertThat(state.version()).isEqualTo(8L);
                });

        verify(mapper).updateGlobalAtVersion(
                "HALT", "operator request", "alice", 456L, 7L);
    }

    @Test
    void rejectsCorruptPersistedModeSoRuntimeCanFailClosed() {
        KillSwitchStateDO row = new KillSwitchStateDO();
        row.setMode("NOT_A_MODE");
        when(mapper.selectGlobal()).thenReturn(row);

        assertThatThrownBy(() -> new DatabaseKillSwitchStateStore(mapper).load())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroRowWriteIsAStoreFailure() {
        KillSwitchStateDO row = state("NORMAL", 1L);
        when(mapper.selectGlobalForUpdate()).thenReturn(row);
        when(mapper.updateGlobalAtVersion("NORMAL", "approved", "alice", 1L, 1L))
                .thenReturn(0);

        assertThatThrownBy(() -> new DatabaseKillSwitchStateStore(mapper)
                .save(KillSwitch.Mode.NORMAL, "approved", "alice", 1L, 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingRowAfterWriteIsAStoreFailure() {
        KillSwitchStateDO row = state("CLOSE_ONLY", 2L);
        when(mapper.selectGlobalForUpdate()).thenReturn(row);
        when(mapper.updateGlobalAtVersion("HALT", "risk", "system", 2L, 2L))
                .thenReturn(1);
        when(mapper.selectGlobal()).thenReturn(null);

        assertThatThrownBy(() -> new DatabaseKillSwitchStateStore(mapper)
                .save(KillSwitch.Mode.HALT, "risk", "system", 2L, 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disappeared");
    }

    @Test
    void staleProcessCannotRelaxANewerStricterState() {
        KillSwitchStateDO row = state("HALT", 9L);
        when(mapper.selectGlobalForUpdate()).thenReturn(row);

        assertThatThrownBy(() -> new DatabaseKillSwitchStateStore(mapper)
                .save(KillSwitch.Mode.NORMAL, "stale approval", "alice", 3L, 8L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prevents relaxation");
    }

    private KillSwitchStateDO state(String mode, long version) {
        KillSwitchStateDO row = new KillSwitchStateDO();
        row.setStateKey(KillSwitchStateMapper.GLOBAL_KEY);
        row.setMode(mode);
        row.setReason("state");
        row.setChangedBy("test");
        row.setChangedAtMs(1L);
        row.setStateVersion(version);
        return row;
    }
}
