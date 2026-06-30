SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: quota-backed employee leave requests and approvals
-- ---------------------------------------------------------------------

CREATE TABLE hr.leave_type (
    leave_type_code   VARCHAR(30) PRIMARY KEY,
    name_th           VARCHAR(120) NOT NULL,
    name_en           VARCHAR(120) NOT NULL,
    annual_quota_days NUMERIC(5,2) NOT NULL,
    requires_attachment BOOLEAN NOT NULL DEFAULT FALSE,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_leave_type_quota_positive CHECK (annual_quota_days > 0),
    CONSTRAINT chk_leave_type_code_upper CHECK (leave_type_code = upper(leave_type_code))
);

INSERT INTO hr.leave_type (
    leave_type_code, name_th, name_en, annual_quota_days, requires_attachment
) VALUES
    ('SICK', 'ลาป่วย', 'Sick leave', 30.00, TRUE),
    ('VACATION', 'ลาพักร้อน', 'Vacation leave', 6.00, FALSE),
    ('PERSONAL', 'ลากิจ', 'Personal leave', 3.00, FALSE)
ON CONFLICT (leave_type_code) DO NOTHING;

CREATE TABLE hr.leave_request (
    leave_request_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id           BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    leave_type_code       VARCHAR(30) NOT NULL REFERENCES hr.leave_type(leave_type_code),
    start_date            DATE NOT NULL,
    end_date              DATE NOT NULL,
    total_days            NUMERIC(5,2) NOT NULL,
    quota_year            SMALLINT NOT NULL,
    reason                TEXT NOT NULL,
    attachment_name       VARCHAR(255),
    attachment_url        TEXT,
    status                VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    quota_remaining_before NUMERIC(5,2) NOT NULL,
    quota_remaining_after  NUMERIC(5,2) NOT NULL,
    system_note           TEXT,
    requested_by_id       BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by_id        BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    reviewed_at           TIMESTAMPTZ,
    reviewer_note         TEXT,
    cancelled_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_leave_status CHECK (
        status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'AUTO_REJECTED')
    ),
    CONSTRAINT chk_leave_date_order CHECK (end_date >= start_date),
    CONSTRAINT chk_leave_total_positive CHECK (total_days > 0),
    CONSTRAINT chk_leave_quota_year CHECK (quota_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_leave_reason_nonblank CHECK (btrim(reason) <> ''),
    CONSTRAINT chk_leave_quota_remaining_nonnegative CHECK (
        quota_remaining_before >= 0 AND quota_remaining_after >= 0
    )
);

CREATE INDEX idx_leave_request_employee_date
    ON hr.leave_request(employee_id, start_date DESC, end_date DESC);

CREATE INDEX idx_leave_request_status_date
    ON hr.leave_request(status, start_date DESC);

CREATE INDEX idx_leave_request_quota
    ON hr.leave_request(employee_id, leave_type_code, quota_year, status);
