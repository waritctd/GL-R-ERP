-- ---------------------------------------------------------------------------
-- Approval-routing gap audit — run READ-ONLY against prod (and UAT) after the
-- ฝ่าย-manager-only / CEO-direct change.
--
-- Every predicate here is copied from ManagerApproverRepository so the numbers
-- describe what the application will actually do, not an approximation of it.
-- If you change that class, change this file in the same commit.
--
-- Nothing here writes. Safe to run on production.
-- ---------------------------------------------------------------------------

-- Shared shape: "this position makes someone a ผู้จัดการ", mirroring
-- DivisionAccessPolicy.isManager (strip whitespace, then substring match).
--   regexp_replace(COALESCE(p.name_th, ''), '\s+', '', 'g') LIKE '%ผู้จัดการ%'


-- === A. THE RESIDUAL GAP: still stuck, by deliberate design ================
-- The ฝ่าย HAS an active ผู้จัดการ (so no CEO bypass fires) but NONE of them has
-- a usable email, so none can log in and none can approve. These employees'
-- overtime still cannot be actioned by anybody.
--
-- This is a DATA fix (give the ผู้จัดการ an email), not a code one — widening
-- the bypass to "manager cannot log in" would hand the CEO a shortcut on the
-- strength of a missing email address.
SELECT e.employee_id, e.employee_code,
       concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
       d.name_th AS division
  FROM hr.employee e
  JOIN hr.division d ON d.division_id = e.division_id
 WHERE e.is_active
   AND NOT EXISTS (
       SELECT 1 FROM hr.position p
        WHERE p.position_id = e.position_id
          AND regexp_replace(COALESCE(p.name_th, ''), '\s+', '', 'g') LIKE '%ผู้จัดการ%')
   AND EXISTS (
       SELECT 1 FROM hr.employee m JOIN hr.position mp ON mp.position_id = m.position_id
        WHERE m.division_id = e.division_id AND m.is_active
          AND regexp_replace(COALESCE(mp.name_th, ''), '\s+', '', 'g') LIKE '%ผู้จัดการ%')
   AND NOT EXISTS (
       SELECT 1 FROM hr.employee m JOIN hr.position mp ON mp.position_id = m.position_id
        WHERE m.division_id = e.division_id AND m.is_active
          AND regexp_replace(COALESCE(mp.name_th, ''), '\s+', '', 'g') LIKE '%ผู้จัดการ%'
          AND m.email IS NOT NULL AND btrim(m.email) <> '')
 ORDER BY d.name_th, e.employee_code;


-- === B. WHO LOSES APPROVAL RIGHTS (the narrowing) =========================
-- People who are somebody's reports_to manager but are NOT a ผู้จัดการ. They
-- could approve/see/file-on-behalf for their reports before this change and
-- cannot now. Expect this list to be reviewed by HR, not silently accepted.
SELECT m.employee_id, m.employee_code,
       concat_ws(' ', m.first_name_th, m.last_name_th) AS former_approver,
       COALESCE(mp.name_th, '(no position)') AS position_th,
       count(*) AS reports_affected
  FROM hr.employee e
  JOIN hr.employee m ON m.employee_id = e.reports_to_employee_id
  LEFT JOIN hr.position mp ON mp.position_id = m.position_id
 WHERE e.is_active AND m.is_active
   AND regexp_replace(COALESCE(mp.name_th, ''), '\s+', '', 'g') NOT LIKE '%ผู้จัดการ%'
 GROUP BY 1, 2, 3, 4
 ORDER BY reports_affected DESC;


-- === C. WHICH ฝ่าย NOW ROUTE STRAIGHT TO THE CEO ==========================
-- Divisions with active staff but no active ผู้จัดการ. Every OT request from
-- these lands on the CEO directly. A large count here is a signal the org
-- chart is under-populated, not that the routing is wrong.
SELECT d.division_id, d.source_code, d.name_th,
       count(e.employee_id) AS active_staff
  FROM hr.division d
  JOIN hr.employee e ON e.division_id = d.division_id AND e.is_active
 WHERE NOT EXISTS (
       SELECT 1 FROM hr.employee m JOIN hr.position mp ON mp.position_id = m.position_id
        WHERE m.division_id = d.division_id AND m.is_active
          AND regexp_replace(COALESCE(mp.name_th, ''), '\s+', '', 'g') LIKE '%ผู้จัดการ%')
 GROUP BY 1, 2, 3
 ORDER BY active_staff DESC;


-- === D. ACTIVE EMPLOYEES WITH NO ฝ่าย AT ALL ==============================
-- division_id IS NULL means no ผู้จัดการ can ever match, so these also route
-- straight to the CEO. Usually a data-quality signal.
SELECT employee_id, employee_code,
       concat_ws(' ', first_name_th, last_name_th) AS employee_name
  FROM hr.employee
 WHERE is_active AND division_id IS NULL
 ORDER BY employee_code;


-- === E. WHO COULD IN PRINCIPLE SELF-APPROVE (owner-ruled: not a scenario) ==
-- A กรรมการผู้จัดการ is BOTH role=ceo (DivisionAccessPolicy: กรรมการ) AND a
-- ผู้จัดการ by position — so their own request would have no manager stage and
-- the only approver would be themselves. Same for welfare, which is CEO-only.
--
-- OWNER RULING 2026-08-03: CEO and HR are queue-side. They review other
-- people's requests and see company-wide activity; they do not file requests
-- for themselves. So no backend guard was added — there is no higher authority
-- to route to, and blocking it would only re-create the stall this change
-- removed. Keeping the query because it is the list to re-check if that ever
-- stops being true.
SELECT e.employee_id, e.employee_code,
       concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
       p.name_th AS position_th, d.name_th AS division
  FROM hr.employee e
  JOIN hr.position p ON p.position_id = e.position_id
  LEFT JOIN hr.division d ON d.division_id = e.division_id
 WHERE e.is_active
   AND regexp_replace(COALESCE(p.name_th, ''), '\s+', '', 'g') LIKE '%ผู้จัดการ%'
   -- This is the `ceo` ROLE test (DivisionAccessPolicy.roleFor: division code EXACTLY 'md', or a
   -- กรรมการ-family position), because this section asks who could self-APPROVE -- an authority
   -- question, decided by the role.
   -- Deliberately NOT the same as CeoApproverRule.SQL_PREDICATE, which decides who gets NOTIFIED
   -- and is narrower still (กรรมการผู้จัดการ only, owner ruling 2026-08-10). Keeping the two apart
   -- is the point: the other executives can still approve, they are just no longer told.
   AND (LOWER(BTRIM(d.source_code)) = 'md'
        OR regexp_replace(COALESCE(p.name_th, ''), '\s+', '', 'g') LIKE '%กรรมการ%')
 ORDER BY e.employee_code;


-- === F. WELFARE ROWS STRANDED IN THE REMOVED MANAGER STAGE ================
-- Welfare no longer has a manager stage. Nothing new can enter MANAGER_APPROVED,
-- but rows written before the change can be sitting in it. The CEO can still
-- clear these (SpecialMoneyService keeps the legacy branch) — this just counts
-- them so nobody is surprised.
SELECT status, count(*) AS rows
  FROM hr.special_money_request
 GROUP BY status
 ORDER BY status;
