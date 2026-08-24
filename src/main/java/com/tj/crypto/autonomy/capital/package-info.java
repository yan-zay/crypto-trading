/**
 * Asset-class-neutral deterministic capital policy primitives.
 *
 * <p>This package verifies pinned Ed25519 signatures, validates multi-account capital conservation,
 * resolves exact account/venue/instrument-specification/strategy envelopes, and proves that an
 * emergency revision only reduces risk. It deliberately has no Spring, database, OMS, signer,
 * gateway or Agent dependency.
 *
 * <p>A resolved envelope is not a trading permit. The resolver port is mandatory, but its durable
 * active-head/revocation/CAS implementation, atomic
 * budget reservation/release (including UNKNOWN outcomes), trusted point-in-time positions and
 * prices, valuation/conversion, open-interest units and observations, market-calendar evidence,
 * persistence, recovery and live gateway integration remain NOT_WIRED and must fail closed at a
 * future integration boundary.
 */
package com.tj.crypto.autonomy.capital;
