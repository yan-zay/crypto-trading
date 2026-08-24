/**
 * Pure, fail-closed decision models for champion/challenger shadow evaluation and bounded canaries.
 * This package deliberately owns no exchange credentials, order gateway, OMS mutation, or runtime
 * capital state persistence. A trusted external owner must apply content-addressed decisions
 * with an exact deployment-scope, run-state SHA and version compare-and-set, and may always choose
 * a stricter action. The read-only current-head verifier does not remove evaluate/apply TOCTOU;
 * no production applier or live gateway wiring is provided by this package.
 */
package com.tj.crypto.autonomy.canary;
