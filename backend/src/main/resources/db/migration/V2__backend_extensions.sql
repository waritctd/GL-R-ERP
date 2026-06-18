SET search_path = hr, public;

CREATE TABLE IF NOT EXISTS hr.employee_emergency_contact (
    employee_id   BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    full_name     VARCHAR(200),
    relationship  VARCHAR(80),
    phone         VARCHAR(40),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS hr.profile_change_request (
    request_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id           BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    field_key             VARCHAR(40) NOT NULL,
    field_label           VARCHAR(120) NOT NULL,
    old_value             TEXT,
    new_value             TEXT NOT NULL,
    requested_by_user_id  BIGINT REFERENCES hr.app_user(user_id) ON DELETE SET NULL,
    requested_by_name     VARCHAR(200),
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    status                VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'rejected')),
    reviewed_by_user_id   BIGINT REFERENCES hr.app_user(user_id) ON DELETE SET NULL,
    reviewed_at           TIMESTAMPTZ,
    reviewer_note         TEXT
);

CREATE INDEX IF NOT EXISTS idx_profile_request_employee ON hr.profile_change_request(employee_id);
CREATE INDEX IF NOT EXISTS idx_profile_request_status ON hr.profile_change_request(status, requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_app_user_enabled ON hr.app_user(is_enabled);
CREATE INDEX IF NOT EXISTS idx_role_name ON hr.role(name);
