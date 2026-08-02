SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Deduction classification, shortfall tracking, and written-consent recording (issue #376, split
-- out of #373). #373 (V106) built the กยศ/กรมบังคับคดี obligation record + remittance ledger; this
-- migration builds the CONSTRAINT-LAYER record-keeping around it: which statutory/legal regime each
-- deduction belongs to (recorded in code -- see th.co.glr.hr.payroll.PayrollDeductionClassification
-- -- this migration carries no classification TABLE, because the classification is a property of
-- the deduction TYPE, not a mutable per-employee fact), a general requested/actual/shortfall ledger
-- for the one place a shortfall can genuinely occur today (the ALREADY-enforced ป.วิ.พ. ม.302
-- garnishment cap), and a place for HR to record written consent where ม.76 requires it.
--
-- ⚠️ SCOPE, STATED PER CLAUDE.md / issue #376: this is authorised payroll-classification/constraint
-- work, but it changes NO deducted amount. Deduction ordering when pay cannot cover everything, the
-- ม.76 10%/20% cap itself, and a protected-minimum-net floor across all deductions are explicitly
-- OUT of scope here -- each needs a legal answer this issue says not to guess at. See
-- th.co.glr.hr.payroll.PayrollDeductionOrderingPolicy for the "needs review" marker and the
-- de-facto order this migration's author found by reading the existing engine.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- Shortfall ledger (task 2): generalises #373's requested/actual/shortfall triple to every
-- payroll-line deduction, not just the two obligation-tracked kinds. In practice only
-- LEGAL_EXECUTION_GARNISHMENT ever populates this today (see PayrollDeductionClassification / the
-- service that writes it) -- every OTHER currently-supported deduction passes through the engine
-- unclamped, so requested == actual always and there is nothing to record. This is a factual
-- statement about the current engine, not a design limitation of this table: the day another
-- deduction gains an enforced cap (e.g. ม.76 items (2)-(5), once the per-row mapping is confirmed),
-- it slots into this SAME table with its own deduction_kind, no schema change required.
--
-- UNIQUE (employee_id, payroll_month, deduction_kind) mirrors V106's remittance ledger: a payroll
-- re-run upserts THAT period's own row instead of inserting a second one, and a re-run that removes
-- a shortfall (e.g. a correction that lowers the requested figure below the statutory cap) DELETEs
-- the stale row rather than leaving it stranded -- see PayrollDeductionShortfallRepository.
-- ---------------------------------------------------------------------

CREATE TABLE hr.payroll_deduction_shortfall (
    shortfall_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id        BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    payroll_month      DATE NOT NULL,
    deduction_kind     VARCHAR(40) NOT NULL
                       CHECK (deduction_kind IN (
                           'WITHHOLDING_TAX', 'SOCIAL_SECURITY', 'STUDENT_LOAN',
                           'LEGAL_EXECUTION_GARNISHMENT', 'WARNING_LETTER', 'CUSTOMER_RETURN',
                           'OTHER_PRETAX', 'OTHER_POST_TAX'
                       )),
    -- What was asked to be deducted this period BEFORE the cap that produced the shortfall --
    -- for LEGAL_EXECUTION_GARNISHMENT, this is DeductionObligationService#resolveMonthlyAmount's own
    -- return value (the obligation's clamp already applied, if one exists), i.e. the SAME figure
    -- PayrollCalculator#calculateClassified was actually asked to garnish. Never a second,
    -- independently-guessed "what should have been asked" figure.
    requested_amount   NUMERIC(12,2) NOT NULL CHECK (requested_amount >= 0),
    -- What hr.payroll_line actually persisted for this deduction this period -- read back from the
    -- processed line, never recomputed independently.
    actual_amount      NUMERIC(12,2) NOT NULL CHECK (actual_amount >= 0),
    shortfall_amount   NUMERIC(12,2) NOT NULL CHECK (shortfall_amount > 0 AND shortfall_amount = requested_amount - actual_amount),
    -- Free text citing the specific statutory cap that bound (e.g. "ป.วิ.พ. ม.302 ... ประเภท SALARY")
    -- -- always non-null: a row only exists when there WAS a shortfall, so it always has a reason.
    shortfall_reason   TEXT NOT NULL,
    payroll_period_id  BIGINT REFERENCES hr.payroll_period(period_id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_id, payroll_month, deduction_kind)
);

CREATE INDEX idx_payroll_deduction_shortfall_employee ON hr.payroll_deduction_shortfall(employee_id);

COMMENT ON TABLE hr.payroll_deduction_shortfall IS
  'One row per (employee, payroll period, deduction kind) where the statutory cap actually enforced today reduced a deduction below what was requested -- the generalised requested/actual/shortfall triple from issue #373, per issue #376. A row exists ONLY when shortfall_amount > 0 (a full remittance never appears here) -- see PayrollDeductionShortfallRepository''s upsert-or-delete pattern.';

-- ---------------------------------------------------------------------
-- Written-consent recording (task 4): a RECORDABLE FIELD, deliberately NOT an enforcement gate --
-- issue #376 is explicit that ม.76's per-item consent requirement (items (2)-(5): union fees,
-- cooperative/welfare debts, employee-caused damages, agreed savings funds) is an OPEN QUESTION
-- ("which deductions actually have written employee consent on file?"), not a settled fact this
-- system can safely gate on. Restricted to the four deduction kinds that are HR-typed catch-alls
-- rather than a statutory figure or a court order (WITHHOLDING_TAX/SOCIAL_SECURITY/STUDENT_LOAN are
-- item (1) -- no consent question arises; LEGAL_EXECUTION_GARNISHMENT is compelled by a court order
-- regardless of consent) -- see PayrollDeductionClassification for the full classification these
-- four kinds are drawn from.
-- ---------------------------------------------------------------------

CREATE TABLE hr.deduction_written_consent (
    consent_id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id                  BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    deduction_kind               VARCHAR(40) NOT NULL
                                 CHECK (deduction_kind IN (
                                     'WARNING_LETTER', 'CUSTOMER_RETURN', 'OTHER_PRETAX', 'OTHER_POST_TAX'
                                 )),
    consent_on_file              BOOLEAN NOT NULL DEFAULT FALSE,
    -- Reference to the signed consent document HR holds -- never inferred, always copied from a
    -- real document, same discipline as hr.deduction_obligation.authority_reference.
    consent_document_reference   TEXT,
    consent_date                 DATE,
    notes                        TEXT,
    recorded_by_id                BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    recorded_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_id                 BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_id, deduction_kind)
);

COMMENT ON TABLE hr.deduction_written_consent IS
  'HR-recorded fact: is written employee consent on file for this deduction kind, per ม.76''s consent requirement for items (2)-(5)? Purely evidentiary -- NOT read by PayrollCalculator or any payroll calculation, and NOT a gate on whether the deduction runs. See issue #376.';
