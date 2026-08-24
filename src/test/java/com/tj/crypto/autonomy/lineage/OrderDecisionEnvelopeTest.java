package com.tj.crypto.autonomy.lineage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDecisionEnvelopeTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-24T01:00:00Z");

    @Test
    void deterministicEnvelopeIsReproducibleAndExplicitlyHasNoModel() {
        Fixture first = Fixture.deterministic("café-kernel");
        Fixture second = Fixture.deterministic(
                Normalizer.normalize("café-kernel", Normalizer.Form.NFD));

        OrderDecisionEnvelope envelope = first.create();

        assertThat(envelope.envelopeId()).isEqualTo(envelope.envelopeSha256())
                .matches("[0-9a-f]{64}")
                .isEqualTo(second.create().envelopeSha256());
        assertThat(envelope.producer().producerKind()).isEqualTo(DecisionProducerKind.DETERMINISTIC);
        assertThat(envelope.producer().modelVersion()).isEqualTo(DecisionProducerLineage.MODEL_NONE);
        assertThat(envelope.producer().proposalSha256()).isNull();
    }

    @Test
    void agentEnvelopeRequiresProposalModelPromptContextAndToolTrace() {
        DecisionProducerLineage agent = DecisionProducerLineage.agent(
                "agent-workload-1", "proposal-1", sha('1'), "gpt-model-build-1",
                sha('2'), sha('3'), sha('4'));
        Fixture fixture = Fixture.deterministic("kernel").withProducer(agent);

        assertThat(fixture.create().producer().producerKind()).isEqualTo(DecisionProducerKind.AGENT);
        assertThatThrownBy(() -> new DecisionProducerLineage(
                DecisionProducerKind.DETERMINISTIC, "kernel", null, null, "model-1",
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MODEL_NONE");
        assertThatThrownBy(() -> new DecisionProducerLineage(
                DecisionProducerKind.DETERMINISTIC, "kernel", "proposal-1", sha('1'),
                DecisionProducerLineage.MODEL_NONE, sha('2'), sha('3'), sha('4')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot claim Agent lineage");
        assertThatThrownBy(() -> new DecisionProducerLineage(
                DecisionProducerKind.AGENT, "agent", "proposal-1", sha('1'),
                DecisionProducerLineage.MODEL_NONE, sha('2'), sha('3'), sha('4')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model version");
        assertThatThrownBy(() -> DecisionProducerLineage.agent(
                "agent", "proposal-1", sha('1'), "model-1", sha('2'), sha('3'), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolTraceRootSha256");
    }

    @Test
    void anyClaimedFieldMutationWithoutReaddressingIsRejected() {
        Fixture fixture = Fixture.deterministic("kernel");
        OrderDecisionEnvelope original = fixture.create();

        DecisionFactLineage changedFacts = new DecisionFactLineage(
                original.facts().marketEventId(), sha('9'),
                original.facts().journalEntryId(), original.facts().journalEntrySha256(),
                original.facts().archiveManifestId(), original.facts().archiveManifestSha256(),
                original.facts().factorSnapshotId(), original.facts().factorSnapshotSha256(),
                original.facts().signalId(), original.facts().signalSha256());
        BuildArtifactLineage changedBuild = new BuildArtifactLineage(
                "f".repeat(40), original.build().buildArtifactSha256(),
                original.build().containerImageSha256(), original.build().configurationSha256());
        ExactOrderCommandReference changedCommand = new ExactOrderCommandReference(
                original.exactCommand().orderId(), original.exactCommand().clientOrderId(),
                original.exactCommand().commandId(),
                original.exactCommand().commandSchemaVersion(), sha('8'));

        StrategyReleaseLineage changedStrategy = new StrategyReleaseLineage(
                "golden-strategy", "release-8", "assignment-8", 8, sha('6'), sha('7'));
        InstrumentSpecificationLineage changedInstrument = new InstrumentSpecificationLineage(
                "ISO-10383", "CRYPTO_PERPETUAL", "ETHUSDT", "spec-12", sha('b'));
        DecisionSnapshotLineage changedSnapshots = new DecisionSnapshotLineage(
                new SnapshotReference("account-snapshot-2", sha('c'), CREATED_AT.minusSeconds(3)),
                original.snapshots().riskSnapshot(), original.snapshots().valuationSnapshot());
        DecisionPolicyLineage changedPolicies = new DecisionPolicyLineage(
                original.policies().capitalPolicy(), original.policies().riskPolicy(),
                new PolicyArtifactReference("execution-policy", "6", sha('1')));
        DecisionAuthorizationLineage changedAuthorization = new DecisionAuthorizationLineage(
                "approval-2", original.authorization().approvalSha256(),
                original.authorization().capabilityId(), original.authorization().capabilitySha256(),
                original.authorization().authorizationSubjectSha256(),
                original.authorization().capabilityNotBefore(),
                original.authorization().capabilityExpiresAt());

        List<OrderDecisionEnvelope> mutations = List.of(
                fixture.withFacts(changedFacts).create(),
                fixture.withStrategy(changedStrategy).create(),
                fixture.withBuild(changedBuild).create(),
                fixture.withProducer(DecisionProducerLineage.deterministic("other-kernel")).create(),
                fixture.withInstrument(changedInstrument).create(),
                fixture.withSnapshots(changedSnapshots).create(),
                fixture.withPolicies(changedPolicies).create(),
                fixture.withAuthorization(changedAuthorization).create(),
                fixture.withCommand(changedCommand).create(),
                fixture.withExpiresAt(original.expiresAt().minusSeconds(1)).create());

        assertThat(mutations).extracting(OrderDecisionEnvelope::envelopeSha256)
                .doesNotContain(original.envelopeSha256());
        mutations.forEach(changed -> assertTamperRejected(original, changed));
    }

    @Test
    void ttlCapabilityWindowAndPointInTimeSnapshotsFailClosed() {
        Fixture fixture = Fixture.deterministic("kernel");
        OrderDecisionEnvelope envelope = fixture.create();

        envelope.requireActiveAt(CREATED_AT.plusSeconds(1));
        assertThatThrownBy(() -> envelope.requireActiveAt(CREATED_AT.minusMillis(1)))
                .isInstanceOf(OrderDecisionLineageException.class)
                .extracting(error -> ((OrderDecisionLineageException) error).reasonCode())
                .isEqualTo("ORDER_DECISION_ENVELOPE_INACTIVE");
        assertThatThrownBy(() -> envelope.requireActiveAt(envelope.expiresAt()))
                .isInstanceOf(OrderDecisionLineageException.class);

        assertThatThrownBy(() -> fixture.withExpiresAt(
                fixture.notBefore.plus(OrderDecisionEnvelope.MAX_TTL).plusMillis(1)).create())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TTL");

        SnapshotReference future = new SnapshotReference("risk-future", sha('a'),
                CREATED_AT.plusMillis(1));
        assertThatThrownBy(() -> fixture.withSnapshots(new DecisionSnapshotLineage(
                fixture.snapshots.accountSnapshot(), future,
                fixture.snapshots.valuationSnapshot())).create())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> new SnapshotReference(
                "sub-millisecond", sha('b'), CREATED_AT.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("millisecond precision");

        DecisionAuthorizationLineage tooShort = new DecisionAuthorizationLineage(
                "approval-1", sha('e'), "capability-1", sha('f'), sha('0'),
                CREATED_AT, CREATED_AT.plusSeconds(10));
        assertThatThrownBy(() -> fixture.withAuthorization(tooShort).create())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capability validity");
    }

    @Test
    void defaultPortsAreUnavailableAndAdmissionCannotProduceAuthority() throws Exception {
        OrderDecisionEnvelope envelope = Fixture.deterministic("kernel").create();

        assertUnavailable(() -> OrderDecisionStore.unavailable().append(envelope),
                "ORDER_DECISION_STORE_UNAVAILABLE");
        assertUnavailable(() -> OrderDecisionVerifier.unavailable().verify(envelope, CREATED_AT),
                "ORDER_DECISION_VERIFIER_UNAVAILABLE");
        assertUnavailable(() -> OrderDecisionAdmissionPort.unavailable()
                        .requireAdmissible(envelope, CREATED_AT),
                "ORDER_DECISION_ADMISSION_UNAVAILABLE");

        Method admission = OrderDecisionAdmissionPort.class.getMethod(
                "requireAdmissible", OrderDecisionEnvelope.class, Instant.class);
        assertThat(admission.getReturnType()).isEqualTo(void.class);
        assertThat(OrderDecisionAdmissionPort.class.getDeclaredMethods())
                .allMatch(method -> !method.getReturnType().getSimpleName().toLowerCase()
                        .contains("permit"));
    }

    private static void assertTamperRejected(
            OrderDecisionEnvelope original, OrderDecisionEnvelope changed) {
        assertThatThrownBy(() -> new OrderDecisionEnvelope(
                original.envelopeId(), original.envelopeSha256(), changed.facts(),
                changed.strategyRelease(), changed.build(), changed.producer(), changed.instrument(),
                changed.snapshots(), changed.policies(), changed.authorization(), changed.exactCommand(),
                changed.createdAt(), changed.notBefore(), changed.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete normalized payload");
    }

    private static void assertUnavailable(ThrowingAction action, String reasonCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(OrderDecisionLineageException.class)
                .extracting(error -> ((OrderDecisionLineageException) error).reasonCode())
                .isEqualTo(reasonCode);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    private static final class Fixture {
        private final DecisionFactLineage facts;
        private final StrategyReleaseLineage strategy;
        private final BuildArtifactLineage build;
        private final DecisionProducerLineage producer;
        private final InstrumentSpecificationLineage instrument;
        private final DecisionSnapshotLineage snapshots;
        private final DecisionPolicyLineage policies;
        private final DecisionAuthorizationLineage authorization;
        private final ExactOrderCommandReference command;
        private final Instant notBefore;
        private final Instant expiresAt;

        private Fixture(
                DecisionFactLineage facts,
                StrategyReleaseLineage strategy,
                BuildArtifactLineage build,
                DecisionProducerLineage producer,
                InstrumentSpecificationLineage instrument,
                DecisionSnapshotLineage snapshots,
                DecisionPolicyLineage policies,
                DecisionAuthorizationLineage authorization,
                ExactOrderCommandReference command,
                Instant notBefore,
                Instant expiresAt) {
            this.facts = facts;
            this.strategy = strategy;
            this.build = build;
            this.producer = producer;
            this.instrument = instrument;
            this.snapshots = snapshots;
            this.policies = policies;
            this.authorization = authorization;
            this.command = command;
            this.notBefore = notBefore;
            this.expiresAt = expiresAt;
        }

        static Fixture deterministic(String producerId) {
            DecisionFactLineage facts = new DecisionFactLineage(
                    "market-1", sha('1'), "journal-1", sha('2'),
                    "manifest-1", sha('3'), "factor-1", sha('4'),
                    "signal-1", sha('5'));
            StrategyReleaseLineage strategy = new StrategyReleaseLineage(
                    "golden-strategy", "release-7", "assignment-7", 7,
                    sha('6'), sha('7'));
            BuildArtifactLineage build = new BuildArtifactLineage(
                    "a".repeat(40), sha('8'), sha('9'), sha('a'));
            InstrumentSpecificationLineage instrument = new InstrumentSpecificationLineage(
                    "ISO-10383", "crypto_perpetual", "BTCUSDT", "spec-11", sha('b'));
            SnapshotReference account = new SnapshotReference(
                    "account-snapshot-1", sha('c'), CREATED_AT.minusSeconds(3));
            SnapshotReference risk = new SnapshotReference(
                    "risk-snapshot-1", sha('d'), CREATED_AT.minusSeconds(2));
            SnapshotReference valuation = new SnapshotReference(
                    "valuation-snapshot-1", sha('e'), CREATED_AT.minusSeconds(1));
            DecisionPolicyLineage policies = new DecisionPolicyLineage(
                    new PolicyArtifactReference("capital-policy", "3", sha('f')),
                    new PolicyArtifactReference("risk-policy", "9", sha('0')),
                    new PolicyArtifactReference("execution-policy", "5", sha('1')));
            DecisionAuthorizationLineage authorization = new DecisionAuthorizationLineage(
                    "approval-1", sha('2'), "capability-1", sha('3'), sha('4'),
                    CREATED_AT, CREATED_AT.plusSeconds(600));
            return new Fixture(facts, strategy, build,
                    DecisionProducerLineage.deterministic(producerId), instrument,
                    new DecisionSnapshotLineage(account, risk, valuation), policies,
                    authorization, new ExactOrderCommandReference(
                    "order-1", "client-order-1", "command-1", "LIVE_ORDER_V1", sha('5')),
                    CREATED_AT, CREATED_AT.plusSeconds(300));
        }

        Fixture withProducer(DecisionProducerLineage value) {
            return new Fixture(facts, strategy, build, value, instrument, snapshots, policies,
                    authorization, command, notBefore, expiresAt);
        }

        Fixture withFacts(DecisionFactLineage value) {
            return new Fixture(value, strategy, build, producer, instrument, snapshots, policies,
                    authorization, command, notBefore, expiresAt);
        }

        Fixture withStrategy(StrategyReleaseLineage value) {
            return new Fixture(facts, value, build, producer, instrument, snapshots, policies,
                    authorization, command, notBefore, expiresAt);
        }

        Fixture withBuild(BuildArtifactLineage value) {
            return new Fixture(facts, strategy, value, producer, instrument, snapshots, policies,
                    authorization, command, notBefore, expiresAt);
        }

        Fixture withInstrument(InstrumentSpecificationLineage value) {
            return new Fixture(facts, strategy, build, producer, value, snapshots, policies,
                    authorization, command, notBefore, expiresAt);
        }

        Fixture withSnapshots(DecisionSnapshotLineage value) {
            return new Fixture(facts, strategy, build, producer, instrument, value, policies,
                    authorization, command, notBefore, expiresAt);
        }

        Fixture withAuthorization(DecisionAuthorizationLineage value) {
            return new Fixture(facts, strategy, build, producer, instrument, snapshots, policies,
                    value, command, notBefore, expiresAt);
        }

        Fixture withPolicies(DecisionPolicyLineage value) {
            return new Fixture(facts, strategy, build, producer, instrument, snapshots, value,
                    authorization, command, notBefore, expiresAt);
        }

        Fixture withCommand(ExactOrderCommandReference value) {
            return new Fixture(facts, strategy, build, producer, instrument, snapshots, policies,
                    authorization, value, notBefore, expiresAt);
        }

        Fixture withExpiresAt(Instant value) {
            return new Fixture(facts, strategy, build, producer, instrument, snapshots, policies,
                    authorization, command, notBefore, value);
        }

        OrderDecisionEnvelope create() {
            return OrderDecisionEnvelope.create(facts, strategy, build, producer, instrument,
                    snapshots, policies, authorization, command,
                    CREATED_AT, notBefore, expiresAt);
        }
    }
}
