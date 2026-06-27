package com.tj.crypto.observability.alert;

import com.tj.crypto.observability.MetricsSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警服务。
 * 管理告警规则，检查指标快照并触发/解除告警。
 *
 * <p>支持的条件类型：
 * <ul>
 *   <li>EVENT_THROUGHPUT_DROP — 事件吞吐量低于阈值（events/min）</li>
 *   <li>DISCONNECTED — 断开的连接器数量 >= 阈值</li>
 *   <li>HIGH_MEMORY — 内存使用率超过阈值（百分比）</li>
 *   <li>HIGH_ERROR_RATE — 错误率超过阈值（百分比）</li>
 *   <li>HIGH_EVENT_LATENCY — P99 事件延迟超过阈值（毫秒）</li>
 *   <li>HIGH_STRATEGY_LATENCY — 策略执行 P99 延迟超过阈值（毫秒）</li>
 *   <li>QUEUE_DEPTH — 持久化队列深度超过阈值</li>
 * </ul>
 */
@Slf4j
@Component
public class AlertService {

    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final List<AlertEvent> alertHistory = Collections.synchronizedList(new ArrayList<>());

    /**
     * 检查指标并触发/解除告警。
     *
     * @param snapshot 指标快照
     * @return 本次检查产生的告警事件列表
     */
    public List<AlertEvent> checkAndAlert(MetricsSnapshot snapshot) {
        List<AlertEvent> newAlerts = new ArrayList<>();

        for (AlertRule rule : rules.values()) {
            if (!rule.enabled()) {
                continue;
            }
            Optional<String> violation = evaluate(rule, snapshot);
            if (violation.isPresent()) {
                AlertEvent event = new AlertEvent(
                        rule.name(),
                        rule.severity(),
                        violation.get(),
                        System.currentTimeMillis(),
                        false
                );
                newAlerts.add(event);
                alertHistory.add(event);
                log.warn("[ALERT] {} [{}]: {}", rule.severity(), rule.name(), violation.get());
            }
        }

        return newAlerts;
    }

    /**
     * 评估单条规则是否被触发。
     *
     * @return 违规描述，空表示未触发
     */
    private Optional<String> evaluate(AlertRule rule, MetricsSnapshot snapshot) {
        return switch (rule.condition()) {
            case "EVENT_THROUGHPUT_DROP" -> {
                long totalEvents = snapshot.barEvents() + snapshot.liquidationEvents()
                        + snapshot.fundingRateEvents() + snapshot.openInterestEvents();
                if (totalEvents < rule.threshold()) {
                    yield Optional.of("Event throughput " + totalEvents
                            + " is below threshold " + (long) rule.threshold());
                }
                yield Optional.empty();
            }
            case "DISCONNECTED" -> {
                long disconnected = snapshot.connectorHealthMap().values().stream()
                        .filter(h -> !h.connected())
                        .count();
                if (disconnected >= rule.threshold()) {
                    yield Optional.of(disconnected + " connector(s) disconnected (threshold: "
                            + (long) rule.threshold() + ")");
                }
                yield Optional.empty();
            }
            case "HIGH_MEMORY" -> {
                double usedPct = snapshot.memoryUsedPct();
                if (usedPct > rule.threshold()) {
                    yield Optional.of("Memory usage " + String.format("%.1f%%", usedPct)
                            + " exceeds threshold " + rule.threshold() + "%");
                }
                yield Optional.empty();
            }
            case "HIGH_ERROR_RATE" -> {
                double errorRate = snapshot.errorRatePct();
                if (errorRate > rule.threshold()) {
                    yield Optional.of("Error rate " + String.format("%.2f%%", errorRate)
                            + " exceeds threshold " + rule.threshold() + "%");
                }
                yield Optional.empty();
            }
            case "HIGH_EVENT_LATENCY" -> {
                double p99 = snapshot.eventProcessingP99Ms();
                if (p99 > rule.threshold()) {
                    yield Optional.of("Event P99 latency " + String.format("%.1fms", p99)
                            + " exceeds threshold " + rule.threshold() + "ms");
                }
                yield Optional.empty();
            }
            case "HIGH_STRATEGY_LATENCY" -> {
                double p99 = snapshot.strategyExecutionP99Ms();
                if (p99 > rule.threshold()) {
                    yield Optional.of("Strategy P99 latency " + String.format("%.1fms", p99)
                            + " exceeds threshold " + rule.threshold() + "ms");
                }
                yield Optional.empty();
            }
            case "QUEUE_DEPTH" -> {
                long depth = snapshot.persistenceQueueDepth();
                if (depth >= rule.threshold()) {
                    yield Optional.of("Persistence queue depth " + depth
                            + " exceeds threshold " + (long) rule.threshold());
                }
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /**
     * 添加告警规则。
     * 如果同名规则已存在则覆盖。
     */
    public void addRule(AlertRule rule) {
        rules.put(rule.name(), rule);
        log.info("[ALERT] Rule added: {} ({})", rule.name(), rule.condition());
    }

    /**
     * 移除告警规则。
     *
     * @param name 规则名称
     * @return 是否成功移除
     */
    public boolean removeRule(String name) {
        AlertRule removed = rules.remove(name);
        if (removed != null) {
            log.info("[ALERT] Rule removed: {}", name);
            return true;
        }
        return false;
    }

    /**
     * 获取所有已注册的告警规则。
     */
    public List<AlertRule> getActiveRules() {
        return List.copyOf(rules.values());
    }

    /**
     * 获取告警历史。
     */
    public List<AlertEvent> getAlertHistory() {
        return List.copyOf(alertHistory);
    }
}
