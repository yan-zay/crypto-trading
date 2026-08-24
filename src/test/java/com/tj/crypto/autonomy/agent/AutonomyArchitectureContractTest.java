package com.tj.crypto.autonomy.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AutonomyArchitectureContractTest {

    @Test
    void publicServicesExposeNoExecutionPrivilegeOrPolicyMutationApi() {
        Set<String> l3Methods = publicMethodNames(L3LiveAdviceService.class);
        Set<String> l4Methods = publicMethodNames(L4BoundedAutonomyService.class);

        assertThat(l3Methods).containsExactlyInAnyOrder("approve", "revokeProposal", "revokeApprovalToken");
        assertThat(l4Methods).containsExactlyInAnyOrder(
                "authorize", "consumeCapability", "revokeProposal",
                "revokePolicyGrantToken", "revokeCapability");
        assertThat(Arrays.stream(TradeProposalAction.values()).map(Enum::name))
                .noneMatch(name -> containsAny(name, "PROMOTE", "PERMISSION", "RISK_POLICY",
                        "KEY", "SECRET", "WITHDRAW", "GATEWAY", "ORDER"));
    }

    @Test
    void proposalAndCapabilityTypesHaveNoGatewayOrExecutionDependencies() {
        Set<String> componentTypes = Arrays.stream(TradeProposal.class.getRecordComponents())
                .map(RecordComponent::getType).map(Class::getName).collect(Collectors.toSet());
        componentTypes.addAll(Arrays.stream(L4CapabilityToken.class.getRecordComponents())
                .map(RecordComponent::getType).map(Class::getName).toList());

        assertThat(componentTypes).noneMatch(type -> containsAny(type,
                ".execution.", ".trading.venue.", "LiveOrder", "Gateway", "Signer", "Secret"));
    }

    @Test
    void adviceAndConsumptionReceiptsCannotBeConstructedOutsidePackage() {
        assertThat(Arrays.stream(ApprovedLiveAdvice.class.getDeclaredConstructors()))
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()));
        assertThat(Arrays.stream(CapabilityConsumption.class.getDeclaredConstructors()))
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()));
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .collect(Collectors.toSet());
    }

    private static boolean containsAny(String value, String... fragments) {
        return Arrays.stream(fragments).anyMatch(value::contains);
    }
}
