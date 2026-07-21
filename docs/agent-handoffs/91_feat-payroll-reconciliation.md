# Agent Handoff

## Task
Reconcile `PayrollCalculator` against the accountant's real 2026 payroll workbook (2026.xlsx, sheet
พ.ค.69). Four gaps closed:

- **C1** — persist the 16-field tax-allowance declaration per employee/tax-year (`hr.employee_tax_allowance`)
  instead of retyping it every payroll run; the run body may still override individual fields in-run.
- **C2** — a year-to-date backfill seed (`hr.payroll_year_to_date_seed`) so a mid-year go-live doesn't
  under-withhold to zero from ~August (empty YTD under-projects annual income).
  merged with `payroll_line`'s actual processed months in `findYearToDateByEmployee`.
- **C3** — ค่าตอบแทนกรรมการ (director remuneration): a fixed monthly amount stored on `hr.employee`,
  taxable but explicitly excluded from the SSO wage base (directors carry no SSO in the sheet).
- **C4** — the three pre-tax deductions the sheet subtracts before tax (หักตามใบเตือน, หัก
  ลูกค้าคืนสินค้า, อื่นๆ — sheet columns Z/AA/AB), which the engine previously had nowhere to put
  except post-tax `otherPostTaxDeductions`, over-taxing the employee.

**This branch is an explicit, owner-requested exception to CLAUDE.md's "never change payroll/tax
math" rule.** Every change mirrors arithmetic the accountant's workbook already performs; this is
correcting the engine to match an existing source of truth, not new business logic. See the branch
system prompt / owner instructions for the exact authorization.

`backend/src/test/java/th/co/glr/hr/payroll/PayrollExcelReconciliationTest.java` (present untracked
at the start of this task, documenting the four findings against transcribed sheet figures) was left
byte-for-byte unedited and still passes.

## Branch
`feat/payroll-reconciliation`

## Base Commit
`8b513b8` (origin/main)

## Current Commit
Not committed — working tree left uncommitted per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `PayrollCalculator` gross/taxable-income/SSO/deduction math (the approved exception).
- New `hr.employee_tax_allowance`, `hr.payroll_year_to_date_seed` tables; new columns on
  `hr.employee` (`director_remuneration`) and `hr.payroll_line` (director remuneration + 3 pre-tax
  deductions).
- Four new HR-edit / HR+CEO-view payroll endpoints and their service/repository plumbing.
- `PayrollPage.jsx`: three new editable pre-tax deduction inputs + a read-only director-remuneration
  line.

### Out of Scope
- Everything unrelated to payroll (no other module touched).
- No UI was built for the new tax-allowance / YTD-seed CRUD screens themselves (only the API +
  PayrollPage's per-run deduction inputs) — see Known Risks.

## Files Changed

Backend:
- `backend/src/main/resources/db/migration/V73__payroll_reconciliation_inputs.sql` — new migration:
  `hr.employee_tax_allowance`, `hr.payroll_year_to_date_seed` tables; `hr.employee.director_remuneration`;
  `hr.payroll_line.director_remuneration/warning_letter_deduction/customer_return_deduction/other_pretax_deduction`.
  V55–V72 are claimed by other unmerged branches/prod per explicit owner instruction; V71 is the
  first free number after this worktree's V54 tip.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCalculationInput.java` — 4 new fields
  (directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction)
  appended after the original 12; a legacy 12-arg constructor delegates to the canonical one with the
  new fields at zero, so every pre-existing positional call site (including the fixed reconciliation
  test) still compiles unchanged.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCalculation.java` — same 4 fields appended as
  outputs.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCalculator.java` — director remuneration joins
  `grossEarnings` (taxable) but never `ssoWageBase` (base-salary-only, unchanged); the 3 pre-tax
  deductions subtract from `grossTaxableIncome` (mirroring `unpaidLeaveDeduction`'s existing pattern)
  and also land in `totalDeductions` (mirroring the same existing double-appearance, which is how
  `netPay = grossEarnings - totalDeductions + nonTaxable` stays correct without double-counting).
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollLineDto.java` — same 4 fields appended.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollEmployeeSnapshot.java` — `directorRemuneration`
  appended.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollEmployeeInputRequest.java` — 3 new per-run
  fields (warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction) appended; director
  remuneration is deliberately NOT here (it comes from the employee record, not typed per run).
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollReconciliationDtos.java` — **new file**:
  `EmployeeTaxAllowanceDto`/`UpsertRequest`, `YtdSeedDto`/`UpsertRequest`, and their bulk
  request/response wrappers.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollRepository.java` —
  `findActiveEmployees` now also selects `director_remuneration` and its `WHERE` clause was widened
  to `(current_salary > 0 OR director_remuneration > 0)` — **necessary behavior change**: a director
  with zero salary would otherwise never appear in payroll at all, defeating the point of C3.
  `findYearToDateByEmployee` now UNION-ALLs `payroll_line` with `payroll_year_to_date_seed` and
  GROUPs BY employee (equivalent to a FULL OUTER JOIN, handles either/both/neither per employee).
  New: `findTaxAllowancesByEmployee`, `findTaxAllowanceRows`, `upsertTaxAllowances`,
  `findYtdSeedRows`, `upsertYtdSeed`. `insertLine`/`mapLine`/`findLines` SQL extended with the 4 new
  `payroll_line` columns.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollService.java` — `preview()` loads the stored
  tax-allowance declaration for the payroll month's tax year and merges it with the request body
  per-field (`mergeAllowances`/`firstNonNull`: a non-null request field wins, else the stored value,
  else zero) — stored = standing declaration, body = this-run correction. `calculateLine` now also
  passes `directorRemuneration` from the employee snapshot and the 3 pre-tax deductions from the
  request. New: `getTaxAllowances`/`upsertTaxAllowances`/`getYtdSeed`/`upsertYtdSeed`, gated
  `PAYROLL_VIEW_ROLES` (hr, ceo) for GET and `PAYROLL_EDIT_ROLES` (hr only) for PUT, each PUT audited
  via `AuditService`.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollController.java` — 4 new endpoints:
  `GET/PUT /api/payroll/tax-allowances?year=`, `GET/PUT /api/payroll/ytd-seed?year=`. GET is
  `hasAnyRole('HR','CEO')`, PUT is `hasRole('HR')`.
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipRenderer.java` — renders "ค่าตอบแทนกรรมการ" and
  the 3 pre-tax deduction lines only when non-zero.
- Tests updated for the new positional fields (all safe to edit, none are the fixed reconciliation
  file): `PayrollCalculatorTest.java` (+5 tests: byte-identical regression, director remuneration vs
  SSO, each pre-tax deduction, pre-tax-vs-post-tax tax treatment), `PayrollRepositoryIntegrationTest.java`
  (+3 tests: director round-trip incl. `findActiveEmployees` picking up a salary-less director,
  tax-allowance upsert/overwrite round-trip, YTD seed-merges-with-lines), `PayrollServiceTest.java`,
  `PayrollRepositoryIntegrationTest.java`, `PayslipRendererTest.java`, `PayslipDistributionServiceTest.java`
  (positional `PayrollLineDto(...)` calls extended with 4 trailing zeros).
- `backend/src/test/java/th/co/glr/hr/config/SecurityAuthorizationIntegrationTest.java` — +4 authz
  tests for the new endpoints (see Authz Evidence).

Frontend:
- `frontend/src/api/routes.js` — `taxAllowances`/`ytdSeed` routes.
- `frontend/src/api/hrApi.js` — `getTaxAllowances`/`saveTaxAllowances`/`getYtdSeed`/`saveYtdSeed`.
- `frontend/src/api/mockApi.js` — mirrored methods; GET returns an empty list, PUT throws "not
  supported in mock mode" (same house style as `preview`/`process`, which also carry real payroll
  figures the mock can't fake convincingly).
- `frontend/src/features/payroll/PayrollPage.jsx` — 3 new editable inputs (หักตามใบเตือน / หัก
  ลูกค้าคืนสินค้า / หักอื่น ๆ ก่อนภาษี) in a new "รายการหักก่อนภาษี" collapsible section; a read-only
  ค่าตอบแทนกรรมการ line in the existing breakdown panel, shown only when non-zero.

## Commands Run
```bash
cd backend && ./mvnw -B clean verify
cd frontend && npm ci && npm run lint && npm test -- --run && npm run build
```
Plus targeted `./mvnw -q -B test -Dtest=...` runs during development, and a manual mutation-check
cycle (see Authz Evidence).

## Test / Build Results
- **Backend**: `./mvnw -B clean verify` → **587 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.
  Baseline was 568 before this branch's untracked reconciliation test existed, 575 with it added; this
  change added 12 new tests (5 in `PayrollCalculatorTest`, 3 in `PayrollRepositoryIntegrationTest`, 4
  in `SecurityAuthorizationIntegrationTest`) → 575 + 12 = 587, matching observed output exactly.
  **Testcontainers integration tests ran for real** (Docker available): the log shows the real
  Testcontainers Postgres booting, Flyway migrating schema "hr" through V71 ("payroll reconciliation
  inputs") multiple times (once per `@BeforeEach resetSchema()` in each Postgres-backed test class),
  and zero `@Disabled`/`Skipped` entries.
- **Frontend**: `npm run lint` → 0 errors (3 pre-existing `react-hooks/exhaustive-deps` warnings,
  one of them in `PayrollPage.jsx` but pre-existing, unrelated to this change). `npm test -- --run` →
  **215 tests, 36 files, all passed** (unchanged from baseline — no new frontend test was added; the
  mock's new methods only needed contract-surface parity, verified by `contract.test.js` which passed).
  `npm run build` → succeeded (149ms, `PayrollPage` chunk 17.37 kB / 5.18 kB gzip).

## Authz Evidence
**Verified against the real Java service and real Postgres**: `SecurityAuthorizationIntegrationTest`
(`backend/src/test/java/th/co/glr/hr/config/SecurityAuthorizationIntegrationTest.java`), which boots
the full Spring context with the real `springSecurityFilterChain` + real `PayrollController` +
`PayrollService` + `PayrollRepository` against a real (Testcontainers) Postgres.

4 new tests, written wrong-way-round (assert the caller CANNOT reach the edit, and the table is
provably unchanged afterward):
- `aPlainEmployeeCannotViewOrEditStoredTaxAllowancesOrYtdSeed` — employee role gets 403 on all 4 new
  endpoints (GET and PUT, both resources); zero rows exist afterward.
- `aSalesRoleCannotEditStoredTaxAllowancesOrYtdSeedEither` — sales role gets 403 on both PUTs; zero
  rows afterward.
- `ceoCanViewStoredTaxAllowancesAndYtdSeedButCannotEditEither` — CEO gets 200 on both GETs (the
  intended broader-view allowance) but 403 on both PUTs; zero rows afterward.
- `anHrSessionCanEditStoredTaxAllowancesAndYtdSeed` — positive control: HR PUT succeeds and the row
  is actually persisted (1 row each).

**Mutation-check, run and reverted for real** (not simulated):
1. First attempt — loosened only `PayrollController`'s two `@PutMapping` `@PreAuthorize` annotations
   from `hasRole('HR')` to `hasAnyRole('HR','CEO')`. Result: **all 10 tests in
   `SecurityAuthorizationIntegrationTest` still passed** — `PayrollService.requireRole(actor,
   PAYROLL_EDIT_ROLES)` (defense-in-depth, `PAYROLL_EDIT_ROLES = Set.of("hr")`) still blocked CEO with
   a 403 from the service layer even though the controller-level guard was open. This is a *good*
   finding (real defense-in-depth), but it meant this specific mutation wasn't observable through
   this guard alone.
2. Combined mutation — loosened the same two `@PreAuthorize` annotations **and**
   `PayrollService.PAYROLL_EDIT_ROLES` to `Set.of("hr", "ceo")` (fully opening the CEO-edit path end
   to end, simulating someone forgetting both layers). Result:
   **exactly 2 tests went red, nothing else**:
   - `SecurityAuthorizationIntegrationTest.ceoCanViewStoredTaxAllowancesAndYtdSeedButCannotEditEither`
     — expected 403, got 500 (the write was no longer blocked by authz and instead failed a downstream
     FK constraint on the fixture's synthetic employee id — still proof the authz gate was gone, just
     via a different failure mode than a clean pass).
   - `PayrollServiceTest.ceoCannotProcessPayroll` — a **pre-existing** test that also exercises
     `PAYROLL_EDIT_ROLES` (shared constant with `/api/payroll/process`), confirming the shared role set
     is real, load-bearing, and covered from both the old and new call sites.
   - `PayrollControllerTest` (12 tests, standalone MockMvc, no real security filter chain) was
     unaffected, as expected — it doesn't exercise `@PreAuthorize` or the real service.
3. Reverted both files to the pre-mutation state (`@PreAuthorize("hasRole('HR')")` on both PUT
   endpoints, `PAYROLL_EDIT_ROLES = Set.of("hr")`); confirmed via `git diff` that only the originally
   authored new code remained (`grep` for `@PreAuthorize`/`PAYROLL_EDIT_ROLES` showed the intended
   final values, nothing residual from the mutation).
4. Re-ran the full `./mvnw -B clean verify` after reverting: 587/587 green (see above).

## Decisions Made
- **`findActiveEmployees` WHERE clause widened** to `(current_salary > 0 OR director_remuneration >
  0)`. Not explicitly specified in the task, but required: without it, a director with zero salary
  (the entire point of C3 — "D/H salary EMPTY") would never be included in any payroll run at all.
- **Allowance merge is per-field, not per-employee.** A non-null field in the request body overrides
  the stored value for that field only; a null field falls back to the stored declaration (or zero).
  This lets HR correct e.g. just this month's donation figure without retyping the other 15 fields.
- **PayrollCalculationInput/PayrollCalculation/PayrollLineDto**: new fields appended at the very end
  of each record (after the pre-existing last field, including after `calculationNote` in the DTOs),
  not interleaved by theme, to minimize the diff surface on every existing positional constructor call
  and keep the "did this compile because nothing moved" guarantee obvious on review.
- **`PayrollCalculationInput` keeps a secondary 12-arg constructor** (not just relying on nullable new
  fields) so the untouchable reconciliation test's positional calls compile byte-for-byte unchanged,
  per the explicit instruction not to edit that file.
- **YTD merge implemented as `UNION ALL` + `GROUP BY`** rather than an explicit `FULL OUTER JOIN` —
  equivalent result, avoids NULL-coalescing boilerplate on both sides of a join.

## Assumptions
- "HR-typed per run" (C4's three deductions) vs "fixed on the employee" (C3's director remuneration)
  is exactly as specified; no attempt was made to also let director remuneration vary per-run.
- The GET tax-allowances/ytd-seed responses join to `hr.employee` for display (`employeeCode`,
  `employeeName`) since a bare `employeeId` list would be unusable HR/CEO UI data, even though this
  wasn't explicitly specified.

## Known Risks
- **No dedicated UI was built for managing the standing tax-allowance declarations or the YTD seed**
  (the `GET/PUT .../tax-allowances` and `.../ytd-seed` endpoints have no page consuming them yet).
  `PayrollPage.jsx` only got the three new per-run pre-tax deduction inputs and the read-only director
  line, which were explicitly in scope; a CRUD screen for the other two was not requested and would be
  a separate, reviewable slice.
- `mockApi.js`'s new GET methods always return an empty list and PUT always throws — this is
  consistent with the existing `preview`/`process` mock behavior for real-figures endpoints, but means
  `VITE_USE_MOCKS=true` cannot be used to browser-test the new tax-allowance/YTD-seed flow end to end;
  only the real backend can (as already documented for the rest of this file's payroll namespace).
- The `chk_employee_director_remuneration_non_negative` / `chk_payroll_line_pretax_non_negative`
  constraints in V71 use `DROP CONSTRAINT IF EXISTS` + re-`ADD` (idempotent-on-rerun pattern already
  used elsewhere in this schema, e.g. V31), which is intentional, not an oversight.

## Recommended Next Agent
Claude Opus review, focused on: (1) the C4 pre-tax-vs-post-tax arithmetic in `PayrollCalculator`
against the sheet's AC/AD identity, (2) the allowance-merge semantics in `PayrollService.mergeAllowances`,
(3) whether a dedicated tax-allowance/YTD-seed management UI should be scoped as a follow-up branch.

## Exact Next Prompt
```
Review the payroll reconciliation branch feat/payroll-reconciliation (handoff
docs/agent-handoffs/89_feat-payroll-reconciliation.md) against
backend/src/test/java/th/co/glr/hr/payroll/PayrollExcelReconciliationTest.java and the accountant's
2026.xlsx workbook (sheet พ.ค.69). Specifically verify: (1) PayrollCalculator's C3/C4 changes produce
byte-identical output to the pre-change engine when all four new inputs are zero (see
PayrollCalculatorTest.withEveryNewInputAtZeroTheCalculatorIsByteIdenticalToBeforeTheReconciliationChange);
(2) the mutation-check evidence in the handoff's Authz Evidence section is reproducible; (3) whether a
CRUD UI for GET/PUT /api/payroll/tax-allowances and /api/payroll/ytd-seed should be scoped as a
follow-up branch (currently API-only, no page). Do not change payroll math further without an
equally explicit owner exception.
```


## Review findings (Opus verification pass, 2026-07-21)

Independently re-ran `./mvnw -B clean verify` and mutation-checked the C3 guard directly rather than
relying on the implementation report. Two things came out of it.

### The SSO exclusion was NOT actually guarded — fixed

Folding director remuneration into the SSO base
(`ssoWageBase(baseSalary.add(directorRemuneration).subtract(unpaidLeaveDeduction))`) — which is
precisely the ฿875/month over-deduction C3 exists to prevent — produced **zero test failures**.

`directorRemunerationRaisesGrossAndTaxButNeverTouchesSocialSecurity` cannot catch it: its fixture uses
a ฿30,000 base salary, already above the ฿17,500 wage ceiling, so social security is capped at ฿875
whether or not director pay joins the base. The assertion `withDirectorPay.socialSecurity() ==
withoutDirectorPay.socialSecurity()` passes trivially under the mutation.

Added **`aDirectorWithNoSalaryPaysNoSocialSecurityAtAll`**, which uses the real director profile from
the workbook — base salary ZERO (columns D/H are empty for all five directors), director remuneration
฿150,000. With a zero base the ceiling no longer hides the defect: SSO must be exactly ฿0, and the
mutation produces ฿875. Re-applying the mutation now turns exactly that one test red; reverted and the
full suite re-verified.

**Backend is 588 tests** (587 + this one), 0 failures, 0 skipped, integration tests ran.

### Deviation reviewed and endorsed

Widening `findActiveEmployees`'s `WHERE` from `current_salary > 0` to
`(current_salary > 0 OR director_remuneration > 0)` was necessary, not incidental: directors have no
salary, so the old predicate excluded them from payroll entirely and C3 would have been silently
inert. The change touches only that predicate — `is_active` and everything else are unchanged.

Also verified the single production call site (`PayrollService:202`) passes all 16 constructor
arguments, so the 12-arg legacy overload is test-compatibility only and cannot silently zero the new
inputs in production.
