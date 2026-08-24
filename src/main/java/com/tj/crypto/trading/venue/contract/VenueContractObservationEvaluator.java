package com.tj.crypto.trading.venue.contract;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, fail-closed evaluator for redacted venue observations. */
final class VenueContractObservationEvaluator {
    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1_000;

    Evaluation evaluate(VenueContractObservation observation) {
        List<String> blockers = new ArrayList<>();
        switch (observation.scenario()) {
            case ACCOUNT_AND_PERMISSION_BOUNDARY -> requireFacts(observation, blockers,
                    "productMatched", "accountModeMatched", "marginModeMatched",
                    "permissionsLeastPrivilegeVerified", "withdrawalDisabledVerified");
            case PLACE_QUERY_CANCEL -> {
                requireActionSubsequence(observation, blockers,
                        expected(VenueContractAction.Type.PLACE,
                                VenueContractAction.Outcome.ACCEPTED),
                        expected(VenueContractAction.Type.QUERY,
                                VenueContractAction.Outcome.KNOWN),
                        expected(VenueContractAction.Type.CANCEL,
                                VenueContractAction.Outcome.CANCELLED),
                        expected(VenueContractAction.Type.QUERY,
                                VenueContractAction.Outcome.CANCELLED));
                requireFacts(observation, blockers, "sameClientOrderId", "finalStateCancelled");
            }
            case PARTIAL_FILL -> requireFacts(observation, blockers,
                    "partialFillObserved", "cumulativeQuantityMonotonic", "fillDeduplicated");
            case PRECISION_AND_MIN_NOTIONAL -> requireFacts(observation, blockers,
                    "invalidPrecisionRejected", "belowMinimumRejected", "validBoundaryAccepted",
                    "filtersVersioned");
            case RATE_LIMIT -> requireFacts(observation, blockers,
                    "rateLimitObserved", "retryAfterHonored", "boundedBackoff",
                    "requestBudgetEnforced");
            case ERROR_MAPPING -> requireFacts(observation, blockers,
                    "authRejected", "validationRejected", "throttledMapped",
                    "ambiguousServerFailureUnknown", "messagesRedacted");
            case CLOCK_SKEW -> requireFacts(observation, blockers,
                    "skewDetected", "requestBlockedUntilSynchronized",
                    "postSyncWithinTolerance");
            case PRIVATE_STREAM_RECONNECT -> {
                requireActionSubsequence(observation, blockers,
                        expected(VenueContractAction.Type.STREAM_DISCONNECT,
                                VenueContractAction.Outcome.DISCONNECTED),
                        expected(VenueContractAction.Type.STREAM_RECONNECT,
                                VenueContractAction.Outcome.CONNECTED),
                        expected(VenueContractAction.Type.BACKFILL,
                                VenueContractAction.Outcome.COMPLETED));
                requireFacts(observation, blockers, "disconnectDetected", "reconnected",
                        "backfillTriggered", "listenKeyRenewedOrNotApplicable");
            }
            case DUPLICATE_AND_DELAYED_EVENTS -> requireFacts(observation, blockers,
                    "duplicateDeduplicated", "delayedDetected", "eventOrderPreserved",
                    "gapFrozenOrBackfilled");
            case UNKNOWN_QUERY_ONLY -> evaluateUnknownQueryOnly(observation, blockers);
            case ACTIVE_ORDERS, RECENT_FILLS, BALANCES, POSITIONS -> requireFacts(
                    observation, blockers, "capabilitySupported", "paginationCovered",
                    "emptyDistinguishedFromUnsupported");
            case PRIVATE_STREAM_SEVEN_DAY_STABILITY -> {
                if (observation.observedDurationMs() < SEVEN_DAYS_MS) {
                    blockers.add("A real seven-day wall-clock observation was not supplied");
                }
                requireFacts(observation, blockers, "wallClockElapsed", "noUnexplainedGaps",
                        "noUnboundedGrowth", "reconciliationStable");
            }
            case PROFILE_BOUNDARY -> blockers.add("PROFILE_BOUNDARY is not a probe observation");
        }
        return new Evaluation(blockers.isEmpty(), List.copyOf(blockers));
    }

    private void evaluateUnknownQueryOnly(VenueContractObservation observation,
                                          List<String> blockers) {
        List<VenueContractAction> actions = observation.actions();
        int ambiguousPlaceIndex = -1;
        int placeCount = 0;
        for (int i = 0; i < actions.size(); i++) {
            VenueContractAction action = actions.get(i);
            if (action.type() == VenueContractAction.Type.PLACE) {
                placeCount++;
                if (action.outcome() == VenueContractAction.Outcome.AMBIGUOUS) {
                    ambiguousPlaceIndex = i;
                }
            }
        }
        if (placeCount != 1 || ambiguousPlaceIndex < 0) {
            blockers.add("UNKNOWN scenario requires exactly one ambiguous place attempt");
        } else {
            if (ambiguousPlaceIndex == actions.size() - 1) {
                blockers.add("UNKNOWN scenario must query after the ambiguous place attempt");
            }
            for (int i = ambiguousPlaceIndex + 1; i < actions.size(); i++) {
                if (actions.get(i).type() != VenueContractAction.Type.QUERY) {
                    blockers.add("Only query is allowed after an ambiguous place attempt");
                    break;
                }
            }
        }
        requireFacts(observation, blockers, "clientOrderIdStable", "unresolvedRiskFrozen");
    }

    private void requireFacts(VenueContractObservation observation, List<String> blockers,
                              String... names) {
        for (String name : names) {
            if (!"true".equalsIgnoreCase(observation.facts().get(name))) {
                blockers.add("Missing verified fact: " + name);
            }
        }
    }

    private void requireActionSubsequence(VenueContractObservation observation,
                                          List<String> blockers,
                                          ExpectedAction... expected) {
        int cursor = 0;
        for (VenueContractAction action : observation.actions()) {
            if (cursor < expected.length && expected[cursor].matches(action)) cursor++;
        }
        if (cursor != expected.length) {
            blockers.add("Required action/outcome sequence was not observed");
        }
    }

    private ExpectedAction expected(VenueContractAction.Type type,
                                    VenueContractAction.Outcome outcome) {
        return new ExpectedAction(type, outcome);
    }

    private record ExpectedAction(VenueContractAction.Type type,
                                  VenueContractAction.Outcome outcome) {
        private boolean matches(VenueContractAction action) {
            return action.type() == type && action.outcome() == outcome;
        }
    }

    record Evaluation(boolean passed, List<String> blockers) {}
}
