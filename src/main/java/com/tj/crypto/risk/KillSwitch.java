package com.tj.crypto.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);

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
        Mode previous = mode.getAndSet(targetMode);
        log.warn("[KILL_SWITCH] Activated: {} → {}", previous, targetMode);
    }

    /**
     * 解除熔断，恢复为 NORMAL 模式。
     */
    public void deactivate() {
        Mode previous = mode.getAndSet(Mode.NORMAL);
        log.warn("[KILL_SWITCH] Deactivated: {} → NORMAL", previous);
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
}
