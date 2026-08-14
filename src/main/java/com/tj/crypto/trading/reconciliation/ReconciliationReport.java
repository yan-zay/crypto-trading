package com.tj.crypto.trading.reconciliation;

import java.util.List;

public record ReconciliationReport(
        String accountId,
        long checkedAtMs,
        int ordersChecked,
        int balancesChecked,
        int positionsChecked,
        int newOrUpdatedIncidents,
        long openIncidents,
        List<String> checks
) {}
