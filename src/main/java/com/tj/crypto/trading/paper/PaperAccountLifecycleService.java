package com.tj.crypto.trading.paper;

import com.tj.crypto.reliability.outbox.OutboxService;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.trading.paper.ledger.DoubleEntryLedgerService;
import com.tj.crypto.trading.paper.ledger.LedgerAccount;
import com.tj.crypto.trading.paper.ledger.LedgerPosting;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperAccountMapper;
import com.tj.crypto.trading.paper.persistence.PaperBalanceDO;
import com.tj.crypto.trading.paper.persistence.PaperBalanceMapper;
import com.tj.crypto.trading.paper.persistence.PaperEquitySnapshotDO;
import com.tj.crypto.trading.paper.persistence.PaperEquitySnapshotMapper;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent lifecycle; a running account survives process restarts unchanged. */
@Service
@RequiredArgsConstructor
public class PaperAccountLifecycleService {
    private final PaperAccountMapper accountMapper;
    private final PaperBalanceMapper balanceMapper;
    private final PaperPositionMapper positionMapper;
    private final PaperEquitySnapshotMapper equityMapper;
    private final OmsOrderMapper orderMapper;
    private final DoubleEntryLedgerService ledgerService;
    private final OutboxService outboxService;

    @Transactional
    public synchronized PaperAccountDO start(BigDecimal initialBalance, String accountName,
                                             String correlationId) {
        if (initialBalance == null || initialBalance.signum() <= 0) {
            throw new IllegalArgumentException("Initial paper balance must be positive");
        }
        if (accountMapper.selectRunning() != null) {
            throw new IllegalStateException("A paper account is already running");
        }
        long now = System.currentTimeMillis();
        PaperAccountDO account = new PaperAccountDO();
        account.setAccountId(UUID.randomUUID().toString());
        account.setAccountName(accountName == null || accountName.isBlank()
                ? "Paper " + now : accountName.trim());
        account.setStatus("RUNNING");
        account.setBaseCurrency("USDT");
        account.setInitialBalance(initialBalance);
        account.setStartedAtMs(now);
        account.setVersion(0L);
        accountMapper.insert(account);

        PaperBalanceDO balance = new PaperBalanceDO();
        balance.setAccountId(account.getAccountId());
        balance.setAsset("USDT");
        balance.setTotalBalance(initialBalance);
        balance.setAvailableBalance(initialBalance);
        balance.setLockedBalance(BigDecimal.ZERO);
        balanceMapper.insert(balance);

        ledgerService.post(account.getAccountId(), "INITIAL_DEPOSIT", "PAPER_ACCOUNT",
                account.getAccountId(), now, "Initial paper capital", List.of(
                        LedgerPosting.debit(LedgerAccount.CASH_AVAILABLE, "USDT", initialBalance),
                        LedgerPosting.credit(LedgerAccount.EXTERNAL_CAPITAL, "USDT", initialBalance)));
        snapshotInitialEquity(account, initialBalance, now);
        outboxService.append("PAPER_ACCOUNT", account.getAccountId(), "PAPER_ACCOUNT_STARTED",
                Map.of("accountId", account.getAccountId(), "initialBalance", initialBalance),
                correlationId, now);
        return account;
    }

    @Transactional
    public synchronized PaperAccountDO stop(String accountId, String correlationId) {
        PaperAccountDO account = lockRunning(accountId);
        if (orderMapper.countActiveByAccount(account.getAccountId()) > 0) {
            throw new IllegalStateException("Cancel all active orders before stopping paper trading");
        }
        if (!positionMapper.selectByAccount(account.getAccountId()).isEmpty()) {
            throw new IllegalStateException("Close all positions before stopping paper trading");
        }
        long now = System.currentTimeMillis();
        if (accountMapper.stop(account.getAccountId(), now) != 1) {
            throw new IllegalStateException("Paper account was concurrently changed");
        }
        outboxService.append("PAPER_ACCOUNT", account.getAccountId(), "PAPER_ACCOUNT_STOPPED",
                Map.of("accountId", account.getAccountId()), correlationId, now);
        return accountMapper.selectById(account.getAccountId());
    }

    @Transactional
    public synchronized PaperAccountDO resume(String accountId, String correlationId) {
        if (accountMapper.selectRunning() != null) {
            throw new IllegalStateException("A paper account is already running");
        }
        PaperAccountDO account = accountMapper.selectForUpdate(accountId);
        if (account == null) throw new IllegalArgumentException("Unknown paper account: " + accountId);
        if (accountMapper.resume(accountId) != 1) {
            throw new IllegalStateException("Only a stopped paper account can be resumed");
        }
        long now = System.currentTimeMillis();
        outboxService.append("PAPER_ACCOUNT", accountId, "PAPER_ACCOUNT_RESUMED",
                Map.of("accountId", accountId), correlationId, now);
        return accountMapper.selectById(accountId);
    }

    @Transactional(readOnly = true)
    public PaperAccountDO running() {
        return accountMapper.selectRunning();
    }

    public PaperAccountDO lockRunning(String accountId) {
        String resolved = accountId;
        if (resolved == null || resolved.isBlank()) {
            PaperAccountDO running = accountMapper.selectRunning();
            resolved = running == null ? null : running.getAccountId();
        }
        PaperAccountDO account = resolved == null ? null : accountMapper.selectForUpdate(resolved);
        if (account == null || !"RUNNING".equals(account.getStatus())) {
            throw new IllegalStateException("No running paper account");
        }
        return account;
    }

    private void snapshotInitialEquity(PaperAccountDO account, BigDecimal balance, long now) {
        PaperEquitySnapshotDO snapshot = new PaperEquitySnapshotDO();
        snapshot.setSnapshotId(UUID.randomUUID().toString());
        snapshot.setAccountId(account.getAccountId());
        snapshot.setEventTimeMs(now);
        snapshot.setBalance(balance);
        snapshot.setAvailableBalance(balance);
        snapshot.setLockedMargin(BigDecimal.ZERO);
        snapshot.setUnrealizedPnl(BigDecimal.ZERO);
        snapshot.setEquity(balance);
        equityMapper.upsert(snapshot);
    }
}
