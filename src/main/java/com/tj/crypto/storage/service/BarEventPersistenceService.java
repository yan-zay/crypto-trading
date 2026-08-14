package com.tj.crypto.storage.service;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.storage.converter.BarEventConverter;
import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BarEvent 批量持久化服务。
 * <p>
 * 用于历史数据回填场景，提供批量保存和按时间范围加载功能。
 */
@Slf4j
@Service
@AllArgsConstructor
public class BarEventPersistenceService {

    private final BarEventMapper barEventMapper;

    /**
     * 批量保存 BarEvent 到数据库。
     *
     * @param events 要保存的 BarEvent 列表
     */
    public void saveAll(List<BarEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        List<BarEventDO> entities = events.stream()
                .map(BarEventConverter::toDO)
                .toList();

        barEventMapper.upsertBatch(entities);
        log.info("Persisted {} bar events to database", entities.size());
    }

    /**
     * 从数据库按时间范围加载 BarEvent。
     *
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @param from       起始时间（毫秒）
     * @param to         结束时间（毫秒）
     * @return 按时间正序排列的 BarEvent 列表
     */
    public List<BarEvent> loadByTimeRange(Instrument instrument, Timeframe timeframe,
                                           long from, long to) {
        List<BarEventDO> entities = barEventMapper.selectByTimeRange(
                instrument.exchange().getCode(), instrument.marketType().getCode(),
                instrument.symbol(), timeframe.getCode(), from, to);

        return entities.stream()
                .map(BarEventConverter::toEvent)
                .toList();
    }
}
