package com.tj.crypto.factor.cache;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;

import java.util.List;
import java.util.Optional;

/**
 * Bar 数据缓存接口。
 * 按 Instrument + Timeframe 缓存最近的 BarEvent 数据，供因子计算使用。
 */
public interface BarCache {

    /**
     * 添加或更新 bar。未收盘 bar 只进入 forming 区，收盘 bar 按 openTime 幂等 upsert。
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
     * 获取 exchangeTimestamp 不晚于 asOfTimestamp 的最近 N 根已收盘 bar。
     */
    List<BarEvent> getBarsAsOf(Instrument instrument, Timeframe timeframe,
                               long asOfTimestamp, int count);

    /** 返回当前尚未收盘的 K 线快照；因子计算不会读取该值。 */
    Optional<BarEvent> getFormingBar(Instrument instrument, Timeframe timeframe);

    /**
     * 获取缓存的 bar 数量。
     */
    int size(Instrument instrument, Timeframe timeframe);
}
