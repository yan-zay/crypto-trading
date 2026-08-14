package com.tj.crypto.trading.paper.ledger;

import com.tj.crypto.trading.paper.persistence.LedgerEntryDO;
import com.tj.crypto.trading.paper.persistence.LedgerMapper;
import com.tj.crypto.trading.paper.persistence.LedgerTransactionDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates and persists balanced postings; the caller owns the surrounding transaction. */
@Service
@RequiredArgsConstructor
public class DoubleEntryLedgerService {
    private final LedgerMapper mapper;

    public String post(String accountId, String type, String referenceType, String referenceId,
                       long eventTime, String description, List<LedgerPosting> postings) {
        validateBalanced(postings);
        String transactionId = UUID.randomUUID().toString();
        LedgerTransactionDO transaction = new LedgerTransactionDO();
        transaction.setTransactionId(transactionId);
        transaction.setAccountId(accountId);
        transaction.setTransactionType(type);
        transaction.setReferenceType(referenceType);
        transaction.setReferenceId(referenceId);
        transaction.setEventTimeMs(eventTime);
        transaction.setDescription(description);
        mapper.insertTransaction(transaction);
        for (LedgerPosting posting : postings) {
            LedgerEntryDO entry = new LedgerEntryDO();
            entry.setEntryId(UUID.randomUUID().toString());
            entry.setTransactionId(transactionId);
            entry.setAccountId(accountId);
            entry.setLedgerAccount(posting.ledgerAccount());
            entry.setAsset(posting.asset());
            entry.setDebit(posting.debit());
            entry.setCredit(posting.credit());
            mapper.insertEntry(entry);
        }
        return transactionId;
    }

    public long countImbalances(String accountId) {
        return mapper.countImbalancedTransactions(accountId);
    }

    private void validateBalanced(List<LedgerPosting> postings) {
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction requires at least two postings");
        }
        Map<String, BigDecimal> debits = new HashMap<>();
        Map<String, BigDecimal> credits = new HashMap<>();
        for (LedgerPosting posting : postings) {
            debits.merge(posting.asset(), posting.debit(), BigDecimal::add);
            credits.merge(posting.asset(), posting.credit(), BigDecimal::add);
        }
        for (String asset : debits.keySet()) {
            if (debits.get(asset).compareTo(credits.getOrDefault(asset, BigDecimal.ZERO)) != 0) {
                throw new IllegalArgumentException("Unbalanced ledger postings for asset " + asset);
            }
        }
        for (String asset : credits.keySet()) {
            if (!debits.containsKey(asset)) {
                throw new IllegalArgumentException("Credit-only ledger postings for asset " + asset);
            }
        }
    }
}
