package com.tj.crypto.portfolio.runtime;

import com.tj.crypto.portfolio.analytics.PortfolioEquitySnapshot;
import com.tj.crypto.portfolio.analytics.PortfolioExposureSnapshot;

/** Point-in-time Paper facts for research analytics; never an execution permission. */
public record PaperPortfolioSnapshotReport(
        PortfolioExposureSnapshot exposureSnapshot,
        PortfolioEquitySnapshot equitySnapshot,
        boolean executionAuthorized) {

    public PaperPortfolioSnapshotReport {
        if (exposureSnapshot == null || equitySnapshot == null || executionAuthorized) {
            throw new IllegalArgumentException(
                    "complete Paper snapshots are required and cannot authorize execution");
        }
    }
}
