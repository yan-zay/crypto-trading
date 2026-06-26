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
        SignalEventDO DO = new SignalEventDO();
        DO.setStrategyName(event.strategyName());
        DO.setExchange(event.instrument().exchange().getCode());
        DO.setSymbol(event.instrument().symbol());
        DO.setSignalType(event.type().getCode());
        DO.setConfidence(event.confidence());
        DO.setReason(event.reason());
        DO.setSignalTime(event.timestamp());

        // 序列化 factorSnapshot 为 JSON
        if (event.factorSnapshot() != null && !event.factorSnapshot().isEmpty()) {
            try {
                DO.setFactorSnapshot(objectMapper.writeValueAsString(event.factorSnapshot()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize factorSnapshot: {}", e.getMessage());
                DO.setFactorSnapshot("{}");
            }
        }

        return DO;
    }
}
