package com.tj.crypto.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DrawdownGuardTest {

    private KillSwitch killSwitch;
    private DrawdownGuard guard;

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch();
        guard = new DrawdownGuard(killSwitch, BigDecimal.valueOf(20));
    }

    @Test
    @DisplayName("回撤在阈值内时不应触发熔断")
    void shouldNotTriggerWhenDrawdownWithinLimit() {
        // 10% drawdown: (10000 - 9000) / 10000 = 10%
        boolean triggered = guard.checkDrawdown(
                BigDecimal.valueOf(9000), BigDecimal.valueOf(10000));

        assertThat(triggered).isFalse();
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    @DisplayName("回撤达到阈值时应触发 CLOSE_ONLY 熔断")
    void shouldTriggerCloseOnlyWhenDrawdownReachesThreshold() {
        // 20% drawdown: (10000 - 8000) / 10000 = 20%
        boolean triggered = guard.checkDrawdown(
                BigDecimal.valueOf(8000), BigDecimal.valueOf(10000));

        assertThat(triggered).isTrue();
        assertThat(killSwitch.isActive()).isTrue();
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
    }

    @Test
    @DisplayName("回撤超过阈值时应触发熔断")
    void shouldTriggerWhenDrawdownExceedsThreshold() {
        // 30% drawdown: (10000 - 7000) / 10000 = 30%
        boolean triggered = guard.checkDrawdown(
                BigDecimal.valueOf(7000), BigDecimal.valueOf(10000));

        assertThat(triggered).isTrue();
        assertThat(killSwitch.isActive()).isTrue();
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
    }

    @Test
    @DisplayName("peakEquity 为零时不应触发熔断")
    void shouldNotTriggerWhenPeakEquityIsZero() {
        boolean triggered = guard.checkDrawdown(
                BigDecimal.valueOf(0), BigDecimal.valueOf(0));

        assertThat(triggered).isFalse();
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    @DisplayName("peakEquity 为 null 时不应触发熔断")
    void shouldNotTriggerWhenPeakEquityIsNull() {
        boolean triggered = guard.checkDrawdown(
                BigDecimal.valueOf(5000), null);

        assertThat(triggered).isFalse();
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    @DisplayName("回撤为 0% 时不应触发熔断")
    void shouldNotTriggerWhenNoDrawdown() {
        // 0% drawdown: equity == peak
        boolean triggered = guard.checkDrawdown(
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10000));

        assertThat(triggered).isFalse();
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    @DisplayName("多次检查应在每次超限时都返回 true")
    void shouldReturnTrueOnEveryExceedingCheck() {
        BigDecimal peak = BigDecimal.valueOf(10000);

        // 第一次：刚好 20%
        boolean first = guard.checkDrawdown(BigDecimal.valueOf(8000), peak);
        assertThat(first).isTrue();

        // 第二次：30%
        boolean second = guard.checkDrawdown(BigDecimal.valueOf(7000), peak);
        assertThat(second).isTrue();

        // KillSwitch 保持 CLOSE_ONLY
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
    }

    @Test
    @DisplayName("自定义阈值应正确生效")
    void shouldRespectCustomThreshold() {
        DrawdownGuard strictGuard = new DrawdownGuard(killSwitch, BigDecimal.valueOf(10));

        // 15% drawdown against 10% threshold
        boolean triggered = strictGuard.checkDrawdown(
                BigDecimal.valueOf(8500), BigDecimal.valueOf(10000));

        assertThat(triggered).isTrue();
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
    }
}
