package com.tj.crypto.governance.evidence;

import java.util.Optional;

public interface ControlEvidenceRepository {
    void append(ControlEvidence evidence);

    Optional<ControlEvidence> findLatest(ControlKind kind, String scope, String environment,
                                         String subjectSha256, String policyVersion);
}
