-- =====================================================================
-- DEMO PROFILE ONLY: close the two seed gaps that made real-backend
-- verification impossible for three roles and one whole workflow.
--
-- Both gaps were recorded in frontend/e2e-real/README.md ("Not covered —
-- read this before citing a green run") and neither was a code defect:
-- the suite could not reach the behaviour because the seed could not
-- produce the rows.
--
-- GAP 1 — three roles had no login at all.
--   DivisionAccessPolicy.roleFor returns nine role strings; V21 seeds six.
--   AC -> account, WH -> warehouse and QC -> qc had no demo employee, so
--   api-authz / write-authz / route-coverage swept six roles and said
--   nothing whatsoever about the other three. `account` was the sharp
--   edge: it is in TicketAccessPolicy.VIEWER_ROLES and is the ONLY role
--   permitted to confirm a payment, and no automated test had ever
--   exercised it.
--
-- GAP 2 — every demo employee had hire_date IS NULL.
--   That single null is the root cause of all four symptoms the README
--   lists for leave. LeaveService#employeeAnnualQuota returns ZERO when
--   findHireDate is empty, so VACATION (6.00) and PERSONAL (3.00) both
--   prorate to nothing and every request fails closed on quota; and
--   #autoRejectNote fails ORDINATION with HIRE_DATE_MISSING_MIN_SERVICE
--   before it can reach a reviewable state. The net effect was that no
--   leave request could ever land in SUBMITTED, so HR's leave-review
--   authorization was unreachable and therefore untested -- the exact
--   counterpart to #199 (LeaveService.REVIEW_ALL_ROLES is {hr}, so HR CAN
--   review leave while OvertimeService refuses HR an overtime approval).
--
-- Numbered V139 deliberately: V138 is the highest applied version on both
-- Supabase projects, and with out-of-order unset a migration numbered
-- below the applied max is SILENTLY SKIPPED on a green deploy (the failure
-- mode V67's header records and issue #439 re-raised). A demo-seed file
-- numbered in the V2x/V9x range next to V21 would look tidy and never run.
--
-- Safety — the V21 payroll rule still holds, and this file does not weaken it:
--   * These personas carry NO current_salary and NO director_remuneration.
--     PayrollRepository.findActiveEmployees() has no DEMO- prefix filter;
--     the ONLY thing keeping demo rows out of a real payroll batch is its
--     COALESCE(current_salary,0) > 0 OR COALESCE(director_remuneration,0) > 0
--     condition. hire_date is not part of that predicate, so backfilling it
--     cannot sweep a demo row into a payroll run.
--   * All rows keep the 'DEMO-' employee_code prefix so they stay trivially
--     identifiable and removable.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Reference data. ON CONFLICT DO NOTHING keeps this a no-op against the
-- real databases, which already carry AC / WH / QC (verified live on both
-- Supabase projects, 2026-08-10) -- it only backfills a fresh or CI DB.
--
-- The source_code matters and is not cosmetic: DivisionAccessPolicy
-- lowercases source_code and compares it exactly, so 'QC' resolves to the
-- qc role while the co-existing 'QC&ISO'-named row would fall through to
-- plain employee. Seed the code the policy actually matches.
-- ---------------------------------------------------------------------
INSERT INTO hr.division (source_code, name_th) VALUES
    ('AC', 'AC-ฝ่ายบัญชี'),
    ('WH', 'WH-คลังสินค้า'),
    ('QC', 'QC&ISO')
ON CONFLICT (source_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- The three missing personas. Same BCrypt hash as V21 (Demo@2026), same
-- DEMO-STAFF position, so DivisionAccessPolicy.isManager stays false and
-- each resolves purely on its division.
-- ---------------------------------------------------------------------
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th, email,
    division_id, position_id, password_hash, must_change_password, is_active
)
SELECT v.employee_code, v.first_name_th, v.last_name_th, v.email,
       d.division_id, p.position_id,
       '$2y$10$dgX94V4KgoGZzoiJPGiA9.Xa3M1GLBph8x1yOyTPqr8c9DPF4Fkt2',
       FALSE, TRUE
FROM (VALUES
    ('DEMO-ACC01', '[DEMO]', 'ฝ่ายบัญชี',     'demo.account@demo.invalid',   'AC', 'DEMO-STAFF'),
    ('DEMO-WH01',  '[DEMO]', 'ฝ่ายคลังสินค้า', 'demo.warehouse@demo.invalid', 'WH', 'DEMO-STAFF'),
    ('DEMO-QC01',  '[DEMO]', 'ฝ่ายควบคุมคุณภาพ', 'demo.qc@demo.invalid',        'QC', 'DEMO-STAFF')
) AS v(employee_code, first_name_th, last_name_th, email, division_code, position_code)
LEFT JOIN hr.division d ON d.source_code = v.division_code
LEFT JOIN hr.position p ON p.source_code = v.position_code
ON CONFLICT (employee_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- Backfill hire_date on every demo persona, including the three above.
--
-- Three years before the migration runs, so completed service exceeds
-- LeaveService.FULL_SERVICE_MONTHS and the prorating branch returns the
-- type's full annual_quota_days rather than a fraction. A fixed literal
-- would silently drift back below the probation and min-service
-- thresholds as the demo DB ages; an interval keeps the seed correct
-- whenever it is first applied.
--
-- Guarded on hire_date IS NULL so this never overwrites a date someone
-- has deliberately set on a long-lived demo database.
-- ---------------------------------------------------------------------
UPDATE hr.employee
   SET hire_date = (CURRENT_DATE - INTERVAL '3 years')::date
 WHERE employee_code LIKE 'DEMO-%'
   AND hire_date IS NULL;
