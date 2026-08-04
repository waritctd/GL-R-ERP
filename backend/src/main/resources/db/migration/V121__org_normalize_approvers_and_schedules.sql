-- Owner-directed org corrections, shipped as one migration (2026-08-02, revised after review):
--
--   Part B — create the แม่บ้าน (housekeeping) department and move employee 10051 into it.
--   Part A — normalise leave approvers (hr.employee.reports_to_employee_id) for active
--            employees in a fixed set of departments, per an explicit owner-supplied mapping.
--            Includes a post-condition guard: the migration refuses to apply if it would leave any
--            active employee in a mapped department without an approver.
--   Part C — give the new แม่บ้าน department the six-day OPS_6D schedule (คลังสินค้า already has
--            it), and raise the company-wide late-arrival grace period from 5 to 15 minutes.
--            Sales Support 1/2 were considered for OPS_6D during review and the owner REVERSED
--            that -- they stay on OFFICE_5D, untouched -- see Part C's own comment for the history.
--
-- Part B runs FIRST even though it is listed second in the request: Part A's approver mapping
-- includes the new แม่บ้าน department, so that department (and employee 10051's membership in it)
-- must exist before Part A's UPDATE runs, or its WHERE dep.source_code = 'HK' branch would match
-- nothing.
--
-- LeaveService already enforces "HR/CEO or the employee's direct manager may approve" by reading
-- hr.employee.reports_to_employee_id (see LeaveService#isDirectManager /
-- LeaveRepository#findEmployeeAccess). Nothing in this migration touches that rule -- every
-- statement here corrects the DATA the rule reads, never the rule itself.
--
-- Every statement resolves ids by employee_code / department source_code INSIDE this migration,
-- never a hardcoded surrogate id (those differ per environment -- a fresh/CI/UAT database may have
-- none of these employees/departments at all, in which case the affected statement is simply a
-- no-op, by construction of its JOIN).
--
-- Only ACTIVE employees (hr.employee.is_active = TRUE) are touched anywhere in this migration.
-- Historical/inactive rows keep whatever reports_to_employee_id / department_id they already have.

-- =================================================================================================
-- Part B — แม่บ้าน (housekeeping) department, and moving employee 10051 into it.
-- =================================================================================================
--
-- 10051 (จำเนียร วงค์สา) is the company's only active cleaning employee (ฝ่ายทำความสะอาด, part of
-- the six-day OPS_6D group per the 2567 announcement -- see V115) but is currently filed under
-- department โชว์รูม, which is organisationally wrong. There is no แม่บ้าน department to file her
-- under yet, so this migration creates one.
--
-- source_code chosen: 'HK' (Housekeeping). Rationale: every other department source_code in this
-- schema is either a transliteration/abbreviation of the Thai name (SATM = ทีมขาย, SALES = ฝ่าย
-- สนับสนุนการขาย) or a short English functional tag (WH = warehouse, AC = accounting, QC&ISO =
-- quality control); 'HK' is the standard short-form abbreviation for "housekeeping" and does not
-- collide with any existing division or department source_code in this schema (division-level 'HR'
-- is บุคคล/HR, a different department, so no ambiguity with the Thai reading of "แม่บ้าน" as
-- household staff).
--
-- division_id: this migration deliberately files the new department under 10051's OWN current
-- division (whatever that is in this environment), rather than inventing a new division or leaving
-- it NULL -- Part B is scoped to fixing her DEPARTMENT, the request does not ask this migration to
-- restructure divisions. A no-op (INSERT ... SELECT matches zero rows) if employee 10051 does not
-- exist in this environment; also a no-op (guarded by NOT EXISTS) if a 'HK' department was already
-- created by an earlier run of this migration.
INSERT INTO hr.department (source_code, name_th, division_id, is_active)
SELECT 'HK', 'แม่บ้าน', e.division_id, TRUE
  FROM hr.employee e
 WHERE e.employee_code = '10051'
   AND NOT EXISTS (SELECT 1 FROM hr.department WHERE source_code = 'HK');

-- Move 10051 into the new department. No-op if either the employee or the 'HK' department row is
-- absent (the JOIN below simply matches nothing).
UPDATE hr.employee e
   SET department_id = dep.department_id
  FROM hr.department dep
 WHERE e.employee_code = '10051'
   AND dep.source_code = 'HK';

-- =================================================================================================
-- Part A — normalise leave approvers for active employees.
-- =================================================================================================
--
-- Mapping (owner-ruled):
--   Sales Support 1 (SALES), Sales Support 2 (SALES2), ทีมขาย (SATM)      -> จินตนา หาญมนตรี (10049)
--   คลังสินค้า (WH, and the second คลังสินค้า department with NULL code) -> จริญญา ผึ่งสีใส (10014)
--   จัดซื้อต่างประเทศ (PCIM), บัญชี (AC), QC&ISO (QC&ISO)                -> ราม อิฐรัตน์ (10009)
--   แม่บ้าน (HK, created above)                                          -> ราม อิฐรัตน์ (10009)
--
-- This SUPERSEDES the previous ad-hoc paper-approval chain: ยุทธนา บู่สกุล (10063) and ปรางค์เนตร
-- ถาใจ (10079) currently sign leave approvals on paper but hold position พนักงาน (a rank-and-file
-- position, not a manager one) and are NOT managers under this or any prior data-driven mapping.
-- They lose approver status here as a DIRECT, INTENDED consequence of the department-driven UPDATE
-- below (any employee who previously had reports_to_employee_id pointing at either of them, in one
-- of the departments above, is repointed to the correct approver). This is recorded explicitly so a
-- future reader comparing a signed paper leave form against this system's decision can find out why
-- they differ: the paper trail is stale, not this migration.
--
-- Explicitly OUT OF SCOPE, left untouched by this migration:
--   - บุคคล (HR) and บริหาร departments: the owner has ruled these employees do not submit leave in
--     this system at all. Their rows are not in the department list below, so the UPDATEs cannot
--     touch them; nothing further to guard.
--   - Employee codes '10016', '10030', 'GLR-1001': the owner says they will not use the system.
--     They have NO department (department_id IS NULL per the request), so they fall through every
--     department-driven UPDATE below by construction -- this is DELIBERATE, not an oversight.
--   - Inactive employees (is_active = FALSE): every UPDATE below filters on is_active = TRUE.

-- --- Explicit self-approval exception -----------------------------------------------------------
-- จินตนา (10049) manages the Sales Support / ทีมขาย departments below, and จริญญา (10014) manages
-- the คลังสินค้า departments below -- a manager never approves their own leave, so neither may be
-- repointed at herself by the blanket department UPDATEs that follow. Both keep their EXISTING
-- reporting line to ราม (10009), asserted explicitly and unconditionally here (not left to "the
-- blanket UPDATE happens to exclude them") so the exception is visible in the SQL itself, per the
-- owner's explicit ruling. Also acts as a backstop if either of them is ever re-filed into one of
-- the very departments they manage -- the general department UPDATE below additionally guards
-- against setting any approver's row to point at itself (belt-and-braces; hr.employee already
-- carries CONSTRAINT chk_no_self_manager CHECK (reports_to_employee_id <> employee_id) since V1,
-- which would reject a self-reference outright and fail this migration loudly rather than silently
-- writing bad data -- see the note at the bottom of this file, this migration does NOT need to add
-- that constraint, it already exists).
UPDATE hr.employee AS mgr
   SET reports_to_employee_id = ram.employee_id
  FROM hr.employee ram
 WHERE ram.employee_code = '10009'
   AND mgr.employee_code IN ('10049', '10014')
   AND mgr.is_active = TRUE;

-- --- Sales Support 1/2 + ทีมขาย -> จินตนา (10049) -----------------------------------------------
-- employee_code <> '10049' guards against ever pointing จินตนา at herself if her own row is ever
-- filed under one of these departments; '10049'/'10014' are additionally excluded outright since
-- their reporting line is fixed above, not derived from their own department.
UPDATE hr.employee AS e
   SET reports_to_employee_id = approver.employee_id
  FROM hr.department dep
  JOIN hr.employee approver ON approver.employee_code = '10049'
 WHERE e.department_id = dep.department_id
   AND e.is_active = TRUE
   AND e.employee_code NOT IN ('10049', '10014')
   AND e.employee_id <> approver.employee_id
   AND dep.source_code IN ('SALES', 'SALES2', 'SATM');

-- --- คลังสินค้า (WH, and the NULL-source_code duplicate) -> จริญญา (10014) ------------------------
-- Matches BOTH the coded ('WH') and the uncoded department row sharing the same Thai name --
-- hr.department.source_code is UNIQUE but Postgres UNIQUE permits multiple NULLs, which is exactly
-- the pre-existing state described by the owner (two คลังสินค้า department rows, one carrying 'WH',
-- one carrying no code at all).
UPDATE hr.employee AS e
   SET reports_to_employee_id = approver.employee_id
  FROM hr.department dep
  JOIN hr.employee approver ON approver.employee_code = '10014'
 WHERE e.department_id = dep.department_id
   AND e.is_active = TRUE
   AND e.employee_code NOT IN ('10049', '10014')
   AND e.employee_id <> approver.employee_id
   AND (dep.source_code = 'WH' OR (dep.source_code IS NULL AND dep.name_th = 'คลังสินค้า'));

-- --- จัดซื้อต่างประเทศ (PCIM) + บัญชี (AC) + QC&ISO + แม่บ้าน (HK) -> ราม (10009) -------------------
-- 'HK' is the department created by Part B above; by the time this statement runs it already
-- exists (and 10051 is already filed under it), so จำเนียร receives ราม as her approver via this
-- same department-driven UPDATE like everyone else -- no separate statement needed for her.
UPDATE hr.employee AS e
   SET reports_to_employee_id = approver.employee_id
  FROM hr.department dep
  JOIN hr.employee approver ON approver.employee_code = '10009'
 WHERE e.department_id = dep.department_id
   AND e.is_active = TRUE
   AND e.employee_code NOT IN ('10049', '10014')
   AND e.employee_id <> approver.employee_id
   AND dep.source_code IN ('PCIM', 'AC', 'QC&ISO', 'HK');

-- --- Re-assert the self-approval exception, last -----------------------------------------------
-- Repeats the explicit UPDATE above as the final word on จินตนา/จริญญา's own reporting line, so
-- their correct state does not depend on statement ordering above (the earlier exclusions already
-- guarantee this; this is redundant belt-and-braces, not load-bearing).
UPDATE hr.employee AS mgr
   SET reports_to_employee_id = ram.employee_id
  FROM hr.employee ram
 WHERE ram.employee_code = '10009'
   AND mgr.employee_code IN ('10049', '10014')
   AND mgr.is_active = TRUE;

-- NOTE on chk_no_self_manager: the task that produced this migration asked for a CHECK constraint
-- forbidding reports_to_employee_id = employee_id to be added. hr.employee already has exactly
-- that constraint, unchanged since V1__employee_master_schema.sql:
--     CONSTRAINT chk_no_self_manager CHECK (reports_to_employee_id <> employee_id)
-- (Postgres CHECK semantics: a NULL reports_to_employee_id always satisfies this, so it never blocks
-- an employee with no manager.) Adding a second, differently-named constraint with the same
-- predicate would be redundant and would error on ADD CONSTRAINT IF NOT EXISTS being unsupported for
-- CHECK in this Postgres version's syntax used elsewhere in this repo -- so this migration relies on
-- the existing constraint rather than adding a duplicate. Every UPDATE above is additionally guarded
-- in SQL (see comments) so this constraint is never exercised as anything but a backstop by this
-- migration's own data.

-- --- Post-condition: nobody who uses the system may be left without an approver -----------------
-- Owner requirement, added after initial review: a silently-orphaned active employee (mapped
-- department, reports_to_employee_id still NULL after the UPDATEs above) cannot get leave approved
-- by anyone except HR/CEO, and that failure is invisible until someone actually tries to take leave.
-- Rather than hope the UPDATEs above covered every row, this migration REFUSES TO APPLY if any do
-- exist -- fail loudly at deploy time, not silently in production months later.
--
-- Scope: every ACTIVE employee in a MAPPED department (the identical department set the UPDATEs
-- above target -- Sales Support 1/2, ทีมขาย, คลังสินค้า [both the 'WH'-coded and the NULL-coded
-- row], จัดซื้อต่างประเทศ, บัญชี, QC&ISO, แม่บ้าน) must end up with a non-NULL
-- reports_to_employee_id.
--
-- Deliberately EXCLUDED -- owner ruling 2026-08-02: executives and non-system users do not submit
-- leave requests, so an absent approver for them is CORRECT, not an incomplete migration. Kept as
-- ONE array literal, right here, so any future change to who is exempt is a one-line edit rather
-- than a hunt through this file. This exclusion list is FINAL (owner-confirmed), not provisional:
--   - employee_codes '10001' (กัลยาณี), '10002' (เลิศชัย), '10003' (รานี), '10009' (ราม -- the
--     approver himself) -- บริหาร, the executive team. They keep whatever reports_to_employee_id
--     they already have, NULL included; that is correct, not a gap. (บุคคล's ฟ้าใส is separately
--     excluded from SUBMITTING leave by the same ruling, but she is NOT in this array -- she keeps
--     her existing non-NULL approver [10009 ราม] unmodified, and since บุคคล/HR is not one of the
--     mapped departments matched below, this check never looks at her row either way.)
--   - employee_codes '10016'/'10030'/'GLR-1001': explicitly out of scope per the owner (no
--     department, will not use the system). Already excluded by construction -- this check only
--     looks at employees who HAVE one of the mapped departments below -- but named here too so the
--     omission reads as deliberate rather than an oversight, matching Part A's own comment above.
DO $$
DECLARE
    orphan_count INTEGER;
    excluded_employee_codes CONSTANT VARCHAR[] := ARRAY['10001', '10002', '10003', '10009'];
BEGIN
    SELECT count(*) INTO orphan_count
      FROM hr.employee e
      JOIN hr.department dep ON dep.department_id = e.department_id
     WHERE e.is_active = TRUE
       AND e.reports_to_employee_id IS NULL
       AND e.employee_code <> ALL (excluded_employee_codes)
       AND (
           dep.source_code IN ('SALES', 'SALES2', 'SATM', 'PCIM', 'AC', 'QC&ISO', 'HK')
           OR dep.source_code = 'WH'
           OR (dep.source_code IS NULL AND dep.name_th = 'คลังสินค้า')
       );

    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'V121: % active employee(s) in a mapped department have no leave approver (reports_to_employee_id IS NULL) after normalization -- refusing to apply this migration rather than leave a silent approval hole',
            orphan_count;
    END IF;
END $$;

-- =================================================================================================
-- Part C — schedules follow the org change.
-- =================================================================================================
--
-- Per the owner's ruling (2026-08-02, later REVERSED on the Sales Support question -- see below),
-- the six-day group (Mon-Sat 08:30-17:30, OPS_6D) is คลังสินค้า + แม่บ้าน (plus ฝ่ายผลิต and
-- ฝ่ายบริการ, which V115 already covers at DIVISION scope and which currently have no active staff).
--
-- REVERSED: an earlier draft of this migration also moved Sales Support 1/2 (SALES, SALES2) from
-- OFFICE_5D to OPS_6D, reasoning that they perform เจ้าหน้าที่จัดส่ง (delivery) work in practice. The
-- owner has since REVERSED that ruling -- Sales Support 1 and 2 STAY on OFFICE_5D, unchanged from
-- V115. This puts the schema back in line with the announcement's own literal §1 text (which lists
-- ฝ่ายสนับสนุนการขาย in the five-day group), so there is deliberately no SALES/SALES2 statement in
-- this migration at all -- do not add one back without a fresh, current owner ruling to cite.
--
-- เจ้าหน้าที่จัดส่ง (delivery) remains UNMODELLED: no division/department in this schema represents
-- that role (same gap V115's own OPS_6D comment already recorded), so nobody is seeded into it here
-- either. Noted so the gap stays visible rather than being silently forgotten now that the Sales
-- Support theory for who performs it has been withdrawn.
--
-- V117 (read before writing this): a DELETE-then-INSERT is required rather than closing a row with
-- effective_to whenever the old and new rows for the same scope would share an effective_from
-- (chk_work_schedule_assignment_effective_range requires effective_to >= effective_from, so there is
-- no valid effective_to strictly before a same-day effective_from). Not relevant to แม่บ้าน below --
-- it is a brand-new department with no prior row to replace -- but recorded here since the next
-- schedule change in this table will very likely need it again.

-- แม่บ้าน (HK) is a brand-new department (created in Part B above) with no prior schedule row to
-- delete -- a plain INSERT, not delete-and-reinsert. Same effective_from as everything else in this
-- migration for consistency with the announcement's own effective date.
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DEPARTMENT', dep.department_id, s.work_schedule_id, DATE '2024-10-01', NULL
  FROM hr.department dep
  JOIN hr.work_schedule s ON s.code = 'OPS_6D'
 WHERE dep.source_code = 'HK';

-- V115's EMPLOYEE-scope OPS_6D override for employee_code '10051' is now REDUNDANT: she is filed
-- under the แม่บ้าน (HK) department as of Part B above, and that department now carries its own
-- DEPARTMENT-scope OPS_6D row (immediately above) -- TieredWorkScheduleResolver would resolve
-- OPS_6D for her via the DEPARTMENT tier alone even without the EMPLOYEE-scope row.
--
-- DECISION: remove the redundant EMPLOYEE-scope row rather than leave it. Leaving it would be
-- harmless today (both tiers agree), but it is a second source of truth for the same fact that can
-- silently drift out of sync with the department-level row in a future migration (e.g. if แม่บ้าน's
-- department-level schedule is later changed and nobody remembers a per-employee override still
-- exists for the one person originally in that department). Removing it now, while it is provably
-- redundant, is the cleaner state. This targets exactly the row V115 seeded (matched on scope,
-- schedule, and V115's own effective_from) so it is a no-op wherever that row was never created.
DELETE FROM hr.work_schedule_assignment a
 USING hr.employee e, hr.work_schedule s
 WHERE a.scope_type = 'EMPLOYEE'
   AND a.scope_id = e.employee_id
   AND e.employee_code = '10051'
   AND a.work_schedule_id = s.work_schedule_id
   AND s.code = 'OPS_6D'
   AND a.effective_from = DATE '2024-10-01'
   AND a.effective_to IS NULL;

-- --- Grace period: 5 minutes -> 15 minutes (owner ruling) ---------------------------------------
-- Applies to EVERY seeded schedule (OFFICE_5D, OPS_6D, SALES_5D, WEEKEND_DUTY) -- the owner's
-- ruling is a company-wide grace period, not a per-schedule one. The application-level fallback
-- default (app.attendance.schedule.grace-minutes, read by CompanyWideWorkScheduleResolver for any
-- employee with no hr.work_schedule_assignment row at all) is changed to match in the same commit --
-- see application.yml and AppProperties.Schedule -- so an unassigned employee's grace period is
-- identical to an assigned one's, rather than silently diverging from this table.
--
-- Grace stays a THRESHOLD, not an allowance (WorkSchedule#isLate / #lateMinutes, UNCHANGED by this
-- migration): with a 15-minute grace and work_start 08:30, a 08:44 check-in is 0 late minutes, but a
-- 08:46 check-in is late by 16 minutes measured from 08:30 -- not 1. Zero payroll effect either way
-- (Thai LPA §76, late/early minutes are reporting-only).
UPDATE hr.work_schedule
   SET grace_minutes = 15
 WHERE code IN ('OFFICE_5D', 'OPS_6D', 'SALES_5D', 'WEEKEND_DUTY');
