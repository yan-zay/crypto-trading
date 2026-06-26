package com.tj.crypto.central;

import com.tj.crypto.enums.Indicator;
import com.tj.crypto.enums.Symbol;

import java.util.Set;

/**
 * @Author zay
 * @Date 2025/9/17 16:50
 */
public abstract class BaseStrategy {

    protected volatile boolean running = true;

    // 需要监听的交易对列表
    public abstract Set<Symbol> getListenSymbol();

    public abstract Set<Indicator> getListenIndicator();

    // 轮询间隔(毫秒)
    public long getPollingInterval() {
        return 500;
    }

    // 事件驱动执行逻辑
    public abstract void onEvent(Symbol symbol, Indicator indicator);

    // 轮询执行逻辑
    public abstract void pollingExecute();

    public abstract void processData(Symbol symbol);
}
