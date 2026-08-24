package com.tj.crypto.governance.approval;

import java.util.List;

public record DualControlDecision(boolean granted,
                                  String requestId,
                                  ApprovalAction action,
                                  String resourceSha256,
                                  String governanceSubjectSha256,
                                  List<String> approvedBy,
                                  long consumedAtMs,
                                  List<String> blockers) {
    public DualControlDecision {
        approvedBy = List.copyOf(approvedBy);
        blockers = List.copyOf(blockers);
        if (granted && !blockers.isEmpty()) {
            throw new IllegalArgumentException("a granted decision cannot contain blockers");
        }
        if (granted && consumedAtMs <= 0) {
            throw new IllegalArgumentException("a granted decision must be atomically consumed");
        }
    }
}
