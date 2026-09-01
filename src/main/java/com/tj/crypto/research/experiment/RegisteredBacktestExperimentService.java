package com.tj.crypto.research.experiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.marketdata.model.BarEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pre-registers and closes every Spring-managed backtest trial around its real input facts. */
@Service
public class RegisteredBacktestExperimentService {
    private static final String TRIAL_ID = "trial-0";
    private static final String MODEL_VERSION = "MODEL_NONE";
    private static final String PROMPT_VERSION = "PROMPT_NONE";

    private final ExperimentRegistryService registry;
    private final ObjectMapper mapper;
    private final String gitCommit;
    private final String containerImageDigest;

    public RegisteredBacktestExperimentService(
            ExperimentRegistryService registry,
            ObjectMapper mapper,
            @Value("${git.commit:UNKNOWN}") String gitCommit,
            @Value("${CONTAINER_IMAGE_DIGEST:LOCAL_UNVERIFIED}") String containerImageDigest) {
        this.registry = registry;
        this.mapper = mapper;
        this.gitCommit = required(gitCommit, "gitCommit");
        this.containerImageDigest = required(containerImageDigest, "containerImageDigest");
    }

    public PreparedTrial prepare(String executionId, String experimentName,
                                 BacktestConfig config, Object strategyDefinition,
                                 List<BarEvent> bars, long randomSeed, String operator) {
        String runId = required(executionId, "executionId");
        String actor = required(operator, "operator");
        String datasetSha = datasetDigest(bars);
        String configSha = digestJson(Map.of(
                "schemaVersion", "registered-backtest-config-v1",
                "instrument", config.instrument().id().value(),
                "timeframe", config.timeframe().getCode(),
                "dataStartTime", config.dataStartTime(),
                "startTime", config.startTime(),
                "endTime", config.endTime(),
                "initialBalance", config.initialBalance().stripTrailingZeros().toPlainString(),
                "strategy", strategyDefinition));
        String codeSha = digestResource(
                "/com/tj/crypto/backtest/engine/BacktestEngine.class", gitCommit);
        String dependencySha = digestResource(
                "/META-INF/sbom/application.cdx.json", "SBOM_UNAVAILABLE");
        String containerSha = sha256(containerImageDigest);
        String modelSha = sha256(MODEL_VERSION);
        String promptSha = sha256(PROMPT_VERSION);

        List<ExperimentArtifactReference> artifacts = List.of(
                artifact("dataset", ExperimentArtifactKind.DATASET, "canonical-bars-v1",
                        datasetSha, "application/x-ndjson"),
                artifact("code", ExperimentArtifactKind.CODE, gitCommit,
                        codeSha, "application/java-vm"),
                artifact("container", ExperimentArtifactKind.CONTAINER, containerImageDigest,
                        containerSha, "application/vnd.oci.image.manifest.v1+json"),
                artifact("config", ExperimentArtifactKind.CONFIG, "registered-backtest-config-v1",
                        configSha, "application/json"),
                artifact("dependency-lock", ExperimentArtifactKind.DEPENDENCY_LOCK, "cyclonedx-v1",
                        dependencySha, "application/vnd.cyclonedx+json"),
                artifact("model", ExperimentArtifactKind.MODEL, MODEL_VERSION,
                        modelSha, "application/x.no-model"),
                artifact("prompt", ExperimentArtifactKind.PROMPT, PROMPT_VERSION,
                        promptSha, "application/x.no-prompt"));
        ExperimentTrialDefinition trial = new ExperimentTrialDefinition(
                TRIAL_ID, 0, "Execute the complete pre-registered backtest without changing inputs",
                randomSeed, configSha);
        ExperimentFamilyRegistration command = new ExperimentFamilyRegistration(
                "backtest:" + runId,
                required(experimentName, "experimentName"),
                "The declared strategy is evaluated on the exact immutable bar set and assumptions",
                "registered-backtest-v1", MODEL_VERSION, PROMPT_VERSION,
                1, artifacts, List.of(trial));
        ExperimentRegistrationReceipt receipt = registry.register(command, actor);
        Map<String, String> observedArtifacts = new LinkedHashMap<>();
        artifacts.forEach(artifact -> observedArtifacts.put(artifact.artifactId(), artifact.sha256()));
        ExperimentReproductionInputs observed = new ExperimentReproductionInputs(
                receipt.registrationSha256(), observedArtifacts, randomSeed,
                trial.parameterSha256(), MODEL_VERSION, PROMPT_VERSION);
        ExperimentEventAudit started = registry.startTrial(receipt.registrationId(), TRIAL_ID,
                observed, "backtest:" + runId + ":start", actor);
        ResearchExecutionLineage lineage = new ResearchExecutionLineage(
                receipt.registrationId(), receipt.registrationSha256(), TRIAL_ID,
                started.observedProvenanceSha256());
        return new PreparedTrial(lineage, actor, runId);
    }

    public void succeeded(PreparedTrial prepared, BacktestResult result) {
        String resultSha = digestJson(result);
        registry.recordOutcome(prepared.lineage().registrationId(),
                prepared.lineage().trialId(), ExperimentState.SUCCEEDED, null,
                "Backtest completed and required persistence listeners acknowledged the result",
                new ExperimentResultArtifact("urn:sha256:" + resultSha,
                        "backtest-result-v1", resultSha),
                "backtest:" + prepared.executionId() + ":terminal", prepared.operator());
    }

    public void failed(PreparedTrial prepared, RuntimeException failure) {
        try {
            registry.recordOutcome(prepared.lineage().registrationId(),
                    prepared.lineage().trialId(), ExperimentState.FAILED,
                    failure.getClass().getSimpleName(), "Backtest failed closed",
                    null, "backtest:" + prepared.executionId() + ":terminal",
                    prepared.operator());
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
        }
    }

    private ExperimentArtifactReference artifact(String id, ExperimentArtifactKind kind,
                                                   String version, String sha, String mediaType) {
        return new ExperimentArtifactReference(id, kind, version,
                "urn:sha256:" + sha, sha, mediaType);
    }

    private String datasetDigest(List<BarEvent> bars) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("registered backtest requires non-empty bars");
        }
        StringBuilder canonical = new StringBuilder("canonical-bars-v1");
        long previous = Long.MIN_VALUE;
        for (BarEvent bar : bars) {
            long timestamp = bar.metadata().exchangeTimestamp();
            if (timestamp <= previous) {
                throw new IllegalArgumentException("registered bars must be strictly ordered");
            }
            previous = timestamp;
            canonical.append('\n').append(bar.instrument().id().value()).append('|')
                    .append(bar.timeframe().getCode()).append('|').append(timestamp).append('|')
                    .append(bar.open().toPlainString()).append('|').append(bar.high().toPlainString())
                    .append('|').append(bar.low().toPlainString()).append('|')
                    .append(bar.close().toPlainString()).append('|').append(bar.volume().toPlainString())
                    .append('|').append(bar.quoteVolume().toPlainString()).append('|')
                    .append(bar.closed());
        }
        return sha256(canonical.toString());
    }

    private String digestJson(Object value) {
        try {
            return HexFormat.of().formatHex(digest().digest(mapper.writeValueAsBytes(value)));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("research artifact cannot be serialized", error);
        }
    }

    private String digestResource(String resourceName, String fallback) {
        try (InputStream input = RegisteredBacktestExperimentService.class
                .getResourceAsStream(resourceName)) {
            return input == null ? sha256(fallback) : HexFormat.of().formatHex(digest().digest(input.readAllBytes()));
        } catch (IOException error) {
            throw new IllegalStateException("cannot hash runtime research artifact " + resourceName, error);
        }
    }

    private String sha256(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    public record PreparedTrial(ResearchExecutionLineage lineage, String operator,
                                String executionId) {
        public PreparedTrial {
            if (lineage == null || operator == null || operator.isBlank()
                    || executionId == null || executionId.isBlank()) {
                throw new IllegalArgumentException("prepared trial identity is required");
            }
        }
    }
}
