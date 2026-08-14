package com.tj.crypto.admin;

import com.tj.crypto.admin.application.BacktestApplicationService;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.admin.dto.FactorBacktestRequest;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.strategy.factor.FactorComparisonTarget;
import com.tj.crypto.strategy.factor.FactorMatchMode;
import com.tj.crypto.strategy.factor.FactorOperator;
import com.tj.crypto.strategy.factor.FactorPositionMode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Starts synchronous research backtests from persisted market data. */
@RestController
@RequestMapping("/api/admin/backtests")
@RequiredArgsConstructor
public class BacktestAdminController {
    private final BacktestApplicationService service;
    private final com.tj.crypto.admin.application.AuditService auditService;
    private final MarketUniverseProperties marketUniverse;
    private final FactorRegistry factorRegistry;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestParam String strategyName,
            @RequestParam(defaultValue = "BINANCE") Exchange exchange,
            @RequestParam(defaultValue = "PERPETUAL") MarketType marketType,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1h") String timeframe,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "100") int warmupBars,
            @RequestParam(defaultValue = "10000") BigDecimal initialBalance,
            HttpServletRequest request) {
        BacktestResult result = service.run(strategyName, exchange, marketType, symbol,
                timeframe, days, warmupBars, initialBalance);
        auditService.logOperation(operator(request), "RUN_BACKTEST",
                strategyName + ":" + exchange + ":" + marketType + ":" + symbol + ":" + timeframe);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategyName", result.strategyName());
        body.put("finalBalance", result.finalBalance());
        body.put("totalReturnPct", result.performanceReport().totalReturn());
        body.put("maxDrawdownPct", result.performanceReport().maxDrawdown());
        body.put("totalTrades", result.trades().size());
        body.put("persisted", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/factor-run")
    public ResponseEntity<Map<String, Object>> runFactorStrategy(
            @RequestBody FactorBacktestRequest request,
            HttpServletRequest servletRequest) {
        BacktestResult result = service.runFactorStrategy(
                request.exchange(), request.marketType(), request.symbol(), request.timeframe(),
                request.days(), request.warmupBars(), request.initialBalance(),
                request.autoBackfill(), request.strategy());
        auditService.logOperation(operator(servletRequest), "RUN_FACTOR_BACKTEST",
                request.strategy().name() + ":" + request.exchange() + ":"
                        + request.marketType() + ":" + request.symbol() + ":" + request.timeframe());
        return ResponseEntity.ok(summary(result));
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "exchanges", marketUniverse.getExchanges(),
                "marketTypes", marketUniverse.getMarketTypes(),
                "symbols", marketUniverse.getSymbols(),
                "timeframes", java.util.Arrays.stream(Timeframe.values())
                        .map(Timeframe::getCode).toList(),
                "factors", factorRegistry.getRegisteredFactors().stream()
                        .filter(factorRegistry::supportsBarHistory).sorted().toList(),
                "operators", FactorOperator.values(),
                "comparisonTargets", FactorComparisonTarget.values(),
                "matchModes", FactorMatchMode.values(),
                "positionModes", FactorPositionMode.values());
    }

    private Map<String, Object> summary(BacktestResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategyName", result.strategyName());
        body.put("finalBalance", result.finalBalance());
        body.put("totalReturnPct", result.performanceReport().totalReturn());
        body.put("maxDrawdownPct", result.performanceReport().maxDrawdown());
        body.put("totalTrades", result.trades().size());
        body.put("signalCount", result.signals().size());
        body.put("persisted", true);
        return body;
    }

    private String operator(HttpServletRequest request) {
        Object user = request.getAttribute("currentUser");
        return user instanceof com.tj.crypto.admin.domain.UserDO current
                ? current.getUsername() : "system";
    }
}
