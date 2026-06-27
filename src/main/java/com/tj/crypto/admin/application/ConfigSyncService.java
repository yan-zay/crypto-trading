package com.tj.crypto.admin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersionDO;
import com.tj.crypto.admin.mapper.ConfigVersionMapper;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.StrategyManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
    private final ObjectMapper objectMapper;

    public ConfigSyncService(ConfigVersionMapper configVersionMapper,
                             StrategyManager strategyManager,
                             RiskProperties riskProperties,
                             ObjectMapper objectMapper) {
        this.configVersionMapper = configVersionMapper;
        this.strategyManager = strategyManager;
        this.riskProperties = riskProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        syncActiveConfigs();
    }

    @EventListener
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
            case CONNECTOR, EXECUTION -> log.debug("Config type {} not yet applied to runtime", type);
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
            strategyManager.enableStrategy(strategyName);
        } else {
            strategyManager.disableStrategy(strategyName);
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
        log.info("Factor config loaded: key={}, params={}", config.getConfigKey(), config.getContentJson());
        // Factor 参数在 FactorCalculator 初始化时已读取，运行态更新待 FactorRegistry 支持动态参数后实现
    }
}
