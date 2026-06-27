package com.tj.crypto.storage.service;

import com.tj.crypto.storage.entity.RawMessageDO;
import com.tj.crypto.storage.mapper.RawMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RawMessagePersistenceService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RawMessagePersistenceServiceTest {

    static {
        // 初始化 MyBatis-Plus lambda 缓存，使 LambdaUpdateWrapper 能在纯单元测试中工作
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RawMessageDO.class);
    }

    @Mock
    private RawMessageMapper rawMessageMapper;

    @InjectMocks
    private RawMessagePersistenceService service;

    private static final String SOURCE = "coinglass";
    private static final String CHANNEL = "liquidationOrders";
    private static final String SYMBOL = "BTCUSDT";
    private static final String RAW_JSON = "{\"data\":[{\"baseAsset\":\"BTC\",\"volUsd\":50000}]}";

    @Nested
    @DisplayName("saveRawMessage")
    class SaveRawMessage {

        @Test
        @DisplayName("应保存新消息并返回 ID")
        void shouldSaveNewMessageAndReturnId() {
            when(rawMessageMapper.selectByChecksum(any())).thenReturn(null);
            when(rawMessageMapper.insert(any(RawMessageDO.class))).thenAnswer(invocation -> {
                RawMessageDO entity = invocation.getArgument(0);
                entity.setId(1L);
                return 1;
            });

            Long id = service.saveRawMessage(SOURCE, CHANNEL, SYMBOL, RAW_JSON);

            assertThat(id).isEqualTo(1L);

            ArgumentCaptor<RawMessageDO> captor = ArgumentCaptor.forClass(RawMessageDO.class);
            verify(rawMessageMapper).insert(captor.capture());

            RawMessageDO saved = captor.getValue();
            assertThat(saved.getSource()).isEqualTo(SOURCE);
            assertThat(saved.getChannel()).isEqualTo(CHANNEL);
            assertThat(saved.getSymbol()).isEqualTo(SYMBOL);
            assertThat(saved.getRawJson()).isEqualTo(RAW_JSON);
            assertThat(saved.getChecksum()).isNotBlank();
            assertThat(saved.getProcessed()).isFalse();
            assertThat(saved.getReceivedTime()).isGreaterThan(0);
        }

        @Test
        @DisplayName("重复消息应返回 null 且不插入")
        void shouldReturnNullForDuplicateMessage() {
            String checksum = RawMessagePersistenceService.computeChecksum(RAW_JSON);
            RawMessageDO existing = new RawMessageDO();
            existing.setId(99L);
            existing.setChecksum(checksum);

            when(rawMessageMapper.selectByChecksum(checksum)).thenReturn(existing);

            Long id = service.saveRawMessage(SOURCE, CHANNEL, SYMBOL, RAW_JSON);

            assertThat(id).isNull();
            verify(rawMessageMapper, never()).insert(any(RawMessageDO.class));
        }

        @Test
        @DisplayName("相同内容应产生相同 checksum")
        void shouldProduceSameChecksumForSameContent() {
            String checksum1 = RawMessagePersistenceService.computeChecksum(RAW_JSON);
            String checksum2 = RawMessagePersistenceService.computeChecksum(RAW_JSON);

            assertThat(checksum1).isEqualTo(checksum2);
            assertThat(checksum1).hasSize(64); // SHA-256 hex
        }

        @Test
        @DisplayName("不同内容应产生不同 checksum")
        void shouldProduceDifferentChecksumForDifferentContent() {
            String checksum1 = RawMessagePersistenceService.computeChecksum(RAW_JSON);
            String checksum2 = RawMessagePersistenceService.computeChecksum("{\"other\":\"data\"}");

            assertThat(checksum1).isNotEqualTo(checksum2);
        }
    }

    @Nested
    @DisplayName("markProcessed")
    class MarkProcessed {

        @Test
        @DisplayName("应将 processed 设为 true")
        void shouldMarkAsProcessed() {
            doReturn(1).when(rawMessageMapper).update(any(), any());

            service.markProcessed(42L);

            verify(rawMessageMapper).update(isNull(), any());
        }
    }
}
