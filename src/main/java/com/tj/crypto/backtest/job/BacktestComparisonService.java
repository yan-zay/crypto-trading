package com.tj.crypto.backtest.job;

import com.tj.crypto.admin.dto.BacktestResultDTO;
import com.tj.crypto.storage.service.BacktestResultPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BacktestComparisonService {
    private final BacktestResultPersistenceService resultService;

    public List<BacktestComparisonRow> compare(List<String> runIds) {
        if (runIds == null || runIds.size() < 2 || runIds.size() > 20) {
            throw new IllegalArgumentException("Compare between 2 and 20 runs");
        }
        List<BacktestResultDTO> results = runIds.stream().distinct().map(id -> {
            BacktestResultDTO result = resultService.find(id);
            if (result == null) throw new IllegalArgumentException("Unknown backtest run: " + id);
            return result;
        }).sorted(Comparator.comparing(BacktestResultDTO::sharpeRatio).reversed()
                .thenComparing(Comparator.comparing(BacktestResultDTO::totalReturnPct).reversed()))
                .toList();
        List<BacktestComparisonRow> rows = new ArrayList<>();
        int rank = 1;
        for (BacktestResultDTO result : results) {
            rows.add(new BacktestComparisonRow(rank++, result.id(), result.strategyName(),
                    result.exchange(), result.marketType(), result.symbol(), result.timeframe(),
                    result.totalReturnPct(), result.annualizedReturnPct(), result.maxDrawdownPct(),
                    result.sharpeRatio(), result.sortinoRatio(), result.calmarRatio(),
                    result.winRatePct(), result.profitFactor(), result.totalFees(), result.totalTrades()));
        }
        return List.copyOf(rows);
    }
}
