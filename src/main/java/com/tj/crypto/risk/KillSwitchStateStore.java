package com.tj.crypto.risk;

import java.util.Optional;

/** Persistence boundary kept small so fail-closed behavior is easy to unit test. */
public interface KillSwitchStateStore {
    Optional<KillSwitchState> load();

    /**
     * Persists a transition and returns the authoritative row observed in the same transaction.
     * Returning the versioned snapshot prevents a process from applying a local relaxation on top
     * of a newer, stricter transition made by another writer.
     */
    KillSwitchState save(KillSwitch.Mode mode, String reason, String changedBy, long changedAtMs,
                         long expectedVersion);
}
