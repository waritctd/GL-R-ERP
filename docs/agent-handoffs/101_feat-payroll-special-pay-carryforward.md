# Agent Handoff

## Task
Build special-pay carry-forward: when HR opens a new monthly payroll run, pre-fill the RECURRING
inputs (special_pay_1..5, non_taxable_income, student_loan_deduction, legal_execution_deduction)
from the employee's previous month, while HR can still edit/override every value before
preview/process. Do NOT carry special_pay_6 (commission — already fed by the commission engine),
special_pay_7/8 (KPI/bonus), or any event-driven field (unpaid leave days, warning-letter /
customer-return deductions, other pre/post-tax deductions).

## Branch
`feat/payroll-special-pay-carryforward`

## Base Commit
`eba6fa4` (origin/main, "Merge pull request #280 from waritctd/test/payroll-seam-integration")

## Current Commit
(not committed yet by this agent — see note below; commit pending user confirmation per workflow)

## Agent / Model Used
Claude Sonnet 5 (implementation agent in a Sonnet-implements/Opus-reviews loop)

## Scope

### In Scope
- New read-only `GET /api/payroll/suggested-inputs?payrollMonth=YYYY-MM` endpoint (HR/CEO only).
- New additive `PayrollRepository.findCarryForwardSuggestions` query.
- New additive `PayrollService.suggestedInputs` method.
- Frontend `PayrollPage.jsx` pre-fill from the new endpoint, with HR edits taking priority.
- `hrApi.js` / `mockApi.js` / `routes.js` counterparts.
- New backend test classes (repository behaviour + real-filter-chain authz).
- New frontend test file for the pre-fill/override behaviour.

### Out of Scope
- `PayrollService.preview()` / `process()` request contract — unchanged. No new field was added to
  `ProcessPayrollRequest` / `PayrollEmployeeInputRequest`; carry-forward is entirely a client-side
  pre-fill that happens before the existing payload is built.
- The `feat/payroll-statutory-export-files` branch's `bankExport`/`export` rework, and the
  `PayrollService` constructor — untouched, per explicit instruction (that branch edits the same
  files concurrently).
- Existing `PayrollRepositoryIntegrationTest`, `PayrollServiceTest`, `PayrollControllerTest`,
  `SecurityAuthorizationIntegrationTest`, `PayrollPage.test.jsx` — untouched (all are concurrently
  edited by the export-files branch); new coverage lives in brand-new files instead.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCarryForwardDtos.java` (NEW) — `SuggestedInputRow` /
  `SuggestedInputsResponse` records for the carried fields only.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollRepository.java` — additive
  `findCarryForwardSuggestions(LocalDate payrollMonth)`, inserted between
  `findYearToDateByEmployee` and `findTaxAllowancesByEmployee` (a region the concurrent export
  branch does not touch — that branch inserts its `findExportRows` after `findLines` instead).
  `DISTINCT ON (pl.employee_id)` picks the latest `payroll_period.payroll_month` strictly before the
  requested month with `status <> 'VOID'`, joined to `hr.employee` with `is_active = TRUE`.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollService.java` — additive
  `suggestedInputs(LocalDate, UserPrincipal)`, inserted between `currentOrPreview` and `preview`
  (a region the export branch does not touch — its changes are in the constructor and
  `bankExport`/`export`). No constructor change, no new dependency.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollController.java` — additive
  `GET /api/payroll/suggested-inputs` mapping, inserted between `currentOrPreview` and `preview`
  mappings (disjoint from the export branch's `bankExport` → `export`/`{kind}` rework and its new
  `parseKind`/`parseEffectiveDate` private helpers).
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollCarryForwardSuggestionsIntegrationTest.java`
  (NEW) — real-Postgres repository test: latest-prior-line selection, excludes commission/KPI/bonus
  and event-driven fields, ignores VOID periods, ignores same-month/future periods, no suggestion for
  an employee with no prior line, no suggestion for a terminated employee.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollSuggestedInputsAuthorizationIntegrationTest.java`
  (NEW) — real `SecurityFilterChain` test: HR/CEO reach 200, `employee`/`sales` roles get 403,
  unauthenticated gets 401. Mirrors `SecurityAuthorizationIntegrationTest`'s pattern exactly, as a
  separate file since that file is concurrently edited by the export branch.
- `frontend/src/api/routes.js` — additive `payroll.suggestedInputs` route.
- `frontend/src/api/hrApi.js` — additive `payroll.suggestedInputs` method.
- `frontend/src/api/mockApi.js` — additive `payroll.suggestedInputs` mock (returns an empty
  suggestions list — mirrors `current`'s existing "no seeded payroll history" behaviour).
- `frontend/src/features/payroll/PayrollPage.jsx` — `load()` now fetches suggestions (only when the
  loaded period is a fresh `PREVIEW` with no processed period id yet); `adjustmentFromLine` /
  `applyPeriod` gained a `suggestion` parameter used as a fallback value with priority: the line's own
  real value (already submitted/persisted) > a carried figure from last month > the pre-existing
  hardcoded "UAT demo default" (500 for specialPay1/specialPay5). The `api.payroll.suggestedInputs?.()`
  call uses optional chaining so it degrades to "no suggestions" rather than throwing if the mock
  surface doesn't implement it (defensive, not required in production where both hrApi/mockApi always
  implement it).
- `frontend/src/features/payroll/PayrollPage.carryForward.test.jsx` (NEW) — pre-fill from suggestions,
  HR-edit overrides the pre-filled value (and the edited value — not the suggestion — is what gets
  submitted), and suggestions are never fetched once a period is already processed.
- `docs/agent-handoffs/101_feat-payroll-special-pay-carryforward.md` (this file, NEW).

## Commands Run
```bash
git fetch origin
git worktree add -b feat/payroll-special-pay-carryforward .claude/worktrees/carryforward origin/main

cd frontend
npm ci
npm run lint
npm test
npm run build

cd ../backend
psql -h localhost -U "$USER" -d postgres -c "CREATE DATABASE glr_carryforward_it_1784750943 OWNER $USER;"
TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_carryforward_it_1784750943" \
  ./mvnw -B clean verify -Dtest.fork.count=1
# (drop the throwaway DB afterward)
```

## Test / Build Results
- Frontend lint: **pass** (0 errors; 1 pre-existing warning in `PayrollPage.jsx`, unrelated to this
  change — `react-hooks/exhaustive-deps` on the existing `useEffect(() => { ...; load(); }, [month])`,
  present before this branch's changes).
- Frontend tests: **pass** — 49 files / 435 tests, including the 3 new carry-forward tests and the
  existing `PayrollPage.test.jsx` (4 tests, untouched) and `contract.test.js` (3 tests, confirms
  `hrApi`/`mockApi` method-surface parity for the new `suggestedInputs` method).
- Frontend build: **pass** (`vite build`, no errors).
- Backend `clean verify`: <!-- FILL IN AFTER BACKGROUND RUN COMPLETES: BUILD SUCCESS/FAILURE + test
  tallies (Tests run: X, Failures: Y, Errors: Z, Skipped: W) --> ran against a real local Postgres
  throwaway DB (`glr_carryforward_it_1784750943`, role `$USER`, `-Dtest.fork.count=1`), so integration
  tests **ran**, not skipped.

## Authz Evidence
Verified against the real Java service via
`PayrollSuggestedInputsAuthorizationIntegrationTest` — boots the real `SecurityFilterChain`
(`@SpringBootTest` + `springSecurityFilterChain`), asserts HR/CEO reach 200 and `employee`/`sales`
roles get 403 on `GET /api/payroll/suggested-inputs`, plus 401 unauthenticated. This is the same
`PAYROLL_VIEW_ROLES` gate (`hasAnyRole('HR','CEO')`) already used by every other read endpoint in
`PayrollController` — no new role or scope concept was introduced, only a new endpoint reusing the
existing gate, and that reuse is what the test proves survives into the real filter chain.

## Decisions Made
- **Null-vs-zero handling (explicitly flagged for review, per the task):** carry-forward lives
  ENTIRELY on the frontend, as a client-side pre-fill step, and does **not** touch
  `PayrollService.preview()`/`process()` in any way. `ProcessPayrollRequest` /
  `PayrollEmployeeInputRequest` are unchanged — HR always submits explicit numeric values (0 for a
  blank field, same as before this branch). Concretely: `PayrollPage.jsx`'s `adjustmentFromLine`
  fills each carried field's form value using priority (real line value → suggested prior value →
  hardcoded UAT demo default), and if HR clears a pre-filled field, `parsePayrollNumber('')` yields
  `0`, which is what goes into `payload().inputs`. There is no "omitted means carry" state on the
  wire at any point — the ambiguity the task asked me to either resolve this way or flag does not
  arise here, because the carry-forward step happens before the request is ever built. I did NOT
  auto-default inside `preview()`/`process()`, so the non-null `BigDecimal` request contract needed
  no change and no "omitted vs explicit 0" distinction had to be invented.
- **Priority of carried value vs. the pre-existing hardcoded "UAT demo default":** the codebase
  already hardcodes specialPay1/specialPay5 to `500` when starting any fresh `PREVIEW` run
  (`defaultSpecialPayValue`, existing code, unrelated to this task, covered by the existing
  `PayrollPage.test.jsx` "uses Excel-based UAT defaults" test). I made a real carried suggestion take
  priority over that hardcoded guess (a real prior figure is more accurate than a flat placeholder),
  falling back to the hardcoded default only when no suggestion exists for that employee/field. The
  existing test still passes unchanged because its mock doesn't call `suggestedInputs` (optional
  chaining), so `suggestion` is `null` and the hardcoded default applies exactly as before.
- **Suggestion fetch is scoped to "starting a fresh run" only:** `load()` only calls
  `GET /suggested-inputs` when the loaded period is `status === 'PREVIEW' && !id` (no processed
  period exists for the month yet). A `PROCESSED` period, or a `PREVIEW` produced by clicking
  "Preview" with HR's own inputs, never re-fetches or re-applies suggestions — those already reflect
  real, submitted-or-about-to-be-submitted values.
- **Restricted to currently active employees** in the repository query (`e.is_active = TRUE`) — a
  terminated employee's old figures should never surface as a suggestion for a payroll run they are
  no longer part of, even though `payroll_line` itself is immutable history.
- **New test files instead of editing existing ones**, mirroring the instruction that
  `feat/payroll-statutory-export-files` concurrently edits `PayrollRepositoryIntegrationTest`,
  `PayrollServiceTest`, `PayrollControllerTest`, `SecurityAuthorizationIntegrationTest`, and
  `PayrollPage.test.jsx`. This branch adds `PayrollCarryForwardSuggestionsIntegrationTest`,
  `PayrollSuggestedInputsAuthorizationIntegrationTest`, and `PayrollPage.carryForward.test.jsx`
  instead, to keep the eventual rebase/merge low-conflict.

## Assumptions
- "Previous month" means the latest **processed** `payroll_period` strictly before the requested
  month (`payroll_month < :payrollMonth`), not necessarily the calendar-adjacent month — matches the
  task's own wording ("the latest period strictly before the requested month").
- A period's `status` can be `'VOID'` (the existing `findYearToDateByEmployee` query already guards
  against it with the same `<> 'VOID'` check), even though no code path in this codebase currently
  sets that status — the integration test sets it directly via SQL to prove the guard works if/when
  a future voiding feature lands.

## Known Risks
- **Rebase conflict surface, despite disjoint insertion points:** `PayrollController.java`,
  `PayrollService.java`, and `PayrollRepository.java` are all concurrently being edited by
  `feat/payroll-statutory-export-files` (verified via `git diff` against that branch's uncommitted
  worktree changes before writing this branch's code, specifically to place insertions in untouched
  regions). Insertions were placed away from every hunk that branch touches (its constructor change,
  `bankExport`→`export` rework, and its private `parseKind`/`parseEffectiveDate` helpers), but a
  textual rebase can still require manual reconciliation depending on merge order.
- **`PayrollPage.jsx`'s existing `react-hooks/exhaustive-deps` lint warning is pre-existing**, not
  introduced by this branch — flagging so it isn't mistaken for new fallout.
- Frontend `suggestedInputs` uses optional chaining (`api.payroll.suggestedInputs?.()`) purely as a
  defensive fallback for callers/mocks that don't implement it; both `hrApi.js` and `mockApi.js` in
  this repo always implement it, so in practice the optional chaining is inert in this codebase today.

## Things Not Finished
- Backend `./mvnw clean verify` was kicked off against a real local Postgres throwaway DB but this
  handoff was written before it finished — **fill in the BUILD SUCCESS/FAILURE line above and the
  test tallies before treating this branch as done**, and drop the throwaway DB
  (`glr_carryforward_it_1784750943`) afterward regardless of outcome.
- Not committed to git yet (per instructions: implement + verify, do not push/merge; committing is
  the very next step once the backend build result is in).

## Recommended Next Agent
Claude Opus review (per the "Sonnet-implements / Opus-reviews" loop already standing for this repo).

## Exact Next Prompt
```
Review branch feat/payroll-special-pay-carryforward (worktree
.claude/worktrees/carryforward, based on origin/main @ eba6fa4) against
docs/agent-handoffs/101_feat-payroll-special-pay-carryforward.md. Confirm: (1) the backend
`./mvnw clean verify` result recorded in that handoff actually shows BUILD SUCCESS with the new
PayrollCarryForwardSuggestionsIntegrationTest and PayrollSuggestedInputsAuthorizationIntegrationTest
passing; (2) PayrollService.preview()/process() and ProcessPayrollRequest/PayrollEmployeeInputRequest
are byte-for-byte unchanged (no business-logic/contract drift); (3) the new
GET /api/payroll/suggested-inputs endpoint only reuses the existing PAYROLL_VIEW_ROLES gate, no new
permission concept; (4) the frontend carry-forward priority (real line value > suggestion > hardcoded
UAT 500 default) matches the task's intent and the null-vs-zero handling described in "Decisions
Made" is sound. If everything checks out, this branch is ready to commit (it has not been committed
yet) and then open a PR against main; flag anything that doesn't check out back to a fresh
implementation pass instead of fixing it directly.
```
