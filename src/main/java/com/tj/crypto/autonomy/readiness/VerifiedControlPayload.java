package com.tj.crypto.autonomy.readiness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Closed, control-specific claim schema returned by a trusted external verifier.
 *
 * <p>This intentionally does not use an open string map. Unknown schemas therefore cannot be
 * interpreted as valid evidence by the policy gate.</p>
 */
public sealed interface VerifiedControlPayload permits
        VerifiedControlPayload.Legal,
        VerifiedControlPayload.Reproducibility,
        VerifiedControlPayload.ResearchAlpha,
        VerifiedControlPayload.ExecutionConsistency,
        VerifiedControlPayload.RiskControls,
        VerifiedControlPayload.Reconciliation,
        VerifiedControlPayload.Security,
        VerifiedControlPayload.Operations,
        VerifiedControlPayload.AgentAssurance,
        VerifiedControlPayload.CapitalControls,
        VerifiedControlPayload.OwnerVenueContract,
        VerifiedControlPayload.CrossMarketState {

    LongTermControl control();

    enum Result {
        PASS,
        FAIL,
        UNKNOWN
    }

    enum DeploymentMatch {
        MATCH,
        MISMATCH,
        UNKNOWN
    }

    enum ApprovalMode {
        PER_RECOMMENDATION,
        PREAPPROVED_ENVELOPE,
        UNKNOWN
    }

    enum GatewayAccess {
        NONE,
        DIRECT,
        UNKNOWN
    }

    enum AccountIsolation {
        ISOLATED_SUBACCOUNT,
        SHARED,
        UNKNOWN
    }

    enum WithdrawalPermission {
        PROHIBITED,
        ALLOWED,
        UNKNOWN
    }

    enum MarketRegime {
        TRENDING,
        RANGE_BOUND,
        HIGH_VOLATILITY,
        LOW_LIQUIDITY,
        STRESS
    }

    record Legal(String legalEntity,
                 String jurisdiction,
                 String legalScopeSha256,
                 String writtenOpinionSha256,
                 Instant opinionIssuedAt,
                 Instant opinionExpiresAt,
                 DeploymentMatch actualDeploymentMatch) implements VerifiedControlPayload {
        public Legal {
            legalEntity = text(legalEntity, "legalEntity", 192);
            jurisdiction = text(jurisdiction, "jurisdiction", 96);
            legalScopeSha256 = hash(legalScopeSha256, "legalScopeSha256");
            writtenOpinionSha256 = hash(writtenOpinionSha256, "writtenOpinionSha256");
            opinionIssuedAt = Objects.requireNonNull(opinionIssuedAt, "opinionIssuedAt");
            opinionExpiresAt = Objects.requireNonNull(opinionExpiresAt, "opinionExpiresAt");
            actualDeploymentMatch = Objects.requireNonNull(
                    actualDeploymentMatch, "actualDeploymentMatch");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.LEGAL;
        }
    }

    record Reproducibility(String gitCommit,
                           String dependencyLockSha256,
                           String containerImageDigest,
                           String datasetManifestSha256,
                           String configurationSha256,
                           long seed,
                           String modelVersion,
                           String promptSha256,
                           String agentBundleSha256,
                           Result cleanRebuild,
                           Result immutableDataReplay,
                           Result deterministicRerun) implements VerifiedControlPayload {
        public Reproducibility {
            gitCommit = text(gitCommit, "gitCommit", 64).toLowerCase(Locale.ROOT);
            dependencyLockSha256 = hash(dependencyLockSha256, "dependencyLockSha256");
            containerImageDigest = text(containerImageDigest, "containerImageDigest", 96)
                    .toLowerCase(Locale.ROOT);
            datasetManifestSha256 = hash(datasetManifestSha256, "datasetManifestSha256");
            configurationSha256 = hash(configurationSha256, "configurationSha256");
            modelVersion = text(modelVersion, "modelVersion", 128);
            promptSha256 = hash(promptSha256, "promptSha256");
            agentBundleSha256 = hash(agentBundleSha256, "agentBundleSha256");
            cleanRebuild = Objects.requireNonNull(cleanRebuild, "cleanRebuild");
            immutableDataReplay = Objects.requireNonNull(
                    immutableDataReplay, "immutableDataReplay");
            deterministicRerun = Objects.requireNonNull(
                    deterministicRerun, "deterministicRerun");
            if (!gitCommit.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                throw new IllegalArgumentException("gitCommit must be a full Git object id");
            }
            if (!containerImageDigest.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("containerImageDigest must be a sha256 digest");
            }
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.REPRODUCIBILITY;
        }
    }

    record ResearchAlpha(String preregistrationSha256,
                         String oosReportSha256,
                         String pboDsrReportSha256,
                         String costCapacityBenchmarkSha256,
                         BigDecimal netOutOfSampleAlpha,
                         int registeredTrials,
                         int failedTrialsRecorded,
                         Result outOfSample,
                         Result pboDsr,
                         Result multipleTesting,
                         Result costCapacity,
                         Result simpleBenchmark) implements VerifiedControlPayload {
        public ResearchAlpha {
            preregistrationSha256 = hash(preregistrationSha256, "preregistrationSha256");
            oosReportSha256 = hash(oosReportSha256, "oosReportSha256");
            pboDsrReportSha256 = hash(pboDsrReportSha256, "pboDsrReportSha256");
            costCapacityBenchmarkSha256 = hash(
                    costCapacityBenchmarkSha256, "costCapacityBenchmarkSha256");
            netOutOfSampleAlpha = Objects.requireNonNull(
                    netOutOfSampleAlpha, "netOutOfSampleAlpha").stripTrailingZeros();
            nonNegative(registeredTrials, "registeredTrials");
            nonNegative(failedTrialsRecorded, "failedTrialsRecorded");
            outOfSample = Objects.requireNonNull(outOfSample, "outOfSample");
            pboDsr = Objects.requireNonNull(pboDsr, "pboDsr");
            multipleTesting = Objects.requireNonNull(multipleTesting, "multipleTesting");
            costCapacity = Objects.requireNonNull(costCapacity, "costCapacity");
            simpleBenchmark = Objects.requireNonNull(simpleBenchmark, "simpleBenchmark");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.RESEARCH_ALPHA;
        }
    }

    record ExecutionConsistency(String parityReportSha256,
                                long observedOrders,
                                long observedFills,
                                long observedPnlSnapshots,
                                long unexplainedOrderDifferences,
                                long unexplainedFillDifferences,
                                long unexplainedPnlDifferences) implements VerifiedControlPayload {
        public ExecutionConsistency {
            parityReportSha256 = hash(parityReportSha256, "parityReportSha256");
            nonNegative(observedOrders, "observedOrders");
            nonNegative(observedFills, "observedFills");
            nonNegative(observedPnlSnapshots, "observedPnlSnapshots");
            nonNegative(unexplainedOrderDifferences, "unexplainedOrderDifferences");
            nonNegative(unexplainedFillDifferences, "unexplainedFillDifferences");
            nonNegative(unexplainedPnlDifferences, "unexplainedPnlDifferences");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.EXECUTION_CONSISTENCY;
        }
    }

    record RiskControls(String riskPolicySha256,
                        String negativeTestReportSha256,
                        Result killSwitch,
                        Result singleWriterFencing,
                        Result reduceOnlyPath,
                        Result automaticDowngrade,
                        Result humanTakeover) implements VerifiedControlPayload {
        public RiskControls {
            riskPolicySha256 = hash(riskPolicySha256, "riskPolicySha256");
            negativeTestReportSha256 = hash(
                    negativeTestReportSha256, "negativeTestReportSha256");
            killSwitch = Objects.requireNonNull(killSwitch, "killSwitch");
            singleWriterFencing = Objects.requireNonNull(
                    singleWriterFencing, "singleWriterFencing");
            reduceOnlyPath = Objects.requireNonNull(reduceOnlyPath, "reduceOnlyPath");
            automaticDowngrade = Objects.requireNonNull(
                    automaticDowngrade, "automaticDowngrade");
            humanTakeover = Objects.requireNonNull(humanTakeover, "humanTakeover");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.RISK_CONTROLS;
        }
    }

    record Reconciliation(String convergenceReportSha256,
                          long convergenceCycles,
                          long unresolvedOrderDifferences,
                          long unresolvedFillDifferences,
                          long unresolvedBalanceDifferences,
                          long unresolvedPositionDifferences,
                          long unresolvedLedgerDifferences) implements VerifiedControlPayload {
        public Reconciliation {
            convergenceReportSha256 = hash(
                    convergenceReportSha256, "convergenceReportSha256");
            nonNegative(convergenceCycles, "convergenceCycles");
            nonNegative(unresolvedOrderDifferences, "unresolvedOrderDifferences");
            nonNegative(unresolvedFillDifferences, "unresolvedFillDifferences");
            nonNegative(unresolvedBalanceDifferences, "unresolvedBalanceDifferences");
            nonNegative(unresolvedPositionDifferences, "unresolvedPositionDifferences");
            nonNegative(unresolvedLedgerDifferences, "unresolvedLedgerDifferences");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.RECONCILIATION;
        }
    }

    record Security(String keyCustodyReceiptSha256,
                    String mfaReceiptSha256,
                    String dualApprovalReceiptSha256,
                    String rotationRevocationReportSha256,
                    Result keyCustody,
                    Result mfa,
                    Result dualApproval,
                    Result rotationRevocation,
                    int scannedAssets,
                    int criticalFindings,
                    int highFindings) implements VerifiedControlPayload {
        public Security {
            keyCustodyReceiptSha256 = hash(
                    keyCustodyReceiptSha256, "keyCustodyReceiptSha256");
            mfaReceiptSha256 = hash(mfaReceiptSha256, "mfaReceiptSha256");
            dualApprovalReceiptSha256 = hash(
                    dualApprovalReceiptSha256, "dualApprovalReceiptSha256");
            rotationRevocationReportSha256 = hash(
                    rotationRevocationReportSha256, "rotationRevocationReportSha256");
            keyCustody = Objects.requireNonNull(keyCustody, "keyCustody");
            mfa = Objects.requireNonNull(mfa, "mfa");
            dualApproval = Objects.requireNonNull(dualApproval, "dualApproval");
            rotationRevocation = Objects.requireNonNull(
                    rotationRevocation, "rotationRevocation");
            nonNegative(scannedAssets, "scannedAssets");
            nonNegative(criticalFindings, "criticalFindings");
            nonNegative(highFindings, "highFindings");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.SECURITY;
        }
    }

    record Operations(String alertDeliveryReceiptSha256,
                      String backupRestoreReportSha256,
                      String rpoRtoReportSha256,
                      Result alertDelivery,
                      Result backupRestore,
                      Result rpoRto,
                      Duration continuousSoak,
                      int chaosEvents,
                      int unexplainedIncidents) implements VerifiedControlPayload {
        public Operations {
            alertDeliveryReceiptSha256 = hash(
                    alertDeliveryReceiptSha256, "alertDeliveryReceiptSha256");
            backupRestoreReportSha256 = hash(
                    backupRestoreReportSha256, "backupRestoreReportSha256");
            rpoRtoReportSha256 = hash(rpoRtoReportSha256, "rpoRtoReportSha256");
            alertDelivery = Objects.requireNonNull(alertDelivery, "alertDelivery");
            backupRestore = Objects.requireNonNull(backupRestore, "backupRestore");
            rpoRto = Objects.requireNonNull(rpoRto, "rpoRto");
            continuousSoak = Objects.requireNonNull(continuousSoak, "continuousSoak");
            if (continuousSoak.isNegative()) {
                throw new IllegalArgumentException("continuousSoak must not be negative");
            }
            nonNegative(chaosEvents, "chaosEvents");
            nonNegative(unexplainedIncidents, "unexplainedIncidents");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.OPERATIONS;
        }
    }

    record AgentAssurance(String adversarialEvalSha256,
                          String toolAuditSha256,
                          String revocationReportSha256,
                          String agentBaselineReportSha256,
                          String agentBundleSha256,
                          ApprovalMode approvalMode,
                          GatewayAccess privateGatewayAccess,
                          Result immediateRevocation,
                          Result incrementalValue,
                          int adversarialCases,
                          int successfulEscapes,
                          int secretExposures) implements VerifiedControlPayload {
        public AgentAssurance {
            adversarialEvalSha256 = hash(adversarialEvalSha256, "adversarialEvalSha256");
            toolAuditSha256 = hash(toolAuditSha256, "toolAuditSha256");
            revocationReportSha256 = hash(
                    revocationReportSha256, "revocationReportSha256");
            agentBaselineReportSha256 = hash(
                    agentBaselineReportSha256, "agentBaselineReportSha256");
            agentBundleSha256 = hash(agentBundleSha256, "agentBundleSha256");
            approvalMode = Objects.requireNonNull(approvalMode, "approvalMode");
            privateGatewayAccess = Objects.requireNonNull(
                    privateGatewayAccess, "privateGatewayAccess");
            immediateRevocation = Objects.requireNonNull(
                    immediateRevocation, "immediateRevocation");
            incrementalValue = Objects.requireNonNull(incrementalValue, "incrementalValue");
            nonNegative(adversarialCases, "adversarialCases");
            nonNegative(successfulEscapes, "successfulEscapes");
            nonNegative(secretExposures, "secretExposures");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.AGENT_ASSURANCE;
        }
    }

    record CapitalControls(String isolatedAccountScope,
                           String capitalPolicySha256,
                           AccountIsolation accountIsolation,
                           WithdrawalPermission withdrawalPermission,
                           String valuationCurrency,
                           BigDecimal maxGrossNotional,
                           BigDecimal maxDailyLoss,
                           Duration capabilityTtl) implements VerifiedControlPayload {
        public CapitalControls {
            isolatedAccountScope = text(isolatedAccountScope, "isolatedAccountScope", 192);
            capitalPolicySha256 = hash(capitalPolicySha256, "capitalPolicySha256");
            accountIsolation = Objects.requireNonNull(accountIsolation, "accountIsolation");
            withdrawalPermission = Objects.requireNonNull(
                    withdrawalPermission, "withdrawalPermission");
            valuationCurrency = text(valuationCurrency, "valuationCurrency", 16)
                    .toUpperCase(Locale.ROOT);
            maxGrossNotional = ReadinessValues.positive(
                    maxGrossNotional, "maxGrossNotional");
            maxDailyLoss = ReadinessValues.positive(maxDailyLoss, "maxDailyLoss");
            capabilityTtl = Objects.requireNonNull(capabilityTtl, "capabilityTtl");
            if (capabilityTtl.isZero() || capabilityTtl.isNegative()) {
                throw new IllegalArgumentException("capabilityTtl must be positive");
            }
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.CAPITAL_CONTROLS;
        }
    }

    record OwnerVenueContract(String venue,
                              String ownerAccountScope,
                              String contractReportSha256,
                              int passedCases,
                              int failedCases) implements VerifiedControlPayload {
        public OwnerVenueContract {
            venue = text(venue, "venue", 64).toUpperCase(Locale.ROOT);
            ownerAccountScope = text(ownerAccountScope, "ownerAccountScope", 192);
            contractReportSha256 = hash(contractReportSha256, "contractReportSha256");
            nonNegative(passedCases, "passedCases");
            nonNegative(failedCases, "failedCases");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.OWNER_VENUE_CONTRACT;
        }
    }

    record CrossMarketState(String stateEvidenceSha256,
                            Map<MarketRegime, Long> observationsByRegime,
                            Instant windowStartedAt,
                            Instant windowEndedAt,
                            long tracedOrders,
                            long unexplainedEvents) implements VerifiedControlPayload {
        public CrossMarketState {
            stateEvidenceSha256 = hash(stateEvidenceSha256, "stateEvidenceSha256");
            EnumMap<MarketRegime, Long> normalized = new EnumMap<>(MarketRegime.class);
            if (observationsByRegime != null) normalized.putAll(observationsByRegime);
            normalized.forEach((regime, observations) -> {
                Objects.requireNonNull(regime, "market regime");
                if (observations == null || observations < 0) {
                    throw new IllegalArgumentException(
                            "market regime observations must not be negative");
                }
            });
            observationsByRegime = Map.copyOf(normalized);
            windowStartedAt = Objects.requireNonNull(windowStartedAt, "windowStartedAt");
            windowEndedAt = Objects.requireNonNull(windowEndedAt, "windowEndedAt");
            nonNegative(tracedOrders, "tracedOrders");
            nonNegative(unexplainedEvents, "unexplainedEvents");
        }

        @Override
        public LongTermControl control() {
            return LongTermControl.CROSS_MARKET_STATE;
        }
    }

    private static String text(String value, String name, int maxLength) {
        return ReadinessValues.required(value, name, maxLength);
    }

    private static String hash(String value, String name) {
        return ReadinessValues.hash(value, name);
    }

    private static void nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }
}
