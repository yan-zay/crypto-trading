package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AdminOverviewService;
import com.tj.crypto.admin.dto.ConnectorStatusDTO;
import com.tj.crypto.admin.dto.OverviewDTO;
import com.tj.crypto.admin.dto.RiskConfigDTO;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminOverviewServiceTest {

    private AdminOverviewService adminOverviewService;
    private StrategyManager strategyManager;
    private FactorRegistry factorRegistry;
    private SignalCollector signalCollector;
    private List<MarketDataConnector> connectors;
    private RiskProperties riskProperties;

    @BeforeEach
    void setUp() {
        strategyManager = mock(StrategyManager.class);
        factorRegistry = mock(FactorRegistry.class);
        signalCollector = mock(SignalCollector.class);
        riskProperties = new RiskProperties();

        MarketDataConnector connector1 = mock(MarketDataConnector.class);
        MarketDataConnector connector2 = mock(MarketDataConnector.class);
        connectors = List.of(connector1, connector2);

        when(connector1.health()).thenReturn(new ConnectorHealth(
                true, 1700000060000L, 1000L, 0L, null));
        when(connector2.health()).thenReturn(new ConnectorHealth(
                false, 0L, 0L, 3L, "Connection refused"));

        adminOverviewService = new AdminOverviewService(
                strategyManager, factorRegistry, signalCollector, connectors, riskProperties);
    }

    @Test
    @DisplayName("getOverview 返回完整的系统总览")
    void shouldReturnOverview() {
        Strategy strategy1 = mock(Strategy.class);
        Strategy strategy2 = mock(Strategy.class);
        when(strategyManager.getAllStrategies()).thenReturn(List.of(strategy1, strategy2));
        when(strategyManager.getActiveStrategies()).thenReturn(List.of(strategy1));
        when(factorRegistry.getRegisteredFactors()).thenReturn(List.of("SMA", "RSI", "MACD_HIST"));
        when(signalCollector.getAllSignals()).thenReturn(List.of());

        OverviewDTO overview = adminOverviewService.getOverview();

        assertEquals(2, overview.getStrategyCount());
        assertEquals(1, overview.getEnabledStrategyCount());
        assertEquals(3, overview.getFactorCount());
        assertEquals(0, overview.getTotalSignalCount());
        assertEquals(2, overview.getConnectorCount());
        assertEquals(1, overview.getConnectedConnectorCount());
        assertNotNull(overview.getConnectors());
        assertEquals(2, overview.getConnectors().size());
        assertNotNull(overview.getRiskConfig());
    }

    @Test
    @DisplayName("getConnectorStatuses 返回所有连接器状态")
    void shouldReturnConnectorStatuses() {
        List<ConnectorStatusDTO> statuses = adminOverviewService.getConnectorStatuses();

        assertEquals(2, statuses.size());

        ConnectorStatusDTO status1 = statuses.get(0);
        assertTrue(status1.isConnected());
        assertEquals(1000L, status1.getMessagesReceived());
        assertEquals(0L, status1.getReconnectCount());
        assertEquals(1700000060000L, status1.getLastMessageTimestamp());
        assertEquals("", status1.getLastError());

        ConnectorStatusDTO status2 = statuses.get(1);
        assertFalse(status2.isConnected());
        assertEquals(0L, status2.getMessagesReceived());
        assertEquals(3L, status2.getReconnectCount());
        assertEquals(0L, status2.getLastMessageTimestamp());
        assertEquals("Connection refused", status2.getLastError());
    }

    @Test
    @DisplayName("getConnectorStatuses 无连接器时返回空列表")
    void shouldReturnEmptyConnectorStatusesWhenNoConnectors() {
        AdminOverviewService service = new AdminOverviewService(
                strategyManager, factorRegistry, signalCollector, List.of(), riskProperties);

        List<ConnectorStatusDTO> statuses = service.getConnectorStatuses();

        assertNotNull(statuses);
        assertTrue(statuses.isEmpty());
    }

    @Test
    @DisplayName("getRiskConfig 返回默认风控配置")
    void shouldReturnDefaultRiskConfig() {
        RiskConfigDTO config = adminOverviewService.getRiskConfig();

        assertEquals(BigDecimal.valueOf(2.0), config.getMaxLossPerTradePct());
        assertEquals(BigDecimal.valueOf(5.0), config.getMaxDailyLossPct());
        assertEquals(BigDecimal.valueOf(30.0), config.getMaxSizePct());
        assertEquals(5, config.getSlippageBps());
    }

    @Test
    @DisplayName("getRiskConfig 使用自定义配置值")
    void shouldReturnCustomRiskConfig() {
        RiskProperties customProps = new RiskProperties();
        customProps.setMaxLossPerTradePct(BigDecimal.valueOf(1.5));
        customProps.setMaxDailyLossPct(BigDecimal.valueOf(3.0));
        customProps.setMaxSizePct(BigDecimal.valueOf(20.0));
        customProps.setSlippageBps(10);

        AdminOverviewService service = new AdminOverviewService(
                strategyManager, factorRegistry, signalCollector, connectors, customProps);

        RiskConfigDTO config = service.getRiskConfig();

        assertEquals(BigDecimal.valueOf(1.5), config.getMaxLossPerTradePct());
        assertEquals(BigDecimal.valueOf(3.0), config.getMaxDailyLossPct());
        assertEquals(BigDecimal.valueOf(20.0), config.getMaxSizePct());
        assertEquals(10, config.getSlippageBps());
    }

    @Test
    @DisplayName("getOverview 连接器 lastError 为 null 时返回空字符串")
    void shouldHandleNullLastError() {
        MarketDataConnector connector = mock(MarketDataConnector.class);
        when(connector.health()).thenReturn(new ConnectorHealth(
                true, 1700000060000L, 500L, 0L, null));

        AdminOverviewService service = new AdminOverviewService(
                strategyManager, factorRegistry, signalCollector, List.of(connector), riskProperties);

        List<ConnectorStatusDTO> statuses = service.getConnectorStatuses();

        assertEquals(1, statuses.size());
        assertEquals("", statuses.get(0).getLastError());
    }
}
