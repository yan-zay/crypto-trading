package com.tj.crypto.storage.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.storage.entity.SignalEventDO;
import com.tj.crypto.strategy.core.SignalEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * SignalEvent ↔ SignalEventDO 转换器。
 */
@Slf4j
public final class SignalEventConverter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private SignalEventConverter() {}

    /**
     * SignalEvent → SignalEventDO。
     */
    public static SignalEventDO toDO(SignalEvent event) {
        SignalEventDO dobj = new SignalEventDO();
        dobj.setStrategyName(event.strategyName());
        dobj.setExchange(event.instrument().exchange().getCode());
        dobj.setMarketType(event.instrument().marketType().name());
        dobj.setSymbol(event.instrument().symbol());
        dobj.setSignalType(event.type().getCode());
        dobj.setConfidence(event.confidence());
        dobj.setReason(event.reason());
        dobj.setSignalTime(event.timestamp());

        // 序列化 factorSnapshot 为 JSON
        if (event.factorSnapshot() != null && !event.factorSnapshot().isEmpty()) {
            try {
                dobj.setFactorSnapshot(objectMapper.writeValueAsString(event.factorSnapshot()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize factorSnapshot: {}", e.getMessage());
                dobj.setFactorSnapshot("{}");
            }
        }

        return dobj;
    }
}
