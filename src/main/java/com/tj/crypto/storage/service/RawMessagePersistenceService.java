package com.tj.crypto.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tj.crypto.storage.entity.RawMessageDO;
import com.tj.crypto.storage.mapper.RawMessageMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 原始消息持久化服务。
 * 提供保存、去重和标记已处理功能。
 */
@Slf4j
@Service
@AllArgsConstructor
public class RawMessagePersistenceService {

    private final RawMessageMapper rawMessageMapper;

    /**
     * 保存原始消息（含去重检查）。
     *
     * @param source    数据来源（exchange 名称）
     * @param channel   频道
     * @param symbol    交易对
     * @param rawJson   原始 JSON 字符串
     * @return 保存后的实体 ID；如果重复则返回 null
     */
    public Long saveRawMessage(String source, String channel, String symbol, String rawJson) {
        String checksum = computeChecksum(rawJson);

        // 去重检查
        RawMessageDO existing = rawMessageMapper.selectByChecksum(checksum);
        if (existing != null) {
            log.debug("Duplicate raw message skipped, checksum={}", checksum);
            return null;
        }

        RawMessageDO entity = new RawMessageDO();
        entity.setSource(source);
        entity.setChannel(channel);
        entity.setSymbol(symbol);
        entity.setRawJson(rawJson);
        entity.setReceivedTime(System.currentTimeMillis());
        entity.setChecksum(checksum);
        entity.setProcessed(false);

        rawMessageMapper.insert(entity);
        log.debug("Saved raw message: source={}, channel={}, symbol={}, checksum={}",
                source, channel, symbol, checksum);
        return entity.getId();
    }

    /**
     * 标记消息为已处理。
     *
     * @param id 原始消息 ID
     */
    public void markProcessed(Long id) {
        rawMessageMapper.update(null, new LambdaUpdateWrapper<RawMessageDO>()
                .eq(RawMessageDO::getId, id)
                .set(RawMessageDO::getProcessed, true));
        log.debug("Marked raw message as processed: id={}", id);
    }

    /**
     * 计算 SHA-256 校验和。
     */
    static String computeChecksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
