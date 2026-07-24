# Handoff — feat/payroll-withholding-tax-override

Owner-authorized, tax-sensitive change. Adds an **HR override for withholding tax
(ภาษีหัก ณ ที่จ่าย)** with two layers ("Both" pattern, mirroring the V73 tax-allowance design):

1. **Standing per-employee** default on `hr.employee` (nullable) — set once, applies every month.
2. **Per-run** override on the payroll page (`hr.payroll_line`) — HR types it for a run; it WINS.

Precedence (implemented exactly): effective withholding =
`perRunOverride (if non-null)` else `employee.withholdingTaxOverride (if non-null)` else `computed`.
**NULL everywhere = compute normally (today's behaviour, unchanged).**

## Tax-handling change — stated plainly (the guardrail)
The progressive tax computation is **NOT changed**. `progressiveTax`, `projectedAnnualIncome`,
`taxableAnnualIncome`, `annualTax`, allowances and SSO are computed and **still reported unchanged**
in the DTO for transparency. The override **only substitutes the final `withholdingTax` value** (and
therefore `totalDeductions`/`netPay`) WHEN an override is present. The substitution is a single line
in `PayrollCalculator` right after `withholdingTax` is computed; a `null` override is a no-op that
reproduces today's output byte-for-byte. **Zero is a meaningful override** (withhold nothing) and is
honoured — every layer uses an explicit null check, never a truthiness/sign test. SSO, commission and
all other math are untouched.

## Schema — V88 (forward-only; V87 was the prior max on main/uat/prod)
`backend/src/main/resources/db/migration/V88__withholding_tax_override.sql`:
- `hr.employee.withholding_tax_override NUMERIC(12,2) NULL` + CHECK (`IS NULL OR >= 0`). Standing value.
- `hr.payroll_line.withholding_tax_override NUMERIC(12,2) NULL` + CHECK (`IS NULL OR >= 0`). Stores the
  **per-run HR-typed value** so it carries forward; the effective withheld amount stays in the existing
  `withholding_tax` column. NULL = compute normally. Deliberately nullable (no `DEFAULT 0`) — unlike
  `director_remuneration`, 0 is a real override here, so NULL must stay distinct from 0.

## Authorization — no change intended
No role/scope/who-can-read-or-write change. The standing value rides the existing **HR-only**
employee create/update gate (`EmployeeController` → `sessions.requireAnyRole(user, "hr")`); the per-run
value rides the existing payroll gate (`PAYROLL_EDIT_ROLES = hr`). The new employee DTO field is
NON_NULL-serialized and nulled out for non-HR self-service viewers via
`EmployeeDto.withoutSensitiveSelfServiceFields()`, exactly like `salary`/`directorRemuneration`.
**Authz evidence: no authz change** (so the real-DB authz-IT requirement in CLAUDE.md does not apply).

## Files changed (path + why)
### Backend — migration
- `db/migration/V88__withholding_tax_override.sql` — new nullable columns + non-negative CHECKs (above).

### Backend — calculation core
- `payroll/PayrollCalculationInput.java` — appended nullable `withholdingTaxOverride` (resolved
  effective override, or null). Added a legacy 17-arg constructor so prior positional call sites compile.
- `payroll/PayrollCalculator.java` — after `withholdingTax` is computed: if override non-null, substitute
  `withholdingTax = money(override)` and append a `calculationNote` marker. Nothing else changed.
- `payroll/PayrollCalculation.java` — appended nullable `withholdingTaxOverride` (the applied override, or
  null) for transparency; computed projection fields unchanged.

### Backend — service / repository / DTOs
- `payroll/PayrollEmployeeSnapshot.java` — added nullable `withholdingTaxOverride` (standing value, not COALESCEd).
- `payroll/PayrollEmployeeInputRequest.java` — added nullable per-run `withholdingTaxOverride` (read raw, not via `safe()`).
- `payroll/PayrollService.java#calculateLine` — resolves precedence (per-run wins over standing, else null),
  passes the resolved override into the calc input, and persists the **per-run typed value** on the line
  (never the standing value). `suggestedInputs` carries the per-run value forward.
- `payroll/PayrollLineDto.java` — appended nullable `withholdingTaxOverride` (per-run typed value). Added a
  legacy 39-arg constructor for prior positional call sites.
- `payroll/PayrollRepository.java` — `findActiveEmployees` selects `e.withholding_tax_override` (no COALESCE);
  `findCarryForwardSuggestions` selects `pl.withholding_tax_override`; line select + `insertLine` + `mapLine`
  persist/read `payroll_line.withholding_tax_override` (raw, nullable — not via `money()` which coerces null→0).
- `payroll/PayrollCarryForwardDtos.java` — appended nullable `withholdingTaxOverride` to `SuggestedInputRow` (+ `empty()`).

### Backend — employee upsert (standing value)
- `employee/UpsertEmployeeRequest.java` — added `@DecimalMin("0.00") BigDecimal withholdingTaxOverride`
  (nullable; @DecimalMin permits null). Added a legacy constructor (pre-V88 arity) so the many positional
  test call sites compile; Jackson binds JSON via the canonical constructor, so the wire contract is unchanged.
- `employee/EmployeeDto.java` — added NON_NULL `withholdingTaxOverride`; nulled in `withoutSensitiveSelfServiceFields()`.
- `employee/EmployeeRepository.java` — create INSERT (raw, no COALESCE), `update` via `addSet` (mirrors
  director_remuneration: null = "don't change"), `baseSelect` column, and both DTO mappers.

### Frontend
- `features/employees/EmployeeFormModal.jsx` — nullable field `ภาษีหัก ณ ที่จ่าย (กำหนดเอง)` with hint
  `เว้นว่าง = คำนวณอัตโนมัติ`, near director remuneration. Blank → null; typed value (incl. 0) → number;
  stored 0 pre-fills as "0" (`?? ''`, not `|| ''`).
- `features/payroll/PayrollPage.jsx` — per-run override input in the personal-deductions section, with the
  effective ภาษีงวดนี้ shown alongside; carry-forward pre-fill; kept OUT of the numeric-key machinery
  (blank→null, not 0) and `hasPayrollInput` treats a non-null override (incl. 0) as real input.
- `api/mockApi.js` — `createEmployeeRecord` stores `withholdingTaxOverride` null-preserving (update path is
  `Object.assign`, round-trips). Mock payroll `preview`/`process` remain "not supported in mock mode" (same as
  the other reconciliation fields), so mock does not compute the override.

### Tests
- `PayrollCalculatorTest.java` — +3: substitution (withheld = override; annualTax/projections UNCHANGED;
  net reflects it), override-of-0 forces 0 withheld, null override byte-identical to no-override.
- `PayrollWithholdingTaxOverrideIntegrationTest.java` — NEW real-DB IT: standing override flows into the
  preview line (computation intact); per-run override wins over standing and is the value persisted +
  carried forward; override-of-0 through the service.
- Updated positional constructions in existing tests for the new record fields
  (`PayrollAllowanceDirectorNonTaxableIntegrationTest`, `PayrollLeaveUnpaidDeductionSeamIntegrationTest`,
  `PayrollLeaveCorrectionAutoRefundIntegrationTest`, `PayrollReprocessAndAttendanceDataFlowIntegrationTest`,
  `EmployeeServiceTest`, `EmployeeControllerTest`).
- Frontend: `EmployeeFormModal.test.jsx` (+2 override cases, +null in 3 exact-payload assertions),
  `PayrollPage.test.jsx` (+4 override cases).

## Commands run + results
- `cd frontend && npm run lint` — **pass** (0 errors; 1 pre-existing `react-hooks/exhaustive-deps` warning on
  the unrelated `load` effect, not introduced here).
- `cd frontend && npm test` — **pass, 552/552** (63 files).
- `cd frontend && npm run build` — **pass**.
- `cd backend && ./mvnw -o test -Dtest=PayrollCalculatorTest,PayrollServiceTest,PayrollControllerTest,EmployeeServiceTest,EmployeeControllerTest,PayrollExcelReconciliationTest` — **pass, 75/75**
  (PayrollExcelReconciliationTest still green = existing scenarios byte-identical).
- **Mutation-check** (calculator substitution): disabled the `withholdingTax = withholdingTaxOverride` line →
  exactly the 2 substitution tests went red, the null/regression test stayed green → reverted → all green.
- Backend `./mvnw -B clean verify` (full, incl. integration tests): **integration tests were NOT run locally**
  — no `TEST_DB_URL` and the local Docker daemon was unresponsive (`docker info` hung), so
  `AbstractPostgresIntegrationTest`'s `@EnabledIf(isAvailable)` gate would skip them here. **They run in CI via
  Testcontainers.** `PayrollWithholdingTaxOverrideIntegrationTest` compiles and mirrors the proven
  `PayrollAllowanceDirectorNonTaxableIntegrationTest` seam. **Reviewer must confirm the backend ITs pass in CI.**

## Known risks
- **Clearing the standing override to NULL via update:** `EmployeeRepository.update` uses `addSet`, which
  (like `director_remuneration`) skips null — so once set, the standing override cannot be cleared back to
  NULL through the employee update path; it can only be changed to another value. The **mock** update path
  (`Object.assign`) DOES clear it to null, so mock is more permissive than the backend on this one behaviour.
  Low impact (standing override is rarely cleared, and a per-run 0 override achieves "withhold nothing" for a
  single run), but a follow-up could add explicit clear support if HR needs it. Flagged rather than smuggled.
- **Backend ITs unverified locally** (Docker down) — see above; must be green in CI.
- Per the task, **no employee data was set** — the parent will set กัลยาณี = 5000 on UAT/prod via SQL after deploy.

## Exact next prompt for the next agent
> Review branch `feat/payroll-withholding-tax-override` (off `origin/main`, V88). Confirm the backend
> integration suite is GREEN in CI (Testcontainers) — especially `PayrollWithholdingTaxOverrideIntegrationTest`
> and the calculator substitution/regression tests — since they could not run locally (no Docker). Verify the
> tax guardrail: `annualTax`/`projectedAnnualIncome`/`taxableAnnualIncome` are unchanged and still reported
> when an override is applied (only `withholdingTax`/`totalDeductions`/`netPay` move). Rebase onto latest
> `origin/main`, re-run `frontend: npm run lint && npm test && npm run build` and `backend: ./mvnw -B clean
> verify`, then open the PR. Do NOT set any employee's `withholding_tax_override` — the owner sets กัลยาณี=5000
> via SQL after deploy.
