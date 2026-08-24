package com.tj.crypto.autonomy.readiness;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Caller-supplied pointer only. It deliberately contains no trusted status, outcome or boolean.
 */
public record ExternalEvidenceReference(
        String evidenceId,
        LongTermControl control,
        URI artifactUri,
        String declaredContentSha256) {

    public ExternalEvidenceReference {
        evidenceId = ReadinessValues.required(evidenceId, "evidenceId", 128);
        control = Objects.requireNonNull(control, "control");
        artifactUri = Objects.requireNonNull(artifactUri, "artifactUri");
        declaredContentSha256 = ReadinessValues.hash(
                declaredContentSha256, "declaredContentSha256");
        boolean safe = "evidence".equalsIgnoreCase(artifactUri.getScheme())
                && artifactUri.getHost() != null && !artifactUri.getHost().isBlank()
                && artifactUri.getRawPath() != null && !artifactUri.getRawPath().isBlank()
                && artifactUri.getRawUserInfo() == null && artifactUri.getRawQuery() == null
                && artifactUri.getRawFragment() == null && artifactUri.getPort() == -1;
        if (!safe) {
            throw new IllegalArgumentException(
                    "artifactUri must be an opaque evidence://store/object reference");
        }
        URI normalized = artifactUri.normalize();
        if (normalized.getRawPath().contains("%")) {
            throw new IllegalArgumentException(
                    "artifactUri path must use a canonical unescaped object identifier");
        }
        artifactUri = URI.create("evidence://"
                + normalized.getHost().toLowerCase(Locale.ROOT) + normalized.getRawPath());
    }
}
