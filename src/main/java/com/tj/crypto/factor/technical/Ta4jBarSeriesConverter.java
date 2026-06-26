package com.tj.crypto.factor.technical;

import com.tj.crypto.marketdata.model.BarEvent;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNum;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * TA4J BarSeries 转换工具。
 * 将内部 BarEvent 列表转换为 TA4J 的 BarSeries。
 *
 * 设计决策：
 * - 独立工具类，避免每个因子重复转换逻辑
 * - 使用 double 精度（TA4J 默认），对于技术指标计算足够
 * - 保留原始时间戳用于结果映射
 */
public final class Ta4jBarSeriesConverter {

    private Ta4jBarSeriesConverter() {}

    /**
     * 将 BarEvent 列表转换为 TA4J BarSeries。
     *
     * @param bars     BarEvent 列表（按时间正序）
     * @param name     Series 名称
     * @return TA4J BarSeries
     */
    public static BarSeries toBarSeries(List<BarEvent> bars, String name) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(name)
                .build();

        for (BarEvent bar : bars) {
            Duration duration = Duration.ofMillis(bar.timeframe().getMillis());
            ZonedDateTime endTime = ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(bar.metadata().exchangeTimestamp() + bar.timeframe().getMillis()),
                    ZoneOffset.UTC);

            series.addBar(duration, endTime,
                    DecimalNum.valueOf(bar.open()),
                    DecimalNum.valueOf(bar.high()),
                    DecimalNum.valueOf(bar.low()),
                    DecimalNum.valueOf(bar.close()),
                    DecimalNum.valueOf(bar.volume()));
        }

        return series;
    }
}
