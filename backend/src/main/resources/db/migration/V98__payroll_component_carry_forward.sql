SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Per-employee, per-component CARRY-FORWARD (2026-07-29, owner decision).
--
-- What carried forward used to be hardcoded: `PayrollCarryForwardDtos` pre-filled special_pay_1..5
-- on the theory that slots 1-5 were the standing company allowances. The V95/V97 realignment to the
-- accountant's numbering broke that silently -- the same five slot NUMBERS now name different money,
-- so the set quietly gained ค่าเช่าบ้าน (which varies per employee) and lost ค่า GPRS (which does
-- not). Nobody chose that; two frontend tests failing is how it surfaced.
--
-- The owner's rule replaces the guess with evidence: a slot that holds the SAME value for an
-- employee in at least 70% of months is recurring for that employee and carries; anything else does
-- not. Derived from `2026.xlsx` (มี.ค.-มิ.ย 2569, 4 months, so the threshold is 3 of 4).
--
-- What the ledger actually says, and it contradicts the labels in both directions:
--   พ9 เงินรางวัล  carries for 16 employees -- "reward" is a standing allowance for most of them
--   พ7 คอมมิชชั่น  carries for ZERO of 8    -- the amount never repeats
--   ค่า GPRS       carries for 6            -- the slot the realignment had just dropped
--
-- ⚠️ CARRY-FORWARD IS NOT ป.96 CLASSIFICATION. They answer different questions and this table must
-- never be used for the other. Commission is PAID most months (so it recurs) while its AMOUNT changes
-- every time (so pre-filling last month's figure is wrong). A component can be recurring for tax and
-- non-carrying for data entry.
--
-- ⚠️ COUNT CORRECTED (fourth Opus review, 2026-07-30): this comment previously said "SEEDED FOR 29 OF
-- 34 EMPLOYEES", conflating two different counts. Of the 34 workbook names, 5 could not be resolved
-- to an employee row at all (listed below) -- 34 - 5 = 29 WERE resolved to a real employee. But
-- "resolved to an employee" is not "seeded with a recurring flag": of those 29 resolved employees,
-- only 16 actually have at least one component clearing the 70%-same-value bar below (44 rows total).
-- The other 13 resolved employees are correctly seeded with NOTHING here, not because they are
-- unresolved, but because nothing they are paid recurs by this rule -- verify against the VALUES list:
-- 16 distinct employee codes, 44 rows.
--
-- Five workbook names could not be resolved to an employee at all and are deliberately left unseeded
-- rather than guessed -- they carry nothing until someone sets them, which fails safe:
--     ศรรักษณ์, อดิเทพ   -- no employee row exists at all, though ศรรักษณ์ is being paid
--     ศรายุธ, สนั่น       -- two employees share each first name; the workbook keys on first name only
--     ธันยพร             -- matches 100160 but is_active = false while appearing in the sheet
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS hr.payroll_component_carry_forward (
    employee_id   BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    tax_year      SMALLINT NOT NULL,
    component     VARCHAR(40) NOT NULL REFERENCES hr.payroll_pay_component(component),
    carry_forward BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by_id BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (employee_id, tax_year, component)
);

COMMENT ON TABLE hr.payroll_component_carry_forward IS
  'Whether a component pre-fills next month from this month, per employee. Seeded from 2026.xlsx at the owner''s 70%-same-value rule. NOT a ป.96 tax classification -- see hr.payroll_component_tax_treatment for that.';

INSERT INTO hr.payroll_component_carry_forward (employee_id, tax_year, component, carry_forward, updated_by_id, updated_at)
SELECT e.employee_id, 2026, s.component, TRUE, NULL::bigint, now()
  FROM (VALUES
    ('10012', 'SPECIAL_PAY_1'),  -- มณฑ์ชญา: 407 in 4/4 months
    ('10012', 'SPECIAL_PAY_5'),  -- มณฑ์ชญา: 3,000 in 4/4 months
    ('10012', 'SPECIAL_PAY_9'),  -- มณฑ์ชญา: 1,000 in 4/4 months
    ('10013', 'SPECIAL_PAY_4'),  -- ภิญญดา: 500 in 4/4 months
    ('10013', 'SPECIAL_PAY_5'),  -- ภิญญดา: 1,500 in 4/4 months
    ('10013', 'SPECIAL_PAY_9'),  -- ภิญญดา: 4,500 in 4/4 months
    ('10014', 'SPECIAL_PAY_1'),  -- จริญญา: 1,500 in 4/4 months
    ('10014', 'SPECIAL_PAY_4'),  -- จริญญา: 5,000 in 4/4 months
    ('10014', 'SPECIAL_PAY_5'),  -- จริญญา: 6,500 in 4/4 months
    ('10014', 'SPECIAL_PAY_9'),  -- จริญญา: 3,000 in 4/4 months
    ('10018', 'SPECIAL_PAY_1'),  -- ประภัสสร: 550 in 4/4 months
    ('10018', 'SPECIAL_PAY_5'),  -- ประภัสสร: 1,600 in 4/4 months
    ('10018', 'SPECIAL_PAY_6'),  -- ประภัสสร: 1,000 in 4/4 months
    ('10018', 'SPECIAL_PAY_9'),  -- ประภัสสร: 3,000 in 4/4 months
    ('10026', 'SPECIAL_PAY_5'),  -- ชาญณรงค์: 2,000 in 3/4 months
    ('10027', 'SPECIAL_PAY_5'),  -- ชัยวัตน์: 6,000 in 4/4 months
    ('10033', 'MEAL_ALLOWANCE'),  -- สุเชด: 1,680 in 4/4 months
    ('10033', 'SPECIAL_PAY_1'),  -- สุเชด: 580 in 4/4 months
    ('10033', 'SPECIAL_PAY_2'),  -- สุเชด: 850 in 4/4 months
    ('10033', 'SPECIAL_PAY_5'),  -- สุเชด: 3,000 in 4/4 months
    ('10033', 'SPECIAL_PAY_6'),  -- สุเชด: 1,000 in 4/4 months
    ('10033', 'SPECIAL_PAY_9'),  -- สุเชด: 3,500 in 4/4 months
    ('10039', 'SPECIAL_PAY_1'),  -- สุริณีย์: 500 in 4/4 months
    ('10039', 'SPECIAL_PAY_5'),  -- สุริณีย์: 807 in 4/4 months
    ('10039', 'SPECIAL_PAY_9'),  -- สุริณีย์: 1,500 in 4/4 months
    ('10040', 'SPECIAL_PAY_6'),  -- ชนิดา: 1,000 in 4/4 months
    ('10040', 'SPECIAL_PAY_9'),  -- ชนิดา: 500 in 3/4 months
    ('10043', 'SPECIAL_PAY_6'),  -- สุวรรณี: 1,000 in 4/4 months
    ('10043', 'SPECIAL_PAY_9'),  -- สุวรรณี: 1,500 in 4/4 months
    ('10049', 'SPECIAL_PAY_4'),  -- จินตนา: 5,000 in 4/4 months
    ('10049', 'SPECIAL_PAY_5'),  -- จินตนา: 5,000 in 4/4 months
    ('10049', 'SPECIAL_PAY_9'),  -- จินตนา: 3,000 in 4/4 months
    ('10062', 'SPECIAL_PAY_2'),  -- อดิศักดิ์: 3,000 in 4/4 months
    ('10062', 'SPECIAL_PAY_9'),  -- อดิศักดิ์: 1,000 in 4/4 months
    ('10063', 'SPECIAL_PAY_3'),  -- ยุทธนา: 3,000 in 4/4 months
    ('10063', 'SPECIAL_PAY_5'),  -- ยุทธนา: 4,000 in 4/4 months
    ('10063', 'SPECIAL_PAY_6'),  -- ยุทธนา: 500 in 4/4 months
    ('10063', 'SPECIAL_PAY_9'),  -- ยุทธนา: 2,000 in 4/4 months
    ('10068', 'SPECIAL_PAY_5'),  -- ยุพยง: 2,000 in 4/4 months
    ('10068', 'SPECIAL_PAY_9'),  -- ยุพยง: 1,000 in 4/4 months
    ('10071', 'SPECIAL_PAY_6'),  -- เจนเนตร: 1,000 in 4/4 months
    ('10071', 'SPECIAL_PAY_9'),  -- เจนเนตร: 5,000 in 4/4 months
    ('10075', 'SPECIAL_PAY_5'),  -- สุนีย์: 5,000 in 4/4 months
    ('10075', 'SPECIAL_PAY_9')  -- สุนีย์: 5,000 in 4/4 months
  ) AS s(employee_code, component)
  JOIN hr.employee e ON e.employee_code = s.employee_code
ON CONFLICT (employee_id, tax_year, component) DO NOTHING;
