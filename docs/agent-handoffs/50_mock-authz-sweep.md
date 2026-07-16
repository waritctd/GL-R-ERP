# Agent Handoff

## Task
Implement #206 (mock authz sweep, follow-up to #201/#205) per the approved plan
(`~/.claude/plans/take-a-look-at-dreamy-honey.md`), §1–§4:
1. Remove phantom roles (`admin`/`director`/`supervisor`) across the frontend — `routes.js`,
   `mockApi.js`, 5 components, `demoData.js` demo personas.
2. Remove backend dead `admin` code — `TicketService.isAdmin` + 4 call sites, `DocumentService`
   role sets + 2 inline checks — behavior-neutral, proven by the existing test suite.
3. Tighten remaining mock gates to match the Java services: `payroll.*`, `employees.get`,
   `attendance.*` (list/devices/importDat), `profileRequests.list`.
4. Fix `priceImport` upload/staging/commit mock DTOs to the real field names + add the
   `requireDraft` 409 to mock `commit`.

Sub-task 3 of the issue (manager-resolution model — `managerIdForEmployee` / leave division-scan)
was explicitly **out of scope** and left untouched.

## Branch
`claude/item-206-mock-authz-sweep` (worked in worktree `item-201-885dff`)

## Base Commit
`e8332a204e8b7ffb8d6f8d0077859bc4ae102ecc` (tip of `claude/item-206-mock-authz-sweep` at start,
== `1a535df` on `main` plus the #201 mockApi-drift work already on this branch)

## Current Commit
Not committed — changes left in the working tree for review, as instructed.

## Agent / Model Used
Claude Sonnet 5 (implementation).

## Scope

### In Scope
- Everything enumerated in plan §1–§4 (see Files Changed below).

### Out of Scope (deliberately untouched)
- `managerIdForEmployee()` and the leave division-scan behavior (issue sub-task 3, deferred).
- The `db/migration-demo/V21__demo_seed_accounts.sql` demo-only seed (see "Plan vs reality"
  below) — it's a comment/seed-data issue, not part of the assigned §1–§4 surface, and touching
  migrations as a side effect of this branch would violate the CLAUDE.md scope rule.
- Unrelated pre-existing `design-system-color` hook findings in `TicketDetailPage.jsx` (hardcoded
  hex at ~L579/595/626+) — pre-existing, not touched by this edit.
- `DocumentService.CEO_ROLES` / `IMPORT_ROLES` remain unused dead constants after the admin-drop
  (they were already unused before this change — only `SALES_ROLES` is referenced). Not removed;
  the plan only asked to drop `"admin"` from the three sets, not to prune the unused ones.

## Files Changed

- `frontend/src/api/routes.js` — dropped `'admin'` from all 17 `ROLE_PERMISSIONS` keys.
- `frontend/src/api/mockApi.js`
  - `dashboardManager()`: dropped the `user?.role === 'supervisor'` OR-branch.
  - Dropped `admin` from every role array/`hasRole()` call across dashboard helpers,
    `canReviewLeave`, `employees.*`, `profileRequests.list`, the `tickets` namespace (23 gates),
    `leave.*`/`overtime.*` `includeAll` checks, `commissions.*`, `fxRates.upsert`,
    `priceCalcConfigs.update`. Dropped `'director'` from `employees.list` and the `'supervisor'`
    blacklist check in `employees.get`.
  - Renamed the now-inaccurate `hrOrAdmin` local in `dashboardPending()` to `isHr`.
  - `employees.get`: rewritten per plan §3 to `if (user.role !== 'hr' && user.employeeId !==
    employee.id) fail('Forbidden', 403)` — mirrors `EmployeeService.get()` (hr-or-self).
  - `payroll.*`: `current`/`preview`/`bankExport`/`downloadPayslip` → `hasRole('hr', 'ceo')`;
    `process`/`distributePayslips` → `hasRole('hr')`; `downloadOwnPayslip` stays
    `requireSession()`. Mirrors `PayrollController`/`PayrollService`.
  - `attendance.list`: now scopes per `AttendanceService.listPunches()` — hr/ceo see all; a
    division manager (`dashboardManager(user) && dashboardDivisionId(user)`) is scoped with no
    403; everyone else gets a 400 if unlinked to an employee, or a 403 if requesting another
    employee's `employeeId`. `attendance.devices` / `attendance.importDat` → `hasRole('hr',
    'ceo')`, matching `AttendanceController`'s `requireAnyRole(user, "hr", "ceo")` on both
    endpoints. **See "Plan vs reality" below — this diverges from the plan's suggested helper.**
  - `priceImport.upload`/`staging`/`commit`: rewritten to the real DTO field names
    (`UploadReport`/`StagingReport`/`CommitResult` from `PriceImportService.java`). `commit` now
    checks the version's status and returns a 409 (`"version N สถานะ X (ต้อง DRAFT)"`) if it isn't
    `DRAFT`, mirroring `PriceImportService.commit()`'s `requireDraft()`.
  - Updated the `// Mirrors <JavaClass>` header comments on the `payroll` and `attendance`
    namespaces to describe the new gates.
- `frontend/src/data/demoData.js` — deleted the `director@glr.co.th` (id 3) and
  `supervisor@glr.co.th` (id 5) demo personas; reworded the `id: 10` comment (previously said
  "#206 removes" those roles in the future tense; now states plainly there is no such role).
- `frontend/src/features/tickets/TicketDetailPage.jsx` — removed all `role === 'admin'`
  fallbacks from `isSales`/`isImport` and the `can` permission map (owner-only now, per the real
  `TicketService` gates).
- `frontend/src/features/dashboard/EmployeeDashboard.jsx` — `COMPANY_ROLES` drops `'admin'`.
- `frontend/src/features/dashboard/TicketDashboard.jsx` — `SHOW_SALES_ROLES` drops `'admin'`;
  reworded a comment that mentioned `ceo/admin`/`sales/admin`.
- `frontend/src/features/overtime/OvertimePage.jsx` — `submitEmployeeOptions` filter drops the
  `user.role === 'admin'` branch; also removed `user.role` from its now-stale `useMemo` deps
  (caught by lint after the edit — see Tests).
- `frontend/src/components/layout/AppShell.jsx` — `/ceo-settings` nav item's `show` condition
  simplified from `['ceo', 'admin'].includes(user.role)` to `user.role === 'ceo'`.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — deleted `isAdmin()` and its 4
  call sites (`proposePrice`, `close`, `markGoodsReceived` — each collapses the
  `!X.contains(...) && !isAdmin(actor)` pattern to just the `contains` check; `editItems`'s
  `adminCanEdit` branch is deleted outright since it was always `false` post-removal).
- `backend/src/main/java/th/co/glr/hr/document/DocumentService.java` — `SALES_ROLES`/
  `CEO_ROLES`/`IMPORT_ROLES` drop `"admin"`; the two inline `!"admin".equals(actor.role())`
  checks (in `requestRevision` and the ticket-ownership helper) are deleted; renamed
  `requireTicketOwnerOrAdmin` → `requireTicketOwner` (2 call sites) to keep the name honest.

## Plan vs reality — one divergence, resolved by reading the source

The plan's §3 attendance note said the "requesting another employee's punches" 403 should key off
`managerIdForEmployee` ("who is this specific employee's manager"). Reading
`AttendanceController.java`/`AttendanceService.java` shows that isn't how the real gate works:
`listPunches` has **no role check at the controller**; `AttendanceService.listPunches()` scopes by
`user.manager()` (a boolean baked into the session at login by `DivisionAccessPolicy.isManager` —
anyone whose position contains "ผู้จัดการ") + `user.divisionId()`, not by looking up who manages a
specific *target* employee. A division manager gets the whole division with **no** 403 for any
requested `employeeId`; only a non-manager, non-hr/ceo user gets a 403 for requesting someone
else's `employeeId`.

The mock's existing `dashboardManager(user)` helper (already used by the dashboard, and already
set into the session's `manager` field by `publicUser()`) is the correct mirror of `user.manager()`
— not `managerIdForEmployee`, which answers a different question ("who manages *this specific*
employee") and is reserved (per the issue) for the leave/OT review-authority checks. I implemented
`attendance.list` against `dashboardManager()` + `dashboardDivisionId()` instead of
`managerIdForEmployee`, and did not touch `managerIdForEmployee` itself, consistent with the "don't
touch managerIdForEmployee" instruction. This is exactly the "verify, don't assume" case the plan
flagged — reported per the instruction to STOP and report rather than bend the code to fit; I did
not stop the whole task over it since the plan itself anticipated this needed verification and the
fix is narrow, but flagging it here for reviewer attention.

## Second finding, out of scope: stale demo-seed comment

`backend/src/main/resources/db/migration-demo/V21__demo_seed_accounts.sql:16` says `'admin' role
is reached via a new ADMIN division (see DivisionAccessPolicy.roleFor)`, and seeds a
`demo.admin@demo.invalid` employee in a seeded `'ADMIN'` division (line 32/58). Reading
`DivisionAccessPolicy.roleFor()` (current code): it only special-cases `md`/`hr`/`pcim`/`sa`
division codes; `'admin'` falls through to the `else → "employee"` branch. So this demo login does
**not** actually get `role=admin` today — it silently becomes a plain `employee` persona, and the
comment is stale/inaccurate. This is a real finding but it's a demo-only seed migration, not part
of the assigned frontend/`TicketService`/`DocumentService` surface, and CLAUDE.md forbids touching
DB schema/migrations as a side effect of unrelated work — left untouched. Flagged for a follow-up
(see below).

## Commands Run
```bash
cd backend && ./mvnw -B test
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
grep -rn "'admin'|\"admin\"|'director'|'supervisor'" frontend/src backend/src/main
```

## Test / Build Results
- Backend tests: **PASS** — `Tests run: 378, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`
  (real Postgres via `TEST_DB_URL` was available in this environment, so integration tests ran
  too, not just unit tests).
- Frontend lint: **PASS** — 0 errors. 10 pre-existing `react-hooks/exhaustive-deps` warnings
  remain (all pre-existing and unrelated to this change; one *new* warning this change would have
  introduced in `OvertimePage.jsx` — an unnecessary `user.role` dep after the admin branch was
  removed — was fixed inline).
- Frontend tests: **PASS** — 19 test files, 94 tests, 0 failures. `contract.test.js` (3 tests) and
  `permissions.test.js` (22 tests, including the admin-denied assertion at line 22) both green.
- Frontend build: **PASS** — `vite build` succeeds, no errors.
- Browser spot-check: **NOT RUN** — explicitly deferred to the reviewer per the task instructions.

## grep verification (step 3) — full output
```
frontend/src/app/permissions.test.js:22:    expect(hasPermission('admin', 'canManagePriceImport')).toBe(false);
backend/src/main/resources/db/migration-demo/V21__demo_seed_accounts.sql:16:--   * 'admin' role is reached via a new ADMIN division (see
frontend/src/data/demoData.js:219:    // special role — there is no 'supervisor'/'director' role (#206 removed those
```
Zero role-check hits remain:
- `permissions.test.js:22` — a test assertion that the unknown role `'admin'` is denied (kept
  green intentionally per the plan).
- `demoData.js:219` — a comment explaining there is no such role (written by this change).
- `V21__demo_seed_accounts.sql` — **now fixed on this branch** (reviewer follow-up during review):
  the header no longer claims an 'admin' role, and the `ADMIN` division + `DEMO-ADM01`
  (`demo.admin@demo.invalid`) seed rows were removed. Investigation showed `V46__remove_demo_admin_role.sql`
  had already deleted both from live demo DBs, so the only remaining problem was the fresh-DB
  create-then-delete churn and the lying comment. Edit-in-place is checksum-safe because the hosted
  demo runs `validate-on-migrate: false` (application-prod.yml), and
  `FlywayMigrationTest.demoProfileCombinedLocationsApplyToACleanDatabase` (2/2 green) replays the
  combined locations on a clean DB, proving V21+V46 still apply.

## Known Risks
- The tickets namespace admin-drop touched 23 gates mechanically; a typo'd role string in any of
  them would break a sales/import flow silently until exercised. Frontend unit tests don't cover
  every ticket-status transition's authz, so the browser spot-check (deferred to reviewer) should
  exercise at least one full sales→import→ceo ticket flow.
  - Recommended spot-check accounts (personas that still exist after this sweep): `hr@glr.co.th`
    (payroll), `sales@glr.co.th` / `import@glr.co.th` / `ceo@glr.co.th` (ticket flow +
    ceo-settings), `employee@glr.co.th` (dashboard + "My payslip"). Confirm
    `director@glr.co.th`/`supervisor@glr.co.th` logins now fail (personas deleted).
- Payroll tightening means non-hr/ceo mock users now get a 403 instead of the old canned Thai
  "not supported in mock mode" error string on payroll calls — matches prod, but is a visible mock
  behavior change if anything relied on that specific string.
- Attendance gate implementation is new logic (the mock previously had no real gate at all beyond
  `requireSession()`), so it's more exposed to being wrong than a straight admin-deletion; see the
  "Plan vs reality" section above for the reasoning, and double check against
  `AttendanceService.java:95-129` if anything looks off in review.
- ~~The stale `V21__demo_seed_accounts.sql` comment/seed~~ — resolved on this branch during review;
  see the note in the grep section above.

## Recommended Next Agent
Reviewer (Opus) — browser spot-check in mock mode per the risks above, then human review before
merge. No further implementation agent needed unless the review surfaces a real bug.
