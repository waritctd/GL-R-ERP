SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- OVERTIME: employee requests, approval state, attendance-backed minutes
-- ---------------------------------------------------------------------

CREATE TABLE hr.overtime_request (
    overtime_request_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    work_date           DATE NOT NULL,
    planned_start_at    TIMESTAMPTZ NOT NULL,
    planned_end_at      TIMESTAMPTZ NOT NULL,
    planned_minutes     INTEGER NOT NULL,
    day_type            VARCHAR(20) NOT NULL DEFAULT 'WORKDAY',
    pay_rate_multiplier NUMERIC(4,2) NOT NULL DEFAULT 1.50,
    reason              TEXT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    actual_start_at     TIMESTAMPTZ,
    actual_end_at       TIMESTAMPTZ,
    actual_minutes      INTEGER NOT NULL DEFAULT 0,
    payable_minutes     INTEGER NOT NULL DEFAULT 0,
    calculation_note    TEXT,
    payroll_month       DATE NOT NULL,
    requested_by_id     BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by_id      BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    reviewed_at         TIMESTAMPTZ,
    reviewer_note       TEXT,
    cancelled_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_overtime_status CHECK (
        status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    CONSTRAINT chk_overtime_day_type CHECK (day_type IN ('WORKDAY', 'HOLIDAY')),
    CONSTRAINT chk_overtime_multiplier CHECK (pay_rate_multiplier IN (1.50, 3.00)),
    CONSTRAINT chk_overtime_planned_order CHECK (planned_end_at > planned_start_at),
    CONSTRAINT chk_overtime_actual_order CHECK (
        actual_start_at IS NULL OR actual_end_at IS NULL OR actual_end_at >= actual_start_at
    ),
    CONSTRAINT chk_overtime_minutes_nonnegative CHECK (
        planned_minutes > 0
        AND actual_minutes >= 0
        AND payable_minutes >= 0
    ),
    CONSTRAINT chk_overtime_payroll_month_first CHECK (
        payroll_month = date_trunc('month', payroll_month)::date
    ),
    CONSTRAINT chk_overtime_reason_nonblank CHECK (btrim(reason) <> '')
);

CREATE INDEX idx_overtime_employee_date
    ON hr.overtime_request(employee_id, work_date DESC);

CREATE INDEX idx_overtime_status_date
    ON hr.overtime_request(status, work_date DESC);

CREATE INDEX idx_overtime_payroll
    ON hr.overtime_request(payroll_month, status);
