package com.tj.crypto.trading.venue;

import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.governance.approval.DualControlRequest;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationBudget;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic boundary for approval consumption, governance decision, OMS fact and risk reservation. */
@Service
@RequiredArgsConstructor
public class LiveGovernedOrderReservationService {
    private final LiveTradingWriteGuard writeGuard;
    private final LiveGovernanceDecisionStore decisionStore;
    private final LiveRiskReservationService riskReservationService;

    @Transactional
    public LiveOrderAuthorization authorizeReserveAndRecord(
            Order order, OrderEvent createdEvent, OmsFillMetadata metadata,
            LiveRiskReservationBudget budget, VenueOrderCommand command,
            DualControlRequest approval, String authenticatedOperator) {
        LiveOrderAuthorization authorization = writeGuard.authorizeOrder(
                command, budget, approval, authenticatedOperator);
        if (authorization.liveGoverned()) {
            decisionStore.append(authorization.governanceDecision());
        }
        riskReservationService.reserveAndRecord(order, createdEvent, metadata, budget,
                LiveOrderGovernanceBinding.from(authorization));
        return authorization;
    }
}
