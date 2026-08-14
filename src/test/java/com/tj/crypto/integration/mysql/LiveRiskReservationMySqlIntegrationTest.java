package com.tj.crypto.integration.mysql;

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
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationBudget;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationDO;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationMapper;
import com.tj.crypto.trading.venue.riskreservation.LiveRiskReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** MySQL proof that two JVM-equivalent callers cannot both cross the gross-notional budget. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.maximum-pool-size=6",
        "crypto.outbox.poll-interval-ms=3600000",
        "crypto.reconciliation.interval-ms=3600000",
        "crypto.backtest-jobs.poll-interval-ms=3600000"
})
@ActiveProfiles("ci")
@Testcontainers(disabledWithoutDocker = true)
class LiveRiskReservationMySqlIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .withDatabaseName("crypto_risk_reservation")
            .withUsername("crypto_risk")
            .withPassword("crypto_risk");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired LiveRiskReservationService reservationService;
    @Autowired LiveRiskReservationMapper reservationMapper;
    @Autowired OmsPersistenceService persistenceService;

    @Test
    void duplicateClientOrderRollsBackTheSecondReservation() {
        Order first = order("duplicate-order-first", "duplicate-client",
                OrderStatus.CREATED, BigDecimal.ZERO);
        reservationService.reserveAndRecord(first, OrderEvent.created(first.orderId(), 100L),
                metadata("duplicate-account"), budget("duplicate-account"));

        Order duplicate = order("duplicate-order-second", "duplicate-client",
                OrderStatus.CREATED, BigDecimal.ZERO);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        reservationService.reserveAndRecord(duplicate,
                                OrderEvent.created(duplicate.orderId(), 101L),
                                metadata("duplicate-account"), budget("duplicate-account")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        assertThat(reservationMapper.selectByOrderId(first.orderId())).isNotNull();
        assertThat(reservationMapper.selectByOrderId(duplicate.orderId())).isNull();
    }

    @Test
    void concurrentInstancesSerializeAndUnknownRemainsReservedUntilTerminalTruth() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Attempt>> calls = List.of(
                    () -> attempt("atomic-order-a", "atomic-client-a", start),
                    () -> attempt("atomic-order-b", "atomic-client-b", start));
            List<Future<Attempt>> futures = new ArrayList<>();
            for (Callable<Attempt> call : calls) futures.add(pool.submit(call));
            start.countDown();

            List<Attempt> results = new ArrayList<>();
            for (Future<Attempt> future : futures) results.add(future.get());
            assertThat(results).filteredOn(Attempt::accepted).hasSize(1);
            assertThat(results).filteredOn(result -> !result.accepted()).singleElement()
                    .extracting(Attempt::error).asString().contains("atomic per-symbol");

            Attempt accepted = results.stream().filter(Attempt::accepted).findFirst().orElseThrow();
            LiveRiskReservationDO initial = reservationMapper.selectByOrderId(accepted.orderId());
            assertThat(initial.getRemainingNotional()).isEqualByComparingTo("300");
            assertThat(initial.getReservationStatus()).isEqualTo("ACTIVE");

            persistenceService.markUnknown(accepted.orderId(), "ACK_TIMEOUT", 200L);
            LiveRiskReservationDO unknown = reservationMapper.selectByOrderId(accepted.orderId());
            assertThat(unknown.getReservationStatus()).isEqualTo("UNKNOWN");
            assertThat(unknown.getRemainingNotional()).isEqualByComparingTo("300");

            Order partial = order(accepted.orderId(), accepted.clientOrderId(),
                    OrderStatus.PARTIALLY_FILLED, new BigDecimal("0.005"));
            persistenceService.recordVenue(partial,
                    OrderEvent.partiallyFilled(accepted.orderId(), 300L,
                            new BigDecimal("20000"), new BigDecimal("0.005")),
                    null, metadata(), "LIVE");
            LiveRiskReservationDO afterPartial = reservationMapper.selectByOrderId(accepted.orderId());
            assertThat(afterPartial.getRemainingNotional()).isEqualByComparingTo("200");
            assertThat(afterPartial.getReservationStatus()).isEqualTo("ACTIVE");

            Order cancelled = order(accepted.orderId(), accepted.clientOrderId(),
                    OrderStatus.CANCELLED, new BigDecimal("0.005"));
            persistenceService.recordVenue(cancelled,
                    OrderEvent.cancelled(accepted.orderId(), 400L),
                    null, metadata(), "LIVE");
            LiveRiskReservationDO released = reservationMapper.selectByOrderId(accepted.orderId());
            assertThat(released.getReservationStatus()).isEqualTo("RELEASED");
            assertThat(released.getRemainingNotional()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private Attempt attempt(String orderId, String clientOrderId, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            Order order = order(orderId, clientOrderId, OrderStatus.CREATED, BigDecimal.ZERO);
            reservationService.reserveAndRecord(order, OrderEvent.created(orderId, 100L),
                    metadata("atomic-account"), budget("atomic-account"));
            return new Attempt(true, orderId, clientOrderId, null);
        } catch (RuntimeException failure) {
            return new Attempt(false, orderId, clientOrderId, failure.getMessage());
        }
    }

    private LiveRiskReservationBudget budget(String accountId) {
        return new LiveRiskReservationBudget(accountId, Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", new BigDecimal("0.015"),
                new BigDecimal("20000"), new BigDecimal("300"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500"),
                new BigDecimal("500"), new BigDecimal("800"), true, 99L);
    }

    private Order order(String orderId, String clientOrderId, OrderStatus status,
                        BigDecimal filledQuantity) {
        return new Order(orderId, clientOrderId,
                Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
                OrderSide.LONG, OrderType.LIMIT, new BigDecimal("0.015"),
                new BigDecimal("20000"), filledQuantity,
                filledQuantity.signum() > 0 ? new BigDecimal("20000") : null,
                status, OrderRejectReason.NONE, 100L, 110L,
                status == OrderStatus.FILLED ? 400L : 0L,
                status == OrderStatus.CANCELLED ? 400L : 0L,
                "atomic-strategy", TradeSide.BUY, OrderSide.LONG, false);
    }

    private OmsFillMetadata metadata() {
        return metadata("atomic-account");
    }

    private OmsFillMetadata metadata(String accountId) {
        return new OmsFillMetadata(accountId, "atomic-correlation", null,
                BigDecimal.ZERO, null, null, new BigDecimal("20000"),
                new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 10, "CROSS");
    }

    private record Attempt(boolean accepted, String orderId, String clientOrderId, String error) {}
}
