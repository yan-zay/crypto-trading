package com.tj.crypto.research.experiment;

public class ExperimentReproducibilityException extends IllegalStateException {
    private final ExperimentReproducibilityReport report;

    public ExperimentReproducibilityException(ExperimentReproducibilityReport report) {
        super("Trial inputs do not reproduce the immutable registration: "
                + String.join(", ", report.mismatches()));
        this.report = report;
    }

    public ExperimentReproducibilityReport report() {
        return report;
    }
}
