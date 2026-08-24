package com.tj.crypto.strategy.lifecycle;

import java.util.List;

/** Canonical promotion evidence projected from the immutable experiment registry. */
public record RegisteredResearchEvidence(
        boolean ready,
        byte[] canonicalContent,
        String evidenceSha256,
        String evidenceUri,
        List<String> blockers) {

    public RegisteredResearchEvidence {
        canonicalContent = canonicalContent == null ? new byte[0] : canonicalContent.clone();
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
        evidenceUri = evidenceUri == null ? "" : evidenceUri.trim();
        if (ready) {
            Hashes.requireSha256(evidenceSha256, "evidenceSha256");
            if (canonicalContent.length == 0 || evidenceUri.isEmpty() || !blockers.isEmpty()) {
                throw new IllegalArgumentException("ready research evidence must be complete and blocker-free");
            }
            if (!Hashes.sha256(canonicalContent).equals(evidenceSha256)) {
                throw new IllegalArgumentException("canonical research evidence does not match its SHA-256");
            }
        }
    }

    @Override
    public byte[] canonicalContent() {
        return canonicalContent.clone();
    }

    boolean matches(EvidenceSubmission submission) {
        return ready
                && evidenceSha256.equals(submission.evidenceSha256())
                && evidenceUri.equals(submission.evidenceUri())
                && java.security.MessageDigest.isEqual(canonicalContent,
                submission.content());
    }

    static RegisteredResearchEvidence denied(String reason) {
        return new RegisteredResearchEvidence(false, new byte[0], null, "", List.of(reason));
    }
}
