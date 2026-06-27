package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AdminOverviewService;
import com.tj.crypto.admin.application.ConfigVersionService;
import com.tj.crypto.admin.domain.ConfigType;
import com.tj.crypto.admin.domain.ConfigVersion;
import com.tj.crypto.admin.dto.ConnectorStatusDTO;
import com.tj.crypto.admin.dto.FactorInfoDTO;
import com.tj.crypto.admin.dto.OverviewDTO;
import com.tj.crypto.admin.dto.RiskConfigDTO;
import com.tj.crypto.admin.dto.StrategyInfoDTO;
import com.tj.crypto.admin.dto.SystemStatusDTO;
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
    public ResponseEntity<Map<String, Object>> enableStrategy(@PathVariable String name) {
        boolean success = strategyManager.enableStrategy(name);
        if (!success) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Unknown strategy: " + name
            ));
        }
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
    public ResponseEntity<Map<String, Object>> disableStrategy(@PathVariable String name) {
        boolean success = strategyManager.disableStrategy(name);
        if (!success) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Unknown strategy: " + name
            ));
        }
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
     * 查询数据覆盖率。
     *
     * @param symbol    交易对符号，如 "BTCUSDT"
     * @param timeframe 时间周期，如 "1m", "5m"
     * @param days      回溯天数，默认 30
     */
    @GetMapping("/coverage")
    public ResponseEntity<CoverageReport> getCoverage(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "30") int days) {
        long now = System.currentTimeMillis();
        long from = now - Duration.ofDays(days).toMillis();
        CoverageReport report = dataCoverageService.checkCoverage(symbol, timeframe, from, now);
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
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "30") int days) {
        int filled = autoBackfillService.backfillIfNeeded(symbol, timeframe, days);
        return ResponseEntity.ok(Map.of(
                "success", true,
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
                "mode", killSwitch.getMode().name()
        ));
    }

    /**
     * 激活 KillSwitch。
     *
     * @param mode 熔断模式：NORMAL, CLOSE_ONLY, HALT
     */
    @PostMapping("/risk/kill-switch")
    public ResponseEntity<Map<String, Object>> activateKillSwitch(
            @RequestParam(defaultValue = "HALT") KillSwitch.Mode mode) {
        killSwitch.activate(mode);
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
    public ResponseEntity<Map<String, Object>> deactivateKillSwitch() {
        killSwitch.deactivate();
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
     * @param publishedBy 发布人
     */
    @PostMapping("/configs/{versionId}/publish")
    public ResponseEntity<ConfigVersion> publishConfig(
            @PathVariable String versionId,
            @RequestParam(defaultValue = "admin") String publishedBy) {
        ConfigVersion published = configVersionService.publish(versionId, publishedBy);
        return ResponseEntity.ok(published);
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
}
