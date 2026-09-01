package com.tj.crypto.storage.service;

import com.tj.crypto.storage.converter.SignalEventConverter;
import com.tj.crypto.storage.entity.SignalEventDO;
import com.tj.crypto.storage.mapper.SignalEventMapper;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalListener;
import com.tj.crypto.backtest.paper.PaperTradingEngine;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 信号持久化与 Paper 投递服务；两者共享一个同步数据库事务。
 */
@Slf4j
@Service
public class SignalPersistenceService implements SignalListener {

    private final SignalEventMapper signalEventMapper;
    private final PaperTradingEngine paperTradingEngine;

    public SignalPersistenceService(SignalEventMapper signalEventMapper,
                                    PaperTradingEngine paperTradingEngine) {
        this.signalEventMapper = signalEventMapper;
        this.paperTradingEngine = paperTradingEngine;
    }

    /**
     * 保留历史方法名；实现已改为同步事务，失败时由调用链回滚。
     */
    @Transactional
    public void persistSignalAsync(SignalEvent event) {
        deliver(event);
    }

    @Override
    @Transactional
    public void onSignal(SignalEvent signal) {
        deliver(signal);
    }

    private void deliver(SignalEvent signal) {
        SignalEventDO stored = SignalEventConverter.toDO(signal);
        SignalDeliveryTarget target = deliveryTarget(signal);
        stored.setDeliveryTarget(target.name());
        if (signalEventMapper.insert(stored) != 1 || stored.getId() == null) {
            throw new IllegalStateException("Signal command was not durably inserted");
        }
        if (target == SignalDeliveryTarget.PAPER) {
            paperTradingEngine.executeSignal(signal);
        }
        log.debug("Durably delivered signal: id={} target={} strategy={} type={} instrument={}",
                stored.getId(), target, signal.strategyName(), signal.type(),
                signal.instrument().symbol());
    }

    private SignalDeliveryTarget deliveryTarget(SignalEvent signal) {
        if (!signal.hasRuntimeReleaseLineage()) return SignalDeliveryTarget.PAPER;
        return signal.releaseStage() == StrategyLifecycleStage.SHADOW
                ? SignalDeliveryTarget.SHADOW : SignalDeliveryTarget.PAPER;
    }
}
