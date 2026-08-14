package com.tj.crypto.trading.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.reliability.outbox.OutboxService;
import com.tj.crypto.observability.slo.SloName;
import com.tj.crypto.observability.slo.TradingSloService;
import com.tj.crypto.storage.entity.OmsOrderDO;
import com.tj.crypto.storage.mapper.OmsFillMapper;
import com.tj.crypto.storage.mapper.OmsOrderMapper;
import com.tj.crypto.trading.paper.ledger.DoubleEntryLedgerService;
import com.tj.crypto.trading.paper.persistence.PaperBalanceDO;
import com.tj.crypto.trading.paper.persistence.PaperBalanceMapper;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Detect-only reconciliation. Repairs require an explicit, audited operator decision. */
@Service
@RequiredArgsConstructor
public class ReconciliationService {
    private final OmsOrderMapper orderMapper;
    private final OmsFillMapper fillMapper;
    private final PaperBalanceMapper balanceMapper;
    private final PaperPositionMapper positionMapper;
    private final DoubleEntryLedgerService ledgerService;
    private final ReconciliationMapper reconciliationMapper;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TradingSloService sloService;

    @Transactional
    public ReconciliationReport run(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required for reconciliation");
        }
        long now = System.currentTimeMillis();
        int incidents = 0;
        List<OmsOrderDO> orders = orderMapper.selectByAccount(accountId);
        for (OmsOrderDO order : orders) {
            BigDecimal fills = fillMapper.sumQuantityByOrder(order.getOrderId());
            if (zero(order.getFilledQuantity()).compareTo(zero(fills)) != 0) {
                incidents += incident(accountId, "OMS_FILL_MISMATCH", "CRITICAL",
                        "OMS_ORDER", order.getOrderId(),
                        Map.of("filledQuantity", zero(order.getFilledQuantity())),
                        Map.of("sumFillQuantity", zero(fills)), now);
            }
            if (("FILLED".equals(order.getStatus())
                    && zero(order.getFilledQuantity()).compareTo(order.getQuantity()) != 0)
                    || ("CANCELLED".equals(order.getStatus())
                    && order.getCancelledAtMs() == null)) {
                incidents += incident(accountId, "OMS_TERMINAL_STATE_INVALID", "CRITICAL",
                        "OMS_ORDER", order.getOrderId(),
                        Map.of("statusInvariant", true),
                        Map.of("status", order.getStatus(), "filledQuantity", zero(order.getFilledQuantity())), now);
            }
            if (List.of("CREATED", "SUBMITTED", "CANCEL_REQUESTED").contains(order.getStatus())) {
                incidents += incident(accountId, "OMS_ORPHAN_TRANSIENT_STATE", "HIGH",
                        "OMS_ORDER", order.getOrderId(),
                        Map.of("recoverableStates", List.of("ACKNOWLEDGED", "PARTIALLY_FILLED")),
                        Map.of("status", order.getStatus()), now);
            }
        }

        List<PaperBalanceDO> balances = balanceMapper.selectByAccount(accountId);
        for (PaperBalanceDO balance : balances) {
            boolean invalid = balance.getTotalBalance().signum() < 0
                    || balance.getAvailableBalance().signum() < 0
                    || balance.getLockedBalance().signum() < 0
                    || balance.getAvailableBalance().add(balance.getLockedBalance())
                    .compareTo(balance.getTotalBalance()) > 0;
            if (invalid) {
                incidents += incident(accountId, "BALANCE_INVARIANT", "CRITICAL",
                        "PAPER_BALANCE", balance.getAsset(),
                        Map.of("totalGteAvailablePlusLocked", true, "nonNegative", true),
                        Map.of("total", balance.getTotalBalance(), "available", balance.getAvailableBalance(),
                                "locked", balance.getLockedBalance()), now);
            }
        }

        long ledgerImbalances = ledgerService.countImbalances(accountId);
        if (ledgerImbalances > 0) {
            incidents += incident(accountId, "LEDGER_IMBALANCE", "CRITICAL",
                    "PAPER_ACCOUNT", accountId, Map.of("imbalancedTransactions", 0),
                    Map.of("imbalancedTransactions", ledgerImbalances), now);
        }

        List<PaperPositionDO> positions = positionMapper.selectByAccount(accountId);
        for (PaperPositionDO position : positions) {
            if (position.getQuantity().signum() <= 0 || position.getEntryPrice().signum() <= 0
                    || position.getInitialMargin().signum() < 0) {
                incidents += incident(accountId, "POSITION_INVARIANT", "CRITICAL",
                        "PAPER_POSITION", position.getPositionId(),
                        Map.of("positiveQuantity", true, "positiveEntryPrice", true,
                                "nonNegativeMargin", true),
                        Map.of("quantity", position.getQuantity(), "entryPrice", position.getEntryPrice(),
                                "initialMargin", position.getInitialMargin()), now);
            }
            if ("SPOT".equals(position.getMarketType())) {
                PaperBalanceDO base = balances.stream()
                        .filter(b -> baseAsset(position.getSymbol()).equals(b.getAsset()))
                        .findFirst().orElse(null);
                if (base == null || base.getTotalBalance().compareTo(position.getQuantity()) < 0) {
                    incidents += incident(accountId, "SPOT_POSITION_BALANCE_MISMATCH", "CRITICAL",
                            "PAPER_POSITION", position.getPositionId(),
                            Map.of("minimumBaseBalance", position.getQuantity()),
                            Map.of("baseBalance", base == null ? BigDecimal.ZERO : base.getTotalBalance()), now);
                }
            }
        }

        outboxService.append("PAPER_ACCOUNT", accountId, "RECONCILIATION_COMPLETED",
                Map.of("accountId", accountId, "incidents", incidents, "checkedAt", now), null, now);
        ReconciliationReport report = new ReconciliationReport(accountId, now, orders.size(), balances.size(),
                positions.size(), incidents, reconciliationMapper.countOpen(),
                List.of("OMS_VS_FILLS", "TERMINAL_STATES", "BALANCE_INVARIANTS",
                        "DOUBLE_ENTRY", "POSITION_INVARIANTS", "SPOT_POSITION_VS_BALANCE"));
        sloService.record(SloName.RECONCILIATION_CONSISTENCY,
                report.newOrUpdatedIncidents() == 0 && report.openIncidents() == 0,
                Math.max(0, System.currentTimeMillis() - now));
        return report;
    }

    public List<ReconciliationIncidentDO> incidents(String accountId, String status, int limit) {
        return reconciliationMapper.selectRecent(accountId, status,
                Math.max(1, Math.min(limit, 1000)));
    }

    @Transactional
    public ReconciliationIncidentDO resolve(String incidentId, String operator, String resolution) {
        if (resolution == null || resolution.isBlank()) {
            throw new IllegalArgumentException("resolution is required");
        }
        ReconciliationIncidentDO incident = reconciliationMapper.selectForUpdate(incidentId);
        if (incident == null) throw new IllegalArgumentException("Unknown incident: " + incidentId);
        if (reconciliationMapper.resolve(incidentId, System.currentTimeMillis(), resolution, operator) != 1) {
            throw new IllegalStateException("Incident is not open");
        }
        return reconciliationMapper.selectForUpdate(incidentId);
    }

    private int incident(String accountId, String type, String severity,
                         String aggregateType, String aggregateId,
                         Object expected, Object actual, long now) {
        ReconciliationIncidentDO incident = new ReconciliationIncidentDO();
        incident.setIncidentId(UUID.randomUUID().toString());
        incident.setAccountId(accountId);
        incident.setIncidentType(type);
        incident.setSeverity(severity);
        incident.setAggregateType(aggregateType);
        incident.setAggregateId(aggregateId);
        incident.setExpectedJson(json(expected));
        incident.setActualJson(json(actual));
        incident.setDetectedAtMs(now);
        incident.setFingerprint(sha256(accountId + "|" + type + "|" + aggregateType + "|" + aggregateId));
        reconciliationMapper.upsertOpen(incident);
        return 1;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Reconciliation payload is not serializable", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String baseAsset(String symbol) {
        return symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
    }
}
