package com.tj.crypto.admin.application;

import com.tj.crypto.admin.dto.ConnectorStatusDTO;
import com.tj.crypto.admin.dto.OverviewDTO;
import com.tj.crypto.admin.dto.RiskConfigDTO;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.StrategyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理后台总览服务。
 * 聚合系统状态：连接状态、策略数量、因子数量、信号数量、风控配置。
 * 只读，不改变任何交易行为。
 */
@Slf4j
@Service
public class AdminOverviewService {

    private final StrategyManager strategyManager;
    private final FactorRegistry factorRegistry;
    private final SignalCollector signalCollector;
    private final List<MarketDataConnector> connectors;
    private final RiskProperties riskProperties;

    private volatile long startupTimestamp = 0;

    public AdminOverviewService(StrategyManager strategyManager,
                                FactorRegistry factorRegistry,
                                SignalCollector signalCollector,
                                List<MarketDataConnector> connectors,
                                RiskProperties riskProperties) {
        this.strategyManager = strategyManager;
        this.factorRegistry = factorRegistry;
        this.signalCollector = signalCollector;
        this.connectors = connectors;
        this.riskProperties = riskProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        this.startupTimestamp = System.currentTimeMillis();
        log.info("AdminOverviewService recorded startup at {}", startupTimestamp);
    }

    /**
     * 获取系统总览。
     */
    public OverviewDTO getOverview() {
        long now = System.currentTimeMillis();
        long uptime = startupTimestamp > 0 ? now - startupTimestamp : 0;

        int enabledCount = (int) strategyManager.getActiveStrategies().size();
        List<ConnectorStatusDTO> connectorStatusList = getConnectorStatuses();
        long connectedCount = connectorStatusList.stream()
                .filter(ConnectorStatusDTO::isConnected)
                .count();

        return OverviewDTO.builder()
                .startupTimestamp(startupTimestamp)
                .uptimeMs(uptime)
                .strategyCount(strategyManager.getAllStrategies().size())
                .enabledStrategyCount(enabledCount)
                .factorCount(factorRegistry.getRegisteredFactors().size())
                .totalSignalCount(signalCollector.getAllSignals().size())
                .connectorCount(connectors.size())
                .connectedConnectorCount((int) connectedCount)
                .connectors(connectorStatusList)
                .riskConfig(getRiskConfig())
                .build();
    }

    /**
     * 获取所有连接器状态列表。
     */
    public List<ConnectorStatusDTO> getConnectorStatuses() {
        return connectors.stream()
                .map(this::toConnectorStatus)
                .toList();
    }

    /**
     * 获取当前风控配置。
     */
    public RiskConfigDTO getRiskConfig() {
        return RiskConfigDTO.builder()
                .maxLossPerTradePct(riskProperties.getMaxLossPerTradePct())
                .maxDailyLossPct(riskProperties.getMaxDailyLossPct())
                .maxSizePct(riskProperties.getMaxSizePct())
                .slippageBps(riskProperties.getSlippageBps())
                .build();
    }

    private ConnectorStatusDTO toConnectorStatus(MarketDataConnector connector) {
        ConnectorHealth health = connector.health();
        return ConnectorStatusDTO.builder()
                .name(connector.getClass().getSimpleName())
                .connected(health.connected())
                .messagesReceived(health.messagesReceived())
                .reconnectCount(health.reconnectCount())
                .lastMessageTimestamp(health.lastMessageTimestamp())
                .lastError(health.lastError() != null ? health.lastError() : "")
                .build();
    }
}
