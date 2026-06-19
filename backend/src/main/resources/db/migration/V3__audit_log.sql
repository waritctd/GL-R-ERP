SET search_path = hr, public;

-- Append-only audit trail for accountability (HR/PDPA). Records who changed what,
-- with before/after snapshots. Rows are immutable: no UPDATE/DELETE path exists in
-- the application, and a trigger blocks those operations at the database level too.
CREATE TABLE IF NOT EXISTS hr.audit_log (
    audit_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id  BIGINT REFERENCES hr.app_user(user_id) ON DELETE SET NULL,
    action         VARCHAR(60) NOT NULL,
    entity         VARCHAR(60) NOT NULL,
    entity_id      VARCHAR(80),
    before_json    JSONB,
    after_json     JSONB,
    at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON hr.audit_log(entity, entity_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON hr.audit_log(actor_user_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_at ON hr.audit_log(at DESC);

-- Enforce immutability at the database layer.
CREATE OR REPLACE FUNCTION hr.audit_log_block_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'hr.audit_log is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_log_no_update ON hr.audit_log;
CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE OR DELETE ON hr.audit_log
    FOR EACH ROW EXECUTE FUNCTION hr.audit_log_block_mutation();
