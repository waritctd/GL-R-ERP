-- V147: restore the three hr.employee foreign keys missing on prod (division_id, department_id,
-- position_id) -- issue #779.
--
-- WHAT'S MISSING, AND WHERE FROM. hr.employee is declared with EIGHT foreign keys, all inline
-- REFERENCES with no explicit name and no ON DELETE clause
-- (V1__employee_master_schema.sql:105 is the CREATE TABLE, :129-131 are the three this migration
-- restores):
--
--     division_id   SMALLINT REFERENCES hr.division(division_id),
--     department_id SMALLINT REFERENCES hr.department(department_id),
--     position_id   SMALLINT REFERENCES hr.position(position_id),
--
-- With no name given, Postgres names an inline REFERENCES "<table>_<column>_fkey", and with no ON
-- DELETE clause the action defaults to NO ACTION. So V1 creates exactly:
--     employee_division_id_fkey   FOREIGN KEY (division_id)   REFERENCES hr.division   ON DELETE NO ACTION
--     employee_department_id_fkey FOREIGN KEY (department_id) REFERENCES hr.department ON DELETE NO ACTION
--     employee_position_id_fkey   FOREIGN KEY (position_id)   REFERENCES hr.position   ON DELETE NO ACTION
--
-- Measured 2026-08-14 (see V146's own comment, and issue #779): prod's hr.employee carries only 5
-- of the 8 -- level_id, location_id, reports_to_employee_id, status_id, title_id -- and is missing
-- exactly these three. UAT has all 8, and replaying this repo's full chain (V1..V146) into a fresh
-- database also produces all 8. No migration in this repo drops them, so they were almost
-- certainly dropped by hand during the original ETL import and never restored.
--
-- Issue #779 reports that nothing is broken by their absence today -- "all 211 prod employees
-- resolve to a live division, department and position" -- but THAT SPECIFIC CLAIM IS REPORTED, NOT
-- INDEPENDENTLY RE-VERIFIED HERE: it comes from the same source as V146's original comment, whose
-- *adjacent* claims about this same table (which environment has which rows, which FKs prod is
-- missing) were themselves measured wrong once and needed a follow-up correction (#778) to fix.
-- Treat "211, all resolving" as a reported data point, not an established fact this migration relies
-- on. It does not need to be true for this migration to be safe: guard 3 below is the actual
-- protection, asserted fresh at apply time regardless of what anyone measured or believed
-- beforehand. If the report is stale or wrong and a dangling row exists by the time this runs, guard
-- 3 is what catches it -- that is the whole point of checking at apply time instead of trusting a
-- point-in-time count from whenever this comment was written.
--
-- What is NOT in question: a DELETE of a referenced hr.division / hr.department / hr.position row
-- would currently succeed silently on prod and orphan the referencing hr.employee row(s), where
-- every other environment already refuses it -- that follows directly from the FKs being absent,
-- independent of whether any row happens to be dangling right now.
--
-- FIVE GUARDS, EACH FOR A STATED REASON:
--
-- 1) MUST BE A NO-OP WHERE THE CONSTRAINT ALREADY EXISTS. Every fresh test database, UAT, and
--    every dev environment already has all three (V1 creates them) -- prod is the outlier here,
--    not the norm. This repo's backend suite replays the full migration chain on a fresh Postgres
--    for every integration test run (AbstractPostgresIntegrationTest's golden-template clone), so
--    a blind ADD CONSTRAINT would hit a duplicate-object error on the very first CI run and never
--    reach prod at all. Each DO block below checks pg_constraint before adding anything, and skips
--    entirely when a match is already present.
--
-- 2) DETECT BY COLUMN + REFERENCED TABLE, NEVER BY CONSTRAINT NAME ALONE. A differently-named FK on
--    the same column, to the same table, enforces the identical rule, and adding a second one over
--    it would double-enforce for no reason. Each check reads pg_constraint joined to pg_attribute
--    via conkey (conrelid = 'hr.employee'::regclass, contype = 'f', the column's attnum via
--    pg_attribute, confrelid = the referenced table, and conkey restricted to exactly that single
--    column so a composite FK that merely happens to include this column does not count as a
--    match -- a composite FK does not enforce the same single-column rule).
--
-- 3) FAIL LOUDLY ON A DANGLING ROW; NEVER SILENTLY REWRITE ONE. Before adding each constraint, this
--    migration asserts zero rows have a non-NULL value in that column with no matching parent row.
--    A NULL is NOT dangling -- these three columns are nullable and NULL is a legitimate "not
--    (yet) assigned" state; only a non-NULL value with no matching parent counts. If a dangling row
--    is found, the migration RAISES rather than nulling the column out or repointing it at a
--    placeholder row. This is a deliberate decision, not an oversight: mangling a real HR record's
--    division/department/position is a DATA change nobody has authorised, and doing it silently
--    inside a schema migration would be worse still -- nobody reviewing this file approved changing
--    what any employee's row says. A wedged Flyway chain is loud, visible in CI/deploy logs, and
--    trivially recoverable (fix the offending data, or extend this migration, and re-run); a
--    silently altered HR row is neither seen nor undone. Every RAISE EXCEPTION below names the
--    column and the offending employee_id(s) (capped to the first 10, alongside the true total
--    count) so whoever hits it can act without first having to write their own query.
--
-- 4) ADD CONSTRAINT ... NOT VALID, THEN VALIDATE CONSTRAINT -- WITH AN HONEST CAVEAT ABOUT WHAT
--    THAT BUYS HERE. The general benefit of this idiom is real: ADD CONSTRAINT ... NOT VALID takes
--    an ACCESS EXCLUSIVE lock only for a fast catalog update (no table scan), and a later, separate
--    VALIDATE CONSTRAINT then scans under the much weaker SHARE UPDATE EXCLUSIVE, which does not
--    block ordinary reads/writes -- PROVIDED the two run as genuinely separate transactions. Flyway
--    runs this entire file as ONE transaction, and Postgres does not downgrade a lock mid-transaction
--    -- once the ADD CONSTRAINT step has taken ACCESS EXCLUSIVE on hr.employee, that lock is held
--    for the rest of THIS transaction regardless of what the later VALIDATE CONSTRAINT statement
--    would have settled for on its own. So, run the way this migration actually runs, the weaker-lock
--    benefit does not materialise: ordinary reads/writes to hr.employee ARE blocked for the combined
--    duration of both statements, not just the ADD. What is still true, and is the real reason to
--    keep this pattern: the ADD itself stays a fast metadata-only operation regardless (no table
--    scan happens under the exclusive lock, only under the following, table-scanning VALIDATE, which
--    happens to run under the same lock here rather than a weaker one), it is the correct idiom if
--    this migration is ever restructured to run VALIDATE CONSTRAINT as its own later migration/
--    transaction, and it costs nothing to write it this way now. What actually makes the whole
--    question moot today is simply table size, not the locking idiom: hr.employee is reported at
--    roughly 211 rows (2026-08-14, same reporting caveat as above) and a full validation scan over
--    that many rows completes essentially instantly under any lock strength, and hr.employee is not
--    a table this system writes to at any real frequency either.
--
-- 5) MATCH V1's SEMANTICS EXACTLY. Each ADD CONSTRAINT below uses the same name Postgres itself
--    generates for V1's inline, unnamed REFERENCES, so a fresh database and a repaired prod end up
--    byte-for-byte the same shape in pg_constraint, not merely functionally equivalent. ON DELETE
--    NO ACTION is written out explicitly below for this migration's own readability, even though it
--    is also simply Postgres's default when no ON DELETE clause is given (which is what V1 relies
--    on implicitly) -- pg_get_constraintdef and pg_constraint.confdeltype render NO ACTION
--    identically either way, so being explicit here changes nothing about what gets stored. If prod
--    ended up with differently-named or differently-behaved constraints, this migration would have
--    replaced one drift from a fresh database with another, quieter one.
--
-- Companion test: EmployeeReferenceForeignKeyIntegrationTest, in two parts. Part 1 asserts all
-- three are present (matched by column + referenced table, the same way the guards below check)
-- and actually enforcing on this suite's normal fully-migrated database -- a backstop against a
-- future hand-drop, or a bad migration that removes one of these again. Part 1 alone is NOT
-- evidence this file's own logic works, since V1 already creates all three on every database that
-- test touches, so the guards below always take their no-op branch there. Part 2 builds the state
-- this migration actually has to handle -- the constraint missing, prod's real shape -- and runs
-- this file's own SQL (read live off the classpath) against it, covering both the restore path and
-- guard 3's RAISE. Mutation-checked: replacing this file's body with a no-op leaves Part 1 green
-- and turns every Part 2 test red.

-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- division_id -> hr.division
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_dangling_count integer;
    v_dangling_ids   text;
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conrelid = 'hr.employee'::regclass
           AND c.contype = 'f'
           AND c.confrelid = 'hr.division'::regclass
           AND c.conkey = ARRAY[(
                   SELECT a.attnum
                     FROM pg_attribute a
                    WHERE a.attrelid = 'hr.employee'::regclass
                      AND a.attname = 'division_id'
                      AND NOT a.attisdropped
               )]
    ) THEN
        -- Assert no dangling row BEFORE adding the constraint -- see guard 3 above.
        SELECT count(*),
               string_agg(ranked.employee_id::text, ', ' ORDER BY ranked.employee_id)
                   FILTER (WHERE ranked.rn <= 10)
          INTO v_dangling_count, v_dangling_ids
          FROM (
                   SELECT e.employee_id,
                          row_number() OVER (ORDER BY e.employee_id) AS rn
                     FROM hr.employee e
                    WHERE e.division_id IS NOT NULL
                      AND NOT EXISTS (
                              SELECT 1 FROM hr.division d WHERE d.division_id = e.division_id
                          )
               ) ranked;

        IF v_dangling_count > 0 THEN
            RAISE EXCEPTION
                'V147: refusing to add employee_division_id_fkey -- % hr.employee row(s) have a '
                'non-NULL division_id with no matching hr.division row. Offending employee_id(s), '
                'first 10 of %: %',
                v_dangling_count, v_dangling_count, v_dangling_ids;
        END IF;

        ALTER TABLE hr.employee
            ADD CONSTRAINT employee_division_id_fkey
            FOREIGN KEY (division_id) REFERENCES hr.division (division_id)
            ON DELETE NO ACTION
            NOT VALID;

        ALTER TABLE hr.employee
            VALIDATE CONSTRAINT employee_division_id_fkey;
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- department_id -> hr.department
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_dangling_count integer;
    v_dangling_ids   text;
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conrelid = 'hr.employee'::regclass
           AND c.contype = 'f'
           AND c.confrelid = 'hr.department'::regclass
           AND c.conkey = ARRAY[(
                   SELECT a.attnum
                     FROM pg_attribute a
                    WHERE a.attrelid = 'hr.employee'::regclass
                      AND a.attname = 'department_id'
                      AND NOT a.attisdropped
               )]
    ) THEN
        -- Assert no dangling row BEFORE adding the constraint -- see guard 3 above.
        SELECT count(*),
               string_agg(ranked.employee_id::text, ', ' ORDER BY ranked.employee_id)
                   FILTER (WHERE ranked.rn <= 10)
          INTO v_dangling_count, v_dangling_ids
          FROM (
                   SELECT e.employee_id,
                          row_number() OVER (ORDER BY e.employee_id) AS rn
                     FROM hr.employee e
                    WHERE e.department_id IS NOT NULL
                      AND NOT EXISTS (
                              SELECT 1 FROM hr.department dept
                               WHERE dept.department_id = e.department_id
                          )
               ) ranked;

        IF v_dangling_count > 0 THEN
            RAISE EXCEPTION
                'V147: refusing to add employee_department_id_fkey -- % hr.employee row(s) have a '
                'non-NULL department_id with no matching hr.department row. Offending '
                'employee_id(s), first 10 of %: %',
                v_dangling_count, v_dangling_count, v_dangling_ids;
        END IF;

        ALTER TABLE hr.employee
            ADD CONSTRAINT employee_department_id_fkey
            FOREIGN KEY (department_id) REFERENCES hr.department (department_id)
            ON DELETE NO ACTION
            NOT VALID;

        ALTER TABLE hr.employee
            VALIDATE CONSTRAINT employee_department_id_fkey;
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- position_id -> hr.position
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_dangling_count integer;
    v_dangling_ids   text;
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint c
         WHERE c.conrelid = 'hr.employee'::regclass
           AND c.contype = 'f'
           AND c.confrelid = 'hr.position'::regclass
           AND c.conkey = ARRAY[(
                   SELECT a.attnum
                     FROM pg_attribute a
                    WHERE a.attrelid = 'hr.employee'::regclass
                      AND a.attname = 'position_id'
                      AND NOT a.attisdropped
               )]
    ) THEN
        -- Assert no dangling row BEFORE adding the constraint -- see guard 3 above.
        SELECT count(*),
               string_agg(ranked.employee_id::text, ', ' ORDER BY ranked.employee_id)
                   FILTER (WHERE ranked.rn <= 10)
          INTO v_dangling_count, v_dangling_ids
          FROM (
                   SELECT e.employee_id,
                          row_number() OVER (ORDER BY e.employee_id) AS rn
                     FROM hr.employee e
                    WHERE e.position_id IS NOT NULL
                      AND NOT EXISTS (
                              SELECT 1 FROM hr.position p WHERE p.position_id = e.position_id
                          )
               ) ranked;

        IF v_dangling_count > 0 THEN
            RAISE EXCEPTION
                'V147: refusing to add employee_position_id_fkey -- % hr.employee row(s) have a '
                'non-NULL position_id with no matching hr.position row. Offending employee_id(s), '
                'first 10 of %: %',
                v_dangling_count, v_dangling_count, v_dangling_ids;
        END IF;

        ALTER TABLE hr.employee
            ADD CONSTRAINT employee_position_id_fkey
            FOREIGN KEY (position_id) REFERENCES hr.position (position_id)
            ON DELETE NO ACTION
            NOT VALID;

        ALTER TABLE hr.employee
            VALIDATE CONSTRAINT employee_position_id_fkey;
    END IF;
END $$;
