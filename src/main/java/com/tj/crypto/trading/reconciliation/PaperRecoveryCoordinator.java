package com.tj.crypto.trading.reconciliation;

import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.trading.paper.PaperAccountLifecycleService;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Restores the running paper session by discovering durable active orders and validating facts. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperRecoveryCoordinator {
    private final PaperAccountLifecycleService lifecycleService;
    private final OmsOrderMapper orderMapper;
    private final ReconciliationService reconciliationService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        PaperAccountDO account = lifecycleService.running();
        if (account == null) return;
        List<OmsOrderDO> active = orderMapper.selectActiveByAccount(account.getAccountId());
        ReconciliationReport report = reconciliationService.run(account.getAccountId());
        log.info("Recovered paper account {} with {} active orders; open reconciliation incidents={}",
                account.getAccountId(), active.size(), report.openIncidents());
    }

    @Scheduled(fixedDelayString = "${crypto.reconciliation.interval-ms:60000}")
    public void periodicReconciliation() {
        PaperAccountDO account = lifecycleService.running();
        if (account != null) reconciliationService.run(account.getAccountId());
    }
}
