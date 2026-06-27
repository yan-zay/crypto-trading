package com.tj.crypto.storage.service;

import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据覆盖率检查服务。
 * <p>
 * 从 MySQL 查询已有 K 线数据的时间范围，
 * 通过时间戳间隙检测缺失的 K 线，返回覆盖率报告。
 */
@Slf4j
@Component
@AllArgsConstructor
public class DataCoverageService {

    private final BarEventMapper barEventMapper;

    /**
     * 检查指定 symbol + timeframe 在 [from, to] 时间范围内的数据覆盖率。
     * <p>
     * 算法：
     * 1. 查询该范围内所有已存储的 K 线 openTime
     * 2. 计算期望 K 线数 = (to - from) / intervalMillis + 1
     * 3. 遍历已排序的 openTime，检测超过 1.5 倍间隔的间隙
     * 4. 覆盖率 = actualBars / expectedBars * 100
     *
     * @param symbol    交易对符号，如 "BTCUSDT"
     * @param timeframe 时间周期代码，如 "1m", "5m"
     * @param from      起始时间（毫秒）
     * @param to        结束时间（毫秒）
     * @return 覆盖率报告
     */
    public CoverageReport checkCoverage(String symbol, String timeframe, long from, long to) {
        Timeframe tf = Timeframe.fromCode(timeframe);
        long intervalMillis = tf.getMillis();

        long expectedBars = (to - from) / intervalMillis + 1;

        List<BarEventDO> bars = barEventMapper.selectByTimeRange(symbol, timeframe, from, to);
        long actualBars = bars.size();

        List<CoverageReport.TimeGap> gaps = detectGaps(bars, from, to, intervalMillis);

        double coveragePct = expectedBars > 0
                ? Math.round(actualBars * 10000.0 / expectedBars) / 100.0
                : 100.0;

        log.info("Coverage check: {} {} [{} -> {}] expected={}, actual={}, gaps={}, coverage={}%",
                symbol, timeframe, from, to, expectedBars, actualBars, gaps.size(), coveragePct);

        return new CoverageReport(symbol, timeframe, expectedBars, actualBars, coveragePct, gaps);
    }

    /**
     * 检测 K 线数据中的时间间隙。
     * <p>
     * 允许 1.5 倍时间周期的容差，避免因网络延迟导致的误报。
     *
     * @param bars           已按时间排序的 K 线列表
     * @param rangeFrom      查询范围起始时间
     * @param rangeTo        查询范围结束时间
     * @param intervalMillis 时间周期毫秒数
     * @return 缺失时间段列表
     */
    List<CoverageReport.TimeGap> detectGaps(List<BarEventDO> bars, long rangeFrom,
                                            long rangeTo, long intervalMillis) {
        List<CoverageReport.TimeGap> gaps = new ArrayList<>();

        if (bars.isEmpty()) {
            gaps.add(new CoverageReport.TimeGap(rangeFrom, rangeTo));
            return gaps;
        }

        // 检测范围起始到第一根 K 线之间的间隙
        long firstOpenTime = bars.get(0).getOpenTime();
        if (firstOpenTime - rangeFrom > intervalMillis * 1.5) {
            gaps.add(new CoverageReport.TimeGap(rangeFrom, firstOpenTime));
        }

        // 检测相邻 K 线之间的间隙
        for (int i = 1; i < bars.size(); i++) {
            long prevTime = bars.get(i - 1).getOpenTime();
            long currTime = bars.get(i).getOpenTime();
            long actualGap = currTime - prevTime;

            if (actualGap > intervalMillis * 1.5) {
                gaps.add(new CoverageReport.TimeGap(prevTime, currTime));
            }
        }

        // 检测最后一根 K 线到范围结束之间的间隙
        long lastOpenTime = bars.get(bars.size() - 1).getOpenTime();
        if (rangeTo - lastOpenTime > intervalMillis * 1.5) {
            gaps.add(new CoverageReport.TimeGap(lastOpenTime, rangeTo));
        }

        return gaps;
    }
}
