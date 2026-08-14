package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Creates reduce-only market orders when isolated margin reaches maintenance margin. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperLiquidationService {
    private final PaperAccountLifecycleService lifecycleService;
    private final PaperPositionMapper positionMapper;
    private final PaperOrderService orderService;

    public void evaluate(PaperMarkPriceDO mark) {
        if (!"PERPETUAL".equals(mark.getMarketType())) return;
        PaperAccountDO account = lifecycleService.running();
        if (account == null) return;
        PaperPositionDO position = positionMapper.selectByAccount(account.getAccountId()).stream()
                .filter(p -> p.getExchange().equals(mark.getExchange())
                        && p.getMarketType().equals(mark.getMarketType())
                        && p.getSymbol().equals(mark.getSymbol())).findFirst().orElse(null);
        if (position == null) return;
        if (position.getInitialMargin().add(position.getUnrealizedPnl())
                .compareTo(position.getMaintenanceMargin()) > 0) return;
        TradeSide closeSide = "LONG".equals(position.getSide()) ? TradeSide.SELL : TradeSide.BUY;
        String key = UUID.nameUUIDFromBytes(("LIQUIDATION:" + position.getPositionId() + ":"
                + mark.getEventTimeMs()).getBytes(StandardCharsets.UTF_8)).toString();
        try {
            orderService.place(new PaperOrderRequest(account.getAccountId(), key,
                    "SYSTEM_LIQUIDATION", com.tj.crypto.common.domain.Exchange.valueOf(mark.getExchange()),
                    com.tj.crypto.common.domain.MarketType.PERPETUAL, mark.getSymbol(), closeSide,
                    OrderType.MARKET, position.getQuantity(), null, position.getLeverage(), true, key));
            log.warn("Paper liquidation order submitted: positionId={}", position.getPositionId());
        } catch (RuntimeException e) {
            log.error("Paper liquidation failed: positionId={}", position.getPositionId(), e);
        }
    }
}
