-- Request-level activity log, plus the admin capability that gates reading it.
--
-- WHY A REQUEST LOG AND NOT MORE hr.audit_log ROWS: hr.audit_log is semantic — one row per
-- meaningful business action, with before/after JSON — and it only covers the 69 call sites that
-- remembered to write one, out of 181 mutating endpoints and 295 endpoints total. It can never
-- answer "what did this employee actually do today", because reads leave no trace at all and any
-- new endpoint is unaudited until someone adds a call. This table answers that question by
-- construction: the filter sits in front of every /api/ request, so no endpoint can escape it,
-- including ones added later. The two are complements, not substitutes — keep both.

CREATE TABLE hr.activity_log (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL for anonymous traffic: the four SecurityConfig exceptions (login, punch, health,
    -- OPTIONS preflight) and any request whose session had already expired.
    employee_id BIGINT      REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    actor_email TEXT,                       -- denormalized so a later employee delete keeps the trail readable
    method      TEXT        NOT NULL,
    path        TEXT        NOT NULL,       -- request URI only; the query string is deliberately dropped (see below)
    status      SMALLINT    NOT NULL,
    duration_ms INTEGER,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "Everything today", newest first, is the query this exists to serve.
CREATE INDEX idx_activity_log_at          ON hr.activity_log (at DESC);
-- "Everything one person did", the second question anyone asks.
CREATE INDEX idx_activity_log_employee_at ON hr.activity_log (employee_id, at DESC);

COMMENT ON TABLE hr.activity_log IS
    'One row per /api/ request. Written by ActivityLogFilter, best-effort and asynchronous: a '
    'failure here must never fail the request it describes. Query strings and request bodies are '
    'never stored, because both carry credentials and PII (POST /api/auth/login alone would '
    'otherwise capture every password in plaintext).';

-- The admin capability.
--
-- Deliberately a CAPABILITY, not a role. DivisionAccessPolicy.roleFor derives exactly one role
-- string from the employee's division and position, so making someone "admin" there would REPLACE
-- their existing role — วริศรา is division SA / role `sales`, and turning her into `admin` would
-- silently strip her sales access, her division-scoped rows and her commission-rep eligibility.
-- An orthogonal flag adds the new permission without taking anything away. Note V46 removed the
-- previous attempt at this, which modelled admin as a DIVISION (source_code 'ADMIN') and had the
-- same defect.
ALTER TABLE hr.employee ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN hr.employee.is_admin IS
    'Grants the cross-employee activity log. Orthogonal to the derived role, never a substitute '
    'for it. Read live per request, so revoking it takes effect immediately rather than at the '
    'holder''s next login.';

-- Granted to วริศรา จันทเดช only, matched on employee_code rather than employee_id (ids differ
-- between prod/uat/demo) and rather than email (five sales reps were re-addressed on 2026-08-30,
-- so email is the less stable identifier of the two). Affects 0 rows anywhere the code is absent,
-- which is the correct outcome for UAT and the demo showcase.
UPDATE hr.employee SET is_admin = TRUE WHERE employee_code = 'GLR-1001';
