package com.tj.crypto.admin;

import com.tj.crypto.risk.KillSwitch;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminKillSwitchSafetyTest {

    @Test
    void adminEndpointPolicyOnlyAllowsIdempotentOrStricterTransitions() {
        assertThat(AdminController.isTighteningTransition(
                KillSwitch.Mode.NORMAL, KillSwitch.Mode.CLOSE_ONLY)).isTrue();
        assertThat(AdminController.isTighteningTransition(
                KillSwitch.Mode.NORMAL, KillSwitch.Mode.HALT)).isTrue();
        assertThat(AdminController.isTighteningTransition(
                KillSwitch.Mode.CLOSE_ONLY, KillSwitch.Mode.HALT)).isTrue();
        assertThat(AdminController.isTighteningTransition(
                KillSwitch.Mode.HALT, KillSwitch.Mode.HALT)).isTrue();

        assertThat(AdminController.isTighteningTransition(
                KillSwitch.Mode.HALT, KillSwitch.Mode.CLOSE_ONLY)).isFalse();
        assertThat(AdminController.isTighteningTransition(
                KillSwitch.Mode.HALT, KillSwitch.Mode.NORMAL)).isFalse();
        assertThat(AdminController.isTighteningTransition(
                KillSwitch.Mode.CLOSE_ONLY, KillSwitch.Mode.NORMAL)).isFalse();
    }
}
