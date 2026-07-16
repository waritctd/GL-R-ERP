-- =====================================================================
-- V908 — Add the ฝ่ายบัญชี (accounting) UAT persona: account@uat.glr.
--
-- WHY
--   Phase-2 (main #212) introduced the `account` role — ฝ่ายบัญชี confirms
--   the money-receipt steps of the dual-track sales flow (รับยอดมัดจำ /
--   รับชำระเต็มจำนวน = confirmDepositPaid / confirmFinalPayment), with CEO
--   as the only fallback. The V900 seed predates that role, so its 9
--   personas had no way to exercise those two confirmations — a tester
--   could drive a ticket up to DEPOSIT_NOTICE_ISSUED / AWAITING_FINAL_PAYMENT
--   and then get stuck, because neither sales, import, nor sales_manager
--   can confirm receipt.
--
-- HOW
--   Layer the login onto an existing active AC-division employee, exactly
--   as V900 layered its 8 persona logins onto authored rows (no new
--   employee). GLR-0013 (ประพันธ์ รุ่งเรือง) is division AC, so
--   DivisionAccessPolicy derives role `account` from its division code
--   ('ac') at login — no stored role needed. It carried no persona email,
--   so this UPDATE is the first to claim it.
--
--   Shared password Uat@2026 (the same published hash V900/V907 use), and
--   must_change_password = FALSE so the one-click quick sign-in works
--   immediately — matching the V907 end-state for the other 9 personas
--   (V907 already ran; it does not touch this new email, so we set the
--   cleared state directly here).
--
-- APPEND-ONLY / CHECKSUM-SAFE
--   New migration on top of the frozen V900–V907 seed — never edit those
--   (their Flyway checksums are applied on the hosted UAT DB). Scoped to
--   one employee_code, idempotent (re-run is a no-op), so the checksum is
--   stable and a fresh-DB deploy replays it deterministically.
-- =====================================================================

UPDATE hr.employee
SET email = 'account@uat.glr',
    password_hash = '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm',
    must_change_password = FALSE
WHERE employee_code = 'GLR-0013'
  AND email IS NULL;
