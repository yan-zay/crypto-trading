package com.tj.crypto.storage.service;

import com.tj.crypto.storage.entity.OmsFillDO;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.entity.OmsOrderEventDO;
import com.tj.crypto.storage.mapper.OmsFillMapper;
import com.tj.crypto.storage.mapper.OmsOrderEventMapper;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** OMS 管理端只读查询。 */
@Service
@RequiredArgsConstructor
public class OmsQueryService {
    private final OmsOrderMapper orderMapper;
    private final OmsOrderEventMapper eventMapper;
    private final OmsFillMapper fillMapper;

    public List<OmsOrderDO> recentOrders(String exchange, String marketType,
                                         String symbol, String status, int limit) {
        return orderMapper.selectRecent(exchange, marketType, symbol, status, limit);
    }

    public OmsOrderDO getOrder(String orderId) {
        return orderMapper.selectById(orderId);
    }

    public List<OmsOrderEventDO> getEvents(String orderId) {
        return eventMapper.selectByOrderId(orderId);
    }

    public List<OmsFillDO> getFills(String orderId) {
        return fillMapper.selectByOrderId(orderId);
    }

    public List<OmsFillDO> recentFills(int limit) {
        return fillMapper.selectRecent(limit);
    }
}
