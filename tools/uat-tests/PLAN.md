# UAT Automated Test Harness — Implementation Plan (for Codex)

## Goal
A **repeatable Python + pytest** harness that drives the GL-R-ERP backend API through the
API-testable UAT acceptance cases, asserts expected behaviour, and emits a **pass/fail report keyed to
the UAT test IDs** in `ERP Documentation/11_UAT_Test_Cases.md`. Runs against a **fresh local `uat`
stack** (deterministic, resettable) — not the shared cloud UAT (whose DB is already dirtied).

Decisions (user, 2026-07-09): **Python + pytest**; **fresh local uat stack** as the target.

Lives on the `uat` branch only (it depends on the `db/migration-uat` seed: persona emails, `GLR-####`
employees, seeded tickets/commissions). Do **not** merge to `main` (overlay model).

---

## 1. Target environment — fresh local `uat` stack
The harness assumes a backend on `SPRING_PROFILES_ACTIVE=uat` against a **freshly-migrated** Postgres
(so V1–V36 + V900–V903 seed is pristine: all 9 personas at `Uat@2026` / `must_change_password=true`,
leave quotas full, no processed payroll).

Add `tools/uat-tests/docker-compose.uat.yml` (a dedicated compose, don't reuse the prod override):
- `db`: `postgres:16-alpine`, DB `hris`, ephemeral (no named volume, so `down` wipes it).
- `backend`: build from `../../backend`, `SPRING_PROFILES_ACTIVE=uat`, `APP_FLYWAY_ENABLED=true`,
  `APP_MAIL_PROVIDER=log` (never send email from the harness), `SERVER_SESSION_COOKIE_SECURE=false`
  (plain-HTTP local), datasource → the local `db`. Expose backend on a fixed port (e.g. 8099).

`run.sh` (harness entrypoint): `docker compose -f docker-compose.uat.yml down -v && up -d --build`,
wait for `/actuator/health` = UP, then `pytest`, then `down -v`. Each run = clean state.

`UAT_BASE_URL` env var (default `http://localhost:8099`) lets the same suite point at the cloud UAT
for a read-only smoke if ever needed — but the full mutating suite is meant for the local stack.

---

## 2. Harness architecture — `tools/uat-tests/`
```
tools/uat-tests/
  PLAN.md                 (this file)
  README.md               (how to run; env vars; interpreting the report)
  requirements.txt        (requests, pytest, pytest-order)
  docker-compose.uat.yml
  run.sh                  (reset stack -> pytest -> report -> teardown)
  conftest.py             (session client, persona fixtures, UAT-ID reporter hook)
  glrclient.py            (login/session/CSRF wrapper; typed request helpers)
  fixtures/
    uat_valid.dat         (sample attendance import for ATT-04)
    uat_oversized.dat     (>size cap for ATT-06)          # generate in-test if simpler
    invoice.pdf           (tiny PDF for COM-01 upload)
  test_auth.py  test_employee.py  test_attendance.py  test_leave.py
  test_overtime.py  test_commission.py  test_payroll.py
  test_security.py  test_notification.py  test_tickets.py
  reports/                (git-ignored output: uat-results.md, uat-results.csv, junit.xml)
```

### `glrclient.py` — the session/CSRF wrapper (the crux; mirrors what worked all session)
```python
class GlrClient:
    def __init__(self, base_url): self.s = requests.Session(); self.base = base_url
    def login(self, email, password):
        r = self.s.post(f"{self.base}/api/auth/login", json={"email": email, "password": password})
        r.raise_for_status()                       # sets GLR_HR_SESSION + XSRF-TOKEN cookies
        return r.json()["user"]
    def _csrf(self): return self.s.cookies.get("XSRF-TOKEN")
    def get(self, path, **kw):  return self.s.get(f"{self.base}{path}", **kw)
    def post(self, path, **kw): return self.s.post(f"{self.base}{path}",
                 headers={**kw.pop("headers", {}), "X-XSRF-TOKEN": self._csrf()}, **kw)
    # patch/put/delete similar (all need X-XSRF-TOKEN)
```
CSRF is enforced (confirmed live: 403 without `X-XSRF-TOKEN`, 200 with). Session cookie is
`SameSite=Lax`, so a single `requests.Session` per persona holds auth for the whole test.

### `conftest.py`
- `base_url` fixture from `UAT_BASE_URL`.
- Per-persona client fixtures: `hr`, `ceo`, `sales`, `salesmgr`, `import_`, `divmgr`, `employee`,
  `nulldiv`, `admin` — each logs in with `Uat@2026` (fresh seed) and yields a `GlrClient`.
- **UAT-ID reporter hook:** each test carries `@pytest.mark.uat("OT-03", priority="P0")`. A
  `pytest_runtest_makereport` hook collects (uat_id, title, priority, outcome, detail) and writes
  `reports/uat-results.md` (+ `.csv`, + `--junitxml`). Summary line: `P0: X/Y passed` — **go-live gate
  = every 🔴 (P0) case passes**.

---

## 3. Test-ID coverage matrix (all 69 IDs — Codex implements the SCRIPTABLE ones)
Priority: 🔴=P0 (must pass for go-live), 🟡=P1. "MANUAL" = keep in the human checklist, mark `xfail`/`skip`
with reason in the harness so the report shows it as intentionally not-automated.

| Area | Scriptable (implement) | Manual/UI (skip w/ reason) |
|---|---|---|
| **Auth** | AUTH-01 login→session; AUTH-03 lockout (use a THROWAWAY account — locks 900s); AUTH-04 change-pw (throwaway); AUTH-06 logout→`/me` 401; AUTH-02 assert `mustChangePassword:true` + change-pw endpoint works | AUTH-02 forced-change *screen*, AUTH-05 session-survives-restart (needs backend bounce) |
| **Employee** | EMP-01 create→code gen; EMP-02 edit assignment→history kept; EMP-03 list `?page&size` + filter; EMP-04 own profile 200 / edit 403; EMP-05 submit profile-request→pending; EMP-06 approve→applied; EMP-07 reject+note; EMP-08 open PII→audit row written | — |
| **Attendance** | ATT-02 employee sees own only; ATT-03 divmgr sees division; ATT-04 `.dat` import; ATT-05 rotate device token; ATT-06 oversized `.dat` rejected | ATT-01 physical SC700 tap |
| **Overtime** | OT-01 submit→SUBMITTED×1.5; OT-02 holiday→×3.0; OT-03 mgr→MANAGER_APPROVED; OT-04 appears in payroll preview; OT-05 cancel→CANCELLED; OT-06 end<start→400; OT-07 cross-division approve denied; OT-08 CEO→APPROVED; OT-09 reject→REJECTED | — |
| **Leave** | LV-01 within-quota→APPROVED (auto); LV-02 over-quota→AUTO_REJECTED; LV-03 sick no-attachment→blocked; LV-04 approve path; LV-05 cancel→balance restored; LV-06 balances (SICK30/VAC6/PER3) | — |
| **Sales tickets** | TKT-01..09 full lifecycle via `/api/tickets` (draft→submitted→price_proposed→approved/closed, edit→has_edits, quotation issue+running no., revision, comment/event, close) | PDF *visual* render (assert file downloads + content-type only) |
| **Commission** | COM-01 submit + invoice upload (multipart); COM-02 simulator (no save); COM-03 mgr→MANAGER_APPROVED; COM-04 payroll-ready feed; COM-05 clawback (negative); COM-06 CEO→APPROVED (dual gate: single approval must NOT finalize); COM-07 reject | — |
| **Payroll** | PAY-01 preview lists actives; PAY-02 OT+commission on line (use GLR-0005 in **2026-06**, see doc note); PAY-03 SSO(875 cap)+annualized tax; PAY-04 special-pay/unpaid recalc; PAY-05 process→audit `PROCESS_PAYROLL`; PAY-06 bank-export `.txt`+audit `EXPORT_PAYROLL_BANK_FILE` | PAY-07 parallel-run reconciliation (manual vs accountant) |
| **Security** | SEC-01 employee→HR endpoint 403; SEC-02 cross-user data denied; SEC-03 sales(non-mgr) approve price denied; SEC-04 punch w/o device token rejected; SEC-05 unknown route→404 (not 500); SEC-06 CSP/HSTS/nosniff/X-Frame headers present | — |
| **Notifications** | NOTIF-01 action→in-app row in `/api/notifications` (email = assert row only; delivery is provider=log here); NOTIF-03 cross-user mark-read denied | NOTIF-02 bell-UI unread count |

Endpoints (confirmed this session): `POST /api/auth/{login,logout,change-password}`, `GET /api/auth/me`;
`GET /api/employees?page&size`, `GET/POST /api/employees`, `/api/profile-requests`; `GET/POST /api/leave`,
`/api/leave/balances`, `/api/leave/{id}/{approve,reject,cancel}`; `GET/POST /api/overtime`,
`/api/overtime/{id}/{approve,reject,cancel}`; `GET/POST /api/commissions`, `/api/commissions/simulator`,
`/api/commissions/payroll-ready`; `GET /api/payroll?payrollMonth`, `POST /api/payroll/{preview,process}`,
`GET /api/payroll/{periodId}/bank-export`; `GET/POST /api/tickets`, `/api/tickets/{id}/...`;
`GET /api/notifications`, `PATCH /api/notifications/{id}/read`; `GET /api/attendance`. Mutations need
`X-XSRF-TOKEN`. Confirm exact request bodies against the controllers/DTOs before implementing each.

---

## 4. State & ordering rules (critical for a mutating suite)
- **Fresh DB per run** (`run.sh` resets), so mutations don't accumulate across runs.
- **Within a run**, isolate destructive tests:
  - AUTH-03 (lockout, 900s) and AUTH-04 (password change) must use a **throwaway employee** created at
    test start (EMP-01 style) or one of the `GLR-1001..1060` padding employees given a login — **never**
    a shared persona (would break every later test that logs in as it). Prefer: seed/create a dedicated
    `uat-throwaway@...` at session start.
  - Leave quota is finite (PERSONAL 3 / VACATION 6 / SICK 30). LV tests that consume quota should each
    use a **different** employee (there are 90+ seeded) so LV-01/02/05 don't starve each other. Map one
    employee per quota-consuming case.
  - PAY-05 (process) writes a period; run payroll tests in order (preview → process → bank-export) with
    `pytest-order`, and use a payroll month not needed by other assertions.
  - OT/commission approval chains: create a fresh request inside each test, don't reuse seeded ones for
    state-transition tests (seeded ones are for the read/preview assertions).
- Use `pytest-order` (or dependency markers) only where a real ordering exists; otherwise keep tests
  independent + self-provisioning.

---

## 5. Report output (feeds the UAT execution log)
`conftest.py` writes to `tools/uat-tests/reports/` (git-ignored):
- **`uat-results.md`** — table `| UAT-ID | Title | Priority | Result | Detail |`, sorted by ID, plus a
  header summary: total, passed, failed, skipped, and **P0 pass-rate (go-live gate)**.
- **`uat-results.csv`** — same columns, for pasting into `UAT Deliverables/UAT_Execution_Log.xlsx`.
- **`junit.xml`** (`pytest --junitxml`) — for any CI later.
A `FAIL` row must include the assertion detail (expected vs actual, status code, response snippet) so a
failure is actionable without re-running.

---

## 6. Deliverables checklist (for Codex)
1. `tools/uat-tests/` scaffold per §2 (compose, run.sh, requirements, conftest, glrclient).
2. Implement every **SCRIPTABLE** case in §3 as `@pytest.mark.uat("<ID>", priority=...)` tests; mark
   MANUAL ones `skip`/`xfail` with a clear reason so they still appear in the report.
3. Confirm each request body/endpoint against the actual controllers/DTOs before asserting.
4. Reporter (`uat-results.md` + `.csv` + `junit.xml`) per §5.
5. `README.md`: `./run.sh` (local, default), `UAT_BASE_URL=... pytest -m "uat"` (cloud smoke), how to
   read the report, and the go-live gate rule.
6. `reports/` in `.gitignore`.

## 7. Verification (definition of done for the harness)
- `./run.sh` on a clean checkout: stack comes up, suite runs, `reports/uat-results.md` produced.
- Every P0 SCRIPTABLE case is **green** against the fresh seed (any red is a real app bug → file it,
  don't paper over the test).
- Re-running `./run.sh` yields identical results (proves repeatability / clean state).
- Row count in the report = 69 (all IDs represented: pass / fail / skip-with-reason).
