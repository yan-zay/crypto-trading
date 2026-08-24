package com.tj.crypto.autonomy.lineage;

import java.time.Duration;
import java.time.Instant;

/**
 * Complete, immutable, content-addressed explanation of why one exact command was decided.
 *
 * <p>The envelope is evidence only. It has no execution method and cannot create a gateway permit.
 * Its identity is the SHA-256 of every normalized field below.</p>
 */
public record OrderDecisionEnvelope(
        String envelopeId,
        String envelopeSha256,
        DecisionFactLineage facts,
        StrategyReleaseLineage strategyRelease,
        BuildArtifactLineage build,
        DecisionProducerLineage producer,
        InstrumentSpecificationLineage instrument,
        DecisionSnapshotLineage snapshots,
        DecisionPolicyLineage policies,
        DecisionAuthorizationLineage authorization,
        ExactOrderCommandReference exactCommand,
        Instant createdAt,
        Instant notBefore,
        Instant expiresAt) {

    public static final Duration MAX_TTL = Duration.ofMinutes(15);
    private static final String HASH_DOMAIN = "order-decision-envelope-v1";

    public OrderDecisionEnvelope {
        envelopeId = OrderDecisionLineageValues.digest(envelopeId, "envelopeId");
        envelopeSha256 = OrderDecisionLineageValues.digest(envelopeSha256, "envelopeSha256");
        if (facts == null || strategyRelease == null || build == null || producer == null
                || instrument == null || snapshots == null || policies == null
                || authorization == null || exactCommand == null) {
            throw new IllegalArgumentException("complete order-decision lineage is required");
        }
        validateWindow(createdAt, notBefore, expiresAt, authorization, snapshots);
        String expected = digest(facts, strategyRelease, build, producer, instrument, snapshots,
                policies, authorization, exactCommand, createdAt, notBefore, expiresAt);
        if (!expected.equals(envelopeId) || !expected.equals(envelopeSha256)) {
            throw new IllegalArgumentException(
                    "envelopeId and envelopeSha256 must address the complete normalized payload");
        }
    }

    public static OrderDecisionEnvelope create(
            DecisionFactLineage facts,
            StrategyReleaseLineage strategyRelease,
            BuildArtifactLineage build,
            DecisionProducerLineage producer,
            InstrumentSpecificationLineage instrument,
            DecisionSnapshotLineage snapshots,
            DecisionPolicyLineage policies,
            DecisionAuthorizationLineage authorization,
            ExactOrderCommandReference exactCommand,
            Instant createdAt,
            Instant notBefore,
            Instant expiresAt) {
        if (facts == null || strategyRelease == null || build == null || producer == null
                || instrument == null || snapshots == null || policies == null
                || authorization == null || exactCommand == null) {
            throw new IllegalArgumentException("complete order-decision lineage is required");
        }
        validateWindow(createdAt, notBefore, expiresAt, authorization, snapshots);
        String digest = digest(facts, strategyRelease, build, producer, instrument, snapshots,
                policies, authorization, exactCommand, createdAt, notBefore, expiresAt);
        return new OrderDecisionEnvelope(digest, digest, facts, strategyRelease, build, producer,
                instrument, snapshots, policies, authorization, exactCommand,
                createdAt, notBefore, expiresAt);
    }

    public void requireActiveAt(Instant now) {
        if (now == null || now.isBefore(notBefore) || !now.isBefore(expiresAt)) {
            throw new OrderDecisionLineageException(
                    "ORDER_DECISION_ENVELOPE_INACTIVE", "Order-decision envelope is not active");
        }
    }

    private static void validateWindow(
            Instant createdAt,
            Instant notBefore,
            Instant expiresAt,
            DecisionAuthorizationLineage authorization,
            DecisionSnapshotLineage snapshots) {
        OrderDecisionLineageValues.millisecondInstant(createdAt, "createdAt");
        OrderDecisionLineageValues.millisecondInstant(notBefore, "notBefore");
        OrderDecisionLineageValues.millisecondInstant(expiresAt, "expiresAt");
        if (notBefore.isBefore(createdAt) || !expiresAt.isAfter(notBefore)) {
            throw new IllegalArgumentException("envelope must have a positive forward validity window");
        }
        if (Duration.between(createdAt, expiresAt).compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("envelope TTL exceeds " + MAX_TTL);
        }
        if (notBefore.isBefore(authorization.capabilityNotBefore())
                || expiresAt.isAfter(authorization.capabilityExpiresAt())) {
            throw new IllegalArgumentException("envelope window must fit inside capability validity");
        }
        if (snapshots.accountSnapshot().observedAt().isAfter(createdAt)
                || snapshots.riskSnapshot().observedAt().isAfter(createdAt)
                || snapshots.valuationSnapshot().observedAt().isAfter(createdAt)) {
            throw new IllegalArgumentException("decision snapshots cannot come from the future");
        }
    }

    private static String digest(
            DecisionFactLineage facts,
            StrategyReleaseLineage strategy,
            BuildArtifactLineage build,
            DecisionProducerLineage producer,
            InstrumentSpecificationLineage instrument,
            DecisionSnapshotLineage snapshots,
            DecisionPolicyLineage policies,
            DecisionAuthorizationLineage authorization,
            ExactOrderCommandReference command,
            Instant createdAt,
            Instant notBefore,
            Instant expiresAt) {
        return OrderDecisionLineageValues.sha256(sink -> {
            sink.value(HASH_DOMAIN)
                    .value(facts.marketEventId()).value(facts.marketEventSha256())
                    .value(facts.journalEntryId()).value(facts.journalEntrySha256())
                    .value(facts.archiveManifestId()).value(facts.archiveManifestSha256())
                    .value(facts.factorSnapshotId()).value(facts.factorSnapshotSha256())
                    .value(facts.signalId()).value(facts.signalSha256())
                    .value(strategy.strategyId()).value(strategy.releaseVersion())
                    .value(strategy.releaseAssignmentId()).value(strategy.releaseAssignmentVersion())
                    .value(strategy.strategyArtifactSha256())
                    .value(strategy.governanceSubjectSha256())
                    .value(build.gitCommitHash()).value(build.buildArtifactSha256())
                    .value(build.containerImageSha256()).value(build.configurationSha256())
                    .value(producer.producerKind().name()).value(producer.producerId())
                    .nullable(producer.proposalId()).nullable(producer.proposalSha256())
                    .value(producer.modelVersion()).nullable(producer.promptSha256())
                    .nullable(producer.contextSha256()).nullable(producer.toolTraceRootSha256())
                    .value(instrument.instrumentNamespace()).value(instrument.assetClass())
                    .value(instrument.instrumentId()).value(instrument.specificationVersion())
                    .value(instrument.instrumentSpecificationSha256());
            appendSnapshot(sink, snapshots.accountSnapshot());
            appendSnapshot(sink, snapshots.riskSnapshot());
            appendSnapshot(sink, snapshots.valuationSnapshot());
            appendPolicy(sink, policies.capitalPolicy());
            appendPolicy(sink, policies.riskPolicy());
            appendPolicy(sink, policies.executionPolicy());
            sink.value(authorization.approvalId()).value(authorization.approvalSha256())
                    .value(authorization.capabilityId()).value(authorization.capabilitySha256())
                    .value(authorization.authorizationSubjectSha256())
                    .value(authorization.capabilityNotBefore())
                    .value(authorization.capabilityExpiresAt())
                    .value(command.orderId()).value(command.clientOrderId())
                    .value(command.commandId()).value(command.commandSchemaVersion())
                    .value(command.commandSha256())
                    .value(createdAt).value(notBefore).value(expiresAt);
        });
    }

    private static void appendSnapshot(
            OrderDecisionLineageValues.DigestSink sink, SnapshotReference snapshot) {
        sink.value(snapshot.snapshotId()).value(snapshot.snapshotSha256())
                .value(snapshot.observedAt());
    }

    private static void appendPolicy(
            OrderDecisionLineageValues.DigestSink sink, PolicyArtifactReference policy) {
        sink.value(policy.policyId()).value(policy.policyVersion())
                .value(policy.policyArtifactSha256());
    }
}
