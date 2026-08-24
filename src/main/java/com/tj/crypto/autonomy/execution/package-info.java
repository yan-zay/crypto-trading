/**
 * Asset-class-neutral, deterministic target-position planning boundary.
 *
 * <p>The package compiles exact, content-addressed target-position inputs into short-lived evidence.
 * Every {@code ExecutionCandidate} and {@code ExecutionPlan} is non-dispatchable by construction.
 * The planner performs no network, persistence, reservation, signing, account-permission or funds
 * transfer operation.
 *
 * <p><strong>NOT_WIRED:</strong> the authoritative PIT position/valuation producer, active capital
 * head re-resolution at consumption time, venue and broker adapters, owner-certified trading
 * calendars and capability evidence, cross-currency/inverse valuation, KMS/HSM signing, atomic OMS
 * reservation, UNKNOWN-order budget retention, reconciliation and final execution gate. Until those
 * external trust boundaries exist, this package is research evidence only and cannot authorize
 * live trading.
 */
package com.tj.crypto.autonomy.execution;
