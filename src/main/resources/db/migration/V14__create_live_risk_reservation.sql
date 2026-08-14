-- Durable gross-notional reservations close the cross-JVM pre-trade check/insert race.
-- Every risk-increasing live order is serialized by account + exchange before it can be
-- committed to the OMS. The exchange still does not accept our database fencing token;
-- client_order_id remains the venue-side idempotency key for that external boundary.
CREATE TABLE live_risk_budget_scope (
    account_id VARCHAR(64) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    scope_version BIGINT NOT NULL DEFAULT 0,
    last_reserved_at_ms BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, exchange)
) ENGINE=InnoDB COMMENT='Serializes live gross-notional reservations per account and exchange';

CREATE TABLE live_risk_reservation (
    order_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    market_type VARCHAR(20) NOT NULL,
    symbol VARCHAR(40) NOT NULL,
    risk_increasing TINYINT(1) NOT NULL,
    original_quantity DECIMAL(38,18) NOT NULL,
    remaining_quantity DECIMAL(38,18) NOT NULL,
    reference_price DECIMAL(38,18) NOT NULL,
    original_notional DECIMAL(38,18) NOT NULL,
    remaining_notional DECIMAL(38,18) NOT NULL,
    reservation_status VARCHAR(20) NOT NULL COMMENT 'ACTIVE/UNKNOWN/RELEASED/UNVALUED',
    last_order_status VARCHAR(30) NOT NULL,
    snapshot_event_time_ms BIGINT NOT NULL,
    released_at_ms BIGINT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_live_risk_scope_status
        (account_id, exchange, reservation_status),
    INDEX idx_live_risk_symbol_status
        (account_id, exchange, market_type, symbol, reservation_status)
) ENGINE=InnoDB COMMENT='Durable remaining gross notional for non-terminal live orders';

-- Upgrade safety: serialize and account for any live orders already active at migration time.
INSERT IGNORE INTO live_risk_budget_scope
    (account_id, exchange, scope_version, last_reserved_at_ms)
SELECT DISTINCT account_id, UPPER(exchange), 0,
       CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
FROM oms_order
WHERE order_source='LIVE'
  AND account_id IS NOT NULL AND account_id <> ''
  AND status IN ('PENDING','CREATED','SUBMITTED','ACKNOWLEDGED',
                 'PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN');

INSERT IGNORE INTO live_risk_reservation
    (order_id, account_id, exchange, market_type, symbol, risk_increasing,
     original_quantity, remaining_quantity, reference_price,
     original_notional, remaining_notional, reservation_status,
     last_order_status, snapshot_event_time_ms)
SELECT order_id,
       account_id,
       UPPER(exchange),
       UPPER(market_type),
       UPPER(symbol),
       IF(reduce_only, 0, 1),
       quantity,
       GREATEST(quantity - COALESCE(filled_quantity, 0), 0),
       COALESCE(NULLIF(price, 0), NULLIF(avg_fill_price, 0), 0),
       IF(reduce_only, 0,
          quantity * COALESCE(NULLIF(price, 0), NULLIF(avg_fill_price, 0), 0)),
       IF(reduce_only, 0,
          GREATEST(quantity - COALESCE(filled_quantity, 0), 0)
              * COALESCE(NULLIF(price, 0), NULLIF(avg_fill_price, 0), 0)),
       CASE
           WHEN NOT reduce_only
                AND COALESCE(NULLIF(price, 0), NULLIF(avg_fill_price, 0), 0) <= 0
               THEN 'UNVALUED'
           WHEN status='UNKNOWN' THEN 'UNKNOWN'
           ELSE 'ACTIVE'
       END,
       status,
       COALESCE(last_event_at_ms, created_at_ms)
FROM oms_order
WHERE order_source='LIVE'
  AND account_id IS NOT NULL AND account_id <> ''
  AND status IN ('PENDING','CREATED','SUBMITTED','ACKNOWLEDGED',
                 'PARTIALLY_FILLED','CANCEL_REQUESTED','UNKNOWN');
