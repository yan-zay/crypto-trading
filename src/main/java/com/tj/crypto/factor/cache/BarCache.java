package com.tj.crypto.factor.cache;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;

import java.util.List;

/**
 * Bar 数据缓存接口。
 * 按 Instrument + Timeframe 缓存最近的 BarEvent 数据，供因子计算使用。
 */
public interface BarCache {

    /**
     * 添加 bar 到缓存。
     */
    void addBar(BarEvent bar);

    /**
     * 获取最近 N 根 bar（按时间正序返回）。
     *
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @param count      需要的 bar 数量
     * @return bar 列表（可能少于 count）
     */
    List<BarEvent> getBars(Instrument instrument, Timeframe timeframe, int count);

    /**
     * 获取缓存的 bar 数量。
     */
    int size(Instrument instrument, Timeframe timeframe);
}
