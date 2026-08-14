package com.tj.crypto.storage.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tj.crypto.mapper.BaseMapperX;
import com.tj.crypto.storage.entity.OmsOrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** OMS 订单快照 Mapper。 */
@Mapper
public interface OmsOrderMapper extends BaseMapperX<OmsOrderDO> {

    @Insert("""
            INSERT INTO oms_order (
                order_id, client_order_id, account_id, order_source, venue_order_id,
                external_status, correlation_id, leverage, margin_mode, state_version, last_event_at_ms,
                strategy_id, exchange, market_type, symbol,
                trade_side, requested_side, position_side, reduce_only, order_type,
                quantity, price, filled_quantity, avg_fill_price, status, reject_reason,
                created_at_ms, submitted_at_ms, filled_at_ms, cancelled_at_ms
            ) VALUES (
                #{o.orderId}, #{o.clientOrderId}, #{o.accountId}, #{o.orderSource}, #{o.venueOrderId},
                #{o.externalStatus}, #{o.correlationId}, #{o.leverage}, #{o.marginMode},
                #{o.stateVersion}, #{o.lastEventAtMs},
                #{o.strategyId}, #{o.exchange}, #{o.marketType}, #{o.symbol},
                #{o.tradeSide}, #{o.requestedSide}, #{o.positionSide}, #{o.reduceOnly}, #{o.orderType},
                #{o.quantity}, #{o.price}, #{o.filledQuantity}, #{o.avgFillPrice}, #{o.status}, #{o.rejectReason},
                #{o.createdAtMs}, #{o.submittedAtMs}, #{o.filledAtMs}, #{o.cancelledAtMs}
            ) ON DUPLICATE KEY UPDATE
                account_id=COALESCE(VALUES(account_id), account_id),
                order_source=VALUES(order_source), venue_order_id=COALESCE(VALUES(venue_order_id), venue_order_id),
                external_status=COALESCE(VALUES(external_status), external_status),
                correlation_id=COALESCE(VALUES(correlation_id), correlation_id),
                leverage=VALUES(leverage), margin_mode=VALUES(margin_mode),
                state_version=state_version+1, last_event_at_ms=VALUES(last_event_at_ms),
                filled_quantity=VALUES(filled_quantity), avg_fill_price=VALUES(avg_fill_price),
                status=VALUES(status), reject_reason=VALUES(reject_reason),
                submitted_at_ms=VALUES(submitted_at_ms), filled_at_ms=VALUES(filled_at_ms),
                cancelled_at_ms=VALUES(cancelled_at_ms), update_time=CURRENT_TIMESTAMP
            """)
    int upsert(@Param("o") OmsOrderDO order);

    @Select("SELECT * FROM oms_order WHERE order_id=#{orderId} FOR UPDATE")
    OmsOrderDO selectByIdForUpdate(@Param("orderId") String orderId);

    @Select("SELECT * FROM oms_order WHERE client_order_id=#{clientOrderId} LIMIT 1")
    OmsOrderDO selectByClientOrderId(@Param("clientOrderId") String clientOrderId);

    @Select("SELECT * FROM oms_order WHERE UPPER(exchange)=UPPER(#{exchange}) AND venue_order_id=#{venueOrderId} LIMIT 1")
    OmsOrderDO selectByVenueOrderId(@Param("exchange") String exchange,
                                    @Param("venueOrderId") String venueOrderId);

    @Update("""
            UPDATE oms_order SET venue_order_id=COALESCE(#{venueOrderId}, venue_order_id),
                external_status=#{externalStatus}, update_time=CURRENT_TIMESTAMP
            WHERE order_id=#{orderId}
            """)
    int attachVenue(@Param("orderId") String orderId,
                    @Param("venueOrderId") String venueOrderId,
                    @Param("externalStatus") String externalStatus);

    @Update("""
            UPDATE oms_order SET status='UNKNOWN', external_status=#{externalStatus},
                last_event_at_ms=#{eventTime}, state_version=state_version+1,
                update_time=CURRENT_TIMESTAMP
            WHERE order_id=#{orderId} AND status NOT IN ('FILLED','CANCELLED','REJECTED','EXPIRED')
            """)
    int markUnknown(@Param("orderId") String orderId,
                    @Param("externalStatus") String externalStatus,
                    @Param("eventTime") long eventTime);

    @Select("""
            SELECT * FROM oms_order
            WHERE account_id=#{accountId}
              AND status IN ('CREATED','SUBMITTED','ACKNOWLEDGED','PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN')
            ORDER BY created_at_ms
            """)
    List<OmsOrderDO> selectActiveByAccount(@Param("accountId") String accountId);

    @Select("""
            SELECT COUNT(*) FROM oms_order
            WHERE account_id=#{accountId}
              AND status IN ('CREATED','SUBMITTED','ACKNOWLEDGED','PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN')
            """)
    int countActiveByAccount(@Param("accountId") String accountId);

    /**
     * Bounded recovery scan for durable live orders whose venue outcome can still change.
     * This intentionally does not depend on a currently-running account session.
     */
    @Select("""
            SELECT * FROM oms_order
            WHERE order_source='LIVE'
              AND status IN ('CREATED','SUBMITTED','ACKNOWLEDGED','PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN')
            ORDER BY COALESCE(last_event_at_ms, created_at_ms), created_at_ms
            LIMIT #{limit}
            """)
    List<OmsOrderDO> selectActiveLiveOrders(@Param("limit") int limit);

    @Select("""
            SELECT order_id FROM oms_order
            WHERE account_id=#{accountId} AND exchange=#{exchange}
              AND market_type=#{marketType} AND symbol=#{symbol}
              AND status IN ('ACKNOWLEDGED','PARTIALLY_FILLED')
            ORDER BY created_at_ms
            """)
    List<String> selectMatchableOrderIds(@Param("accountId") String accountId,
                                         @Param("exchange") String exchange,
                                         @Param("marketType") String marketType,
                                         @Param("symbol") String symbol);

    @Select("SELECT * FROM oms_order WHERE account_id=#{accountId} ORDER BY created_at_ms")
    List<OmsOrderDO> selectByAccount(@Param("accountId") String accountId);

    @Select("""
            SELECT * FROM oms_order WHERE account_id=#{accountId}
            ORDER BY created_at_ms DESC LIMIT #{limit}
            """)
    List<OmsOrderDO> selectRecentByAccount(@Param("accountId") String accountId,
                                           @Param("limit") int limit);

    default List<OmsOrderDO> selectRecent(String exchange, String marketType,
                                           String symbol, String status, int limit) {
        LambdaQueryWrapper<OmsOrderDO> query = new LambdaQueryWrapper<OmsOrderDO>()
                .eq(exchange != null && !exchange.isBlank(), OmsOrderDO::getExchange, exchange)
                .eq(marketType != null && !marketType.isBlank(), OmsOrderDO::getMarketType, marketType)
                .eq(symbol != null && !symbol.isBlank(), OmsOrderDO::getSymbol, symbol)
                .eq(status != null && !status.isBlank(), OmsOrderDO::getStatus, status)
                .orderByDesc(OmsOrderDO::getCreatedAtMs)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        return selectList(query);
    }
}
