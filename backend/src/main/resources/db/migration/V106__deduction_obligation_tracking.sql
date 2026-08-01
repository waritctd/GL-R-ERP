SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Deduction obligation tracking (issue #373, 2026-08-01): กยศ (student loan) and กรมบังคับคดี
-- (legal execution / court-ordered garnishment) deductions used to be a single retyped-every-month
-- NUMERIC(12,2) column on hr.payroll_line/hr.payroll_input_draft, with no total, no paid-to-date,
-- no remaining balance, no end date, and no source-document reference. A deduction silently
-- vanished on a payroll re-run unless HR remembered to retype it from memory -- this nearly caused
-- a real underpayment in July 2026 ("re-enter ราม's ฿1,000 student_loan_deduction or it's lost").
--
-- This migration adds the obligation record + remittance ledger the PR describes.
--
-- ⚠️ AUTHORISED PAYROLL BUSINESS-LOGIC CHANGE (stated per CLAUDE.md, see PR body): once an employee
-- has an obligation row for a kind, the monthly deduction fed into PayrollCalculator is DERIVED
-- from this record (clamped to the remaining instructed total) instead of the HR-typed per-run
-- field for that kind, and the draw-down auto-stops (deducts 0, never a partial overshoot) once the
-- instructed total is reached. See PayrollService#calculateLine and
-- DeductionObligationService#resolveMonthlyAmount.
--
-- instructed_total is DELIBERATELY nullable. The issue's own open questions to the owner include
-- whether กยศ notifies a fixed monthly amount or a total, and whether a LED order states a total, a
-- monthly rate, or both. An obligation with no stated total simply never auto-stops (see
-- DeductionObligationService#resolveMonthlyAmount) -- the total must always be entered from the
-- authority's own document, never inferred or defaulted to zero.
--
-- ม.76's 10%/20% aggregate deduction cap is DELIBERATELY NOT enforced here -- it binds only items
-- (2)-(5) of the itemised deduction order (union fees, cooperative/welfare debts, security/damages,
-- agreed savings); กยศ is item (1) territory under separate legislation (issue #373, "Important:
-- what not to do"; that cap is issue #376, a separate branch). ป.วิ.พ. ม.302's garnishment cap
-- (SALARY 30% / BONUS 50% / OVERTIME_OR_DILIGENCE 30% / SEVERANCE ฿300,000 floor) is ALREADY
-- enforced in PayrollCalculator#garnishmentDeduction via PayrollGarnishmentType and is UNCHANGED by
-- this migration -- this obligation's own remaining-balance clamp and that statutory cap compose
-- (both apply; whichever is smaller wins). Neither replaces the other.
-- ---------------------------------------------------------------------

CREATE TABLE hr.deduction_obligation (
    obligation_id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id                     BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    kind                            VARCHAR(20) NOT NULL
                                     CHECK (kind IN ('STUDENT_LOAN', 'LEGAL_EXECUTION')),
    -- The authority's instructed monthly figure. Always required -- an obligation with nothing to
    -- deduct each period is not yet an instruction, only a placeholder.
    monthly_instructed_amount       NUMERIC(12,2) NOT NULL CHECK (monthly_instructed_amount >= 0),
    -- Nullable BY DESIGN -- see migration header. NULL = no stated total, this obligation never
    -- auto-stops.
    instructed_total                NUMERIC(14,2) CHECK (instructed_total IS NULL OR instructed_total >= 0),
    -- กยศ contract/account number, or the เจ้าพนักงานบังคับคดี case number -- copied from the
    -- authority's own document, never inferred.
    authority_reference             TEXT NOT NULL,
    -- The date ON THE AUTHORITY'S DOCUMENT (the กยศ notification / LED order date) -- NOT "the
    -- first payroll month to deduct". This obligation drives payroll from the CALENDAR MONTH
    -- CONTAINING this date onward: a document dated 2026-01-15 drives January in full, not
    -- February -- see DeductionObligationRepository#findDrivingObligation's month-truncated
    -- comparison (`date_trunc('month', start_date) <= payrollMonth`), fixed after an Opus review
    -- caught a raw-date off-by-one that silently skipped a mid-month start's own first instalment.
    -- This reading is a judgment call with no UI yet to confirm it against how HR actually enters
    -- it -- see the PR's UI-phase handoff note: the entry form must label this field unambiguously.
    start_date                      DATE NOT NULL,
    status                          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                                     CHECK (status IN ('ACTIVE', 'COMPLETED', 'STOPPED')),
    -- Set when cumulative remittance first reaches instructed_total (ACTIVE -> COMPLETED). Cleared
    -- if a later correction (a reprocessed month) drops cumulative remittance back under the total
    -- (COMPLETED -> ACTIVE) -- see DeductionObligationRepository#applyCompletionTransition.
    completed_at                    TIMESTAMPTZ,
    -- Decision 3 (mitigation): completion is a PROMINENT STATE that requires HR to confirm with the
    -- authority, never a silent stop. These two columns record that HR has seen and accepted it --
    -- they do NOT themselves change deduction behaviour (status = COMPLETED already deducts 0
    -- unless overridden below).
    completion_acknowledged_by_id   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    completion_acknowledged_at      TIMESTAMPTZ,
    -- Decision 3 (mitigation): the ONLY way a deduction resumes past instructed_total. Explicit,
    -- recorded who/when/why -- never inferred from payroll simply continuing to run.
    override_continue_past_total    BOOLEAN NOT NULL DEFAULT FALSE,
    override_by_id                  BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    override_at                     TIMESTAMPTZ,
    override_reason                 TEXT,
    notes                           TEXT,
    created_by_id                   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_id                   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (override_continue_past_total = FALSE OR (override_by_id IS NOT NULL AND override_at IS NOT NULL))
);

-- At most one ACTIVE obligation per employee per kind -- otherwise which one drives payroll would
-- be ambiguous. A new obligation of the same kind may be created once the old one is COMPLETED or
-- STOPPED (e.g. a second, later court order against the same employee).
CREATE UNIQUE INDEX ux_deduction_obligation_one_active_per_kind
    ON hr.deduction_obligation(employee_id, kind)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_deduction_obligation_employee ON hr.deduction_obligation(employee_id);

COMMENT ON TABLE hr.deduction_obligation IS
  'One instruction from an external authority (กยศ / เจ้าพนักงานบังคับคดี) to deduct and remit money on an employee''s behalf. Records the instruction; never computes one -- see issue #373.';

COMMENT ON COLUMN hr.deduction_obligation.start_date IS
  'The date ON THE AUTHORITY''S DOCUMENT, not "first payroll month to deduct" -- drives payroll from the calendar month CONTAINING this date onward (month-truncated comparison in DeductionObligationRepository#findDrivingObligation). A judgment call with no UI yet to confirm against how HR actually enters it -- the entry form must label this unambiguously.';

-- ---------------------------------------------------------------------
-- Remittance ledger: one row per payroll PERIOD actually deducted, so paid-to-date is a derived
-- SUM over real events rather than a mutable running counter. UNIQUE (obligation_id, payroll_month)
-- makes recording idempotent per period -- reprocessing a month UPSERTs that period's own row
-- rather than inserting a second one, so a payroll re-run self-corrects instead of double-counting
-- (this system already deletes and re-inserts hr.payroll_line per period on every re-run --
-- PayrollRepository#saveProcessedPeriod -- so this ledger must tolerate the same replay).
-- ---------------------------------------------------------------------

CREATE TABLE hr.deduction_obligation_remittance (
    remittance_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    obligation_id        BIGINT NOT NULL REFERENCES hr.deduction_obligation(obligation_id) ON DELETE CASCADE,
    payroll_month         DATE NOT NULL,
    -- The amount ACTUALLY remitted this period, i.e. the persisted hr.payroll_line figure AFTER
    -- every other cap (ป.วิ.พ. ม.302 for LEGAL_EXECUTION) has already been applied -- never the
    -- pre-cap requested figure. This is what keeps next period's remaining-balance clamp correct
    -- even in a period where the statutory garnishment cap bit harder than this obligation's own
    -- draw-down would have.
    amount                NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    payroll_period_id     BIGINT REFERENCES hr.payroll_period(period_id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (obligation_id, payroll_month)
);

CREATE INDEX idx_deduction_obligation_remittance_obligation
    ON hr.deduction_obligation_remittance(obligation_id);

COMMENT ON TABLE hr.deduction_obligation_remittance IS
  'One row per payroll period actually deducted against a deduction_obligation. Paid-to-date is SUM(amount) over this table, never a separately-maintained counter -- see issue #373.';
