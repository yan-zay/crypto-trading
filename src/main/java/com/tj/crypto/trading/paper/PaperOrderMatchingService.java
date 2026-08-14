package com.tj.crypto.trading.paper;

import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Isolates each match in its own transaction so one bad order cannot block the book. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperOrderMatchingService {
    private final PaperAccountLifecycleService lifecycleService;
    private final OmsOrderMapper orderMapper;
    private final PaperOrderService orderService;

    public void match(PaperMarkPriceDO mark) {
        PaperAccountDO account = lifecycleService.running();
        if (account == null) return;
        for (String orderId : orderMapper.selectMatchableOrderIds(account.getAccountId(),
                mark.getExchange(), mark.getMarketType(), mark.getSymbol())) {
            try {
                orderService.matchOne(orderId, mark);
            } catch (RuntimeException e) {
                log.error("Paper order match failed: orderId={}", orderId, e);
            }
        }
    }
}
