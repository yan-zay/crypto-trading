package com.tj.crypto.trading.venue.riskreservation;

import lombok.Data;

import java.math.BigDecimal;

/** Database row locked while a new live order consumes gross-notional budget. */
@Data
public class LiveRiskReservationDO {
    private String orderId;
    private String accountId;
    private String exchange;
    private String marketType;
    private String symbol;
    private Boolean riskIncreasing;
    private BigDecimal originalQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal referencePrice;
    private BigDecimal originalNotional;
    private BigDecimal remainingNotional;
    private String reservationStatus;
    private String lastOrderStatus;
    private Long snapshotEventTimeMs;
    private Long releasedAtMs;
    private Long stateVersion;
}
