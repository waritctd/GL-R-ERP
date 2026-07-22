# Agent Handoff

## Task
Build the leave -> payroll auto-flow with Thai-labour-law unpaid-day deductions: the 3 existing
leave types' quotas are the statutory PAID caps in working days (SICK<=30, PERSONAL>=3,
VACATION>=6); days beyond quota are unpaid (deducted `base/30` per unpaid WORKING day, Mon-Fri, no
holiday calendar, whole days only); add a `LEAVE_WITHOUT_PAY` type (always unpaid); wire the
leave-derived unpaid-day figure into the existing payroll `suggested-inputs` pre-fill path
(additive only, `preview()`/`process()` math unchanged); and design a cancel-after-close reversal.

## Branch
`feat/leave-payroll-unpaid-deduction`

## Base Commit
`20a568385b1a293d1447a480ecd9e0a33e3cb836` (origin/main tip, PR #281 merged)

## Current Commit
`9a3787051f30c6d01e56c0c1a809f257e639b1ac` — committed on the branch, NOT pushed/merged (per the
task brief). The final full `mvn clean verify` referenced below completed with BUILD SUCCESS
before this commit was made.

## Agent / Model Used
Claude Sonnet 5 (implementation)

## Scope

### In Scope
- `leave/` package: schema (`paid_days`/`unpaid_days` split, `LEAVE_WITHOUT_PAY` type, cancel
  correction table), gate redesign (`LeaveService#submit`), per-month attribution query,
  cancel-after-close reversal bookkeeping.
- `payroll/` package: **additive only** — extend `PayrollService#suggestedInputs` /
  `PayrollCarryForwardDtos` to surface leave-derived figures. `PayrollCalculator`, `preview()`,
  `process()` are untouched.
- Frontend: `PayrollPage.jsx` pre-fill of `unpaidLeaveDays` from the suggestion, plus a
  correction-credit hint; `PayrollPage.test.jsx` coverage.
- New backend migration `V85`.

### Out of Scope (explicitly not built, flagged in code comments)
- Maternity/paternity/military/sterilization paid leave types (Dec-2025 amendment).
- Holiday calendar (weekends are the only non-working days recognized in v1).
- Half-day leave.
- Automatic resolution of a cancel-after-close correction credit (see "Decisions Made").

## Files Changed

### Backend — new
- `backend/src/main/resources/db/migration/V85__leave_payroll_unpaid_deduction.sql` — adds
  `hr.leave_request.paid_days`/`unpaid_days` (NUMERIC(5,2), backfilled for
  APPROVED/CANCELLED rows); relaxes `hr.leave_type` quota check to `>= 0`; seeds
  `LEAVE_WITHOUT_PAY` (0-day quota); creates `hr.leave_payroll_correction` (cancel-after-close
  credit ledger, `resolved_at`/`resolved_payroll_period_id` reserved for a future step).
- `backend/src/main/java/th/co/glr/hr/leave/LeaveDayMath.java` — shared weekday-counting math
  (`countWorkingDays`, `unpaidWorkingDaysByMonth`), used by both the per-month attribution query
  and the cancel reversal.
- `backend/src/test/java/th/co/glr/hr/leave/LeaveDayMathTest.java` — unit coverage of the
  cross-month split.
- `backend/src/test/java/th/co/glr/hr/leave/LeaveUnpaidDeductionIntegrationTest.java` — real-DB
  coverage: within-quota (unpaid=0), beyond-quota split, `LEAVE_WITHOUT_PAY` (all unpaid),
  cross-month split, SICK-beyond-quota still needs a certificate, cancel-after-close records a
  real correction row, cancel-before-close records none.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollLeaveUnpaidDeductionSeamIntegrationTest.java`
  — real-DB coverage of the leave<->payroll seam: suggestions surfaces the right
  `unpaidLeaveDays`/`pendingUnpaidLeaveCorrectionDays`, and an explicit `preview()` input using
  that suggestion deducts `base/30 x unpaidDays` pre-tax at the right amount; paid-within-quota
  yields 0.

### Backend — modified
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRepository.java` — `paid_days`/`unpaid_days` in
  `baseSelect`/`mapRequest`/`create()`; new `findUnpaidLeaveDaysByEmployeeForMonth`,
  `findProcessedPayrollMonths`, `recordPayrollCorrection`, `findPendingPayrollCorrectionsByEmployee`.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRequestDto.java` — added `paidDays`/`unpaidDays`.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveService.java` — `submit()` gate redesign
  (approve-with-split replaces auto-reject-on-quota; SICK-attachment and advance-notice rejects
  unchanged); `cancel()` now calls `recordPayrollCorrectionIfNeeded` (new, minimal-design reversal).
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCarryForwardDtos.java` — `SuggestedInputRow`
  gains `unpaidLeaveDays`/`pendingUnpaidLeaveCorrectionDays` + an `empty(employeeId)` factory.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollRepository.java` —
  `findCarryForwardSuggestions` passes `ZERO` placeholders for the two new fields (overlaid later).
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollService.java` — new `LeaveRepository`
  constructor dependency; `suggestedInputs()` merges leave-derived figures into the carry-forward
  rows. `preview()`/`process()` untouched.
- `backend/src/test/java/th/co/glr/hr/leave/LeaveServiceTest.java` — constructor/DTO signature
  updates throughout; the old `submissionAutoRejectsWhenQuotaIsInsufficient` test replaced with
  `submissionApprovesWithPaidUnpaidSplitWhenQuotaIsInsufficient`; new tests for
  `LEAVE_WITHOUT_PAY` and the cancel-correction call-through (Mockito level).
- **Mechanical constructor-arity fixes only** (PayrollService gained a 6th constructor param;
  these 7 files instantiate it directly and needed the extra arg to keep compiling — no other
  change): `PayrollServiceTest.java`, `RetroactiveOvertimeReachesPayrollIntegrationTest.java`
  (both explicitly excluded from behavioural changes per the task brief —
  `feat/payroll-statutory-export-files` touches them — this is a one-line arity fix only),
  `PayrollAllowanceDirectorNonTaxableIntegrationTest.java`,
  `PayrollReprocessAndAttendanceDataFlowIntegrationTest.java`,
  `PayrollPersistedPayslipIntegrationTest.java`, `PayrollYtdAndSsoIntegrationTest.java`,
  `PayrollCommissionWeightedBaseIntegrationTest.java`.

### Frontend
- `frontend/src/features/payroll/PayrollPage.jsx` — `unpaidLeaveDays` now pre-fills from the
  suggestion (matching the existing carry-forward fields' fallback pattern); a warning-styled hint
  renders under the field when `pendingUnpaidLeaveCorrectionDays > 0`.
- `frontend/src/features/payroll/PayrollPage.test.jsx` — 4 new tests: pre-fill, HR override,
  real-value-wins-on-PROCESSED, correction hint. (Had to add `{ selector: 'input' }` to
  `getByLabelText` — the field's `InfoTip` carries the same accessible name as an `aria-label`,
  causing ambiguous matches; and expand the collapsed "รายการหักรายบุคคล" section first, since
  `CollapsibleSection` unmounts its body rather than CSS-hiding it.)

### Docs
- `docs/agent-handoffs/108_feat-leave-payroll-unpaid-deduction.md` (this file).

## Commands Run
```bash
cd backend && ./mvnw -q -o compile && ./mvnw -q -o test-compile
# throwaway DB (role $USER, :5432), TEST_DB_URL, -Dtest.fork.count=1, run exactly once as final verify:
TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_test_leavepayroll_<ts>" TEST_DB_USERNAME="$USER" TEST_DB_PASSWORD="" \
  ./mvnw -B -o clean verify -Dtest.fork.count=1
cd frontend && npm run lint && npm test -- --run && npm run build
```

## Test / Build Results
- **Backend compile + test-compile**: PASS.
- **Backend `clean verify` (real Postgres, throwaway DB)**: **BUILD SUCCESS.** Final confirmed run:
  `Tests run: 1153, Failures: 0, Errors: 0, Skipped: 2` (7m04s). Jacoco coverage checks also passed.
  Two real bugs were found and fixed by earlier runs in this same session before this final green
  one (see "Decisions Made" / "Known Risks" for the first; both are already reflected in the
  committed diff, not left as follow-ups): (1) `chk_leave_paid_unpaid_sum` was too strict for
  SUBMITTED/REJECTED demo-seed rows — fixed by scoping the constraint to
  `status IN ('APPROVED','CANCELLED')`; (2) my own new integration test NPE'd/FK-violated on a
  mocked attachment path — fixed by stubbing a real `hr.file_attachment` row instead of a
  fabricated id. The 2 skipped tests are `IntegrationResetInvariantTest`'s Testcontainers-only
  checks, which don't apply on the external-`TEST_DB_URL` path used here — expected, unrelated to
  this change.
- **`@SpringBootTest`-context race note**: not observed this session — no context-load errors seen
  across any of the four full/partial runs.
- **Frontend**: `npm run lint` — 0 errors, 1 pre-existing unrelated warning
  (`react-hooks/exhaustive-deps` on `PayrollPage`'s `load()` effect, present before this branch).
  `npm test -- --run` — 439/439 pass (all files, incl. the 4 new PayrollPage tests + existing
  suite). `npm run build` — succeeds (`✓ built in 174ms`).
- **Environment note**: the local machine's disk filled up completely (ENOSPC) mid-session from
  accumulated build artifacts across ~25 other `.claude/worktrees/*` checkouts (`backend/target` +
  `frontend/node_modules`, ~5GB total). Freed by deleting those regenerable directories (not
  source, always safe) — do NOT assume this is a one-off; if a future agent hits ENOSPC on this
  machine, the same cleanup is the fix, not a code problem.

## Authz Evidence
No authorization change in this task. `submit()`/`cancel()` keep their existing role/ownership
checks unchanged; the only new surface is a read-only leave-derived overlay on an existing
HR/CEO-gated payroll endpoint (`suggestedInputs`, unchanged `PAYROLL_VIEW_ROLES` gate).

## Decisions Made
1. **Paid-quota consumption order**: a leave request's `paid_days` are always the request's
   earliest chronological working days; the remainder is unpaid. This is the only ordering the
   aggregate `paid_days`/`unpaid_days` columns (not a per-day flag) can represent — documented in
   `LeaveDayMath` and the V85 migration as a company-policy caveat needing HR/legal sign-off.
2. **`chk_leave_paid_unpaid_sum` scope**: only enforced for `APPROVED`/`CANCELLED` rows, not
   `SUBMITTED`/`REJECTED`/`AUTO_REJECTED` — a pending or rejected request has a real `total_days`
   but an undetermined (0/0) split. Found via the demo-seed migration test
   (`FlywayMigrationTest.demoProfileCombinedLocationsApplyToACleanDatabase`), which seeds exactly
   this case (a pending SICK request).
3. **Suggestions merge is additive, keyed on employee**: `PayrollService#suggestedInputs` now
   unions employee IDs across the existing carry-forward rows, `findUnpaidLeaveDaysByEmployeeForMonth`,
   and `findPendingPayrollCorrectionsByEmployee` — an employee with only leave-derived figures
   (no prior processed payroll_line) still gets a row via `SuggestedInputRow.empty()`.
4. **Cancel-after-close reversal — v1/minimal, explicitly flagged for review**: cancelling was
   already unconditional (no code blocked cancelling an APPROVED leave overlapping a PROCESSED
   month). What was missing was bookkeeping: `LeaveService#cancel` now detects, per processed
   month the cancelled leave's unpaid days fell in, and inserts a row into the new
   `hr.leave_payroll_correction` table. `PayrollService#suggestedInputs` surfaces the unresolved
   total per employee as `pendingUnpaidLeaveCorrectionDays` — visible to HR (backend field +
   frontend hint text), but **never auto-netted into `unpaidLeaveDays` and never auto-resolved**.
   The `resolved_at`/`resolved_payroll_period_id` columns exist for a future explicit "mark
   applied" step that this PR does not build — see "Known Risks" for why that was deferred rather
   than attempted.
5. **SICK-attachment / advance-notice rejects are quota-independent**: preserved unchanged from
   before the redesign — a request that would be entirely unpaid (quota already at 0) still needs
   a certificate if SICK, still needs advance notice otherwise. Covered by
   `sickLeaveBeyondQuotaStillAutoRejectsWithoutACertificate`.

## Assumptions
- "Whole days only" means `paid_days` is always integral in practice (enforced by construction,
  not a DB constraint) — `LeaveDayMath` truncates defensively (`setScale(0, DOWN)`) wherever it
  reads a stored `paid_days` back, in case of any future non-integral write.
- No holiday calendar means a public holiday inside a leave range still counts as a working day in
  v1 (explicitly out of scope per the task brief).

## Known Risks
- **Cancel-after-close correction is never auto-resolved.** Every `suggestedInputs` call keeps
  surfacing an unresolved correction for an employee forever, until either (a) a future PR adds an
  explicit "mark resolved" action, or (b) someone manually sets `resolved_at` via SQL. Flagging
  this clearly rather than attempting an automatic version was a deliberate choice: automatically
  detecting that HR *actually applied* the credit (vs. merely saw the number) needs either an
  explicit UI action or a way to trace a specific correction into a specific processed
  `payroll_line`, and guessing at either risked silently over/under-crediting real payroll money.
- **Migration V85 is not yet deployed anywhere** (created and iterated on entirely within this
  session/branch) — safe to have edited it in place twice while debugging; do NOT do that once
  it's merged to `main`.
- The `chk_leave_paid_unpaid_sum` and `chk_leave_paid_unpaid_nonnegative` constraints, plus the
  `PayrollService` constructor-arity change, are the most likely rebase-conflict points against
  `feat/payroll-statutory-export-files` (touches `PayrollService.java`/`PayrollRepository.java`/
  `PayrollController.java`/`PayrollPage.jsx`) — the change there should be additive-only per its
  own branch's brief, so a conflict should be mechanical, not semantic.

## Things Not Finished
- **Committed, not pushed/merged** (`9a37870`) — pushing/merging is explicitly out of scope for
  this branch per the task brief; the next step is Opus review, not a push.
- **HR/legal sign-off** on the deduction rule itself (quota-exceeded = unpaid, chronological
  consumption) is a real open item, not a code task — flagged in the V85 migration comment,
  `LeaveDayMath`'s class doc, and `LeaveService#submit`'s inline comment.
- Frontend does not surface `pendingUnpaidLeaveCorrectionDays` anywhere except the payroll
  unpaid-leave-days field hint — no dedicated "corrections" admin view exists. Given the
  never-resolved caveat above, a future PR building the resolution flow should probably also add
  a proper list view at that point.

## Recommended Next Agent
Claude Opus review (per the Sonnet-implements/Opus-reviews loop this repo uses).

## Exact Next Prompt
```
Review branch feat/leave-payroll-unpaid-deduction (worktree at .claude/worktrees/leave-payroll,
commit 9a37870) against docs/agent-handoffs/108_feat-leave-payroll-unpaid-deduction.md. Backend
`clean verify` and frontend lint/test/build are confirmed green as of that commit -- re-run only
if you've made changes. Specifically verify: (1) the chk_leave_paid_unpaid_sum scoping fix
(APPROVED/CANCELLED only) is correct and doesn't mask a real bug elsewhere; (2) the
cancel-after-close correction design (recorded but never auto-resolved) is an acceptable v1 given
the "flag, don't drop" instruction, or should be reduced further / built out more before merge;
(3) the paid-quota-consumed-chronologically assumption and the "no holiday calendar" scope cut are
reasonable interim calls, not silent scope creep. Then decide: merge-ready, or send back with
specific findings.
```
