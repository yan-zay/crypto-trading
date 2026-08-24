/**
 * Deterministic, read-only portfolio analytics.
 *
 * <p>The package turns caller-supplied point-in-time exposures, returns, stress shocks, market
 * capacity facts, OMS fills and marks into immutable reports. It deliberately contains no Spring
 * component, mapper, transaction, order gateway or risk-engine mutation. Consequently, an
 * analytics result is evidence for research/review only and can never authorize an order or
 * replace durable hard-risk reservations.
 *
 * <p>TCA uses a positive-cost convention: BUY cost is {@code fill-arrival}, SELL cost is
 * {@code arrival-fill}; implementation shortfall adds quote-converted fees and the end-mark
 * opportunity cost of unfilled quantity. Capacity uses an explicit square-root impact estimate
 * and reports which configured constraint binds. Portfolio/stress inputs require a content-
 * addressed COMPLETE point-in-time snapshot, one valuation currency, and the explicitly supported
 * linear quote-settled payoff. TCA requires contiguous same-unit market-volume intervals, fresh
 * point-in-time marks/FX facts, and reconciliation to the durable OMS order aggregate. Every
 * successful result is explicitly {@code RESEARCH_ONLY}.
 */
package com.tj.crypto.portfolio.analytics;
