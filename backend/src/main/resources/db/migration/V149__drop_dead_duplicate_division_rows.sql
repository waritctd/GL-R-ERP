-- V149: delete dead, zero-reference hr.division rows that duplicate the name_th of a division that
-- survives -- legacy import duplicates, not distinct organizational units.
--
-- OWNER RULING (2026-08-16): division_id 38 (source_code 'QC', name_th 'QC&ISO', currently 2
-- employees, 1 department) and division_id 18 (source_code '0009', name_th 'QC&ISO', zero
-- references anywhere) are the SAME division. 18 is an earlier/legacy import row superseded by 38
-- and never cleaned up -- the same "earlier import generation, superseded by a coded row covering
-- the same ฝ่าย, never cleaned up" shape V146 already documented for ten other hr.division rows.
-- Row 38 is canonical. This migration never touches it: it has real references, so it can never
-- satisfy guard 1-3 below and is not a candidate under any circumstance.
--
-- WHAT ELSE THIS TABLE CARRIES. Two more zero-reference "Sales Support" name collisions exist:
--   division_id  6  source_code '0011'  name_th 'Sales Support 1'
--   division_id 12  source_code '0013'  name_th 'Sales Support 1'
--   division_id  8  source_code '0014'  name_th 'Sales Support 2'   (ONE space before '2')
--   division_id 13  source_code '0012'  name_th 'Sales Support  2'  (TWO spaces before '2')
-- All four are dead (zero hr.employee, zero hr.department, zero hr.employee_assignment rows), but
-- they do NOT all delete under the guard below -- see "WHY ONLY SOME OF THE FIVE GO". That is the
-- guard working as designed, not a shortfall: deleting every zero-reference division outright would
-- also delete a uniquely-named division that simply has no staff assigned yet, which is a different
-- and unsafe thing to do.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- THE GUARD. A division row is deleted only when ALL of the following hold:
--
--   1. Zero rows in hr.employee reference it (hr.employee.division_id = division_id).
--   2. Zero rows in hr.department reference it (hr.department.division_id = division_id).
--   3. Zero rows in hr.employee_assignment reference it (hr.employee_assignment.division_id =
--      division_id).
--
--      These are the three (and, confirmed below, ONLY three) foreign keys in the entire schema
--      Flyway owns -- hr, hr_restricted, sales, customers, price_catalog -- with
--      confrelid = 'hr.division'::regclass: employee_division_id_fkey, department_division_id_fkey
--      and employee_assignment_division_id_fkey, all declared inline by V1 on exactly the three
--      tables named above and nowhere else (grepped across every migration; V147 only re-validates
--      the first of the three, under an IF NOT EXISTS guard, it does not add a new one). Confirmed
--      live against pg_constraint by DivisionDedupeIntegrationTest#exactlyThreeForeignKeysReferenceHrDivision_confirmedFromPgConstraint,
--      not assumed -- so a fourth FK added later fails that test instead of silently going unnoticed
--      in this comment.
--
--   4. Zero rows in hr.work_schedule_assignment reference it via the polymorphic
--      (scope_type = 'DIVISION' AND scope_id = division_id) pattern. This is NOT one of the "three
--      foreign keys" above -- hr.work_schedule_assignment.scope_id is a bare BIGINT with no FK of
--      its own, untyped BY DESIGN per V115's own comment on that table, precisely because it points
--      into whichever of hr.employee / hr.department / hr.division scope_type names -- so
--      pg_constraint cannot see this reference and guards 1-3 alone cannot catch it. V146's own
--      migration comment already flagged hr.work_schedule_assignment as exactly this kind of
--      hazard for hr.division. Belt-and-suspenders: none of the five candidate rows above are
--      seeded into hr.work_schedule_assignment by V115/V117 (their source_codes -- '0009', '0011',
--      '0013', '0014', '0012' -- are not in either seed list, which key off 'QC', 'SA', 'WH' and
--      the like), so this guard changes nothing for the data this migration sees today. It stays as
--      a structural guard, not a currently load-bearing one for these five rows, so a future
--      DIVISION-scope schedule assignment on one of them -- however unlikely -- is never silently
--      orphaned by this migration.
--
--   5. Another hr.division row shares the EXACT same name_th AND is not itself being deleted by
--      this same migration (so a uniquely-named empty division, with no duplicate to pair against,
--      is never touched). "Not itself being deleted" means either: that other row fails guard 1-4
--      (it has a real reference, so it survives unconditionally -- the 38/18 case), or, when an
--      entire name_th group is zero-reference throughout (no row to anchor it that way), it wins
--      the deterministic tie-break in guard 6.
--
--   6. TIE-BREAK, consulted only when a name_th group has no reference-holding row to anchor it --
--      i.e. every row sharing that name_th is itself zero-reference, so guard 5 cannot be satisfied
--      by "the other row obviously survives" and one row must be chosen to survive on purpose (the
--      'Sales Support 1' pair below). Rule, applied per name_th group: keep the row with a
--      non-numeric source_code if one exists in the group, else keep the row with the lowest
--      division_id. Deterministic, and re-derived from live data every time this migration runs --
--      never a hardcoded id, because surrogate ids differ across prod/UAT/dev/every test database.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- WHY ONLY SOME OF THE FIVE GO. Walking all six known rows through guards 1-6:
--
--   38  QC&ISO            (QC)    2 employees, 1 department -> fails guard 1/2 -> NEVER a
--                                  candidate. Canonical, untouched. Also makes 18 deletable: 38 is
--                                  "another division, same name_th, not being deleted" for guard 5,
--                                  with no need for the guard-6 tie-break.
--   18  QC&ISO            (0009) zero refs, pairs with surviving 38 via guard 5 -> DELETED.
--   6   Sales Support 1   (0011) zero refs. Its only same-name partner is 12, ALSO zero-ref, so
--                                  guard 5 needs guard 6: both source_codes ('0011', '0013') are
--                                  fully numeric, so the tie-break falls to the lower division_id ->
--                                  6 is the keeper -> SURVIVES.
--   12  Sales Support 1   (0013) zero refs, loses the guard-6 tie-break to 6 -> DELETED.
--   8   Sales Support 2   (0014) zero refs, but 'Sales Support  2' (13, below) is a DIFFERENT
--                                  string (two spaces, not one) -- name_th matching is exact, by
--                                  design, see the warning below -- so 8 has NO same-name partner at
--                                  all -> guard 5 fails -> SURVIVES, exactly like any other
--                                  uniquely-named empty division.
--   13  Sales Support  2  (0012) symmetric with 8: unique string, no partner -> guard 5 fails ->
--                                  SURVIVES.
--
-- Net effect: this migration deletes division_id 18 and 12, and leaves 38, 6, 8 and 13 in place.
-- THIS IS THE INTENDED, SAFER OUTCOME, not a shortfall against "delete all five": 6 is the sole
-- survivor of an all-zero-reference name group, and 8/13 are each uniquely named with no duplicate
-- to pair against -- guard 5 exists precisely to leave rows in that shape alone. Do NOT "fix" this
-- migration to force all five out via a hardcoded id list -- that is exactly the anti-pattern this
-- file is required not to be, and it would make 8 and 13 indistinguishable from a genuine
-- uniquely-named empty division created moments before this runs.
--
-- ⚠️ NAME MATCHING IS BYTE-EXACT, DELIBERATELY. 'Sales Support 2' and 'Sales Support  2' (double
-- space) are different strings, and this migration must never paper over that with a normalizing
-- comparison (e.g. collapsing whitespace) -- doing so would make guard 5 pair them and delete BOTH
-- zero-reference "Sales Support 2"-shaped rows, leaving zero rows named either way. If HR's records
-- confirm 8 and 13 really are the same division under a typo, that is a data-correction decision for
-- a human via a future migration that states the correction explicitly -- not something this
-- byte-exact guard should infer silently.
--
-- IDEMPOTENT BY CONSTRUCTION, like V146: every condition above is re-evaluated against row state at
-- apply time, matched by name_th + emptiness, never by a hardcoded division_id. Re-running this file
-- after it has already deleted 18 and 12 finds no remaining row that satisfies guards 1-6 (6, 8 and
-- 13's name_th groups no longer have a losing duplicate to remove) and deletes 0 rows. Deleting zero
-- rows is success, not failure: this migration also runs against every fresh test database and every
-- dev/UAT environment that never had these specific rows at all.
--
-- DELETE, NOT is_active = FALSE, for the same reason as V146: this repo's
-- EmployeeReferenceRepository#findOrInsertDivisionByName resolves by name_th with no is_active
-- filter, so a deactivated duplicate would still be found and silently reused by a future import.

WITH reference_free AS (
    -- Guards 1-4: zero rows in each of the three FK-backed tables, and zero DIVISION-scope rows in
    -- the one known polymorphic non-FK reference to hr.division (see guard 4's comment above).
    SELECT d.division_id, d.name_th, d.source_code
      FROM hr.division d
     WHERE NOT EXISTS (SELECT 1 FROM hr.employee e WHERE e.division_id = d.division_id)
       AND NOT EXISTS (SELECT 1 FROM hr.department dept WHERE dept.division_id = d.division_id)
       AND NOT EXISTS (SELECT 1 FROM hr.employee_assignment ea WHERE ea.division_id = d.division_id)
       AND NOT EXISTS (
               SELECT 1 FROM hr.work_schedule_assignment wsa
                WHERE wsa.scope_type = 'DIVISION' AND wsa.scope_id = d.division_id
           )
),
protected_rows AS (
    -- Every division NOT in reference_free survives unconditionally (it has a real reference), so
    -- it can anchor guard 5 for a same-named reference_free duplicate without needing guard 6.
    SELECT d.division_id, d.name_th
      FROM hr.division d
     WHERE d.division_id NOT IN (SELECT rf.division_id FROM reference_free rf)
),
ranked AS (
    -- Guard 6's deterministic tie-break, computed for every reference_free row regardless of
    -- whether it ends up needed: non-numeric source_code first, else lowest division_id, per
    -- name_th group. Only consulted below when no protected row shares the group's name_th.
    SELECT rf.division_id,
           rf.name_th,
           ROW_NUMBER() OVER (
               PARTITION BY rf.name_th
               ORDER BY (COALESCE(rf.source_code, '') ~ '^[0-9]+$') ASC,  -- FALSE (non-numeric) first
                        rf.division_id ASC
           ) AS rank_within_name
      FROM reference_free rf
)
DELETE FROM hr.division d
 WHERE d.division_id IN (
     SELECT r.division_id
       FROM ranked r
      WHERE EXISTS (SELECT 1 FROM protected_rows p WHERE p.name_th = r.name_th)  -- guard 5, easy case
         OR r.rank_within_name > 1                                              -- guard 5+6, all-zero-ref group
 );
