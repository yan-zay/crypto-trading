package com.tj.crypto.marketdata.connector.certification;

import java.util.List;

public record ConnectorCertificationDecision(Status status, List<String> blockers,
                                             String evidenceSha256) {
    public ConnectorCertificationDecision {
        if (status == null || blockers == null || evidenceSha256 == null) {
            throw new IllegalArgumentException("certification decision fields are required");
        }
        blockers = List.copyOf(blockers);
        if ((status == Status.PAPER_READY) != blockers.isEmpty()) {
            throw new IllegalArgumentException("PAPER_READY requires zero blockers");
        }
    }

    public boolean liveAuthorized() {
        return false;
    }

    public enum Status { RESEARCH_ONLY, PAPER_READY }
}
