package com.tj.crypto.integration.mysql;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderStatus;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.trading.paper.PaperAccountLifecycleService;
import com.tj.crypto.trading.paper.PaperAccountSnapshot;
import com.tj.crypto.trading.paper.PaperMarkRequest;
import com.tj.crypto.trading.paper.PaperMarketDataService;
import com.tj.crypto.trading.paper.PaperOrderRequest;
import com.tj.crypto.trading.paper.PaperOrderService;
import com.tj.crypto.trading.paper.PaperTradingQueryService;
import com.tj.crypto.trading.paper.ledger.DoubleEntryLedgerService;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.reconciliation.ReconciliationReport;
import com.tj.crypto.trading.reconciliation.ReconciliationService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-database acceptance path for the durable paper trading core.
 *
 * <p>The test is part of the default Maven suite. It runs against a fresh MySQL 8
 * container whenever Docker is available and is skipped on ordinary developer
 * machines without Docker. {@link PaperTradingMySqlIntegrationCiGuardTest}
 * prevents that local convenience from turning into a skipped/green CI build.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.baseline-on-migrate=false",
                "spring.datasource.hikari.maximum-pool-size=4",
                "crypto.outbox.poll-interval-ms=3600000",
                "crypto.reconciliation.interval-ms=3600000",
                "crypto.backtest-jobs.poll-interval-ms=3600000"
        })
@ActiveProfiles("ci")
@Testcontainers(disabledWithoutDocker = true)
class PaperTradingMySqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .withDatabaseName("crypto_trading")
            .withUsername("crypto_test")
            .withPassword("crypto_test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PaperAccountLifecycleService accountLifecycleService;
    @Autowired
    private PaperMarketDataService marketDataService;
    @Autowired
    private PaperOrderService orderService;
    @Autowired
    private PaperTradingQueryService queryService;
    @Autowired
    private DoubleEntryLedgerService ledgerService;
    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private KillSwitch killSwitch;

    @Test
    void freshMigrationAndPaperOrderFactsReconcileOnMySql8() {
        assertFreshSchemaIsAtLatestMigration();

        PaperAccountDO account = accountLifecycleService.start(
                new BigDecimal("10000.00"), "mysql-golden-path", "corr-account-start");
        long markTime = System.currentTimeMillis();
        marketDataService.update(new PaperMarkRequest(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTCUSDT",
                new BigDecimal("10000.00"),
                new BigDecimal("10100.00"),
                new BigDecimal("9900.00"),
                new BigDecimal("100.00"),
                markTime), "MYSQL_GOLDEN_PATH");

        // A freshly migrated production-like schema is deliberately HALT. The test
        // performs an explicit, durable operator transition before opening risk.
        killSwitch.deactivate("MYSQL_GOLDEN_PATH_TEST", "INTEGRATION_TEST");

        Order order = orderService.place(new PaperOrderRequest(
                account.getAccountId(),
                "mysql-golden-buy-1",
                "MYSQL_GOLDEN_STRATEGY",
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTCUSDT",
                TradeSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.01000"),
                null,
                1,
                false,
                "corr-order-buy"));

        assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.filledQuantity()).isEqualByComparingTo("0.01000");
        assertThat(order.avgFillPrice()).isPositive();

        PaperAccountSnapshot snapshot = queryService.snapshot(account.getAccountId());
        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.activeOrderCount()).isZero();
        assertThat(snapshot.positions()).singleElement().satisfies(position -> {
            assertThat(position.getExchange()).isEqualTo("BINANCE");
            assertThat(position.getMarketType()).isEqualTo("SPOT");
            assertThat(position.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(position.getSide()).isEqualTo("LONG");
            assertThat(position.getQuantity()).isEqualByComparingTo("0.01000");
            assertThat(position.getEntryPrice()).isEqualByComparingTo(order.avgFillPrice());
        });
        assertThat(queryService.fills(account.getAccountId(), 10)).singleElement()
                .satisfies(fill -> {
                    assertThat(fill.getOrderId()).isEqualTo(order.orderId());
                    assertThat(fill.getFillQuantity()).isEqualByComparingTo("0.01000");
                    assertThat(fill.getFee()).isPositive();
                });
        assertThat(queryService.ledger(account.getAccountId(), 100)).hasSizeGreaterThanOrEqualTo(9);
        assertThat(ledgerService.countImbalances(account.getAccountId())).isZero();

        ReconciliationReport report = reconciliationService.run(account.getAccountId());
        assertThat(report.ordersChecked()).isEqualTo(1);
        assertThat(report.balancesChecked()).isEqualTo(2);
        assertThat(report.positionsChecked()).isEqualTo(1);
        assertThat(report.newOrUpdatedIncidents()).isZero();
        assertThat(report.openIncidents()).isZero();
    }

    private void assertFreshSchemaIsAtLatestMigration() {
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();

        MigrationInfo current = flyway.info().current();
        MigrationVersion latestResolved = Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        assertThat(current).isNotNull();
        assertThat(current.getVersion()).isEqualTo(latestResolved);

        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class);
        assertThat(successfulMigrations).isNotNull().isGreaterThanOrEqualTo(14);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name IN "
                        + "('paper_account','paper_mark_price','oms_order','oms_fill',"
                        + "'paper_position','account_ledger_transaction',"
                        + "'account_ledger_entry','reconciliation_incident',"
                        + "'kill_switch_state','execution_writer_lease',"
                        + "'live_risk_budget_scope','live_risk_reservation')",
                Integer.class)).isEqualTo(12);
    }
}

/** Proves that an already-running V11 database upgrades fail-closed through V12-V14. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlFlywayUpgradeIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .withDatabaseName("crypto_upgrade")
            .withUsername("crypto_upgrade")
            .withPassword("crypto_upgrade");

    @Test
    void upgradesV11SchemaToLatestWithTradingHaltedAndWriterLeasePresent() throws Exception {
        Flyway throughV11 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("11"))
                .load();
        throughV11.migrate();
        assertThat(throughV11.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("11"));

        Flyway latest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        latest.migrate();
        latest.validate();
        assertThat(latest.info().pending()).isEmpty();
        assertThat(latest.info().current().getVersion())
                .isEqualTo(Arrays.stream(latest.info().all())
                        .map(MigrationInfo::getVersion)
                        .filter(version -> version != null)
                        .max(Comparator.naturalOrder())
                        .orElseThrow());

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            try (ResultSet state = statement.executeQuery(
                    "SELECT mode FROM kill_switch_state WHERE state_key='GLOBAL'")) {
                assertThat(state.next()).isTrue();
                assertThat(state.getString(1)).isEqualTo("HALT");
            }
            try (ResultSet leaseTable = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=DATABASE() AND table_name='execution_writer_lease'")) {
                assertThat(leaseTable.next()).isTrue();
                assertThat(leaseTable.getInt(1)).isEqualTo(1);
            }
            try (ResultSet reservationTables = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=DATABASE() AND table_name IN "
                            + "('live_risk_budget_scope','live_risk_reservation')")) {
                assertThat(reservationTables.next()).isTrue();
                assertThat(reservationTables.getInt(1)).isEqualTo(2);
            }
        }
    }
}

/** Ensures disabledWithoutDocker can never make a CI run silently green. */
class PaperTradingMySqlIntegrationCiGuardTest {

    @Test
    void ciMustProvideDockerForTheMySqlGoldenPath() {
        if (!isCi()) return;
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("CI must provide Docker; the required MySQL golden-path test may not be skipped")
                .isTrue();
    }

    private boolean isCi() {
        String value = System.getenv("CI");
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
