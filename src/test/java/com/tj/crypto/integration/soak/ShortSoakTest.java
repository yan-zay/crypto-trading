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
 * 短时 soak test（2 分钟）。
 * 可在 CI 中运行，验证系统在短时间持续运行下的基本稳定性。
 *
 * <p>检测项：
 * <ul>
 *   <li>无内存泄漏（堆内存增长不超过 100MB）</li>
 *   <li>无线程泄漏（线程数增长不超过 10）</li>
 *   <li>无事件丢失（sent == processed）</li>
 *   <li>无高错误率（错误率 < 1%）</li>
 * </ul>
 */
@Tag("soak")
@DisplayName("短时稳定性测试 (2 分钟)")
class ShortSoakTest {

    private static final List<Instrument> TEST_SYMBOLS = List.of(
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT")
    );

    @Test
    @DisplayName("2 分钟持续运行：无内存泄漏、无线程泄漏、无事件丢失")
    void shouldRunStablyFor2Minutes() {
        // Arrange
        SoakTestConfig config = SoakTestConfig.shortTest(TEST_SYMBOLS);
        SoakTestRunner runner = new SoakTestRunner(config);

        // Act
        SoakTestRunner.SoakTestResult result = runner.run();

        // Assert: 无异常
        assertThat(result.anomalies())
                .as("2 分钟 soak test 不应有异常，实际异常: %s", result.anomalies())
                .isEmpty();

        // Assert: 事件应全部处理
        assertThat(result.totalEventsProcessed())
                .as("已处理事件数应等于已发送事件数")
                .isEqualTo(result.totalEventsSent());

        // Assert: 应有信号生成（每 100 事件一个信号，2 分钟约 2400 个事件 -> ~24 个信号）
        assertThat(result.totalSignalsGenerated())
                .as("应有信号生成")
                .isGreaterThan(0);

        // Assert: 应有指标快照
        assertThat(result.snapshots())
                .as("应有指标快照")
                .isNotEmpty();

        // Assert: 最终堆内存合理（不超过 500MB）
        SoakTestMetrics lastSnapshot = result.snapshots().get(result.snapshots().size() - 1);
        assertThat(lastSnapshot.heapUsedMB())
                .as("最终堆内存不应超过 500MB")
                .isLessThan(500);
    }

    @Test
    @DisplayName("短时 soak test 应产生周期性状态报告")
    void shouldProducePeriodicReports() {
        // Arrange
        SoakTestConfig config = SoakTestConfig.shortTest(TEST_SYMBOLS);
        SoakTestRunner runner = new SoakTestRunner(config);

        // Act
        SoakTestRunner.SoakTestResult result = runner.run();

        // Assert: 应有周期性报告（2 分钟 / 10 秒间隔 = ~12 个报告）
        assertThat(result.reports())
                .as("应有周期性状态报告")
                .isNotEmpty();
    }
}
