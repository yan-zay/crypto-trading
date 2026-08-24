package com.tj.crypto.research.experiment;

import java.util.List;

/**
 * Signed deterministic certification evidence supplied by a trusted adapter.
 *
 * <p>The registry deliberately does not infer PASS from a result file. The adapter
 * must verify the signature and the OOS/cost/capacity/DSR/PBO policy verdict first.
 */
public record ExperimentCertificationEvidence(
        String registrationId,
        String registrationSha256,
        ExperimentCertificationVerdict verdict,
        String reportUri,
        String reportVersion,
        String reportSha256,
        String policySha256,
        String signerKeyId,
        String signatureAlgorithm,
        String signature,
        boolean signatureVerified,
        boolean outOfSamplePassed,
        boolean transactionCostsPassed,
        boolean capacityPassed,
        boolean deflatedSharpePassed,
        boolean pboPassed,
        long verifiedAtEpochMillis,
        long expiresAtEpochMillis,
        List<String> reasons
) {
    public ExperimentCertificationEvidence {
        if (verdict == null) throw new IllegalArgumentException("certification verdict is required");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (verdict == ExperimentCertificationVerdict.PASS) {
            registrationId = ExperimentValidation.required(registrationId, "registrationId", 64);
            registrationSha256 = ExperimentValidation.sha256(registrationSha256,
                    "registration sha256");
            reportUri = ExperimentValidation.required(reportUri, "certification report uri", 1000);
            reportVersion = ExperimentValidation.required(reportVersion,
                    "certification report version", 160);
            reportSha256 = ExperimentValidation.sha256(reportSha256,
                    "certification report sha256");
            policySha256 = ExperimentValidation.sha256(policySha256,
                    "certification policy sha256");
            signerKeyId = ExperimentValidation.required(signerKeyId, "signer key id", 200);
            signatureAlgorithm = ExperimentValidation.required(signatureAlgorithm,
                    "signature algorithm", 100);
            signature = ExperimentValidation.required(signature, "signature", 2000);
            if (verifiedAtEpochMillis <= 0 || expiresAtEpochMillis <= verifiedAtEpochMillis) {
                throw new IllegalArgumentException("certification verification and expiry times are invalid");
            }
        } else {
            registrationId = registrationId == null || registrationId.isBlank()
                    ? null : ExperimentValidation.required(registrationId, "registrationId", 64);
            if (registrationSha256 != null && !registrationSha256.isBlank()) {
                registrationSha256 = ExperimentValidation.sha256(registrationSha256,
                        "registration sha256");
            } else {
                registrationSha256 = null;
            }
        }
    }

    public static ExperimentCertificationEvidence notVerified(
            String registrationId, String registrationSha256, String reason) {
        return new ExperimentCertificationEvidence(registrationId, registrationSha256,
                ExperimentCertificationVerdict.NOT_VERIFIED, null, null, null, null,
                null, null, null, false, false, false, false, false, false,
                0, 0, List.of(reason));
    }

    public boolean certifiedAt(long nowEpochMillis) {
        return verdict == ExperimentCertificationVerdict.PASS
                && signatureVerified
                && outOfSamplePassed
                && transactionCostsPassed
                && capacityPassed
                && deflatedSharpePassed
                && pboPassed
                && verifiedAtEpochMillis <= nowEpochMillis
                && nowEpochMillis < expiresAtEpochMillis;
    }
}
