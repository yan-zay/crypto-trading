package com.tj.crypto.research.parity;

import java.util.List;

public record DifferentialReplayReport(boolean withinTolerance, List<String> differences) {
    public DifferentialReplayReport {
        if (differences == null) throw new IllegalArgumentException("differences are required");
        differences = List.copyOf(differences);
        if (withinTolerance != differences.isEmpty()) {
            throw new IllegalArgumentException("parity status must match differences");
        }
    }
}
