package com.tj.crypto.trading.paper.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaperBalanceMapper {
    @Insert("""
            INSERT INTO paper_balance
                (account_id, asset, total_balance, available_balance, locked_balance, version)
            VALUES (#{accountId}, #{asset}, #{totalBalance}, #{availableBalance}, #{lockedBalance}, 0)
            """)
    int insert(PaperBalanceDO balance);

    @Select("SELECT * FROM paper_balance WHERE account_id=#{accountId} ORDER BY asset")
    List<PaperBalanceDO> selectByAccount(@Param("accountId") String accountId);

    @Select("""
            SELECT * FROM paper_balance WHERE account_id=#{accountId} AND asset=#{asset} FOR UPDATE
            """)
    PaperBalanceDO selectForUpdate(@Param("accountId") String accountId,
                                   @Param("asset") String asset);

    @Update("""
            UPDATE paper_balance SET total_balance=#{totalBalance},
                available_balance=#{availableBalance}, locked_balance=#{lockedBalance},
                version=version+1, update_time=CURRENT_TIMESTAMP
            WHERE account_id=#{accountId} AND asset=#{asset}
            """)
    int update(PaperBalanceDO balance);
}
