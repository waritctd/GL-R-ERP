-- =====================================================================
-- V907 — Let the 9 UAT persona accounts keep signing in with Uat@2026.
--
-- WHY
--   V900 seeded all 9 personas with must_change_password = TRUE so that
--   UAT-AUTH-01/02 (forced change on first login) was exercised
--   naturally by every tester. That works exactly once per persona:
--   after a tester completes the forced change, Uat@2026 stops working
--   for that account and the next tester is locked out of it.
--
--   The login screen now offers a one-click quick sign-in per persona
--   (frontend/src/features/auth/uatQuickLogin.js, gated on
--   VITE_UAT_QUICK_LOGIN — uat builds only), which posts the shared
--   Uat@2026 to the real /api/auth/login. That only stays usable if the
--   shared password stays valid, so this migration:
--     1. resets password_hash back to the shared Uat@2026 hash, and
--     2. clears must_change_password.
--
-- TRADE-OFF (accepted, requested)
--   This gives up the forced-change coverage the V900 seed was set up
--   for. UAT-AUTH-01/02 must now be tested deliberately — flip one
--   persona back by hand:
--       UPDATE hr.employee SET must_change_password = TRUE
--        WHERE email = 'employee@uat.glr';
--   Nothing in the auth code changed; only this seed's starting state.
--
-- DESTRUCTIVE, DELIBERATELY
--   Step 1 overwrites any password a tester has already chosen for these
--   9 accounts. That is the point (it is what makes the shared password
--   reliable again) and it is safe here precisely because these are
--   synthetic seed logins whose password is published in V900 and
--   UAT_Accounts.md. Scoped to the 9 @uat.glr persona emails by an
--   explicit IN list — it must never touch an authored employee row.
--
--   Hash is the same literal V900 uses, generated with:
--       htpasswd -nbBC 10 x 'Uat@2026'
--   and verified against Spring Security's BCryptPasswordEncoder.
--
-- Idempotent and deterministic: a plain scoped UPDATE to constant
-- values, so re-running is a no-op and the Flyway checksum is stable.
-- =====================================================================

UPDATE hr.employee
SET password_hash = '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm',
    must_change_password = FALSE
WHERE email IN (
    'ceo@uat.glr',
    'hr@uat.glr',
    'salesmgr@uat.glr',
    'sales@uat.glr',
    'import@uat.glr',
    'divmgr@uat.glr',
    'employee@uat.glr',
    'nulldiv@uat.glr',
    'admin@uat.glr'
);
