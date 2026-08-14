package com.tj.crypto.risk;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistentKillSwitchTest {

    @Test
    void persistentRuntimeStartsHaltedAndRestoresDurableMode() {
        MemoryStore store = new MemoryStore();
        store.state = new KillSwitchState(KillSwitch.Mode.CLOSE_ONLY, "drawdown", "risk-engine", 10L, 4L);
        KillSwitch killSwitch = new KillSwitch(store);

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(killSwitch.isPersistenceHealthy()).isFalse();

        killSwitch.restoreFromStore();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
        assertThat(killSwitch.isPersistenceHealthy()).isTrue();
    }

    @Test
    void missingStateIsSeededAsHaltInsteadOfNormal() {
        MemoryStore store = new MemoryStore();
        KillSwitch killSwitch = new KillSwitch(store);

        killSwitch.restoreFromStore();

        assertThat(store.state.mode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(store.state.reason()).isEqualTo("FAIL_CLOSED_MISSING_STATE");
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void stateSurvivesAProcessReplacement() {
        MemoryStore store = new MemoryStore();
        KillSwitch firstProcess = new KillSwitch(store);
        firstProcess.restoreFromStore();
        firstProcess.activate(KillSwitch.Mode.CLOSE_ONLY, "daily loss", "risk-engine");

        KillSwitch replacementProcess = new KillSwitch(store);
        replacementProcess.restoreFromStore();

        assertThat(replacementProcess.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
        assertThat(store.state.reason()).isEqualTo("daily loss");
        assertThat(store.state.changedBy()).isEqualTo("risk-engine");
    }

    @Test
    void restoreFailureRemainsHalted() {
        MemoryStore store = new MemoryStore();
        store.failLoad = true;
        KillSwitch killSwitch = new KillSwitch(store);

        killSwitch.restoreFromStore();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(killSwitch.isPersistenceHealthy()).isFalse();
    }

    @Test
    void failedDeactivationForcesHaltAndSurfacesTheFailure() {
        MemoryStore store = new MemoryStore();
        store.state = new KillSwitchState(KillSwitch.Mode.CLOSE_ONLY, "risk", "system", 1L, 1L);
        KillSwitch killSwitch = new KillSwitch(store);
        killSwitch.restoreFromStore();
        store.failSave = true;

        assertThatThrownBy(killSwitch::deactivate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HALT");
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(killSwitch.isPersistenceHealthy()).isFalse();
    }

    @Test
    void cannotRelaxBeforeDurableRestore() {
        MemoryStore store = new MemoryStore();
        store.state = new KillSwitchState(KillSwitch.Mode.NORMAL, "approved", "operator", 1L, 1L);
        KillSwitch killSwitch = new KillSwitch(store);

        assertThatThrownBy(killSwitch::deactivate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restoration");
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(store.state.version()).isEqualTo(1L);
    }

    @Test
    void periodicRefreshConvergesImmediatelyToRemoteStricterState() {
        MemoryStore store = new MemoryStore();
        store.state = new KillSwitchState(KillSwitch.Mode.NORMAL, "approved", "operator", 1L, 1L);
        KillSwitch killSwitch = new KillSwitch(store);
        killSwitch.restoreFromStore();

        store.state = new KillSwitchState(KillSwitch.Mode.HALT, "remote risk", "watchdog", 2L, 2L);
        killSwitch.refreshFromStore();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(killSwitch.isPersistenceHealthy()).isTrue();
    }

    @Test
    void staleRelaxationCannotUndoFreshLocalPersistedHalt() {
        MemoryStore store = new MemoryStore();
        store.state = new KillSwitchState(KillSwitch.Mode.NORMAL, "approved", "operator", 1L, 4L);
        KillSwitch killSwitch = new KillSwitch(store);
        killSwitch.restoreFromStore();
        killSwitch.activate(KillSwitch.Mode.HALT, "local risk", "risk-engine");

        store.state = new KillSwitchState(KillSwitch.Mode.NORMAL, "stale replica", "operator", 1L, 4L);
        killSwitch.refreshFromStore();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void refreshFailureAfterReadinessForcesLocalHalt() {
        MemoryStore store = new MemoryStore();
        store.state = new KillSwitchState(KillSwitch.Mode.NORMAL, "approved", "operator", 1L, 1L);
        KillSwitch killSwitch = new KillSwitch(store);
        killSwitch.restoreFromStore();
        store.failLoad = true;

        killSwitch.refreshFromStore();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(killSwitch.isPersistenceHealthy()).isFalse();
    }

    private static final class MemoryStore implements KillSwitchStateStore {
        private KillSwitchState state;
        private boolean failLoad;
        private boolean failSave;

        @Override
        public Optional<KillSwitchState> load() {
            if (failLoad) throw new IllegalStateException("database unavailable");
            return Optional.ofNullable(state);
        }

        @Override
        public KillSwitchState save(KillSwitch.Mode mode, String reason, String changedBy,
                                    long changedAtMs, long expectedVersion) {
            if (failSave) throw new IllegalStateException("database unavailable");
            long version = state == null ? 0 : state.version() + 1;
            state = new KillSwitchState(mode, reason, changedBy, changedAtMs, version);
            return state;
        }
    }
}
