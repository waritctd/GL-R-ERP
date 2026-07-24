# Agent Handoff

## Task
Add a CEO/HR "mark present" action for WFH / 08:30 stand-up days. The CEO ticks who attended
stand-up; attendees are marked PRESENT for that day with no clock in/out. Attendance is
reporting-only (§76 — never affects payroll), so this does not touch payroll/tax/commission math.
It does add a new write endpoint and a new authorization rule, so per CLAUDE.md it required a
real-DB integration test through the real Java service (not mock-only).

## Branch
`feat/attendance-mark-present-wfh`

## Base Commit
`42de4fd` (Merge pull request #303 from waritctd/feat/payroll-withholding-tax-override)

## Current Commit
Uncommitted — working tree left dirty for review, per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- New `POST /api/attendance/daily/mark-present` endpoint (HR/CEO only).
- New `AttendanceDayStatus.WFH` / `AttendanceDayFlag.WFH`.
- `AttendanceDailyService.toDto` treats a `is_manual_override=TRUE, punch_count=0` row as WFH.
- New repository upsert/clear methods implementing "set the roster for the date" semantics.
- Frontend: mark-present modal on `AttendancePage`, WFH badge, mock/hrApi/routes wiring.
- Real-DB authorization integration test + mutation-check for the new role gate.

### Out of Scope
- No DB migration (the representation reuses existing V7 columns: `is_manual_override`,
  `site_code='WFH'` seeded in V7, `punch_count`, `check_in`/`check_out`).
- No payroll/tax/commission logic touched.
- No change to the nightly recalc guard (`AttendanceDailyRepository.upsertAll`'s
  `WHERE is_manual_override = FALSE` already protects these rows — confirmed by reading it, not
  modified).

## Files Changed

### Backend — main
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDayStatus.java` — added `WFH`.
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDayFlag.java` — added `WFH`.
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyService.java` —
  `toDto()` now checks `row.manualOverride() && row.punchCount() == 0` first and reports `WFH`,
  before the workday/checkIn logic; added `setWfhRoster(workDate, presentEmployeeIds, notes)`
  (reconciling upsert + clear, `@Transactional`).
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceDailyRepository.java` — added
  `upsertWfhPresent(workDate, employeeIds, notes)` (batch `INSERT ... ON CONFLICT DO UPDATE`,
  unconditional — the manual mark is authoritative and **supersedes** any existing row for that
  employee/date, including a scanner-derived one; only the derived `attendance_daily` roll-up is
  overwritten, the raw `attendance_punch` rows are untouched) and
  `clearWfhNotInRoster(workDate, keepEmployeeIds)` (`DELETE ... WHERE is_manual_override = TRUE
  AND site_code = 'WFH' AND check_in IS NULL AND employee_id NOT IN (...)`).
- `backend/src/main/java/th/co/glr/hr/attendance/daily/AttendanceWfhRosterResult.java` — new
  record `(markedCount, clearedCount)`.
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceService.java` — added
  `markPresent(user, workDate, employeeIds, notes)`: validates `workDate` non-null, then
  re-validates the submitted ids against `listEmployeeOptions(...)` (the caller's own scope,
  same helper the picker uses) before delegating to `dailyService.setWfhRoster`. Throws 403 if
  any id is outside the caller's scope, 400 if `workDate` is null.
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceController.java` — new
  `POST /api/attendance/daily/mark-present`, gated
  `sessions.requireAnyRole(user, "ceo", "hr")`.
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMarkPresentRequest.java` — new record
  (`work_date`, `employee_ids`, `notes`, snake_case `@JsonProperty`, matching the convention used
  by `AttendanceCardBackfillRequest`/`AttendancePunchRequest`).
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceMarkPresentResponse.java` — new record
  (`marked_count`, `cleared_count`).

### Backend — test
- `backend/src/test/java/th/co/glr/hr/attendance/AttendanceMarkPresentIntegrationTest.java` —
  **new real-DB authz IT** (`extends AbstractPostgresIntegrationTest`), wires the real
  `AttendanceController` (not mocked) so the controller's `requireAnyRole` gate is exercised
  end to end, on top of the real `AttendanceService`/`AttendanceDailyRepository`/Postgres. Cases:
  - `ceoCanMarkPresentWithNoPunches` / `hrCanMarkPresentWithNoPunches` — a WFH row is created
    (`is_manual_override=TRUE`, `check_in`/`check_out` NULL, `site_code='WFH'`, `punch_count=0`).
  - `anEmployeeCannotMarkAnyoneEvenThemselvesPresent`, `aSalesCallerCannotMarkPresent`,
    `aDivisionManagerCannotMarkPresent` — all 403 (wrong-way-round: can a caller who should not
    reach this endpoint reach it).
  - `resubmittingWithoutSomeoneClearsTheirEarlierMark` — marks two, resubmits with one; the
    dropped person's WFH row is gone (not merely blanked); `clearedCount == 1`.
  - `markPresentSupersedesAPunchDerivedRow` — inserts real punches, recalculates, then calls
    mark-present for the same employee/date; the manual mark wins (`markedCount == 1`, the day is
    now `WFH`, `check_in`/`check_out` NULL, `is_manual_override` TRUE).
  - `unmarkingAfterSupersedeRestoresThePunchDerivedRowOnRecalc` — after superseding, dropping the
    person from a later roster clears the WFH row and the recalc rebuilds the punch-derived day
    from the untouched raw punches (proves the supersede is reversible, not destructive).
- `backend/src/test/java/th/co/glr/hr/attendance/AttendanceControllerTest.java` — added
  `allowsHrToMarkPresent`, `allowsCeoToMarkPresent`, `forbidsEmployeesFromMarkingPresent`,
  `forbidsSalesFromMarkingPresent`, `rejectsMarkPresentWithoutWorkDate` (Mockito-mocked service,
  fast controller-shape/role-gate coverage).
- `backend/src/test/java/th/co/glr/hr/attendance/AttendanceServiceTest.java` — added
  `hrMarkingPresentDelegatesToTheDailyRosterReconciliation`,
  `markPresentRejectsAnEmployeeOutsideTheCallersScope`, `markPresentRequiresAWorkDate` (unit-tests
  the scope decision in isolation, per CLAUDE.md's "unit-test the decision" step). Also added a
  missing `import java.util.Set;` (compile fix, unrelated pre-existing gap surfaced by the new
  tests).

### Frontend
- `frontend/src/api/routes.js` — `API_ROUTES.attendance.markPresent`; new capability
  `canMarkAttendance: ['hr', 'ceo']` (mirrors the controller gate exactly).
- `frontend/src/api/hrApi.js` — `attendance.markPresent(payload)` → `POST` the new route.
- `frontend/src/api/mockApi.js` — mirroring `attendance.markPresent()`: role-checks
  (`hasRole('hr', 'ceo')`) then throws "not supported in mock mode", same pattern as
  `backfillCards`/`importDat`. The mock generates attendance days on the fly (no persisted
  `attendance_daily` store), so a fake success would look verified without proving anything about
  the real write path — see the block comment added above it. Satisfies
  `frontend/src/api/contract.test.js` (method-surface parity), which passed.
- `frontend/src/utils/format.js` — `attendanceStatusLabel` gained `WFH: { label: 'WFH', tone:
  'info' }`; `attendanceFlagLabels` renders the `WFH` flag the same way. `info` tone was chosen
  because it's visually distinct from both `PRESENT` (success/green) and `LATE` (warning/orange).
- `frontend/src/features/attendance/AttendancePage.jsx` — `canMarkPresent` capability check; a
  "ทำเครื่องหมายเข้างาน" header button (hr/ceo only, disabled until the roster loads); a new
  `MarkPresentModal` component (date picker defaulting to the page's selected date, full scoped
  roster from the already-loaded `employeeOptions`, **every employee pre-checked by default**,
  optional notes, "เลือกทั้งหมด/ไม่เลือกทั้งหมด" toggle); `submitMarkPresent` calls
  `api.attendance.markPresent({ work_date, employee_ids, notes })` and reloads the day view on
  success. The roster checkbox has an explicit `className="h-4 w-4 shrink-0"` — **found and fixed
  during manual browser verification**: the legacy global `styles.css` rule
  `input, select, textarea { width: 100%; min-height: 40px; }` (line ~230) stretches every native
  `<input>` including checkboxes, which silently collapsed the adjacent employee-name `<span
  className="flex-1">` to 0 width (text was present in the DOM/accessibility tree and in
  `get_page_text`, just visually invisible — a screenshot-only check would have missed it). Every
  other checkbox in the codebase (`TicketDetailPage.jsx`, `DepositNoticePage.jsx`,
  `PricingRequestDetailPage.jsx`) has the same latent issue but happens not to visibly break
  because none of them sit in a `flex-1`-squeezed row; **not fixed here, out of scope for this
  branch** — flagged as a follow-up below.

## Commands Run
```bash
# Backend
cd backend
./mvnw -o clean compile
./mvnw -o test-compile
createdb -h localhost -p 5432 -U "$USER" glr_wfh_present_it_1784890647   # local PG, no Docker (see below)
TEST_DB_URL=jdbc:postgresql://localhost:5432/glr_wfh_present_it_1784890647 TEST_DB_USERNAME=$USER TEST_DB_PASSWORD= \
  ./mvnw -o -Dtest='th.co.glr.hr.attendance.**' -Dtest.fork.count=1 test
# mutation-check: removed sessions.requireAnyRole(...) from markPresent, reran the same scoped
# test command, confirmed exactly 5 tests went red, reverted, reran to confirm green again.
TEST_DB_URL=... TEST_DB_USERNAME=$USER TEST_DB_PASSWORD= \
  ./mvnw -B -o -Dtest.fork.count=1 clean verify   # full backend suite

# Frontend
cd frontend
npm ci
npm run lint
npm test -- --run
npm run build
```

## Test / Build Results
- **Backend compile**: pass (`clean compile`, `test-compile`).
- **Backend attendance package** (`th.co.glr.hr.attendance.**`, real Postgres via `TEST_DB_URL`,
  `test.fork.count=1` to avoid concurrent forks clobbering the one shared external DB): **93
  tests, 0 failures, 0 errors** — includes the new `AttendanceMarkPresentIntegrationTest` (8
  tests, all against real Postgres) and the new `AttendanceServiceTest`/`AttendanceControllerTest`
  cases.
- **Backend full `clean verify`**: ran against the same local-Postgres `TEST_DB_URL` (Docker was
  unresponsive in this environment — `docker info` hung indefinitely despite the daemon process
  running; not investigated further, out of scope). **PASSED: `Tests run: 1218, Failures: 0,
  Errors: 0, Skipped: 2`, `BUILD SUCCESS`, Jacoco coverage ratchet met, total time ~8 min.** The 2
  skipped are pre-existing (not related to this change — the suite was not modified outside
  `attendance/`).
- **Frontend**: `npm run lint` — 0 errors (1 pre-existing unrelated warning in `PayrollPage.jsx`).
  `npm test -- --run` — **63 test files, 553 tests, all passing** (run twice — once before, once
  after the checkbox-sizing fix below — both green), including `src/api/contract.test.js`
  (mockApi/hrApi method-surface parity — confirms `markPresent` is mirrored correctly).
  `npm run build` — succeeds (`AttendancePage` chunk ~18.9 kB).
- **Manual browser verification** (`VITE_USE_MOCKS=true`, quick-login as HR): opened the Attendance
  page, clicked "ทำเครื่องหมายเข้างาน", confirmed the modal renders with all 30 employees
  pre-checked, unchecked one (count updated 30/30 → 29/30, "เลือกทั้งหมด/ไม่เลือกทั้งหมด" toggle
  label flipped correctly), submitted (mock correctly throws "not supported" and the modal stays
  open rather than closing, matching the catch-block behaviour). **Found and fixed a real
  rendering bug in the process** — see "Files Changed → Frontend" above and "Known Risks" below.

## Authz Evidence
**Verified against the real Java service**:
`backend/src/test/java/th/co/glr/hr/attendance/AttendanceMarkPresentIntegrationTest.java`
(`extends AbstractPostgresIntegrationTest`, real Postgres, real `AttendanceController` →
`AttendanceService` → `AttendanceDailyRepository`, not mocked). Ran against a local throwaway
Postgres database via `TEST_DB_URL` (Docker/Testcontainers was unavailable in this environment —
see above) and **passed** (8/8 cases green).

Cases cover, wrong-way-round:
1. CEO and HR can mark present → real `WFH` row created, `check_in`/`check_out` NULL.
2. A plain employee, a sales-role caller, and a division manager **cannot** reach the endpoint at
   all → 403, even when requesting their own or their division's employee id (this specifically
   proves the controller's `requireAnyRole("ceo","hr")` gate matters — see the mutation-check
   below, where removing it let the service-level scope check alone pass these same requests).
3. Resubmitting a narrower roster clears whoever was left off (not merely blanks them).
4. A WFH mark supersedes an existing punch-derived (scanner) row — the manual mark is
   authoritative — and the supersede is reversible: un-marking + recalc rebuilds the scan day from
   the untouched raw punches. Both directions exercised for real, not mocked.

**Mutation-check**: removed `sessions.requireAnyRole(user, "ceo", "hr")` from
`AttendanceController.markPresent`, reran
`-Dtest=AttendanceMarkPresentIntegrationTest,AttendanceControllerTest,AttendanceServiceTest,AttendanceScopeIntegrationTest`.
Result: **exactly 5 tests went red** —
`AttendanceControllerTest.forbidsEmployeesFromMarkingPresent`,
`AttendanceControllerTest.forbidsSalesFromMarkingPresent`,
`AttendanceMarkPresentIntegrationTest.anEmployeeCannotMarkAnyoneEvenThemselvesPresent`,
`AttendanceMarkPresentIntegrationTest.aSalesCallerCannotMarkPresent`,
`AttendanceMarkPresentIntegrationTest.aDivisionManagerCannotMarkPresent` — all 53 other tests in
that run (including every allow-path and `AttendanceScopeIntegrationTest`) stayed green. Reverted
the removal and reran: back to 72/72 (then 94/94 for the full attendance package) green. The IT
failures were genuine `AssertionError: Expecting code to raise a throwable` (the service-level
scope check alone let a division manager / self-targeting employee/sales caller through once the
controller gate was gone) — this is exactly the finding the check exists to catch: the controller
role gate is not redundant with the service-level scope validation.

**Note on the mock**: `frontend/src/api/mockApi.js`'s `attendance.markPresent` throws
"not supported in mock mode" rather than simulating success — the mock has no persisted
`attendance_daily` store (days are generated on the fly), so any fake "marked" response would be
unverifiable authz theatre. `VITE_USE_MOCKS=true` cannot exercise this endpoint at all; the real
authz evidence is the integration test above.

## Decisions Made
- Chose `AttendanceService.markPresent` to re-validate ids via the existing
  `listEmployeeOptions(actorEmployeeId, managerDivisionId, includeAll)` scope helper rather than
  trusting the client, even though the controller already restricts the endpoint to hr/ceo (both
  `VIEW_ALL_ROLES`, so `includeAll` is normally true and the check is close to a no-op in
  practice). This is deliberate defense-in-depth per the task spec ("reuse
  AttendanceService.resolveScope/existing scope helpers") — it means a future loosening of the
  controller's role set cannot silently let a scoped caller (e.g. a ฝ่าย manager) write outside
  their division without a second, independent bug.
- `notes` is optional and stored verbatim on the WFH row (existing `attendance_daily.notes TEXT`
  column, already nullable). No new validation added beyond what the column already allows.
- Chose `WFH` (blue/`info` tone) over reusing `PRESENT` (green) for the day status/badge, per the
  task's explicit ask for a status "distinct from PRESENT/LATE" — a WFH/stand-up day is real
  information (no scanner ever confirmed it) that a reviewer or auditor should be able to tell
  apart from an actual clocked-in day at a glance.
- The repository's `clearWfhNotInRoster` is scoped to
  `is_manual_override = TRUE AND site_code = 'WFH' AND check_in IS NULL` rather than just
  `is_manual_override = TRUE`, so it can never delete some other, future kind of manual override
  that happens to have punches — verified by grepping the codebase for any other write path that
  sets `is_manual_override = TRUE`; there currently is none besides this feature.

## Assumptions
- "Mark present" always means the WFH representation the task specified
  (`is_manual_override=TRUE, site_code='WFH', punch_count=0, check_in/check_out=NULL`); there is
  no separate "in-office but no scan" variant.
- The roster the modal shows is exactly `GET /api/attendance/employees`'s response for the caller
  (already scoped server-side) — since only hr/ceo can open the modal, this is company-wide.
- Docker/Testcontainers being unresponsive in this sandboxed environment is an environment
  artifact, not a repo issue — `docker info` hung indefinitely even though the Docker Desktop
  backend process was running. Worked around with a local Postgres + `TEST_DB_URL` (see memory:
  "Backend ITs locally without Docker").

## Known Risks
- **WFH mark supersedes a scan (by design, per owner Ploy 2026-07-24).** Marking someone present
  overwrites their derived `attendance_daily` row for that date even if they also physically
  scanned — the manual mark is authoritative. The raw `attendance_punch` rows are never touched, so
  this is reversible (drop them from a later roster for that date, then a recalc rebuilds the scan
  day — covered by `unmarkingAfterSupersedeRestoresThePunchDerivedRowOnRecalc`). Consequence to be
  aware of: while marked WFH, that day's real check-in/out times are not shown in the day view.
- `AttendanceService.markPresent`'s scope re-check calls `listEmployeeOptions`, an extra query on
  every mark-present call. Negligible at this company's headcount; would need revisiting only at a
  much larger scale.
- **Pre-existing, NOT fixed here**: `frontend/src/styles.css`'s global `input, select, textarea {
  width: 100%; }` rule (line ~230) will silently stretch any native checkbox/radio `<input>` placed
  in a flex row with a `flex-1` sibling, collapsing that sibling's visible width to 0 — text stays
  in the DOM (screen readers and `get_page_text` see it fine) but is invisible on screen. This
  branch's own checkbox was fixed (`className="h-4 w-4 shrink-0"`), but the same latent issue
  exists in `TicketDetailPage.jsx:1726`, `DepositNoticePage.jsx:758`, and
  `PricingRequestDetailPage.jsx:559` — those three happen not to visibly break today only because
  none of their checkbox rows currently has a `flex-1` sibling squeezing against it. Worth a
  dedicated small cleanup pass (either fix the three call sites or add a `input[type=checkbox],
  input[type=radio] { width: auto; min-height: 0; }` override to the global rule) so a future
  layout change doesn't reproduce this bug elsewhere. Not done here to keep this branch's diff
  scoped to the mark-present feature.
- Mobile card view (`AttendanceDayCard`) for a WFH day was not separately screenshotted at mobile
  width — it reuses the existing `StatusCell`/`MidDayPunchChip` render path (already exercised by
  other statuses), but the exact mobile layout for `status === 'WFH'` was not visually confirmed.

## Things Not Finished
- No frontend component test was added for `AttendancePage`/`MarkPresentModal` — there was no
  pre-existing `AttendancePage.test.jsx` to extend, and the task's evidence requirement was
  specifically the backend authz IT. Manual/visual verification in a running mock-mode browser was
  performed instead (see "Test / Build Results" above) and surfaced one real bug, which was fixed.
- The checkbox-stretch CSS issue described in "Known Risks" is a pre-existing latent bug elsewhere
  in the codebase, deliberately left unfixed to keep this branch scoped — flagged for a follow-up
  task.

## Recommended Next Agent
Reviewer (Claude Opus or human) for PR review. All tests/builds are green; no further
implementation work is expected before review, other than addressing whatever the review raises.

## Exact Next Prompt
```
Review branch feat/attendance-mark-present-wfh in /Users/ploy_warit/Desktop/GL-R-ERP-wfh-present
against docs/agent-handoffs/112_feat-attendance-mark-present-wfh.md. Backend: 1218/1218 tests green
(full `clean verify`, local Postgres since Docker/Testcontainers was unresponsive), including the
new AttendanceMarkPresentIntegrationTest (8 real-DB authz cases) and a mutation-check that confirmed
the controller role gate matters. Frontend: 553/553 tests green, lint/build clean, and the mark-
present modal was manually verified in a mock-mode browser (a real checkbox-rendering bug was found
and fixed along the way — see "Known Risks" for a related pre-existing issue left unfixed
elsewhere). If the review is clean, this is ready to open as a PR; if not, address findings on this
branch and re-run the same verification commands (see "Commands Run").
```
