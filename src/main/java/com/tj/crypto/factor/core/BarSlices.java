package com.tj.crypto.factor.core;

import com.tj.crypto.marketdata.model.BarEvent;

import java.util.List;
import java.util.TreeMap;

/** 构造确定性的 finalized K 线历史切片。 */
public final class BarSlices {

    private BarSlices() {
    }

    /** 按 openTime 去重、排序，并返回最近 count 根已收盘 K 线。 */
    public static List<BarEvent> latestFinalized(List<BarEvent> bars, int count) {
        if (bars == null || bars.isEmpty() || count <= 0) {
            return List.of();
        }
        TreeMap<Long, BarEvent> ordered = new TreeMap<>();
        for (BarEvent bar : bars) {
            if (bar != null && bar.closed()) {
                ordered.put(bar.metadata().exchangeTimestamp(), bar);
            }
        }
        List<BarEvent> all = List.copyOf(ordered.values());
        int from = Math.max(0, all.size() - count);
        return List.copyOf(all.subList(from, all.size()));
    }
}
