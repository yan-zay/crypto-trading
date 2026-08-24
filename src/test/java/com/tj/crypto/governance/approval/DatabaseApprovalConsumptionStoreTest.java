package com.tj.crypto.governance.approval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseApprovalConsumptionStoreTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void insertsParentThenEveryOneToOneReservation() {
        ApprovalConsumptionMapper mapper = mock(ApprovalConsumptionMapper.class);
        ApprovalConsumption consumption = consumption();
        when(mapper.insertConsumption("request-1", "STRATEGY_PROMOTE", HASH, HASH,
                "requester", "admin,risk", 1, 20, 10)).thenReturn(1);
        when(mapper.insertMfaReservation("reservation-admin", "request-1", "admin", 0))
                .thenReturn(1);
        when(mapper.insertMfaReservation("reservation-risk", "request-1", "risk", 1))
                .thenReturn(1);

        assertThat(new DatabaseApprovalConsumptionStore(mapper).tryConsume(consumption)).isTrue();

        var ordered = inOrder(mapper);
        ordered.verify(mapper).insertConsumption("request-1", "STRATEGY_PROMOTE", HASH, HASH,
                "requester", "admin,risk", 1, 20, 10);
        ordered.verify(mapper).insertMfaReservation(
                "reservation-admin", "request-1", "admin", 0);
        ordered.verify(mapper).insertMfaReservation(
                "reservation-risk", "request-1", "risk", 1);
    }

    @Test
    void childInsertFailureEscapesTransactionalBoundary() {
        ApprovalConsumptionMapper mapper = mock(ApprovalConsumptionMapper.class);
        ApprovalConsumption consumption = consumption();
        when(mapper.insertConsumption("request-1", "STRATEGY_PROMOTE", HASH, HASH,
                "requester", "admin,risk", 1, 20, 10)).thenReturn(1);
        when(mapper.insertMfaReservation("reservation-admin", "request-1", "admin", 0))
                .thenThrow(new IllegalStateException("duplicate replay reservation"));

        assertThatThrownBy(() -> new DatabaseApprovalConsumptionStore(mapper)
                .tryConsume(consumption)).hasMessageContaining("duplicate replay reservation");
    }

    private ApprovalConsumption consumption() {
        return new ApprovalConsumption("request-1", ApprovalAction.STRATEGY_PROMOTE,
                HASH, HASH, "requester", List.of("admin", "risk"),
                List.of(new MfaReplayReservation("admin", "reservation-admin"),
                        new MfaReplayReservation("risk", "reservation-risk")),
                1, 20, 10);
    }
}
