-- ---------------------------------------------------------------------------
-- CEO-notification recipient set: NEW rule vs OLD rule, side by side --
-- run READ-ONLY against UAT and prod so the owner can confirm the narrowed
-- set before fix/ceo-notification-fanout-match-role-rule merges.
--
-- Background: five copies of the same query decided who gets notified about a
-- CEO-stage approval (OvertimeRepository, SpecialMoneyRepository,
-- CommissionRepository, AttendanceCorrectionRepository, and
-- NotificationRepository#notifyByRoleInternal's "ceo" case) -- every active
-- employee whose division source_code ILIKE 'MD%' OR ILIKE 'MN%'. That is
-- broader than the actual `ceo` role (DivisionAccessPolicy#roleFor): division
-- code EXACTLY 'md', OR a position containing กรรมการ (whitespace stripped).
-- This file's NEW query is CeoApproverRule.SQL_PREDICATE, copied verbatim;
-- if that predicate changes, update this file in the same commit, same
-- discipline approval-routing-gap-audit.sql already documents for itself.
--
-- Nothing here writes. Safe to run on production.
-- ---------------------------------------------------------------------------


-- === NEW: CeoApproverRule.SQL_PREDICATE (what this branch ships) ===========
-- Division code EXACTLY 'md' (not a prefix), OR a position containing
-- กรรมการ once whitespace is stripped. LEFT JOINs on both division and
-- position -- an active employee with no ฝ่าย, or no position, must still be
-- reachable via the other branch.
SELECT e.employee_id, e.employee_code,
       concat_ws(' ', e.first_name_th, e.last_name_th) AS name,
       COALESCE(p.name_th, '(no position)') AS position,
       COALESCE(d.name_th, '(no division)') AS division
  FROM hr.employee e
  LEFT JOIN hr.division d ON d.division_id = e.division_id
  LEFT JOIN hr.position p ON p.position_id = e.position_id
 WHERE e.is_active = TRUE
   AND (LOWER(BTRIM(d.source_code)) = 'md'
        OR regexp_replace(COALESCE(p.name_th, ''), '\s+', '', 'g') LIKE '%กรรมการ%')
 ORDER BY e.employee_id;


-- === OLD: the superseded MD%/MN% division-prefix convention ================
-- What every one of the five call sites actually ran before this branch --
-- reproduced here for comparison only, not proposed as correct.
SELECT e.employee_id, e.employee_code,
       concat_ws(' ', e.first_name_th, e.last_name_th) AS name,
       COALESCE(p.name_th, '(no position)') AS position,
       COALESCE(d.name_th, '(no division)') AS division
  FROM hr.employee e
  JOIN hr.division d ON d.division_id = e.division_id
  LEFT JOIN hr.position p ON p.position_id = e.position_id
 WHERE e.is_active = TRUE
   AND (d.source_code ILIKE 'MD%' OR d.source_code ILIKE 'MN%')
 ORDER BY e.employee_id;


-- === What to look for =======================================================
-- Run both queries on UAT and again on prod (Supabase project IDs: UAT
-- wuypxdznuhhluwzncafh, prod tdyzcqzxmhtxpbouewud -- per this repo's own
-- notes, verify against the actual project before trusting that from memory).
--
--   * Rows in OLD but NOT in NEW: people who STOP being notified about
--     CEO-stage approvals. Expected to shrink the set -- confirm every name
--     that drops off is someone who does not actually hold the `ceo` role
--     (i.e. not division `md` and no กรรมการ-family position). If a name you
--     expect to still be a CEO approver drops off, STOP -- that means this
--     predicate disagrees with DivisionAccessPolicy#roleFor on real data and
--     needs investigation before merging, not after.
--   * Rows in NEW but NOT in OLD: people who START being notified who were
--     not before. Under this predicate that can only happen for someone with
--     a กรรมการ-family position OUTSIDE division MD/MN, or a NULL-division
--     กรรมการ -- confirm any such row is a real, intended CEO-role holder.
--   * Owner ruling 2026-08-09 (per the reported UAT observation) was that one
--     welfare submission notified 4 "CEO approvers" where only คุณราม was
--     wanted -- use these two result sets to confirm which of the 4 the NEW
--     query drops, and whether the survivor(s) match who the owner expects.
-- ---------------------------------------------------------------------------
