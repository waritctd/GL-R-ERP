SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE -> PAYROLL: Thai-labour-law unpaid-day deduction (2026-07-23)
-- ---------------------------------------------------------------------
--
-- CAVEAT (needs HR/legal sign-off before this drives a REAL payroll run): the rule encoded here --
-- "days beyond the existing SICK<=30 / PERSONAL>=3 / VACATION>=6 statutory quota are unpaid,
-- deducted at base/30 per unpaid WORKING day (Mon-Fri only, no holiday calendar, whole days only)" --
-- and "a leave request's paid quota is consumed from its own earliest working days first" are
-- company-policy choices this migration + the accompanying LeaveService change encode, not something
-- this migration itself certifies as correct for GL&R's actual, final policy. Flag for HR/legal
-- review before this reaches a live payroll run. See LeaveService#submit and LeaveDayMath.

-- 1) Split total_days into what quota actually covered vs. what went unpaid. Every existing
--    APPROVED/CANCELLED row was approved under the OLD gate, which never let an approval through
--    unless quota fully covered it -- so 100% of their days were paid; backfill accordingly.
--    AUTO_REJECTED/REJECTED/SUBMITTED rows never consumed any days and keep the 0/0 default.
ALTER TABLE hr.leave_request
    ADD COLUMN paid_days   NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN unpaid_days NUMERIC(5,2) NOT NULL DEFAULT 0;

UPDATE hr.leave_request
   SET paid_days = total_days,
       unpaid_days = 0
 WHERE status IN ('APPROVED', 'CANCELLED');

-- The sum invariant only holds once a decision has actually been made (APPROVED/CANCELLED): a
-- SUBMITTED (pending) request has a real total_days but an undetermined paid/unpaid split, and
-- REJECTED/AUTO_REJECTED requests never had any days granted at all -- both legitimately keep the
-- 0/0 default regardless of total_days. Demo seed data includes exactly this case (a pending SICK
-- request), which is what first caught this constraint being too strict.
ALTER TABLE hr.leave_request
    ADD CONSTRAINT chk_leave_paid_unpaid_nonnegative CHECK (paid_days >= 0 AND unpaid_days >= 0),
    ADD CONSTRAINT chk_leave_paid_unpaid_sum CHECK (
        status NOT IN ('APPROVED', 'CANCELLED') OR paid_days + unpaid_days = total_days
    );

-- 2) New leave type: always-unpaid from day 1 (0-day statutory quota). Requires relaxing the
--    quota-must-be-positive check to quota-must-be-nonnegative.
ALTER TABLE hr.leave_type
    DROP CONSTRAINT chk_leave_type_quota_positive,
    ADD CONSTRAINT chk_leave_type_quota_nonnegative CHECK (annual_quota_days >= 0);

INSERT INTO hr.leave_type (leave_type_code, name_th, name_en, annual_quota_days, requires_attachment)
VALUES ('LEAVE_WITHOUT_PAY', 'ลาไม่รับค่าจ้าง', 'Leave without pay', 0.00, FALSE)
ON CONFLICT (leave_type_code) DO NOTHING;

-- 3) Cancel-after-close reversal. When an APPROVED leave with unpaid_days > 0 is cancelled AFTER
--    payroll for an overlapping month has already been PROCESSED, that unpaid-day deduction already
--    landed in the employee's net pay for a closed period and cannot be undone in place. This table
--    is the auditable record of the credit still owed. LeaveService#cancel populates it (write-only,
--    never resolves anything itself); PayrollService#suggestedInputs surfaces the unresolved total
--    as an early heads-up (independent of any run); PayrollService#preview/#process (V86,
--    AUTO-REFUND, 2026-07-23 -- see that migration) auto-apply the credit as a real pre-tax refund
--    the next time payroll runs for this employee, and #process sets `resolved_at` /
--    `resolved_payroll_period_id` in the same transaction it applies the refund in.
CREATE TABLE hr.leave_payroll_correction (
    correction_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    leave_request_id           BIGINT NOT NULL REFERENCES hr.leave_request(leave_request_id) ON DELETE CASCADE,
    employee_id                BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    payroll_month               DATE NOT NULL,
    unpaid_days_to_refund      NUMERIC(5,2) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at                 TIMESTAMPTZ,
    resolved_payroll_period_id BIGINT REFERENCES hr.payroll_period(period_id) ON DELETE SET NULL,
    CONSTRAINT chk_leave_payroll_correction_days_positive CHECK (unpaid_days_to_refund > 0),
    CONSTRAINT chk_leave_payroll_correction_month_first CHECK (payroll_month = date_trunc('month', payroll_month)::date)
);

CREATE INDEX idx_leave_payroll_correction_employee
    ON hr.leave_payroll_correction(employee_id, resolved_at);

CREATE INDEX idx_leave_payroll_correction_leave_request
    ON hr.leave_payroll_correction(leave_request_id);
