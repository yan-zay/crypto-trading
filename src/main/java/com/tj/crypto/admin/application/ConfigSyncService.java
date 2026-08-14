package com.tj.crypto.admin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersionDO;
import com.tj.crypto.admin.mapper.ConfigVersionMapper;
import com.tj.crypto.config.properties.ConnectorProperties;
import com.tj.crypto.config.properties.OkxProperties;
import com.tj.crypto.factor.FactorProperties;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.StrategyManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 配置同步服务。
 * 从数据库加载 Active 配置，更新策略/风控/因子运行态。
 *
 * 触发时机：
 * 1. 应用启动时（@PostConstruct）
 * 2. 配置发布或回滚后（@EventListener ConfigPublishedEvent）
 */
@Slf4j
@Service
public class ConfigSyncService {

    private final ConfigVersionMapper configVersionMapper;
    private final StrategyManager strategyManager;
    private final RiskProperties riskProperties;
    private final FactorProperties factorProperties;
    private final ConnectorProperties connectorProperties;
    private final OkxProperties okxProperties;
    private final ObjectMapper objectMapper;

    public ConfigSyncService(ConfigVersionMapper configVersionMapper,
                             StrategyManager strategyManager,
                             RiskProperties riskProperties,
                             FactorProperties factorProperties,
                             ConnectorProperties connectorProperties,
                             OkxProperties okxProperties,
                             ObjectMapper objectMapper) {
        this.configVersionMapper = configVersionMapper;
        this.strategyManager = strategyManager;
        this.riskProperties = riskProperties;
        this.factorProperties = factorProperties;
        this.connectorProperties = connectorProperties;
        this.okxProperties = okxProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        syncActiveConfigs();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConfigPublished(ConfigPublishedEvent event) {
        log.info("Config published event received: type={}, key={}", event.getConfigType(), event.getConfigKey());
        syncActiveConfigs();
    }

    /**
     * 从数据库加载所有 Active 配置，更新运行态。
     */
    public void syncActiveConfigs() {
        log.info("Syncing active configs from database...");
        List<ConfigVersionDO> allActive = configVersionMapper.selectAllActive();
        log.info("Found {} active config versions", allActive.size());

        for (ConfigVersionDO config : allActive) {
            try {
                applyConfig(config);
            } catch (Exception e) {
                log.error("Failed to apply config: type={}, key={}, versionId={}",
                        config.getConfigType(), config.getConfigKey(), config.getVersionId(), e);
            }
        }
        log.info("Active config sync completed");
    }

    private void applyConfig(ConfigVersionDO config) {
        ConfigType type;
        try {
            type = ConfigType.fromCode(config.getConfigType());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown config type: {}", config.getConfigType());
            return;
        }

        switch (type) {
            case STRATEGY -> applyStrategyConfig(config);
            case RISK -> applyRiskConfig(config);
            case FACTOR -> applyFactorConfig(config);
            case CONNECTOR -> applyConnectorConfig(config);
            case EXECUTION -> applyExecutionConfig(config);
        }
    }

    private void applyStrategyConfig(ConfigVersionDO config) {
        String strategyName = config.getConfigKey();
        JsonNode root;
        try {
            root = objectMapper.readTree(config.getContentJson());
        } catch (Exception e) {
            log.error("Failed to parse strategy config JSON: {}", config.getContentJson(), e);
            return;
        }

        boolean enabled = root.path("enabled").asBoolean(true);
        if (enabled) {
            if (!strategyManager.enableStrategy(strategyName)) {
                throw new IllegalArgumentException("Unknown strategy: " + strategyName);
            }
        } else {
            if (!strategyManager.disableStrategy(strategyName)) {
                throw new IllegalArgumentException("Unknown strategy: " + strategyName);
            }
        }
        log.info("Applied strategy config: {} enabled={}", strategyName, enabled);
    }

    private void applyRiskConfig(ConfigVersionDO config) {
        JsonNode root;
        try {
            root = objectMapper.readTree(config.getContentJson());
        } catch (Exception e) {
            log.error("Failed to parse risk config JSON: {}", config.getContentJson(), e);
            return;
        }

        if (root.has("maxLossPerTradePct")) {
            riskProperties.setMaxLossPerTradePct(new BigDecimal(root.get("maxLossPerTradePct").asText()));
        }
        if (root.has("maxDailyLossPct")) {
            riskProperties.setMaxDailyLossPct(new BigDecimal(root.get("maxDailyLossPct").asText()));
        }
        if (root.has("maxSizePct")) {
            riskProperties.setMaxSizePct(new BigDecimal(root.get("maxSizePct").asText()));
        }
        if (root.has("slippageBps")) {
            riskProperties.setSlippageBps(root.get("slippageBps").asInt());
        }
        log.info("Applied risk config: key={}", config.getConfigKey());
    }

    private void applyFactorConfig(ConfigVersionDO config) {
        JsonNode root;
        try {
            root = objectMapper.readTree(config.getContentJson());
        } catch (Exception e) {
            log.error("Failed to parse factor config JSON: {}", config.getContentJson(), e);
            return;
        }

        if (root.has("smaPeriod")) factorProperties.setSmaPeriod(root.get("smaPeriod").asInt());
        if (root.has("emaPeriod")) factorProperties.setEmaPeriod(root.get("emaPeriod").asInt());
        if (root.has("macdFast")) factorProperties.setMacdFast(root.get("macdFast").asInt());
        if (root.has("macdSlow")) factorProperties.setMacdSlow(root.get("macdSlow").asInt());
        if (root.has("macdSignal")) factorProperties.setMacdSignal(root.get("macdSignal").asInt());
        if (root.has("rsiPeriod")) factorProperties.setRsiPeriod(root.get("rsiPeriod").asInt());
        if (root.has("bbPeriod")) factorProperties.setBbPeriod(root.get("bbPeriod").asInt());
        if (root.has("bbStdDev")) factorProperties.setBbStdDev(root.get("bbStdDev").asDouble());
        if (root.has("atrPeriod")) factorProperties.setAtrPeriod(root.get("atrPeriod").asInt());
        if (root.has("adxPeriod")) factorProperties.setAdxPeriod(root.get("adxPeriod").asInt());

        log.info("Applied factor config: key={}, params={}", config.getConfigKey(), config.getContentJson());
    }

    private void applyExecutionConfig(ConfigVersionDO config) {
        JsonNode root;
        try {
            root = objectMapper.readTree(config.getContentJson());
        } catch (Exception e) {
            log.error("Failed to parse execution config JSON: {}", config.getContentJson(), e);
            return;
        }

        // slippageBps 在 RiskProperties 中管理
        if (root.has("slippageBps")) {
            riskProperties.setSlippageBps(root.get("slippageBps").asInt());
        }

        log.info("Applied execution config: key={}, params={}", config.getConfigKey(), config.getContentJson());
    }

    private void applyConnectorConfig(ConfigVersionDO config) {
        JsonNode root;
        try {
            root = objectMapper.readTree(config.getContentJson());
        } catch (Exception e) {
            log.error("Failed to parse connector config JSON: {}", config.getContentJson(), e);
            return;
        }

        if (root.has("symbols")) {
            List<String> symbols = new ArrayList<>();
            root.get("symbols").forEach(node -> symbols.add(node.asText()));
            connectorProperties.setSymbols(symbols);
        }
        if (root.has("binanceWsUrl")) {
            // Legacy connector configs represented only the USD-M futures endpoint.
            connectorProperties.setBinancePerpetualWsUrl(root.get("binanceWsUrl").asText());
        }
        if (root.has("binanceSpotWsUrl")) {
            connectorProperties.setBinanceSpotWsUrl(root.get("binanceSpotWsUrl").asText());
        }
        if (root.has("binancePerpetualWsUrl")) {
            connectorProperties.setBinancePerpetualWsUrl(
                    root.get("binancePerpetualWsUrl").asText());
        }
        if (root.has("binanceTimeframes")) {
            List<String> timeframes = new ArrayList<>();
            root.get("binanceTimeframes").forEach(node -> timeframes.add(node.asText()));
            connectorProperties.setBinanceTimeframes(timeframes);
        }
        if (root.has("coinglassWsUrl")) {
            connectorProperties.setCoinglassWsUrl(root.get("coinglassWsUrl").asText());
        }
        if (root.has("okxEnabled")) {
            okxProperties.setEnabled(root.get("okxEnabled").asBoolean());
        }
        if (root.has("okxWsUrl")) {
            okxProperties.setWebsocketUrl(root.get("okxWsUrl").asText());
        }
        if (root.has("okxRestBaseUrl")) {
            okxProperties.setRestBaseUrl(root.get("okxRestBaseUrl").asText());
        }
        if (root.has("okxInstruments")) {
            List<String> instruments = new ArrayList<>();
            root.get("okxInstruments").forEach(node -> instruments.add(node.asText()));
            okxProperties.setInstruments(instruments);
        }
        if (root.has("okxTimeframes")) {
            List<String> timeframes = new ArrayList<>();
            root.get("okxTimeframes").forEach(node -> timeframes.add(node.asText()));
            okxProperties.setTimeframes(timeframes);
        }
        if (root.has("reconnectIntervalSec")) {
            connectorProperties.setReconnectIntervalSec(root.get("reconnectIntervalSec").asInt());
        }

        log.info("Applied connector config: key={}, params={} (restart required for WebSocket reconnection)",
                config.getConfigKey(), config.getContentJson());
    }
}
