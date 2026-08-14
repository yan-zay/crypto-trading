package com.tj.crypto.trading.paper.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaperOrderReservationMapper {
    @Insert("""
            INSERT INTO paper_order_reservation
                (order_id, account_id, asset, reservation_type, original_amount,
                 remaining_amount, original_quantity, remaining_quantity)
            VALUES (#{orderId}, #{accountId}, #{asset}, #{reservationType}, #{originalAmount},
                    #{remainingAmount}, #{originalQuantity}, #{remainingQuantity})
            """)
    int insert(PaperOrderReservationDO reservation);

    @Select("SELECT * FROM paper_order_reservation WHERE order_id=#{orderId} FOR UPDATE")
    PaperOrderReservationDO selectForUpdate(@Param("orderId") String orderId);

    @Update("""
            UPDATE paper_order_reservation
            SET remaining_amount=#{remainingAmount}, remaining_quantity=#{remainingQuantity},
                update_time=CURRENT_TIMESTAMP
            WHERE order_id=#{orderId}
            """)
    int update(PaperOrderReservationDO reservation);

    @Delete("DELETE FROM paper_order_reservation WHERE order_id=#{orderId}")
    int delete(@Param("orderId") String orderId);
}
