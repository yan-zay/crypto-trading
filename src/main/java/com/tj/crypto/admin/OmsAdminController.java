package com.tj.crypto.admin;

import com.tj.crypto.storage.entity.OmsFillDO;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.service.OmsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** OMS 订单、生命周期事件和成交查询 API。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class OmsAdminController {

    private final OmsQueryService queryService;

    @GetMapping("/orders")
    public List<OmsOrderDO> orders(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String marketType,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return queryService.recentOrders(exchange, marketType, symbol, status, limit);
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<?> order(@PathVariable String orderId) {
        OmsOrderDO order = queryService.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "order", order,
                "events", queryService.getEvents(orderId),
                "fills", queryService.getFills(orderId)));
    }

    @GetMapping("/fills")
    public List<OmsFillDO> fills(@RequestParam(defaultValue = "100") int limit) {
        return queryService.recentFills(limit);
    }
}
