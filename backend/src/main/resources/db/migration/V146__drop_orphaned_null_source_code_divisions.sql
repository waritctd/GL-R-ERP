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
-- ⚠️ WHICH ENVIRONMENT HAS THESE ROWS: UAT, NOT PROD. The first version of this comment said prod
-- throughout. Re-measured against BOTH databases on 2026-08-14, before this migration had applied
-- anywhere, that attribution is inverted:
--
--   - UAT:  29 divisions, 10 with a blank/NULL source_code -- exactly the ten names listed below,
--     and all ten genuinely orphaned: zero employees, zero employee_assignment rows, zero
--     departments and zero work_schedule_assignment DIVISION scopes, for every one.
--   - PROD: 19 divisions, and NOT ONE has a blank/NULL source_code. Zero of the ten names exist
--     there at all.
--
-- So this migration deletes 10 rows on UAT and 0 on prod. See the row-count note at the bottom --
-- expecting 10 on prod is what the original text told the next operator to do, and it would have
-- looked exactly like the byte-exact name drift that same note warns about.
--
-- THAT ALSO RE-FRAMES WHAT THIS IS FOR. The divergence described above cannot occur on prod today:
-- every prod division carries a source_code, so the name-prefix fallback and the ILIKE predicates
-- agree on every row. This is UAT hygiene, not a prod fix. The code-level exposure below is real in
-- both environments regardless -- the insert path that CREATES a blank-code row is reachable over
-- HTTP on prod too -- so nothing here argues the durable fix matters less.
--
-- The ten read as an earlier import generation, superseded by a coded row covering the same ฝ่าย and
-- never cleaned up.
--
-- THIS IS A CLEANUP, NOT A PERMANENT FIX -- do not read it as closing the divergence for good.
-- An earlier draft of this comment claimed each of the ten "duplicates the name of an already-coded
-- division", which would have meant findOrInsertDivisionByName's unordered `LIMIT 1` could resolve
-- to the surviving coded row afterwards. That was reported as FALSE for all ten: the coded siblings
-- cover the same ฝ่าย under a DIFFERENT name ('ฝ่ายขาย' vs 'SA-ฝ่ายขาย', 'ฝ่ายคลังสินค้า' vs
-- 'WH-คลังสินค้า', and so on), so zero of the ten names have a coded row of the same name.
-- ⚠️ THAT SUB-CLAIM WAS NOT RE-VERIFIED by the 2026-08-14 correction above, and it was originally
-- attributed to prod -- where, as established, none of the ten rows exist, so it cannot have been
-- measured there. It is consistent with what prod DOES show (prod carries 'ฝ่ายขาย'/SA and
-- 'ฝ่ายคลังสินค้า'/WH, i.e. the differently-named coded siblings, and none of the ten prefixed
-- names), but treat it as unconfirmed on UAT until someone checks. It does not affect this
-- migration's safety either way: the DELETE never touches a coded row, because the predicate
-- requires a blank source_code.
-- So nothing stops the shape coming back: findOrInsertDivisionByName's insert is
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
-- THE WHERE CLAUSE RE-VERIFIES ORPHANHOOD AT APPLY TIME, NOT JUST THE NAME. BOTH prod and UAT were
-- at V144 when this was written -- two versions behind this repo, and neither had applied V145 or
-- V146 -- so this may run weeks later against different data. A name match alone is not proof a row
-- is still unreferenced. Four reference paths are checked directly below:
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
-- ⚠️ THE SAFETY CONCLUSION THAT FOLLOWED FROM THIS WAS INVERTED, in the same way as the paragraph
-- at the top. The original text read "ON PROD -- the only environment where these ten rows actually
-- exist -- hr.employee has NONE [of the backstops]", making the explicit NOT EXISTS checks below
-- sound like the only thing standing between this DELETE and an orphaned employee row. Both halves
-- were measured again on 2026-08-14, and they do not line up that way:
--
--   - PROD is missing the three FKs, exactly as stated above -- but prod has none of the ten rows,
--     so this migration deletes nothing there and the missing backstop never comes into play.
--   - UAT, the environment where all ten rows actually live and where this DELETE actually does
--     something, has ALL EIGHT of the FKs V1 declares on hr.employee, division_id included.
--
-- So in the only environment that deletes anything, the database-level backstop is fully present:
-- Postgres itself would refuse a DELETE that orphaned an employee, employee_assignment or
-- department row. This migration is SAFER than the original comment claimed, not less safe.
--
-- Keep the explicit checks anyway, and keep them mutation-checked. They are what makes the DELETE
-- correct on a database whose constraints you have not personally read -- which is the situation
-- every future environment is in, and which prod's own three missing FKs prove is not hypothetical.
-- The work_schedule_assignment check in particular has no FK to fall back on ANYWHERE (scope_id is
-- polymorphic, see above), so it is the one path that is only ever protected by the SQL below.
-- (Nothing is currently broken by prod's missing constraints: all 211 prod employees resolve to a
-- live division, department and position. The exposure is that nothing stops that changing. Tracked
-- separately in issue #779, which also explains why restoring them is not a one-line ADD CONSTRAINT:
-- it has to handle a dangling row appearing between authoring and applying, or it wedges the chain.)
-- Restoring those three constraints is a separate piece of work and deliberately NOT bundled here:
-- it would need its own migration, its own verification that no dangling row exists at apply time,
-- and its own decision about what to do if one appears.
--
-- Deleting zero rows is success, not failure: this migration also runs against every fresh test
-- database and every dev/UAT environment, where these ten rows may never have existed at all. A
-- plain DELETE already satisfies that -- no row-count assertion is added.
--
-- THE COST OF THAT CHOICE: a successful cleanup and a silent no-op look identical. Matching is
-- byte-exact, so one character of drift in a name produces a run that reports nothing wrong and
-- deletes nothing. 'SS1-Sales support 1' is the likeliest to bite -- the only mixed-case ASCII name
-- in the list, and this same table already carries a 'Sales Support  2' with a DOUBLE space. The
-- direction is safe (it under-deletes, never over-deletes).
--
-- ⚠️ EXPECTED ROW COUNTS, PER ENVIRONMENT (measured 2026-08-14, before this had applied anywhere):
--     UAT  -> 10 rows deleted
--     PROD ->  0 rows deleted, because none of the ten names exist there
-- The original text said "expected 10" without naming an environment, and told whoever applied it
-- to prod to check for that. They would have found 0 -- and this very paragraph would have handed
-- them the wrong explanation, since a 0 that means "these rows were never here" is indistinguishable
-- from the byte-exact name drift described above. DO NOT "FIX" THE NAME LIST TO MAKE PROD DELETE
-- SOMETHING. A no-op on prod is the correct outcome; every prod division has a source_code, so the
-- blank-code predicate excludes all 19 of them by design.
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
