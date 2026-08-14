package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.trading.paper.PaperAccountLifecycleService;
import com.tj.crypto.trading.paper.PaperAccountSnapshot;
import com.tj.crypto.trading.paper.PaperMarkRequest;
import com.tj.crypto.trading.paper.PaperMarketDataService;
import com.tj.crypto.trading.paper.PaperFundingService;
import com.tj.crypto.trading.paper.PaperOrderMatchingService;
import com.tj.crypto.trading.paper.PaperOrderRequest;
import com.tj.crypto.trading.paper.PaperOrderService;
import com.tj.crypto.trading.paper.PaperTradingQueryService;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent paper brokerage, account, order and post-trade endpoints. */
@RestController
@RequestMapping("/api/admin/paper-trading")
@RequiredArgsConstructor
public class PaperTradingAdminController {
    private final PaperAccountLifecycleService lifecycleService;
    private final PaperTradingQueryService queryService;
    private final PaperMarketDataService marketDataService;
    private final PaperOrderMatchingService matchingService;
    private final PaperOrderService orderService;
    private final PaperFundingService fundingService;
    private final AuditService auditService;

    @PostMapping("/start")
    @Transactional
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(defaultValue = "10000") BigDecimal initialBalance,
            @RequestParam(required = false) String accountName,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        PaperAccountDO account = lifecycleService.start(initialBalance, accountName, correlationId);
        auditService.logOperation(operator(request), "START_PAPER_TRADING",
                "accountId=" + account.getAccountId() + ",initialBalance=" + initialBalance);
        return ResponseEntity.ok(statusBody(account.getAccountId()));
    }

    @PostMapping("/stop")
    @Transactional
    public ResponseEntity<Map<String, Object>> stop(
            @RequestParam(required = false) String accountId,
            HttpServletRequest request) {
        PaperAccountDO account = queryService.resolveAccount(accountId);
        if (account == null) throw new IllegalStateException("No paper account exists");
        lifecycleService.stop(account.getAccountId(), correlationId(request));
        auditService.logOperation(operator(request), "STOP_PAPER_TRADING",
                "accountId=" + account.getAccountId());
        return ResponseEntity.ok(statusBody(account.getAccountId()));
    }

    @PostMapping("/accounts/{accountId}/resume")
    @Transactional
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String accountId,
                                                       HttpServletRequest request) {
        lifecycleService.resume(accountId, correlationId(request));
        auditService.logOperation(operator(request), "RESUME_PAPER_TRADING", "accountId=" + accountId);
        return ResponseEntity.ok(statusBody(accountId));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(statusBody(accountId));
    }

    @GetMapping("/accounts")
    public Object accounts(@RequestParam(defaultValue = "50") int limit) {
        return queryService.accounts(limit);
    }

    @PostMapping("/market-price")
    @Transactional
    public PaperMarkPriceDO marketPrice(@RequestBody PaperMarkRequest request,
                                        HttpServletRequest servletRequest) {
        PaperMarkPriceDO mark = marketDataService.update(request, "ADMIN_MANUAL");
        matchingService.match(mark);
        auditService.logOperation(operator(servletRequest), "SET_PAPER_MARK",
                mark.getExchange() + ":" + mark.getMarketType() + ":" + mark.getSymbol()
                        + "=" + mark.getPrice());
        return mark;
    }

    @PostMapping("/orders")
    @Transactional
    public Order placeOrder(@RequestBody PaperOrderRequest request,
                            HttpServletRequest servletRequest) {
        PaperOrderRequest command = withCorrelation(request, correlationId(servletRequest));
        Order order = orderService.place(command);
        auditService.logOperation(operator(servletRequest), "PLACE_PAPER_ORDER",
                "orderId=" + order.orderId() + ",status=" + order.status());
        return order;
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Transactional
    public Order cancelOrder(@PathVariable String orderId,
                             @RequestParam(required = false) String accountId,
                             HttpServletRequest request) {
        Order order = orderService.cancel(orderId, accountId, correlationId(request));
        auditService.logOperation(operator(request), "CANCEL_PAPER_ORDER", "orderId=" + orderId);
        return order;
    }

    @GetMapping("/orders/active")
    public Object activeOrders(@RequestParam(required = false) String accountId) {
        return queryService.activeOrders(accountId);
    }

    @GetMapping("/orders")
    public Object orders(@RequestParam(required = false) String accountId,
                         @RequestParam(defaultValue = "500") int limit) {
        return queryService.orders(accountId, limit);
    }

    @GetMapping("/fills")
    public Object fills(@RequestParam(required = false) String accountId,
                        @RequestParam(defaultValue = "500") int limit) {
        return queryService.fills(accountId, limit);
    }

    @GetMapping("/trades")
    public Object trades(@RequestParam(required = false) String accountId,
                         @RequestParam(defaultValue = "500") int limit) {
        return queryService.trades(accountId, limit);
    }

    @GetMapping("/equity")
    public Object equity(@RequestParam(required = false) String accountId,
                         @RequestParam(defaultValue = "5000") int limit) {
        return queryService.equity(accountId, limit);
    }

    @GetMapping("/ledger")
    public Object ledger(@RequestParam(required = false) String accountId,
                         @RequestParam(defaultValue = "500") int limit) {
        return queryService.ledger(accountId, limit);
    }

    @GetMapping("/attribution")
    public Object attribution(@RequestParam(required = false) String accountId) {
        return queryService.attribution(accountId);
    }

    @GetMapping("/execution-quality")
    public Object executionQuality(@RequestParam(required = false) String accountId) {
        return queryService.executionQuality(accountId);
    }

    @GetMapping("/marks")
    public Object marks() {
        return queryService.marks();
    }

    @PostMapping("/funding")
    @Transactional
    public Object applyFunding(@RequestParam String accountId,
                               @RequestParam com.tj.crypto.common.domain.Exchange exchange,
                               @RequestParam String symbol,
                               @RequestParam BigDecimal fundingRate,
                               @RequestParam String eventId,
                               @RequestParam(defaultValue = "0") long eventTime,
                               HttpServletRequest request) {
        long timestamp = eventTime <= 0 ? System.currentTimeMillis() : eventTime;
        Object settlement = fundingService.apply(accountId, exchange, symbol, fundingRate,
                eventId, timestamp);
        auditService.logOperation(operator(request), "APPLY_PAPER_FUNDING",
                "accountId=" + accountId + ",eventId=" + eventId);
        return settlement;
    }

    private Map<String, Object> statusBody(String accountId) {
        PaperAccountSnapshot snapshot = queryService.snapshot(accountId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", snapshot.running());
        body.put("account", snapshot.account());
        body.put("accountId", snapshot.account() == null ? null : snapshot.account().getAccountId());
        body.put("balance", snapshot.latestEquity() == null ? null : snapshot.latestEquity().getBalance());
        body.put("initialBalance", snapshot.account() == null ? null : snapshot.account().getInitialBalance());
        body.put("balances", snapshot.balances());
        body.put("positions", snapshot.positions());
        body.put("tradeCount", snapshot.tradeCount());
        body.put("activeOrderCount", snapshot.activeOrderCount());
        body.put("feesPaid", snapshot.totalFees());
        body.put("realizedPnl", snapshot.realizedPnl());
        body.put("unrealizedPnl", snapshot.unrealizedPnl());
        body.put("netPnl", snapshot.netPnl());
        body.put("equity", snapshot.equity());
        return body;
    }

    private PaperOrderRequest withCorrelation(PaperOrderRequest source, String correlationId) {
        return new PaperOrderRequest(source.accountId(), source.clientOrderId(), source.strategyId(),
                source.exchange(), source.marketType(), source.symbol(), source.side(),
                source.orderType(), source.quantity(), source.limitPrice(), source.leverage(),
                source.reduceOnly(), source.correlationId() == null ? correlationId : source.correlationId());
    }

    private String correlationId(HttpServletRequest request) {
        Object audited = request.getAttribute(
                com.tj.crypto.admin.api.AdminAuditFilter.CORRELATION_ID_ATTRIBUTE);
        if (audited instanceof String value && !value.isBlank()) return value;
        String existing = request.getHeader("X-Correlation-Id");
        return existing == null || existing.isBlank()
                ? java.util.UUID.randomUUID().toString() : existing;
    }

    private String operator(HttpServletRequest request) {
        Object user = request.getAttribute("currentUser");
        return user instanceof com.tj.crypto.admin.domain.UserDO current
                ? current.getUsername() : "system";
    }
}
