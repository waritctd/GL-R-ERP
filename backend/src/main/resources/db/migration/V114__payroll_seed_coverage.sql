-- Close seed-covered payroll months to OT (and codify the hand-installed guard trigger).
--
-- Background: the owner wanted Mar-Jun 2026 marked PROCESSED on prod so nobody could file
-- overtime into those months. That write was correctly REFUSED by a trigger that was installed
-- BY HAND directly on prod (it is in NO migration -- `grep -rln guard_seeded_month_not_processed
-- backend/src/main/resources/db/` found nothing before this file). Those four months are already
-- covered by hr.payroll_year_to_date_seed (34 employees, PND1 already filed, back-loaded from the
-- accountant's workbooks) -- marking them PROCESSED here would count every affected employee's
-- year-to-date a second time and corrupt the rest of their 2026 withholding.
--
-- The hand-installed trigger's condition hardcoded that fact as two literals:
--   NEW.payroll_month < DATE '2026-07-01' AND EXTRACT(YEAR FROM NEW.payroll_month) = 2026
-- hr.payroll_seed_coverage below replaces those literals with a real table both this trigger and
-- OvertimeService#requirePayrollMonthOpen can read, so the next seeded year does not need another
-- hand edit to a live trigger.
--
-- payroll_year_to_date_seed itself is keyed (employee_id, tax_year) -- no month granularity -- so
-- it cannot answer "which months" on its own; that is the whole reason this table exists.

CREATE TABLE IF NOT EXISTS hr.payroll_seed_coverage (
    tax_year        SMALLINT NOT NULL PRIMARY KEY,
    -- First-of-month. This month and every earlier month in tax_year are seed-covered / closed.
    covers_through  DATE NOT NULL,
    note            TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payroll_seed_coverage_covers_through_first CHECK (
        covers_through = date_trunc('month', covers_through)::date
    ),
    CONSTRAINT chk_payroll_seed_coverage_year_matches CHECK (
        EXTRACT(YEAR FROM covers_through)::smallint = tax_year
    )
);

-- Seed the one known row -- guarded on payroll_year_to_date_seed actually having a 2026 row.
-- Prod has 34 such rows (fact verified live); a fresh/UAT database has none, and must NOT inherit
-- coverage that does not apply to it -- an unguarded insert here would silently close Mar-Jun on
-- every environment, including ones where payroll never ran outside the ERP for those months.
INSERT INTO hr.payroll_seed_coverage (tax_year, covers_through, note)
SELECT 2026, DATE '2026-06-01',
       'Jan-Jun 2026 paid outside the ERP; PND1 filed. Back-loaded to payroll_year_to_date_seed.'
 WHERE EXISTS (
     SELECT 1 FROM hr.payroll_year_to_date_seed WHERE tax_year = 2026
 )
ON CONFLICT (tax_year) DO NOTHING;

-- Drop whatever trigger(s) on hr.payroll_period currently invoke the hand-installed guard,
-- regardless of what the trigger object itself happens to be named.
--
-- The two names differ on prod and that matters -- verified live against the production database
-- (project tdyzcqzxmhtxpbouewud) via pg_trigger joined to pg_proc:
--     trigger object : trg_guard_seeded_month_not_processed
--     function       : hr.guard_seeded_month_not_processed   <- NO "trg_" prefix
-- An earlier draft of this block matched on proname = 'trg_guard_...' and therefore found nothing
-- on prod; the migration only still worked because the literal DROP TRIGGER below happens to match
-- the trigger's own name. That was luck, not design. Match BOTH the hand-installed function name
-- and this migration's own canonical one, so the block does its job on prod and stays idempotent
-- against itself. A database with neither (UAT, fresh) matches zero rows and this is a no-op.
--
-- Not filtered by schema on purpose: if the original was ever created with a default search_path it
-- could live in public, and matching by name alone still finds it.
DO $$
DECLARE
    old_trigger RECORD;
BEGIN
    FOR old_trigger IN
        SELECT t.tgname
          FROM pg_trigger t
          JOIN pg_proc p ON p.oid = t.tgfoid
         WHERE t.tgrelid = 'hr.payroll_period'::regclass
           AND NOT t.tgisinternal
           AND p.proname IN ('guard_seeded_month_not_processed',
                             'trg_guard_seeded_month_not_processed')
    LOOP
        EXECUTE format('DROP TRIGGER %I ON hr.payroll_period', old_trigger.tgname);
    END LOOP;
END;
$$;

-- The hand-installed function is now unreferenced (its trigger was just dropped, and the trigger
-- recreated below points at this migration's own function instead). Drop it rather than leave an
-- orphan behind, so "one source of truth" is actually true afterwards: a stale copy carrying the
-- old hardcoded `< DATE '2026-07-01'` literal would keep firing if anything ever re-attached it,
-- and would silently ignore hr.payroll_seed_coverage. No-op on UAT/fresh, where it never existed.
DROP FUNCTION IF EXISTS hr.guard_seeded_month_not_processed();

-- Also cover a rerun of this exact migration's own canonical trigger name (Testcontainers golden
-- template / repeated local migrate), so this file is idempotent against itself, not just against
-- the hand-installed original.
DROP TRIGGER IF EXISTS trg_guard_seeded_month_not_processed ON hr.payroll_period;

CREATE OR REPLACE FUNCTION hr.trg_guard_seeded_month_not_processed()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'PROCESSED'
       AND EXISTS (
           SELECT 1
             FROM hr.payroll_seed_coverage c
            WHERE c.tax_year = EXTRACT(YEAR FROM NEW.payroll_month)::smallint
              AND NEW.payroll_month <= c.covers_through
       )
    THEN
        RAISE EXCEPTION
            'งวดเงินเดือน % ถูกจ่ายนอกระบบไปแล้วตามข้อมูลยกยอดต้นปี (payroll_year_to_date_seed) และได้ยื่นแบบ ภ.ง.ด.1 แล้ว จึงไม่สามารถประมวลผล (PROCESSED) งวดนี้ซ้ำในระบบได้ เพราะจะทำให้ยอดสะสมรายปีของพนักงานถูกนับซ้ำ (payroll month % is already covered by payroll_year_to_date_seed and PND1 has already been filed -- marking it PROCESSED again would double-count every affected employee''s year-to-date withholding)',
            NEW.payroll_month, NEW.payroll_month;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION hr.trg_guard_seeded_month_not_processed() IS
    'Refuses to mark a hr.payroll_period row PROCESSED when its payroll_month is seed-covered '
    '(hr.payroll_seed_coverage). Prevents double-counting year-to-date withholding for months '
    'already paid outside the ERP and reported via PND1. Do not drop or weaken this trigger; '
    'see V114 migration header.';

CREATE TRIGGER trg_guard_seeded_month_not_processed
    BEFORE INSERT OR UPDATE ON hr.payroll_period
    FOR EACH ROW
    EXECUTE FUNCTION hr.trg_guard_seeded_month_not_processed();
