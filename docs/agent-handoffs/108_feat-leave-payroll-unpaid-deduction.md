# Agent Handoff

## Task
Build the leave -> payroll auto-flow with Thai-labour-law unpaid-day deductions: the 3 existing
leave types' quotas are the statutory PAID caps in working days (SICK<=30, PERSONAL>=3,
VACATION>=6); days beyond quota are unpaid (deducted `base/30` per unpaid WORKING day, Mon-Fri, no
holiday calendar, whole days only); add a `LEAVE_WITHOUT_PAY` type (always unpaid); wire the
leave-derived unpaid-day figure into the existing payroll `suggested-inputs` pre-fill path
(additive only); and reverse an over-deduction when a leave is cancelled after its payroll month
has already closed.

**Two phases, same branch, same day:**
1. **Phase 1** (commits `9a3787051f30c6d01e56c0c1a809f257e639b1ac`, `565da6e`): the deduction side
   (gate redesign, per-month attribution, `suggestedInputs` overlay) plus a v1 cancel-after-close
   design that only RECORDED a correction row and surfaced it to HR as a "please net this in by
   hand" hint -- never auto-applied.
2. **Phase 2 / AUTO-REFUND** (owner decision, same day, mid-task instruction from the coordinator):
   phase 1's record-and-surface-only design was judged not enough for a money-owed situation --
   this phase makes `PayrollService#preview`/`#process` **auto-apply** the correction as a real
   pre-tax credit and auto-resolve it, with idempotent re-processing. This is the section most
   worth a reviewer's attention: it is the one place in this task where `preview()`/`process()`
   math changes, which is explicitly authorised here (not a side effect).

## Branch
`feat/leave-payroll-unpaid-deduction`

## Base Commit
`20a568385b1a293d1447a480ecd9e0a33e3cb836` (origin/main tip, PR #281 merged)

## Current Commit
Phase 1: `9a37870` (feat) + `565da6e` (docs). Phase 2/AUTO-REFUND: committed on the same branch,
see the commit this handoff update ships with (`git log -1` on `feat/leave-payroll-unpaid-deduction`
at handoff time). NOT pushed/merged (per the task brief) -- next step is Opus review.

## Agent / Model Used
Claude Sonnet 5 (implementation), both phases.

## Scope

### In Scope
- `leave/` package: schema (`paid_days`/`unpaid_days` split, `LEAVE_WITHOUT_PAY` type, cancel
  correction table), gate redesign (`LeaveService#submit`), per-month attribution query,
  cancel-after-close reversal bookkeeping (write-only -- see Phase 2 for who resolves it).
- `payroll/` package: Phase 1 was additive-only (`suggestedInputs`/`PayrollCarryForwardDtos`,
  `PayrollCalculator`/`preview()`/`process()` untouched). **Phase 2 changes `preview()`/`process()`
  math** -- explicitly authorised by the coordinator's instruction ("This DOES change
  preview()/process() math -- that's authorized (it's the point)").
- Frontend: `PayrollPage.jsx` pre-fill of `unpaidLeaveDays` from the suggestion; Phase 2 adds a
  "refund already applied" hint (replacing the now-stale "please adjust manually" one) and a
  breakdown-panel line for the refund amount.
- Backend migrations `V85` (Phase 1) and `V86` (Phase 2).

### Out of Scope (explicitly not built, flagged in code comments)
- Maternity/paternity/military/sterilization paid leave types (Dec-2025 amendment).
- Holiday calendar (weekends are the only non-working days recognized in v1).
- Half-day leave.
- An explicit "corrections" admin/list view (the refund is visible per-employee on the payroll
  detail panel, but there is no page listing all outstanding/resolved corrections).

## Files Changed

### Backend — new (Phase 1)
- `backend/src/main/resources/db/migration/V85__leave_payroll_unpaid_deduction.sql`
- `backend/src/main/java/th/co/glr/hr/leave/LeaveDayMath.java`
- `backend/src/test/java/th/co/glr/hr/leave/LeaveDayMathTest.java`
- `backend/src/test/java/th/co/glr/hr/leave/LeaveUnpaidDeductionIntegrationTest.java`
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollLeaveUnpaidDeductionSeamIntegrationTest.java`

### Backend — new (Phase 2 / AUTO-REFUND)
- `backend/src/main/resources/db/migration/V86__leave_payroll_correction_auto_refund.sql` — adds
  `hr.payroll_line.leave_refund_days` (NUMERIC(6,2)) / `leave_deduction_refund` (NUMERIC(12,2)),
  both `NOT NULL DEFAULT 0`. Includes the tax-timing nuance caveat (see "Known Risks" below) in the
  migration comment itself, not just this handoff.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollLeaveCorrectionAutoRefundIntegrationTest.java`
  — real-DB, full-cycle test: beyond-quota leave deducts in month M -> cancel after M is PROCESSED
  -> correction recorded -> month M+1 PREVIEW shows the refund (nothing mutated yet) -> month M+1
  PROCESS applies the refund and resolves the correction -> RE-processing M+1 does not double-
  refund (same period_id, same figures, still exactly 1 correction row) -> month M+2 sees nothing
  outstanding. Plus a second test: a correction with zero other payroll activity for that employee
  still refunds (inserted directly via SQL fixture, bypassing the leave workflow entirely, to
  isolate the payroll-side logic).
- 4 new unit tests appended to `PayrollCalculatorTest.java` (see below).

### Backend — modified (Phase 1)
- `LeaveRepository.java`, `LeaveRequestDto.java`, `LeaveService.java` (submit/cancel),
  `PayrollCarryForwardDtos.java`, `PayrollRepository.java` (`findCarryForwardSuggestions`),
  `PayrollService.java` (`suggestedInputs`), `LeaveServiceTest.java`.
- Mechanical constructor-arity fixes only (`PayrollService` gained a 6th constructor param):
  `PayrollServiceTest.java`, `RetroactiveOvertimeReachesPayrollIntegrationTest.java` (both
  untouched again in Phase 2 -- no further arity change was needed),
  `PayrollAllowanceDirectorNonTaxableIntegrationTest.java`,
  `PayrollReprocessAndAttendanceDataFlowIntegrationTest.java`,
  `PayrollPersistedPayslipIntegrationTest.java`, `PayrollYtdAndSsoIntegrationTest.java`,
  `PayrollCommissionWeightedBaseIntegrationTest.java`.

### Backend — modified (Phase 2 / AUTO-REFUND)
- `LeaveRepository.java` — two new methods: `findRefundableUnpaidDaysByEmployee(Long
  givingBackPeriodId)` (the read `preview()`/`process()` use) and `resolvePendingCorrections(long
  periodId)` (the write `process()` uses). `findPendingPayrollCorrectionsByEmployee` (used by
  `suggestedInputs`, per the task brief's "keep the deduction-suggestion path as-is") is untouched.
- `LeaveService.java` — updated `recordPayrollCorrectionIfNeeded`'s doc comment only (it was
  claiming "never auto-resolved," no longer true now that `PayrollService` resolves it) -- no
  behavioural change to this method.
- `PayrollCalculationInput.java` — new trailing field `leaveRefundDays`, appended after the C3/C4
  fields (same pattern as before: a new 16-arg legacy constructor added so every existing 16-arg
  call site, including `PayrollCalculatorTest`, keeps compiling; the existing 12-arg legacy
  constructor is untouched and still chains through).
- `PayrollCalculation.java` — two new trailing fields, `leaveRefundDays`/`leaveDeductionRefund`.
  Only one construction site exists (`PayrollCalculator#calculate`), so no compat shim was needed.
- `PayrollCalculator.java` — the actual math (see "Decisions Made" #1 for the full mechanism):
  `leaveDeductionRefund = dailyRate x leaveRefundDays`, added into `grossTaxableIncome` and
  `ssoWageBase`'s base (opposite sign from `unpaidLeaveDeduction`), subtracted from
  `totalDeductions`.
- `PayrollLineDto.java` — two new trailing fields. A new 37-arg legacy constructor was added so
  `PayrollServiceTest`/`PayrollRepositoryIntegrationTest` (both off-limits) and 4 other test files
  that construct this record positionally keep compiling unchanged -- verified by `test-compile`
  passing with zero edits to any of them.
- `PayrollRepository.java` — `insertLine`/`findLines`/`mapLine` read and write the two new
  `payroll_line` columns.
- `PayrollService.java`:
  - class-level `leaveRepository` field doc updated (no longer "never touches preview()/process()").
  - `preview(LocalDate, List, actor)` (the shared private engine both the public `preview()` and
    `process()` call): looks up `existingPeriodId` via `payrollRepository.findPeriodByMonth`, then
    `leaveRefundDaysByEmployee = leaveRepository.findRefundableUnpaidDaysByEmployee(existingPeriodId)`,
    passed into `calculateLine` per employee.
  - `calculateLine`: new `leaveRefundDays` parameter, threaded into `PayrollCalculationInput` and
    the resulting `PayrollLineDto`.
  - `process()`: after `saveProcessedPeriod`, calls `leaveRepository.resolvePendingCorrections(periodId)`
    in the same `@Transactional` method.

### Frontend (Phase 2 / AUTO-REFUND; Phase 1's frontend changes are unchanged from before)
- `frontend/src/features/payroll/PayrollPage.jsx`:
  - the `pendingUnpaidLeaveCorrectionDays > 0` hint (Phase 1) said "ระบบยังไม่หักเครดิตนี้ให้อัตโนมัติ"
    ("the system doesn't deduct this credit automatically yet") -- **now false and actively
    misleading** (HR could double-enter the credit into `unpaidLeaveDays` by hand, causing a real
    double deduction). Replaced: when the CALCULATED line already includes a refund
    (`selectedLine.leaveRefundDays > 0`), show an "already auto-applied" hint instead; the
    pending-suggestion hint (shown when there's no refund on this specific line yet) now says the
    system will apply it automatically on the next run, not "please adjust manually."
  - `payroll-breakdown` panel gained a conditional line showing the refund days + amount.
- `frontend/src/features/payroll/PayrollPage.test.jsx` — 2 new tests: the auto-applied hint
  appears and the stale manual-entry text is gone when a line has a refund; no refund hint at all
  when there's neither a refund nor a pending correction.

### Docs
- `docs/agent-handoffs/108_feat-leave-payroll-unpaid-deduction.md` (this file, both phases).

## Commands Run
```bash
cd backend && ./mvnw -q -o compile && ./mvnw -q -o test-compile
# Targeted runs during development (broad but not exhaustive) against a throwaway DB, then:
TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_test_leavepayroll_refund_<ts>" TEST_DB_USERNAME="$USER" TEST_DB_PASSWORD="" \
  ./mvnw -B -o clean verify -Dtest.fork.count=1   # run EXACTLY once as the final Phase 2 verify
cd frontend && npm run lint && npm test -- --run && npm run build
```

## Test / Build Results
- **Backend compile + test-compile**: PASS (both phases).
- **Backend `clean verify` (real Postgres, throwaway DB, run exactly once for Phase 2 as
  instructed)**: **BUILD SUCCESS.** `Tests run: 1159, Failures: 0, Errors: 0, Skipped: 2` (7m08s).
  Jacoco coverage checks passed. The 2 skips are `IntegrationResetInvariantTest`'s
  Testcontainers-only checks (N/A on the external-`TEST_DB_URL` path) -- expected, unrelated. 1159
  = Phase 1's confirmed-green 1153 + 6 new (4 `PayrollCalculatorTest` unit cases + 2
  `PayrollLeaveCorrectionAutoRefundIntegrationTest` cases).
  - Before that single verify, targeted `-Dtest=...` runs (not counted as "the" verify) covered:
    all of `PayrollServiceTest`, `PayrollRepositoryIntegrationTest`,
    `RetroactiveOvertimeReachesPayrollIntegrationTest` (the 3 off-limits files) plus every other
    payroll/leave test class, all green with zero edits to the off-limits files -- confirming the
    legacy-constructor compat shims work before spending the one full-verify run.
  - Hand/derived expected values for the 4 new `PayrollCalculatorTest` cases were cross-checked
    with a faithful Python port of the calculator's exact arithmetic (Decimal + ROUND_HALF_UP)
    before writing the assertions, then confirmed to match the real Java output byte-for-byte on
    the first test run -- no guessing games with BigDecimal rounding.
- **`@SpringBootTest`-context race note**: not observed.
- **Frontend**: `npm run lint` — 0 errors, 1 pre-existing unrelated warning
  (`react-hooks/exhaustive-deps` on `PayrollPage`'s `load()` effect). `npm test -- --run` —
  441/441 pass. `npm run build` — succeeds.
- **Environment note (Phase 1, resolved, informational only)**: the machine's disk filled up
  completely (ENOSPC) mid-session-1 from accumulated build artifacts across other worktrees; fixed
  by deleting regenerable `target`/`node_modules` dirs. Not an issue during Phase 2 (~20GB free
  throughout).

## Authz Evidence
No authorization change in either phase. `submit()`/`cancel()` keep their existing role/ownership
checks unchanged. `preview()`/`process()` keep their existing `PAYROLL_VIEW_ROLES`/`PAYROLL_EDIT_ROLES`
gates unchanged -- the refund is computed and applied inside the same authorized call, not a new
endpoint or a new permission surface.

## Decisions Made

### Phase 1
1. **Paid-quota consumption order**: a leave request's `paid_days` are always the request's
   earliest chronological working days; the remainder is unpaid.
2. **`chk_leave_paid_unpaid_sum` scope**: only enforced for `APPROVED`/`CANCELLED` rows.
3. **Suggestions merge is additive, keyed on employee.**
4. **SICK-attachment / advance-notice rejects are quota-independent.**

### Phase 2 / AUTO-REFUND
5. **The refund mechanism, precisely**: `refund_days = SUM(unpaid_days_to_refund)` over an
   employee's pending (`resolved_at IS NULL`) `hr.leave_payroll_correction` rows (plus, on a
   re-process, rows already resolved by THIS SAME period -- see idempotency below).
   `leaveDeductionRefund = dailyRate x refund_days` in `PayrollCalculator`, flowing through the
   **exact same pre-tax path** `unpaidLeaveDeduction` already used, with the opposite sign:
   - `grossTaxableIncome = grossEarnings - unpaidLeaveDeduction + leaveDeductionRefund - (other
     pre-tax deductions)`
   - `ssoWageBase = ssoWageBase(baseSalary - unpaidLeaveDeduction + leaveDeductionRefund)` (the
     existing `[1650, 17500]` clamp already protects against a refund pushing SSO past the
     statutory ceiling -- verified by a dedicated unit test at a salary near the ceiling)
   - `totalDeductions = unpaidLeaveDeduction - leaveDeductionRefund + (everything else, unchanged)`

   This is deliberately a NEW, separate field (`leaveRefundDays`/`leaveDeductionRefund`), not
   netted into `unpaidLeaveDays` before it reaches the calculator: `unpaidLeaveDays` is HR-typed
   and `@PositiveOrZero`, and a month can legitimately have zero of its own new unpaid leave while
   still owing a refund from an earlier month's cancellation. The two combine additively inside
   `PayrollCalculator`, independent events with independent signs.
6. **Auto-apply on PROCESS, surface on PREVIEW, never mutate on PREVIEW**: both the public
   `preview()` endpoint and `process()`'s internal pre-save calculation share the exact same
   `preview(LocalDate, List, actor)` code path, so a plain "Preview" click already shows the refund
   before HR commits to anything. Only `process()` calls `resolvePendingCorrections` (after
   `saveProcessedPeriod`, in the same `@Transactional` method) -- `preview()` alone never writes to
   `hr.leave_payroll_correction`.
7. **Idempotency, exactly how it works**: `findRefundableUnpaidDaysByEmployee(givingBackPeriodId)`
   and `resolvePendingCorrections(periodId)` use the DELIBERATELY IDENTICAL WHERE-shape:
   `resolved_at IS NULL OR resolved_payroll_period_id = :periodId`. Both run in the same DB
   transaction as each other (`process()` is `@Transactional`; `saveProcessedPeriod`'s
   find-or-create-by-`payroll_month` guarantees the `periodId` used by the read (via
   `findPeriodByMonth` before the calculation) and the write (the ID `saveProcessedPeriod` just
   returned) are the SAME id for a given month). Consequences:
   - **First-time processing a month**: `existingPeriodId` is null, so the read collapses to
     `resolved_at IS NULL` (plain pending), and the write resolves exactly those same rows against
     the newly-created period id. No double-refund, nothing lost.
   - **Re-processing the SAME month** (`saveProcessedPeriod` reuses the existing `payroll_month`
     row): the read now ALSO includes rows already `resolved_payroll_period_id = periodId` from
     last time -- giving them back into the pool for this recomputation-from-scratch -- while rows
     resolved by any OTHER (different) period stay excluded by both the read and the write. The
     resolve-write on a row already stamped with this same `periodId` is a no-op refresh of
     `resolved_at` (same period, same effective state) -- not a double-refund, because the read
     would have summed that row in either case.
   - **Verified empirically**, not just argued: `PayrollLeaveCorrectionAutoRefundIntegrationTest`
     re-processes month M+1 twice and asserts identical `leaveRefundDays`/`leaveDeductionRefund`/
     `netPay`, exactly 1 correction row still existing (not duplicated), and a third month (M+2)
     seeing nothing outstanding.
8. **Migration split (V85 vs V86)**: V85 (Phase 1's schema, already local-only/uncommitted-to-main)
   could have absorbed V86's 2 columns, but they were kept separate -- V85 was already committed to
   this branch by the time Phase 2 started, and "forward-only Vnnn" is the safer default even
   within a single still-unmerged branch once something has already landed in a commit.

## Assumptions
- "Whole days only" -- `paid_days` truncated defensively wherever read back (unchanged from Phase 1).
- No holiday calendar (unchanged from Phase 1).
- **Refund timing**: the refund is computed and applied against the SAME employee's CURRENT payroll
  run's own base salary / month value -- if an employee's salary changed between the original
  deduction month and the refund month, the refund amount is `(refund month's) dailyRate x
  unpaid_days_to_refund`, not the original deduction's exact reversed amount. This mirrors how
  `unpaid_days_to_refund` is stored as DAYS (not a frozen money amount) in `hr.leave_payroll_correction`
  -- a deliberate v1 choice (storing days rather than a frozen Baht figure), not an oversight, but
  worth flagging: a salary change between the two months means the refund is not bit-for-bit the
  original deduction, even though the DAY COUNT is exactly reversed.

## Known Risks
- **TAX-TIMING NUANCE (flagged for HR/legal -- see V86 migration comment, same wording):** the
  refund is taxed/SSO'd as THIS payroll month's income, at this month's marginal rate and against
  this month's SSO ceiling headroom -- NOT a retroactive correction to the ORIGINAL month's tax
  return. If the original over-deduction and the refund land in different tax months (which they
  always do here, by construction -- earliest possible refund is the month AFTER the cancellation),
  the employee's total annual tax across the two months is not guaranteed to exactly equal what it
  would have been had the deduction never happened (e.g. differing marginal brackets between the
  two months, or if the original month's withholding was already reported/filed). This is standard
  "correct the current period" treatment, the same convention Thai monthly PND1 withholding
  generally uses for in-year corrections -- not a bug -- but it is NOT equivalent to reopening and
  refiling the original month. **Confirm this convention is acceptable before this drives a real
  payroll run** (same HR/legal sign-off gate Phase 1 already flagged for the deduction rule itself).
- **Salary-change-between-months edge case** (see "Assumptions" above): not wrong, but worth a
  reviewer's eyes -- the refund uses the CURRENT month's daily rate, not the original month's.
- **No admin view of the correction ledger.** HR can see a specific employee's refund on that
  employee's payroll line once it applies, and `suggestedInputs` still surfaces an early "something
  is pending" number, but there's no page listing every open/resolved correction across all
  employees. Not built here; flagged as a natural next step if this needs auditing at scale.
- **Migrations V85/V86 are not yet deployed anywhere** (both local-only to this branch/session) --
  safe to have edited V85 in place during Phase 1's own debugging; do NOT do that once merged.
- Same rebase-conflict risk as Phase 1 noted, now also covering `PayrollCalculationInput.java`/
  `PayrollCalculation.java`/`PayrollLineDto.java`/`PayrollCalculator.java`'s legacy constructors and
  math against `feat/payroll-statutory-export-files` (touches the same payroll files) -- should
  still be mechanical (both branches append trailing fields with back-compat constructors) rather
  than semantic, but verify after any rebase.

## Things Not Finished
- Committed on the branch, not pushed/merged -- next step is Opus review.
- **HR/legal sign-off** on both the deduction rule (Phase 1) AND the refund's tax-timing convention
  (Phase 2, see "Known Risks") -- real open items, not code tasks.
- No admin/list view of the correction ledger (see "Known Risks").

## Recommended Next Agent
Claude Opus review (per the Sonnet-implements/Opus-reviews loop this repo uses).

## Exact Next Prompt
```
Review branch feat/leave-payroll-unpaid-deduction (worktree at .claude/worktrees/leave-payroll) --
BOTH phases (Phase 1: leave-side deduction + suggestedInputs overlay; Phase 2/AUTO-REFUND: the
cancel-after-close correction is now auto-applied by PayrollService#preview/#process, changing
their math, per an explicit owner decision mid-task) -- against
docs/agent-handoffs/108_feat-leave-payroll-unpaid-deduction.md. Backend `clean verify` (1159 tests,
0F/0E, 2 expected skips) and frontend lint/test/build are confirmed green as of the current commit;
re-run only if you've made changes. Specifically verify:
(1) the AUTO-REFUND mechanism in PayrollCalculator (leaveRefundDays/leaveDeductionRefund flowing
through the same pre-tax path as unpaidLeaveDeduction with the opposite sign) is mathematically
sound and the SSO-ceiling-clamp / progressive-tax interaction is correctly reasoned about;
(2) the idempotency argument (findRefundableUnpaidDaysByEmployee / resolvePendingCorrections
sharing the exact same WHERE-shape, same transaction) actually holds -- consider adversarial cases
beyond what PayrollLeaveCorrectionAutoRefundIntegrationTest covers (e.g. concurrent processing of
two different months for the same employee, though note this codebase does not use SERIALIZABLE
isolation anywhere else either);
(3) the tax-timing nuance and salary-change-between-months assumption are acceptable v1 tradeoffs
to flag rather than block on, or should have been handled differently;
(4) the chk_leave_paid_unpaid_sum scoping fix (Phase 1) is still correct.
Then decide: merge-ready, or send back with specific findings.
```
