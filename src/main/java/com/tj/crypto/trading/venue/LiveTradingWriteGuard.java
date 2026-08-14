package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Fail-closed write gate. Credentials alone never enable live orders. */
@Component
@RequiredArgsConstructor
public class LiveTradingWriteGuard {
    private final PrivateTradingProperties properties;

    public void requireWriteEnabled(Exchange exchange) {
        requireMutationEnabled(exchange, false);
    }

    /** Cancellation is risk-reducing, but still requires an enabled and approved venue mode. */
    public void requireCancelEnabled(Exchange exchange) {
        requireMutationEnabled(exchange, true);
    }

    private void requireMutationEnabled(Exchange exchange, boolean cancellation) {
        properties.validateStartupPolicy();
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.RESEARCH_ONLY) {
            throw new IllegalStateException("Venue writes are forbidden in RESEARCH_ONLY mode");
        }
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.SANDBOX) {
            boolean sandboxVenue = switch (exchange) {
                case BINANCE -> properties.getBinance().isTestnet();
                case OKX -> properties.getOkx().isSimulatedTrading();
                case COINGLASS -> false;
            };
            if (!sandboxVenue) {
                throw new IllegalStateException("SANDBOX writes require a venue testnet/demo endpoint for " + exchange);
            }
        }
        if (properties.getOperatingMode() == PrivateTradingProperties.OperatingMode.LIVE_CANARY
                && (properties.getTargetJurisdiction() == null
                || properties.getTargetJurisdiction().isBlank()
                || properties.getLegalApprovalReference() == null
                || properties.getLegalApprovalReference().isBlank())) {
            throw new IllegalStateException(
                    "LIVE_CANARY mutations require target-jurisdiction and legal-approval-reference");
        }
        boolean venueEnabled = switch (exchange) {
            case BINANCE -> properties.getBinance().isEnabled()
                    && (cancellation || properties.getBinance().isWriteEnabled());
            case OKX -> properties.getOkx().isEnabled()
                    && (cancellation || properties.getOkx().isWriteEnabled());
            case COINGLASS -> false;
        };
        if ((!cancellation && !properties.isLiveWriteEnabled()) || !venueEnabled) {
            throw new IllegalStateException("Live trading writes are disabled for " + exchange);
        }
    }

    /**
     * This helper lets readiness and recovery code inspect effective write authorization without
     * inferring it from credential presence.
     */
    public boolean liveWritesEnabled(Exchange exchange) {
        try {
            requireWriteEnabled(exchange);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }
}
