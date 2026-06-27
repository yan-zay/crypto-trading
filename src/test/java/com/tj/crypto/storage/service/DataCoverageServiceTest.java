package com.tj.crypto.storage.service;

import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DataCoverageService 单元测试。
 */
class DataCoverageServiceTest {

    private BarEventMapper barEventMapper;
    private DataCoverageService service;

    private static final long ONE_MINUTE = 60_000L;
    private static final long FIVE_MINUTES = 300_000L;

    @BeforeEach
    void setUp() {
        barEventMapper = mock(BarEventMapper.class);
        service = new DataCoverageService(barEventMapper);
    }

    @Nested
    @DisplayName("checkCoverage - 覆盖率计算")
    class CheckCoverage {

        @Test
        @DisplayName("完整数据应返回 100% 覆盖率")
        void shouldReturn100PercentForCompleteData() {
            long from = 1000L;
            long to = 1000L + 9 * ONE_MINUTE; // 10 bars expected
            List<BarEventDO> bars = createBars(from, 10, ONE_MINUTE);

            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", from, to))
                    .thenReturn(bars);

            CoverageReport report = service.checkCoverage("BTCUSDT", "1m", from, to);

            assertThat(report.symbol()).isEqualTo("BTCUSDT");
            assertThat(report.timeframe()).isEqualTo("1m");
            assertThat(report.expectedBars()).isEqualTo(10);
            assertThat(report.actualBars()).isEqualTo(10);
            assertThat(report.coveragePct()).isEqualTo(100.0);
            assertThat(report.gaps()).isEmpty();
        }

        @Test
        @DisplayName("空数据应返回 0% 覆盖率")
        void shouldReturn0PercentForEmptyData() {
            long from = 1000L;
            long to = 1000L + 9 * ONE_MINUTE;

            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", from, to))
                    .thenReturn(Collections.emptyList());

            CoverageReport report = service.checkCoverage("BTCUSDT", "1m", from, to);

            assertThat(report.expectedBars()).isEqualTo(10);
            assertThat(report.actualBars()).isZero();
            assertThat(report.coveragePct()).isZero();
            assertThat(report.gaps()).hasSize(1);
        }

        @Test
        @DisplayName("部分数据应返回正确的覆盖率百分比")
        void shouldReturnCorrectPercentageForPartialData() {
            long from = 1000L;
            long to = 1000L + 9 * ONE_MINUTE; // 10 bars expected
            // 只有 5 根 K 线
            List<BarEventDO> bars = createBars(from, 5, ONE_MINUTE);

            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", from, to))
                    .thenReturn(bars);

            CoverageReport report = service.checkCoverage("BTCUSDT", "1m", from, to);

            assertThat(report.expectedBars()).isEqualTo(10);
            assertThat(report.actualBars()).isEqualTo(5);
            assertThat(report.coveragePct()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("5 分钟 K 线应使用正确的间隔计算期望数量")
        void shouldUseCorrectIntervalFor5mTimeframe() {
            long from = 1000L;
            long to = 1000L + 9 * FIVE_MINUTES; // 10 bars expected for 5m
            List<BarEventDO> bars = createBars(from, 10, FIVE_MINUTES);

            when(barEventMapper.selectByTimeRange("BTCUSDT", "5m", from, to))
                    .thenReturn(bars);

            CoverageReport report = service.checkCoverage("BTCUSDT", "5m", from, to);

            assertThat(report.expectedBars()).isEqualTo(10);
            assertThat(report.actualBars()).isEqualTo(10);
            assertThat(report.coveragePct()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("未知 timeframe 应抛出异常")
        void shouldThrowForUnknownTimeframe() {
            assertThatThrownBy(() ->
                    service.checkCoverage("BTCUSDT", "99x", 1000L, 2000L)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown timeframe code");
        }
    }

    @Nested
    @DisplayName("detectGaps - 间隙检测")
    class DetectGaps {

        @Test
        @DisplayName("无间隙的连续 K 线应返回空间隙列表")
        void shouldReturnNoGapsForContinuousBars() {
            List<BarEventDO> bars = createBars(1000L, 5, ONE_MINUTE);

            List<CoverageReport.TimeGap> gaps = service.detectGaps(
                    bars, 1000L, 1000L + 4 * ONE_MINUTE, ONE_MINUTE);

            assertThat(gaps).isEmpty();
        }

        @Test
        @DisplayName("空列表应返回整个范围作为间隙")
        void shouldReturnFullRangeAsGapForEmptyList() {
            List<CoverageReport.TimeGap> gaps = service.detectGaps(
                    Collections.emptyList(), 1000L, 5000L, ONE_MINUTE);

            assertThat(gaps).hasSize(1);
            assertThat(gaps.get(0).from()).isEqualTo(1000L);
            assertThat(gaps.get(0).to()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("应检测相邻 K 线之间的间隙")
        void shouldDetectGapBetweenAdjacentBars() {
            // 第一根在 1000，第二根在 1000 + 3min（缺了 2 根 1min K 线）
            List<BarEventDO> bars = List.of(
                    createBar(1000L),
                    createBar(1000L + 3 * ONE_MINUTE)
            );

            List<CoverageReport.TimeGap> gaps = service.detectGaps(
                    bars, 1000L, 1000L + 3 * ONE_MINUTE, ONE_MINUTE);

            assertThat(gaps).hasSize(1);
            assertThat(gaps.get(0).from()).isEqualTo(1000L);
            assertThat(gaps.get(0).to()).isEqualTo(1000L + 3 * ONE_MINUTE);
        }

        @Test
        @DisplayName("应检测范围起始到第一根 K 线之间的间隙")
        void shouldDetectGapFromRangeStart() {
            // 第一根 K 线距范围起始 5 分钟
            List<BarEventDO> bars = List.of(
                    createBar(1000L + 5 * ONE_MINUTE),
                    createBar(1000L + 6 * ONE_MINUTE)
            );

            List<CoverageReport.TimeGap> gaps = service.detectGaps(
                    bars, 1000L, 1000L + 6 * ONE_MINUTE, ONE_MINUTE);

            assertThat(gaps).hasSize(1);
            assertThat(gaps.get(0).from()).isEqualTo(1000L);
            assertThat(gaps.get(0).to()).isEqualTo(1000L + 5 * ONE_MINUTE);
        }

        @Test
        @DisplayName("应检测最后一根 K 线到范围结束之间的间隙")
        void shouldDetectGapToEnd() {
            // 最后一根 K 线距范围结束 5 分钟
            List<BarEventDO> bars = List.of(
                    createBar(1000L),
                    createBar(1000L + ONE_MINUTE)
            );

            List<CoverageReport.TimeGap> gaps = service.detectGaps(
                    bars, 1000L, 1000L + 6 * ONE_MINUTE, ONE_MINUTE);

            assertThat(gaps).hasSize(1);
            assertThat(gaps.get(0).from()).isEqualTo(1000L + ONE_MINUTE);
            assertThat(gaps.get(0).to()).isEqualTo(1000L + 6 * ONE_MINUTE);
        }

        @Test
        @DisplayName("应检测多个间隙")
        void shouldDetectMultipleGaps() {
            // 3 段数据，2 个间隙
            List<BarEventDO> bars = List.of(
                    createBar(1000L),
                    createBar(1000L + ONE_MINUTE),
                    // gap: 3 minutes missing
                    createBar(1000L + 5 * ONE_MINUTE),
                    createBar(1000L + 6 * ONE_MINUTE),
                    // gap: 4 minutes missing
                    createBar(1000L + 11 * ONE_MINUTE)
            );

            List<CoverageReport.TimeGap> gaps = service.detectGaps(
                    bars, 1000L, 1000L + 11 * ONE_MINUTE, ONE_MINUTE);

            assertThat(gaps).hasSize(2);
        }

        @Test
        @DisplayName("允许 1.5 倍容差，小间隙不检测")
        void shouldAllowToleranceForSmallGaps() {
            // 间隔刚好在 1.5 倍容差内
            long gap = (long) (ONE_MINUTE * 1.4);
            List<BarEventDO> bars = List.of(
                    createBar(1000L),
                    createBar(1000L + gap)
            );

            List<CoverageReport.TimeGap> gaps = service.detectGaps(
                    bars, 1000L, 1000L + gap, ONE_MINUTE);

            assertThat(gaps).isEmpty();
        }
    }

    @Nested
    @DisplayName("CoverageReport.TimeGap")
    class TimeGapTests {

        @Test
        @DisplayName("missingBars 应正确计算缺失 K 线数量")
        void shouldCalculateMissingBars() {
            CoverageReport.TimeGap gap = new CoverageReport.TimeGap(1000L, 4000L);

            // 3000ms / 60000ms = 0 (整数除法)
            assertThat(gap.missingBars(ONE_MINUTE)).isZero();

            // 3000ms / 1000ms = 3
            assertThat(gap.missingBars(1000L)).isEqualTo(3);
        }
    }

    // ---- 辅助方法 ----

    private List<BarEventDO> createBars(long start, int count, long interval) {
        List<BarEventDO> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bars.add(createBar(start + i * interval));
        }
        return bars;
    }

    private BarEventDO createBar(long openTime) {
        BarEventDO bar = new BarEventDO();
        bar.setOpenTime(openTime);
        bar.setSymbol("BTCUSDT");
        bar.setTimeframe("1m");
        bar.setExchange("binance");
        bar.setMarketType("perpetual");
        bar.setOpenPrice(BigDecimal.valueOf(100));
        bar.setHighPrice(BigDecimal.valueOf(110));
        bar.setLowPrice(BigDecimal.valueOf(90));
        bar.setClosePrice(BigDecimal.valueOf(105));
        bar.setVolume(BigDecimal.valueOf(50));
        bar.setQuoteVolume(BigDecimal.valueOf(5250));
        return bar;
    }
}
