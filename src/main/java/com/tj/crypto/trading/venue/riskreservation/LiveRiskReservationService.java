package com.tj.crypto.trading.venue.riskreservation;

import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.storage.service.OmsPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Commits the initial OMS fact and its gross-notional reservation atomically.
 *
 * <p>The scope row is the cross-JVM mutex. All budget arithmetic happens after that row is
 * locked and after older reservation rows have been read with {@code FOR UPDATE}.</p>
 */
@Service
@RequiredArgsConstructor
public class LiveRiskReservationService {
    private final LiveRiskReservationMapper mapper;
    private final OmsPersistenceService persistenceService;

    @Transactional
    public void reserveAndRecord(Order order, OrderEvent createdEvent,
                                 OmsFillMetadata metadata,
                                 LiveRiskReservationBudget budget) {
        validateInputs(order, createdEvent, metadata, budget);
        String accountId = budget.accountId().trim();
        String exchange = budget.exchange().name();

        mapper.ensureScope(accountId, exchange);
        if (mapper.lockScope(accountId, exchange) == null) {
            throw new IllegalStateException("Live risk reservation scope could not be locked");
        }

        // INSERT first so a duplicate clientOrderId fails before a replay can be counted as a
        // second reservation. Any later budget failure rolls this insert back with the transaction.
        persistenceService.recordInitialLiveCommand(order, createdEvent, metadata);

        List<LiveRiskReservationDO> blocking = mapper.selectBlockingForUpdate(accountId, exchange);
        if (budget.riskIncreasing()) {
            List<String> gaps = mapper.selectUnreservedActiveOrderIds(
                    accountId, exchange, order.orderId());
            if (!gaps.isEmpty()) {
                throw new IllegalStateException(
                        "Active live OMS order has no durable risk reservation: " + gaps.get(0));
            }
            for (LiveRiskReservationDO row : blocking) {
                if ("UNVALUED".equalsIgnoreCase(row.getReservationStatus())
                        || (Boolean.TRUE.equals(row.getRiskIncreasing())
                        && nonPositive(row.getRemainingNotional()))) {
                    throw new IllegalStateException(
                            "Existing live risk reservation cannot be valued: " + row.getOrderId());
                }
            }
            BigDecimal reservedPortfolio = blocking.stream()
                    .filter(row -> Boolean.TRUE.equals(row.getRiskIncreasing()))
                    .map(LiveRiskReservationDO::getRemainingNotional)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reservedSymbol = blocking.stream()
                    .filter(row -> Boolean.TRUE.equals(row.getRiskIncreasing()))
                    .filter(row -> sameInstrument(row, budget))
                    .map(LiveRiskReservationDO::getRemainingNotional)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            requireWithin("single-order", budget.orderNotional(),
                    budget.maxSingleOrderNotional());
            requireWithin("per-symbol",
                    budget.currentSymbolGrossNotional()
                            .add(reservedSymbol).add(budget.orderNotional()),
                    budget.maxSymbolGrossNotional());
            requireWithin("portfolio",
                    budget.currentPortfolioGrossNotional()
                            .add(reservedPortfolio).add(budget.orderNotional()),
                    budget.maxPortfolioGrossNotional());
        }

        if (mapper.insert(toRow(order, budget)) != 1) {
            throw new IllegalStateException("Live risk reservation was not inserted");
        }
        if (mapper.touchScope(accountId, exchange, createdEvent.timestamp()) != 1) {
            throw new IllegalStateException("Live risk reservation scope was not advanced");
        }
    }

    private void validateInputs(Order order, OrderEvent event, OmsFillMetadata metadata,
                                LiveRiskReservationBudget budget) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(event, "createdEvent");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(budget, "budget");
        require(order.status() == OrderStatus.CREATED,
                "Initial live risk reservation requires a CREATED order");
        require(event.eventType() == OrderEvent.EventType.CREATED
                        && order.orderId().equals(event.orderId()),
                "Initial live risk reservation requires the matching CREATED event");
        require(budget.accountId() != null && !budget.accountId().isBlank(),
                "Live risk reservation account is required");
        require(budget.accountId().equals(metadata.accountId()),
                "Live risk reservation account does not match OMS metadata");
        require(budget.exchange() == order.instrument().exchange()
                        && budget.marketType() == order.instrument().marketType()
                        && canonical(budget.symbol()).equals(canonical(order.instrument().symbol())),
                "Live risk reservation instrument does not match the OMS order");
        require(positive(budget.originalQuantity())
                        && budget.originalQuantity().compareTo(order.quantity()) == 0,
                "Live risk reservation quantity does not match the OMS order");
        require(positive(budget.referencePrice())
                        && budget.referencePrice().compareTo(order.price()) == 0,
                "Live risk reservation price does not match the OMS order");
        require(budget.snapshotEventTimeMs() > 0,
                "Live risk reservation requires a signed snapshot timestamp");

        if (budget.riskIncreasing()) {
            require(positive(budget.orderNotional()),
                    "Risk-increasing live reservation notional must be positive");
            require(budget.orderNotional().compareTo(
                    budget.originalQuantity().multiply(budget.referencePrice())) == 0,
                    "Live risk reservation notional is inconsistent");
            require(nonNegative(budget.currentSymbolGrossNotional())
                            && nonNegative(budget.currentPortfolioGrossNotional()),
                    "Current live gross exposure must not be negative");
            require(positive(budget.maxSingleOrderNotional())
                            && positive(budget.maxSymbolGrossNotional())
                            && positive(budget.maxPortfolioGrossNotional()),
                    "Live gross-notional limits must be positive");
        } else {
            require(order.reduceOnly(),
                    "Only a validated reduce-only order may bypass opening-risk reservation");
        }
    }

    private LiveRiskReservationDO toRow(Order order, LiveRiskReservationBudget budget) {
        LiveRiskReservationDO row = new LiveRiskReservationDO();
        row.setOrderId(order.orderId());
        row.setAccountId(budget.accountId().trim());
        row.setExchange(budget.exchange().name());
        row.setMarketType(budget.marketType().name());
        row.setSymbol(canonical(budget.symbol()));
        row.setRiskIncreasing(budget.riskIncreasing());
        row.setOriginalQuantity(budget.originalQuantity());
        row.setRemainingQuantity(budget.originalQuantity());
        row.setReferencePrice(budget.referencePrice());
        row.setOriginalNotional(budget.riskIncreasing()
                ? budget.orderNotional() : BigDecimal.ZERO);
        row.setRemainingNotional(budget.riskIncreasing()
                ? budget.orderNotional() : BigDecimal.ZERO);
        row.setReservationStatus("ACTIVE");
        row.setLastOrderStatus(order.status().name());
        row.setSnapshotEventTimeMs(budget.snapshotEventTimeMs());
        return row;
    }

    private boolean sameInstrument(LiveRiskReservationDO row,
                                   LiveRiskReservationBudget budget) {
        return budget.marketType().name().equalsIgnoreCase(row.getMarketType())
                && canonical(budget.symbol()).equals(canonical(row.getSymbol()));
    }

    private void requireWithin(String name, BigDecimal actual, BigDecimal maximum) {
        if (actual.compareTo(maximum) > 0) {
            throw new IllegalStateException("Live order exceeds atomic " + name + " risk limit");
        }
    }

    private String canonical(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace("/", "");
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }

    private boolean nonPositive(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
