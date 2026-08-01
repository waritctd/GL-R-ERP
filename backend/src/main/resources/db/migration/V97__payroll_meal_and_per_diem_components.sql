SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- ค่าอาหาร and เบี้ยเลี้ยง (ตจว/ตปท) — two components that exist in the accountant's ledger and
-- have never existed in this system (2026-07-29, owner decision).
--
-- Found by reading `2026.xlsx` rather than by anyone reporting them missing. The workbook's income
-- block runs I..V and every column in it feeds W (รวมรายได้ที่ต้องคิดภาษี), so both are TAXABLE:
--
--     col K  ค่าอาหาร                  -- สุเชด ฿1,680, ศรรักษณ์ ฿1,680 in มิ.ย
--     col P  เบี้ยเลี้ยง (ตจว/ตปท)      -- per-diem for upcountry / overseas travel
--
-- Until now HR had nowhere to put this money in the system, which means any figure typed into the
-- product for these employees was either wrong or squeezed into an unrelated พิเศษ slot.
--
-- Deliberately NOT พิเศษ slots. The workbook does not number them as พิเศษ either — they sit between
-- the พิเศษ columns as their own named items, and the system now matches the workbook's structure as
-- well as its numbering (see V95 and PayrollService#specialPayDtos for the slot realignment).
--
-- เบี้ยเลี้ยง follows มาตรา 42, owner-decided 2026-07-29. The workbook currently puts it wholly inside
-- the taxable W block; the law does not.
--
--   มาตรา 42(1)  "ค่าเบี้ยเลี้ยง หรือค่าพาหนะซึ่งลูกจ้าง...ได้จ่ายไปโดยสุจริตตามความจำเป็นเฉพาะในการที่
--                 ต้องปฏิบัติการตามหน้าที่ของตนและได้จ่ายไปทั้งหมดในการนั้น"
--                 -> reimbursed against receipts. Exempt in full, but the receipts are the evidence.
--   มาตรา 42(2)  "ค่าพาหนะและเบี้ยเลี้ยงเดินทางตามอัตราที่รัฐบาลกำหนดไว้โดยพระราชกฤษฎีกา"
--                 -> flat rate. Exempt up to the government rate, no proof of spend needed.
--
-- Two consequences drive the shape below.
--
-- FIRST: exemption is PARTIAL. Pay ฿1,000 where the government rate is ฿700 and ฿700 is exempt while
-- ฿300 is taxable. A single exempt/not-exempt flag cannot express that, and treating the whole
-- payment as exempt under-reports income on ภ.ง.ด.1. So the amount is entered as two figures and the
-- split is HR's, made against the rate that applied — the system cannot derive it without knowing
-- destination and employee grade.
--
-- SECOND: the owner confirms GL&R pays BOTH ways depending on the trip. The two limbs demand
-- different evidence, so the line records which one applied. Without it a later audit cannot tell
-- whether receipts should exist for a given payment.
--
-- The exempt portion is NOT a taxable component: it behaves like non_taxable_income, outside both the
-- tax base and the ประกันสังคม wage base. Only the excess is a real component with a ป.96 treatment.
-- ---------------------------------------------------------------------

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS meal_allowance    NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS per_diem_exempt   NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS per_diem_taxable  NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS per_diem_basis    VARCHAR(24);

-- Finding 6 fix (fourth Opus review, 2026-07-30): these two CHECKs were added without the
-- `DROP CONSTRAINT IF EXISTS` guard the sibling non-negative block below already uses, making this
-- block non-idempotent on a manual replay (a second run fails on `ADD CONSTRAINT` against a
-- constraint name that already exists, unlike CREATE/ADD COLUMN IF NOT EXISTS above). Guarded to
-- match.
ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_per_diem_basis,
    ADD CONSTRAINT chk_payroll_line_per_diem_basis CHECK (
        per_diem_basis IS NULL OR per_diem_basis IN ('FLAT_RATE_S42_2', 'REIMBURSED_S42_1')
    ),
    -- A basis must be recorded whenever any per-diem is paid. The exempt figure is a tax position;
    -- an unattributable tax position is one nobody can defend later.
    DROP CONSTRAINT IF EXISTS chk_payroll_line_per_diem_basis_present,
    ADD CONSTRAINT chk_payroll_line_per_diem_basis_present CHECK (
        (per_diem_exempt = 0 AND per_diem_taxable = 0) OR per_diem_basis IS NOT NULL
    );

-- Defect fix (Opus review, 2026-07-29): the three new money columns above had no non-negative CHECK,
-- breaking the pattern V95/V96 set for every other payroll_line money column (e.g.
-- chk_payroll_line_special_pay_9_non_negative, chk_payroll_line_bonus_pay_non_negative).
ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_meal_allowance_non_negative,
    ADD CONSTRAINT chk_payroll_line_meal_allowance_non_negative CHECK (meal_allowance >= 0),
    DROP CONSTRAINT IF EXISTS chk_payroll_line_per_diem_exempt_non_negative,
    ADD CONSTRAINT chk_payroll_line_per_diem_exempt_non_negative CHECK (per_diem_exempt >= 0),
    DROP CONSTRAINT IF EXISTS chk_payroll_line_per_diem_taxable_non_negative,
    ADD CONSTRAINT chk_payroll_line_per_diem_taxable_non_negative CHECK (per_diem_taxable >= 0);

COMMENT ON COLUMN hr.payroll_line.meal_allowance IS
  'ค่าอาหาร — workbook 2026.xlsx column K. Taxable (inside the W total). Not a พิเศษ slot.';
COMMENT ON COLUMN hr.payroll_line.per_diem_exempt IS
  'เบี้ยเลี้ยง ส่วนที่ยกเว้นภาษีตามมาตรา 42 — outside the tax base AND the ประกันสังคม wage base, like non_taxable_income. Not a ป.96 component.';
COMMENT ON COLUMN hr.payroll_line.per_diem_taxable IS
  'เบี้ยเลี้ยง ส่วนเกินอัตราที่ยกเว้น — ordinary taxable income. A real component (PER_DIEM_TAXABLE) needing a ป.96 treatment like any other.';
COMMENT ON COLUMN hr.payroll_line.per_diem_basis IS
  'Which มาตรา 42 limb applied: FLAT_RATE_S42_2 (เหมาจ่ายตามอัตราราชการ, no receipts needed) or REIMBURSED_S42_1 (จ่ายจริงตามหน้าที่, receipts are the evidence). GL&R uses both depending on the trip.';

-- Both join the component vocabulary, so each gets its own per-employee ประกันสังคม inclusion tick
-- and its own ป.96 tax treatment like every other component.
INSERT INTO hr.payroll_pay_component (component) VALUES
    ('MEAL_ALLOWANCE'),
    ('PER_DIEM_TAXABLE')
ON CONFLICT (component) DO NOTHING;

-- SSO inclusion defaults for the two new components, for every employee that already has rows.
-- Default TRUE, matching every component except ค่าตอบแทนกรรมการ and รายได้ไม่คิดภาษี (V95/V96).
INSERT INTO hr.payroll_component_sso_inclusion (employee_id, tax_year, component, is_included, updated_by_id, updated_at)
-- NULL::bigint, not a bare NULL: fed from a VALUES list Postgres infers an untyped NULL as text and
-- rejects the insert against a bigint column.
SELECT DISTINCT i.employee_id, i.tax_year, c.component, TRUE, NULL::bigint, now()
  FROM hr.payroll_component_sso_inclusion i
 CROSS JOIN (VALUES ('MEAL_ALLOWANCE'), ('PER_DIEM_TAXABLE')) AS c(component)
ON CONFLICT (employee_id, tax_year, component) DO NOTHING;

-- No tax-treatment rows are seeded. Both components are new, nobody has been paid either through
-- this system, and the owner's rule stands: an unclassified component with a non-zero amount blocks
-- the run. HR classifies them the first time they are actually paid.
