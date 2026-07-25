# Sub-day leave + paper-form contact fields + PERSONAL quota fix

**Date:** 2026-07-25 · **Branch:** `feat/leave-subday-and-contact-fields` (off `origin/main`) · **NOT committed, NOT merged.**
Built via the Sonnet-implements / Opus-reviews loop. Plan: `~/.claude/plans/parallel-pondering-liskov.md`.

## What it does
- **Sub-day leave**: employees can file hourly/half-day leave via start-time + end-time pickers.
  `total_days = min(1.00, round(clockHours(start,end)/8, 2, HALF_UP))` — lunch NOT deducted (decided rule).
  Multi-day leave stays whole-day, unchanged.
- **Paper-form contact block**: `contact_house_no/subdistrict/district/province/phone` — autofilled from the
  employee's current address (editable/overridable). Position/dept/division shown read-only.
- **PERSONAL quota 3 → 7** (company rule §5.2) via forward migration V90 + mock seed.
- Approval workflow unchanged (still auto-approve/reject on submit).

## Files changed (leave feature only)
- `backend/.../db/migration/V90__leave_subday_and_contact.sql` (new): time cols + 3 CHECKs, contact cols, PERSONAL=7.
- `backend/.../leave/{SubmitLeaveRequest,LeaveController,LeaveService,LeaveDayMath,LeaveRepository,LeaveRequestDto,LeaveResponses,LeaveContactDefaultsDto}.java`
- `backend/.../test/.../leave/{LeaveDayMathTest,LeaveServiceTest,LeaveUnpaidDeductionIntegrationTest}.java`, `.../payroll/PayrollLeaveUnpaidDeductionSeamIntegrationTest.java`
- `frontend/src/features/leave/{LeavePage.jsx,LeavePage.test.jsx}`, `frontend/src/api/{hrApi,mockApi,routes,queryKeys}.js`

## Key design (de-risked)
`PayrollCalculator` is already fraction-safe (`dailyRate × unpaidLeaveDays`, scale-2). Only the month-attribution
leg truncated to int; `LeaveDayMath.unpaidWorkingDaysByMonth` is now 4-arg `(start,end,BigDecimal paid,BigDecimal
total)` → `Map<LocalDate,BigDecimal>`. Sub-day is single-day only, so a fraction lands wholly in one month.
Both callers updated (payroll seam + cancel/correction). Multi-day path numerically identical (regression-safe).

## Review (Opus) — APPROVE-WITH-NITS, all findings fixed
1. **Weekend sub-day guard** (fixed): `validateSubDayTimes` now rejects a timed leave whose date is a weekend
   (`countWorkingDays(d,d)==0` → 400), mirrored in mockApi. Prevented a pay deduction for a non-working day.
2. **Authz IT** (added): `LeaveUnpaidDeductionIntegrationTest#employeeCannotReadANonReportPeersContactDefaults`
   — wrong-way-round, asserts a plain employee gets 403 for a peer's contact-defaults. `contact-defaults` reuses
   the `/balances` predicate (self / direct-report / HR-CEO) verbatim.
3–4. Nits fixed: post-submit contact prefill re-applied in `onSuccess`; full-day cap returns scale-2 (`FULL_DAY`).

## Tests / build
- Frontend: `npm run lint` clean (1 pre-existing unrelated warning), `npm test` **585/585**, `npm run build` OK.
- Backend: `./mvnw -B test-compile` OK; `LeaveDayMathTest` + `LeaveServiceTest` **23/23**.
- ⚠️ **Integration tests NOT run** — no `TEST_DB_URL` and Docker unavailable in this env. The new authz IT and the
  fractional payroll seam IT **compile but were not executed**. Per CLAUDE.md the **authz aspect is UNVERIFIED**
  until run against real Postgres (CI or local). Run `./mvnw -B clean verify` with Postgres before merging.
- Live mock-mode UI smoke deferred (concurrent-session dev-server port collision); the RTL test exercises
  checkbox → time inputs → contact autofill/override → submit payload end-to-end.

## Known / carry-forward
- Working tree also holds UNRELATED concurrent-session changes (Button/DataTable/Sidebar/AttendancePage/
  EmployeeListPage/Procurement/Ticket pages, styles.css, DESIGN.md, PRODUCT.md, docs/ui-repair/*). **Stage ONLY
  the leave-feature files when committing.**
- Mock address schema is coarser than prod (no subdistrict) → `contactSubdistrict` autofills null in mock mode (accepted).
- The 30 prod leave rows from the July import already exist; this branch is app code only — deploy via PR →
  review → merge → Flyway (Render auto-deploy).

## Next prompt for the next agent
"On branch feat/leave-subday-and-contact-fields, run `./mvnw -B clean verify` against a real Postgres to execute
the leave/payroll integration tests (esp. employeeCannotReadANonReportPeersContactDefaults and the sub-day
fractional seam test), confirm green, then open a PR staging ONLY the leave-feature files."
