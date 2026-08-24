package com.tj.crypto.autonomy.canary;

import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Content-addressed boundary preventing evidence or capital decisions from crossing accounts,
 * venues, universes, environments, or independently governed capital/risk policies.
 */
public record DeploymentScope(
        String accountId,
        String subaccountId,
        String venue,
        String instrumentUniverseSha256,
        String capitalPolicySha256,
        String riskPolicySha256,
        CanaryEnvironment environment,
        String contentSha256
) {
    public DeploymentScope {
        accountId = CanaryCanonical.required(accountId, "accountId");
        subaccountId = CanaryCanonical.required(subaccountId, "subaccountId");
        venue = CanaryCanonical.required(venue, "venue").toUpperCase(Locale.ROOT);
        instrumentUniverseSha256 = lowerRequired(instrumentUniverseSha256, "instrumentUniverseSha256");
        capitalPolicySha256 = lowerRequired(capitalPolicySha256, "capitalPolicySha256");
        riskPolicySha256 = lowerRequired(riskPolicySha256, "riskPolicySha256");
        environment = Objects.requireNonNull(environment, "environment");
        contentSha256 = lowerRequired(contentSha256, "contentSha256");
    }

    public static DeploymentScope create(String accountId,
                                         String subaccountId,
                                         String venue,
                                         String instrumentUniverseSha256,
                                         String capitalPolicySha256,
                                         String riskPolicySha256,
                                         CanaryEnvironment environment) {
        String canonicalVenue = venue.trim().toUpperCase(Locale.ROOT);
        String universe = instrumentUniverseSha256.trim().toLowerCase(Locale.ROOT);
        String capital = capitalPolicySha256.trim().toLowerCase(Locale.ROOT);
        String risk = riskPolicySha256.trim().toLowerCase(Locale.ROOT);
        String content = digest(accountId.trim(), subaccountId.trim(), canonicalVenue,
                universe, capital, risk, environment);
        return new DeploymentScope(accountId, subaccountId, canonicalVenue, universe,
                capital, risk, environment, content);
    }

    public boolean contentAddressMatches() {
        return CanaryCanonical.isSha256(instrumentUniverseSha256)
                && CanaryCanonical.isSha256(capitalPolicySha256)
                && CanaryCanonical.isSha256(riskPolicySha256)
                && CanaryCanonical.isSha256(contentSha256)
                && contentSha256.equals(digest(accountId, subaccountId, venue,
                instrumentUniverseSha256, capitalPolicySha256, riskPolicySha256, environment));
    }

    private static String digest(String accountId,
                                 String subaccountId,
                                 String venue,
                                 String instrumentUniverseSha256,
                                 String capitalPolicySha256,
                                 String riskPolicySha256,
                                 CanaryEnvironment environment) {
        return CanaryCanonical.sha256(new StringJoiner("\n")
                .add("schema=canary-deployment-scope/v1")
                .add("accountId=" + accountId)
                .add("subaccountId=" + subaccountId)
                .add("venue=" + venue)
                .add("instrumentUniverseSha256=" + instrumentUniverseSha256)
                .add("capitalPolicySha256=" + capitalPolicySha256)
                .add("riskPolicySha256=" + riskPolicySha256)
                .add("environment=" + environment.name())
                .toString());
    }

    private static String lowerRequired(String value, String field) {
        return CanaryCanonical.required(value, field).toLowerCase(Locale.ROOT);
    }
}
