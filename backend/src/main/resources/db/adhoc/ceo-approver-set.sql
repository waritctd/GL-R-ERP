-- ---------------------------------------------------------------------------
-- CEO-notification recipient set: NEW rule vs OLD rule, side by side --
-- run READ-ONLY against UAT and prod so the owner can confirm the narrowed
-- set before fix/ceo-notification-fanout-match-role-rule merges.
--
-- Background: five copies of the same query decided who gets notified about a
-- CEO-stage approval (OvertimeRepository, SpecialMoneyRepository,
-- CommissionRepository, AttendanceCorrectionRepository, and
-- NotificationRepository#notifyByRoleInternal's "ceo" case) -- every active
-- employee whose division source_code ILIKE 'MD%' OR ILIKE 'MN%'. An earlier
-- pass on this branch replaced that with a predicate mirroring the actual
-- `ceo` role (DivisionAccessPolicy#roleFor: division EXACTLY 'md', OR a
-- position containing กรรมการ) -- but measured against real data (UAT
-- wuypxdznuhhluwzncafh and prod tdyzcqzxmhtxpbouewud, identical here), that
-- mirror was a NO-OP: it selected the exact same four people the old
-- MD%/MN% rule did. Owner ruling 2026-08-10: only the position
-- กรรมการผู้จัดการ (managing director) should be notified -- division is no
-- longer part of the test at all.
--
-- This file's NEW query WHERE clause is CeoApproverRule.SQL_PREDICATE,
-- copied verbatim; if that predicate changes, update this file in the same
-- commit, same discipline approval-routing-gap-audit.sql already documents
-- for itself.
--
-- Nothing here writes. Safe to run on production.
-- ---------------------------------------------------------------------------


-- === NEW: CeoApproverRule.SQL_PREDICATE (what this branch ships) ===========
-- Position contains กรรมการผู้จัดการ once whitespace is stripped -- nothing
-- else matters. Division-independent by design: the LEFT JOIN on
-- hr.division below exists only to DISPLAY the column for comparison against
-- the OLD set -- CeoApproverRepository#findEmployeeIds() itself does not
-- join hr.division at all any more. LEFT JOIN hr.position because
-- position_id is nullable -- an active employee with no position must still
-- be reachable by this query (and is correctly excluded, since
-- COALESCE(p.name_th, '') = '' cannot LIKE '%กรรมการผู้จัดการ%').
SELECT e.employee_id, e.employee_code,
       concat_ws(' ', e.first_name_th, e.last_name_th) AS name,
       COALESCE(p.name_th, '(no position)') AS position,
       COALESCE(d.name_th, '(no division)') AS division
  FROM hr.employee e
  LEFT JOIN hr.division d ON d.division_id = e.division_id
  LEFT JOIN hr.position p ON p.position_id = e.position_id
 WHERE e.is_active = TRUE
   AND (regexp_replace(COALESCE(p.name_th, ''), '\s+', '', 'g') LIKE '%กรรมการผู้จัดการ%')
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
--   * EXPECTED RESULT: the NEW query returns EXACTLY ONE row -- ราม อิฐรัตน์
--     (employee_code 10009, employee_id 136), position กรรมการผู้จัดการ. If
--     it returns anyone else, nobody, or more than one row, STOP: the
--     position data has changed since 2026-08-10 and this rule needs
--     revisiting before merging, not after.
--   * The OLD query returns four rows on both UAT and prod as of 2026-08-10:
--     ราม (10009) plus three more -- กัลยาณี (10001, ประธานกรรมการ), เลิศชัย
--     (10002, กรรมการ), รานี (10003, กรรมการ) -- all division MD. All four
--     still HOLD the `ceo` role (DivisionAccessPolicy#roleFor is unchanged
--     by this branch) and can still approve; the other three simply stop
--     being NOTIFIED. Confirming that shrink -- OLD has 4, NEW has exactly
--     ราม -- is the entire point of this comparison.
--   * Owner-reported bug (UAT, 2026-08-09): one welfare submission notified
--     4 "CEO approvers" where only คุณราม was wanted. The predicate this
--     branch shipped BEFORE this fix (division `md` OR any กรรมการ-family
--     position) was measured 2026-08-10 to still select all 4 -- a no-op
--     against real data, not a fix. This file's two result sets are the
--     before/after proof that the position-only predicate actually fixes
--     that bug, and does not touch anyone's ability to approve.
-- ---------------------------------------------------------------------------
