package com.tj.crypto.risk.persistence;

import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.risk.KillSwitchState;
import com.tj.crypto.risk.KillSwitchStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** MySQL-backed singleton state store. Flyway V12 adds the table for existing databases. */
@Repository
@RequiredArgsConstructor
public class DatabaseKillSwitchStateStore implements KillSwitchStateStore {
    private final KillSwitchStateMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<KillSwitchState> load() {
        KillSwitchStateDO stored = mapper.selectGlobal();
        if (stored == null) return Optional.empty();
        return Optional.of(map(stored));
    }

    @Override
    @Transactional
    public KillSwitchState save(KillSwitch.Mode mode, String reason, String changedBy,
                                long changedAtMs, long expectedVersion) {
        KillSwitchStateDO current = mapper.selectGlobalForUpdate();
        if (current == null) {
            if (mapper.insertGlobal(mode.name(), reason, changedBy, changedAtMs) != 1) {
                throw new IllegalStateException("Kill-switch state was not initialized");
            }
        } else {
            KillSwitch.Mode currentMode = KillSwitch.Mode.valueOf(current.getMode());
            long currentVersion = value(current.getStateVersion());
            if (severity(mode) < severity(currentMode) && currentVersion != expectedVersion) {
                throw new IllegalStateException(
                        "Concurrent stricter kill-switch state prevents relaxation");
            }
            if (mapper.updateGlobalAtVersion(mode.name(), reason, changedBy, changedAtMs,
                    currentVersion) != 1) {
                throw new IllegalStateException("Kill-switch state version changed during transition");
            }
        }
        KillSwitchStateDO stored = mapper.selectGlobal();
        if (stored == null) {
            throw new IllegalStateException("Kill-switch state disappeared after persistence");
        }
        return map(stored);
    }

    private int severity(KillSwitch.Mode mode) {
        return switch (mode) {
            case NORMAL -> 0;
            case CLOSE_ONLY -> 1;
            case HALT -> 2;
        };
    }

    private KillSwitchState map(KillSwitchStateDO stored) {
        KillSwitch.Mode mode = KillSwitch.Mode.valueOf(stored.getMode());
        return new KillSwitchState(mode, stored.getReason(), stored.getChangedBy(),
                value(stored.getChangedAtMs()), value(stored.getStateVersion()));
    }

    private long value(Long source) {
        return source == null ? 0L : source;
    }
}
