package com.tj.crypto.governance.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Metadata-only attestation for a signing key. Raw key material is deliberately unrepresentable. */
public record KeyCustodyAttestation(
        CustodyProvider provider,
        String keyReference,
        String environment,
        boolean nonExportable,
        boolean signingOnly,
        boolean withdrawalsDisabled,
        boolean sourceIpRestricted,
        long verifiedAtMs,
        long rotationDrillAtMs,
        long revocationDrillAtMs,
        long expiresAtMs,
        String artifactSha256) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> KEY_REFERENCE_SCHEMES = Set.of(
            "hsm", "vault", "kms", "awskms", "azurekv", "gcpkms");

    public KeyCustodyAttestation {
        if (provider == null) throw new IllegalArgumentException("provider is required");
        keyReference = required(keyReference, "keyReference");
        requireOpaqueKeyReference(keyReference);
        environment = required(environment, "environment").toUpperCase(Locale.ROOT);
        artifactSha256 = required(artifactSha256, "artifactSha256").toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(artifactSha256).matches()) {
            throw new IllegalArgumentException("artifactSha256 must be a lowercase SHA-256");
        }
        if (verifiedAtMs <= 0 || rotationDrillAtMs <= 0 || revocationDrillAtMs <= 0
                || expiresAtMs <= verifiedAtMs) {
            throw new IllegalArgumentException("custody attestation timestamps are invalid");
        }
        String scheme = URI.create(keyReference).getScheme().toLowerCase(Locale.ROOT);
        if (!providerAllows(provider, scheme)) {
            throw new IllegalArgumentException("keyReference scheme does not match custody provider");
        }
    }

    @Override
    public String toString() {
        return "KeyCustodyAttestation[provider=" + provider + ", keyReference=<redacted>"
                + ", environment=" + environment + ", nonExportable=" + nonExportable
                + ", signingOnly=" + signingOnly + ", withdrawalsDisabled=" + withdrawalsDisabled
                + ", sourceIpRestricted=" + sourceIpRestricted + ", verifiedAtMs=" + verifiedAtMs
                + ", expiresAtMs=" + expiresAtMs + ", artifactSha256=" + artifactSha256 + "]";
    }

    private static void requireOpaqueKeyReference(String raw) {
        try {
            URI uri = URI.create(raw);
            boolean safe = uri.getScheme() != null
                    && KEY_REFERENCE_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawPath() != null && !uri.getRawPath().isBlank()
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getPort() == -1;
            if (!safe) throw new IllegalArgumentException("unsafe reference");
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("keyReference must be an opaque KMS/Vault/HSM reference", invalid);
        }
    }

    private static boolean providerAllows(CustodyProvider provider, String scheme) {
        return switch (provider) {
            case AWS_KMS -> "awskms".equals(scheme) || "kms".equals(scheme);
            case AZURE_KEY_VAULT -> "azurekv".equals(scheme) || "kms".equals(scheme);
            case GCP_KMS -> "gcpkms".equals(scheme) || "kms".equals(scheme);
            case HASHICORP_VAULT -> "vault".equals(scheme);
            case HSM -> "hsm".equals(scheme);
            case EXTERNAL_SIGNER -> Set.of("hsm", "vault", "kms").contains(scheme);
        };
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
