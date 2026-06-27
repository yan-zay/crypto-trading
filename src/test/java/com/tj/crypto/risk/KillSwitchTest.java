package com.tj.crypto.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KillSwitchTest {

    private KillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch();
    }

    @Test
    @DisplayName("初始状态应为 NORMAL 且未激活")
    void shouldStartInNormalMode() {
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    @DisplayName("activate(HALT) 应切换到 HALT 模式")
    void shouldActivateHaltMode() {
        killSwitch.activate(KillSwitch.Mode.HALT);

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(killSwitch.isActive()).isTrue();
    }

    @Test
    @DisplayName("activate(CLOSE_ONLY) 应切换到 CLOSE_ONLY 模式")
    void shouldActivateCloseOnlyMode() {
        killSwitch.activate(KillSwitch.Mode.CLOSE_ONLY);

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);
        assertThat(killSwitch.isActive()).isTrue();
    }

    @Test
    @DisplayName("deactivate 应恢复为 NORMAL 模式")
    void shouldDeactivateToNormal() {
        killSwitch.activate(KillSwitch.Mode.HALT);
        assertThat(killSwitch.isActive()).isTrue();

        killSwitch.deactivate();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    @DisplayName("多次 activate 应覆盖前一个模式")
    void shouldOverridePreviousMode() {
        killSwitch.activate(KillSwitch.Mode.HALT);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);

        killSwitch.activate(KillSwitch.Mode.CLOSE_ONLY);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.CLOSE_ONLY);

        killSwitch.activate(KillSwitch.Mode.NORMAL);
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivate 未激活时应保持 NORMAL")
    void shouldStayNormalWhenDeactivateNotActive() {
        killSwitch.deactivate();

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
        assertThat(killSwitch.isActive()).isFalse();
    }
}
