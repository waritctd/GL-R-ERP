# Agent Handoff

## Task
Make `ค่าตอบแทนกรรมการ` (director remuneration) an editable, sustainable per-employee field so HR
can maintain it in the employee edit form, and confirm it auto-populates every monthly payroll run.
The `hr.employee.director_remuneration` column already existed (V73) and the payroll engine already
auto-reads it — the gap was that there was **no write path** to set the value, so it was 0 for
everyone and director-only employees (salary 0) never appeared in payroll.

## Branch
`feat/employee-director-remuneration-editable`

## Base Commit
`978cb4d60d63a06108c7caec77001e0eac8ddf21` (origin/main)

## Current Commit
Not committed (left on the branch, uncommitted, as instructed).

## Agent / Model Used
Claude Opus 4.8 (implementation).

## Scope

### In Scope
- Backend write path: accept `directorRemuneration` on employee create + update, persist to
  `hr.employee.director_remuneration`, return it in the employee read DTO. Mirror `current_salary`
  exactly (same non-negative validation, same nullable-skip-on-update semantics, same SQL position).
- Frontend editable numeric field labeled `ค่าตอบแทนกรรมการ` in the employee edit form.
- Keep `mockApi.js` / `hrApi.js` in parity (contract.test.js).
- Prove the auto-poll: a salary-0 / director>0 employee appears as a payroll line with director pay
  in gross and 0 SSO base, and is not filtered out in PayrollPage.

### Out of Scope / Untouched
- **Payroll math / `PayrollCalculator` — NOT touched.** The calculation already adds director
  remuneration to gross and excludes it from the SSO wage base.
- No DB migration (column already exists from V73, with CHECK
  `chk_employee_director_remuneration_non_negative`).
- No change to authorization (see Authz Evidence).

## Files Changed
Backend (main):
- `backend/.../employee/UpsertEmployeeRequest.java`: added `@DecimalMin("0.00") BigDecimal directorRemuneration` (same validation as `salary`).
- `backend/.../employee/EmployeeDto.java`: added `directorRemuneration` field (after `salary`, `@JsonInclude(NON_NULL)`); updated `withPendingRequestCount` and nulled it in `withoutSensitiveSelfServiceFields` — so non-HR self-service callers never see it, exactly like `salary`.
- `backend/.../employee/EmployeeRepository.java`: create INSERT now writes `director_remuneration` (defaults `BigDecimal.ZERO` when null, like salary); update adds `addSet(..., "director_remuneration", "directorRemuneration", request.directorRemuneration())`; `baseSelect` selects `e.director_remuneration`; both row mappers read it back.

Backend (test):
- `backend/.../employee/EmployeeRepositoryIntegrationTest.java`: **new** IT `req(...)` overload + 3 tests — create round-trips director remuneration, update persists it, and `PayrollRepository.findActiveEmployees()` now includes a salary-0 / director>0 employee. (Requires Postgres — see results.)
- `backend/.../employee/EmployeeServiceTest.java`, `EmployeeControllerTest.java`: added the new DTO arg to their `employee(...)` fixtures.
- 19 sales/commission/ticket/payroll IT helper files: inserted the new `directorRemuneration` positional arg (a `null`) into their `new UpsertEmployeeRequest(...)` calls so they still compile. Mechanical, one null each, no behavior change.

Frontend:
- `frontend/src/features/employees/EmployeeFormModal.jsx`: added `directorRemuneration` to the zod schema, defaults (seeded from `employee.directorRemuneration`), the submit payload (`Number(...)`), and a numeric `<input min="0" step="0.01">` labeled `ค่าตอบแทนกรรมการ` immediately after เงินเดือน.
- `frontend/src/features/employees/EmployeeFormModal.test.jsx`: assertions updated; edit-mode test now seeds a director value and asserts it round-trips through submit.
- `frontend/src/api/mockApi.js`: `createEmployeeRecord` now sets `directorRemuneration: Number(payload.directorRemuneration || 0)`; the mock `update` already flows it via `Object.assign`. `hrApi.js` needed no change — `employees.create/update` pass the payload straight through, so the field rides the existing method.

## Commands Run
```bash
# Frontend
cd frontend && npm ci
npm run lint      # 0 errors (1 pre-existing warning in PayrollPage.jsx, untouched)
npm test          # 545 passed (63 files), incl. contract.test.js
npm run build     # built OK

# Backend
cd backend && ./mvnw -B -q compile         # OK
./mvnw -B -q test-compile                  # OK (confirms all 22 constructor edits compile)
./mvnw -B -q -o surefire:test -Dtest='EmployeeServiceTest,EmployeeControllerTest,PayrollCalculatorTest'
# EmployeeServiceTest 9/9, EmployeeControllerTest 9/9, PayrollCalculatorTest 24/24
```

## Test / Build Results
- Frontend lint: **pass** (0 errors; the single warning is pre-existing in `PayrollPage.jsx`).
- Frontend tests: **pass** — 545/545, including `contract.test.js` (mockApi ⇄ hrApi parity intact).
- Frontend build: **pass**.
- Backend compile + test-compile: **pass** (all positional constructor edits verified by the compiler).
- Backend unit tests run: **pass** — `EmployeeServiceTest` 9/9, `EmployeeControllerTest` 9/9,
  `PayrollCalculatorTest` 24/24.
- Backend integration tests (incl. the new `EmployeeRepositoryIntegrationTest` cases and the full
  `./mvnw -B clean verify`): **NOT RUN — Docker daemon down and no `TEST_DB_URL`.** They compile and
  will run in CI (Testcontainers). Deliberately did **not** stand up a throwaway Postgres.

### Auto-poll proof (two halves, both green where runnable)
1. **Inclusion** — a salary-0 / director>0 employee becomes a payroll line: covered by the new
   `EmployeeRepositoryIntegrationTest.payrollAutoPollIncludesDirectorOnlyEmployeeWithZeroSalary`
   (drives the real `PayrollRepository.findActiveEmployees()` predicate
   `current_salary > 0 OR director_remuneration > 0`). Runs in CI; not run locally (no DB).
2. **Calculation** — director pay in gross, 0 SSO base for a salary-0 director: already covered by
   the pre-existing pure-unit `PayrollCalculatorTest.aDirectorWithNoSalaryPaysNoSocialSecurityAtAll`
   (salary 0, director 150,000 → grossEarnings 150,000, ssoWageBase 0, socialSecurity 0) plus
   `directorRemunerationRaisesGrossAndTaxButNeverTouchesSocialSecurity`. **Ran locally, 24/24 green.**
3. **PayrollPage filter** — verified there is no client-side salary/`> 0` filter that would hide a
   salary-0 row; lines render straight from the backend response and the director amount already has
   a display block (`PayrollPage.jsx` ~L596). No change needed.

## API-contract change (stated plainly)
The employee create/update contract gains one optional field: `directorRemuneration`
(non-negative decimal). It is accepted on `POST /api/employees` and `PATCH /api/employees/{id}` and
returned on the employee read DTO for HR (nulled for non-HR self-service, exactly like `salary`).
This is an intentional, additive contract change — the whole point of the task — not a side effect.

## Authz Evidence
**No authorization change.** `directorRemuneration` rides the *same* employee create/update
endpoints that already edit `current_salary`, which are gated `sessions.requireAnyRole(user, "hr")`
in `EmployeeController` (HR only). Read exposure mirrors salary: present only when
`canSeeSensitiveEmployeeFields` (role `hr`) is true, and nulled by `withoutSensitiveSelfServiceFields`
otherwise. No role gate, scope/filter, or who-can-read/write-whose-rows changed, so per CLAUDE.md a
real-DB authz integration test is **not required** for this task. It **inherits the existing
salary-edit gate**.

## Decisions Made
- Placed `directorRemuneration` immediately after `salary` in the DTO/request/SQL for readability and
  to mirror `current_salary` one-to-one.
- Added functional (non-authz) round-trip ITs to `EmployeeRepositoryIntegrationTest` so the write
  path is proven against real Postgres in CI, even though not runnable locally here.
- Left `hrApi.js` untouched — its `employees.create/update` are pass-through, so no method surface
  changed and contract.test.js stays green.

## Assumptions
- The V73 column + CHECK constraint are present in every environment the app migrates (they are, per
  the migration and the payroll engine already reading the column).
- `NUMERIC(12,2)` is adequate headroom for director remuneration (same type as many money columns).

## Known Risks
- The new `EmployeeRepositoryIntegrationTest` cases and the full `mvnw clean verify` were not run
  locally (no Docker/Postgres). If the branch is merged without CI running them, the write-path
  persistence assertion is unverified against a real DB. **CI (Testcontainers) must be green before
  merge.**
- 19 unrelated IT helper files were edited purely to keep the positional record constructor
  compiling. They are mechanical single-null insertions; the compiler (`test-compile` passed) is the
  guarantee they are correct, but a reviewer should skim the diff to confirm no null landed in the
  wrong slot.
- Mock verification is authz-incomplete by nature (`VITE_USE_MOCKS`), but there is no authz change
  here so this does not affect the permission story.

## Things Not Finished
- Run `cd backend && ./mvnw -B clean verify` with Postgres/Docker (or in CI) to execute the new ITs
  and the full suite.

## Recommended Next Agent
Claude Opus review, then merge once backend CI (Testcontainers) is green.

## Exact Next Prompt
```
On branch feat/employee-director-remuneration-editable: run `cd backend && ./mvnw -B clean verify`
with Docker/Testcontainers available (or confirm backend CI is green) so the new
EmployeeRepositoryIntegrationTest cases (create/update round-trip + findActiveEmployees inclusion of
a salary-0 director) actually execute against real Postgres. Confirm nothing else in the suite
regressed from the 19 mechanical UpsertEmployeeRequest constructor edits. If green, this branch is
ready for review/merge. Do not change payroll math.
```
