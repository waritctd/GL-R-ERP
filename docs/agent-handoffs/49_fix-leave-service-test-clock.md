# Agent Handoff

## Task
Two tests in `LeaveServiceTest` failed on a clean `main` as of 2026-07-16 (Thursday):

1. `submitAutoApprovesWhenQuotaAndAdvanceNoticeAreSatisfied` — expected `2.00` total days but got `1`.
2. `submissionAutoRejectsWhenQuotaIsInsufficient` — `ApiException: Leave request not found`.

**Root cause — date-dependent test data.** `validSubmit()` built its leave range relative to
"now": `nextWeekdayAfterNotice()` (today + 8, advanced past weekends) as the start date and
`startDate.plusDays(1)` as the end date, with no guard on the *end* date. On 2026-07-16 that
produced start = Friday 2026-07-24, end = **Saturday** 2026-07-25:

- `LeaveService.workingDaysBetween(Fri, Sat)` counts 1 working day, not 2 → failure 1.
- In the quota test, 5.00 used + 1 day requested = 6.00 ≤ the 6.00 quota, so the service
  **auto-approved** instead of auto-rejecting. The `create` stub only matched
  `LeaveStatus.AUTO_REJECTED`, so the unstubbed call returned `0L`, `findById(0)` was empty,
  and `requireRequest` threw "Leave request not found" → failure 2.

The tests fail whenever "today + 8 days (weekend-advanced)" lands on a Friday, i.e. every
Thursday, plus the Wednesday/Thursday pairs around weekend rollovers.

**Fix — deterministic dates via an injected fixed clock.** This mirrors the suite's existing
precedent: `DashboardService` has an `@Autowired` public constructor delegating to a
package-private constructor that accepts a `java.time.Clock` (production default
`Clock.system(BUSINESS_ZONE)`), and `DashboardServiceTest` injects `Clock.fixed(...)`.
`LeaveService` now follows the same pattern, and all leave dates in the test are fixed
`LocalDate.parse(...)` values relative to the fixed clock.

**No business logic changed.** The service diff is mechanical: three
`LocalDate.now(BUSINESS_ZONE)` call sites became `LocalDate.now(clock)` where `clock` defaults
to `Clock.system(BUSINESS_ZONE)` — identical behavior in production.

## Branch
`fix/leave-service-test-clock`

## Base Commit
`217d19f` (origin/main)

## Current Commit
Single commit on the branch (branch tip = the whole change).

## Agent / Model Used
Claude Opus 4.8 (implementation).

## Scope

### In Scope
- Clock injection in `LeaveService` (constructor plumbing only, DashboardService idiom).
- Fixed clock + fixed dates in `LeaveServiceTest`.

### Out of Scope (deliberately untouched)
- Any leave business rule (working-day counting, quota math, advance-notice policy, statuses).
- Other services/tests that use `LocalDate.now(...)` — only `LeaveService` was date-fragile in
  its unit test. (`LeaveServiceTest.user(...)` still calls `LocalDate.now()` for a
  `UserPrincipal` field; no assertion reads it, left as-is for minimal diff.)

## Files Changed
- `backend/src/main/java/th/co/glr/hr/leave/LeaveService.java`
  - Added a `Clock clock` field. The existing public constructor is now `@Autowired` and
    delegates to a new package-private constructor with a `Clock` parameter, defaulting to
    `Clock.system(BUSINESS_ZONE)` — same shape as `DashboardService`.
  - `LocalDate.now(BUSINESS_ZONE)` → `LocalDate.now(clock)` at the three call sites:
    `list(...)` default date window, `balances(...)` default year, and the advance-notice
    check in `autoRejectNote(...)`.
- `backend/src/test/java/th/co/glr/hr/leave/LeaveServiceTest.java`
  - `FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z")` (Wednesday 09:00 Asia/Bangkok);
    service constructed with `Clock.fixed(FIXED_NOW, BUSINESS_ZONE)`.
  - `validSubmit()` now uses fixed Monday 2026-07-13 → Tuesday 2026-07-14 (2 working days,
    12 days notice ≥ the 7-day default) — the same dates the approve tests already used.
  - Relative helpers `nextWeekdayAfterNotice()` / `nextWeekdayWithinNotice()` / `nextWeekday()`
    replaced with fixed `weekdayAfterNotice()` (2026-07-13) and `weekdayWithinNotice()`
    (2026-07-02, 1 day out < 7).
- `docs/agent-handoffs/49_fix-leave-service-test-clock.md` (new) — this file.

## Commands Run
- `cd backend && ./mvnw -B test -Dtest=LeaveServiceTest` — before fix: 1 failure + 1 error
  (reproduced exactly as reported); after fix: 9/9 pass.
- `cd backend && ./mvnw -B clean verify` — full suite (integration tests skipped without
  `TEST_DB_URL`, as usual on a local run).

## Tests / Build Results
- `LeaveServiceTest`: **9/9 pass** after the fix (was 7/9 on clean main on 2026-07-16).
- Full `./mvnw -B clean verify`: **BUILD SUCCESS — 325 tests, 0 failures, 0 errors** (surefire;
  integration tests skipped without `TEST_DB_URL`).
- Frontend: not touched, not run.

## Known Risks
- Very low. Production constructor path is unchanged (`Clock.system(BUSINESS_ZONE)` ==
  previous inline `LocalDate.now(BUSINESS_ZONE)` behavior). The package-private test
  constructor is not visible to callers outside the package.
- Integration tests were skipped locally (no `TEST_DB_URL`) — CI covers what it usually covers.

## Next Prompt
None required — this is a self-contained test-determinism fix. If more date-fragile tests
surface later, follow the same pattern: inject `Clock` via a package-private constructor and
pin `Clock.fixed(...)` in the test (see `DashboardService` / `LeaveService`).
