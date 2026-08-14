package com.tj.crypto.storage.service;

import com.tj.crypto.admin.dto.BacktestResultDTO;
import com.tj.crypto.storage.entity.BacktestRunDO;
import com.tj.crypto.storage.entity.BacktestTradeDO;
import com.tj.crypto.storage.mapper.BacktestRunMapper;
import com.tj.crypto.storage.mapper.BacktestTradeMapper;
import com.tj.crypto.storage.mapper.BacktestEquityPointMapper;
import com.tj.crypto.storage.mapper.BacktestSignalMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import com.tj.crypto.backtest.robustness.BacktestRobustnessAnalyzer;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestResultPersistenceServiceTest {

    @Mock BacktestRunMapper runMapper;
    @Mock BacktestTradeMapper tradeMapper;
    @Mock BacktestEquityPointMapper equityPointMapper;
    @Mock BacktestSignalMapper signalMapper;
    @Mock DataLineageService dataLineageService;

    @Test
    void mapsPersistedPercentageMetricsToFrontendFractions() {
        BacktestRunDO run = run();
        BacktestTradeDO trade = trade();
        when(runMapper.selectById("run-1")).thenReturn(run);
        when(tradeMapper.selectByRunId("run-1")).thenReturn(List.of(trade));
        when(equityPointMapper.selectByRunId("run-1")).thenReturn(List.of());
        when(signalMapper.selectByRunId("run-1")).thenReturn(List.of());
        BacktestResultPersistenceService service =
                new BacktestResultPersistenceService(runMapper, tradeMapper,
                        equityPointMapper, signalMapper, new ObjectMapper(),
                        new BacktestRobustnessAnalyzer(), dataLineageService);

        BacktestResultDTO dto = service.find("run-1");

        assertThat(dto.totalReturnPct()).isEqualByComparingTo("0.125");
        assertThat(dto.maxDrawdownPct()).isEqualByComparingTo("0.2");
        assertThat(dto.winRatePct()).isEqualByComparingTo("0.6");
        assertThat(dto.avgWinPct()).isEqualByComparingTo("0.002");
        assertThat(dto.avgLossPct()).isEqualByComparingTo("-0.001");
        assertThat(dto.trades().get(0).pnlPct()).isEqualByComparingTo("-0.025");
        assertThat(service.requiredForCompletion()).isTrue();
    }

    private BacktestRunDO run() {
        BacktestRunDO run = new BacktestRunDO();
        run.setRunId("run-1");
        run.setStrategyName("MacdCross");
        run.setExchange("okx");
        run.setMarketType("PERPETUAL");
        run.setSymbol("BTCUSDT");
        run.setTimeframe("1m");
        run.setStartTime(1_704_067_200_000L);
        run.setEndTime(1_704_153_600_000L);
        run.setInitialCapital(new BigDecimal("10000"));
        run.setFinalCapital(new BigDecimal("11250"));
        run.setTotalReturnPct(new BigDecimal("12.5"));
        run.setMaxDrawdownPct(new BigDecimal("20"));
        run.setWinRatePct(new BigDecimal("60"));
        run.setSharpeRatio(new BigDecimal("1.2"));
        run.setTotalTrades(1);
        run.setWinningTrades(0);
        run.setLosingTrades(1);
        run.setAvgWin(new BigDecimal("20"));
        run.setAvgLoss(new BigDecimal("10"));
        run.setProfitFactor(new BigDecimal("1.5"));
        return run;
    }

    private BacktestTradeDO trade() {
        BacktestTradeDO trade = new BacktestTradeDO();
        trade.setRunId("run-1");
        trade.setSequenceNo(0);
        trade.setSide("LONG");
        trade.setQuantity(new BigDecimal("2"));
        trade.setEntryPrice(new BigDecimal("100"));
        trade.setExitPrice(new BigDecimal("98"));
        trade.setEntryTime(1L);
        trade.setExitTime(2L);
        trade.setNetPnl(new BigDecimal("-5"));
        trade.setTotalFee(BigDecimal.ONE);
        return trade;
    }
}
