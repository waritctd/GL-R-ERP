# Agent Handoff

## Task
Audit the payroll withholding-tax engine against official Thai law and fix what diverges.

Owner-requested scope (chosen explicitly, 2026-07-28):
- Core ป.96/2543 fix — **in scope**
- ค่าลดหย่อน (allowance) layer — **in scope**
- แบบ ล.ย.01 effective dating — **in scope**
- Reclassifying หักตามใบเตือน / หักลูกค้าคืนสินค้า out of pre-tax — **explicitly OUT of scope**
  (needs the accountant's sign-off; it changes reported income on ภ.ง.ด.1 and 50 ทวิ)

## Branch
`fix/payroll-wht-po96-compliance`

Worked in a dedicated worktree at `/Users/ploy_warit/Desktop/GL-R-ERP-payroll-tax`. The primary
worktree was on `fix/ui-contrast-tokens` with 256 uncommitted changes from a concurrent session and
was deliberately left untouched.

## Base Commit
`d2b0b066` (origin/main, after rebase 2026-07-29). Originally based on `c5e46189`; fast-forwarded once
the work was complete because the branch carries no commits of its own yet.

The four incoming commits (`d72a146b`, `8598da67`, `6645ced5`, `d2b0b066` — WCAG contrast tokens and
payroll-screen hardening) are **frontend-only plus DESIGN.md**, and this branch is backend + handoff,
so there was no file overlap and no conflict. `./mvnw -B clean verify` re-run on the new base:
**1276 tests, 0 failures, 0 skipped, BUILD SUCCESS** on Testcontainers.

Note one of them, `6645ced5 fix(ui): harden the payroll screen`, touches `PayrollPage.jsx` — the same
screen that will eventually need the input fields listed in risk 5. Whoever builds those should start
from the rebased version, not the pre-rebase one.

## Current Commit
`1eff276e` — committed 2026-07-29 on the owner's instruction, 29 files, +4106/−97.

**NOT pushed. No PR opened.** The owner asked for the commit only. `origin` has never seen this
branch.

## Agent / Model Used
Claude Opus 5 throughout. The standing Sonnet-implements/Opus-reviews loop could not be used — the
Sonnet delegation failed with "claude-sonnet-5 is temporarily unavailable" — so Opus implemented, and
independent Opus reviewers audited each pass.

**Four independent review passes, all REJECT, all correct.** Pass 1 and 2 found arithmetic defects
(the clawback, the withholding ceiling, FLOOR/CEILING, the unwrapped payslip note). Pass 3 found the
allowance cap had been destroyed by the previous pass's own fix. Pass 4 found no new arithmetic defect
— *"the engine's math survived every attack I mounted"* — and rejected on documentation stating the
classification backwards plus a payslip string that was false for eight months of the year. Pass 5 is
the review of the remediation of pass 4. Every reviewer finding cited in this document was
independently reproduced before being accepted.

The methodological lesson, recorded because it recurred three times: **the arithmetic was never the
hard part.** Two of the four rejections were for the branch's own comments and documents describing
behaviour the code did not have — including one that would have led the next maintainer to revert the
commission correction. A third was for a "fix" that defeated the control it was protecting.

## Legal basis

Primary sources, all Revenue Department:

| Source | Used for |
|---|---|
| [คำสั่งกรมสรรพากร ที่ ป.96/2543](https://www.rd.go.th/3558.html) | the governing order for ม.50(1) withholding on 40(1) |
| [แบบ ภ.ง.ด.1 + คำชี้แจง (พิมพ์ มี.ค. 2560)](https://www.rd.go.th/fileadmin/tax_pdf/withhold/200360_WHT1.pdf) | ข้อ 2.1–2.12 — the clause-level text every change below cites |
| [การหักค่าใช้จ่าย](https://www.rd.go.th/556.html) | 50% capped 100,000, **shared** across 40(1)+40(2) |
| [ใบแสดงสิทธิฯ 65 ปีขึ้นไป / คนพิการ](https://www.rd.go.th/fileadmin/tax_pdf/pit/65year_040161.pdf) | proves the 190,000 exemption is taken BEFORE ค่าใช้จ่าย |
| [กฎกระทรวง ฉบับที่ 126](https://www.rd.go.th/2502.html) | the 190,000 exemption itself |
| [ราชกิจจาฯ เพดานประกันสังคม 1 ม.ค. 2569](https://www.humansoft.co.th/th/blog/pay-social-security) | confirms the existing 17,500 / 875 / 10,500 constants are correct |
| [SSF (ยกเลิกแล้ว)](https://www.itax.in.th/pedia/ssf/) | SSF not deductible from ปีภาษี 2568 |

The clauses that drive the code:

- **ข้อ 2.1** regular pay × จำนวนคราวที่ต้องจ่าย (×12 monthly). Mid-year joiner uses the payments
  actually remaining — the RD's own example is a 1 April start = 9 ครั้ง.
- **ข้อ 2.2** less ค่าใช้จ่าย and ค่าลดหย่อน, taxed under ม.48(1). Allowances come from แบบ ล.ย.01 and
  may be applied from January; **เงินบริจาค is the one exception** — only once actually paid. A
  mid-year change applies from the period notified.
- **ข้อ 2.3** annual tax ÷ จำนวนคราว = each period's withholding; the division remainder goes into the
  last withholding of the year.
- **ข้อ 2.5** เงินพิเศษที่จ่ายเป็นครั้งคราว (names ค่าล่วงเวลา and เงินโบนัส): × the number of times
  THAT payment is made, added to annualised regular income, retaxed, and the **difference** withheld.
- **ข้อ 2.9 / 2.10** December, and a leaver's final period, may be trued up to the actual liability.

## What was wrong (measured, not asserted)

Both defects were reproduced by running the **real compiled `PayrollCalculator`** over full
simulated tax years before any change was made.

**P1 — ข้อ 2.5 violated.** `projectedAnnualIncome = ytd + grossTaxableIncome × monthsRemaining`
multiplied *everything* — bonus, OT, commission — by the months remaining.

```
฿50,000 salary + ฿100,000 bonus in June
  m6  projected ฿1,300,000   withheld ฿19,836.31     <- bonus multiplied by 7
  ข้อ 2.5 answer: 1,704.17 + (31,925.00 - 20,450.00) = ฿13,179.17
```

**P1 — over-withholding was permanent.** `remainingAnnualTax.max(ZERO)` is one-directional, so a
late-year bonus could never be given back.

```
฿50,000 salary + ฿500,000 bonus in November
  year withheld ฿119,708.34   actual liability ฿100,900.00
  m12 clamped to 0.00 -> ฿18,808.34 recoverable only via ภ.ง.ด.91
```

**P2** — no ข้อ 2.10 handling for leavers (later found unbuildable — see below); **P2** — กองทุนสำรองเลี้ยงชีพ absent entirely; **P2** —
บุตร and อุปการะคนพิการ uncapped; **P3** — SSF still deductible; **P3** — no 190,000 exemption;
**P3** — no ล.ย.01 effective dating.

## After the fix (same simulations, real compiled class)

```
A. ฿100,000 bonus in JUNE        m6 withheld ฿13,179.16   year total ฿31,925.00  (exact liability)
B. ฿500,000 bonus in NOVEMBER    m11 withheld ฿82,154.17  year total ฿100,900.00 (was ฿119,708.34)
C. salary only, no เงินพิเศษ      year total ฿20,450.00    (unchanged — regression safe)
```

Both invariants held in every month of every simulation:
`regularTaxableIncome + variableTaxableIncome == grossTaxableIncome` and
`regularWithholdingTax + variableWithholdingTax == withholdingTax`.

## Files changed

### Core ป.96 (V92)

| File | Change |
|---|---|
| `backend/.../payroll/PayrollCalculator.java` | Split income into the ข้อ 2.1 regular limb (salary + **พิเศษ 6 / commission** + director remuneration) and the ข้อ 2.5 variable limb (**พิเศษ 1–5, 7, 8** + OT). **CLASSIFICATION CORRECTED 2026-07-29 ON THE OWNER'S STATEMENT — see below.** Regular is annualised; variable enters at its actual cumulative amount and is taxed as the difference between two ข้อ 2.2 passes. Single outer `max(0, …)` clamp replaces the inner one. New `assessAnnualTax` helper. |
| `PayrollCalculation.java` | +9 fields: the two income limbs, the two withholding limbs, annual regular/variable income, regular/variable annual tax, `remainingPayPeriods`. |
| `PayrollCalculationInput.java` | +`taxYear`, +`remainingPayPeriods`, +`taxpayerAge`, behind legacy 18/20-arg constructors. |
| `PayrollYearToDate.java` | Now carries both limbs of income and withholding. Legacy 3-arg constructor attributes everything to the regular limb. |
| `PayrollLineDto.java` | +4 persisted limb fields, behind a legacy 40-arg constructor. |
| `PayrollService.java` | `remainingPayPeriods(payrollMonth)`, `taxpayerAge(dob, month)`, `headCountFor(...)`, passes tax year + limbs through. The ข้อ 2.10 leaver cap was implemented and then **removed** — see below. |
| `PayrollRepository.java` | YTD query sums the limbs **separately**; insert/select carry them. |
| `V92__payroll_withholding_regular_variable_split.sql` | 4 limb columns on `payroll_line`, with backfill. **No columns on `payroll_year_to_date_seed`** — see the migration comment and caught-bug 1 below. |

### Allowances + ล.ย.01 (V93)

| File | Change |
|---|---|
| `PayrollCalculator.java` | กองทุนสำรองเลี้ยงชีพ added (15% / 500,000, taken **first** in the 500,000 cluster). Per-head caps on บุตร (30,000, doubled for 2nd+ born from 2561) and อุปการะคนพิการ (60,000). SSF zeroed from ปีภาษี 2568. ยกเว้นเงินได้ 190,000 for 65+/disability-card holders, applied **before** the 50% expense deduction. |
| `PayrollTaxAllowanceInput.java` | +`providentFundAllowance`, `childCount`, `childCountDouble`, `disabledCareCount`, `disabilityCardHolder`. Legacy 16-arg constructor **derives** head counts from the declared amounts (see risk 3). |
| `PayrollEmployeeSnapshot.java` | +`dateOfBirth` (nullable). |
| `PayrollReconciliationDtos.java` | Upsert/read DTOs carry the new fields plus `effectiveMonth` + `documentReference`; legacy 17-arg constructor retained. |
| `PayrollRepository.java` | Allowance lookup resolves the **latest declaration on or before the payroll month**; upsert keyed on `(employee_id, tax_year, effective_month)`. |
| `V93__tax_allowance_lor_yor_01_effective_dating.sql` | New columns, per-head count backfill, PK re-keyed to include `effective_month`. |

### Also changed (omitted from the tables above until pass 5)

`PayslipRenderer.java` — wraps the calculation note (a latent defect predating this branch), prints
the ป.96 one-off pay rows, and prints the over-withholding notice gated on the final period of the
year. `PayrollCarryForwardDtos.java` — javadoc warning only, no behaviour change.

New test files: `PayrollCalculatorPo96ReviewTest`, `PayrollAllowanceCapIntegrationTest`,
`PayrollAllowanceCapResidualReviewTest`, `PayrollCalculatorRegularLimbSpikeReviewTest`,
`PayrollExcessWithheldNoticeReviewTest`, `PayslipMaximalLayoutReviewTest`,
`PayslipProvisionalNoticeGeometryReviewTest`. Several began as reviewer defect-pins and were converted
to regression guards when the defect was fixed; the conversions are mutation-checked.

### Tests

`PayrollCalculatorTest.java` — 18 new tests. `PayrollRepositoryIntegrationTest.java` — 2 call sites
updated for the new `findTaxAllowancesByEmployee(LocalDate)` signature.

One pre-existing assertion changed **deliberately**:
`calculatesPayrollAccordingToThaiPayrollExample` expected `projectedAnnualIncome = 494,000.04` and
`annualTax = 6,425.00`, which is ฿2,500 of ค่าล่วงเวลา annualised ×12 — precisely the ข้อ 2.5
violation. Now 466,500.04 / 5,050.00. Withheld and net are unchanged in that scenario (in January,
with 12 periods left and the whole liability inside the 5% band, the two methods coincide).

**`PayrollExcelReconciliationTest` passes unedited.**

## Commands run

```
cd backend && ./mvnw -B -q compile
cd backend && ./mvnw -B test -Dtest='PayrollCalculatorTest,PayrollExcelReconciliationTest,PayrollServiceTest,PayrollControllerTest,PayslipRendererTest,PayslipDistributionServiceTest'

# Docker is down on this machine, so Testcontainers hangs instead of failing. Local Postgres instead:
createdb glr_po96_it
cd backend && TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_po96_it?user=ploy_warit" \
  ./mvnw -B clean verify -Dtest.fork.count=1
```

Plus direct simulation of the real compiled `PayrollCalculator` over full tax years, before and
after, to produce the figures above.

## Tests / build results

Unit suites — **all pass**:

```
PayrollCalculatorTest             46 run, 0 failures
PayrollExcelReconciliationTest     7 run, 0 failures   (unedited)
PayrollServiceTest                10 run, 0 failures
PayrollControllerTest             13 run, 0 failures
PayslipRendererTest                2 run, 0 failures
PayslipDistributionServiceTest     4 run, 0 failures
```

`./mvnw -B clean verify` — **BUILD SUCCESS. 1245 run, 0 failures, 0 errors, 2 skipped.**

Integration tests **RAN** — they were not skipped. Docker was down on this machine, which made
Testcontainers hang rather than fail, so the run used a local Postgres 15.17 via
`TEST_DB_URL=jdbc:postgresql://localhost:5432/glr_po96_it?user=ploy_warit` with `-Dtest.fork.count=1`
(the correct flag name; `-Dfork.count=1` is silently ignored and lets two forks clean the same DB).
Every payroll integration suite executed against real Postgres, through the real service and
repository:

```
PayrollRepositoryIntegrationTest                   6 run, 0 failures
PayrollYtdAndSsoIntegrationTest                    4 run, 0 failures
PayrollAllowanceDirectorNonTaxableIntegrationTest  3 run, 0 failures
PayrollWithholdingTaxOverrideIntegrationTest       5 run, 0 failures
PayrollCarryForwardSuggestionsIntegrationTest      5 run, 0 failures
PayrollLeaveUnpaidDeductionSeamIntegrationTest     4 run, 0 failures
PayrollLeaveCorrectionAutoRefundIntegrationTest    2 run, 0 failures
PayrollCommissionWeightedBaseIntegrationTest       4 run, 0 failures
PayrollReprocessAndAttendanceDataFlowIntegrationTest 2 run, 0 failures
PayrollPersistedPayslipIntegrationTest             1 run, 0 failures
PayrollSuggestedInputsAuthorizationIntegrationTest 5 run, 0 failures
```

The 2 skips are `IntegrationResetInvariantTest`, pre-existing and unrelated to this change.

### Three bugs the integration run caught in this change

The first `verify` failed 3 of 1245. All three were real defects in this diff, and **all three would
have passed against a mocked repository** — the SQL was the thing that was wrong. Recorded because
they are the strongest argument for the real-DB rule in CLAUDE.md.

1. **The go-live YTD seed read back as zero.** `upsertYtdSeed` writes `taxable_income` /
   `withholding_tax`; the new query read the split columns, which V92 only backfilled for rows that
   existed AT migration time. Any seed entered afterwards contributed nothing to the projection —
   silently under-withholding exactly the employees being back-loaded at go-live. Fixed by removing
   the split columns from the seed table altogether and mapping the legacy columns to the regular
   limb, which deletes the failure mode rather than guarding against it.
2. Same root cause surfaced separately by `PayrollYtdAndSsoIntegrationTest`.
3. **A stored ฿100,000 child allowance was clamped to zero.** The legacy 17-arg
   `EmployeeTaxAllowanceUpsertRequest` passed `childCount = 0`, so the new per-head cap wiped a real
   declaration. Fixed by deriving the head count from the declared amount at that arity, matching
   `PayrollTaxAllowanceInput`'s legacy constructor and V93's own backfill.

## Authz evidence

**No authorization change.** No role gate, scope, filter, or who-may-read-whose-rows rule was
touched. The payroll endpoints keep their existing `PAYROLL_VIEW_ROLES` / `PAYROLL_EDIT_ROLES`
checks unchanged. No real-DB authz integration test is therefore required — and none is claimed.

## Corrections made after two review rejections (2026-07-29)

This branch was REJECTED twice by independent Opus review. Both rejections were correct. What
changed, and what it means for anything stated earlier in this document:

### The income classification was WRONG, and is now owner-stated rather than inferred

The first implementation read the split off the special-pay slot LABELS and
`PayrollCarryForwardDtos`' javadoc: พิเศษ 1–5 recurring, 6–8 occasional. The owner's account
(2026-07-29) is the opposite in almost every respect:

> "all of 1-8 is occasional for each employee in each month except พิเศษ 6 that is commission that
> sales get every month, just the amount varies"

and, asked where an annual bonus is entered: **"one of พิเศษ 1–5"**.

Consequences, both material:

1. **The headline fix did not fire.** The demonstrated ฿100,000 June bonus case (฿19,836 → ฿13,179)
   only worked with the bonus in slot 8. Typed into slot 1 — where it actually goes — it took the
   OLD annualised path unchanged. The engine was correct and unreachable at the same time.
2. **Commission was in the wrong limb.** Paid every คราว, so จำนวนคราวที่ต้องจ่าย is the periods
   remaining, not 1. A pure-commission rep had ZERO withheld for the first quarter and a December
   lump — the exact opposite of ข้อ 2.3. The owner authorised the move on 2026-07-29.

The classification is now: REGULAR = base salary + พิเศษ 6 + `commissionPay` + director remuneration;
VARIABLE = พิเศษ 1–5, 7, 8 + overtime. It is index-based, not a contiguous range, precisely because
writing it as a range is the mistake this replaces.

**ค่าตอบแทนกรรมการ — owner-confirmed 2026-07-29:** *"director remuneration is every month but the pay
may change once in a while."* Recurring, so the REGULAR limb is correct. The occasional amount change
is ข้อ 2.4 territory — *"กรณีมีการเปลี่ยนแปลงจำนวนเงินได้พึงประเมินที่จ่ายระหว่างปีภาษี ให้คำนวณภาษีใหม่"* —
and needs no special handling here: `annualRegularIncome = ytdRegular + regularTaxable ×
remainingPeriods` re-projects from actual year-to-date every period, so a changed amount is picked up
from the period it changes, which is what ข้อ 2.4 requires. Structurally identical to commission
(recurring, varying), and it inherits the same open question — see the spiky-input risk below.

### The ข้อ 2.10 leaver true-up was removed, not fixed

Review found `hr.resignation` has exactly one reader (the query written for this branch) and **no
writer anywhere in the codebase**; `findActiveEmployees` filters `is_active = TRUE` and resignation
is expressed by clearing that flag, so a leaver leaves payroll before the cap could apply. The owner
confirms resignations are not recorded in this platform at all — they live in another system.

Code that cannot execute is worse than an acknowledged gap, because it reads as a working compliance
feature. `findResignDatesFromMonth` and the resignation cap are gone. **ข้อ 2.10 is a known gap**
pending resignation data from the other platform.

### Open design question: recurring-but-varying income in the regular limb

Both items now in the regular limb vary month to month — commission by design, director remuneration
"once in a while". `annualRegularIncome = ytdRegular + regularTaxable × remainingPeriods` projects the
CURRENT period's figure across every remaining period, so an unusually large commission month
over-projects the year in that month. That is structurally the same shape as the bug this branch
exists to fix, moved to the other limb.

It self-corrects from the following period (year-to-date replaces the projection with actuals) and the
year still lands on the actual liability, so this is a cash-flow and monthly-ภ.ง.ด.1 accuracy question,
not an annual-liability one. Whether ข้อ 2.4 is satisfied by re-projecting each period, or whether
commission should instead be projected from its year-to-date AVERAGE, is under review and NOT settled.
Do not treat the current behaviour as verified-correct for a spiky commission month.

### Three defects introduced by this branch, found by review and fixed

1. **A commission clawback inflated taxable income above pay.** Each limb was floored at zero
   independently where the old code floored the combined figure once, so ฿50,000 salary with a
   ฿−10,000 clawback taxed the employee on ฿50,000 while paying ฿40,000 — and ฿50,000 would have gone
   onto their ภ.ง.ด.1 and 50 ทวิ. The variable limb may now go negative; only the combined figure is
   floored.
2. **Withholding continued past the year's own liability.** The regular limb's credit is spread over
   remaining periods while the variable limb's charge falls due in full, so a period could withhold
   when the engine already knew the year was covered — and December's clamp stranded it. A ceiling
   now caps any period at `annualTax − ytdWithheld`.
3. **A per-run child/disabled-care allowance was wiped.** `mergeAllowances` took the amount from the
   request body and the head count from the stored declaration, so an employee with no stored ล.ย.01
   row met a cap of zero. `headCountFor` now raises a missing OR stale count to cover the declared
   amount. Pinned by a real-DB test through the real service.

### Two documentation claims that were false

- **"`PayrollExcelReconciliationTest` passes unedited"** was offered as a regression guard. Review
  showed it constrains almost nothing here: it uses the legacy 12-arg constructor and collapses the
  whole พิเศษ block into slot 1. It has now been **edited** — `anEmptyYearToDateUnderWithholds…`
  asserted `may > august` and both are now zero, because the พิเศษ block is no longer annualised.
  The go-live hazard it documents is unchanged and now bites EARLIER (zero from May, not August).
- **"`excessWithheldToDate` surfaces over-withholding."** True when written of pass 3: the field was
  on `PayrollCalculation` and consumed by nothing. **RESOLVED in pass 5** — V94 persists it to
  `hr.payroll_line.excess_withheld_to_date` and `PayslipRenderer` prints it. The other five reported
  fields (`annualRegularIncome`, `annualVariableIncome`, `regularAnnualTax`, `variableAnnualTax`,
  `remainingPayPeriods`) still have no consumer; they are working papers for ภ.ง.ด.1, not employee-
  facing, so this is a smaller gap than it was — but it is still a gap.

### The payslip note was overflowing every payslip

The clamp warning was added claiming it made the clamp "visible on the payslip". `PayslipRenderer`
drew the note as one unwrapped line and never measured it; the new base note ran 568pt against a
495pt printable width, and a warning landed ~900pt past the page edge. PDFBox draws off-page without
error, which is why no test failed. The note is now **wrapped** (a latent defect that predates this
branch — an override clause alone reached 1,771pt), the base note is shortened, and the warning fires
on ANY clamp rather than only a zero head count.

### FLOOR vs CEILING

V93's `child_count` backfill used `FLOOR` while all Java derivations used `CEILING`, and the handoff
claimed they matched. They did not — ฿45,000 resolved to 1 head in SQL and 2 in Java, and FLOOR
would have silently deleted ฿15,000 from that declaration on migration. All five sites now use
CEILING, verified on real Postgres as a strict no-op on existing rows.

## Pass 4 + 5 (2026-07-29): owner-confirmed classification, V94, and the notice

### Owner statements that settled the design

| Question | Owner's answer | Effect |
|---|---|---|
| Are พิเศษ 1–8 recurring? | *"all of 1-8 is occasional for each employee in each month except พิเศษ 6 that is commission that sales get every month, just the amount varies"* | REGULAR = salary + พิเศษ 6 + commissionPay + director; VARIABLE = พิเศษ 1–5, 7, 8 + OT |
| Where is a bonus typed? | *"one of พิเศษ 1–5"*, and *"bonus, อื่นๆ should be a separate field"* | V94 adds `bonus_pay` + `other_one_off_pay` |
| Is director remuneration constant? | *"every month but the pay may change once in a while"* | Stays REGULAR. The change is ข้อ 2.4, already satisfied by re-projecting from year-to-date each period |
| How are resignations recorded? | Not in this platform — another system | ข้อ 2.10 removed as dead code, now a known gap |

The first answer INVERTED the classification shipped in pass 3, which had been inferred from slot
labels. Consequence at the time: the headline ฿100,000 June bonus fix did not fire in production,
because a bonus typed into พิเศษ 1 took the annualised path. It fires now.

### V94 — dedicated one-off pay + persisted over-withholding

- `bonus_pay`, `other_one_off_pay` — both ข้อ 2.5 variable limb, plumbed request → calculator → DTO →
  repository → payslip. **No UI yet** (see risks).
- `excess_withheld_to_date` — the over-withholding an earlier period stranded. Persisted AND printed.

### The payslip over-withholding notice, and why it is gated on December

`excessWithheldToDate` is measured against the CURRENT period's PROJECTED annual tax. Before the final
period it is an estimate that later periods can still absorb by withholding less. An unconditional
*"ไม่สามารถคืนผ่านระบบเงินเดือนได้"* was therefore a **false statement on a payroll document from as
early as April**, naming a baht figure an employee could carry onto a ภ.ง.ด.91 — review built a case
where payroll then returned it in full. December prints the final wording; earlier periods print a
clearly-labelled ประมาณการ that does not mention ภ.ง.ด.91.

### Defects this branch introduced, found by review, fixed

| # | Defect | Fix |
|---|---|---|
| 1 | Clawback inflated taxable income above pay (taxed ฿50,000, paid ฿40,000) | Only the combined figure is floored; the variable limb may go negative |
| 2 | Withholding continued past the year's liability | Ceiling at `annualTax − ytdWithheld` in every period |
| 3 | Per-run allowance wiped for an employee with no ล.ย.01 row | `headCountFor` derives a count in exactly that case |
| 4 | …then that fix DESTROYED the cap: it fell back to the stored amount and raised the count to cover it (฿300,000 against one child, allowed in full, triggered by any unrelated field, direction = UNDER-withholding) | Narrowed to run-body amounts only, when no stored count exists. A stale count clamps and warns |
| 5 | Payslip note ran 568pt against a 495pt printable width — **every** payslip | Wrapped (a latent defect predating this branch: an override clause alone reached 1,771pt) |
| 6 | V92 `COMMENT ON` and `PayrollYearToDate`'s javadoc stated the classification BACKWARDS | Both corrected |
| 7 | ข้อ 2.10 claimed as working in six places after removal | Corrected in code and tests |

Reviewer defect-pins were **converted to regression guards, not deleted**, and mutation-checked:
re-introducing the old `headCountFor` turns `PayrollAllowanceCapIntegrationTest` RED with the exact
predicted figures and nothing else fails.

### Commands run

```
cd backend && ./mvnw -B clean verify      # Docker up -> Testcontainers, the CI path
```

### Test / build results — pass 5

**BUILD SUCCESS — 1271 tests, 0 failures, 0 errors, 0 skipped.** Integration tests **RAN** on
Testcontainers (not `TEST_DB_URL`), including all payroll real-Postgres suites. Supersedes every
earlier figure in this document.

### Authz evidence

**No authorization change.** No role gate, scope, filter or row-visibility rule touched;
`PAYROLL_VIEW_ROLES` / `PAYROLL_EDIT_ROLES` unchanged. Independently confirmed by review. The real-DB
tests added here assert allowance arithmetic, not authorization, and no authz claim is made from them.

## Known risks

1. **V92/V93/V94 numbering must be re-checked before merge.** V91 is the maximum across this repo and
   every worktree, but production has previously run ahead of `main` (see the Flyway collision
   incident of 2026-07-20). Confirm against the **applied history on the target DB**, and never
   renumber a migration that has already been applied.

2. **V92's `payroll_line` backfill is an approximation, and deliberately so.** History was never split, so past
   months are attributed entirely to the regular limb — which is truthful, since that is exactly how
   the old formula treated them. Consequence: for an employee already paid OT/commission/bonus
   earlier in 2026, the first post-migration run sees a zero variable limb year-to-date and will
   withhold the **full** ข้อ 2.5 difference on their cumulative เงินพิเศษ with no credit for what the
   old formula already over-withheld. The owner has chosen to **recompute and re-file the affected
   2026 months** (ภ.ง.ด.1 เพิ่มเติม), which replaces this backfill with real split figures — so this
   risk is retired by doing that, not by relying on the backfill.

3. **The legacy 16-arg `PayrollTaxAllowanceInput` derives head counts from the declared amounts.**
   Necessary so a pre-V93 caller stays byte-identical instead of silently losing a real child
   allowance, and it mirrors V93's own backfill. It is permissive by construction: a caller at that
   arity gets whatever count the amount implies. Every production path goes through the repository,
   which reads the real declared counts.

4. **V93's per-head backfill needs HR verification.** `child_count` is derived by `FLOOR(amount /
   30000)` and `disabled_care_count` by `CEIL(amount / 60000)` from figures that were never capped or
   audited. Rounding is deliberately toward under-claiming. HR must confirm each row against the
   employee's actual ล.ย.01, and re-declare where wrong.

5. **NO UI for any new field, and the API is the only route in.** `providentFundAllowance`, the
   per-head counts, `disabilityCardHolder`, `effectiveMonth`, `documentReference`, `bonusPay` and
   `otherOneOffPay` are all accepted and persisted with no HR screen. Two consequences beyond
   inconvenience: (a) **V94 changes nobody's tax as shipped** — HR keeps typing bonuses into พิเศษ 1,
   which is already the ข้อ 2.5 limb, so the field's value is identification, not arithmetic, and it
   is unrealised until the form exists; (b) `hr.employee_tax_allowance` can only be loaded over the
   raw API, and a pre-V93 JSON payload stores `child_count = 0`, which the payroll run then **clamps
   to zero with a warning** — over-withholding, noisy rather than silent, but a real go-live hazard
   for whoever loads the ล.ย.01 declarations.

6. **Residual: a run-body allowance can still set its own cap.** An employee with NO stored ล.ย.01 row
   whose HR types `childAllowance = 300,000` gets a derived 10 heads and the full amount, with no
   warning (because `declared == cap` after derivation). The same figure properly declared for one
   child clamps to ฿30,000 with a warning. Deliberate — it is the only way to avoid silently deleting
   a per-run declaration — and bounded by HR already being able to override withholding outright
   (V88), but it means **a declaration recorded on ล.ย.01 is treated more strictly than one typed into
   the run**. Resolve by giving the allowance screen a head-count field, i.e. risk 5.



7. **Still not fixed, and still real** (out of scope by decision, or previously reported):
   - หักตามใบเตือน / หักลูกค้าคืนสินค้า still reduce assessable income (owner decision pending).
   - SSO base excludes commission; Supreme Court authority treats commission paid for normal working
     time as ค่าจ้าง. Only bites employees whose base pay is under ฿17,500.
   - SSO minimum contribution computes ฿82.50 where SSO's own table says ฿83.
   - อายัดเงินเดือน takes 30% of total taxable income including bonus; ป.วิ.พ. ม.302 treats bonus
     under a separate limb.

**Review status.** Five independent Opus passes have run: four REJECT, then APPROVE WITH FIXES, then
this remediation. Pass 5's own summary of the arithmetic: *"I attacked the ceiling, the limb split, the
negative-clawback path, the year-end true-up and the payslip geometry, and none of them broke."* Its
mutation check confirmed the regression guards are live. Everything it raised is either fixed here or
listed as a risk below.


8. **`PayrollCarryForwardDtos` can re-propose last month's one-off pay.** `suggestedInputs`
    pre-fills พิเศษ 1–5 into the next run, on a javadoc that calls them "recurring company
    allowances". The owner says those slots are occasional and are where a **bonus** is typed — so a
    ฿100,000 June bonus pre-fills again in July, and only HR noticing stops it. It is a suggestion HR
    edits before submitting, never an automatic payment, so nothing is paid without a human. Left
    unchanged deliberately: what carries forward is a payroll-entry behaviour change needing the
    owner's decision, not a side effect of a withholding fix. The javadoc now carries the warning.

9. **The over-withholding notice names ภ.ง.ด.91 unconditionally.** An employee with income outside
    มาตรา 40(1) files **ภ.ง.ด.90**, not 91. Needs the accountant's wording before it reaches a real
    payslip.

10. **`excessWithheldToDate` was blind to a withholding override** and is now computed after the
    substitution (pinned by `aWithholdingOverrideIsReflectedInTheReportedOverWithholding`). Recorded
    because the pre-fix figure would have printed on a December payslip understated by exactly the
    override, contradicted by the override clause in the same page's calculation note.

## The exact next prompt for the next agent

> You are reviewing, not implementing. Read `docs/agent-handoffs/117_fix-payroll-wht-po96-compliance.md`
> first, then review the full uncommitted diff in the worktree
> `/Users/ploy_warit/Desktop/GL-R-ERP-payroll-tax` (branch `fix/payroll-wht-po96-compliance`, base
> `c5e46189`). Do not fix anything larger than a typo — report instead.
>
> Verify independently, against
> https://www.rd.go.th/fileadmin/tax_pdf/withhold/200360_WHT1.pdf (คำชี้แจง ภ.ง.ด.1 ข้อ 2.1–2.10) and
> https://www.rd.go.th/3558.html (ป.96/2543), that:
> 1. The regular/variable classification is right. Is director remuneration really ข้อ 2.1 regular?
>    Are พิเศษ 6–8 really the ข้อ 2.5 limb? Is พิเศษ 4 (เบี้ยขยันประจำ) recurring or occasional?
> 2. Running `allowanceBreakdown` twice on two different income bases is what ข้อ 2.5 means by
>    "คำนวณภาษีใหม่ตามที่กล่าวใน 2.2", and the percentage-based caps differing between the two runs is
>    correct rather than double-counting.
> 3. The single outer `max(0, …)` clamp cannot be gamed: construct a case where a negative regular
>    limb and a positive variable limb interact, and confirm the persisted split still sums to the
>    amount withheld and that year-to-date stays reconcilable.
> 4. The ฿190,000 exemption really is taken before ค่าใช้จ่าย, and applying it inside BOTH ข้อ 2.2
>    passes does not grant it twice.
> 5. `remainingPayPeriods` is right for a leaver resigning on the 1st, on the last day of a month, in
>    December, and in a prior year; and for an employee with no `hr.resignation` row.
> 6. V92 and V93 are safe to apply to a database that already has data, are genuinely forward-only,
>    and that V93's PK re-key cannot lose rows.
>
> Then run `cd backend && ./mvnw -B clean verify` and state plainly whether the integration tests RAN
> or were SKIPPED. Try to break the change — build a scenario the new tests do not cover and see
> whether the engine still lands on the year's actual liability.
