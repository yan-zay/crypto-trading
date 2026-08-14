package com.tj.crypto.admin;

import com.tj.crypto.admin.dto.FactorInfoDTO;
import com.tj.crypto.admin.dto.StrategyInfoDTO;
import com.tj.crypto.admin.dto.SystemStatusDTO;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.Strategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Admin 服务。
 * 聚合 FactorRegistry、SignalCollector、Strategy、MarketDataConnector 的数据，
 * 为 AdminController 提供统一查询接口。
 */
@Slf4j
@Service
public class AdminService {

    private final FactorRegistry factorRegistry;
    private final SignalCollector signalCollector;
    private final List<Strategy> strategies;
    private final List<MarketDataConnector> connectors;

    private volatile long startupTimestamp = 0;

    public AdminService(FactorRegistry factorRegistry,
                        SignalCollector signalCollector,
                        List<Strategy> strategies,
                        List<MarketDataConnector> connectors) {
        this.factorRegistry = factorRegistry;
        this.signalCollector = signalCollector;
        this.strategies = strategies;
        this.connectors = connectors;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        this.startupTimestamp = System.currentTimeMillis();
        log.info("AdminService recorded startup at {}", startupTimestamp);
    }

    /**
     * 获取系统状态。
     */
    public SystemStatusDTO getSystemStatus() {
        long now = System.currentTimeMillis();
        long uptime = startupTimestamp > 0 ? now - startupTimestamp : 0;

        List<ConnectorHealth> healthList = connectors.stream()
                .map(MarketDataConnector::health)
                .toList();

        long connectedCount = healthList.stream()
                .filter(ConnectorHealth::connected)
                .count();

        return SystemStatusDTO.builder()
                .startupTimestamp(startupTimestamp)
                .uptimeMs(uptime)
                .strategyCount(strategies.size())
                .factorCount(factorRegistry.getRegisteredFactors().size())
                .totalSignalCount(signalCollector.getAllSignals().size())
                .connectorCount(connectors.size())
                .connectedConnectorCount((int) connectedCount)
                .build();
    }

    /**
     * 获取最近的信号列表。
     *
     * @param limit 最大返回数量
     */
    public List<SignalEvent> getRecentSignals(int limit) {
        List<SignalEvent> all = signalCollector.getAllSignals();
        return all.stream()
                .sorted(Comparator.comparingLong(SignalEvent::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * 获取所有已注册因子。
     */
    public List<FactorInfoDTO> getAllFactors() {
        return factorRegistry.getRegisteredFactors().stream()
                .map(name -> FactorInfoDTO.builder()
                        .name(name)
                        .historicalBacktestSupported(
                                factorRegistry.supportsBarHistory(name))
                        .build())
                .toList();
    }

    /**
     * 获取所有已注册策略。
     */
    public List<StrategyInfoDTO> getAllStrategies() {
        return strategies.stream()
                .map(s -> StrategyInfoDTO.builder()
                        .name(s.name())
                        .listenedEvents(s.listenedEvents().stream()
                                .map(Class::getSimpleName)
                                .collect(java.util.stream.Collectors.toSet()))
                        .build())
                .toList();
    }

    /**
     * 获取所有连接器健康状态。
     */
    public List<Map<String, Object>> getConnectorHealth() {
        return connectors.stream()
                .map(c -> {
                    ConnectorHealth h = c.health();
                    return Map.<String, Object>of(
                            "name", c.getClass().getSimpleName(),
                            "connected", h.connected(),
                            "messagesReceived", h.messagesReceived(),
                            "reconnectCount", h.reconnectCount(),
                            "lastMessageTimestamp", h.lastMessageTimestamp(),
                            "lastError", h.lastError() != null ? h.lastError() : ""
                    );
                })
                .toList();
    }
}
