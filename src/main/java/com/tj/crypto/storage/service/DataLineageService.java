package com.tj.crypto.storage.service;

import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.entity.RawMessageDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import com.tj.crypto.storage.mapper.RawMessageMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 数据血缘服务。
 * 提供从 BarEvent 追溯到原始消息的能力，以及数据版本标识。
 */
@Slf4j
@Service
@AllArgsConstructor
public class DataLineageService {

    private final RawMessageMapper rawMessageMapper;
    private final BarEventMapper barEventMapper;

    /**
     * 根据 BarEvent ID 追溯到对应的原始消息。
     * <p>
     * 策略：通过 BarEvent 的 symbol + openTime 时间范围，
     * 查找时间窗口内该 symbol 的原始消息。
     *
     * @param barEventId BarEvent 的 ID
     * @return 对应时间窗口内的原始消息列表；找不到则返回空列表
     */
    public List<RawMessageDO> traceBack(Long barEventId) {
        BarEventDO barEvent = barEventMapper.selectById(barEventId);
        if (barEvent == null) {
            log.warn("BarEvent not found: id={}", barEventId);
            return List.of();
        }

        // 以 openTime 为中心，前后 1 分钟的窗口查找原始消息
        long openTime = barEvent.getOpenTime();
        long windowStart = openTime - 60_000;
        long windowEnd = openTime + 60_000;

        List<RawMessageDO> rawMessages = rawMessageMapper.selectBySourceAndTimeRange(
                barEvent.getExchange(), barEvent.getSymbol(), windowStart, windowEnd);

        log.debug("Traced back {} raw messages for BarEvent id={}", rawMessages.size(), barEventId);
        return rawMessages;
    }

    /**
     * 获取数据版本标识。
     * <p>
     * 基于指定 symbol 和时间范围内的原始消息 checksum 生成一个聚合版本哈希，
     * 用于判断该时间段的数据是否发生变化。
     *
     * @param symbol    交易对
     * @param timeframe 时间周期（如 "1m", "5m"）
     * @param from      起始时间（毫秒）
     * @param to        结束时间（毫秒）
     * @return 版本标识（SHA-256 前 16 位）；无数据则返回 "empty"
     */
    public String getDataVersion(String symbol, String timeframe, long from, long to) {
        List<BarEventDO> barEvents = barEventMapper.selectByTimeRange(symbol, timeframe, from, to);
        if (barEvents.isEmpty()) {
            return "empty";
        }

        // 拼接所有 BarEvent 的 ID 作为版本输入
        StringBuilder sb = new StringBuilder();
        for (BarEventDO event : barEvents) {
            sb.append(event.getId()).append(':');
        }
        sb.append(symbol).append(':').append(timeframe);

        String hash = computeSha256(sb.toString());
        return hash.substring(0, 16);
    }

    private static String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
