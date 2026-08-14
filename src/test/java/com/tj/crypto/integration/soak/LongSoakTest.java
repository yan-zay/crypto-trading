package com.tj.crypto.integration.soak;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长时间 soak test（默认 24 小时，可配置）。
 * 默认 @Disabled，需通过环境变量 SOAK_TEST_DURATION_MINUTES 启用。
 *
 * <p>启用方式：
 * <pre>
 * # 运行 24 小时（1440 分钟）
 * SOAK_TEST_DURATION_MINUTES=1440 mvn test -pl . -Dtest=LongSoakTest
 *
 * # 运行 1 小时
 * SOAK_TEST_DURATION_MINUTES=60 mvn test -pl . -Dtest=LongSoakTest
 * </pre>
 *
 * <p>检测项：
 * <ul>
 *   <li>无内存泄漏（堆内存持续增长不超过 100MB）</li>
 *   <li>无线程泄漏（线程数增长不超过 10）</li>
 *   <li>无事件丢失（sent == processed）</li>
 *   <li>无高错误率（错误率 < 1%）</li>
 * </ul>
 */
@Tag("soak")
@DisplayName("长时间稳定性测试 (24h/72h)")
class LongSoakTest {

    private static final int DEFAULT_DURATION_MINUTES = 1440; // 24 小时

    private static final List<Instrument> TEST_SYMBOLS = List.of(
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT"),
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "SOLUSDT"),
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BNBUSDT")
    );

    @Test
    @DisplayName("长时间持续运行：无内存泄漏、无线程泄漏、无事件丢失")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "SOAK_TEST_DURATION_MINUTES",
            matches = "\\d+",
            disabledReason = "长时间 soak test 需设置 SOAK_TEST_DURATION_MINUTES 环境变量启用"
    )
    void shouldRunStablyForLongDuration() {
        // Arrange: 从环境变量读取持续时间
        int durationMinutes = getDurationMinutes();
        SoakTestConfig config = SoakTestConfig.longTest(TEST_SYMBOLS, durationMinutes);
        SoakTestRunner runner = new SoakTestRunner(config);

        // Act
        SoakTestRunner.SoakTestResult result = runner.run();

        // 输出摘要
        printSummary(result, durationMinutes);

        // Assert: 无异常
        assertThat(result.anomalies())
                .as("长时间 soak test 不应有异常，实际异常: %s", result.anomalies())
                .isEmpty();

        // Assert: 事件应全部处理
        assertThat(result.totalEventsProcessed())
                .as("已处理事件数应等于已发送事件数")
                .isEqualTo(result.totalEventsSent());

        // Assert: 应有信号生成
        assertThat(result.totalSignalsGenerated())
                .as("应有信号生成")
                .isGreaterThan(0);

        // Assert: 最终堆内存合理
        SoakTestMetrics lastSnapshot = result.snapshots().get(result.snapshots().size() - 1);
        assertThat(lastSnapshot.heapUsedMB())
                .as("最终堆内存不应超过 1GB")
                .isLessThan(1024);
    }

    private static int getDurationMinutes() {
        String envValue = System.getenv("SOAK_TEST_DURATION_MINUTES");
        if (envValue != null && !envValue.isBlank()) {
            try {
                return Integer.parseInt(envValue.trim());
            } catch (NumberFormatException e) {
                // 忽略，使用默认值
            }
        }
        return DEFAULT_DURATION_MINUTES;
    }

    private static void printSummary(SoakTestRunner.SoakTestResult result, int durationMinutes) {
        System.out.println("========================================");
        System.out.println("Soak Test Summary");
        System.out.println("========================================");
        System.out.printf("Duration:           %d minutes%n", durationMinutes);
        System.out.printf("Events sent:        %d%n", result.totalEventsSent());
        System.out.printf("Events processed:   %d%n", result.totalEventsProcessed());
        System.out.printf("Signals generated:  %d%n", result.totalSignalsGenerated());
        System.out.printf("Snapshots:          %d%n", result.snapshots().size());
        System.out.printf("Anomalies:          %d%n", result.anomalies().size());
        System.out.printf("Passed:             %s%n", result.passed());

        if (!result.anomalies().isEmpty()) {
            System.out.println("--- Anomalies ---");
            result.anomalies().forEach(a -> System.out.println("  " + a));
        }

        if (!result.snapshots().isEmpty()) {
            SoakTestMetrics first = result.snapshots().get(0);
            SoakTestMetrics last = result.snapshots().get(result.snapshots().size() - 1);
            System.out.println("--- Memory ---");
            System.out.printf("  Heap start: %dMB%n", first.heapUsedMB());
            System.out.printf("  Heap end:   %dMB%n", last.heapUsedMB());
            System.out.printf("  Growth:     %dMB%n", last.heapUsedMB() - first.heapUsedMB());
            System.out.println("--- Threads ---");
            System.out.printf("  Threads start: %d%n", first.threadCount());
            System.out.printf("  Threads end:   %d%n", last.threadCount());
        }
        System.out.println("========================================");
    }
}
