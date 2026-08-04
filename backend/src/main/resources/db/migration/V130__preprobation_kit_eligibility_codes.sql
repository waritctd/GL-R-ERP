SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- UNIFORM_PREPROBATION_KIT (§2.1.3): enable it, against the real org data.
--
-- Numbered V130, not V129: V129__widen_holiday_name_th.sql is already claimed by
-- another session (branches fix/holiday-name-column-width and
-- feat/attendance-schedule-holiday-admin-api). V126 is an unused gap but is NOT
-- reusable -- production has already applied through V127, and Flyway here runs
-- without outOfOrder, so a lower version arriving later fails the whole migrate.
--
-- The type has been unusable since V66. It gated on a single policy key,
-- sales_support_department_code, deliberately seeded EMPTY because the real
-- code was only knowable from production -- so every request of this type was
-- refused, for seven years.
--
-- Production was read on 2026-08-03 and the single key turns out to be the
-- wrong SHAPE, not merely unset. §2.1.3 lists FOUR job functions:
--
--   1. พนักงานขับรถ            -> a POSITION (source_code POS11 / 0023)
--   2. พนักงานติดรถส่งของ       -> NOT REPRESENTED in the org data
--   3. พนักงานฝ่ายโมเสค         -> NOT REPRESENTED in the org data
--   4. พนักงานสนับสนุนฝ่ายขาย   -> TWO DEPARTMENTS (SALES "Sales Support 1",
--                                 SALES2 "Sales Support 2")
--
-- So the eligible set spans both departments AND positions, and is plural on
-- both axes. One singular department key could express none of that.
--
-- Replaced by two CSV keys, read by SpecialMoneyPolicyEvaluator
-- #evaluatePreprobationKitEligibility. CSV rather than one row per code because
-- ux_smp_type_key_effective is UNIQUE (request_type, policy_key, effective_from)
-- -- multiple rows would need synthesised key names (…_1, …_2), which is worse
-- to read and worse to edit.
--
-- ⚠️ FUNCTIONS 2 AND 3 REMAIN UNGATED, deliberately. Nothing in hr.department or
-- hr.position corresponds to ติดรถส่งของ or โมเสค, so there is no code to match
-- them on. They are NOT approximated by widening to a whole division (e.g. all of
-- คลังสินค้า for the delivery riders) -- that would hand a ~฿2,000 kit to every
-- warehouse employee, not just the ones who ride the truck. An employee in one of
-- those roles stays refused until HR gives the role a department or position and
-- appends its code below.
-- ---------------------------------------------------------------------

-- Retire the singular key. Safe to delete outright rather than effective-date it:
-- it was never non-empty, so no historical request was ever evaluated against a
-- value it carried, and hr.special_money_request stores its own policy_version
-- snapshot regardless.
DELETE FROM hr.special_money_policy
 WHERE request_type = 'UNIFORM_PREPROBATION_KIT'
   AND policy_key = 'sales_support_department_code';

INSERT INTO hr.special_money_policy (request_type, policy_key, text_value, effective_from) VALUES
    ('UNIFORM_PREPROBATION_KIT', 'preprobation_kit_department_codes', 'SALES,SALES2', DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'preprobation_kit_position_codes',   'POS11,0023',   DATE '2018-06-08')
ON CONFLICT (request_type, policy_key, effective_from) DO UPDATE
    SET text_value = EXCLUDED.text_value;

COMMENT ON COLUMN hr.special_money_policy.text_value IS
    'Non-numeric policy settings. UNIFORM_PREPROBATION_KIT carries two CSV lists — preprobation_kit_department_codes (hr.department.source_code) and preprobation_kit_position_codes (hr.position.source_code) — because §2.1.3''s four job functions do not all sit at one level of the org data. Append a code to the right list to make a role eligible.';
