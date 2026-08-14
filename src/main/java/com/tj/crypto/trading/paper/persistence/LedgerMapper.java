package com.tj.crypto.trading.paper.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LedgerMapper {
    @Insert("""
            INSERT INTO account_ledger_transaction
                (transaction_id, account_id, transaction_type, reference_type,
                 reference_id, event_time_ms, description)
            VALUES (#{transactionId}, #{accountId}, #{transactionType}, #{referenceType},
                    #{referenceId}, #{eventTimeMs}, #{description})
            """)
    int insertTransaction(LedgerTransactionDO transaction);

    @Insert("""
            INSERT INTO account_ledger_entry
                (entry_id, transaction_id, account_id, ledger_account, asset, debit, credit)
            VALUES (#{entryId}, #{transactionId}, #{accountId}, #{ledgerAccount},
                    #{asset}, #{debit}, #{credit})
            """)
    int insertEntry(LedgerEntryDO entry);

    @Select("""
            SELECT * FROM account_ledger_entry
            WHERE account_id=#{accountId}
            ORDER BY create_time DESC LIMIT #{limit}
            """)
    List<LedgerEntryDO> selectEntries(@Param("accountId") String accountId,
                                      @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM (
                SELECT transaction_id, asset
                FROM account_ledger_entry WHERE account_id=#{accountId}
                GROUP BY transaction_id, asset
                HAVING SUM(debit) <> SUM(credit)
            ) imbalance
            """)
    long countImbalancedTransactions(@Param("accountId") String accountId);
}
