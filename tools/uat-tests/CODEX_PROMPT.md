# Codex handoff — implement the GL-R-ERP automated UAT test harness

You are implementing the GL-R-ERP automated UAT test harness on the **`uat` branch**.

Read first:
- `tools/uat-tests/PLAN.md` — the design + the full **69-ID coverage matrix** (scriptable vs manual)
  + state/ordering rules + report format.
- The existing runnable **skeleton** in `tools/uat-tests/`: `glrclient.py` (session/CSRF client),
  `conftest.py` (persona fixtures + the UAT-ID report writer), `test_overtime.py` (the **OT-01**
  worked example), `docker-compose.uat.yml`, `run.sh`, `README.md`.

## Task
Implement **every SCRIPTABLE case** from the PLAN.md §3 matrix as
`@pytest.mark.uat("<ID>", title="...", priority="P0"|"P1")` tests, split across:
`test_auth.py`, `test_employee.py`, `test_attendance.py`, `test_leave.py`, `test_overtime.py`
(extend), `test_commission.py`, `test_payroll.py`, `test_security.py`, `test_notification.py`,
`test_tickets.py`. Follow the skeleton's client + marker + reporter patterns exactly.

## Rules
1. **Verify every request body / endpoint / response field against the actual controllers + DTOs**
   in `backend/src/main/java/th/co/glr/hr/**` before asserting — do not guess payloads or status
   tokens. (E.g. leave/OT/commission status enums, payroll request shape, employee-list JSON keys.)
2. **State/ordering (PLAN.md §4):** destructive cases (AUTH-03 login lockout — locks 900s; AUTH-04
   password change) must use a **throwaway account you create at test start**, never a shared
   persona. Spread quota-consuming leave cases (LV-01/02/05) across **different** seeded employees so
   they don't starve each other. Order payroll `preview → process → bank-export` with `pytest-order`.
   For approval-chain cases (OT/commission), **create a fresh request inside the test** rather than
   mutating a seeded one.
3. **Manual/UI cases** (AUTH-02 forced-change *screen*, AUTH-05 restart, ATT-01 device tap, PAY-07
   reconciliation, NOTIF-02 bell UI, PDF *visual* render) → `pytest.skip(reason="manual/UI")` so they
   still show in the report as intentional skips.
4. **Never send email** — the stack runs `APP_MAIL_PROVIDER=log`; for NOTIF-01 assert the in-app row
   in `/api/notifications`, not delivery.
5. **`uat`-branch only.** This harness depends on the `db/migration-uat` seed and must **never** be
   merged to `main`.

## Definition of done
- `cd tools/uat-tests && ./run.sh` on a clean checkout: local uat stack comes up, suite runs, tears
  down, and `reports/uat-results.md` is produced with **all 69 IDs represented** (pass / fail /
  skip-with-reason).
- **Every P0 SCRIPTABLE case is green** against the fresh seed.
- A red is a **real app bug** → report it with the failure detail; do **not** weaken the assertion to
  make it pass. (Expect the report to formalize known quirks, e.g. PAY-02 feeds the **2026-06** period
  for GLR-0005 — see `11_UAT_Test_Cases.md` PAY-02 — and the `admin` persona resolves to employee-level
  access; assert the *actual* documented behavior, not the naive expectation.)
- Re-running `./run.sh` yields identical results (proves the fresh-seed reset works).
- Final message: the P0 pass-rate and a list of any reds with detail.
