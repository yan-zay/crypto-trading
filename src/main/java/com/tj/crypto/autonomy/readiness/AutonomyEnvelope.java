package com.tj.crypto.autonomy.readiness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable limits that an L4 Agent may select within but can never enlarge or modify.
 *
 * <p>The gate only binds evidence to this envelope. Enforcement remains the responsibility of
 * the deterministic policy, risk, OMS and execution layers.</p>
 */
public record AutonomyEnvelope(
        String venue,
        String marketType,
        Set<String> instruments,
        Instant validFrom,
        Instant validUntil,
        String valuationCurrency,
        BigDecimal maxGrossNotional,
        BigDecimal maxDailyLoss,
        Duration capabilityTtl) {

    private static final Duration MAX_CAPABILITY_TTL = Duration.ofMinutes(2);

    public AutonomyEnvelope {
        venue = ReadinessValues.required(venue, "venue", 64).toUpperCase(Locale.ROOT);
        marketType = ReadinessValues.required(marketType, "marketType", 32)
                .toUpperCase(Locale.ROOT);
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        validUntil = Objects.requireNonNull(validUntil, "validUntil");
        valuationCurrency = ReadinessValues.required(
                valuationCurrency, "valuationCurrency", 16).toUpperCase(Locale.ROOT);
        capabilityTtl = Objects.requireNonNull(capabilityTtl, "capabilityTtl");
        maxGrossNotional = ReadinessValues.positive(maxGrossNotional, "maxGrossNotional");
        maxDailyLoss = ReadinessValues.positive(maxDailyLoss, "maxDailyLoss");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must follow validFrom");
        }
        if (capabilityTtl.isZero() || capabilityTtl.isNegative()
                || capabilityTtl.compareTo(Duration.between(validFrom, validUntil)) > 0
                || capabilityTtl.compareTo(MAX_CAPABILITY_TTL) > 0) {
            throw new IllegalArgumentException(
                    "capabilityTtl must fit inside the window and not exceed two minutes");
        }
        TreeSet<String> normalized = new TreeSet<>();
        if (instruments != null) {
            instruments.forEach(instrument -> normalized.add(ReadinessValues.required(
                    instrument, "instrument", 64).toUpperCase(Locale.ROOT)));
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("instruments are required");
        instruments = Set.copyOf(normalized);
    }

    public boolean isActiveAt(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    public String sha256() {
        List<String> components = new ArrayList<>();
        components.add(venue);
        components.add(marketType);
        components.addAll(new TreeSet<>(instruments));
        components.add(validFrom.toString());
        components.add(validUntil.toString());
        components.add(valuationCurrency);
        components.add(maxGrossNotional.toPlainString());
        components.add(maxDailyLoss.toPlainString());
        components.add(Long.toString(capabilityTtl.getSeconds()));
        components.add(Integer.toString(capabilityTtl.getNano()));
        return ReadinessHashing.sha256("autonomy-envelope-v1", components);
    }
}
