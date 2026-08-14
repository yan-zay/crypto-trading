package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.trading.reconciliation.truth.VenueTruthCapabilities;
import com.tj.crypto.trading.reconciliation.truth.VenueTruthFill;
import com.tj.crypto.trading.reconciliation.truth.VenueTruthOrder;
import com.tj.crypto.trading.reconciliation.truth.VenueTruthRequest;

import java.util.List;

public interface PrivateVenueGateway {
    Exchange exchange();
    boolean configured();
    VenueOrderSnapshot place(VenueOrderCommand command);
    VenueOrderSnapshot cancel(VenueCancelCommand command);
    VenueOrderSnapshot query(VenueCancelCommand command);
    VenueAccountSnapshot account();

    /** Returns the account scope used to risk-check this instrument. */
    default VenueAccountSnapshot account(Instrument instrument) {
        return account();
    }

    /**
     * Account-wide truth capabilities used by the startup/live promotion gate.
     *
     * <p>The default is deliberately UNSUPPORTED for every dimension. An adapter must opt in
     * only after it implements an account-wide, paginated endpoint with contract tests. An
     * empty list is a valid venue fact only after the corresponding capability is supported;
     * it must never be used to represent an unimplemented endpoint.
     */
    default VenueTruthCapabilities truthCapabilities() {
        return VenueTruthCapabilities.unsupportedAll(
                "Private venue adapter has not implemented account-wide truth discovery");
    }

    default List<VenueTruthOrder> activeOrders(VenueTruthRequest request) {
        throw new UnsupportedOperationException(
                exchange() + " active-order truth discovery is unsupported");
    }

    default List<VenueTruthFill> recentFills(VenueTruthRequest request) {
        throw new UnsupportedOperationException(
                exchange() + " recent-fill truth discovery is unsupported");
    }
}
