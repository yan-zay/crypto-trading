/**
 * Content-addressed order-decision lineage contracts for long-term auditability.
 *
 * <p>This package is deliberately {@code NOT_WIRED}. It neither dispatches orders nor creates a
 * gateway permit. The default persistence, verification and admission ports fail closed until
 * independently trusted implementations are supplied and wired at a deterministic boundary.</p>
 */
package com.tj.crypto.autonomy.lineage;
