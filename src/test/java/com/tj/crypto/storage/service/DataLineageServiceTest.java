package com.tj.crypto.storage.service;

import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.entity.RawMessageDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import com.tj.crypto.storage.mapper.RawMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DataLineageService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DataLineageServiceTest {

    @Mock
    private RawMessageMapper rawMessageMapper;

    @Mock
    private BarEventMapper barEventMapper;

    @InjectMocks
    private DataLineageService service;

    @Nested
    @DisplayName("traceBack")
    class TraceBack {

        @Test
        @DisplayName("应根据 BarEvent 时间窗口查找原始消息")
        void shouldFindRawMessagesInTimeWindow() {
            BarEventDO barEvent = createBarEvent(100L, "BTCUSDT", 1700000060000L);
            when(barEventMapper.selectById(100L)).thenReturn(barEvent);

            RawMessageDO raw1 = new RawMessageDO();
            raw1.setId(1L);
            RawMessageDO raw2 = new RawMessageDO();
            raw2.setId(2L);

            when(rawMessageMapper.selectBySourceAndTimeRange(
                    eq("binance"), eq("BTCUSDT"), anyLong(), anyLong()))
                    .thenReturn(List.of(raw1, raw2));

            List<RawMessageDO> result = service.traceBack(100L);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo(1L);
            assertThat(result.get(1).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("BarEvent 不存在时应返回空列表")
        void shouldReturnEmptyWhenBarEventNotFound() {
            when(barEventMapper.selectById(999L)).thenReturn(null);

            List<RawMessageDO> result = service.traceBack(999L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("无匹配原始消息时应返回空列表")
        void shouldReturnEmptyWhenNoRawMessages() {
            BarEventDO barEvent = createBarEvent(100L, "ETHUSDT", 1700000060000L);
            when(barEventMapper.selectById(100L)).thenReturn(barEvent);
            when(rawMessageMapper.selectBySourceAndTimeRange(
                    eq("binance"), eq("ETHUSDT"), anyLong(), anyLong()))
                    .thenReturn(List.of());

            List<RawMessageDO> result = service.traceBack(100L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getDataVersion")
    class GetDataVersion {

        @Test
        @DisplayName("有数据时应返回 16 位版本标识")
        void shouldReturn16CharVersionWhenDataExists() {
            BarEventDO bar1 = new BarEventDO();
            bar1.setId(1L);
            BarEventDO bar2 = new BarEventDO();
            bar2.setId(2L);

            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", 1000L, 2000L))
                    .thenReturn(List.of(bar1, bar2));

            String version = service.getDataVersion("BTCUSDT", "1m", 1000L, 2000L);

            assertThat(version).hasSize(16);
            assertThat(version).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("无数据时应返回 'empty'")
        void shouldReturnEmptyWhenNoData() {
            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", 1000L, 2000L))
                    .thenReturn(List.of());

            String version = service.getDataVersion("BTCUSDT", "1m", 1000L, 2000L);

            assertThat(version).isEqualTo("empty");
        }

        @Test
        @DisplayName("相同数据应返回相同版本标识")
        void shouldReturnSameVersionForSameData() {
            BarEventDO bar1 = new BarEventDO();
            bar1.setId(1L);
            BarEventDO bar2 = new BarEventDO();
            bar2.setId(2L);

            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", 1000L, 2000L))
                    .thenReturn(List.of(bar1, bar2));

            String version1 = service.getDataVersion("BTCUSDT", "1m", 1000L, 2000L);
            String version2 = service.getDataVersion("BTCUSDT", "1m", 1000L, 2000L);

            assertThat(version1).isEqualTo(version2);
        }

        @Test
        @DisplayName("不同数据应返回不同版本标识")
        void shouldReturnDifferentVersionForDifferentData() {
            BarEventDO bar1 = new BarEventDO();
            bar1.setId(1L);

            BarEventDO bar2 = new BarEventDO();
            bar2.setId(2L);

            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", 1000L, 2000L))
                    .thenReturn(List.of(bar1));
            when(barEventMapper.selectByTimeRange("BTCUSDT", "1m", 3000L, 4000L))
                    .thenReturn(List.of(bar2));

            String version1 = service.getDataVersion("BTCUSDT", "1m", 1000L, 2000L);
            String version2 = service.getDataVersion("BTCUSDT", "1m", 3000L, 4000L);

            assertThat(version1).isNotEqualTo(version2);
        }
    }

    private BarEventDO createBarEvent(Long id, String symbol, long openTime) {
        BarEventDO event = new BarEventDO();
        event.setId(id);
        event.setExchange("binance");
        event.setMarketType("FUTURES");
        event.setSymbol(symbol);
        event.setTimeframe("1m");
        event.setOpenTime(openTime);
        event.setOpenPrice(BigDecimal.valueOf(50000));
        event.setHighPrice(BigDecimal.valueOf(50100));
        event.setLowPrice(BigDecimal.valueOf(49900));
        event.setClosePrice(BigDecimal.valueOf(50050));
        event.setVolume(BigDecimal.valueOf(100));
        event.setQuoteVolume(BigDecimal.valueOf(5000000));
        return event;
    }
}
