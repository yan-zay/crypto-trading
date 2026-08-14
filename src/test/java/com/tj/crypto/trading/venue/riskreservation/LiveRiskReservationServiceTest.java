package com.tj.crypto.trading.venue.riskreservation;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.journal.OmsFillMetadata;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.storage.service.OmsPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LiveRiskReservationServiceTest {
    private static final String ACCOUNT = "account-1";
    private static final BigDecimal PRICE = new BigDecimal("20000");

    @Mock LiveRiskReservationMapper mapper;
    @Mock OmsPersistenceService persistenceService;

    private LiveRiskReservationService service;

    @BeforeEach
    void setUp() {
        service = new LiveRiskReservationService(mapper, persistenceService);
        lenient().when(mapper.lockScope(ACCOUNT, "BINANCE")).thenReturn(0L);
        lenient().when(mapper.selectUnreservedActiveOrderIds(ACCOUNT, "BINANCE", "order-new"))
                .thenReturn(List.of());
        lenient().when(mapper.insert(any())).thenReturn(1);
        lenient().when(mapper.touchScope(ACCOUNT, "BINANCE", 100L)).thenReturn(1);
    }

    @Test
    void countsUnknownRemainingNotionalAtInclusiveSymbolAndPortfolioBoundaries() {
        LiveRiskReservationDO unknown = blocking("order-unknown", "PERPETUAL", "BTCUSDT",
                "200", "UNKNOWN");
        LiveRiskReservationDO other = blocking("order-other", "PERPETUAL", "ETHUSDT",
                "300", "ACTIVE");
        when(mapper.selectBlockingForUpdate(ACCOUNT, "BINANCE"))
                .thenReturn(List.of(unknown, other));

        LiveRiskReservationBudget budget = budget("100", "100", "200",
                "500", "400", "800");

        assertThatCode(() -> service.reserveAndRecord(order(false), created(), metadata(), budget))
                .doesNotThrowAnyException();

        ArgumentCaptor<LiveRiskReservationDO> inserted =
                ArgumentCaptor.forClass(LiveRiskReservationDO.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getRemainingNotional()).isEqualByComparingTo("100");
        assertThat(inserted.getValue().getReservationStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsAtomicallyWhenAnotherInstanceReservationConsumesTheSymbolBudget() {
        when(mapper.selectBlockingForUpdate(ACCOUNT, "BINANCE"))
                .thenReturn(List.of(blocking("order-racing", "PERPETUAL", "BTCUSDT",
                        "201", "ACTIVE")));

        assertThatThrownBy(() -> service.reserveAndRecord(order(false), created(), metadata(),
                budget("100", "100", "200", "500", "400", "800")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("atomic per-symbol");

        // The OMS INSERT deliberately occurs inside the same transaction before the check;
        // the runtime exception makes Spring roll both facts back together.
        verify(persistenceService).recordInitialLiveCommand(order(false), created(), metadata());
    }

    @Test
    void anyUnvaluedOrMissingActiveOrderFailsClosed() {
        when(mapper.selectUnreservedActiveOrderIds(ACCOUNT, "BINANCE", "order-new"))
                .thenReturn(List.of("legacy-active"));

        assertThatThrownBy(() -> service.reserveAndRecord(order(false), created(), metadata(),
                budget("100", "0", "0", "500", "500", "800")))
                .hasMessageContaining("no durable risk reservation");

        when(mapper.selectUnreservedActiveOrderIds(ACCOUNT, "BINANCE", "order-new"))
                .thenReturn(List.of());
        when(mapper.selectBlockingForUpdate(ACCOUNT, "BINANCE"))
                .thenReturn(List.of(blocking("unvalued", "PERPETUAL", "ETHUSDT",
                        "0", "UNVALUED")));

        assertThatThrownBy(() -> service.reserveAndRecord(order(false), created(), metadata(),
                budget("100", "0", "0", "500", "500", "800")))
                .hasMessageContaining("cannot be valued");
    }

    @Test
    void validatedReduceOnlyOrderCreatesAuditRowWithoutOpeningRisk() {
        when(mapper.selectBlockingForUpdate(ACCOUNT, "BINANCE")).thenReturn(List.of());
        Order reduction = order(true);
        LiveRiskReservationBudget budget = LiveRiskReservationBudget.reduction(
                ACCOUNT, Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT",
                reduction.quantity(), PRICE, 99L);

        service.reserveAndRecord(reduction, created(), metadata(), budget);

        ArgumentCaptor<LiveRiskReservationDO> inserted =
                ArgumentCaptor.forClass(LiveRiskReservationDO.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getRiskIncreasing()).isFalse();
        assertThat(inserted.getValue().getRemainingNotional()).isZero();
    }

    private LiveRiskReservationBudget budget(
            String orderNotional, String currentSymbol, String currentPortfolio,
            String singleLimit, String symbolLimit, String portfolioLimit) {
        return new LiveRiskReservationBudget(ACCOUNT, Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", new BigDecimal("0.005"), PRICE,
                new BigDecimal(orderNotional), new BigDecimal(currentSymbol),
                new BigDecimal(currentPortfolio), new BigDecimal(singleLimit),
                new BigDecimal(symbolLimit), new BigDecimal(portfolioLimit), true, 99L);
    }

    private LiveRiskReservationDO blocking(String orderId, String marketType, String symbol,
                                           String remainingNotional, String status) {
        LiveRiskReservationDO row = new LiveRiskReservationDO();
        row.setOrderId(orderId);
        row.setAccountId(ACCOUNT);
        row.setExchange("BINANCE");
        row.setMarketType(marketType);
        row.setSymbol(symbol);
        row.setRiskIncreasing(true);
        row.setRemainingNotional(new BigDecimal(remainingNotional));
        row.setReservationStatus(status);
        return row;
    }

    private Order order(boolean reduceOnly) {
        return new Order("order-new", "client-new",
                Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
                OrderSide.LONG, OrderType.MARKET, new BigDecimal("0.005"), PRICE,
                BigDecimal.ZERO, null, OrderStatus.CREATED, OrderRejectReason.NONE,
                100L, 0, 0, 0, "strategy", reduceOnly ? TradeSide.SELL : TradeSide.BUY,
                OrderSide.LONG, reduceOnly);
    }

    private OrderEvent created() {
        return OrderEvent.created("order-new", 100L);
    }

    private OmsFillMetadata metadata() {
        return new OmsFillMetadata(ACCOUNT, "correlation", null, BigDecimal.ZERO,
                null, null, PRICE, PRICE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 10, "CROSS");
    }
}
