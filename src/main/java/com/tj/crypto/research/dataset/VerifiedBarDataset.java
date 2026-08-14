package com.tj.crypto.research.dataset;

import java.nio.file.Path;
import java.util.List;

/** Dataset bytes and parsed rows after every manifest invariant has passed. */
public record VerifiedBarDataset(
        DatasetManifest manifest,
        String manifestSha256,
        Path manifestPath,
        Path dataPath,
        List<CanonicalBarRow> rows
) {
    public VerifiedBarDataset {
        rows = List.copyOf(rows);
    }

    public boolean eligibleForRealDataResearchGate() {
        return manifest.isDeclaredImmutableRealMarketData();
    }
}
