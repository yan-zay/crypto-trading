package com.tj.crypto.governance.approval;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Result from a trusted IdP adapter after signature, nonce, audience and replay validation. */
public record MfaAssertionVerification(boolean trusted,
                                       String verifierId,
                                       String verifiedSubject,
                                       Set<String> verifiedRoles,
                                       String verifiedProvider,
                                       String verifiedChallengeId,
                                       String verifiedRequestId,
                                       ApprovalAction verifiedAction,
                                       String verifiedResourceSha256,
                                       String verifiedGovernanceSubjectSha256,
                                       String verifiedAudience,
                                       String recomputedArtifactSha256,
                                       String replayReservationId,
                                       String blocker) {
    public MfaAssertionVerification {
        verifierId = normalized(verifierId);
        verifiedSubject = verifiedSubject == null ? "" : verifiedSubject.trim();
        verifiedRoles = verifiedRoles == null ? Set.of() : verifiedRoles.stream()
                .map(role -> role == null ? "" : role.trim().toUpperCase(Locale.ROOT))
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        verifiedProvider = normalized(verifiedProvider);
        verifiedChallengeId = normalized(verifiedChallengeId);
        verifiedRequestId = normalized(verifiedRequestId);
        verifiedResourceSha256 = normalized(verifiedResourceSha256).toLowerCase(Locale.ROOT);
        verifiedGovernanceSubjectSha256 = normalized(verifiedGovernanceSubjectSha256)
                .toLowerCase(Locale.ROOT);
        verifiedAudience = normalized(verifiedAudience);
        recomputedArtifactSha256 = normalized(recomputedArtifactSha256).toLowerCase(Locale.ROOT);
        replayReservationId = normalized(replayReservationId);
        blocker = blocker == null ? "" : blocker.trim();
        if (trusted && (verifierId.isEmpty() || verifiedSubject.isEmpty()
                || verifiedRoles.isEmpty() || verifiedProvider.isEmpty()
                || verifiedChallengeId.isEmpty() || verifiedRequestId.isEmpty()
                || verifiedAction == null
                || !verifiedResourceSha256.matches("[0-9a-f]{64}")
                || !verifiedGovernanceSubjectSha256.matches("[0-9a-f]{64}")
                || verifiedAudience.isEmpty()
                || !recomputedArtifactSha256.matches("[0-9a-f]{64}")
                || replayReservationId.isEmpty() || !blocker.isEmpty())) {
            throw new IllegalArgumentException("trusted MFA verification is incomplete");
        }
    }

    public static MfaAssertionVerification untrusted(String reason) {
        return new MfaAssertionVerification(false, "UNCONFIGURED", "", Set.of(), "", "", "",
                null, "", "", "", "", "", reason);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
