package com.tj.crypto.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.application.AdminOverviewService;
import com.tj.crypto.admin.application.ConfigVersionService;
import com.tj.crypto.admin.dto.ConnectorStatusDTO;
import com.tj.crypto.admin.dto.FactorInfoDTO;
import com.tj.crypto.admin.dto.OverviewDTO;
import com.tj.crypto.admin.dto.RiskConfigDTO;
import com.tj.crypto.admin.dto.StrategyInfoDTO;
import com.tj.crypto.admin.dto.SystemStatusDTO;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.storage.service.AutoBackfillService;
import com.tj.crypto.storage.service.DataCoverageService;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminControllerTest {

    private MockMvc mockMvc;
    private AdminService adminService;
    private AdminOverviewService adminOverviewService;
    private ConfigVersionService configVersionService;
    private StrategyManager strategyManager;
    private DataCoverageService dataCoverageService;
    private AutoBackfillService autoBackfillService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        adminOverviewService = mock(AdminOverviewService.class);
        configVersionService = mock(ConfigVersionService.class);
        strategyManager = mock(StrategyManager.class);
        dataCoverageService = mock(DataCoverageService.class);
        autoBackfillService = mock(AutoBackfillService.class);
        AdminController controller = new AdminController(adminService, adminOverviewService,
                configVersionService, strategyManager, dataCoverageService, autoBackfillService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/admin/status 返回系统状态")
    void shouldReturnSystemStatus() throws Exception {
        SystemStatusDTO status = SystemStatusDTO.builder()
                .startupTimestamp(1700000000000L)
                .uptimeMs(60000L)
                .strategyCount(2)
                .factorCount(5)
                .totalSignalCount(10)
                .connectorCount(2)
                .connectedConnectorCount(1)
                .build();
        when(adminService.getSystemStatus()).thenReturn(status);

        mockMvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.startupTimestamp").value(1700000000000L))
                .andExpect(jsonPath("$.uptimeMs").value(60000))
                .andExpect(jsonPath("$.strategyCount").value(2))
                .andExpect(jsonPath("$.factorCount").value(5))
                .andExpect(jsonPath("$.totalSignalCount").value(10))
                .andExpect(jsonPath("$.connectorCount").value(2))
                .andExpect(jsonPath("$.connectedConnectorCount").value(1));
    }

    @Test
    @DisplayName("GET /api/admin/signals 返回最近信号列表")
    void shouldReturnRecentSignals() throws Exception {
        Instrument btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        SignalEvent signal = new SignalEvent(
                "MacdCross", btcUsdt, SignalType.BUY,
                BigDecimal.valueOf(0.7), "MACD golden cross",
                Map.of("MACD_HIST", BigDecimal.valueOf(0.5)),
                1700000000000L
        );
        when(adminService.getRecentSignals(50)).thenReturn(List.of(signal));

        mockMvc.perform(get("/api/admin/signals"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].strategyName").value("MacdCross"))
                .andExpect(jsonPath("$[0].type").value("BUY"))
                .andExpect(jsonPath("$[0].reason").value("MACD golden cross"))
                .andExpect(jsonPath("$[0].confidence").value(0.7));
    }

    @Test
    @DisplayName("GET /api/admin/signals?limit=5 支持 limit 参数")
    void shouldSupportLimitParam() throws Exception {
        when(adminService.getRecentSignals(5)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/signals").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /api/admin/factors 返回因子列表")
    void shouldReturnFactorList() throws Exception {
        when(adminService.getAllFactors()).thenReturn(List.of(
                FactorInfoDTO.builder().name("SMA_20").build(),
                FactorInfoDTO.builder().name("RSI_14").build(),
                FactorInfoDTO.builder().name("MACD_HIST").build()
        ));

        mockMvc.perform(get("/api/admin/factors"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("SMA_20"))
                .andExpect(jsonPath("$[1].name").value("RSI_14"))
                .andExpect(jsonPath("$[2].name").value("MACD_HIST"));
    }

    @Test
    @DisplayName("GET /api/admin/strategies 返回策略列表")
    void shouldReturnStrategyList() throws Exception {
        when(adminService.getAllStrategies()).thenReturn(List.of(
                StrategyInfoDTO.builder()
                        .name("MacdCross")
                        .listenedEvents(Set.of("BarEvent"))
                        .build(),
                StrategyInfoDTO.builder()
                        .name("LiquidationSpike")
                        .listenedEvents(Set.of("LiquidationEvent"))
                        .build()
        ));

        mockMvc.perform(get("/api/admin/strategies"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("MacdCross"))
                .andExpect(jsonPath("$[0].listenedEvents[0]").value("BarEvent"))
                .andExpect(jsonPath("$[1].name").value("LiquidationSpike"));
    }

    @Test
    @DisplayName("GET /api/admin/health 返回健康状态")
    void shouldReturnHealthStatus() throws Exception {
        SystemStatusDTO status = SystemStatusDTO.builder()
                .startupTimestamp(1700000000000L)
                .uptimeMs(120000L)
                .strategyCount(2)
                .factorCount(5)
                .totalSignalCount(10)
                .connectorCount(2)
                .connectedConnectorCount(1)
                .build();
        when(adminService.getSystemStatus()).thenReturn(status);
        when(adminService.getConnectorHealth()).thenReturn(List.of(
                Map.of(
                        "name", "BinanceWebSocketClient",
                        "connected", true,
                        "messagesReceived", 1000L,
                        "reconnectCount", 0L,
                        "lastMessageTimestamp", 1700000060000L,
                        "lastError", ""
                )
        ));

        mockMvc.perform(get("/api/admin/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.uptimeMs").value(120000))
                .andExpect(jsonPath("$.connectors.length()").value(1))
                .andExpect(jsonPath("$.connectors[0].name").value("BinanceWebSocketClient"))
                .andExpect(jsonPath("$.connectors[0].connected").value(true))
                .andExpect(jsonPath("$.strategyCount").value(2))
                .andExpect(jsonPath("$.factorCount").value(5));
    }

    @Test
    @DisplayName("GET /api/admin/health 无连接器时返回 DOWN")
    void shouldReturnDownWhenNoConnectors() throws Exception {
        SystemStatusDTO status = SystemStatusDTO.builder()
                .startupTimestamp(1700000000000L)
                .uptimeMs(120000L)
                .strategyCount(2)
                .factorCount(5)
                .totalSignalCount(10)
                .connectorCount(0)
                .connectedConnectorCount(0)
                .build();
        when(adminService.getSystemStatus()).thenReturn(status);
        when(adminService.getConnectorHealth()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.connectors").isEmpty());
    }

    @Test
    @DisplayName("POST /api/admin/strategies/{name}/enable 启用已存在的策略")
    void shouldEnableStrategy() throws Exception {
        when(strategyManager.enableStrategy("MacdCross")).thenReturn(true);

        mockMvc.perform(post("/api/admin/strategies/MacdCross/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.strategy").value("MacdCross"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("POST /api/admin/strategies/{name}/enable 不存在的策略返回 400")
    void shouldReturnBadRequestForUnknownStrategyEnable() throws Exception {
        when(strategyManager.enableStrategy("Unknown")).thenReturn(false);

        mockMvc.perform(post("/api/admin/strategies/Unknown/enable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Unknown strategy: Unknown"));
    }

    @Test
    @DisplayName("POST /api/admin/strategies/{name}/disable 禁用已存在的策略")
    void shouldDisableStrategy() throws Exception {
        when(strategyManager.disableStrategy("MacdCross")).thenReturn(true);

        mockMvc.perform(post("/api/admin/strategies/MacdCross/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.strategy").value("MacdCross"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("POST /api/admin/strategies/{name}/disable 不存在的策略返回 400")
    void shouldReturnBadRequestForUnknownStrategyDisable() throws Exception {
        when(strategyManager.disableStrategy("Unknown")).thenReturn(false);

        mockMvc.perform(post("/api/admin/strategies/Unknown/disable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Unknown strategy: Unknown"));
    }

    @Test
    @DisplayName("GET /api/admin/strategies/{name}/status 返回策略状态")
    void shouldReturnStrategyStatus() throws Exception {
        Strategy strategy = mock(Strategy.class);
        when(strategy.name()).thenReturn("MacdCross");
        when(strategy.listenedEvents()).thenReturn(Set.of(BarEvent.class));
        when(strategyManager.getStrategy("MacdCross")).thenReturn(Optional.of(strategy));
        when(strategyManager.isStrategyEnabled("MacdCross")).thenReturn(true);

        mockMvc.perform(get("/api/admin/strategies/MacdCross/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MacdCross"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.listenedEvents[0]").value("BarEvent"));
    }

    @Test
    @DisplayName("GET /api/admin/strategies/{name}/status 不存在的策略返回 400")
    void shouldReturnBadRequestForUnknownStrategyStatus() throws Exception {
        when(strategyManager.getStrategy("Unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/strategies/Unknown/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Unknown strategy: Unknown"));
    }

    @Test
    @DisplayName("GET /api/admin/overview 返回系统总览")
    void shouldReturnOverview() throws Exception {
        OverviewDTO overview = OverviewDTO.builder()
                .startupTimestamp(1700000000000L)
                .uptimeMs(120000L)
                .strategyCount(3)
                .enabledStrategyCount(2)
                .factorCount(5)
                .totalSignalCount(10)
                .connectorCount(2)
                .connectedConnectorCount(1)
                .connectors(List.of(
                        ConnectorStatusDTO.builder()
                                .name("BinanceConnector")
                                .connected(true)
                                .messagesReceived(1000L)
                                .reconnectCount(0L)
                                .lastMessageTimestamp(1700000060000L)
                                .lastError("")
                                .build(),
                        ConnectorStatusDTO.builder()
                                .name("CoinglassConnector")
                                .connected(false)
                                .messagesReceived(0L)
                                .reconnectCount(3L)
                                .lastMessageTimestamp(0L)
                                .lastError("Connection refused")
                                .build()
                ))
                .riskConfig(RiskConfigDTO.builder()
                        .maxLossPerTradePct(BigDecimal.valueOf(2.0))
                        .maxDailyLossPct(BigDecimal.valueOf(5.0))
                        .maxSizePct(BigDecimal.valueOf(30.0))
                        .slippageBps(5)
                        .build())
                .build();
        when(adminOverviewService.getOverview()).thenReturn(overview);

        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.startupTimestamp").value(1700000000000L))
                .andExpect(jsonPath("$.uptimeMs").value(120000))
                .andExpect(jsonPath("$.strategyCount").value(3))
                .andExpect(jsonPath("$.enabledStrategyCount").value(2))
                .andExpect(jsonPath("$.factorCount").value(5))
                .andExpect(jsonPath("$.totalSignalCount").value(10))
                .andExpect(jsonPath("$.connectorCount").value(2))
                .andExpect(jsonPath("$.connectedConnectorCount").value(1))
                .andExpect(jsonPath("$.connectors.length()").value(2))
                .andExpect(jsonPath("$.connectors[0].name").value("BinanceConnector"))
                .andExpect(jsonPath("$.connectors[0].connected").value(true))
                .andExpect(jsonPath("$.connectors[1].name").value("CoinglassConnector"))
                .andExpect(jsonPath("$.connectors[1].connected").value(false))
                .andExpect(jsonPath("$.riskConfig.maxLossPerTradePct").value(2.0))
                .andExpect(jsonPath("$.riskConfig.maxDailyLossPct").value(5.0))
                .andExpect(jsonPath("$.riskConfig.maxSizePct").value(30.0))
                .andExpect(jsonPath("$.riskConfig.slippageBps").value(5));
    }

    @Test
    @DisplayName("GET /api/admin/connectors 返回连接器状态列表")
    void shouldReturnConnectorList() throws Exception {
        when(adminOverviewService.getConnectorStatuses()).thenReturn(List.of(
                ConnectorStatusDTO.builder()
                        .name("BinanceConnector")
                        .connected(true)
                        .messagesReceived(500L)
                        .reconnectCount(0L)
                        .lastMessageTimestamp(1700000060000L)
                        .lastError("")
                        .build()
        ));

        mockMvc.perform(get("/api/admin/connectors"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("BinanceConnector"))
                .andExpect(jsonPath("$[0].connected").value(true))
                .andExpect(jsonPath("$[0].messagesReceived").value(500))
                .andExpect(jsonPath("$[0].reconnectCount").value(0))
                .andExpect(jsonPath("$[0].lastError").value(""));
    }

    @Test
    @DisplayName("GET /api/admin/risk/configs 返回风控配置")
    void shouldReturnRiskConfigs() throws Exception {
        when(adminOverviewService.getRiskConfig()).thenReturn(RiskConfigDTO.builder()
                .maxLossPerTradePct(BigDecimal.valueOf(2.0))
                .maxDailyLossPct(BigDecimal.valueOf(5.0))
                .maxSizePct(BigDecimal.valueOf(30.0))
                .slippageBps(5)
                .build());

        mockMvc.perform(get("/api/admin/risk/configs"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.maxLossPerTradePct").value(2.0))
                .andExpect(jsonPath("$.maxDailyLossPct").value(5.0))
                .andExpect(jsonPath("$.maxSizePct").value(30.0))
                .andExpect(jsonPath("$.slippageBps").value(5));
    }
}
