# Agent Handoff

## Task
Two fixes to the attendance screen (`/attendance`):
1. **Punch-order bug, fixed at the real backend.** The day-row drill-down labels the first scan `เข้า`
   (clock-in) and the last `ออก` (clock-out). The backend punch-list query returned rows newest-first,
   so the labels were swapped (17:30 shown as เข้า, 08:45 as ออก). A frontend-only stopgap sort existed;
   the fix is now in the backend so the API is correct for every consumer, and the stopgap is removed.
2. **New "source" column** on the attendance table showing **Showroom / Warehouse / WFH** — where WFH is
   a CEO/HR "marked present" day with no device punches.
3. **Validated the invariant** (per follow-up request) that the first punch is the clock-in and the last
   is the clock-out, at both layers.

## Branch
`fix/attendance-punch-order-and-source-column`

## Base Commit
`94a91ed` (origin/main, incl. PR #307 warehouse scanner + V89 warehouse device migration)

## Current Commit
Not committed (per repo convention — commit only when asked).

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Order the punch-list query ascending at the repository (bug fix; DTO shape unchanged).
- Add a display-only "source" column derived from the daily row's existing `site_code`.
- Enrich the mock daily builder so the column is verifiable under `VITE_USE_MOCKS=true`.
- Add tests validating punch ordering and the clock-in/clock-out invariant.

### Out of Scope
- No payroll/tax/commission/SSO math touched.
- No schema change (the daily DTO already carries `site_code` + `is_manual_override`; WFH days are
  written `site_code='WFH'` by the existing `upsertWfhPresent`).
- No authorization change (mark-present authz and punch-list scope untouched).

## Files Changed
- `backend/src/main/java/th/co/glr/hr/attendance/AttendanceRepository.java` — `findPunches`: wrap the
  query so it still takes the most-recent `:limit` punches (inner `ORDER BY ... DESC LIMIT`) but returns
  them **oldest-first** (`SELECT * FROM (...) recent ORDER BY recent.punch_time, recent.punch_id`). The
  recency cap stays meaningful for wide windows; every consumer now gets chronological order.
- `backend/src/test/java/th/co/glr/hr/attendance/AttendanceRepositoryIntegrationTest.java` — new
  `findPunchesReturnsAscendingChronologicalOrder` (real Postgres): inserts punches out of order, asserts
  the result is ascending and first=earliest / last=latest (compared by instant — TIMESTAMPTZ is UTC).
- `frontend/src/features/attendance/AttendancePage.jsx` — removed the stopgap client-side punch sort in
  `toggleExpanded` (backend is now authoritative); added `sourceColumn` (header `สถานที่`) to both
  `selfColumns` and `teamColumns`; added the source to the mobile `AttendanceDayCard`; exported
  `PunchDetail` + `punchRole` for testing; imported `attendanceSourceLabel`.
- `frontend/src/features/attendance/AttendancePage.test.jsx` — **new**: `punchRole` unit tests +
  `PunchDetail` render test proving earliest time → เข้า, latest → ออก, middle → ระหว่างวัน.
- `frontend/src/utils/format.js` — new `attendanceSourceLabel(day)` mapping `site_code`
  (SHOWROOM→Showroom, WAREHOUSE→Warehouse, WFH→WFH; null→null so the caller renders `-`).
- `frontend/src/utils/format.test.js` — unit tests for `attendanceSourceLabel`.
- `frontend/src/api/mockApi.js` — daily builder now alternates `site_code` Showroom/Warehouse by
  employee and emits a `wfh` shape (manual override, 0 punches, status WFH); drill-down punches match
  the row's site. (`markPresent` mock still intentionally unimplemented.)

## Commands Run
```bash
# Frontend
cd frontend && npm run lint && npm test && npm run build
# Backend (local Postgres throwaway DB)
psql -h localhost -U $USER -d postgres -c "CREATE DATABASE glr_it_scratch"
cd backend && TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_it_scratch" TEST_DB_USERNAME=$USER \
  TEST_DB_PASSWORD="" ./mvnw -B test -Dtest='AttendanceRepositoryIntegrationTest,AttendanceDailyCalculatorTest,AttendanceServiceTest,AttendanceControllerTest' -DforkCount=1
# Mutation check: flipped the outer ORDER BY back to DESC → only
# findPunchesReturnsAscendingChronologicalOrder went red (other 3 stayed green) → reverted.
# Full: ./mvnw -B clean verify -DforkCount=1  (see Test Results)
```

## Test / Build Results
- Frontend lint: **pass** (0 errors; 1 pre-existing warning in PayrollPage.jsx, unrelated).
- Frontend tests: **pass** — 559 tests incl. the new 10 (contract test green → mock stays in sync).
- Frontend build: **pass**.
- Backend attendance suite: **pass** — 63 tests (`AttendanceRepositoryIntegrationTest` 4,
  `AttendanceDailyCalculatorTest` 19, `AttendanceServiceTest` 22, `AttendanceControllerTest` 18).
  Integration tests **ran** against real Postgres (local `TEST_DB_URL`, not skipped).
- Mutation check: **confirmed** — DESC ordering fails exactly the new test, nothing else.
- Full `./mvnw -B clean verify` against the external throwaway `TEST_DB_URL`: **BUILD FAILURE, but NOT
  from this change** — it hit the known external-DB Flyway-reset race (many IT classes clean+migrate the
  same shared DB and collide). Errors are `resetSchema` FlywaySql ("Unable to drop" ×178, "no schema
  history table" ×42, PessimisticLocking ×32, BadSqlGrammar ×44), spread across commission / pricing /
  security / notification / special-money ITs — **none of which this branch touches**. Crucially,
  `AttendanceRepositoryIntegrationTest` still reports **4/0/0 inside the full run**, and all attendance
  ITs pass when run in isolation (63/63). The valid gate is CI's Testcontainers golden-template path
  (Docker unavailable locally). Re-run the ITs in isolation, or rely on CI, rather than an external-DB
  full verify. (This trap is recorded in agent memory: "external-DB full-verify races Flyway reset —
  run IT alone/Testcontainers".)
- Browser (mock, my own server on :5250 — the shared :5200 belongs to another worktree): source column
  shows Showroom / Warehouse / WFH and `-` for no-record; a WFH row shows no in/out; expanding a 5-punch
  row reads เข้า 08:47 → ระหว่างวัน 12:03/13:10/15:30 → ออก 17:32. Screenshots captured.

## Authz Evidence
- **No authorization change in this task.** The source column renders an existing DTO field
  (`site_code`); the ordering change affects row order only. Mark-present authz (`ceo`/`hr`) and the
  punch-list scope filter are untouched.

## Decisions Made
- Fixed ordering at the repository (single source of truth) and removed the frontend stopgap, rather
  than keeping two sorts.
- Used a subquery wrap (newest-N inner, ascending outer) instead of a plain `ORDER BY ASC` flip, so a
  future wide-range caller still gets the most-recent window, not the oldest-N.
- Source labels are English (Showroom/Warehouse/WFH) under a Thai header (`สถานที่`); shown in both the
  team/HR view and the employee self view (user decisions).

## Assumptions
- The daily row's `site_code` is the correct "source" signal (confirmed: `upsertWfhPresent` writes
  `WFH`, and the calculator carries the check-in punch's site for scanned days).

## Known Risks
- Device identity does not survive the daily roll-up, so the column is site-level (Showroom/Warehouse),
  not per-device — matches the requirement.
- The shared mock dev server on :5200 runs from another worktree; verify on a server started from this
  checkout (I used :5250).

## Things Not Finished
- Full `./mvnw -B clean verify` result to be recorded (was running in background at handoff).
- Not committed/pushed.

## Recommended Next Agent
Claude Opus review (re-verify the ordering fix + invariant tests), then merge on explicit say-so.

## Exact Next Prompt
```
Review branch fix/attendance-punch-order-and-source-column against origin/main. Confirm:
(1) AttendanceRepository.findPunches returns ascending order and the integration test + mutation check
    are sound; (2) the frontend source column + PunchDetail invariant tests are correct; (3) no authz or
    schema change slipped in. Then report the full `./mvnw -B clean verify` result and, if green, merge.
```
