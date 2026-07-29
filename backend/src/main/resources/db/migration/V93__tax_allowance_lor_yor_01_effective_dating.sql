SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- ค่าลดหย่อน correctness + แบบ ล.ย.01 effective dating (2026-07-28)
--
-- Two things at once, because they touch the same table and the second is what makes the first
-- auditable.
--
-- (1) ALLOWANCE CORRECTNESS. Gaps found auditing the engine against the Revenue Code:
--
--     * กองทุนสำรองเลี้ยงชีพ (provident fund) was MISSING ENTIRELY. hr.employee_pii already carries
--       provident_fund_no, so the company has members, and their contributions are deducted at
--       source -- every one of them was being over-withheld by roughly (contribution x marginal
--       rate). Deductible up to 15% of ค่าจ้าง, max 500,000, inside the same 500,000 retirement
--       cluster as RMF and บำนาญ.
--     * บุตร and อุปการะคนพิการ were UNCAPPED -- free-typed baht amounts the engine trusted. The law
--       is per-head: 30,000 per child (60,000 for the 2nd and later born from 2561), and 60,000 per
--       disabled person cared for. Counts are what ล.ย.01 actually asks for, so counts are what we
--       now store, and the typed amount is clamped to what the counts allow.
--     * The ยกเว้นเงินได้ 190,000 for taxpayers aged 65+ or holding a disability card
--       (กฎกระทรวง ฉบับที่ 126) was not modelled at all. Age comes from hr.employee.date_of_birth;
--       the disability card is a ล.ย.01 declaration, so it is stored here.
--     * SSF is not dropped -- forward-only, and the historic column is still needed to explain past
--       filings -- but the engine stops applying it for tax years from 2568 (Gregorian 2025), which
--       is when SSF purchases stopped being deductible.
--
-- (2) แบบ ล.ย.01 EFFECTIVE DATING. คำชี้แจง ภ.ง.ด.1 ข้อ 2.2 says the employer computes ค่าลดหย่อน
--     from what the employee declared on ล.ย.01, and that a mid-year change applies from the period
--     it is notified. One overwritten row per tax year cannot express that, and cannot answer "which
--     declaration produced August's tax?" during an audit. The primary key gains effective_month, so
--     each declaration is a dated row and the engine resolves the latest one on or before the payroll
--     month. document_reference records the evidence the employee attached.
--
--     ข้อ 2.2 also carves out ONE exception: เงินบริจาค may only be counted once actually paid, unlike
--     every other allowance which may be applied from January on the strength of the declaration.
--     education_donation/general_donation therefore mean "paid so far this tax year", and a new dated
--     row is added as further donations are made.
-- ---------------------------------------------------------------------

ALTER TABLE hr.employee_tax_allowance
    -- ป.96/2543 / ล.ย.01 dating. 1..12; the declaration applies from this month onward.
    ADD COLUMN IF NOT EXISTS effective_month        SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS document_reference     TEXT,
    -- กองทุนสำรองเลี้ยงชีพ: employee's own contribution for the year.
    ADD COLUMN IF NOT EXISTS provident_fund_allowance NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Per-head counts backing the capped allowances (this is what ล.ย.01 asks for).
    ADD COLUMN IF NOT EXISTS child_count            SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS child_count_double     SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS disabled_care_count    SMALLINT NOT NULL DEFAULT 0,
    -- บัตรประจำตัวคนพิการ, for the 190,000 ยกเว้นเงินได้ when the employee is under 65.
    ADD COLUMN IF NOT EXISTS disability_card_holder BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE hr.employee_tax_allowance
    ADD CONSTRAINT chk_eta_effective_month CHECK (effective_month BETWEEN 1 AND 12),
    ADD CONSTRAINT chk_eta_counts_non_negative CHECK (
        child_count >= 0 AND child_count_double >= 0 AND disabled_care_count >= 0
        AND child_count_double <= child_count
        AND provident_fund_allowance >= 0
    );

COMMENT ON COLUMN hr.employee_tax_allowance.child_count_double IS
  'Of child_count, how many are the SECOND or later child born from พ.ศ. 2561 onward -- those carry 60,000 rather than 30,000. Must not exceed child_count.';

-- Best-effort backfill of the counts from the free-typed amounts that preceded them. This is an
-- APPROXIMATION and HR must verify it: the amounts were uncapped and unaudited, so a row that reads
-- 90,000 might be three children at 30,000 or a mistake.
--
-- CEILING, not FLOOR. The purpose of deriving a count is to PRESERVE an existing declaration that the
-- new per-head cap would otherwise clamp -- rounding down does the very thing the derivation exists to
-- prevent. FLOOR here would have deleted 15,000 from a 45,000 declaration and the whole of a 15,000
-- one, silently, on migration. CEILING makes the cap a no-op for every pre-existing row, so the
-- migration changes nobody's tax; genuine overstatements are then corrected by HR's verification pass
-- against the real ล.ย.01, which is a review with evidence rather than a guess made by arithmetic.
--
-- This matches PayrollTaxAllowanceInput#headCount and EmployeeTaxAllowanceUpsertRequest#headCount,
-- which face the same problem: a pre-existing amount with no count beside it.
--
-- PayrollService#headCountFor is deliberately NOT the same rule. It fires only when there is no stored
-- count AND the RUN BODY supplies the amount. An earlier version derived from the stored amount too,
-- which let the head count be raised to cover the very figure it constrains -- a 300,000 declaration
-- against one child passed in full. A stale count must CLAMP and warn, never widen the cap.
UPDATE hr.employee_tax_allowance
   SET child_count = LEAST(CEIL(child_allowance / 30000)::smallint, 20)
 WHERE child_count = 0 AND child_allowance > 0;

UPDATE hr.employee_tax_allowance
   SET disabled_care_count = LEAST(CEIL(disabled_care_allowance / 60000)::smallint, 20)
 WHERE disabled_care_count = 0 AND disabled_care_allowance > 0;

-- Re-key onto (employee_id, tax_year, effective_month). Existing rows become the declaration in
-- force from January, which is exactly how they were being applied.
ALTER TABLE hr.employee_tax_allowance DROP CONSTRAINT IF EXISTS employee_tax_allowance_pkey;
ALTER TABLE hr.employee_tax_allowance
    ADD CONSTRAINT employee_tax_allowance_pkey PRIMARY KEY (employee_id, tax_year, effective_month);

COMMENT ON TABLE hr.employee_tax_allowance IS
  'แบบ ล.ย.01 declarations, effective-dated. One row per (employee, tax year, effective month); PayrollRepository resolves the latest row on or before the payroll month, per คำชี้แจง ภ.ง.ด.1 ข้อ 2.2. Donation columns mean "paid so far this tax year" -- ข้อ 2.2 allows donations only once actually paid.';
