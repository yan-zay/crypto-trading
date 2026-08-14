package com.tj.crypto.trading.venue.riskreservation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/** Atomic MySQL operations for durable live risk reservations. */
@Mapper
public interface LiveRiskReservationMapper {

    @Insert("""
            INSERT IGNORE INTO live_risk_budget_scope
                (account_id, exchange, scope_version, last_reserved_at_ms)
            VALUES (#{accountId}, #{exchange}, 0, 0)
            """)
    int ensureScope(@Param("accountId") String accountId,
                    @Param("exchange") String exchange);

    @Select("""
            SELECT scope_version
            FROM live_risk_budget_scope
            WHERE account_id=#{accountId} AND exchange=#{exchange}
            FOR UPDATE
            """)
    Long lockScope(@Param("accountId") String accountId,
                   @Param("exchange") String exchange);

    /** Locking reads observe the latest committed release and block a concurrent release. */
    @Select("""
            SELECT *
            FROM live_risk_reservation
            WHERE account_id=#{accountId} AND exchange=#{exchange}
              AND reservation_status IN ('ACTIVE','UNKNOWN','UNVALUED')
            ORDER BY order_id
            FOR UPDATE
            """)
    List<LiveRiskReservationDO> selectBlockingForUpdate(
            @Param("accountId") String accountId,
            @Param("exchange") String exchange);

    /**
     * Detects an active live OMS command which bypassed the reservation transaction. Such a
     * gap is not assigned zero risk; all further risk increases fail closed.
     */
    @Select("""
            SELECT o.order_id
            FROM oms_order o
            LEFT JOIN live_risk_reservation r ON r.order_id=o.order_id
            WHERE o.order_source='LIVE'
              AND o.account_id=#{accountId} AND UPPER(o.exchange)=#{exchange}
              AND o.order_id<>#{newOrderId}
              AND o.status IN ('PENDING','CREATED','SUBMITTED','ACKNOWLEDGED',
                               'PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN')
              AND r.order_id IS NULL
            ORDER BY o.order_id
            """)
    List<String> selectUnreservedActiveOrderIds(
            @Param("accountId") String accountId,
            @Param("exchange") String exchange,
            @Param("newOrderId") String newOrderId);

    @Insert("""
            INSERT INTO live_risk_reservation (
                order_id, account_id, exchange, market_type, symbol, risk_increasing,
                original_quantity, remaining_quantity, reference_price,
                original_notional, remaining_notional, reservation_status,
                last_order_status, snapshot_event_time_ms
            ) VALUES (
                #{r.orderId}, #{r.accountId}, #{r.exchange}, #{r.marketType}, #{r.symbol},
                #{r.riskIncreasing}, #{r.originalQuantity}, #{r.remainingQuantity},
                #{r.referencePrice}, #{r.originalNotional}, #{r.remainingNotional},
                #{r.reservationStatus}, #{r.lastOrderStatus}, #{r.snapshotEventTimeMs}
            )
            """)
    int insert(@Param("r") LiveRiskReservationDO reservation);

    @Update("""
            UPDATE live_risk_budget_scope
            SET scope_version=scope_version+1,
                last_reserved_at_ms=#{eventTimeMs},
                update_time=CURRENT_TIMESTAMP
            WHERE account_id=#{accountId} AND exchange=#{exchange}
            """)
    int touchScope(@Param("accountId") String accountId,
                   @Param("exchange") String exchange,
                   @Param("eventTimeMs") long eventTimeMs);

    /**
     * Remaining risk follows cumulative fill quantity. UNKNOWN deliberately retains the prior
     * amount; only a terminal OMS fact releases the reservation.
     */
    @Update("""
            UPDATE live_risk_reservation
            SET remaining_quantity = CASE
                    WHEN #{orderStatus} IN ('FILLED','CANCELLED','REJECTED','EXPIRED') THEN 0
                    WHEN #{orderStatus}='UNKNOWN' THEN remaining_quantity
                    ELSE GREATEST(original_quantity-COALESCE(#{filledQuantity},0),0)
                END,
                remaining_notional = CASE
                    WHEN #{orderStatus} IN ('FILLED','CANCELLED','REJECTED','EXPIRED') THEN 0
                    WHEN #{orderStatus}='UNKNOWN' THEN remaining_notional
                    WHEN risk_increasing THEN
                        GREATEST(original_quantity-COALESCE(#{filledQuantity},0),0)
                            * reference_price
                    ELSE 0
                END,
                reservation_status = CASE
                    WHEN #{orderStatus} IN ('FILLED','CANCELLED','REJECTED','EXPIRED')
                        THEN 'RELEASED'
                    WHEN #{orderStatus}='UNKNOWN' THEN 'UNKNOWN'
                    WHEN risk_increasing AND reference_price<=0 THEN 'UNVALUED'
                    ELSE 'ACTIVE'
                END,
                last_order_status=#{orderStatus},
                released_at_ms=CASE
                    WHEN #{orderStatus} IN ('FILLED','CANCELLED','REJECTED','EXPIRED')
                        THEN #{eventTimeMs}
                    ELSE NULL
                END,
                state_version=state_version+1,
                update_time=CURRENT_TIMESTAMP
            WHERE order_id=#{orderId}
            """)
    int syncFromOrder(@Param("orderId") String orderId,
                      @Param("orderStatus") String orderStatus,
                      @Param("filledQuantity") BigDecimal filledQuantity,
                      @Param("eventTimeMs") long eventTimeMs);

    @Update("""
            UPDATE live_risk_reservation
            SET reservation_status='UNKNOWN', last_order_status='UNKNOWN',
                state_version=state_version+1, update_time=CURRENT_TIMESTAMP
            WHERE order_id=#{orderId} AND reservation_status<>'RELEASED'
            """)
    int markUnknown(@Param("orderId") String orderId);

    @Select("SELECT * FROM live_risk_reservation WHERE order_id=#{orderId}")
    LiveRiskReservationDO selectByOrderId(@Param("orderId") String orderId);
}
