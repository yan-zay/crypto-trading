-- Configuration publishing must have exactly one ACTIVE row per logical key.
-- NULL values do not collide in MySQL UNIQUE indexes, so only ACTIVE rows expose a scope key.
ALTER TABLE admin_config_version
    ADD COLUMN active_scope VARCHAR(130)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'active' THEN CONCAT(config_type, ':', config_key) ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_admin_config_single_active (active_scope),
    ADD CONSTRAINT chk_admin_config_checksum
        CHECK (checksum IS NOT NULL AND checksum REGEXP '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_admin_config_status
        CHECK (status IN ('draft','validated','published','active','rolled_back','archived'));

-- Existing signal rows are the durable command facts. Prevent rewriting or deleting their
-- strategy, release, instrument, decision or factor payload after downstream Paper delivery.
DELIMITER $$
CREATE TRIGGER trg_signal_event_no_update
BEFORE UPDATE ON signal_event
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'signal_event is append-only';
END$$

CREATE TRIGGER trg_signal_event_no_delete
BEFORE DELETE ON signal_event
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'signal_event is append-only';
END$$
DELIMITER ;
