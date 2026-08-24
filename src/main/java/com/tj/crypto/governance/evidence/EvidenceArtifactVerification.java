package com.tj.crypto.governance.evidence;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Result from a trusted adapter that read, hashed, schema-validated and signature-verified an artifact. */
public record EvidenceArtifactVerification(boolean trusted,
                                           String verifierId,
                                           String verifiedIssuer,
                                           String signatureKeyId,
                                           String signatureAlgorithm,
                                           String recomputedArtifactSha256,
                                           long verifiedAtMs,
                                           Map<String, String> claims,
                                           List<String> blockers) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ALLOWED_SIGNATURE_ALGORITHMS = Set.of(
            "ED25519", "ES256", "PS256", "PS384", "PS512");

    public EvidenceArtifactVerification {
        verifierId = normalized(verifierId);
        verifiedIssuer = normalized(verifiedIssuer);
        signatureKeyId = normalized(signatureKeyId);
        signatureAlgorithm = normalized(signatureAlgorithm).toUpperCase(Locale.ROOT);
        recomputedArtifactSha256 = normalized(recomputedArtifactSha256).toLowerCase(Locale.ROOT);
        claims = Map.copyOf(claims == null ? Map.of() : claims);
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
        if (!recomputedArtifactSha256.isEmpty()
                && !SHA_256.matcher(recomputedArtifactSha256).matches()) {
            throw new IllegalArgumentException("recomputedArtifactSha256 must be a lowercase SHA-256");
        }
        if (trusted && (verifierId.isEmpty() || verifiedIssuer.isEmpty()
                || signatureKeyId.isEmpty() || verifiedAtMs <= 0
                || !ALLOWED_SIGNATURE_ALGORITHMS.contains(signatureAlgorithm)
                || recomputedArtifactSha256.isEmpty() || claims.isEmpty()
                || !blockers.isEmpty())) {
            throw new IllegalArgumentException(
                    "trusted artifact verification requires a verified issuer, signature, recomputed hash and claims");
        }
    }

    public static EvidenceArtifactVerification untrusted(String reason) {
        String blocker = normalized(reason);
        if (blocker.isEmpty()) blocker = "artifact is NOT_VERIFIED";
        return new EvidenceArtifactVerification(false, "UNCONFIGURED", "", "", "", "", 0,
                Map.of(), List.of(blocker));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
