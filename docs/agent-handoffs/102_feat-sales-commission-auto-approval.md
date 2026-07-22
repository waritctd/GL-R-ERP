# Agent Handoff

## Task
Slice A1 of the commission redesign: extend `CommissionCalculator`/`CommissionService` to model
two Excel columns the calculator previously omitted — หัก ณ ที่จ่าย (withholding tax, subtracted)
and รับเงินเกิน (overpayment received, added back) — add a `<50,000` monthly commissionable-base
floor (pay nothing below it), and correct the live DB's `sales.tier_config` tier 13 (>3,000,000)
rate from 7.5000% to 3.2500% per the real commission policy Excel. Owner-authorized commission
business-logic change (per CLAUDE.md's sales/CRM unfreeze + this task's explicit authorization).

## Branch
feat/sales-commission-auto-approval

## Base Commit
d7fc4d7e0a21a6c07153a8d446fd573a85d04fc9 (origin/main tip, "Merge pull request #265 from
waritctd/feat/quotation-item-pagination")

## Current Commit
Not committed — working tree only, per instructions (owner commits when asked).

## Agent / Model Used
Claude Sonnet (subagent), orchestrated by a parent Claude session per this repo's
Sonnet-implements/Opus-reviews loop.

## Scope

### In Scope
- `CommissionCalculator.calculateInvoice`: add `withholdingTax`/`overpayment` params, new formula.
- `CommissionCalculator.progressiveCommission`: add the <50,000 monthly floor.
- `sales.invoice_details`: two new columns (V80).
- `sales.tier_config` tier 13 rate fix (V81, separate migration, real-payroll data change).
- All DTOs/repository/service call sites that touch invoice deduction fields.
- Real-Postgres integration tests for all four new behaviors, each mutation-checked.

### Out of Scope (explicitly not touched)
- Auto-create trigger at accountant invoice upload (Slice A2 — next).
- The manager→CEO approval chain, the sales submit endpoint's existence, or anything else in
  `CommissionService.approve`/`reject`/`createClawback`.
- Frontend commission UI/forms — this slice is backend-only; the two new fields are additive and
  optional (default 0), so no frontend contract test broke, but the commission submission/edit
  forms do not yet expose WHT/overpayment inputs to users.
- `CommissionController`'s JSON-body `submit()` endpoint needed no code change (Jackson binds new
  record fields by name automatically); only the multipart `submitMultipart()` overload needed new
  `@RequestParam`s.

## Files Changed
- `backend/src/main/resources/db/migration/V80__commission_wht_overpayment.sql` (new): adds
  `withholding_tax NUMERIC(14,2) NOT NULL DEFAULT 0` and `overpayment NUMERIC(14,2) NOT NULL
  DEFAULT 0` to `sales.invoice_details`, each with a non-negative CHECK. Forward-only.
- `backend/src/main/resources/db/migration/V81__commission_tier13_rate_fix.sql` (new): `UPDATE
  sales.tier_config SET rate_percent = 3.2500 WHERE tier_number = 13`. Deliberately separate from
  V80 — this is a real-payroll data fix, not schema, and should be reviewed/deployed independently.
- `backend/src/main/java/th/co/glr/hr/commission/CommissionCalculator.java`: `calculateInvoice`
  gains `withholdingTax`/`overpayment` params (subtract WHT, add overpayment, before the VAT
  strip); `progressiveCommission` gains the `MONTHLY_FLOOR = 50000` gate (returns 0 below it,
  tier bounds/rates unchanged above it).
- `backend/src/main/java/th/co/glr/hr/commission/TierConfig.java`: `defaults()` tier 13 corrected
  from 7.5000% to 3.2500% (in-memory fallback used only when `sales.tier_config` is empty; kept in
  sync with V81 for consistency).
- `backend/src/main/java/th/co/glr/hr/commission/InvoiceDetails.java`: added `withholdingTax`,
  `overpayment` fields (positioned after `shortfall`, before `invoiceAttachmentId`).
- `backend/src/main/java/th/co/glr/hr/commission/SubmitCommissionRequest.java`,
  `UpdateCommissionDeductionsRequest.java`, `CommissionSimulatorRequest.java`: added
  `withholdingTax`, `overpayment` fields (both `@DecimalMin("0.00")`, nullable → treated as 0).
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRepository.java`: `RECORD_SELECT` now
  selects `inv.withholding_tax, inv.overpayment`; `mapRecord` reads them into `InvoiceDetails`;
  `createInvoice` inserts them; `updateDeductions` gained two new params and persists them.
- `backend/src/main/java/th/co/glr/hr/commission/CommissionService.java`: `submit`,
  `updateDeductions`, `simulate` all pass the two new values through to `calculateInvoice`;
  `normalizedRequest` passes `withholdingTax`/`overpayment` through unchanged for every role (see
  Assumptions below — NOT zeroed for sales like transportFee/cutFee/shortfall are).
- `backend/src/main/java/th/co/glr/hr/commission/CommissionController.java`: `submitMultipart`
  gained two new optional `@RequestParam`s and passes them into the `SubmitCommissionRequest`.
- Test updates for the new 8-arg `calculateInvoice` signature, 12-arg `SubmitCommissionRequest`,
  and 15-arg `InvoiceDetails` constructors, plus the corrected tier-13 expected values:
  - `backend/src/test/java/th/co/glr/hr/commission/CommissionCalculatorTest.java`
  - `backend/src/test/java/th/co/glr/hr/commission/CommissionServiceTest.java`
  - `backend/src/test/java/th/co/glr/hr/commission/CommissionDealLinkageIntegrationTest.java`
- `backend/src/test/java/th/co/glr/hr/commission/CommissionCalculationIntegrationTest.java` (new):
  real-Postgres integration tests, see below.

## Commands Run
```bash
cd backend && ./mvnw -q -B -DskipTests compile
cd backend && ./mvnw -q -B test-compile
cd backend && ./mvnw -B test -Dtest='th.co.glr.hr.commission.*Test'
cd backend && ./mvnw -B clean verify
```
Plus four manual mutation-check cycles (see Authz Evidence / below) using `sed` to flip one guard
at a time, `./mvnw -q -B test -Dtest='CommissionCalculatorTest,CommissionCalculationIntegrationTest'`,
then restoring from a saved copy and diffing to confirm an exact revert.

## Test / Build Results
- Backend tests: **PASS** — `./mvnw -B clean verify` → `Tests run: 1048, Failures: 0, Errors: 0,
  Skipped: 0`, `BUILD SUCCESS`. Integration tests **ran** (Docker was available; Testcontainers
  Postgres was used — not skipped).
- Frontend: **not run** — this slice is backend-only (see Scope). `frontend/src/api/mockApi.js`
  and `hrApi.js` were not touched, so `contract.test.js` (method-surface parity) is unaffected; no
  frontend build/lint/test was run and none is claimed.
- Lint: no backend lint step in this repo beyond compilation; not applicable.

## Authz Evidence
No authorization change in this task. This slice does not touch any role gate, scope/filter, or
who-may-read/write-whose-rows decision — `CommissionService`'s existing role checks
(`SUBMIT_ROLES`, `MANAGER_ROLES`, `CEO_ROLES`, `hasDeductionOverride`) are unchanged. The two new
fields (`withholdingTax`, `overpayment`) were deliberately **not** added to `hasDeductionOverride`
or the sales-cannot-edit-deductions zeroing — see Assumptions below for why, and what to confirm.

## Decisions Made
- **Migration split**: V80 (schema, safe/idempotent-by-default columns) and V81 (real tier-13 rate
  change) are two separate migrations per the task's explicit instruction, so V81 can be reviewed
  and deployed on its own timeline distinct from the schema change.
- **Field placement**: `withholdingTax`/`overpayment` were appended to `InvoiceDetails` right after
  `shortfall` (keeping all money fields grouped) and to the three request DTOs at the end (minimal
  diff — avoids reordering existing positional-constructor call sites beyond what was necessary).
- **Floor semantics**: the floor gates the *entire* monthly commission to zero below 50,000, but
  tier 1 still starts at THB 0 (unchanged) once the floor is met — per the task's explicit
  instruction not to change tier bounds.
- **Integration test strategy**: used `CommissionService.simulate()` (read-only, no invoice file,
  no persisted `commission_record`) as the real-DB test vehicle instead of `submit()` — it exercises
  the exact same `CommissionCalculator.calculateInvoice()` call and the exact same
  `CommissionRepository.findTiers()` query against real `sales.tier_config` that `submit()`/
  `updateDeductions()` use, without needing a full ticket/pricing-request fixture pipeline. Chose
  exact-division gross amounts (e.g. `49999 * 1.07 = 53498.93` exactly) so the floor-boundary
  assertions have zero rounding ambiguity.

## Assumptions
- **WHT/overpayment sign convention — CONFIRMED BY OWNER (2026-07-22).** The owner inspected the
  original `.xls` formula directly (not a converted copy) and confirmed the exact row formula is
  `L = D - E - F - G - H - I - J + K`, i.e. `ยอดรับจริง = จำนวนเงิน - ค่าธรรมเนียม - ภาษีรอใช้สิทธิ -
  ค่าตัด - ค่าขนส่ง - หัก ณ ที่จ่าย - รับเงินขาด + รับเงินเกิน`, then `÷ 1.07`. This is **term-for-term
  identical** to the implemented `calculateInvoice` (WHT subtracted, overpayment added, before the
  VAT strip). The earlier flag (formulas couldn't be extracted via LibreOffice) is resolved: the
  implementation is now a verified transcription of the workbook, not a best-effort mirror. No code
  change was needed.
- **WHT/overpayment editability (FLAG FOR OWNER CONFIRMATION)**: treated like `bankFees`/
  `suspenseVat` — any submitting role (including `sales`) may set them directly on `submit()`,
  unlike `transportFee`/`cutFee`/`shortfall` which are zeroed for the `sales` role and require a
  manager/CEO override via `updateDeductions`. Rationale: WHT and overpayment appear to come off
  the invoice/payment document itself (a WHT certificate, an overpayment on the bank statement),
  similar to bank fees and VAT suspense, rather than being a managerial deduction judgment call.
  **If this is wrong** — if WHT should be manager-gated like the three deduction fields — move it
  into the `salesCannotEditDeductions` branch of `CommissionService.normalizedRequest` and add it
  to `hasDeductionOverride`; that would then be a role-gate change requiring the CLAUDE.md
  "permission changes must ship evidence" real-DB test treatment, which this slice does not
  currently have because the fields were deliberately kept out of that gate.
- Tier 13's live-DB correction (V81) is confirmed safe to apply forward-only: at authoring time the
  live DB has only 2 test commission rows, neither above the 3,000,000 threshold, so no real
  payroll numbers change retroactively.

## Known Risks
- **V81 is a real-payroll data change** and must not be silently bundled with an unrelated deploy —
  flagged in both the migration's own SQL comment and this handoff. Confirm with the owner/
  accounting team before it reaches a real-money environment (UAT/prod), even though the migration
  itself is technically safe (no rows currently affected).
- The WHT/overpayment sign convention (see Assumptions) is unverified against the source Excel
  formula. If the Excel actually nets these differently (e.g. WHT computed as a percentage of
  gross rather than a flat subtracted amount, or applied after the VAT strip rather than before),
  this slice's numbers would be wrong for any invoice that actually uses non-zero WHT/overpayment.
  Backward compatibility for the zero/zero case is proven and mutation-checked, so no existing
  invoice is at risk — only future invoices that start using the new fields.
- Commission submission/edit forms (frontend) do not yet surface `withholdingTax`/`overpayment` to
  users — until Slice A2 or a follow-up frontend task adds the inputs, these fields can only be
  populated via direct API calls (JSON body path) or left at their 0 default.

## Things Not Finished
- Slice A2 (auto-create trigger at accountant invoice upload) — see Exact Next Prompt.
- Frontend commission submission/edit UI does not expose the two new fields.
- Owner confirmation on the two flagged assumptions above is outstanding.

## Recommended Next Agent
Claude Opus review (per this repo's Sonnet-implements/Opus-reviews loop), focused on verifying the
WHT/overpayment sign convention and editability assumptions against real accounting input before
this reaches a real-money environment, then Slice A2 implementation.

## Exact Next Prompt
```
Implement Slice A2 of the commission redesign in GL-R-ERP: an auto-create trigger for commission
records at accountant invoice upload, on a new branch off origin/main (rebase first — check
docs/agent-handoffs/102_feat-sales-commission-auto-approval.md and confirm Slice A1's V80/V81
migrations and CommissionCalculator/CommissionService changes are already on main, or stack this
branch directly on feat/sales-commission-auto-approval if Slice A1 hasn't merged yet).

Before writing code: read docs/agent-handoffs/102_feat-sales-commission-auto-approval.md in full,
especially the two FLAG FOR OWNER CONFIRMATION items under Assumptions (WHT/overpayment sign
convention and editability) — get owner sign-off on those before Slice A2 builds further on top of
them, since Slice A2's auto-create logic will presumably read the same invoice_details fields.

This continues to be an OWNER-AUTHORIZED commission business-logic change and MUST ship real-DB
integration tests through the real Java service per CLAUDE.md's "Permission changes must ship
evidence" section if it touches any role/scope decision (e.g. who can trigger the auto-create, or
which accountant role can upload), and per the general "mutation-check the guard" discipline for
any new business-logic branch regardless of whether it's authz-shaped.
```

---

## Calc-Refine Slice (2026-07-22)

### Task
Owner reconciled the real commission policy Excel and confirmed two more corrections on top of
Slices A1/A2:

1. **2x weighted tier-base credit** (workbook column O): certain receipts count DOUBLE toward the
   monthly TIER BASE (not toward real cash). `adjusted tier base = SUM(net) + SUM(weighted_extra)`,
   equivalently `tier base = SUM(net * multiplier)`.
2. **Round only at final approval, not per receipt**: the monthly TIER BASE is now
   `SUM(actual_received * weight_multiplier) / 1.07` at full precision (dividing once), and ONLY
   the final `progressiveCommission` total rounds to 2dp. The per-record `commissionable_base`
   column stays 2dp for display but is no longer the summation source for the tier base.

Owner-authorized commission business-logic change (CLAUDE.md sales/CRM unfreeze + this task's
explicit authorization). Builds on Slices A1+A2 (uncommitted on this branch).

### Files Changed
- `backend/src/main/resources/db/migration/V82__commission_weight_multiplier.sql` (new): adds
  `weight_multiplier SMALLINT NOT NULL DEFAULT 1 CHECK (weight_multiplier IN (1,2,3))` to
  `sales.commission_record`. 1x default, 2x owner-confirmed, 3x storable but **NOT confirmed
  policy** (flagged in the migration comment, the request DTO Javadoc, and here).
- `backend/src/main/java/th/co/glr/hr/commission/CommissionCalculator.java`:
  - Added `TIER_BASE_SCALE = 10` and `monthlyTierBase(BigDecimal weightedActualReceived)`:
    `weighted.divide(VAT_DIVISOR, 10, HALF_UP)` — the single full-precision division point.
  - `progressiveCommission(base, tiers)` no longer pre-rounds its input to 2dp (`money(base)` →
    plain null-check). Rounding now happens exactly once, on the final total, as documented in the
    method's new Javadoc. **This is a real, observable behavior change**, not cosmetic — see
    `CommissionCalculatorTest#progressiveCommission_doesNotPreRoundAFullPrecisionBase_beforeRunningTheBrackets`,
    which demonstrates a genuine tie-breaking divergence (4,262.04 vs 4,262.05) between a
    full-precision base and its 2dp-rounded equivalent.
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRecord.java`: added `int
  weightMultiplier` component (positioned after `commissionableBase`).
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRepository.java`:
  - `RECORD_SELECT` now selects `cr.weight_multiplier`; `mapRecord` reads it.
  - Replaced `sumActiveMonthlyBase` (SUM of already-2dp `commissionable_base`) with
    `sumActiveWeightedActualReceived` (exact `SUM(actual_received * weight_multiplier)`, no
    division — the caller divides once) and added `sumActiveActualReceived` (unweighted real-cash
    total, kept available for a future team-commission slice per the task's explicit instruction
    to "expose/keep both, don't conflate them" — not yet wired into any response DTO).
  - Added `updateWeightMultiplier(commissionId, weightMultiplier)` — separate statement from
    `updateDeductions` since the column lives on `commission_record`, not `invoice_details`.
  - `createClawback` now copies the original's `weight_multiplier` instead of leaving it at the
    column default of 1 — **a correctness fix found while implementing this slice**: without it, a
    clawback of a 2x-weighted sale would only reverse 1x of the original's contribution to the
    monthly tier base, silently leaving half the original's weighted credit in place after
    "cancellation". Covered by
    `CommissionCalcRefineIntegrationTest#clawback_preservesOriginalsWeightMultiplier_...`.
- `backend/src/main/java/th/co/glr/hr/commission/UpdateCommissionDeductionsRequest.java`: added
  `@Min(1) @Max(3) Integer weightMultiplier` (null = unchanged, same pattern as every other field
  on this request). Sales has no route to this field — the endpoint is manager/CEO-only
  (`requireManagerOrCeo`), unchanged from before this slice, so this is not a new authz decision.
- `backend/src/main/java/th/co/glr/hr/commission/CommissionService.java`:
  - `updateDeductions`: resolves `weightMultiplier` (new-value-or-existing, same pattern as the
    money fields) and persists it via `commissions.updateWeightMultiplier`.
  - `simulate`: rebuilt on `sumActiveWeightedActualReceived` + `calculator.monthlyTierBase` at full
    precision; the simulated (unpersisted) invoice is folded into the weighted sum at its default
    weight of 1 *before* the single division, rather than adding an already-rounded 2dp
    `commissionableBase` onto a full-precision base. `existingMonthlyBase`/`projectedMonthlyBase`
    in the DTO are rounded to 2dp only for display; the unrounded value is what feeds
    `progressiveCommission`.
  - `payrollReadySummary`: `RepAccumulator` now tracks `weightedActualReceived` (real cash x
    multiplier, summed) and `unweightedActualReceived` (real cash only, kept separate per the
    task's instruction — not yet surfaced in `PayrollCommissionSummaryDto`, no consumer needs it
    yet). Divides once per rep via `calculator.monthlyTierBase`, same as `simulate`.
  - Added `int valueOrExisting(Integer, int)` overload.
- `backend/src/test/java/th/co/glr/hr/commission/CommissionCalculatorTest.java`: added the
  tie-breaking unit test above (calculator-level, no DB needed).
- `backend/src/test/java/th/co/glr/hr/commission/CommissionServiceTest.java`: `record()` fixture
  helper updated for the new `CommissionRecord` component (weight defaults to `1`).
- `backend/src/test/java/th/co/glr/hr/commission/CommissionAutoCreateIntegrationTest.java`: fixed
  one positional `UpdateCommissionDeductionsRequest` constructor call for the new field (`null`
  inserted before `reason`) — pre-existing Slice-A2 test, no behavior change.
- `backend/src/test/java/th/co/glr/hr/commission/CommissionCalcRefineIntegrationTest.java` (new):
  real-Postgres reconciliation suite, see below.

### Reconciliation Tests (real Postgres, real `CommissionService`)
`CommissionCalcRefineIntegrationTest` seeds real `sales.commission_record`/`invoice_details` rows
via the real `CommissionRepository` (no ticket/deal fixture chain needed — this slice doesn't touch
creation), drives 2x weighting through the real manager-review path
(`CommissionService#updateDeductions`), and reads the result back through the real
`CommissionService#simulate` (and, once, `#payrollReadySummary`) — the same aggregation a real
submission or payroll run uses. Every commission figure is asserted **exactly**, matching the
owner's hand-checked workbook totals:

| Rep | Weighting | Monthly tier base | Commission |
|---|---|---|---|
| ชนิดา | none | 801,204.00 | **4,262.04** |
| สุวรรณี | one 2x receipt | 1,800,079.50 | **18,501.59** |
| เจนเนตร | one 2x receipt | 3,587,668.62 (exercises the >3M @3.25% V81 tier) | **67,849.23** |
| ประภัสสร | none | 699,199.00 | **3,368.99** |
| อดิศักดิ์ | none | 703,717.00 | **3,402.88** |

(สุวรรณี/เจนเนตร's exact full-precision base lands a fraction of a satang from the workbook's
own 2dp-rounded "adjusted base" figure — 1,800,079.50 vs a hand-summed 1,800,079.49, and
3,587,668.62 vs 3,587,668.47 — expected, since the whole point of this slice is that full-precision
intermediate math no longer matches naive 2dp arithmetic; the **commission** figure it produces is
exact and unaffected by that fraction.)

Plus:
- **(a) Rounding-accumulation test**: 20 receipts of 1.00 THB each. Old approach (SUM of
  already-2dp-rounded `commissionable_base`) gives 18.60; new approach (full-precision, divide
  once) gives 18.6915887850 (displays as 18.69) — a real, non-cosmetic 0.09+ THB divergence from
  only 20 receipts, proven to come from the SERVICE path, not just the repository.
- **(b) Separation test**: unweighted real-cash total (150.00) vs weighted tier base (200.00,
  after one row is set to 2x) — proven to agree before weighting and diverge after.
- **(c) Mutation-check (permanent regression tests)**: `suwannee_withoutTheTwoXReview_...` and
  `jennet_withoutTheTwoXReview_...` seed the *same* receipts but skip the manager-review weighting
  step, and assert the resulting commission is materially LOWER (17,771.51 / 67,390.34) and does
  NOT equal the workbook target — proving the weighting step, not some other input, is what makes
  the reconciliation tests pass.
- `payrollReadySummary_usesWeightedFullPrecisionBase_matchingSimulate`: proves the OTHER monthly-
  base aggregation path (HR's payroll-ready view) was updated too, not just `simulate()`.
- `clawback_preservesOriginalsWeightMultiplier_...`: proves the clawback fix above.

**Live mutation-check performed on the actual code** (per CLAUDE.md, not just the permanent
regression tests above): temporarily changed `sumActiveWeightedActualReceived`'s SQL from
`SUM(actual_received * weight_multiplier)` to `SUM(actual_received)` (i.e. reintroduced "no
weighting") and reran `CommissionCalcRefineIntegrationTest` — exactly the 4 weighting-dependent
tests (`suwannee_...reconciliation`, `jennet_...reconciliation`, the separation test, the clawback
test) went red; all 7 others stayed green. Reverted; `diff` against the pre-mutation file showed
zero difference. Separately, temporarily reintroduced the `progressiveCommission` 2dp pre-rounding
(`money(base)` instead of the plain null-check) and reran `CommissionCalculatorTest` — exactly
`progressiveCommission_doesNotPreRoundAFullPrecisionBase_...` went red (4,262.05 instead of
4,262.04); all 8 others stayed green. Reverted; `diff` showed zero difference.

### Existing-Test Expectations Updated
**None.** All of Slice A1/A2's existing tests (`CommissionCalculatorTest`,
`CommissionCalculationIntegrationTest`, `CommissionServiceTest`, `CommissionDealLinkageIntegrationTest`,
`CommissionAutoCreateIntegrationTest`) passed unchanged after this slice's changes — see Test/Build
Results. This is expected: every existing test already fed `progressiveCommission` an
already-2dp-exact base with a non-tie result (per A1's own design — "chose exact-division gross
amounts... so the floor-boundary assertions have zero rounding ambiguity"), so removing the
pre-rounding step was a no-op for all of them. `CommissionServiceTest`'s `record()` fixture helper
needed a one-line addition (the new required constructor argument, `weightMultiplier = 1`) to keep
compiling — not a behavior-driven change.

### 3x-Unconfirmed Note
The `weight_multiplier` column accepts 1, 2, or 3, and a sales manager CAN set 3 through
`updateDeductions` — the column and the manager-review path make no distinction between 2x and 3x.
**Only 2x is owner-confirmed real policy.** 3x's real-world meaning (a third documented multiplier
tier? a manual override for an unusual deal? something else?) has NOT been confirmed. This is
flagged in three places: the V82 migration comment, `UpdateCommissionDeductionsRequest`'s Javadoc,
and here. **Do not treat a 3x row as verified business policy without owner sign-off** — if a
manager sets 3x in production before that confirmation, the resulting commission number is
unverified against any known workbook rule.

### Commands Run
```bash
cd backend && ./mvnw -q -B -DskipTests compile
cd backend && ./mvnw -q -B test-compile
cd backend && ./mvnw -B test -Dtest='th.co.glr.hr.commission.*Test'
cd backend && ./mvnw -B test -Dtest='CommissionCalcRefineIntegrationTest'
cd backend && ./mvnw -B test -Dtest='CommissionCalculatorTest'
cd backend && ./mvnw -B clean verify
```
Plus two manual mutation-check cycles (see above) — edit → rerun the affected test class → confirm
exactly the expected tests go red and nothing else → restore from a saved copy → `diff` confirmed
byte-identical revert.

### Test / Build Results
- `th.co.glr.hr.commission.*Test` (all pre-existing + new commission tests, excluding nothing):
  **PASS**, `Tests run: 59` (48 pre-existing + 11 new in `CommissionCalcRefineIntegrationTest`),
  `Failures: 0, Errors: 0, Skipped: 0`.
- `CommissionCalculatorTest` alone (incl. the new tie-breaking unit test): **PASS**, 9/9.
- Full backend suite: `./mvnw -B clean verify` → **PASS** — `Tests run: 1077, Failures: 0, Errors:
  0, Skipped: 0`, `BUILD SUCCESS`, all jacoco coverage checks met, total time 4:14 min.
- Integration tests **ran** (Docker available; Testcontainers Postgres used, golden-template clone
  path, forkCount=2 parallel — not skipped, not the external `TEST_DB_URL` path).
- Frontend: **not run** — this slice is backend-only, no `frontend/` files touched, `hrApi.js`/
  `mockApi.js` method surfaces unchanged (only new fields on existing request/response shapes),
  so `contract.test.js` is unaffected. Consistent with A1/A2.

### Authz Evidence
No new authorization decision. `weightMultiplier` is set through `CommissionService#updateDeductions`,
whose role gate (`requireManagerOrCeo` — `sales_manager`/`ceo` only) is **unchanged** from before
this slice; sales already had no route to that endpoint. Per CLAUDE.md, a field addition to an
existing manager-only endpoint's request body is not itself a role/scope change requiring the
heavier real-DB authz-evidence protocol — but since the manager-review path IS exercised for real
in `CommissionCalcRefineIntegrationTest` (real `CommissionService.updateDeductions` calls with a
real `sales_manager` `UserPrincipal` against real Postgres), the new field's persistence and
recompute behavior is verified through the real service regardless.

### Known Risks
- **3x is unconfirmed** (see above) — the column/UI (once a frontend exists) should probably not
  expose 3x as a normal option until the owner confirms its meaning.
- **The `progressiveCommission` no-pre-round change is a genuine (if narrow) behavior change** for
  any OTHER caller of `CommissionCalculator` that might exist outside this codebase's own
  `CommissionService` (none currently do) — a caller that relied on the base being silently
  2dp-rounded before bracket math would now see full-precision behavior instead. Not a risk in this
  codebase today, but worth remembering if `CommissionCalculator` is ever reused elsewhere.
- **`sumActiveActualReceived`/`RepAccumulator.unweightedActualReceived`** are plumbed but not yet
  surfaced in any DTO — the "future team-commission slice" mentioned in the task will need to wire
  one of these into a response, not re-derive it from scratch.
- Slices A1's two flagged owner-confirmation items (WHT/overpayment sign convention and
  editability) remain outstanding from before this slice — unrelated to calc-refine, not resolved
  here.

### Things Not Finished
- A3 (frontend): commission submission/edit UI does not yet expose `weightMultiplier` to sales
  managers, and the simulator/payroll views don't yet reflect the full-precision base display
  change. See Exact Next Prompt.
- 3x confirmation from the owner is outstanding (see 3x-Unconfirmed Note).

### Recommended Next Agent
Claude Opus review (per this repo's Sonnet-implements/Opus-reviews loop) of the calc-refine slice —
particularly the reconciliation test numbers and the `progressiveCommission` pre-rounding removal —
before A3 (frontend) builds on top of it, and before this branch merges.

### Exact Next Prompt
```
Implement A3 (frontend) of the commission redesign in GL-R-ERP, on feat/sales-commission-auto-approval
(or a branch stacked on it if this slice has merged — check
docs/agent-handoffs/102_feat-sales-commission-auto-approval.md's latest section first, and confirm
Slices A1/A2/calc-refine are on main or this branch before starting).

Before writing code: read the calc-refine section of handoff 102 in full, especially the 3x-
unconfirmed note (do not expose 3x as a routine option in the UI without an explicit callout that
it is unconfirmed policy) and the "round only at final" display convention (existingMonthlyBase/
projectedMonthlyBase in CommissionSimulationDto, and commissionableBase in
SalesRepCommissionSummaryDto, are already rounded to 2dp for you server-side — do not re-round or
re-derive them client-side).

Scope: expose weightMultiplier (1/2/3, defaulting to 1) as a manager-review field on the commission
detail/edit view (PATCH /api/commissions/{id}/deductions already accepts it, manager/CEO-gated,
requires the existing `reason` field like every other edit on that endpoint) — likely a small
select/stepper next to the existing deduction fields, with a visible "2x confirmed, 3x unconfirmed"
hint per the handoff note. No other commission workflow logic changes.

This continues to be an OWNER-AUTHORIZED commission business-logic change per CLAUDE.md's sales/CRM
unfreeze. Per CLAUDE.md: run `cd frontend && npm run lint && npm test && npm run build` and record
results (there is no typecheck script). Since this is a frontend-only slice with no new role gate
(the endpoint's authz is unchanged), no new real-DB authz test is required — but if you find
yourself adding any new permission check, treat it as an authz change and follow CLAUDE.md's
"Permission changes must ship evidence" section.
```

---

## A3 Slice (2026-07-22) — Frontend

### Task
Implement the commission-redesign frontend end to end: sales becomes read-only, account gets a new
"record invoice / create from deal" flow (`POST /api/commissions/from-deal`), sales_manager/ceo get
a review queue that edits any invoice input (incl. `weightMultiplier`) with a required reason and a
live recomputed preview, and the mock (`mockApi.js`) is brought into parity with everything Slices
A1/A2/calc-refine already shipped on the backend but the frontend never got (WHT/overpayment, the
<50,000 floor, the V81 tier-13 rate, weight-multiplier-aware monthly aggregation, "round only at
final"). Owner-authorized commission business-logic/UI change per CLAUDE.md's sales/CRM unfreeze.

### Files Changed
- `frontend/src/features/commissions/commissionCalc.js` (new): the single source of truth for
  commission math on the frontend — `invoiceCalculation` (VAT-strip formula incl. WHT/overpayment),
  `monthlyTierBase` (full-precision, divides once), `tierBreakdown`/`progressiveCommission` (13
  tiers incl. the V81 3.25% tier-13 rate, the <50,000 floor, "round only at final"). Mirrors
  `CommissionCalculator.java`/`TierConfig.java` exactly (verified against the handoff's own
  reconciliation table — see Mock-Calc Parity below). Imported by both `mockApi.js` and
  `CommissionPage.jsx` so the two can never drift from each other.
- `frontend/src/features/commissions/CommissionPage.jsx` (rewritten): role-based rendering —
  see UI Differences below. Kept the file's existing conventions (bare `page-stack`/`panel`/
  `form-grid` shared classes + Tailwind utilities for new markup, `DataTable` with `mobileCard`,
  `ConfirmDialog`) rather than introducing a new component vocabulary.
- `frontend/src/api/routes.js`: added `commissions.createFromDeal` route. `ROLE_PERMISSIONS`:
  added `account` to `canViewCommissions` (route access only — see below), added
  `canListCommissionRecords` (`sales`/`sales_manager`/`ceo`, mirrors `CommissionController#list`'s
  `PreAuthorize` exactly — deliberately excludes `account` and `hr`), replaced `canSubmitCommissions`
  with `canCreateCommissionFromDeal` (`account` only, mirrors `CommissionService.CREATE_FROM_DEAL_ROLES`).
  `canApproveCommissions`/`canViewPayrollCommissions` unchanged.
- `frontend/src/api/hrApi.js`: added `commissions.createFromDeal(payload)` (multipart POST, same
  shape as the existing `create` multipart path plus `ticketId` and no `salesRepId`/`sourceTicketId`
  — the server always resolves the rep from the deal). Added a comment on `updateDeductions`
  clarifying `weightMultiplier` rides through the existing generic JSON-body passthrough with no
  code change needed.
- `frontend/src/api/mockApi.js`: full parity pass —
  - `list()`: unchanged (already matched `CommissionController#list`'s `SALES`/`SALES_MANAGER`/`CEO`
    gate); added a comment pinning that down explicitly.
  - `create()`: role gate changed `sales,sales_manager,ceo` → `account,sales_manager,ceo`
    (mirrors `SUBMIT_ROLES`); dropped the now-dead `sales`-zeroing branches; added
    `withholdingTax`/`overpayment` to the built `invoiceDetails`.
  - `createFromDeal()` (new): mirrors `CommissionService#createFromDeal` — resolves `salesRepId`
    from the ticket's `createdById` (never from the payload), the `hasActiveCommissionForTicket`
    duplicate guard (one live SALE commission per deal), the CLOSED_PAID gate + cross-check
    snapshot, defaults `grossAmount` from `payableAmount(ticket)` when omitted, and dual-writes an
    `INVOICE`-type ticket attachment (`mockAttachments`) the same way the real service does, so
    `hasInvoiceAttachment`/`invoiceOnFile` becomes true from this one upload.
  - `updateDeductions()`: now accepts and value-or-existing's every invoice field (not just the
    three deduction fields) plus `weightMultiplier` (clamped to 1/2/3, scoped to only the specific
    `commissionId`, never shared across a clawback pair — mirrors
    `CommissionRepository#updateWeightMultiplier` being keyed on `commission_id` vs. the amount
    fields keying on `invoice_id`), and now requires a non-blank `reason` (400 otherwise) —
    mirrors `UpdateCommissionDeductionsRequest`'s `@NotBlank reason`.
  - `clawback()`: unchanged logic, but now correctly carries `weightMultiplier` over from the
    original via the existing `structuredClone(original)` spread (no code change needed — added a
    comment explaining why this already mirrors the calc-refine clawback fix).
  - `simulate()`/`payrollReady()`: rewritten to aggregate `SUM(actualReceived * weightMultiplier)`
    and divide once via `calcMonthlyTierBase`, instead of summing already-2dp `commissionableBase`
    rows — mirrors `CommissionService#simulate`/`#payrollReadySummary` exactly.
  - `invoiceCalculation`/`progressiveCommission` local functions replaced with re-exports of the
    shared `commissionCalc.js` implementation (see above).
- `frontend/src/App.test.jsx`: updated the mocked `ROLE_PERMISSIONS` fixture to match the renamed/
  added keys (`canListCommissionRecords`, `canCreateCommissionFromDeal`); no route-guard test
  actually exercises `/commissions`, so this is cosmetic-but-accurate, not a behavior change.
- `frontend/src/api/contract.test.js`: unchanged — the existing generic method-surface tests cover
  `createFromDeal` automatically since it now exists symmetrically on both `hrApi` and `mockApi`.

### New API Methods
- `hrApi.commissions.createFromDeal(payload)` / `mockApi.commissions.createFromDeal(payload)` —
  multipart POST to `/api/commissions/from-deal`. `payload`: `ticketId` (required), `invoiceNumber`,
  `invoiceDate`, `invoiceAttachment` (required), `grossAmount` (optional — defaults from the deal),
  `bankFees`/`suspenseVat`/`transportFee`/`cutFee`/`shortfall`/`withholdingTax`/`overpayment`
  (optional, default 0).

### How Each Role's UI Differs
- **sales** (read-only): sees only their own commissions (server-scoped), by payroll month, with
  totals. No create/edit/approve controls anywhere — the `actions` table column is omitted
  entirely for this role (not just hidden-but-present). Each row has a "ดูรายละเอียดการคำนวณ"
  toggle that expands a waterfall (gross → each deduction incl. WHT → actualReceived → ÷1.07 →
  commissionableBase, weight-multiplier badge when >1, status + manager/CEO approver names/dates).
  Sales-only "ขั้นบันไดค่าคอมเดือนนี้ (ประมาณการ)" panel above the table shows the tier-by-tier
  breakdown for the currently-visible (own) records' weighted monthly base — explicitly labeled
  "ประมาณการ" (estimate) since HR's payroll run is the authoritative source, not this client-side
  mirror. Verified live in the mock: a 2x-weighted ฿583,177.57 base → ฿1,166,355.14 weighted base →
  ฿8,329.44 commission, matching `commissionCalc.js` computed independently via a Node script.
- **account**: a "record invoice / create from deal" panel, not a commission list (the real backend
  has no `GET /api/commissions` route for `account` at all — see `canListCommissionRecords`
  in routes.js — so no list is fetched for this role). Flow: enter a Ticket ID → "โหลดข้อมูลดีล"
  fetches `api.tickets.get(ticketId)` directly (unaffected by the account-role *list* scoping — see
  Known Risks) → verifies `salesStage === 'CLOSED_PAID'` client-side (also re-verified server-side)
  → prefills Gross Amount from the deal's `amountPayable` (still editable) → manual entry of
  invoiceNumber/invoiceDate/all deduction fields (incl. WHT/overpayment) → upload the tax invoice →
  submit calls `createFromDeal`. A best-effort `<select>` of `salesStage=CLOSED_PAID` tickets is
  also offered (via the existing `tickets.list` endpoint) for when it happens to be populated. A
  session-only "บันทึกล่าสุดในเซสชันนี้" list echoes back what was just created (no `GET` needed).
- **sales_manager / ceo**: the same records table as before (all reps, not self-scoped), now with
  an added expand-detail toggle (same waterfall component as sales) and an "แก้ไขค่าหัก" (edit)
  panel that exposes every invoice input — grossAmount, bankFees, suspenseVat, transportFee,
  cutFee, shortfall, withholdingTax, overpayment — plus a `weightMultiplier` select (1/2 เท่า with
  no caveat, 3 เท่า flagged "ยังไม่ยืนยันนโยบาย" per the calc-refine 3x-unconfirmed note), a live
  recomputed ยอดรับจริง/ฐานค่าคอม preview (client-side `invoiceCalculation`, purely for display —
  the server always recomputes independently and authoritatively), and a required "เหตุผลในการแก้ไข"
  reason field (Save is disabled until non-empty; this closes a **pre-existing bug** — the old inline
  edit panel called `updateDeductions` without ever sending a `reason`, which the real backend has
  required with `@NotBlank` since Slice A2, so every prior manager edit would have 400'd against the
  real Java service even though it worked against the un-updated mock). Approve/reject/clawback
  unchanged in behavior (manager approves SUBMITTED→MANAGER_APPROVED, CEO approves
  MANAGER_APPROVED→APPROVED, clawback only on APPROVED SALE records) — confirmed working live: a
  manager-approved-then-CEO-approved record correctly shows both approver names/dates and the
  weight badge throughout.
- **hr** (`canViewPayrollCommissions`): unchanged `PayrollSummary` view (payroll-ready totals per
  rep for the month) — now fed by the corrected weighted/full-precision `payrollReady` aggregation.

### Mock-Calc Parity Note
`commissionCalc.js` was verified independently (Node script, not just visual inspection) against
every number in the calc-refine section's reconciliation table before wiring it into the mock:
`progressiveCommission(801204) === 4262.04`, `progressiveCommission(1800079.50) === 18501.59`,
`progressiveCommission(3587668.62) === 67849.23` (exercises the >3,000,000 V81 tier), plus the
<50,000 floor (`49999` → `0`, `50000` → `125`). All matched exactly. Live end-to-end verification in
the running mock app (see Test/Build Results) additionally reproduced a 2x-weighted scenario
(583,177.57 × 2 = 1,166,355.14 weighted base → 8,329.44 commission) with the UI and an independent
Node computation agreeing to the cent. Per CLAUDE.md: this is the calculation being mirrored
faithfully, not an authorization claim — the mock's role gates approximate the Java service and are
not authoritative (see Authz Evidence below).

### Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
```
Plus live manual verification against a real running `VITE_USE_MOCKS=true` dev server (see below).

### Test / Build Results
- **Lint**: PASS — 0 errors, 1 warning (`PayrollPage.jsx:217`, pre-existing, unrelated to this
  slice — not introduced or touched here).
- **Test**: PASS — `Test Files 48 passed (48)`, `Tests 418 passed (418)`, including
  `src/api/contract.test.js` (method-surface parity, `createFromDeal` covered automatically) and
  `src/app/permissions.test.js` (26 tests, unaffected by the `ROLE_PERMISSIONS` key changes since
  none of those 26 cases touch the commission keys). No `CommissionPage.test.jsx` exists in this
  repo to update (confirmed via search before starting).
- **Build**: PASS — `vite build` succeeds, `CommissionPage-*.js` chunk 35.03 kB (8.32 kB gzip).
- **Live manual verification** (Browser pane, `VITE_USE_MOCKS=true`, a temporary standalone dev
  server on port 5201 since the shared `frontend-mock` port 5200 was already running another
  session's stale build — never disturbed that session): logged in as **account**, looked up real
  demo ticket 14 (IconSiam Riverside, CLOSED_PAID, ฿624,000 payable, no prior invoice), created a
  commission via `createFromDeal` (verified the resulting `actualReceived`/`commissionableBase` =
  624,000 / 624,000÷1.07 = 583,177.57 exactly) → logged in as **sales_manager**, saw it in the
  review queue, set `weightMultiplier=2` with a reason, saved (verified the live preview matched
  the eventual persisted value), approved it (SUBMITTED→MANAGER_APPROVED) → logged in as **ceo**,
  approved it again (→APPROVED) → logged in as **sales** (the deal's actual owner, คุณสมหมาย
  ขายดี), confirmed the read-only view: no action buttons, correct waterfall, correct weight badge,
  correct monthly tier panel (1,166,355.14 base → 8,329.44 commission, independently verified via
  Node). Also confirmed the mobile card layout (375×812) renders the same information with a
  working expand toggle. Two real bugs were caught and fixed during this pass — see Known Risks/
  Bugs Fixed below; both are now covered by the fact that this exact flow was replayed successfully
  after each fix.

### Authz Evidence
**Unverified beyond mock** — per CLAUDE.md, this is explicitly called out rather than glossed over.
This slice is UI-only; it does not add, remove, or change any backend role gate (all
`@PreAuthorize`/`requireRole` decisions were already shipped and tested in Slices A1/A2/calc-refine,
which this slice's handoff sections document as having real-DB integration test coverage — see
those sections above). What this slice *does* do is make the **frontend** correctly reflect gates
that already existed in the Java service (e.g., sales no longer gets a submit UI; account gets a
create-from-deal UI restricted to what `createFromDeal`'s `@PreAuthorize("hasRole('ACCOUNT')")`
already enforced server-side). The manual verification above was against `VITE_USE_MOCKS=true`
only — it proves the UI *calls the right endpoints with the right roles and renders correctly*, not
that the real Java service enforces them (that was already proven in A1/A2/calc-refine's own
real-Postgres integration tests, not repeated here). No new permission check was added anywhere in
this slice, so CLAUDE.md's "Permission changes must ship evidence" real-DB-test requirement does not
apply here — but if any future slice adds one, that discipline must restart.

### Known Risks / Bugs Fixed During Implementation
- **Fixed: `api.tickets.get()` response unwrapping.** The account ticket-lookup flow originally read
  `response.summary` directly; both the mock and the real `TicketController#get` actually return
  `{ ticket: { summary: {...}, ... } }` (`TicketResponses.TicketDetailResponse`). Caught live via
  manual testing (`Cannot read properties of undefined (reading 'salesStage')`), fixed to
  `response.ticket?.summary` with a graceful "ไม่พบดีลนี้" fallback if still absent.
  **This shape is shared with the real backend, not mock-specific — confirmed by reading
  `TicketController.java`/`TicketResponses.java` directly, not inferred from the mock.**
- **Fixed: nested `<button>` accessibility bug.** An earlier draft used `DataTable`'s `onRowClick`
  to toggle the calc-detail panel, which makes `DataTable` render every row as a `<button>` — but
  the `actions` column (sales_manager/ceo) already renders several `<button>` icon-buttons inside
  each row, so this would have nested buttons (invalid HTML, unreliable click semantics). Fixed by
  using a dedicated expand-toggle column/button instead of `onRowClick`, keeping `renderExpanded`
  (which does not depend on `onRowClick`). Caught in code review before manual testing, not by a
  failing test — there is no automated test coverage for this specific DOM-nesting class of bug in
  this repo; worth keeping in mind for any future `DataTable` + `renderExpanded` + actions-column
  combination.
- **Account's ticket-list scoping quirk (pre-existing, not fixed, worked around in the UI):**
  `TicketRepository#appendRoleScope` (real backend) / `accountListScopeIncludes` (mock) narrows
  `GET /api/tickets` for the `account` role to deals with money still *pending* (`payment_status IN
  (DEPOSIT_NOTICE_ISSUED, AWAITING_FINAL_PAYMENT)` or overdue). A `CLOSED_PAID` deal — final payment
  already confirmed — normally falls *out* of that scope, which is exactly the deal account now
  needs to record an invoice for post-A2. This is a **real interaction gap between a pre-existing
  Phase B feature and the new A2 trigger**, not something this UI-only slice is authorized to fix
  (backend Java is explicitly out of scope for this task, and the underlying repository/service
  logic predates this slice). Worked around by making the account "record invoice" flow primarily a
  direct ticket-ID lookup (`api.tickets.get`, which has no such scoping — confirmed by reading
  `TicketService#requireViewAccess`, which only checks `VIEWER_ROLES` + sales ownership, not the
  list-scope predicate) rather than depending on the scoped list dropdown, which is offered only as
  a best-effort convenience. **Flagging for a future slice**: either loosen
  `appendRoleScope`/`accountListScopeIncludes` to also include `CLOSED_PAID` deals without an
  invoice on file, or accept that ticket-ID lookup (e.g. via a deep link from a future
  TicketDetailPage "record invoice" CTA — not built here, out of scope) is the intended primary
  path. The `/commissions?ticketId=NN` query param is already wired up for that future deep link.
- **3x weight multiplier**: exposed in the manager-review `<select>` with an explicit "ยังไม่ยืนยัน
  นโยบาย" (policy not yet confirmed) badge/hint whenever selected — per the calc-refine slice's
  explicit instruction not to present it as a routine option. Not blocked from being set (matches
  backend, which also only flags rather than blocks it).
- **No simulator UI in this slice**: the pre-existing `POST /api/commissions/simulator` endpoint
  (`hrApi`/`mockApi` `commissions.simulate`) is untouched and still callable, but no control in the
  new `CommissionPage.jsx` triggers it. Reasoning: its real-backend role gate is
  `SALES`/`SALES_MANAGER`/`CEO` — **not** `account` — so wiring it into account's create-from-deal
  form (the only role with an actual "create" UI left after this slice) would have been calling an
  endpoint account has no access to. Sales no longer submits, so the simulator's original use case
  (preview before you submit) no longer applies to any role with both a submit UI and simulator
  access in this redesign. Left as an unused-but-present API method (contract-test-covered) rather
  than removed, since removing it would be an unrelated regression to a still-valid backend
  capability.
- **No manual/unlinked commission-creation UI for sales_manager/ceo**: `create()`
  (JSON/multipart submit, still allowed server-side for `account`/`sales_manager`/`ceo` per
  `SUBMIT_ROLES`) has no UI trigger in this slice — the task's role-by-role scope only specified
  account's create-from-deal flow, a sales_manager review queue, and ceo approval, none of which
  described a manual-entry creation form. If sales_manager/ceo need to create an unlinked/manual
  commission record (e.g. a correction), that capability exists on the backend and in both
  `hrApi`/`mockApi` but has no UI path today — flagged as a possible follow-up, not built here since
  it wasn't in scope.

### Things Not Finished
- The account ticket-list scoping gap above (needs a product decision: loosen the backend scope, or
  formalize the ticket-ID-lookup-first UX with a real deep-link entry point from a future
  TicketDetailPage "record invoice" action).
- A manual/unlinked commission-creation UI for sales_manager/ceo, if wanted (see above).
- Track B (weekly-report elimination) — see Exact Next Prompt.

### Recommended Next Agent
Claude Opus review (per this repo's Sonnet-implements/Opus-reviews loop) of the full A3 slice —
particularly the mock/backend calc parity claims and the account ticket-lookup workaround — before
this branch merges, then Track B implementation.

### Exact Next Prompt
```
Implement Track B of the commission redesign in GL-R-ERP: eliminate the weekly commission report
(or whatever manual/spreadsheet process A3's real-time commission pipeline was meant to replace —
check with the owner for the exact current weekly-report mechanism and what "elimination" means
concretely: a scheduled export, a dashboard, or simply confirming the new pipeline is now the
system of record and the manual report can stop). Branch off origin/main (rebase first — check this
handoff for whether feat/sales-commission-auto-approval has merged) or stack on
feat/sales-commission-auto-approval if it has not yet merged.

Before writing anything: read this handoff (102) in full, especially the A3 section's Known Risks
(the account ticket-list scoping gap in particular — Track B work may be a natural place to also
fix TicketRepository#appendRoleScope's account scope to include CLOSED_PAID deals without an
invoice on file, closing that gap properly with a real-DB test, if the owner agrees it's in scope).

This continues to be an OWNER-AUTHORIZED commission-adjacent change. Per CLAUDE.md: if it touches
any role/scope decision, ship a real-DB integration test through the real Java service (see
CLAUDE.md's "Permission changes must ship evidence"); run `cd frontend && npm run lint && npm test
&& npm run build` and `cd backend && ./mvnw -B clean verify` and record exact results.
```
