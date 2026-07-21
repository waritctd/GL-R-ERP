# Agent Handoff

## Task
Implement SLICE 2 of the special-money (welfare) requests feature on top of the already-built,
already-green SLICE 1 (schema V66 + `SpecialMoneyType`/`CapRule`/`ClaimWindow`/`EligibilityRule`/
`EmployeeEligibilitySnapshot`/`UsageSnapshot`/`PolicyAmounts`/`PolicyDecision`/
`SubmitSpecialMoneyRequest`/`SpecialMoneyPolicyEvaluator`, 598 tests green). Slice 2 adds the
repository, service, controller, and full test coverage (unit + real-Postgres repository +
real-Postgres authz-scope evidence + mutation checks) so the feature is submit/review/list/cancel
-able end to end, while staying out of payroll wiring, attachments, and the frontend.

## Branch
`feat/special-money-requests`

## Base Commit
`8ae42e3` (origin/main) — slice 1's files were already present as untracked working-tree changes
when this session started; nothing was committed yet on this branch.

## Current Commit
Not committed (per instructions: do not commit, do not push).

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `SpecialMoneyRepository` (NamedParameterJdbcTemplate, no JPA), `SpecialMoneyService`,
  `SpecialMoneyController`, all supporting DTOs/records.
- `AppProperties.SpecialMoney.payrollCutoffDay` (default 25) + `application.yml` wiring.
- Unit tests (Mockito), real-Postgres repository integration test, real-Postgres authz-scope
  integration test (the CLAUDE.md-required evidence), and the three mandatory mutation checks.

### Out of Scope (untouched, per the task's hard limits)
- `backend/src/main/java/th/co/glr/hr/payroll/` — no payroll wiring at all.
- Any frontend file.
- Attachment upload/download endpoints (later slice).

## Files Changed

### New (slice 2)
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyRepository.java` — repository:
  `employeeExists`, `findEmployeeAccess`, `findEligibility`, `findUsage`, `findPolicyAmounts`,
  `create`, `findById`, `findRequests`, `managerApprove`, `ceoApprove`, `reject`, `ceoReject`,
  `cancel`, `payrollMonthProcessed`, plus `findEmployeeOptions`, `findExcludedProvinces`,
  `findCeoApproverEmployeeIds` (needed to fully mirror `OvertimeRepository`'s picker/notification
  fan-out, not explicitly named in the task list but required by the endpoints/notifications it did
  name).
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyService.java` — submit/approve
  (manager→CEO dispatch)/reject/cancel/list/usage/employeeOptions, CEO cap re-check +
  cap-override-reason gate, 25th-cutoff payroll-month assignment with roll-forward past a
  PROCESSED month.
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyController.java` — `/api/special-money`:
  `GET /`, `POST /`, `GET /employees`, `GET /usage`, `POST /{id}/approve`, `POST /{id}/reject`,
  `POST /{id}/cancel`, `GET /types`.
- `SpecialMoneyEmployeeAccess.java`, `SpecialMoneyEmployeeOption.java`, `SpecialMoneyFilter.java`,
  `SpecialMoneyRequestDto.java`, `SpecialMoneyResponses.java`, `SpecialMoneyStatus.java`,
  `SpecialMoneyTypeOption.java`, `SpecialMoneyUsageDto.java`, `ReviewSpecialMoneyRequest.java`,
  `SubmitSpecialMoneyHttpRequest.java` (the HTTP-layer wrapper that carries `requestType` alongside
  slice 1's `SubmitSpecialMoneyRequest`, which deliberately does not carry it — see Decisions Made).
- `backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyServiceTest.java` (19 tests, Mockito).
- `backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyRepositoryIntegrationTest.java`
  (4 tests, real Postgres via Testcontainers).
- `backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyScopeIntegrationTest.java`
  (10 tests, real Postgres, real service+repository+evaluator — THE AUTHZ EVIDENCE).

### Modified
- `backend/src/main/java/th/co/glr/hr/config/AppProperties.java` — added `SpecialMoney` inner
  class + `getSpecialMoney()`.
- `backend/src/main/resources/application.yml` — added
  `app.special-money.payroll-cutoff-day: ${APP_SPECIAL_MONEY_PAYROLL_CUTOFF_DAY:25}`.

### Untouched (slice 1, already present, reused as-is)
- `V66__special_money_request_schema.sql`, `SpecialMoneyType`, `SpecialMoneyBucket`, `CapRule`,
  `ClaimWindow`, `EligibilityRule`, `EmployeeEligibilitySnapshot`, `UsageSnapshot`, `PolicyAmounts`,
  `PolicyDecision`, `SubmitSpecialMoneyRequest`, `SpecialMoneyPolicyEvaluator`,
  `SpecialMoneyPolicyEvaluatorTest`.

## Commands Run
```bash
cd backend && ./mvnw -q -o compile
cd backend && ./mvnw -o test -Dtest=SpecialMoneyServiceTest
cd backend && ./mvnw -o test -Dtest=SpecialMoneyRepositoryIntegrationTest
cd backend && ./mvnw -o test -Dtest=SpecialMoneyScopeIntegrationTest
cd backend && ./mvnw -o test -Dtest=SpecialMoneyServiceTest,SpecialMoneyScopeIntegrationTest,SpecialMoneyRepositoryIntegrationTest   # x3, for each mutation check
cd backend && ./mvnw -B clean verify
```

## Test / Build Results
- Frontend build: not run (out of scope — no frontend file touched).
- Backend tests: **PASS — 631 tests, 0 failures, 0 errors, 0 skipped** (`./mvnw -B clean verify`).
  Baseline was 598; this branch adds 33 (19 `SpecialMoneyServiceTest` + 4
  `SpecialMoneyRepositoryIntegrationTest` + 10 `SpecialMoneyScopeIntegrationTest`).
  **Integration tests RAN** (Docker was available; Testcontainers Postgres started, Flyway
  migrated to V66, `AbstractPostgresIntegrationTest`-based classes executed for real — confirmed by
  the `Successfully applied 54 migrations ... now at version v66` log line on every run).
- Lint: not applicable (backend-only change; no `checkstyle`/`spotless` step beyond what `verify`
  already runs, which passed as part of the build).

## Authz Evidence
**Verified against the real Java service:**
`backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyScopeIntegrationTest.java` — real
Postgres (Testcontainers), real `SpecialMoneyService` + real `SpecialMoneyRepository` + real
`SpecialMoneyPolicyEvaluator`; only `AuditService` and `NotificationService` are stubbed (they do
not participate in the authorization decision). 10 wrong-way-round cases, each asserting the
database itself (a re-read of `status`/`approved_amount`, or a row count) rather than only the
thrown status code:

1. `managerCannotListRequestsOutsideOwnDivision` — asserts zero rows returned, not "contains own".
2. `managerCannotApproveRequestOfOutOfDivisionEmployee` — 403 AND row still `SUBMITTED`,
   `approved_amount` still `NULL`.
3. `employeeCannotSeeAnotherEmployeesRequest`.
4. `employeeCannotSubmitOnBehalfOfNonReport` — 403 AND row count for the target employee unchanged.
5. `hrCannotApprove` — pins issue #199's exact shape; 403 AND status unchanged. (This test caught a
   real fixture bug during development: the first draft gave the `hr` principal the sales manager's
   own `employeeId`, so `managesEmployee` accidentally succeeded via the *manager* relation, not an
   HR carve-out — fixed by giving HR its own unrelated employee record with no reports and no
   division-manager flag, so the test cannot pass by accident.)
6. `hrCannotSubmitOnBehalfOfArbitraryEmployee` — 403 AND row count unchanged.
7. `managerCannotCeoApproveOwnManagerApprovedRequest` — 403 AND status still `MANAGER_APPROVED`.
8. `ceoCannotApproveRequestStillInSubmittedState` — the CEO must not skip the manager stage.
9. `employeeCannotReadAnotherEmployeesUsageQuota`.
10. `employeeCannotCancelAnotherEmployeesRequest`.

**Mutation checks — all three run, all three caught by the intended tests, all reverted to an empty
diff (confirmed via `git diff` + `grep -n "MUTATION-CHECK"`, both clean):**

1. **`requireManager` disabled** (made a no-op): 4 tests went red —
   `SpecialMoneyServiceTest.hrCannotApproveSubmittedRequest`,
   `SpecialMoneyServiceTest.nonManagerCannotApproveSubmittedRequest`,
   `SpecialMoneyScopeIntegrationTest.hrCannotApprove`,
   `SpecialMoneyScopeIntegrationTest.managerCannotApproveRequestOfOutOfDivisionEmployee`.
   Nothing else failed.
2. **`requireCeo` disabled** (made a no-op): 3 tests went red —
   `SpecialMoneyServiceTest.managerCannotCeoApproveOwnManagerApprovedRequest`,
   `SpecialMoneyServiceTest.managerCannotCeoRejectManagerApprovedRequest`,
   `SpecialMoneyScopeIntegrationTest.managerCannotCeoApproveOwnManagerApprovedRequest`.
   (`ceoCannotApproveRequestStillInSubmittedState` did NOT fail — correctly so: that case routes
   through `requireManager` on the SUBMITTED→managerApprove branch, not `requireCeo`, since a CEO is
   not the employee's manager either. This was checked, not assumed.)
3. **`list()` division filter disabled** (the `managerEmployeeId`-scoping `AND (...)` clause in
   `SpecialMoneyRepository.findRequests` short-circuited off): 2 tests went red —
   `SpecialMoneyScopeIntegrationTest.managerCannotListRequestsOutsideOwnDivision`,
   `SpecialMoneyScopeIntegrationTest.employeeCannotSeeAnotherEmployeesRequest`.
   Nothing else failed.

No mutation left a test suite fully green — every guard is proven testable.

## Decisions Made
- **`SubmitSpecialMoneyRequest` (slice 1) does not carry a `requestType` field** — by design,
  `SpecialMoneyPolicyEvaluator.evaluate(SpecialMoneyType type, ...)` takes the type as a separate
  argument. Rather than editing the slice-1 record, slice 2 adds
  `SubmitSpecialMoneyHttpRequest(String requestType, ...)` at the HTTP layer, which converts to the
  domain record via `.toDomain()`. `SpecialMoneyService.submit(String requestTypeRaw, ...)` and
  `SpecialMoneyController` both carry the type alongside the request rather than folding it in.
- **CEO cap re-check does not gate on the re-run evaluator's `violations()`, only on
  `eligibleAmount()`.** The MANAGER_APPROVED request being approved is itself counted in
  `findUsage`'s lifetime-count (SUBMITTED/MANAGER_APPROVED/APPROVED all count), so gating on
  violations would make the once-per-lifetime AID guard trip against itself on every CEO approval.
  Comparing only `approvedAmount` vs. `eligibleAmount` (the policy cap) avoids that self-reference
  and matches the task's literal wording ("If it exceeds the policy cap...").
- **`findUsage`'s lifetime count is intentionally NOT year-scoped** — it backs the once-per-lifetime
  AID_WEDDING/AID_ORDINATION gate, which must see every prior claim regardless of year. Confirmed by
  `SpecialMoneyRepositoryIntegrationTest.findUsageSumsOnlyApprovedRowsWithinTheRequestedCalendarYear`
  (3 rows counted lifetime vs. 1 in the calendar-year amount sum).
- **`cancel()` has no manager gate** — per the task spec ("the requester or the employee may cancel,
  only while SUBMITTED"), unlike `OvertimeService.cancel` which also lets a manager cancel through
  MANAGER_APPROVED/APPROVED. Confirmed by `employeeCannotCancelAnotherEmployeesRequest`.
- **`GET /employees` is a real employee picker**, not a placeholder — mirrors
  `OvertimeController`/`OvertimeRepository`'s `findEmployeeOptions`/`OvertimeEmployeeOption` with
  local, decoupled `SpecialMoneyEmployeeOption`/`findEmployeeOptions`. The task listed the endpoint
  without specifying its shape; a first draft stubbed it via `list()`, which was replaced with a
  proper implementation before finishing.
- **`SpecialMoneyEmployeeAccess`, not a reused `OvertimeEmployeeAccess`** — per the task's explicit
  instruction to keep the two modules decoupled even though the shape is identical.
- **Repository additions beyond the task's literal method list**: `findEmployeeOptions`,
  `findExcludedProvinces`, `findCeoApproverEmployeeIds`. All three are load-bearing for endpoints
  and notification fan-out the task DID name (`GET /employees`, the submit-time evaluator call, and
  `notifyManagerApproved`'s CEO fan-out mirroring `OvertimeService`) — flagged here rather than
  silently added.

## Assumptions
- `hr.payroll_period.status = 'PROCESSED'` is the correct marker for "this month's payroll has
  already been written" — confirmed by grepping `PayrollRepository.java`, which sets exactly that
  status string on the same table/column the task named.
- Policy-amount lookups (`findPolicyAmounts`) assume the business invariant that at most one
  `special_money_policy` row per `(request_type, policy_key)` is effective on any given date — the
  schema does not enforce non-overlapping `effective_from`/`effective_to` ranges, only uniqueness of
  `(request_type, policy_key, effective_from)`. If two effective rows for the same key ever
  overlap, `findPolicyAmounts` picks whichever Postgres returns last (no `ORDER BY`); this matches
  how the V66 seed data is actually shaped (one live row per key at a time) but is worth a note for
  whoever builds the CEO's policy-editing UI in a later slice.

## Known Risks
- No payroll integration exists yet (explicitly out of scope) — `approved_amount`/`payroll_month`
  are written but nothing reads them into a payslip. This mirrors the slice-1 migration's own
  comment.
- No attachment upload wiring (explicitly out of scope) — `evidenceRequired()` types can be
  submitted today without any evidence actually being attached; `hr.special_money_request_attachment`
  exists but nothing writes to it yet.
- `UNIFORM_PREPROBATION_KIT` stays effectively disabled until a human fills in the real
  `sales_support_department_code` (slice-1 concern, unchanged here).
- No frontend exists yet for this feature; only the API surface is implemented and tested.

## Things Not Finished
- Frontend UI (explicitly out of scope for this slice).
- Attachment upload/download endpoints (explicitly out of scope for this slice — later slice).
- Payroll wiring that consumes `approved_amount`/`payroll_bucket`/`payroll_month` (gated on an
  external sign-off per the V66 migration's own comment).

## Recommended Next Agent
Claude Opus review, then (once approved) a slice-3 implementation agent for either the frontend
submission/review UI or the attachment upload endpoints — whichever the product owner prioritizes
next.

## Exact Next Prompt
```
Review the special-money-requests slice 2 backend implementation in
.claude/worktrees/special-money (branch feat/special-money-requests) against the handoff at
docs/agent-handoffs/90_feat-special-money-requests.md. Confirm: (1) the CEO cap re-check logic in
SpecialMoneyService.ceoApprove is sound (it deliberately checks eligibleAmount() rather than
violations() — verify that reasoning holds), (2) SpecialMoneyScopeIntegrationTest's 10 cases give
real coverage of every gate SpecialMoneyService exposes, (3) nothing in
SpecialMoneyRepository/SpecialMoneyService reaches into th.co.glr.hr.payroll or any frontend file.
If clean, hand off to implement slice 3 (attachment upload/download endpoints OR the frontend
submission/review UI, product owner's call) on a fresh branch off main.
```

---

## Slice 3 Update (frontend) — 2026-07-20

### Task
Frontend consumption of the slice-2 API: the mock/hrApi/routes layer, a combined "คำขอ" page
(overtime + welfare tabs), and the `SpecialMoneyPanel` submit/review UI. Backend untouched.

### Scope
- **In:** `frontend/src/api/{routes,hrApi,queryKeys,mockApi}.js`, a new combined tab-bar page, a
  pure move of `OvertimePage`'s body into `OvertimePanel`, `SpecialMoneyPanel` + tests.
- **Out:** attachment upload/download (no backend endpoint yet — rendered as a disabled, labeled
  placeholder with a note), `backend/`, `PayrollPage.jsx`.

### Files Changed
- `frontend/src/api/routes.js` — added `specialMoney` endpoint block (mirrors `overtime`) and
  `canViewAllSpecialMoney: ['hr', 'ceo']` to `ROLE_PERMISSIONS`.
- `frontend/src/api/hrApi.js` — added `api.specialMoney.{list, create, employees, usage, types,
  approve, reject, cancel}`.
- `frontend/src/api/queryKeys.js` — added `specialMoneyRequests/Employees/Types/Usage` keys.
- `frontend/src/api/mockApi.js` — new `specialMoney` namespace (`// Mirrors SpecialMoneyService`
  header), seed data (5 requests spanning every status), `canReviewSpecialMoney`/
  `canViewAllSpecialMoney`/`canAccessSpecialMoneyEmployee`/`buildSpecialMoneyRecord` helpers kept
  deliberately separate from the overtime equivalents (comment explains why, echoing issue #199).
  Mock `create` does NOT reimplement `SpecialMoneyPolicyEvaluator`'s cap/eligibility math — it
  authorizes and transitions status faithfully but passes `requestedAmount` through unclamped; this
  is called out in the namespace's header comment. Mock authz is explicitly commented as an
  approximation, not authoritative.
- `frontend/src/utils/format.js` — added `specialMoneyStatusLabel` next to the existing
  `overtimeStatusLabel`/`leaveStatusLabel` canonical maps.
- `frontend/src/features/overtime/OvertimePanel.jsx` (new) — the exact former body of
  `OvertimePage.jsx`, function renamed `OvertimePanel`, otherwise byte-identical (no logic change).
- `frontend/src/features/overtime/OvertimePage.jsx` — reduced to a one-line re-export
  (`export { OvertimePanel as OvertimePage } from './OvertimePanel.jsx'`) so
  `OvertimePage.test.jsx` keeps passing unchanged against the same import path/export name.
- `frontend/src/features/requests/RequestsPage.jsx` (new) — page shell: `PageHeader` "คำขอ" + a
  Tailwind tab bar (`role="tablist"`), tab state in `?tab=ot|welfare` (default `ot`), renders
  `<OvertimePanel/>` or `<SpecialMoneyPanel/>`.
- `frontend/src/features/specialmoney/SpecialMoneyPanel.jsx` (new), `specialMoneyRules.js` (new,
  client-side fast-feedback constants/estimator — explicitly commented as an approximation of the
  V66 seed, never authoritative), `thaiProvinces.js` (new, 77-province list + the excluded-province
  set copied verbatim from the migration).
- `frontend/src/features/specialmoney/SpecialMoneyPanel.test.jsx`,
  `frontend/src/features/requests/RequestsPage.test.jsx` (new tests).
- `frontend/src/App.jsx` — lazy `RequestsPage` at `/employee-requests`; `/overtime` becomes
  `<Navigate to="/employee-requests?tab=ot" replace/>`.
- `frontend/src/components/layout/AppShell.jsx` — the `/overtime` nav item becomes `/employee-requests`,
  label "คำขอ", `match: ['/employee-requests', '/overtime']`, visible to anyone with an employee record
  or `canViewAllOvertime`/`canViewAllSpecialMoney`.
- `frontend/src/app/permissions.js` — `PATH_GUARDS` entry for `/overtime` extended to also cover
  `/employee-requests`, condition widened to include `canViewAllSpecialMoney`.

### Deviation from the spec — route path (`/requests` → `/employee-requests`)
The task spec asked for the combined page at `/requests`. That path is already
`ProfileRequestsPage`'s HR review-queue route, with call sites in `AppShell.jsx`, `HrDashboard.jsx`,
and `EmployeeDashboard.jsx` (all navigate/link there). Moving `ProfileRequestsPage` off `/requests`
to free the path would mean rewriting those unrelated dashboard pages — a much larger, riskier diff
than this slice should carry. Mounted the combined page at `/employee-requests` instead and left
`ProfileRequestsPage`/`/requests` completely untouched.

**Known consequence, not fixed here (backend, out of scope):** `SpecialMoneyService.notifySubmitted`
/`notifyManagerApproved`/`notifyCeoApproved`/`notifyRejected` all hardcode `"/requests"` as the
notification link (see `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyService.java`
lines ~366-430). Under this frontend layout those links resolve to the *profile-requests* queue, not
the welfare tab — clicking a special-money notification lands on the wrong page. `OvertimeService`
by contrast hardcodes `"/overtime"`, which this branch's `/overtime → /employee-requests?tab=ot` redirect
does handle correctly. Recommended follow-up: a tiny backend change to
`SpecialMoneyService`'s four `notificationService.notify(...)` calls, swapping `"/requests"` for
`"/employee-requests?tab=welfare"`.

### Other deviations / accepted gaps
- **Double page header when the OT tab is active.** `OvertimePanel` was moved with "no logic change
  whatsoever," so it still renders its own `PageHeader title="จัดการล่วงเวลา"` inside
  `RequestsPage`'s own `PageHeader title="คำขอ"`. Not fixed, since removing it would be a logic/UI
  change to the untouchable moved body.
- **Evidence upload is a disabled, labeled placeholder** (no backend endpoint exists yet), per the
  task's explicit instruction — no endpoint was invented.
- **UNIFORM_PREPROBATION_KIT / UNIFORM_NEW_STAFF** show the same shirt/trouser count fields as
  `UNIFORM_ANNUAL` but the live amount estimate only auto-computes for `UNIFORM_ANNUAL` (the only
  type with known per-piece rates in the V66 seed); the other two fall back to a manual amount
  input. `UNIFORM_PREPROBATION_KIT` is server-disabled anyway (placeholder department code, per
  slice 1/2 notes) so this has no real-world effect yet.
- **`AID_FUNERAL` gained a `relation` select** (parent/spouse/child) not explicitly listed in the
  task's per-type field bullets, because `SpecialMoneyPolicyEvaluator.evaluateFixedAid` hard-requires
  `detail.relation` to be one of those three values — omitting the field would make every funeral-aid
  submission fail server-side.
- **Overseas per-diem (`destination=OVERSEAS`, `region` field) is not exposed** — the task's field
  list for `TRAVEL_PER_DIEM`/`TRAVEL_LODGING` only described the domestic fields (start/end date,
  province, role), so this slice only submits `destination: 'DOMESTIC'`.
- Client-side cap/rate numbers in `specialMoneyRules.js` (medical ฿3,000, aid ฿5,000, uniform
  ฿300/฿350 per piece/4-piece max, per-diem ฿400 driver/฿200 loader) are copied from
  `V66__special_money_request_schema.sql`'s seed for fast inline feedback only; the server
  (`SpecialMoneyPolicyEvaluator` + live `hr.special_money_policy` table) is the sole authority and
  will diverge silently if that table is ever edited without updating this file too — documented
  in-file.

### Commands Run
```bash
cd frontend && npm ci
cd frontend && npm run lint
cd frontend && npx vitest run
cd frontend && npm run build
```

### Test / Build Results
- Lint: **0 errors**, 3 pre-existing warnings (`CommissionPage.jsx` x2, `PayrollPage.jsx` x1 —
  confirmed pre-existing/out of scope, not touched by this branch).
- Tests: **PASS — 38 test files, 222 tests, 0 failures** (`npx vitest run`). New: 5
  `SpecialMoneyPanel.test.jsx` (type-swap fields, per-diem days×rate, excluded-province zeroes +
  warns, tax chip flips, submit payload shape) + 2 `RequestsPage.test.jsx` (both tabs render,
  `?tab=welfare` selects the welfare panel). `OvertimePage.test.jsx` passes unchanged (2 tests, same
  file, same import).
- Build: **PASS** (`npm run build`, 147ms, `RequestsPage` code-split into its own chunk).
- No backend commands were run — backend is untouched in this slice.

### Authz Evidence
**Unverified — mock only.** This slice only touches `frontend/src/api/mockApi.js`'s `specialMoney`
namespace (a new mock authz surface mirroring `SpecialMoneyService`'s gates) and the frontend route
guards in `permissions.js`/`AppShell.jsx` (visibility only, not a real permission decision — the
underlying `canViewAllOvertime`/`canViewAllSpecialMoney` role lists are unchanged, just OR'd
together for one nav item). No backend authorization code was touched. The slice-2 handoff above
already carries the real-Postgres authz evidence for the actual `SpecialMoneyService` gates
(`SpecialMoneyScopeIntegrationTest`); nothing here supersedes or re-verifies it. Per CLAUDE.md,
verification here ran under `VITE_USE_MOCKS=true`-equivalent unit tests only — treat the mock's
authz shape as an approximation, not proof of the real 403 behavior.

### Known Risks
- The `/requests` notification-link mismatch above (backend hardcodes the wrong-for-this-layout
  path) — cosmetic (wrong landing page on a deep link, not a broken link), but worth a follow-up.
- `specialMoneyRules.js`'s client-side cap numbers can silently drift from `hr.special_money_policy`
  if that table is ever edited outside a migration (e.g. a future CEO policy-editing UI) — the file
  is commented accordingly but there is no automated check tying the two together.
- Mock `specialMoney.create` does not enforce caps/eligibility (documented above) — a mock-only
  submit can succeed with amounts the real service would reject or clamp. Never treat a
  mock-verified submit as proof the real cap logic ran.
- Manual-browser click-through was not performed in this session (no dev server/browser step was
  requested); verification here is `npm run lint && npx vitest run && npm run build` only.

### Things Not Finished
- Attachment upload/download endpoints (explicitly out of scope this slice, backend has no endpoint
  yet).
- The `/requests` notification-link follow-up described above.
- Payroll wiring (unchanged from slice 2 — still gated on external sign-off).

### Recommended Next Agent
Claude Opus review of this frontend slice, focused on: (1) the `/employee-requests` vs `/requests`
routing deviation and whether the recommended backend notification-link fix should be scheduled, (2)
whether `SpecialMoneyPanel`'s per-type field set is complete enough for a first release, (3) a manual
click-through under `VITE_USE_MOCKS=true` to sanity-check the UI end-to-end (not done in this
session).

### Exact Next Prompt
```
Review the special-money-requests slice 3 frontend implementation in
.claude/worktrees/special-money (branch feat/special-money-requests) against the "Slice 3 Update"
section of docs/agent-handoffs/90_feat-special-money-requests.md. In particular: (1) confirm the
/requests -> /employee-requests routing deviation is the right call given the existing ProfileRequestsPage
call sites, (2) decide whether to schedule the backend follow-up (SpecialMoneyService's four
notify() calls hardcode "/requests" instead of "/employee-requests?tab=welfare"), (3) manually click
through SpecialMoneyPanel under VITE_USE_MOCKS=true (submit each request-type category, approve/
reject/cancel flows, the excluded-province and medical-cap-clamp warnings) since this session only
ran automated tests. If clean, this slice is ready to merge; permission/authz claims in this handoff
are explicitly UNVERIFIED against the real Java service (no backend authz surface was touched, so
none was required) — do not represent them as tested beyond that mock-only unit-test level.
```

---

## Payroll reconciliation against the accountant's 2026 workbook (2026-07-20)

Findings recorded here for context; the reconciliation test itself
(`PayrollExcelReconciliationTest`, 7 cases, figures transcribed from sheet พ.ค.69 / May 2026) lives
on the **`feat/payroll-reconciliation`** branch alongside the fixes, not on this branch. **No payroll
code was changed by this branch.**

### Reconciles exactly
- **Taxable gross** = sheet column W (`SUM(H:V)`) for all seven sampled employees.
- **Social security** = 5% capped at the 2026 ฿17,500 ceiling → ฿875. All 23 above-ceiling employees
  match; the five sub-ceiling figures (760 / 600 / 563 / 354 / 260) are each exactly 5% of wage.
- **Net-pay identity** `net = gross − deductions + non-taxable`. All **33 rows** of the May sheet
  check out against its own arithmetic (`AD = W − AC`, `AL = Σ(AE:AK)`, `AN = AD − AL + AM`) with
  **zero mismatches**. The engine's structure is the sheet's structure.

### Does NOT reconcile — four findings

1. **Withholding tax cannot match until allowances are loaded.** For จริญญา (฿45,000/mo) the sheet
   withholds **฿296**; the engine run for January with no allowance data withholds **฿1,204.17**.
   The engine applies only the 50% expense deduction (cap 100,000), the 60,000 personal allowance
   and SSO. The accountant also applies spouse / children / parent care / insurance / RMF / mortgage
   — none of which appear in this workbook. `PayrollTaxAllowanceInput`'s 16 fields exist for exactly
   this and are empty. **HR must load them per employee before the tax column can be trusted.**
   (An exact reconstruction was attempted and abandoned: each allowance field carries its own
   statutory cap, so the accountant's total cannot be expressed as a single number.)

2. **MIGRATION HAZARD — an empty year-to-date under-withholds, to zero.**
   `projectedAnnualIncome = YTD + thisMonth × monthsRemaining` is a correct catch-up mechanism *when
   YTD is populated*. Empty, it projects only the remaining months. Same employee, same salary:
   Jan ฿1,204.17 · Mar ฿656.25 · May ฿268.75 · **Aug ฿0.00** · **Dec ฿0.00**.
   Before the first live mid-year run, each employee's YTD taxable income, SSO and withholding must
   be back-loaded, or everyone is under-withheld and finds out at filing time.

3. **ค่าตอบแทนกรรมการ (director remuneration, column G) has no home and no SSO exemption.**
   In the workbook the five directors have column G filled, D/H (salary) **empty**, the same amount
   every month, and **no social security row at all** — director remuneration is not wages under the
   Social Security Act. Employees all carry 875. The engine derives `ssoWageBase` from
   `baseSalary`, and `findActiveEmployees` feeds it `hr.employee.current_salary`, so a director whose
   remuneration is stored as salary is charged **฿875/month the accountant does not charge** — the
   company over-deducts and the SSO filing disagrees with payroll.
   There IS a correct workaround (base salary 0, remuneration as an allowance → SSO 0, gross
   unchanged) and it is now pinned by a test, but nothing prevents HR from typing it into the salary
   field instead. A `director_remuneration` column or an SSO-exempt flag on the employee is the real
   fix; both are payroll-math changes and out of scope here.

4. **The sheet has four pre-tax deduction columns; the engine has one.** หักขาดงาน / หักตามใบเตือน /
   ลูกค้าคืนสินค้า / อื่นๆ (Y..AB) are all subtracted *before* tax. The engine has only
   `unpaidLeaveDeduction`; the other three would have to go through `otherPostTaxDeductions`, which
   is applied *after* tax — so a warning-letter deduction would be taxed when the accountant does not
   tax it. Invisible in May (all four columns are zero) but it bites the first month anyone gets one.

### Tax-position gate — CLEARED
The accountant has confirmed **in writing** that the 2018 สวัสดิการ policy qualifies as a documented
medical-benefit scheme and that uniform reimbursement is ค่าเครื่องแบบ for exemption purposes.
Medical and uniform may therefore be routed into payroll as non-taxable income (sheet column AM).
Gates still open before the payroll wiring: the `bankExport` reconciliation check, and the two
production `SELECT`s (probation exclusion count, sales-support `source_code`).
