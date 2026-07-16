# Agent Handoff

## Task
Implement the last open sub-task of #206 — the manager-resolution model — per the approved plan
(`~/.claude/plans/take-a-look-at-dreamy-honey.md`). Replace the mock's division-scan manager
inference (`managerIdForEmployee`, which scanned a division for `positionTh === 'ผู้จัดการฝ่าย'`)
with a stored per-employee `managerId` link mirroring the backend's self-FK
`hr.employee.reports_to_employee_id`, and make leave and overtime review gates follow their
genuinely different Java models instead of one shared inference:
- Leave (`LeaveService.isDirectManager`): stored FK match + target-employee `active()` check, no
  division fallback, hr bypass only.
- Overtime (`OvertimeService.managesEmployee`): stored FK match OR position-derived division-manager
  match, no `active()` check, no hr bypass.
- `overtime.employees()` gains Java's division term for managers (scope + `directReport`);
  `leave.employees()` must not.

## Branch
`claude/item-206-manager-model` (worked in worktree `item-201-885dff`)

## Base Commit
`a7991d727fe01d668e4f7d2f2446a2ab0a28ebc2` (tip of this branch at start — matches `main`/PR #210's
merge commit, i.e. `1a535df` plus the merge; PR #210 shipped sub-tasks 1/2/4 and left this
sub-task 3 open per the issue's final comment).

## Current Commit
Not committed — changes left in the working tree for review, as instructed. Do not commit/push.

## Agent / Model Used
Claude Sonnet 5 (implementation).

## Scope

### In Scope
- `frontend/src/data/demoData.js` — store `employee.managerId` alongside the existing `reportsTo`
  display string.
- `frontend/src/api/mockApi.js` — `managerIdForEmployee`, `canReviewLeave`, `canReviewOvertime`,
  `leave.employees()`, `overtime.employees()`, and their `// Mirrors` comments.

### Out of Scope (deliberately untouched)
- `dashboardManager`, `dashboardDivisionId`, all dashboard scope helpers, and the `attendance.list`
  gate — plan explicitly says these already correctly mirror `user.manager()`/`user.divisionId()`
  and must not be touched.
- `dashboardManager`'s `role === 'sales_manager'` OR-branch — left alone per plan (Java derives this
  from position name; the demo `sales_manager` persona's own position makes the branch moot in
  practice, noted in the existing comment, not re-litigated here).
- Backend — no Java files touched; this is a frontend-mock-only branch.
- Browser spot-check — explicitly deferred to the reviewer per the task instructions.

## Files Changed
- `frontend/src/data/demoData.js` — in the existing post-pass that sets `employee.reportsTo`
  (:207-210), added `employee.managerId = employee.positionTh === 'ผู้จัดการฝ่าย' ? null :
  divisionManagers[employee.divisionId]`, with a comment naming it as the
  `hr.employee.reports_to_employee_id` mirror. The six division managers (seed employee ids 1, 6,
  13, 16, 20, 23) get `managerId: null` — there is no MD employee row, matching the real NULL-FK
  state.
- `frontend/src/api/mockApi.js`
  - `managerIdForEmployee(employee)` rewritten from the division-scan to a one-line stored-link
    read: `return employee?.managerId ?? null;`. Comment updated to name the FK column.
  - `canReviewLeave(user, employeeId)`: added the `active()` check Java has and the mock never did
    (`Boolean(employee?.active && user.employeeId && managerIdForEmployee(employee) ===
    user.employeeId)`), kept the `hr`-only bypass and no-division-fallback shape. New `// Mirrors
    LeaveService.canReviewEmployee()/isDirectManager()` comment.
  - `canReviewOvertime(user, employeeId)`: restored the division-manager prong the inference had
    collapsed away — `directReport = managerIdForEmployee(employee) === user.employeeId`;
    `divisionManager = dashboardManager(user) && dashboardDivisionId(user) != null &&
    dashboardDivisionId(user) === employee.divisionId && employeeId !== user.employeeId`; returns
    `directReport || divisionManager`. No `active()` check (Java has none here). Comment expanded to
    name `OvertimeService.managesEmployee()` and explicitly warn that a future DRY-merge of
    `canReviewLeave`/`canReviewOvertime` would reintroduce the #199 bug class.
  - `leave.employees()`: no logic change (it already read through `managerIdForEmployee`, which now
    returns the stored FK), only added a `// Mirrors LeaveRepository.findEmployeeOptions()` comment
    documenting that it deliberately has no division term.
  - `overtime.employees()`: added the division term per Java's `OvertimeRepository.findEmployeeOptions()`
    — scope now includes same-division employees when the actor is a position-derived division
    manager (`dashboardManager`/`dashboardDivisionId`), and `directReport` is `true` for either an
    FK match or same-division-and-not-self. New `// Mirrors OvertimeRepository.findEmployeeOptions()`
    comment.

## Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
# throwaway behavioral script (written, run, then deleted — not in the diff):
npx vitest run src/api/__manager-model-behavioral.test.js
```

## Test / Build Results
- Frontend lint: **PASS** — 0 errors, 10 pre-existing `react-hooks/exhaustive-deps` warnings
  (unrelated to this change, unchanged from before).
- Frontend tests: **PASS** — 19 test files, 94 tests, 0 failures (identical count/result before and
  after this change — no existing test exercises the manager-inference logic directly, matching the
  plan's note that `contract.test.js` only asserts method-surface names).
- Frontend build: **PASS** — `vite build`, `built in 142-144ms`, no errors.
- Backend: **NOT RUN** — untouched by this branch; per CLAUDE.md/plan, no Java files were changed
  so the backend suite was not run.
- Browser spot-check: **NOT RUN** — deferred to the reviewer per the task instructions.

### Behavioral script — real observed results (script deleted after running; not in diff)
Four scenarios, run against the live `mockApi` module via `api.auth.login`/`api.leave.*`/
`api.overtime.*`/`api.employees.*`, in this order (later steps' mutations are intentional and
build on earlier ones within the same throwaway module instance):

**(a) Overtime dual-stage approval + HR 403 + employee-13 unreviewability**
- HR attempting `overtime.approve(1)` (OT#1, employee 9, SUBMITTED) → **rejected, status 403**.
- `warehouse.manager@glr.co.th` (employeeId 6) `overtime.approve(1)` → **`MANAGER_APPROVED`** (FK
  prong: `employees[8].managerId === 6`).
- `ceo@glr.co.th` `overtime.approve(1)` → **`APPROVED`**.
- OT#2 (employee 13, a division manager, `managerId: null`) is seeded already `APPROVED`, so
  approve/reject on it always 409s regardless of caller and can't isolate the auth gate. Instead
  proved "stage-1 unreviewable" the way it actually manifests: filing a **new** OT request on
  employee 13's behalf —
  - as `hr@glr.co.th`: `overtime.create({employeeId: 13, ...})` → **rejected, status 403** (no FK,
    HR has no OT bypass).
  - as `warehouse.manager@glr.co.th`: same call → **rejected, status 403** (different division, no
    FK — nobody but employee 13 themself, who has no login persona, can manage them for OT).
  - `overtime.list({employeeId: 13})` as hr → OT#2's DTO has **`managerEmployeeId: null`**.

**(b) Leave: hr + FK manager can review employee 9; sales cannot; employee 13 is hr-only**
- `hr@glr.co.th` → `leave.list()` includes Leave#1 (id 1, employee 9); DTO
  `managerEmployeeId: 6`, `managerName: "นางสาวรัตนา สุขสวัสดิ์"` (employee 6's name).
- `warehouse.manager@glr.co.th` → `leave.list()` **includes** Leave#1 (FK match).
- `sales@glr.co.th` (employeeId `null`) → `leave.list()` **excludes** Leave#1.
- `hr@glr.co.th` → Leave#2 (id 2, employee 13) DTO has **`managerEmployeeId: null`**.
- `warehouse.manager@glr.co.th` → `leave.list()` **excludes** Leave#2 (no FK, no division
  fallback in leave — confirms the asymmetry).

**(c) `overtime.employees()` vs `leave.employees()` scope, as `warehouse.manager`**
- `overtime.employees()`: 8 employees, 7 flagged `directReport: true`.
- `leave.employees()`: 8 employees, 7 flagged `directReport: true`.
- Employees present in overtime's list but absent from leave's: **none** (`[]`).
- **Why they're identical on this seed** (expected, not a bug — the plan called this out): every
  non-manager employee's `managerId` is set to *their division's* manager
  (`divisionManagers[employee.divisionId]`), so "FK reports" and "same-division colleagues" are the
  same set for every division in this seed. The division term in `canReviewOvertime`/
  `overtime.employees()` is real and matches `OvertimeRepository.findEmployeeOptions()`, but it only
  becomes visibly different from the leave scope if a future seed edit gives some employee a
  `managerId` that doesn't match their `divisionId`'s manager (e.g. a cross-division report, or an
  employee mid-transfer). Confirmed the code path is correct by inspection against
  `OvertimeRepository.findEmployeeOptions()`/`LeaveRepository.findEmployeeOptions()` (see Files
  Changed); the seed just doesn't currently exercise the divergence.

**(d) Leave active-check**
- Before: employee 9 `active: true`.
- `hr@glr.co.th` → `employees.update(9, { statusId: 'RSG' })` → employee 9 now `active: false`.
- `warehouse.manager@glr.co.th` (FK manager of employee 9) → `leave.cancel(1)` (Leave#1, still
  `APPROVED`, approver-bypass cancel path) → **rejected, status 403** (active-check fails despite a
  valid FK match — the exact behavior Java's `isDirectManager()` enforces and the old
  division-scan inference never checked).
- `hr@glr.co.th` → `leave.cancel(1)` → **succeeds**, `request.status === 'CANCELLED'` (hr bypass has
  no active-check, matching `canReviewAll()`).
- Used `leave.cancel()` rather than a fresh `approve()`/`reject()` call because `leave.create()` in
  this mock always auto-decides `APPROVED`/`AUTO_REJECTED` synchronously (there is no path to a
  fresh `SUBMITTED` leave request through the API surface) — `cancel()`'s approver-bypass branch
  gates through the identical `canReviewLeave()` call, so it exercises the same logic.

## Decisions Made
- Kept `managerIdForEmployee` as the function name (11 call sites unchanged) per the plan's
  explicit instruction, even though it's now a one-line lookup rather than a scan.
- For testing OT "stage-1 unreviewable" on employee 13, used `overtime.create()` 403s from two
  different actors (hr, warehouse.manager) plus a direct DTO check, instead of `approve()`/
  `reject()` on the already-`APPROVED` OT#2 — the seeded request's status made the review gate
  unobservable through approve/reject directly (409 masks it), so I picked the closest available
  code path that isolates the actual `canReviewOvertime` check.
- For testing the leave active-check (item d), used `leave.cancel()`'s approver-bypass branch
  instead of `approve()`/`reject()` on a fresh `SUBMITTED` request, because `leave.create()` never
  produces `SUBMITTED` in this mock (see behavioral results above) — there is no way to get a fresh
  reviewable leave request through the API surface alone. `cancel()` calls the identical
  `canReviewLeave()` gate, so the check is equivalent.
- Mutated employee 9 to `active: false` via `employees.update(..., { statusId: 'RSG' })` (a real,
  already-existing mock endpoint) inside the throwaway script rather than reaching into `db`
  directly — `db` isn't exported from `mockApi.js`, only `api` is, so this keeps the behavioral
  proof honestly scoped to the public API surface as the plan asked ("test through the api surface
  however works").

## Assumptions
- The plan's own risk section anticipated that the seed's uniform per-division `managerId`
  assignment would make the leave/overtime scope difference invisible in this specific demo data;
  treated that as confirmed-expected rather than a bug once directly observed in (c) above.
- No MD (managing-director) employee row exists in the seed; the six division managers'
  `managerId: null` is intentional and mirrors a real NULL top-of-chain FK, per plan §1.

## Known Risks
- `canReviewOvertime`'s division prong widens mock OT review to any position-derived division
  manager sharing the target's division (e.g. the `sales_manager` persona for SAL employees) —
  this is what production does (`managesEmployee`'s second prong), but it's a visible behavior
  change from the pre-existing mock, which only had the FK check. A browser spot-check with the
  `sales_manager@glr.co.th` persona reviewing a SAL-division OT request would confirm this is
  wired correctly.
- `overtime.employees()`'s new division term enlarges a manager's OT-employee dropdown vs the
  previous mock behavior (though it happens to be a no-op on the current seed, per (c) above) —
  also prod-matching but visibly different if the seed changes.
- The leave/OT asymmetries (active-check present only in leave, division-prong present only in
  overtime) are now explicit in two adjacent, similar-looking functions. The code comments on both
  `canReviewLeave` and `canReviewOvertime` now explicitly warn against a future "DRY merge" — a
  reviewer should confirm those comments read clearly, since this is exactly the shape of mistake
  that caused #199.
- This branch only touches the mock; **mock authorization is never authoritative** (per CLAUDE.md's
  "Mock API contract" section) — nothing here was verified against the actual Java services beyond
  reading the source directly (`LeaveService.java`, `OvertimeService.java`,
  `LeaveRepository.java`, `OvertimeRepository.java`), which was done as part of this task and is
  reflected in the updated `// Mirrors` comments.

## Things Not Finished
- Browser spot-check (plan verification step 3: `warehouse.manager` sees OT#1's approve button and
  ลูกทีม flags in the OT dropdown; employee persona sees neither) — explicitly deferred to the
  reviewer.
- Backend test suite — not run; no backend files were changed on this branch.

## Recommended Next Agent
Reviewer (Opus) — browser spot-check per "Things Not Finished" above (temp `frontend-mock` launch
config pointed at this worktree, revert before commit), then human review before merge. This
closes the last sub-task of #206 — the issue can be closed once this merges.

## Exact Next Prompt
"Review branch `claude/item-206-manager-model` in worktree `item-201-885dff` (handoff:
`docs/agent-handoffs/51_manager-resolution-model.md`) for the #206 manager-resolution-model
change. Do the deferred browser spot-check in mock mode: log in as `warehouse.manager@glr.co.th`
and confirm OT#1 (employee 9)'s approve button appears and the overtime employee-picker flags WHL
colleagues as ลูกทีม; then log in as `employee@glr.co.th` and confirm neither is visible. Also spot
check `sales_manager@glr.co.th` reviewing a SAL-division overtime request (the new division-prong
risk noted in the handoff). If clean, this closes #206 sub-task 3 and the issue can be closed on
merge."
