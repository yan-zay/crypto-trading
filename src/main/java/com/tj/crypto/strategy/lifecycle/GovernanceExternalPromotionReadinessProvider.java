package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.governance.evidence.ControlKind;
import com.tj.crypto.governance.evidence.GovernanceSubject;
import com.tj.crypto.governance.evidence.MediumTermReadinessGate;
import com.tj.crypto.governance.evidence.ReadinessDecision;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Strongly typed bridge from lifecycle promotion to independently verified governance evidence. */
@Component
final class GovernanceExternalPromotionReadinessProvider
        implements ExternalPromotionReadinessProvider {
    private final MediumTermReadinessGate readinessGate;

    GovernanceExternalPromotionReadinessProvider(MediumTermReadinessGate readinessGate) {
        this.readinessGate = readinessGate;
    }

    @Override
    public ExternalPromotionReadiness assess(GovernanceSubject subject, Instant now) {
        if (subject == null) {
            return denied(now, "exact GovernanceSubject is missing");
        }
        ReadinessDecision decision = readinessGate.evaluate(subject, now.toEpochMilli());
        if (!decision.ready()) {
            return new ExternalPromotionReadiness(false, null, now, now, decision.blockers());
        }
        String accepted = decision.acceptedEvidence().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        String digest = Hashes.sha256("CRYPTO_TRADING_GOVERNANCE_READINESS_V1\n"
                + subject.sha256() + "\n" + accepted);
        // The underlying gate is re-evaluated on every transition/current-state read. This very
        // short snapshot cannot be cached as a durable substitute for the source evidence TTLs.
        return new ExternalPromotionReadiness(true, digest, now, now.plusMillis(1), java.util.List.of());
    }

    private ExternalPromotionReadiness denied(Instant now, String blocker) {
        return new ExternalPromotionReadiness(false, null, now, now, java.util.List.of(blocker));
    }
}
