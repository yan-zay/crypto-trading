package com.tj.crypto.research.certification;

import com.tj.crypto.research.dataset.ImmutableArtifactIO;
import com.tj.crypto.research.dataset.ImmutableBarDatasetVerifier;
import com.tj.crypto.research.dataset.ResearchJson;
import com.tj.crypto.research.dataset.VerifiedBarDataset;

import java.nio.file.Path;
import java.util.Map;

/** Minimal offline CLI for immutable dataset verification and report creation. */
public final class GoldenCertificationCli {
    private GoldenCertificationCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "verify".equals(args[0])) {
            VerifiedBarDataset dataset = new ImmutableBarDatasetVerifier().verify(Path.of(args[1]));
            System.out.println(ResearchJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "datasetId", dataset.manifest().datasetId(),
                    "manifestSha256", dataset.manifestSha256(),
                    "dataSha256", dataset.manifest().dataSha256(),
                    "rowCount", dataset.rows().size(),
                    "declaredImmutableRealMarketData", dataset.eligibleForRealDataResearchGate())));
            return;
        }
        if (args.length == 5 && "certify".equals(args[0])) {
            Path manifest = Path.of(args[1]);
            Path registration = Path.of(args[2]);
            Path report = Path.of(args[3]);
            long generatedAt = Long.parseLong(args[4]);
            GoldenStrategyCertificationReport result = new GoldenStrategyCertificationService()
                    .certify(manifest, registration, generatedAt);
            new ImmutableArtifactIO().writeNew(report, result);
            System.out.println(result.status());
            System.out.println("alphaCertified=" + result.alphaCertified());
            return;
        }
        throw new IllegalArgumentException("Usage: verify <manifest.json> | certify <manifest.json> "
                + "<registration.json> <new-report.json> <generatedAtEpochMillis>");
    }
}
