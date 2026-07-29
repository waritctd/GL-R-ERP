# Agent Handoff — payroll tax classification + HR declaration screens

## Task
Implement the owner's decisions of 2026-07-29 on payroll withholding classification, ค่าลดหย่อน
declarations, SSO wage base, garnishment, and the HR screens that drive them.

Successor to `fix/payroll-wht-po96-compliance` (branch `117`), which is committed, green
(1276 tests) and rebased but **not pushed**. That branch fixed the ป.96/2543 withholding mechanism.
This one implements the decisions that came out of reviewing it with the owner.

## Branch
`feat/payroll-classification-and-hr-declarations`, worktree `/Users/ploy_warit/Desktop/GL-R-ERP-payroll-hr`,
base `a104244d`.

⚠️ Branch 117 is NOT merged. Several changes here **conflict with it** (PVD removal, the ฿190,000
exemption, the payslip notice, carry-forward). Sequence: land 117 first, then rebase this onto it.

## Owner decisions — the specification

Every item below is the owner's stated decision, dated 2026-07-29. Where a decision diverges from a
statute or from the accountant's workbook, that divergence is recorded, not hidden.

### 1. Three tax treatments, HR-classified per pay line

| Classification | Applies to | Rule |
|---|---|---|
| `REGULAR_REPROJECT` | salary, fixed recurring allowance | ป.96 **ข้อ 1(4)** — annualise × จำนวนคราวที่ต้องจ่าย, recompute every period |
| `EXTRA_KNOWN_FREQUENCY` | bonus, confirmed one-off | ป.96 **ข้อ 1(5)** — × the known count (1 for an annual bonus), taxed as the difference |
| `EXTRA_CUMULATIVE_ACTUAL` | OT, irregular commission | ป.96 **ข้อ 1(6)** — cumulative actual, less tax already withheld |

**HR sets every line.** An unclassified line **rejects the payroll run** — no silent default.

**Classification is required only for a component with a NON-ZERO amount in that run.** Without this,
adding พิเศษ 9 would block every payroll until HR classified housing allowance for all 30 employees,
including the ones who never receive it — a migration that halts payroll is not a migration. An
employee with ฿0 in a slot has nothing to classify, so the run proceeds. The blocker fires only where
money is actually moving and the treatment is genuinely unknown.
Branch 117 has only two limbs, hardcoded by slot; this replaces that with a per-line classification.

**Only เงินเดือน is fixed.** Owner, 2026-07-29: salary is LOCKED to `REGULAR_REPROJECT` — it is paid
every คราว by definition and has no second sensible treatment. **Everything else is HR-classified per
employee**, including ค่าตอบแทนกรรมการ (some directors are paid monthly, some annually) and all nine
พิเศษ: *"all the พิเศษ behaves like พิเศษ 9"* — recurring for one employee, occasional for another,
absent for a third.

⚠️ **This retires branch 117's `COMMISSION_SPECIAL_PAY_INDEX` entirely.** That constant hardcodes
พิเศษ 6 into the regular limb for everyone. Under this decision no component except salary is
hardcoded to a limb — commission is regular for a rep who earns it monthly and cumulative for one who
earns it on closing, and only HR knows which. The slot-based split was the best available reading at
the time; it is now superseded by data, not by a change of mind.

### 2. ฿190,000 exemption — DISABLED in payroll, both routes

Age 65+ **and** disability-card. Neither reduces monthly withholding. The disability-card field is
kept for eligibility and document tracking only. Employees claim it on their annual return, where
they can choose which income category receives it. **2 active employees are 65+; 3 have no
`date_of_birth`.**

HR-screen reminder, owner-approved wording:

> จากข้อมูลที่แจ้งไว้ ท่านอาจมีสิทธิยกเว้นเงินได้ไม่เกิน ฿190,000 สำหรับผู้มีอายุ 65 ปีขึ้นไป หรือผู้มีบัตรประจำตัวคนพิการ
> ตามหลักเกณฑ์ที่กฎหมายกำหนด บริษัทไม่ได้ใช้สิทธินี้ในการคำนวณภาษีหัก ณ ที่จ่ายรายเดือน โปรดตรวจสอบและใช้สิทธิ
> ในการยื่นแบบ ภ.ง.ด.90 หรือ ภ.ง.ด.91 ประจำปี

### 3. Declaration verification + grandfathering

`GRANDFATHERED_UNVERIFIED` → applied, HR + employee warned. Deadline **60 days or two payroll
cut-offs after launch, whichever is later**. On lapse: `EXPIRED_UNVERIFIED`, `taxDeductionApplied = 0`
from the next payroll. Never retro-alter filed months for late verification. On verification,
reactivate and recompute year-to-date, recovering the temporary over-withholding through lower later
deductions or the December true-up.

Applies to **employee-declared** allowances only — not the automatic ฿60,000 personal allowance,
actual SSO, or anything derived from payroll records.

**Verified against production: `hr.employee_tax_allowance` has ZERO rows.** There is nothing to
grandfather today. Build the state machine anyway — it governs everything HR enters from now on.

### 4. Allowance caps, versioned by tax year

From **the first month of record — March 2026** (verified: earliest `payroll_period.payroll_month`).
Only one tax year exists, so versioning is structural, not a data-migration problem.

Spouse ฿60,000 · **parents ฿30,000 PER qualifying parent, ฿120,000 = the four-parent maximum**
(branch 117 has a flat ฿120,000 cap and no head count — must change) · maternity actual ≤ ฿60,000 ·
life ≤ ฿100,000 · own health ≤ ฿25,000 inside the combined ฿100,000 · parent health ≤ ฿15,000
combined · home loan ≤ ฿100,000 · political ≤ ฿10,000 · ThaiESG 30% ≤ ฿300,000 · RMF 30% within the
฿500,000 retirement cap · pension 15% ≤ ฿200,000 within that cap · donation is not a fixed
allowance — multiplier then the 10% ceiling after other deductions.

**PVD: remove entirely.** Verified: `hr_restricted.employee_pii` has **0** rows with a
`provident_fund_no`. GL&R has no provident fund. Branch 117 added it on the strength of the column
existing — that inference was wrong.

Garnishment is not a tax allowance.

### 5. SSO wage base — HR-tickable inclusion

**In:** เงินเดือน · คอมมิชชั่น · พิเศษ 1–5, 7, 8 · ค่าล่วงเวลา · โบนัส · อื่นๆ
**Out:** ค่าตอบแทนกรรมการ · รายได้ไม่คิดภาษี

**PER EMPLOYEE** — owner-decided 2026-07-29, superseding the company-wide-default proposal. Each
employee carries their own inclusion tick for each pay component, seeded with the defaults above when
the employee record is created.

Per employee **and** per component, both dimensions. Employee **10080 draws a ฿30,000 salary AND a
฿30,000 director fee**, so a single per-person on/off flag gives the wrong answer either way: ticked
sweeps the director fee into the wage base, unticked exempts a real salary. The tick is therefore a
matrix — one row per employee, one column per component — not a single checkbox per person.

UI consequence: this cannot be a per-employee toggle buried in a profile. It needs a view where HR
can see the whole matrix at once, spot the outliers, and change one cell without opening 30 screens.

⚠️ **Divergence from พ.ร.บ.ประกันสังคม ม.5**, recorded as the owner's decision. ม.5 defines ค่าจ้าง as
*"เงินทุกประเภทที่นายจ้างจ่ายให้แก่ลูกจ้างเป็นค่าตอบแทนการทำงานในวันและเวลาทำงานปกติ"*, which on its face
excludes ค่าล่วงเวลา (outside normal hours) and โบนัส (not for normal working time). The owner was
told this and reaffirmed. Commission being included is the legally *correct* half and fixes a real
gap. Impact is bounded: the ฿17,500 ceiling means only the **8 employees under it** can be affected,
5 of whom receive commission/special/OT.

Per decision 7, the accountant must confirm each commission code against the employment/commission
agreement **before any past SSO filing is restated**.

**Correction to an earlier claim in this programme:** production is NOT wrongly charging directors
SSO. The three fee-only directors (10001–10003) are charged zero, correctly. The five lines that
looked wrong all belong to 10080, who has a real salary.

### 6. Deduction reclassification

- **หักตามใบเตือน** — must NOT reduce taxable gross or 50 ทวิ. Post-tax only, where legally
  permitted and with specific written employee consent. Branch 117 has it pre-tax.
- **ลูกค้าคืนสินค้า** — **new flag**. Commission not yet earned → reduce the commission earning.
  Already earned and paid → separate clawback; do NOT silently reduce current taxable income.

⚠️ **This has already gone wrong in production.** June 2026, one line:

```
base_salary 40,850.00 · commission_pay -58,832.83 · special_pay_total 9,519.00
gross_amount -8,463.83 (NEGATIVE) · gross_taxable_income 0.00
social_security 875.00 · withholding_tax 0.00 · net_amount 0.00
```

The employee was **paid nothing**, their June ภ.ง.ด.1 reports ฿0 of income against ฿40,850 of salary
actually earned, and SSO was still deducted. Owner has directed that **this filing be corrected**
along with the rest. Taking wages to nil to recover a commission is also very unlikely to survive
LPA ม.76.

### 7. Garnishment — per payment type

Researched from the Legal Execution Department manual, not assumed:

| Type | Limit |
|---|---|
| เงินเดือน / ค่าจ้าง | max **30%**, must leave **≥ ฿20,000** |
| เงินโบนัส | max **50%** |
| เบี้ยขยัน / ค่าล่วงเวลา | max **30%** |
| เงินตอบแทนกรณีออกจากงาน | must leave **≥ ฿300,000** |

Branch 117 applies a single 30% to total taxable income plus the ฿20,000 floor. The severance rule
does not exist in the engine at all.

### 8. Payslip

Print the month's withholding only. **Do not print the excess amount.** Keep persisting
`excess_withheld_to_date` for the ภ.ง.ด.1 working papers and reconciliation.

Owner-approved wording, normal/mid-year:

> ภาษีหัก ณ ที่จ่ายงวดนี้คำนวณจากเงินได้และรายการลดหย่อนที่บริษัทมีข้อมูลในขณะประมวลผล จึงเป็นยอดประมาณการ
> และอาจเปลี่ยนแปลงเมื่อเงินได้หรือรายการลดหย่อนเปลี่ยนแปลง

December:

> ภาษีหัก ณ ที่จ่ายเดือนธันวาคมเป็นการปรับปรุงยอดปลายปีจากเงินได้ที่บริษัทจ่ายจริง รายการลดหย่อนที่พนักงานแจ้ง
> และภาษีที่หักไว้แล้ว ทั้งนี้ พนักงานยังต้องยื่นแบบ ภ.ง.ด.90 หรือ ภ.ง.ด.91 ตามเงินได้ทั้งหมดของตน

**ภ.ง.ด.90 vs 91:** 40(1) only → 91. Other income types too → 90. Never name 91 unconditionally.

### 9. Carry-forward

Stop carrying พิเศษ 1–5. Only salary and genuinely fixed recurring allowances carry. Bonus, OT,
commission and one-off payments reset to zero. HR may explicitly copy a previous value, with an
audit trail.

Owner reaffirmed that พิเศษ 1–8 except 6 are non-recurring.

### 9b. A NINTH พิเศษ slot — ค่าเช่าบ้าน

Owner, 2026-07-29: *"there should be 9 พิเศษ, another one is ค่าเช่าบ้าน."*

This also settles an earlier ambiguity: the owner's example wrote "พิเศษ 1 (ค่าเช่าบ้าน)", which read
as a correction to พิเศษ 1's label. It is not. **พิเศษ 1 stays ค่าครองชีพ**; ค่าเช่าบ้าน is new.

**APPEND as พิเศษ 9. Never renumber.** `hr.payroll_line` holds 149 processed rows across five filed
months whose `special_pay_1..8` values mean what the current labels say. Inserting ค่าเช่าบ้าน
anywhere but the end would silently redefine every historical figure and every ภ.ง.ด.1 already filed
from them.

Slot 9 is a schema + contract change across ten files: `V15`'s columns, `PayrollCalculator`'s
`SPECIAL_PAY_SLOTS` (and `COMMISSION_SPECIAL_PAY_INDEX`, which must stay pointing at พิเศษ 6),
`PayrollEmployeeInputRequest`, `PayrollRepository` insert/select, `PayrollService#specialPayDtos`
labels, `PayrollPage.jsx`'s `specialPayFields`, plus four integration tests.

**Classification is PER EMPLOYEE — there is no company default for พิเศษ 9.** Owner, 2026-07-29:
*"it depends on the employee very much. some employees get it constantly every month, some don't, some
don't get it at all."*

This retroactively reframes the earlier blanket statement that "พิเศษ 1-8 except 6 are non-recurring".
That was the **seed default**, not a law. The truth is that any component can be recurring for one
employee and occasional for another, which is exactly what the per-employee × per-component matrix
exists to express. The matrix is the source of truth; the blanket rule is only what a new row is
seeded with.

### 9c. Frontend decisions (owner, 2026-07-29, after design review)

- **Payroll is now a phone surface.** PRODUCT.md's "Payroll is desktop-only by design" is updated on
  this branch. The matrices reflow to one employee per screen — never a shrunken fifteen-column table.
- **Phone is fully EDITABLE**, not read-only. Consequence: PRODUCT.md's mis-tap risk now applies to
  the most consequential surface in the product. 48px minimum rows, deliberate confirmation on
  anything that commits.
- **`ผสม`** is the run-table label when an employee's components carry different treatments. Not a
  count, not five chips.
- **Employees see their own สิทธิ์.** ล.ย.01 is not HR-only: an employee can view their own
  declaration, its verification status and what evidence is outstanding. HR still owns verification —
  the employee sees state, not the verify action. Scope it exactly like the existing payslip
  self-service route, which is already employee-visible and HR-scoped for everyone else's data.

### 10. HR screens — build them

Structured records for children / parents / disabled dependants (counts and eligibility, not typed
baht) · `effectiveMonth` · `documentReference` · verification status + verifier · `bonusPay` ·
`otherOneOffPay` · tax-treatment classification · recurring/non-recurring flag · SSO inclusion ticks
· the ฿190,000 eligibility reminder. **No PVD.**

Only **verified** records effective for the payroll month may affect tax.

`commissionPay` keeps its automatic `CommissionService` feed — owner confirmed. Do **not** add an
HR-typed commission field; alongside would double-count.

## Production facts (queried 2026-07-29, real DB `tdyzcqzxmhtxpbouewud`, read-only)

| Fact | Value |
|---|---|
| Earliest payroll month | 2026-03-01 — **no Jan/Feb payroll existed**, owner confirmed |
| Processed 2026 months | Mar, Apr, May, Jun, Jul — the re-file scope |
| `payroll_year_to_date_seed` | **empty, and correctly so** — March is genuinely period 1 of 10 |
| Applied Flyway max | **91.1** — V92/93/94 (branch 117) are free; this branch starts at V95 |
| `employee_tax_allowance` rows | **0** |
| PVD members | **0** |
| Active employees under the ฿17,500 SSO ceiling | **8**, of whom **5** receive commission/special/OT |
| Aged 65+ / missing date_of_birth | **2 / 3** |
| Total 2026 WHT withheld / SSO | ฿114,924.64 / ฿113,160.90 |
| Negative-commission lines | **1** — the June row above |

## Still open

- Which 2026 ภ.ง.ด.1 returns are already filed — the accountant must supply the e-Filing receipt per
  month. Not filed → file original. Filed and understated → เพิ่มเติม. Wrong details → correction
  process, never a blind duplicate. SSO is affected only where the reportable wage base changes.
- The spiky-commission over-withholding inherited from branch 117 is disclosed, not fixed.
- **Whether fixed-by-definition components should be editable in the WHT matrix.** เงินเดือน and
  ค่าตอบแทนกรรมการ have only one sensible treatment; rendering them as clickable badges is a way for
  HR to break something. Locked labels vs full editability is unresolved.


---

# Progress — task 1 of N: schema + model + repository

**Status: DONE and reviewed. Not committed.**

## Files changed

| File | Change |
|---|---|
| `V95__payroll_classification_and_hr_declarations.sql` | `special_pay_9` (appended), `hr.payroll_pay_component` lookup, `hr.payroll_component_tax_treatment`, `hr.payroll_component_sso_inclusion`, verification status/verifier/verified_at/deadline on `employee_tax_allowance`, `parent_care_count` |
| `PayrollComponent.java` | new — 16 components incl. `SPECIAL_PAY_9`, `BONUS_PAY`, `OTHER_ONE_OFF_PAY` |
| `PayrollTaxTreatment.java` | new — the three ป.96 treatments with ข้อ 1(4)/(5)/(6) cited |
| `PayrollClassificationDtos.java` | new — classification + SSO inclusion records |
| `PayrollRepository.java` | read/upsert for both maps, verification-state writers |
| `PayrollCalculator`, `PayrollService`, `PayrollEmployeeInputRequest`, `PayrollReconciliationDtos` | 8 → 9 special-pay slots |
| 7 integration tests | widened for slot 9 |
| `PayrollClassificationAndSsoInclusionIntegrationTest.java` | new — 8 real-Postgres tests |
| `PayrollClassificationReviewIntegrationTest.java` | new — 4 real-Postgres tests from review |
| `PRODUCT.md` | payroll is no longer desktop-only |

## Commands run

```
cd backend && ./mvnw -B clean verify     # Testcontainers, Docker up
```

## Tests / build results

**BUILD SUCCESS — 1238 tests, 0 failures, 0 errors, 0 skipped.** Integration tests **RAN** on
Testcontainers (Flyway applied 90 migrations through v95). Not `TEST_DB_URL`.

## Authz evidence

**No authorization change.** No controller, no role gate, no scope filter, no new endpoint — the
matrices have no HTTP surface yet. Nothing to verify and nothing claimed.

## Two defects found in review and fixed

1. **The SALARY lock did not hold against NULL.** `CHECK (component <> 'SALARY' OR tax_treatment =
   'REGULAR_REPROJECT')` accepts `('SALARY', NULL)`: a CHECK rejects only on FALSE, and
   `FALSE OR NULL` is NULL. Salary could be stored **unclassified** — the one state the owner said
   cannot exist — and it was reachable through the documented reset affordance
   (`upsertComponentTaxTreatment` with a null, then `ON CONFLICT DO UPDATE`). 1234 tests were green
   over it because the only guard test tried a wrong *treatment*, never a null. Fixed with an
   explicit `IS NOT NULL`.
2. **`BONUS_PAY` / `OTHER_ONE_OFF_PAY` were missing** from the enum and lookup table. The spec names
   โบนัส in the ประกันสังคม base and calls bonus the archetypal `EXTRA_KNOWN_FREQUENCY`; a matrix
   that cannot express bonus is unable to state the classification the spec is most explicit about.
   Their `payroll_line` columns arrive with branch 117's V94, which lands first and sorts before V95.

Separately fixed before review: `findComponentTaxTreatmentsByEmployee` used `Collectors.toMap`, which
throws on a null VALUE, so the repository could not represent an unclassified component at all.

## Known risks — carried into the next task

1. **Slot 9 is backend-only.** `frontend/src/features/payroll/PayrollPage.jsx`'s `specialPayFields`
   still stops at 8, so **ค่าเช่าบ้าน cannot be entered by anyone**. Worse than absent: review traced
   a silent-drop path — `adjustmentFromLine` sets `specialPay9` from a 9-element backend line, but
   the submit payload is built from `specialPayKeys`, which excludes it, so any value that reaches a
   line is zeroed on the next round trip. Frontend was out of scope for this task by instruction, not
   by oversight; it must land before slot 9 is usable.
2. **`parent_care_count` is dead schema.** The column exists; no Java path reads or writes it, and it
   sits beside the pre-existing baht column `parent_care_allowance` with no reconciliation rule and
   no backfill. Two sources of truth for one allowance.
3. **SSO inclusion defaults reach nobody.** `seedSsoInclusionDefaults` has zero callers in
   `src/main`, and V95 contains no backfill for the ~30 existing employees. Every employee currently
   has zero inclusion rows. The read path correctly does not synthesize defaults, so this must be
   wired before the tax math depends on it.
4. **The three verification-state writers ignore the update row count.** A mistyped `employeeId` or
   wrong `taxYear` updates 0 rows and reports success, for a state machine that governs whether a
   deduction applies. Should throw.
5. **§10 gaps not yet built and not previously recorded as deferred:** structured child / parent /
   disabled dependant records with eligibility, `documentReference` (117's V93 supplies the column),
   and the recurring/non-recurring flag.

## The exact next prompt for the next agent

> Task 2 of the payroll rework: rewrite `PayrollCalculator` to compute withholding from the
> per-employee, per-component tax treatments in `hr.payroll_component_tax_treatment` and the SSO wage
> base from `hr.payroll_component_sso_inclusion`, replacing the hardcoded limb split. Read
> `docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md` in full first — the
> owner decisions are the authority. Before you start, resolve known risk 3 (nothing seeds the SSO
> inclusion rows, so the wage base would read as empty for every employee). An unclassified component
> with a NON-ZERO amount must reject the run; a zero amount needs no classification. Do not touch the
> frontend. Real-DB integration tests through the real service, per CLAUDE.md.
