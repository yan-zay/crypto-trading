package com.tj.crypto.trading.reconciliation.truth;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Explicit support declaration; every capability has auditable evidence or a NO-GO reason. */
public record VenueTruthCapabilities(Map<VenueTruthCapability, Support> capabilities) {
    public VenueTruthCapabilities {
        if (capabilities == null) throw new IllegalArgumentException("capabilities are required");
        EnumMap<VenueTruthCapability, Support> normalized =
                new EnumMap<>(VenueTruthCapability.class);
        for (VenueTruthCapability capability : VenueTruthCapability.values()) {
            Support support = capabilities.get(capability);
            if (support == null) {
                throw new IllegalArgumentException("Missing truth capability declaration: " + capability);
            }
            normalized.put(capability, support);
        }
        capabilities = Map.copyOf(normalized);
    }

    public boolean supports(VenueTruthCapability capability) {
        return capabilities.get(capability).supported();
    }

    public String detail(VenueTruthCapability capability) {
        return capabilities.get(capability).detail();
    }

    public static VenueTruthCapabilities unsupportedAll(String reason) {
        return partial(EnumSet.noneOf(VenueTruthCapability.class), "", reason);
    }

    public static VenueTruthCapabilities supportedAll(String evidence) {
        return partial(EnumSet.allOf(VenueTruthCapability.class), evidence, "");
    }

    public static VenueTruthCapabilities partial(Set<VenueTruthCapability> supported,
                                                  String supportedEvidence,
                                                  String unsupportedReason) {
        Set<VenueTruthCapability> safeSupported = supported == null
                ? Set.of() : Set.copyOf(supported);
        EnumMap<VenueTruthCapability, Support> result =
                new EnumMap<>(VenueTruthCapability.class);
        for (VenueTruthCapability capability : VenueTruthCapability.values()) {
            boolean isSupported = safeSupported.contains(capability);
            result.put(capability, new Support(isSupported,
                    isSupported ? supportedEvidence : unsupportedReason));
        }
        return new VenueTruthCapabilities(result);
    }

    public record Support(boolean supported, String detail) {
        public Support {
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("Capability support detail is required");
            }
        }
    }
}
