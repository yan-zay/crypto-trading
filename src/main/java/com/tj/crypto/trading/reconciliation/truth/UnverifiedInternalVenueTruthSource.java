package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.Exchange;
import org.springframework.stereotype.Component;

/**
 * Explicit production NO-GO until a durable live account projection is implemented.
 *
 * <p>The current OMS can query individual known orders, but it is not an account-wide,
 * independently complete projection of venue trades, balances and positions. Claiming support
 * here would turn absence of implementation into a false empty snapshot.
 */
@Component
public class UnverifiedInternalVenueTruthSource implements InternalVenueTruthSource {
    @Override
    public VenueTruthCapabilities capabilities(Exchange exchange) {
        return VenueTruthCapabilities.unsupportedAll(
                "Durable account-wide live order/trade/balance/position projection is not implemented");
    }
}
