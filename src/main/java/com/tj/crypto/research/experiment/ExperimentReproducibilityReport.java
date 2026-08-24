package com.tj.crypto.research.experiment;

import java.util.List;

public record ExperimentReproducibilityReport(
        boolean reproducible,
        List<String> mismatches,
        String expectedRegistrationSha256,
        String observedProvenanceSha256
) {
    public ExperimentReproducibilityReport {
        mismatches = List.copyOf(mismatches);
    }
}
