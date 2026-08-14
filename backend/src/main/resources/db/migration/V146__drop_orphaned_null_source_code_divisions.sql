-- V146: drop the ten orphaned hr.division rows whose source_code is NULL/blank.
--
-- WHY. Issue #737's review found this repo has more than one SQL predicate for "is this division
-- the sales (SA-prefixed) division", and they disagree whenever a division's source_code is blank:
--
--   - DivisionAccessPolicy.divisionCode() falls back to the division NAME's prefix before '-' when
--     source_code is blank, so a hypothetical SA-prefixed-name division with a NULL source_code
--     would still resolve to "sa" -> that employee IS sales / sales_manager.
--   - CommissionRepository#findSalesManagerApproverEmployeeIds and
--     NotificationRepository#notifyByRoleInternal's sales_manager branch both match
--     d.source_code ILIKE 'SA%' -> a NULL source_code matches NEITHER.
--
-- Net effect if such a division were ever populated with staff: a genuine sales manager who CAN
-- approve a commission would never be NOTIFIED one is waiting -- silent, the same shape as #737
-- itself.
--
-- CURRENTLY LATENT, NOT LIVE (measured against prod 2026-08-14): every active employee in that
-- database agrees between the two predicates today, because every active sales person sits in the
-- one properly-coded sales division (source_code = 'SA'). Every one of the ten rows removed here
-- has zero employees, zero employee_assignment rows, zero departments and zero
-- work_schedule_assignment DIVISION scopes on prod. They read as an earlier import generation,
-- superseded by a coded row covering the same ฝ่าย and never cleaned up.
--
-- THIS IS A CLEANUP, NOT A PERMANENT FIX -- do not read it as closing the divergence for good.
-- An earlier draft of this comment claimed each of the ten "duplicates the name of an already-coded
-- division", which would have meant findOrInsertDivisionByName's unordered `LIMIT 1` could resolve
-- to the surviving coded row afterwards. That was checked against prod and is FALSE for all ten:
-- the coded siblings cover the same ฝ่าย under a DIFFERENT name ('ฝ่ายขาย' vs 'SA-ฝ่ายขาย',
-- 'ฝ่ายคลังสินค้า' vs 'WH-คลังสินค้า', and so on). Zero of the ten names have a coded row of the
-- same name. So nothing stops the shape coming back: findOrInsertDivisionByName's insert is
--     INSERT INTO hr.division(name_th, is_active) VALUES (:name, TRUE)
-- with source_code omitted (hence NULL), and it is reachable over HTTP -- EmployeeController's
-- hr-gated POST /api/employees and PUT /api/employees/{id} -> EmployeeRepository.create()/update()
-- -> ensureDivision() -> here, whenever a caller supplies a division NAME and a blank division
-- CODE. Not merely "the next ETL import", as an earlier draft implied.
--
-- The durable fix is a code change, deliberately NOT bundled here: either make the blank-code
-- insert path populate source_code, or make the ILIKE predicates use the same name-prefix fallback
-- DivisionAccessPolicy.divisionCode() already uses. This migration buys back the currently-clean
-- state; it does not make it self-sustaining.
--
-- NOTE THE DELETE LIST IS WIDER THAN THE SALES STORY ABOVE. MD-, PCIM-, AC-, WH-, HR-, SR- and
-- SS1-prefixed rows are here too, because the same ILIKE-without-name-fallback shape exists for
-- other roles -- 'MD%'/'MN%' in CommissionRepository, OvertimeRepository, SpecialMoneyRepository
-- and AttendanceCorrectionRepository, and 'PCIM%' in NotificationRepository -- while
-- DivisionAccessPolicy derives ceo/import/account/warehouse through the same prefix fallback. The
-- sales case is the one that was traced end to end; the others are the same latent shape, and all
-- ten rows are equally orphaned, so they go together rather than leaving a known-bad subset behind.
--
-- DELETE, NOT is_active = FALSE. Deactivating would not fix anything: this repo's
-- EmployeeReferenceRepository#findOrInsertDivisionByName (reached from #ensureDivision whenever the
-- caller supplies a blank source_code -- exactly this row shape) resolves by
--     SELECT division_id FROM hr.division WHERE name_th = :name LIMIT 1
-- with NO is_active filter. A deactivated row would still be found and silently reused by the next
-- import that produces the same name. Only removing the row stops that reuse.
--
-- RESOLVED BY PREDICATE, NEVER BY division_id. Surrogate ids differ across prod/UAT/dev and every
-- test database, so this migration matches on name_th + blank source_code, never a hardcoded id.
--
-- WHY THE NAME LIST, ON TOP OF "blank source_code + unreferenced": a bare "delete every
-- blank-source_code division nobody references" would, in principle, also catch a division someone
-- created moments before this migration ran and simply has not been populated yet. Restricting to
-- these ten specific, already-verified names makes that impossible. This is a one-time cleanup of a
-- known set of rows, not a standing policy -- do not widen this predicate into "any orphaned
-- division" and reuse this migration as a template without re-deriving fresh evidence the way issue
-- #737's review did for this set.
--
-- THE WHERE CLAUSE RE-VERIFIES ORPHANHOOD AT APPLY TIME, NOT JUST THE NAME. Prod's
-- flyway_schema_history was two versions behind this repo (at V144) when this was written, so this
-- may run weeks later against different data -- a name match alone is not proof a row is still
-- unreferenced. Four reference paths are checked directly below:
--   - hr.employee.division_id
--   - hr.employee_assignment.division_id
--   - hr.department.division_id
--   - hr.work_schedule_assignment WHERE scope_type = 'DIVISION' AND scope_id = division_id
--     (polymorphic: scope_id is a bare BIGINT with no foreign key of its own -- see V115's own
--     comment on that table.)
--
-- PROD HAS DRIFTED FROM THIS REPO'S SCHEMA, AND THAT IS WHY EVERY PATH IS CHECKED EXPLICITLY.
-- Both of the following were verified on 2026-08-14 and are both true:
--   - Replaying THIS REPO's chain (V1..V145) into a fresh database produces
--     employee_division_id_fkey (ON DELETE NO ACTION) -- V1 declares it inline, and Postgres duly
--     refuses a DELETE that would orphan a referencing employee row.
--   - Reading pg_constraint on PROD shows hr.employee has only 5 foreign keys, and
--     employee_division_id_fkey is NOT among them. Prod is missing exactly three of the eight FKs
--     V1 declares on hr.employee: division_id, department_id and position_id. No migration in this
--     repo drops them, so they were almost certainly dropped by hand during the original ETL import
--     and never restored.
-- So on a freshly-migrated database all four paths but one have a database-level backstop, and ON
-- PROD -- the only environment where these ten rows actually exist -- hr.employee has NONE.
-- Precisely: the employee_assignment and department checks below ARE belt-and-braces on prod (both
-- FKs survive there, so dropping those checks aborts the migration loudly rather than orphaning
-- anything), while the hr.employee and work_schedule_assignment checks are the ONLY protection --
-- mutation-checked, and removing either one silently deletes a referenced row.
-- (Nothing is currently broken by the missing constraints: all 211 prod employees resolve to a live
-- division, department and position. The exposure is that nothing stops that changing.)
-- Restoring those three constraints is a separate piece of work and deliberately NOT bundled here:
-- it would need its own migration, its own verification that no dangling row exists at apply time,
-- and its own decision about what to do if one appears.
--
-- Deleting zero rows is success, not failure: this migration also runs against every fresh test
-- database and every dev/UAT environment, where these ten rows may never have existed at all. A
-- plain DELETE already satisfies that -- no row-count assertion is added.
--
-- THE COST OF THAT CHOICE: a successful cleanup and a silent no-op look identical. Matching is
-- byte-exact, so one character of drift in a name on prod produces a run that reports nothing
-- wrong and deletes nothing. 'SS1-Sales support 1' is the likeliest to bite -- the only
-- mixed-case ASCII name in the list, and this same table already carries a 'Sales Support  2' with
-- a DOUBLE space. The direction is safe (it under-deletes, never over-deletes), but whoever applies
-- this to prod should CHECK THE ROW COUNT rather than assume: expected 10 as measured 2026-08-14.
DELETE FROM hr.division d
 WHERE d.name_th IN (
           'SS1-Sales support 1',
           'WH-คลังสินค้า',
           'AC-บัญชี',
           'PCIM-จัดซื้อต่างประเทศ',
           'HR-บุคคล',
           'MD-ผู้บริหารระดับสูง',
           'SA-ฝ่ายขาย',
           'SR-โชว์รูม',
           'SA-ทีมขาย',
           'SATM-ทีมขาย'
       )
   AND (d.source_code IS NULL OR btrim(d.source_code) = '')
   AND NOT EXISTS (SELECT 1 FROM hr.employee e WHERE e.division_id = d.division_id)
   AND NOT EXISTS (SELECT 1 FROM hr.employee_assignment ea WHERE ea.division_id = d.division_id)
   AND NOT EXISTS (SELECT 1 FROM hr.department dept WHERE dept.division_id = d.division_id)
   AND NOT EXISTS (
           SELECT 1
             FROM hr.work_schedule_assignment wsa
            WHERE wsa.scope_type = 'DIVISION'
              AND wsa.scope_id = d.division_id
       );
