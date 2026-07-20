# Agent Handoff

## Task
Remove the overtime advance-notice constraint on CEO instruction. Employees may now file overtime
same-day or retroactively (ย้อนหลัง), provided the reason is clearly stated.

This is **Branch A of two**. Branch B (`feat/special-money-requests`) generalises the OT page into a
combined "คำขอ" page covering the company welfare policy (สวัสดิการ, per-diem / medical / uniform /
life-event aid) and wires approved amounts into payroll automatically. The full plan lives at
`~/.claude/plans/users-ploy-warit-downloads-2026-1-xlsx-staged-glade.md`.

## Branch
`feat/ot-remove-advance-notice`

Worked in a dedicated worktree, `.claude/worktrees/ot-retroactive`, because the primary worktree was
occupied by another live session on `feat/sales-factory-quote-costing` with uncommitted changes.

## Base Commit
`8ae42e3` (origin/main — Merge pull request #240 from waritctd/fix/employee-detail-view)

## Current Commit
`fed1bec` (plus a follow-up renumbering the migration V65 → V70). Not pushed.

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Deleting the advance-notice rule and the manager-only retroactive-submission rule.
- Replacing them with a retroactive window, a stricter reason requirement for backdated requests,
  and a payroll-period lock.
- Mirroring the new rules in the zod schema and in `mockApi.js`.

### Out of Scope
- Anything in Branch B: the welfare/special-money request module, the new payroll columns, the
  combined `/requests` page.
- Payroll arithmetic. Nothing in this branch changes how a number is computed.

## Files Changed
- `backend/.../config/AppProperties.java` — `Overtime.advanceNoticeDays` (default 3) replaced by
  `Overtime.retroactiveWindowDays` (default 60). **`Leave.advanceNoticeDays` is a separate property
  and was deliberately left alone.**
- `backend/src/main/resources/application.yml` — `app.overtime.advance-notice-days` replaced by
  `app.overtime.retroactive-window-days: ${APP_OVERTIME_RETROACTIVE_WINDOW_DAYS:60}`.
- `backend/.../overtime/OvertimeService.java` — deleted `validateAdvanceNotice`; replaced
  `validateRetroactiveSubmission` with `validateRetroactiveWindow` (window → 400, reason under 20
  chars → 400, processed payroll month → 409); added `requirePayrollMonthOpen`, also called from
  `managerApprove` and `ceoApprove`; added `BACKDATED_REASON_MIN_LENGTH = 20`.
- `backend/.../overtime/OvertimeRepository.java` — new `payrollMonthProcessed(LocalDate)`.
- `backend/.../overtime/OvertimeServiceTest.java` — replaced `submitRequiresThreeDayAdvanceNotice`
  with `submitAllowsSameDayOvertime`; renamed `directManagersCanSubmitOvertimeForReportsWithAdvanceNotice`
  → `...ForReports`; added six cases (self-filed retroactive, short reason, beyond window,
  processed month at submit, processed month at manager approval, processed month at CEO approval).
- `backend/.../overtime/OvertimeRepositoryIntegrationTest.java` — two cases for
  `payrollMonthProcessed` (absent period, and PROCESSED vs OPEN).
- `backend/.../overtime/OvertimeRetroactiveScopeIntegrationTest.java` — **new**, the authz evidence.
- `backend/.../overtime/RetroactiveOvertimeReachesPayrollIntegrationTest.java` — **new**, the
  payroll-integration evidence (9 cases, real DB, real `OvertimeService` + real `PayrollService`).
- `backend/src/main/resources/db/migration/V70__overtime_salary_basis_snapshot.sql` — **new**, adds
  and backfills `hr.overtime_request.salary_basis`. **See "Migration numbering" below — this file was
  renumbered from V64 to avoid a collision, and must be re-checked before merge.**
- `backend/.../payroll/PayrollRepository.java` — `findApprovedOvertimePayByEmployee` now prices from
  `ot.salary_basis`, falling back to the employee join only for pre-existing rows. **This is the one
  payroll-package change in the branch, and it is the explicitly requested fix — the arithmetic is
  unchanged, only the source of the salary input.**
- `frontend/src/features/overtime/OvertimePage.jsx` — `defaultForm` work date now today;
  `createOvertimeFormSchema` exported and given the two backdating rules; `min` on the date input;
  `isBackdated` hint via `FormField`'s existing `hint` prop (which wires `aria-describedby`).
- `frontend/src/api/mockApi.js` — new `validateOvertimeRetroactiveWindow`, called from
  `overtime.create`, with a `KNOWN GAP` comment (see Known Risks).
- `frontend/src/features/overtime/OvertimePage.test.jsx` — added `isoDaysFromToday` so cases stay
  relative to now; four new cases; the existing payload-shape case now uses a future date.

## Commands Run
```bash
cd backend  && ./mvnw -B -q compile
cd backend  && ./mvnw -B test -Dtest=OvertimeServiceTest
cd backend  && ./mvnw -B test -Dtest='OvertimeRetroactiveScopeIntegrationTest,OvertimeRepositoryIntegrationTest'
cd backend  && ./mvnw -B clean verify
cd frontend && npm ci && npm test -- --run && npm run lint && npm run build
```

## Test / Build Results
- **Backend: pass.** `./mvnw -B clean verify` → **594 tests, 0 failures, 0 errors, 0 skipped.**
  **Integration tests ran** — Docker was available so Testcontainers started a real Postgres
  (`OvertimeRetroactiveScopeIntegrationTest` 5 tests / 11.8 s,
  `RetroactiveOvertimeReachesPayrollIntegrationTest` 9 tests / 23.9 s,
  `OvertimeRepositoryIntegrationTest` 7 tests). Jacoco coverage check passed.
- **Frontend tests: pass.** 36 files, 219 tests, including `contract.test.js`.
- **Lint: pass** — 0 errors. 3 pre-existing `react-hooks/exhaustive-deps` warnings in
  `CommissionPage.jsx` and `PayrollPage.jsx`, neither touched by this branch.
- **Frontend build: pass** (191 ms).
- No `typecheck` was run — this project has no such script.

## Authz Evidence
**This branch relaxes authorization** and therefore ships real-DB evidence, per CLAUDE.md.

What was relaxed: `validateRetroactiveSubmission` previously returned **403** when an employee filed
their own backdated overtime ("Retroactive overtime must be submitted by the employee's manager").
That branch is deleted — self-filed retroactive overtime is now allowed, which is the CEO's request.

Verified against the real Java service: **`OvertimeRetroactiveScopeIntegrationTest`** (real Postgres
via Testcontainers, real `OvertimeService` + real `OvertimeRepository`; only `AuditService`,
`NotificationService` and `AttendanceDailyService` are stubbed, none of which is on the guard path).
Cases are written wrong-way-round, and each asserts the DB is unchanged rather than only the status
code:
1. `anEmployeeCanNowSelfFileRetroactiveOvertime` — the intended relaxation.
2. `anEmployeeCannotSelfFileRetroactiveOvertimeForSomeoneTheyDoNotManage` — 403, zero rows written.
3. `aManagerCannotFileRetroactiveOvertimeForAnotherDivision` — 403, zero rows written.
4. `hrCannotFileRetroactiveOvertimeOnBehalfOfAnArbitraryEmployee` — 403, zero rows written.
   (This is issue #199's shape; HR reads as more powerful than it is.)
5. `retroactiveOvertimeIsRefusedOnceItsPayrollMonthHasBeenProcessed` — 409 against a real
   `hr.payroll_period` row, zero rows written.

**Mutation check performed.** `resolveTargetEmployee`'s guard was disabled
(`if (false && targetEmployeeId != actorEmployeeId && !managesEmployee(...))`) and the suite re-run.
Exactly four tests went red and nothing else:
- `OvertimeRetroactiveScopeIntegrationTest.anEmployeeCannotSelfFileRetroactiveOvertimeForSomeoneTheyDoNotManage`
- `OvertimeRetroactiveScopeIntegrationTest.aManagerCannotFileRetroactiveOvertimeForAnotherDivision`
- `OvertimeRetroactiveScopeIntegrationTest.hrCannotFileRetroactiveOvertimeOnBehalfOfAnArbitraryEmployee`
- `OvertimeServiceTest.employeesCannotSubmitForAnotherEmployee` (pre-existing, guards the same rule)

The guard was then restored and the diff verified clean of the mutation.

## Payroll Integration Evidence

Overtime is worth nothing until payroll pays it, so the seam between the two services is verified
end to end, not assumed. **`RetroactiveOvertimeReachesPayrollIntegrationTest`** drives the real
chain — submit → manager approve → CEO approve → `PayrollService.preview` — against real Postgres
with the real `OvertimeService` *and* the real `PayrollService`/`PayrollRepository`/
`PayrollCalculator`. Fixture: salary ฿30,000, two hours of WORKDAY overtime, attendance punches at
08:00 and 20:30 so payable minutes are derived the real way (120), expected pay
`2 h x (30,000 / 30 / 8) x 1.5 = ฿375.00`.

1. `backdatedOvertimeIsPaidInThePayrollMonthOfTheWorkDate` — `payroll_month` is the work date's
   month and payroll returns ฿375.00.
2. `managerApprovedOvertimeIsNotYetPaid` — payable minutes are 120, but the money is ฿0 until the
   CEO approves. Proves the status filter lives in the SQL, not just in the service.
3. `rejectedBackdatedOvertimeIsNeverPaid` — ฿0.
4. `backdatedOvertimeRaisesGrossAndNetOnThePayrollLine` — `grossEarnings` rises by exactly ฿375.00
   and `netPay` rises, so the money reaches the payslip rather than only a report column.
5. `overtimeCannotBeFiledIntoAnAlreadyProcessedPayrollMonth` — 409.
6. `ceoApprovalIsRefusedOnceThePayrollMonthClosesUnderneathTheRequest` — payroll runs while the
   request waits on the CEO; approval is refused and the row stays `MANAGER_APPROVED`.
7. `overtimeIsRepricedByALaterSalaryChange` — **a characterization test of a real defect**, see below.

### Defect found and FIXED: overtime was repriced by a later salary change

**Owner's decision (2026-07-20): overtime is paid at the rate in force when the work was done.**
Implemented in this branch.

`PayrollRepository.findApprovedOvertimePayByEmployee` computes the money at read time:

```sql
SUM((ot.payable_minutes::numeric / 60) * ((COALESCE(e.current_salary, 0) / 30 / 8) * ot.pay_rate_multiplier))
```

It reads `hr.employee.current_salary` **as of the payroll run**. No hourly rate and no amount is
stored on the overtime request, and there is no FK from `payroll_line` back to `overtime_request`.
So a salary change between the work date and the payroll run silently re-prices work already done
and already approved. Test 7 demonstrates it: the same request is worth **฿375.00 before a raise and
฿750.00 after** — twice the money for identical work.

This **predated this branch**. It mattered less when advance notice forced overtime to be filed ahead
of the work date; allowing backdating widened the gap between "when the work happened" and "when it
is priced" (the accountant's workbook has both `วันที่ปรับล่าสุด` and `ปรับขึ้น ณ สิ้นเดือน`, so
mid/end-month raises do happen).

**The fix — freeze the input, not the arithmetic.** The formula is untouched:
`(payable_minutes / 60) * ((salary / 30 / 8) * pay_rate_multiplier)`. Only the *source* of `salary`
changed, from a live join on `hr.employee` to a value frozen on the request. That is what keeps
already-approved historical figures byte-identical.

- **`V70__overtime_salary_basis_snapshot.sql`** — adds `hr.overtime_request.salary_basis
  NUMERIC(12,2)`, with a non-negative check and a documenting `COMMENT ON COLUMN`. Backfills every
  existing row from `employee.current_salary`, which reproduces exactly what the old query would
  have computed, so no historical figure moves.
- **`OvertimeRepository.findSalaryBasisAsOf(employeeId, workDate)`** — resolves the salary in force
  on the work date, in three steps: (1) latest `hr.salary_history` row **effective on or before**
  the work date → its `new_amount`; (2) else the earliest row **effective after** the work date →
  its **`old_amount` (เงินเก่า)**, which is by definition the salary that preceded that change;
  (3) else `employee.current_salary`, then zero. Ties on `effective_date` break by `salary_id` so
  the result is deterministic.
- **`OvertimeService.managerApprove`** writes the snapshot alongside `payable_minutes` — the same
  moment the request's payable value is decided.
- **`PayrollRepository.findApprovedOvertimePayByEmployee`** reads
  `COALESCE(ot.salary_basis, e.current_salary, 0)`. The `hr.employee` join survives only as a safety
  net for rows approved before the column existed.

**Step (2) is the part that a naive implementation gets wrong**, and it was caught by a failing test
rather than by review. Looking only backwards through `salary_history` is not enough: for a backdated
request filed *after* a raise there is no history row on or before the work date, so the query falls
through to `current_salary` — which is already the new figure — and pays the new rate for old work.
`hr.salary_history` records both `old_amount` and `new_amount`, so the correct figure was already in
the table; the first implementation simply did not ask for it.

**Regression evidence.** `overtimeIsRepricedByALaterSalaryChange` was rewritten as
`overtimeIsNotRepricedByALaterSalaryChange` (approved at ฿375.00, salary doubled, still ฿375.00),
plus `overtimeWorkedBeforeARaiseIsPaidAtTheOldRate` and
`overtimeUsesTheSalaryHistoryRowEffectiveOnOrBeforeTheWorkDate`, and four repository-level cases in
`OvertimeRepositoryIntegrationTest`.

**Mutation-checked.** Reverting the payroll query to `COALESCE(e.current_salary, 0)` turned exactly
those three pricing tests red and left the other six in the class green; the query was then restored
and the full suite re-run clean.

Branch B stores `approved_amount` on special-money requests for the same reason and must not copy
the old overtime pattern.

## Decisions Made
1. **Self-filed retroactive OT is allowed.** Keeping it manager-only would just move the typing to
   the manager and recreate the hand-keying problem. Approval is already two-stage, so the control
   belongs at approval, not at submission.
2. **Two hard bounds on backdating.** (a) A **payroll lock** — `hr.payroll_period` with
   `status = 'PROCESSED'` for that month. (b) A **60-day config window**.
3. **The payroll lock is the important one, and it fixes a latent bug.**
   `PayrollRepository.findApprovedOvertimePayByEmployee` keys on `payroll_month` and
   `saveProcessedPeriod` is insert-once, so overtime landing in an already-processed month was
   approved and then **never paid, silently**. Before this branch that was unreachable only because
   advance notice blocked past dates; removing advance notice would have opened it. This adds **no
   payroll arithmetic** — it prevents a silent loss.
4. **The lock is re-checked at both approval stages**, because a request filed on the 24th can be
   CEO-approved on the 3rd, after payroll ran.
5. **Backdated requests need a ≥20-character reason** (service-level, not DTO-level — a DTO bump
   would also hit same-day requests). This is the "reasoning have to be stated clearly" requirement.
6. **The old property was deleted, not set to 0.** A lingering `APP_OVERTIME_ADVANCE_NOTICE_DAYS`
   would otherwise silently restore the constraint.

## Assumptions
- 60 days is a starting value, not a policy the CEO stated. It is one env var to change.
- 20 characters is likewise a chosen floor for "clearly stated".

## Migration numbering — read before merging

`origin/main` tops out at **V54** (V55 exists only on unmerged branches, and prod has V55 applied
from one of them). The higher numbers visible in the primary worktree belong to
`feat/sales-factory-quote-costing`, which is unmerged and has **committed V56 through V64**,
including `V64__pricing_step2_dispatch_and_attachments.sql`.

This branch's migration was originally `V64` — a head-on collision with that file — was moved to
`V65`, and **collided again** when the sales branch claimed `V65__factory_quote_response_idempotency`.
It is now **V70**, above every number claimed anywhere (that branch has since reached V69). Two
migrations sharing a version make Flyway refuse to start ("Found more than one migration with
version 64"), so it would have broken the first deploy after both branches merged. It has been
renumbered to **V70**. Flyway tolerates the V56-V64 gap on this branch; those fill in when the sales
branch merges.

**Before merging, re-check the highest migration version across `main`, every unmerged branch, AND
every working tree** — the sales branch claims numbers in uncommitted files, so `git ls-tree` alone
is not enough. It moved V64 → V69 in a single day. Prod is also ahead of main (V55 applied from an
unmerged branch), so pick against APPLIED history too, not just repo files. Do not read the number off whichever worktree happens
to be open — that is exactly the mistake that produced the collision.

## Known Risks
1. **`APP_OVERTIME_ADVANCE_NOTICE_DAYS` may still be set in a deployment environment.** It is no
   longer read, so it is inert — but it should be removed from Render/Vercel config to avoid
   confusing the next reader. `render.yaml` and the compose files do not set it; the hosted
   dashboard was not checked.
2. **`mockApi.js` is still more permissive than production on one rule.** The mock has no
   `payrollPeriods` collection, so `requirePayrollMonthOpen` is not mirrored. A successful submit
   under `VITE_USE_MOCKS=true` is therefore *not* proof the backend would accept it. This is
   documented in a `KNOWN GAP` comment at the helper. The window and reason rules *are* mirrored,
   so the mock is otherwise stricter than before, never laxer.
3. **Removing advance notice removes the only thing that forced OT to be *planned*.** A manager can
   no longer say "you didn't ask first". If that was a real management control, it should return as
   a report ("% of OT filed retroactively, by manager"), not a validation.
4. Payable minutes are still derived from attendance punches, so retroactive requests will routinely
   be approved against attendance data that already exists — arguably more accurate, but it does mean
   a backdated request can be approved for minutes the employee did not plan.
5. **`hr.salary_history` is ETL-loaded and may be sparse or have null `old_amount`.** The resolver
   degrades to `employee.current_salary` in that case, which is the old (wrong-for-backdated)
   behaviour. Worth a one-off `SELECT` against production to see how many active employees have
   usable history rows before relying on step (2) for real money.
6. **`salary_basis` is frozen at manager approval, not at submit.** If a raise lands between the work
   date and manager approval, step (2) of the resolver still finds the correct historical figure —
   but only if the raise is recorded in `salary_history`. A raise applied by editing
   `employee.current_salary` without a history row is invisible to it.

## Things Not Finished
- Not committed and not pushed.
- No PR opened.
- Browser verification was not run; the change is covered by unit + integration tests, and the
  visible surface is a default date, an input `min`, and a conditional hint.

## Recommended Next Agent
Claude Opus review — verify the authz evidence and the mutation-check claim independently, then
merge on the user's say-so. After merge, Branch B starts from `main`.

## Exact Next Prompt
```
Review branch feat/ot-remove-advance-notice in the worktree .claude/worktrees/ot-retroactive
(based on origin/main 8ae42e3). Read docs/agent-handoffs/89_feat-ot-remove-advance-notice.md first.

Verify independently, do not take the handoff's word for it:
1. Re-run `cd backend && ./mvnw -B clean verify` and confirm 581 tests pass with integration tests
   actually running (not skipped) — check that Testcontainers started.
2. Re-do the mutation check: disable the managesEmployee call in OvertimeService.resolveTargetEmployee,
   confirm exactly the four named tests go red and nothing else, then revert to an empty diff.
3. Confirm no payroll arithmetic changed: `git diff origin/main -- backend/src/main/java/th/co/glr/hr/payroll/`
   must be empty. The payroll integration is proved by a new test, not by editing payroll.
6. Read the "Payroll Integration Evidence" section and independently confirm
   `overtimeIsRepricedByALaterSalaryChange` — it documents a real pre-existing defect
   (same overtime worth ฿375 before a raise, ฿750 after). Decide whether it blocks the merge or
   becomes its own branch; do not fix it inside this one.
4. Confirm Leave.advanceNoticeDays was not touched.
5. Check whether APP_OVERTIME_ADVANCE_NOTICE_DAYS is set anywhere in deployment config.

Do not implement. Report findings; anything larger than a typo goes back to an implementation branch.
```
