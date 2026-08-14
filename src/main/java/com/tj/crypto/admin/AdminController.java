package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AdminOverviewService;
import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.application.AuthService;
import com.tj.crypto.admin.application.ConfigVersionService;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersion;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.admin.dto.ConnectorStatusDTO;
import com.tj.crypto.admin.dto.FactorInfoDTO;
import com.tj.crypto.admin.dto.LoginRequest;
import com.tj.crypto.admin.dto.OverviewDTO;
import com.tj.crypto.admin.dto.RiskConfigDTO;
import com.tj.crypto.admin.dto.StrategyInfoDTO;
import com.tj.crypto.admin.dto.SystemStatusDTO;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.observability.MetricsSnapshot;
import com.tj.crypto.observability.SystemMetrics;
import com.tj.crypto.observability.alert.AlertEvent;
import com.tj.crypto.observability.alert.AlertRule;
import com.tj.crypto.observability.alert.AlertService;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.storage.service.AutoBackfillService;
import com.tj.crypto.storage.service.CoverageReport;
import com.tj.crypto.storage.service.DataCoverageService;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.StrategyManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Admin REST API。
 * 提供系统状态、信号查询、因子列表、策略列表、健康检查等监控端点。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final AdminOverviewService adminOverviewService;
    private final ConfigVersionService configVersionService;
    private final StrategyManager strategyManager;
    private final DataCoverageService dataCoverageService;
    private final AutoBackfillService autoBackfillService;
    private final KillSwitch killSwitch;
    private final AlertService alertService;
    private final SystemMetrics systemMetrics;
    private final AuthService authService;
    private final AuditService auditService;
    private final com.tj.crypto.factor.analysis.FactorAnalyzer factorAnalyzer;

    private final MarketUniverseProperties marketUniverse;

    // ========== 登录端点（不需要认证） ==========

    /**
     * 用户登录。
     * 验证用户名密码，返回 token。
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest credentials,
            HttpServletRequest request) {
        String username = credentials == null ? null : credentials.getUsername();
        String password = credentials == null ? null : credentials.getPassword();
        try {
            String token = authService.login(username, password);
            auditService.logOperation(username, "LOGIN",
                    "ip=" + request.getRemoteAddr());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "token", token
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * 系统状态。
     * 返回运行时间、策略数量、因子数量等关键指标。
     */
    @GetMapping("/status")
    public ResponseEntity<SystemStatusDTO> getStatus() {
        return ResponseEntity.ok(adminService.getSystemStatus());
    }

    /**
     * 最近的信号列表。
     *
     * @param limit 最大返回数量，默认 50
     */
    @GetMapping("/signals")
    public ResponseEntity<List<SignalEvent>> getSignals(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(adminService.getRecentSignals(limit));
    }

    /**
     * 所有已注册因子列表。
     */
    @GetMapping("/factors")
    public ResponseEntity<List<FactorInfoDTO>> getFactors() {
        return ResponseEntity.ok(adminService.getAllFactors());
    }

    /**
     * 所有已注册策略列表。
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<StrategyInfoDTO>> getStrategies() {
        return ResponseEntity.ok(adminService.getAllStrategies());
    }

    /**
     * 健康检查。
     * 返回各连接器状态和系统指标。
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        SystemStatusDTO status = adminService.getSystemStatus();
        List<Map<String, Object>> connectors = adminService.getConnectorHealth();
        return ResponseEntity.ok(Map.of(
                "status", status.getConnectedConnectorCount() > 0 ? "UP" : "DOWN",
                "uptimeMs", status.getUptimeMs(),
                "connectors", connectors,
                "strategyCount", status.getStrategyCount(),
                "factorCount", status.getFactorCount(),
                "totalSignalCount", status.getTotalSignalCount()
        ));
    }

    /**
     * 启用策略。
     * 启用后策略将开始接收并处理市场事件。
     *
     * @param name 策略名称
     */
    @PostMapping("/strategies/{name}/enable")
    public ResponseEntity<Map<String, Object>> enableStrategy(@PathVariable String name,
                                                               HttpServletRequest request) {
        boolean success = strategyManager.enableStrategy(name);
        if (!success) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Unknown strategy: " + name
            ));
        }
        String operator = getOperator(request);
        auditService.logOperation(operator, "ENABLE_STRATEGY", "strategy=" + name);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "strategy", name,
                "enabled", true
        ));
    }

    /**
     * 禁用策略。
     * 禁用后策略将停止接收市场事件。
     *
     * @param name 策略名称
     */
    @PostMapping("/strategies/{name}/disable")
    public ResponseEntity<Map<String, Object>> disableStrategy(@PathVariable String name,
                                                                HttpServletRequest request) {
        boolean success = strategyManager.disableStrategy(name);
        if (!success) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Unknown strategy: " + name
            ));
        }
        String operator = getOperator(request);
        auditService.logOperation(operator, "DISABLE_STRATEGY", "strategy=" + name);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "strategy", name,
                "enabled", false
        ));
    }

    /**
     * 查询策略状态。
     *
     * @param name 策略名称
     */
    @GetMapping("/strategies/{name}/status")
    public ResponseEntity<Map<String, Object>> getStrategyStatus(@PathVariable String name) {
        return strategyManager.getStrategy(name)
                .map(s -> ResponseEntity.ok(Map.<String, Object>of(
                        "name", s.name(),
                        "enabled", strategyManager.isStrategyEnabled(name),
                        "listenedEvents", s.listenedEvents().stream()
                                .map(Class::getSimpleName)
                                .toList()
                )))
                .orElse(ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Unknown strategy: " + name
                )));
    }

    /**
     * 策略详情。
     * 返回策略基本信息 + 最近信号。
     *
     * @param name 策略名称
     */
    @GetMapping("/strategies/{name}/detail")
    public ResponseEntity<Map<String, Object>> getStrategyDetail(@PathVariable String name) {
        return strategyManager.getStrategy(name)
                .map(s -> {
                    List<SignalEvent> recentSignals = adminService.getRecentSignals(20).stream()
                            .filter(sig -> sig.strategyName().equals(name))
                            .toList();
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "name", s.name(),
                            "enabled", strategyManager.isStrategyEnabled(name),
                            "listenedEvents", s.listenedEvents().stream()
                                    .map(Class::getSimpleName)
                                    .toList(),
                            "recentSignals", recentSignals
                    ));
                })
                .orElse(ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Unknown strategy: " + name
                )));
    }

    /**
     * 策略最近信号。
     *
     * @param name  策略名称
     * @param limit 最大返回数量
     */
    @GetMapping("/strategies/{name}/signals")
    public ResponseEntity<List<SignalEvent>> getStrategySignals(
            @PathVariable String name,
            @RequestParam(defaultValue = "10") int limit) {
        List<SignalEvent> signals = adminService.getRecentSignals(limit).stream()
                .filter(sig -> sig.strategyName().equals(name))
                .toList();
        return ResponseEntity.ok(signals);
    }

    /**
     * 查询数据覆盖率。
     *
     * @param symbol    交易对符号，如 "BTCUSDT"
     * @param timeframe 时间周期，如 "1m", "5m"
     * @param days      回溯天数，默认 30
     */
    @GetMapping("/coverage")
    public ResponseEntity<CoverageReport> getCoverage(
            @RequestParam(defaultValue = "BINANCE") com.tj.crypto.common.domain.Exchange exchange,
            @RequestParam(defaultValue = "PERPETUAL") com.tj.crypto.common.domain.MarketType marketType,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > 3650) {
            throw new IllegalArgumentException("days must be between 1 and 3650");
        }
        marketUniverse.validate(exchange, marketType, symbol);
        com.tj.crypto.common.domain.Timeframe tf =
                com.tj.crypto.common.domain.Timeframe.fromCode(timeframe);
        long currentBucket = (System.currentTimeMillis() / tf.getMillis()) * tf.getMillis();
        long to = currentBucket - tf.getMillis();
        long from = to - Duration.ofDays(days).toMillis() + tf.getMillis();
        com.tj.crypto.common.domain.Instrument instrument =
                com.tj.crypto.common.domain.Instrument.of(exchange, marketType,
                        MarketUniverseProperties.normalizeSymbol(symbol));
        CoverageReport report = dataCoverageService.checkCoverage(instrument, timeframe, from, to);
        return ResponseEntity.ok(report);
    }

    /**
     * 触发数据回填。
     * 当覆盖率低于 95% 时自动从 Binance 拉取缺失的 K 线数据。
     *
     * @param symbol    交易对符号，如 "BTCUSDT"
     * @param timeframe 时间周期，如 "1m", "5m"
     * @param days      回溯天数，默认 30
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> triggerBackfill(
            @RequestParam(defaultValue = "BINANCE") com.tj.crypto.common.domain.Exchange exchange,
            @RequestParam(defaultValue = "PERPETUAL") com.tj.crypto.common.domain.MarketType marketType,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "30") int days,
            HttpServletRequest request) {
        int filled = autoBackfillService.backfillIfNeeded(
                exchange, marketType, symbol, timeframe, days);
        String operator = getOperator(request);
        auditService.logOperation(operator, "TRIGGER_BACKFILL",
                "symbol=" + symbol + ",timeframe=" + timeframe + ",days=" + days + ",filled=" + filled);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "exchange", exchange,
                "marketType", marketType,
                "symbol", symbol,
                "timeframe", timeframe,
                "days", days,
                "barsFilled", filled
        ));
    }

    /**
     * 系统总览。
     * 聚合连接状态、策略数量、因子数量、信号数量、风控状态。
     */
    @GetMapping("/overview")
    public ResponseEntity<OverviewDTO> getOverview() {
        return ResponseEntity.ok(adminOverviewService.getOverview());
    }

    /**
     * 连接器状态列表。
     * 返回所有数据源连接器的运行状态。
     */
    @GetMapping("/connectors")
    public ResponseEntity<List<ConnectorStatusDTO>> getConnectors() {
        return ResponseEntity.ok(adminOverviewService.getConnectorStatuses());
    }

    /**
     * 风控配置读取。
     * 返回当前生效的风险控制参数。
     */
    @GetMapping("/risk/configs")
    public ResponseEntity<RiskConfigDTO> getRiskConfigs() {
        return ResponseEntity.ok(adminOverviewService.getRiskConfig());
    }

    // ========== 全局熔断端点 ==========

    /**
     * 查询 KillSwitch 状态。
     */
    @GetMapping("/risk/kill-switch")
    public ResponseEntity<Map<String, Object>> getKillSwitchStatus() {
        return ResponseEntity.ok(Map.of(
                "active", killSwitch.isActive(),
                "mode", killSwitch.getMode().name(),
                "persistenceHealthy", killSwitch.isPersistenceHealthy()
        ));
    }

    /**
     * 激活 KillSwitch。
     *
     * @param mode 熔断模式：NORMAL, CLOSE_ONLY, HALT
     */
    @PostMapping("/risk/kill-switch")
    public ResponseEntity<Map<String, Object>> activateKillSwitch(
            @RequestParam(defaultValue = "HALT") KillSwitch.Mode mode,
            HttpServletRequest request) {
        String operator = getOperator(request);
        killSwitch.activate(mode, "ADMIN_API_ACTIVATION", operator);
        auditService.logOperation(operator, "KILL_SWITCH_ACTIVATE", "mode=" + mode);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "active", killSwitch.isActive(),
                "mode", killSwitch.getMode().name()
        ));
    }

    /**
     * 解除 KillSwitch，恢复正常交易。
     */
    @PostMapping("/risk/kill-switch/deactivate")
    public ResponseEntity<Map<String, Object>> deactivateKillSwitch(HttpServletRequest request) {
        String operator = getOperator(request);
        killSwitch.deactivate("ADMIN_API_DEACTIVATION", operator);
        auditService.logOperation(operator, "KILL_SWITCH_DEACTIVATE", "");
        return ResponseEntity.ok(Map.of(
                "success", true,
                "active", false,
                "mode", "NORMAL"
        ));
    }

    // ========== 配置版本管理端点 ==========

    /**
     * 创建配置草稿。
     *
     * @param type        配置类型（CONNECTOR, FACTOR, STRATEGY, RISK, EXECUTION）
     * @param configKey   配置键（如策略名、因子名）
     * @param contentJson 配置内容 JSON
     * @param remark      备注说明
     */
    @PostMapping("/configs/draft")
    public ResponseEntity<ConfigVersion> createDraft(
            @RequestParam ConfigType type,
            @RequestParam String configKey,
            @RequestParam String contentJson,
            @RequestParam(required = false, defaultValue = "") String remark) {
        ConfigVersion draft = configVersionService.createDraft(type, configKey, contentJson, remark);
        return ResponseEntity.ok(draft);
    }

    /**
     * 发布配置版本。
     * 前置条件：版本必须处于 VALIDATED 状态。
     *
     * @param versionId   版本 ID
     */
    @PostMapping("/configs/{versionId}/publish")
    public ResponseEntity<ConfigVersion> publishConfig(
            @PathVariable String versionId,
            HttpServletRequest request) {
        String operator = getOperator(request);
        ConfigVersion published = configVersionService.publish(versionId, operator);
        auditService.logOperation(operator, "PUBLISH_CONFIG",
                "versionId=" + versionId + ",publishedBy=" + operator);
        return ResponseEntity.ok(published);
    }

    /**
     * 验证配置版本。
     * 状态从 DRAFT 变为 VALIDATED，是发布前的必要步骤。
     *
     * @param versionId 版本 ID
     */
    @PostMapping("/configs/{versionId}/validate")
    public ResponseEntity<ConfigVersion> validateConfig(
            @PathVariable String versionId,
            HttpServletRequest request) {
        ConfigVersion validated = configVersionService.validate(versionId);
        String operator = getOperator(request);
        auditService.logOperation(operator, "VALIDATE_CONFIG",
                "versionId=" + versionId);
        return ResponseEntity.ok(validated);
    }

    /**
     * 回滚配置版本。
     * 将当前 ACTIVE 版本回滚到指定的历史版本。
     *
     * @param versionId       当前版本 ID
     * @param targetVersionId 目标版本 ID（回滚到此版本）
     */
    @PostMapping("/configs/{versionId}/rollback")
    public ResponseEntity<ConfigVersion> rollbackConfig(
            @PathVariable String versionId,
            @RequestParam String targetVersionId,
            HttpServletRequest request) {
        ConfigVersion rolledBack = configVersionService.rollback(versionId, targetVersionId);
        String operator = getOperator(request);
        auditService.logOperation(operator, "ROLLBACK_CONFIG",
                "from=" + versionId + ",to=" + targetVersionId);
        return ResponseEntity.ok(rolledBack);
    }

    /**
     * 查询配置。
     * 如果指定 type 和 configKey，返回当前生效版本；
     * 如果只指定 type，返回该类型下所有生效版本。
     *
     * @param type      配置类型
     * @param configKey 配置键（可选）
     */
    @GetMapping("/configs")
    public ResponseEntity<?> getConfigs(
            @RequestParam ConfigType type,
            @RequestParam(required = false) String configKey) {
        if (configKey != null && !configKey.isBlank()) {
            return configVersionService.getActive(type, configKey)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
        return ResponseEntity.ok(configVersionService.getActiveByType(type));
    }

    /**
     * 查询配置版本历史。
     * 返回指定配置项的所有版本（按创建时间排序）。
     *
     * @param type      配置类型
     * @param configKey 配置键
     */
    @GetMapping("/configs/history")
    public ResponseEntity<List<ConfigVersion>> getConfigHistory(
            @RequestParam ConfigType type,
            @RequestParam String configKey) {
        return ResponseEntity.ok(configVersionService.getHistory(type, configKey));
    }

    // ========== 研究平台端点 ==========

    /**
     * 因子 IC（信息系数）分析。
     *
     * @param factorName 因子名称（如 SMA, RSI, MACD_HIST）
     * @param symbol     交易对
     * @param timeframe  时间周期
     * @param days       回溯天数
     */
    @GetMapping("/research/ic")
    public ResponseEntity<Map<String, Object>> getFactorIC(
            @RequestParam String factorName,
            @RequestParam(defaultValue = "BINANCE") com.tj.crypto.common.domain.Exchange exchange,
            @RequestParam(defaultValue = "PERPETUAL") com.tj.crypto.common.domain.MarketType marketType,
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1h") String timeframe,
            @RequestParam(defaultValue = "30") int days) {
        try {
            marketUniverse.validate(exchange, marketType, symbol);
            com.tj.crypto.common.domain.Instrument instrument =
                    com.tj.crypto.common.domain.Instrument.of(
                            exchange, marketType,
                            MarketUniverseProperties.normalizeSymbol(symbol));
            com.tj.crypto.common.domain.Timeframe tf =
                    com.tj.crypto.common.domain.Timeframe.fromCode(timeframe);

            double ic = factorAnalyzer.calculateIC(factorName, instrument, tf, days);
            double hitRate = factorAnalyzer.calculateHitRate(factorName, instrument, tf, days, 0.0);

            return ResponseEntity.ok(Map.of(
                    "factorName", factorName,
                    "exchange", exchange,
                    "marketType", marketType,
                    "symbol", symbol,
                    "timeframe", timeframe,
                    "days", days,
                    "ic", ic,
                    "hitRate", hitRate
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    // ========== 数据质量端点 ==========

    /**
     * 数据质量概览。
     * 检查所有配置交易对的 1 分钟 K 线覆盖率（最近 24 小时）。
     */
    @GetMapping("/data-quality")
    public ResponseEntity<List<CoverageReport>> getDataQuality() {
        long currentBucket = (System.currentTimeMillis() / 60_000L) * 60_000L;
        long to = currentBucket - 60_000L;
        long from = to - 24 * 60 * 60 * 1000L + 60_000L;
        List<CoverageReport> reports = marketUniverse.getExchanges().stream()
                .flatMap(exchange -> marketUniverse.getMarketTypes().stream()
                        .flatMap(marketType -> marketUniverse.getSymbols().stream()
                                .map(symbol -> dataCoverageService.checkCoverage(
                                        com.tj.crypto.common.domain.Instrument.of(
                                                exchange, marketType, symbol),
                                        "1m", from, to))))
                .toList();
        return ResponseEntity.ok(reports);
    }

    // ========== 告警与可观测性端点 ==========

    /**
     * 获取告警历史列表。
     *
     * @return 告警事件列表
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<AlertEvent>> getAlerts() {
        return ResponseEntity.ok(alertService.getAlertHistory());
    }

    /**
     * 添加告警规则。
     *
     * @param rule 告警规则
     * @return 操作结果
     */
    @PostMapping("/alerts/rules")
    public ResponseEntity<Map<String, Object>> addAlertRule(@RequestBody AlertRule rule) {
        alertService.addRule(rule);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "rule", rule.name()
        ));
    }

    /**
     * 获取详细的可观测性指标。
     * 包含事件延迟、策略耗时、队列深度、内存使用等。
     *
     * @return 指标快照
     */
    @GetMapping("/observability/metrics")
    public ResponseEntity<MetricsSnapshot> getObservabilityMetrics() {
        return ResponseEntity.ok(systemMetrics.snapshot());
    }

    /**
     * 从 request attribute 中获取当前操作人用户名。
     */
    private String getOperator(HttpServletRequest request) {
        UserDO user = (UserDO) request.getAttribute("currentUser");
        return user != null ? user.getUsername() : "unknown";
    }
}
