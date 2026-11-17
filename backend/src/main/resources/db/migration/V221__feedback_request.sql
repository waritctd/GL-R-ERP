-- V221: "แจ้งปัญหา / ขอฟีเจอร์" requests raised by staff from the มีอะไรใหม่ page and decided by the admin
-- (hr.employee.is_admin). Additive: a new table, nothing existing changes. The reporter's name / department / role
-- are a snapshot taken by the server at submit time, never read from the client.
CREATE TABLE IF NOT EXISTS hr.feedback_request (
    feedback_id            BIGSERIAL PRIMARY KEY,
    kind                   TEXT NOT NULL CHECK (kind IN ('BUG', 'FEATURE')),
    area                   TEXT NOT NULL,
    details                TEXT NOT NULL CHECK (char_length(details) BETWEEN 10 AND 4000),
    extra                  TEXT CHECK (char_length(extra) <= 4000),
    impact                 TEXT,
    page_path              TEXT CHECK (char_length(page_path) <= 300),
    user_agent             TEXT CHECK (char_length(user_agent) <= 300),
    viewport               TEXT CHECK (char_length(viewport) <= 30),
    reporter_employee_id   BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    reporter_name          TEXT NOT NULL,
    reporter_department    TEXT,
    reporter_role          TEXT NOT NULL,
    status                 TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'IN_PROGRESS', 'TESTING', 'RELEASED', 'DENIED')),
    eta                    DATE,
    deny_reason            TEXT CHECK (char_length(deny_reason) <= 2000),
    decided_by             BIGINT REFERENCES hr.employee(employee_id),
    decided_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT feedback_request_denied_has_reason CHECK (status <> 'DENIED' OR deny_reason IS NOT NULL),
    CONSTRAINT feedback_request_accepted_has_eta
        CHECK (status NOT IN ('ACCEPTED', 'IN_PROGRESS', 'TESTING', 'RELEASED') OR eta IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_feedback_request_reporter ON hr.feedback_request (reporter_employee_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_request_status ON hr.feedback_request (status, created_at DESC);
