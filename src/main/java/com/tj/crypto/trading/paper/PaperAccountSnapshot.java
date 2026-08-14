package com.tj.crypto.trading.paper;

import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperBalanceDO;
import com.tj.crypto.trading.paper.persistence.PaperEquitySnapshotDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;

import java.math.BigDecimal;
import java.util.List;

/** Read model returned by the paper account status API. */
public record PaperAccountSnapshot(
        PaperAccountDO account,
        List<PaperBalanceDO> balances,
        List<PaperPositionDO> positions,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal totalFees,
        BigDecimal netPnl,
        BigDecimal equity,
        int tradeCount,
        int activeOrderCount,
        PaperEquitySnapshotDO latestEquity
) {
    public boolean running() {
        return account != null && "RUNNING".equals(account.getStatus());
    }
}
