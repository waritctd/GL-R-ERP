CREATE TABLE IF NOT EXISTS hr.payroll_payslip_email_delivery (
    delivery_id      BIGSERIAL PRIMARY KEY,
    period_id        BIGINT NOT NULL REFERENCES hr.payroll_period(period_id) ON DELETE CASCADE,
    line_id          BIGINT NOT NULL REFERENCES hr.payroll_line(line_id) ON DELETE CASCADE,
    employee_id      BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    recipient_email  TEXT,
    status           TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    last_error       TEXT,
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payroll_payslip_email_delivery_line UNIQUE (period_id, line_id)
);

CREATE INDEX IF NOT EXISTS idx_payroll_payslip_email_delivery_period
    ON hr.payroll_payslip_email_delivery (period_id, status);
