-- Issue #4: append-only audit trail for mutating HR actions (HR / PDPA accountability).
-- Captures actor + before/after snapshots for employee edits and profile-request reviews.

CREATE TABLE hr.audit_log (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id BIGINT,                       -- UserPrincipal.id of the acting HR/admin (null if system)
    actor_email   TEXT,                         -- denormalized for readability if the account is later removed
    action        TEXT        NOT NULL,         -- e.g. CREATE_EMPLOYEE, UPDATE_EMPLOYEE, APPROVE_PROFILE_REQUEST
    entity        TEXT        NOT NULL,         -- e.g. employee, profile_request
    entity_id     BIGINT,                       -- primary key of the affected row
    before_json   JSONB,                        -- state prior to the change (null for creates)
    after_json    JSONB,                        -- state after the change (null for pure deletes)
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_entity ON hr.audit_log (entity, entity_id);
CREATE INDEX idx_audit_log_at     ON hr.audit_log (at);

-- Immutability (acceptance criterion): reject any UPDATE/DELETE/TRUNCATE so rows are append-only,
-- enforced at the DB level regardless of the connecting role.
CREATE OR REPLACE FUNCTION hr.audit_log_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'hr.audit_log is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_mutate
    BEFORE UPDATE OR DELETE ON hr.audit_log
    FOR EACH ROW EXECUTE FUNCTION hr.audit_log_immutable();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON hr.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION hr.audit_log_immutable();
