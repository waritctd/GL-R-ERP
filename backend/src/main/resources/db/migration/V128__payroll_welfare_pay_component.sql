SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- WELFARE_PAY — approved สวัสดิการ reaching payroll for the first time.
--
-- AUTHORISED PAYROLL CHANGE (owner-instructed, 2026-08-03). V66 built the
-- welfare request model in 2018 and stated outright that approved amounts do
-- NOT flow into payroll; nothing has consumed hr.special_money_request since.
-- Welfare has been keyed into payroll BY HAND ever since (the welfare document
-- §2.2 says so explicitly: รวบรวมข้อมูลการเบิก...ก่อนวันที่ 25 เพื่อคีย์ข้อมูล
-- เข้าในระบบเงินเดือน).
--
-- WHY ITS OWN COMPONENT, not a พิเศษ slot. SPECIAL_PAY_9 is labelled
-- เงินรางวัล/เงินช่วยเหลืออื่นๆ and looks like the obvious home. It is the wrong
-- one, for the same reason PayrollComponent already records for commission:
-- SPECIAL_PAY_7 (คอมมิชชั่น) is documented there as "the historical พิเศษ slot,
-- distinct from COMMISSION_PAY". Auto-computed money gets its own component in
-- this system. Sharing a manual slot would also make the double-payment below
-- undetectable, because there would be no way to tell the auto figure from the
-- hand-keyed one.
--
-- ⚠️ DOUBLE-PAY CUTOVER. From the deploy of this migration, approved welfare is
-- summed into welfare_pay automatically. HR must STOP keying welfare into
-- special_pay_9. PayrollService refuses to process a month where an employee has
-- both a non-zero welfare_pay and a non-zero special_pay_9, naming them, rather
-- than silently paying twice -- see that guard's own comment.
-- ---------------------------------------------------------------------

-- 1. The component itself.
INSERT INTO hr.payroll_pay_component (component) VALUES
    ('WELFARE_PAY')
ON CONFLICT (component) DO NOTHING;

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS welfare_pay NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_welfare_pay_non_negative,
    ADD CONSTRAINT chk_payroll_line_welfare_pay_non_negative CHECK (welfare_pay >= 0);

COMMENT ON COLUMN hr.payroll_line.welfare_pay IS
  'สวัสดิการที่อนุมัติแล้ว — summed from hr.special_money_request WHERE status = APPROVED for this payroll_month. Auto-fed; do NOT key by hand (see special_pay_9).';

-- 2. Tax treatment.
--
-- ⚠️ SEEDED DEFAULT — CONFIRM WITH THE ACCOUNTANT BEFORE THE FIRST RUN.
--
-- EXTRA_KNOWN_FREQUENCY, chosen by analogy with the treatments V100 already
-- reasoned out rather than invented here: V100 assigns BONUS_PAY and
-- OTHER_ONE_OFF_PAY to EXTRA_KNOWN_FREQUENCY as "confirmed one-off" income, and
-- reserves EXTRA_CUMULATIVE_ACTUAL for genuinely irregular income (OT,
-- commission). Welfare aid is the one-off case: a wedding, an ordination, a
-- birth or a funeral is a discrete event, not an irregular recurring earning.
-- That makes it เงินพิเศษ under ป.96/2543 ข้อ 2.5, the same limb bonus sits in.
--
-- If this is wrong it is wrong about WITHHOLDING TIMING, not about the tax
-- owed: the ป.96 methods differ in how withholding is spread across the year,
-- and the annual liability is settled at filing either way. HR can correct it in
-- the existing classification screen without a migration.
INSERT INTO hr.payroll_component_tax_treatment (employee_id, tax_year, component, tax_treatment, updated_by_id, updated_at)
SELECT e.employee_id, 2026, 'WELFARE_PAY', 'EXTRA_KNOWN_FREQUENCY', NULL, now()
  FROM hr.employee e
    ON CONFLICT (employee_id, tax_year, component) DO NOTHING;

-- 3. ประกันสังคม inclusion: EXCLUDED.
--
-- Better founded than the tax treatment above. ค่าจ้าง under พ.ร.บ.ประกันสังคม
-- ม.5 is money paid IN RETURN FOR WORK. เงินช่วยเหลืองานแต่ง/บวช/คลอด/ศพ,
-- ค่ารักษาพยาบาล and ชุดฟอร์ม are none of them paid for work performed, so they
-- are outside the wage base -- the same reasoning under which OT and bonus were
-- removed from this company's SSO base for tax_year 2026.
--
-- NOTE the seeded rows are per tax_year, like every other classification here:
-- check this again after the year rolls over.
INSERT INTO hr.payroll_component_sso_inclusion (employee_id, tax_year, component, is_included, updated_by_id, updated_at)
SELECT e.employee_id, 2026, 'WELFARE_PAY', FALSE, NULL, now()
  FROM hr.employee e
    ON CONFLICT (employee_id, tax_year, component) DO NOTHING;

-- 4. Audit trail: which payroll period actually paid a given welfare request.
-- included_period_id has existed on the table since V66 and was never written,
-- because nothing consumed these rows. PayrollService stamps it at process time.
COMMENT ON COLUMN hr.special_money_request.included_period_id IS
  'The payroll period that paid this request. Written by PayrollService when a period is processed; NULL until then.';
