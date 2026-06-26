package com.tj.crypto.admin;

import com.tj.crypto.admin.dto.FactorInfoDTO;
import com.tj.crypto.admin.dto.StrategyInfoDTO;
import com.tj.crypto.admin.dto.SystemStatusDTO;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.StrategyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final StrategyManager strategyManager;

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
}
