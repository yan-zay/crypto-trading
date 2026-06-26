package com.tj.crypto.marketdata.quality;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataQualityChecker 单元测试。
 */
class DataQualityCheckerTest {

    private DataQualityChecker checker;

    private static final Instrument BTC_USDT = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    private static final Instrument ETH_USDT = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");

    @BeforeEach
    void setUp() {
        checker = new DataQualityChecker();
    }

    @Nested
    @DisplayName("checkGaps - 时间间隙检测")
    class CheckGaps {

        @Test
        @DisplayName("无间隙的连续 K 线应返回零间隙")
        void shouldReturnZeroGapsForContinuousBars() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1060L, 105, 115, 95, 110, 60),
                    createBar(BTC_USDT, Timeframe.M1, 1120L, 110, 120, 100, 115, 70)
            );

            DataQualityReport report = checker.checkGaps(bars);

            assertThat(report.gapCount()).isZero();
            assertThat(report.hasIssues()).isFalse();
            assertThat(report.totalBars()).isEqualTo(3);
        }

        @Test
        @DisplayName("存在时间间隙时应正确检测")
        void shouldDetectGapsWhenBarsAreMissing() {
            // M1 = 60000ms, 间隔 180000ms 意味着缺了 2 根 K 线
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1000L + 180_000L, 105, 115, 95, 110, 60)
            );

            DataQualityReport report = checker.checkGaps(bars);

            assertThat(report.gapCount()).isEqualTo(1);
            assertThat(report.hasIssues()).isTrue();
            assertThat(report.issues()).hasSize(1);
            assertThat(report.issues().get(0)).contains("missing bar");
        }

        @Test
        @DisplayName("空列表或单条数据应无间隙")
        void shouldReturnZeroGapsForEmptyOrSingleBar() {
            assertThat(checker.checkGaps(Collections.emptyList()).gapCount()).isZero();
            assertThat(checker.checkGaps(null).gapCount()).isZero();

            List<BarEvent> single = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50)
            );
            assertThat(checker.checkGaps(single).gapCount()).isZero();
        }

        @Test
        @DisplayName("不同时间周期的 K 线之间不检测间隙")
        void shouldNotDetectGapsBetweenDifferentTimeframes() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M5, 1000L + 300_000L, 105, 115, 95, 110, 60)
            );

            DataQualityReport report = checker.checkGaps(bars);

            assertThat(report.gapCount()).isZero();
        }
    }

    @Nested
    @DisplayName("checkDuplicates - 重复数据检测")
    class CheckDuplicates {

        @Test
        @DisplayName("无重复数据应返回零重复")
        void shouldReturnZeroDuplicatesForUniqueBars() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1060L, 105, 115, 95, 110, 60),
                    createBar(ETH_USDT, Timeframe.M1, 1000L, 2000, 2100, 1900, 2050, 100)
            );

            DataQualityReport report = checker.checkDuplicates(bars);

            assertThat(report.duplicateCount()).isZero();
            assertThat(report.hasIssues()).isFalse();
        }

        @Test
        @DisplayName("相同 instrument + timeframe + timestamp 应检测为重复")
        void shouldDetectDuplicateBars() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1060L, 105, 115, 95, 110, 60)
            );

            DataQualityReport report = checker.checkDuplicates(bars);

            assertThat(report.duplicateCount()).isEqualTo(1);
            assertThat(report.hasIssues()).isTrue();
            assertThat(report.issues()).hasSize(1);
            assertThat(report.issues().get(0)).contains("Duplicate");
        }

        @Test
        @DisplayName("相同 timestamp 不同 instrument 不算重复")
        void shouldNotFlagSameTimestampDifferentInstrument() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(ETH_USDT, Timeframe.M1, 1000L, 2000, 2100, 1900, 2050, 100)
            );

            DataQualityReport report = checker.checkDuplicates(bars);

            assertThat(report.duplicateCount()).isZero();
        }

        @Test
        @DisplayName("空列表应返回零重复")
        void shouldReturnZeroForEmptyList() {
            assertThat(checker.checkDuplicates(Collections.emptyList()).duplicateCount()).isZero();
            assertThat(checker.checkDuplicates(null).duplicateCount()).isZero();
        }
    }

    @Nested
    @DisplayName("checkAnomalies - 异常数据检测")
    class CheckAnomalies {

        @Test
        @DisplayName("正常数据应无异常")
        void shouldReturnZeroAnomaliesForValidData() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1060L, 105, 115, 95, 110, 60)
            );

            DataQualityReport report = checker.checkAnomalies(bars);

            assertThat(report.anomalyCount()).isZero();
            assertThat(report.hasIssues()).isFalse();
        }

        @Test
        @DisplayName("负价格应检测为异常")
        void shouldDetectNegativePrice() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, -100, 110, 90, 105, 50)
            );

            DataQualityReport report = checker.checkAnomalies(bars);

            assertThat(report.anomalyCount()).isEqualTo(1);
            assertThat(report.hasIssues()).isTrue();
            assertThat(report.issues().get(0)).contains("negative price");
        }

        @Test
        @DisplayName("零成交量应检测为异常")
        void shouldDetectZeroVolume() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 0)
            );

            DataQualityReport report = checker.checkAnomalies(bars);

            assertThat(report.anomalyCount()).isEqualTo(1);
            assertThat(report.issues().get(0)).contains("zero volume");
        }

        @Test
        @DisplayName("high < low 应检测为异常")
        void shouldDetectHighLessThanLow() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 90, 110, 105, 50)
            );

            DataQualityReport report = checker.checkAnomalies(bars);

            assertThat(report.anomalyCount()).isEqualTo(1);
            assertThat(report.issues().get(0)).contains("high");
            assertThat(report.issues().get(0)).contains("low");
        }

        @Test
        @DisplayName("close 超出 [low, high] 范围应检测为异常")
        void shouldDetectCloseOutOfRange() {
            // close=120 > high=110
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 120, 50)
            );

            DataQualityReport report = checker.checkAnomalies(bars);

            assertThat(report.anomalyCount()).isEqualTo(1);
            assertThat(report.issues().get(0)).contains("out of range");
        }

        @Test
        @DisplayName("空列表应无异常")
        void shouldReturnZeroForEmptyList() {
            assertThat(checker.checkAnomalies(Collections.emptyList()).anomalyCount()).isZero();
            assertThat(checker.checkAnomalies(null).anomalyCount()).isZero();
        }

        @Test
        @DisplayName("多条异常数据应正确计数")
        void shouldCountMultipleAnomalies() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, -100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1060L, 100, 110, 90, 105, 0),
                    createBar(BTC_USDT, Timeframe.M1, 1120L, 100, 110, 90, 105, 50)
            );

            DataQualityReport report = checker.checkAnomalies(bars);

            assertThat(report.anomalyCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("checkAll - 综合检查")
    class CheckAll {

        @Test
        @DisplayName("应合并所有检查结果")
        void shouldMergeAllCheckResults() {
            // 1 根正常 + 1 根有间隙 + 1 根重复 + 1 根异常
            List<BarEvent> bars = new ArrayList<>();
            bars.add(createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50));
            bars.add(createBar(BTC_USDT, Timeframe.M1, 1000L + 180_000L, 105, 115, 95, 110, 60));
            bars.add(createBar(BTC_USDT, Timeframe.M1, 1000L + 180_000L, 105, 115, 95, 110, 60));
            bars.add(createBar(BTC_USDT, Timeframe.M1, 1000L + 240_000L, -100, 110, 90, 105, 50));

            DataQualityReport report = checker.checkAll(bars);

            assertThat(report.totalBars()).isEqualTo(4);
            assertThat(report.gapCount()).isGreaterThanOrEqualTo(1);
            assertThat(report.duplicateCount()).isEqualTo(1);
            assertThat(report.anomalyCount()).isGreaterThanOrEqualTo(1);
            assertThat(report.hasIssues()).isTrue();
        }

        @Test
        @DisplayName("干净数据应返回无问题报告")
        void shouldReturnCleanReportForGoodData() {
            List<BarEvent> bars = List.of(
                    createBar(BTC_USDT, Timeframe.M1, 1000L, 100, 110, 90, 105, 50),
                    createBar(BTC_USDT, Timeframe.M1, 1060L, 105, 115, 95, 110, 60),
                    createBar(BTC_USDT, Timeframe.M1, 1120L, 110, 120, 100, 115, 70)
            );

            DataQualityReport report = checker.checkAll(bars);

            assertThat(report.hasIssues()).isFalse();
            assertThat(report.totalBars()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("DataQualityReport")
    class ReportTests {

        @Test
        @DisplayName("clean 工厂方法应创建无问题报告")
        void shouldCreateCleanReport() {
            DataQualityReport report = DataQualityReport.clean(10);

            assertThat(report.totalBars()).isEqualTo(10);
            assertThat(report.gapCount()).isZero();
            assertThat(report.duplicateCount()).isZero();
            assertThat(report.anomalyCount()).isZero();
            assertThat(report.issues()).isEmpty();
            assertThat(report.hasIssues()).isFalse();
        }
    }

    // ---- 辅助方法 ----

    private BarEvent createBar(Instrument instrument, Timeframe timeframe, long timestamp,
                               int open, int high, int low, int close, int volume) {
        return new BarEvent(
                instrument,
                EventMetadata.of(instrument.exchange(), timestamp),
                timeframe,
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(volume),
                BigDecimal.valueOf(volume * close),
                true
        );
    }
}
