package com.tj.crypto.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局熔断开关。
 * 紧急情况下可一键停止所有交易活动。
 *
 * <p>模式：
 * <ul>
 *   <li>NORMAL — 正常交易</li>
 *   <li>CLOSE_ONLY — 只允许平仓，禁止开新仓</li>
 *   <li>HALT — 全部停止，拒绝所有订单</li>
 * </ul>
 *
 * <p>线程安全：使用 AtomicReference 保证并发读写安全。
 */
@Slf4j
@Component
public class KillSwitch {

    private static final String DEFAULT_ACTOR = "SYSTEM";

    private final AtomicReference<Mode> mode;
    private final KillSwitchStateStore stateStore;
    private volatile boolean persistenceHealthy;
    private volatile boolean durableStateRestored;
    private volatile long lastObservedVersion;

    /**
     * Compatibility constructor for isolated engines and pure unit tests.
     * It deliberately preserves the historical in-memory NORMAL default.
     */
    public KillSwitch() {
        this.stateStore = null;
        this.mode = new AtomicReference<>(Mode.NORMAL);
        this.persistenceHealthy = true;
        this.durableStateRestored = true;
        this.lastObservedVersion = -1L;
    }

    /** Spring runtime constructor: remain HALT until the durable row is restored. */
    @Autowired
    public KillSwitch(KillSwitchStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.mode = new AtomicReference<>(Mode.HALT);
        this.persistenceHealthy = false;
        this.durableStateRestored = false;
        this.lastObservedVersion = -1L;
    }

    /**
     * 熔断模式。
     */
    public enum Mode {
        /** 正常交易 */
        NORMAL,
        /** 只允许平仓 */
        CLOSE_ONLY,
        /** 全部停止 */
        HALT
    }

    /**
     * 激活熔断，切换到指定模式。
     *
     * @param targetMode 目标模式
     */
    public void activate(Mode targetMode) {
        activate(targetMode, "MANUAL_ACTIVATION", DEFAULT_ACTOR);
    }

    /** Persist first; an unavailable store always leaves the process in HALT. */
    public synchronized void activate(Mode targetMode, String reason, String changedBy) {
        transition(Objects.requireNonNull(targetMode, "targetMode"), reason, changedBy);
    }

    /**
     * 解除熔断，恢复为 NORMAL 模式。
     */
    public void deactivate() {
        deactivate("MANUAL_DEACTIVATION", DEFAULT_ACTOR);
    }

    public synchronized void deactivate(String reason, String changedBy) {
        transition(Mode.NORMAL, reason, changedBy);
    }

    /**
     * 当前是否处于非 NORMAL 状态。
     */
    public boolean isActive() {
        return mode.get() != Mode.NORMAL;
    }

    /**
     * 获取当前模式。
     */
    public Mode getMode() {
        return mode.get();
    }

    /** True for the compatibility in-memory instance or after a successful durable operation. */
    public boolean isPersistenceHealthy() {
        return persistenceHealthy;
    }

    /** Whether this process has successfully loaded or initialized the durable singleton row. */
    public boolean isDurableStateRestored() {
        return durableStateRestored;
    }

    /**
     * Restores the singleton after Flyway and the application context are ready.
     * Missing/corrupt/unavailable state is initialized or retained as HALT.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public synchronized void restoreFromStore() {
        if (stateStore == null) return;
        refreshFromStore();
    }

    /**
     * Converges every Spring process on the durable singleton instead of treating startup as the
     * only synchronization point. A stale snapshot may tighten the local process, but it may never
     * relax it; relaxation requires a strictly newer durable version.
     */
    @Scheduled(fixedDelayString = "${crypto.risk.kill-switch-refresh-interval-ms:1000}")
    public synchronized void refreshFromStore() {
        if (stateStore == null) return;
        try {
            KillSwitchState stored = stateStore.load().orElse(null);
            if (stored == null) {
                long now = System.currentTimeMillis();
                stored = stateStore.save(Mode.HALT, "FAIL_CLOSED_MISSING_STATE", DEFAULT_ACTOR,
                        now, lastObservedVersion);
                requireAtLeast(stored, Mode.HALT);
                log.error("[KILL_SWITCH] Durable state was missing; initialized as HALT");
            }
            applySnapshot(stored, !durableStateRestored);
            persistenceHealthy = true;
            durableStateRestored = true;
        } catch (RuntimeException e) {
            mode.set(Mode.HALT);
            persistenceHealthy = false;
            log.error("[KILL_SWITCH] Durable state refresh failed; forcing local HALT", e);
        }
    }

    private void transition(Mode targetMode, String reason, String changedBy) {
        String safeReason = text(reason, "UNSPECIFIED", 500);
        String safeActor = text(changedBy, DEFAULT_ACTOR, 100);
        Mode previous = mode.get();
        if (stateStore != null && severity(targetMode) < severity(previous)
                && (!durableStateRestored || !persistenceHealthy)) {
            mode.set(Mode.HALT);
            throw new IllegalStateException(
                    "Kill-switch cannot be relaxed before healthy durable state restoration");
        }
        if (stateStore != null) {
            try {
                KillSwitchState persisted = stateStore.save(
                        targetMode, safeReason, safeActor, System.currentTimeMillis(),
                        lastObservedVersion);
                requireAtLeast(persisted, targetMode);
                applySnapshot(persisted, false);
                persistenceHealthy = true;
                durableStateRestored = true;
            } catch (RuntimeException e) {
                mode.set(Mode.HALT);
                persistenceHealthy = false;
                log.error("[KILL_SWITCH] Failed to persist {} -> {}; forcing local HALT",
                        previous, targetMode, e);
                throw new IllegalStateException("Kill-switch persistence failed; trading is HALT", e);
            }
        } else {
            mode.set(targetMode);
        }
        log.warn("[KILL_SWITCH] Changed: {} -> {}, actor={}, reason={}",
                previous, mode.get(), safeActor, safeReason);
    }

    private void applySnapshot(KillSwitchState stored, boolean initialRestore) {
        Objects.requireNonNull(stored, "stored");
        Mode current = mode.get();
        long observedVersion = lastObservedVersion;
        boolean newer = stored.version() > observedVersion;
        boolean stricter = severity(stored.mode()) > severity(current);
        if (initialRestore || newer || stricter) {
            mode.set(stored.mode());
            if (current != stored.mode()) {
                log.warn("[KILL_SWITCH] Durable convergence: {} -> {}, version={}, changedBy={}, reason={}",
                        current, stored.mode(), stored.version(), stored.changedBy(), stored.reason());
            }
        } else if (stored.mode() != current) {
            log.warn("[KILL_SWITCH] Ignored stale relaxation: local={}, durable={}, durableVersion={}, "
                            + "lastObservedVersion={}",
                    current, stored.mode(), stored.version(), observedVersion);
        }
        lastObservedVersion = Math.max(observedVersion, stored.version());
    }

    private void requireAtLeast(KillSwitchState stored, Mode requested) {
        if (stored == null || severity(stored.mode()) < severity(requested)) {
            throw new IllegalStateException("Durable kill-switch state is less restrictive than requested");
        }
    }

    private int severity(Mode value) {
        return switch (value) {
            case NORMAL -> 0;
            case CLOSE_ONLY -> 1;
            case HALT -> 2;
        };
    }

    private String text(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
