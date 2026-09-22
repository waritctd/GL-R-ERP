-- Self-service "forgot password" (ลืมรหัสผ่าน) — single-use, expiring tokens that let an
-- employee reset their own password via an emailed link, without HR intervention.
--
-- POST /api/auth/forgot-password issues a token (30-minute expiry) and emails a link containing
-- the RAW token; POST /api/auth/reset-password consumes it. Only the SHA-256 hex digest of the
-- raw token is ever persisted (token_hash) — the raw value lives only in the email and the
-- caller's browser, never in this table, so a database read alone can never produce a usable
-- token. A fast deterministic hash (not BCrypt) is correct here: this is an indexed exact-match
-- lookup over a high-entropy 32-byte SecureRandom value, not a low-entropy human password.
--
-- Reachable only for employees with an email on file (hr.employee.email is nullable; V158's own
-- comment records ~half of prod's employees have none) — an accepted, intentional gap. The
-- existing HR admin reset (POST /api/employees/{id}/reset-password, EmployeeAuthRepository
-- #setTemporaryPassword) remains the fallback for everyone else; this migration does not touch it.
CREATE TABLE hr.password_reset_token (
    id                      BIGSERIAL PRIMARY KEY,
    employee_id             BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    token_hash              VARCHAR(64) NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    used_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exact-match lookup on the hash at reset time (findValidByTokenHash) — the only read path.
CREATE UNIQUE INDEX uq_password_reset_token_hash ON hr.password_reset_token(token_hash);

-- Partial index: only unused (open) tokens matter for the cooldown check
-- (mostRecentOpenTokenCreatedAt) and for invalidating outstanding tokens on a fresh request
-- (invalidateOutstanding) — both scan by employee_id, never by the (already unique) hash.
CREATE INDEX idx_password_reset_token_employee_open ON hr.password_reset_token(employee_id) WHERE used_at IS NULL;

COMMENT ON TABLE hr.password_reset_token IS 'Single-use, expiring tokens for the self-service forgot-password flow (POST /api/auth/forgot-password, POST /api/auth/reset-password). token_hash is the SHA-256 hex digest of the raw token emailed to the employee -- the raw token itself is never persisted.';
