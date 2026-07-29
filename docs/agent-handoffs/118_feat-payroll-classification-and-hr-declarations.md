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

### 9d. The accountant's workbook — read 2026-07-29, and it moved several decisions

`2026.xlsx` (sheets มี.ค.69 / เม.ย.69 / พ.ค.69 / มิ.ย — **no July sheet**) is the accountant's real
ledger. Reading it settled three things and overturned one.

**The พิเศษ numbers do NOT match.** The workbook carries ค่าเช่าบ้าน as **พิเศษ 2**, which shifts every
later number by one:

| Workbook | System slot | Same money? |
|---|---|---|
| พิเศษ 1 ค่าครองชีพ | 1 | yes |
| **พิเศษ 2 ค่าเช่าบ้าน** | — | **absent from the system** |
| พิเศษ 3 เบี้ยเลี้ยงประจำ | 2 | yes |
| พิเศษ 4 ค่าตำแหน่ง | 3 | yes |
| พิเศษ 5 เบี้ยขยันประจำ | 4 | yes |
| พิเศษ 6 ค่า GPRS | 5 | yes |
| พิเศษ 7 คอมมิชชั่น | 6 | yes |
| พิเศษ 8 ทำได้ตาม KPI | 7 | yes |
| พิเศษ 9 เงินรางวัล | 8 | yes |

Verified against data, not labels: มณฑ์ชญา is `I=407, R=3,738` in the June sheet; employee 10012 is
`special_pay_1=407, special_pay_6=3,738` in the database. Same money, different พิเศษ number.

**OWNER DECISION (revised 2026-07-29): ALIGN THE SYSTEM TO THE WORKBOOK.**

The first decision was to keep ค่าเช่าบ้าน at slot 9 and have the accountant renumber. That advice
rested entirely on "renumbering would redefine 149 processed rows across five filed months" — and the
owner then confirmed **nothing has ever been processed or paid from this ERP; all five runs were
tests**. With the premise gone the argument collapsed, and the decision was re-opened and reversed.

Target numbering — system slot N == workbook พิเศษ N:

| Slot | Name | Was (system) |
|---|---|---|
| 1 | ค่าครองชีพ | 1 — unchanged |
| **2** | **ค่าเช่าบ้าน** | 9 (added in 506a68e8) |
| 3 | เบี้ยเลี้ยงประจำ | 2 |
| 4 | ค่าตำแหน่ง | 3 |
| 5 | เบี้ยขยันประจำ | 4 |
| 6 | ค่า GPRS | 5 |
| **7** | **คอมมิชชั่น** | **6** |
| 8 | ทำได้ตาม KPI | 7 |
| 9 | เงินรางวัล/เงินช่วยเหลืออื่นๆ | 8 |

Because every payroll period is now VOID, this is a **label change, not a data migration** — no stored
figure needs remapping and no filed return is affected.

⚠️ **CROSS-BRANCH BREAK.** Branch 117 (`fix/payroll-wht-po96-compliance`, committed, unmerged) hardcodes
`COMMISSION_SPECIAL_PAY_INDEX = 5` — zero-indexed slot 6 — to put commission in the ข้อ 2.1 regular
limb. After this renumbering slot 6 is **ค่า GPRS** and commission is slot 7. If 117 merges without
that constant being updated, commission silently lands in the wrong ป.96 limb and ค่า GPRS is
annualised in its place. Nothing fails loudly. Fix it during the rebase, not after.

**Two components exist in the ledger and nowhere in the system — OWNER DECISION: add both as real
components.** `ค่าอาหาร` (workbook col K — สุเชด ฿1,680, ศรรักษณ์ ฿1,680) and
`เบี้ยเลี้ยง (ตจว/ตปท)` (col P). Both sit inside the W total, so both are taxable. They need
`payroll_line` columns, `PayrollComponent` values, SSO inclusion and tax classification like anything
else — and they widen every matrix by two.

**Commission timing.** Owner: commission for month M is paid in month M+1. June's commission is
therefore July money.

### 9e. ALL FIVE 2026 periods were tests — VOIDed 2026-07-29

Owner: *"basically nothing was actually processed and paid from this ERP yet, everything that I clicked
was just to test."* No ภ.ง.ด.1 was ever filed from the system and no SSO was remitted from it. The
accountant's `2026.xlsx` is the real payroll.

Executed on the production database with the owner's explicit authorisation, after confirming no
return was filed and no SSO remitted:

```sql
UPDATE hr.payroll_period SET status = 'VOID'
 WHERE payroll_month BETWEEN '2026-03-01' AND '2026-07-01' AND status = 'PROCESSED';
-- 5 rows: Mar, Apr, May, Jun, Jul
```

Verified after: 5 periods VOID, 149 lines retained for reference, **0 lines visible to the year-to-date
query** (which filters `status <> 'VOID'`). Reversible by flipping the status back.

**What this retires:**
- The recompute-and-re-file plan. There is nothing to amend — no return was ever filed from here.
- The June ฿0-net employee (10018, salary ฿40,850 against a −฿58,832.83 clawback) as a live labour-law
  and 50 ทวิ incident. It remains a real ENGINE defect worth fixing; nobody went unpaid.
- June's ฿58,329 over-withholding as damage. It is still the clearest available demonstration that the
  ป.96 annualisation defect is real — but it is a demonstration, not money taken from anyone.

**What it does NOT retire:** every engine defect found along the way. They were all reached through
test data, and all of them would have been reached through real data on the first live run.

### 9f. July specifically — accidental, owner-confirmed

`hr.payroll_period` holds a PROCESSED July with 28 lines, ฿590,636 base salary, ฿0.00 withholding and
฿567,503.70 net. The owner confirms **no salary was actually disbursed**. It must be VOIDed, not
re-processed. Until it is, it counts toward every employee's year-to-date and distorts every later
projection.

⚠️ **Consequence for the re-file, and it is the opposite of reassuring.** July's ฿0.00 withholding
was masking June:

| Month | Gross taxable | WHT |
|---|---:|---:|
| Mar | 1,140,644 | 29,519 |
| Apr | 1,052,905 | 20,108 |
| May | 764,481 | 6,968 |
| **Jun** | 1,119,946 | **58,329** |
| **Jul** | 590,636 | **0** |

June carried the large commission month. The pre-ป.96 engine annualised that commission across the
remaining months, projected income that would never arrive, and withheld ฿58,329 — more than double
March's on lower gross. By July the year-to-date withholding already exceeded the projected annual
liability, so `max(ZERO)` took July to zero.

**This is branch 117's P1 defect, in production, in real baht.** With July voided, nothing offsets it:
June's over-withholding stands on its own and is part of what the re-file must correct.

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
   Their `payroll_line` columns were originally expected to arrive with branch 117's V94. **Correction
   (task 3, 2026-07-29):** that expectation was wrong. Branch 117 never merged into this worktree, so
   V94 does not exist here — the `bonus_pay`/`other_one_off_pay` columns actually arrive with **V96**
   (`V96__payroll_component_wht_engine.sql`, task 2 of this branch), not V94. See that migration's own
   comment for the full reasoning.

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

---

# Progress — task 2 of N: classified engine, V96/V97/V98, workbook renumbering, frontend slot 9

**Status: DONE, but never recorded here.** This section is written retroactively by the task-3 agent
(below) because task 2's code exists in this worktree and is exercised by the green suite, but no
handoff entry was ever written for it — the gap CLAUDE.md's "update the handoff before ending" rule
exists to prevent. Reconstructed from the code and its own inline comments, not from memory of doing
the work.

## What task 2 built

1. **`PayrollCalculator#calculateClassified`** — the per-component, three-limb (ป.96 ข้อ 1(4)/(5)/(6))
   withholding engine described in handoff section 1, replacing the single hardcoded limb split.
   `PayrollService#calculateLine` calls **only** this method now; the pre-task-2 `calculate` has zero
   production callers (see task 3's Fix 6 below).
2. **V96** (`payroll_component_wht_engine.sql`) — `bonus_pay`/`other_one_off_pay` columns (moved here
   from an assumed branch-117 V94 that turned out not to exist in this worktree — see the task-1
   correction above), per-limb taxable-income/withholding-tax columns on `payroll_line` and
   `payroll_year_to_date_seed`, `customer_return_already_earned`, `garnishment_type`, and a one-time
   SSO-inclusion backfill for every pre-existing employee (task 1's known risk 3).
3. **V97** (`payroll_meal_and_per_diem_components.sql`) — `meal_allowance`, `per_diem_exempt`,
   `per_diem_taxable`, `per_diem_basis` columns; `MEAL_ALLOWANCE`/`PER_DIEM_TAXABLE` added to
   `PayrollComponent`; `PerDiemBasis` enum.
4. **V98** (`payroll_component_carry_forward.sql`) — `hr.payroll_component_carry_forward`, replacing
   the hardcoded special_pay_1..5 carry-forward assumption with a per-employee, per-component flag
   seeded from the accountant's ledger at a 70%-same-value rule.
5. **Workbook renumbering** (handoff section 9d) — `PayrollService#specialPayDtos` and
   `frontend/.../PayrollPage.jsx`'s `specialPayFields` were moved to the accountant's numbering
   (พิเศษ 2 = ค่าเช่าบ้าน, not 9; commission moved from slot 6 to slot 7; etc). Slot 9 was wired into
   the frontend (`specialPayFields`/`specialPayKeys` now go to 9), closing task 1's known risk 1.
6. `PayrollGarnishmentType` (SALARY/BONUS/OVERTIME_OR_DILIGENCE/SEVERANCE, handoff section 7) and the
   per-type garnishment cap logic in `calculateClassified`.

## What task 2 got wrong (found and fixed by Opus review, then task 3 below)

- `PayrollRepository`'s own copy of the slot→label mapping was **not** moved to the new numbering,
  only `PayrollService`'s was — every processed period read back the wrong Thai labels.
- The garnishment OT/diligence base read `SPECIAL_PAY_4` (ค่าตำแหน่ง under the new numbering), not
  `SPECIAL_PAY_5` (เบี้ยขยันประจำ).
- V97's four new columns were computed into tax but never persisted or read back.
- `resolveEffectiveTaxYear` resolved one year for the whole table, not per employee (the "January
  cliff" task 1 believed it had already fixed — it had fixed the *symptom* for a fresh database, not
  the underlying per-employee case).
- V98's carry-forward flags for slots 6/7/8/9 and meal allowance were seeded but never read.
- No non-negative CHECK on V97's three money columns.

Full detail, fixes, and evidence in "Progress — task 3" below.

## Files changed (task 2, reconstructed)

| File | Change |
|---|---|
| `PayrollClassifiedCalculationDtos.java` | new — `calculateClassified`'s input/output records |
| `PayrollGarnishmentType.java`, `PerDiemBasis.java` | new enums |
| `V96__payroll_component_wht_engine.sql`, `V97__payroll_meal_and_per_diem_components.sql`, `V98__payroll_component_carry_forward.sql` | new migrations |
| `PayrollCalculator.java` | `calculateClassified` added; `calculate` untouched (frozen for `PayrollCalculatorTest`) |
| `PayrollService.java` | `calculateLine` switched to `calculateClassified`; `specialPayDtos` renumbered |
| `PayrollComponent.java` | `MEAL_ALLOWANCE`, `PER_DIEM_TAXABLE`, `BONUS_PAY`, `OTHER_ONE_OFF_PAY` added |
| `PayrollLineDto.java`, `PayrollEmployeeInputRequest.java`, `PayrollCarryForwardDtos.java` | task-2 fields appended (legacy constructors preserved) |
| `PayrollRepository.java` | classification/SSO-inclusion queries, garnishment column, per-limb YTD columns |
| `frontend/.../PayrollPage.jsx` | `specialPayFields` extended to 9, renumbered; `namedAllowanceFields` (meal/per-diem) added |
| `PayrollClassifiedEngineIntegrationTest.java`, `PayrollClassifiedLimbClampReviewTest.java` | new — real-DB coverage of the classified engine |
| Multiple existing payroll tests | widened for the new fields (legacy constructors kept them compiling) |

## Authz evidence (task 2)

No new role gate. `calculateClassified`'s blocker (reject a run with an unclassified non-zero
component) is a data-completeness gate, not a role/scope check — `PayrollService`'s existing
`PAYROLL_VIEW_ROLES`/`PAYROLL_EDIT_ROLES` are unchanged.

---

# Progress — task 3 of N: Opus review fixes (2026-07-29)

Six defects (plus two smaller ones bundled in) found by an Opus review of task 2's uncommitted work,
fixed by a Sonnet implementer per the standing Sonnet-implements/Opus-reviews loop. Every fix below
ships a real-Postgres integration test that was mutation-checked (introduced the bug, confirmed the
specific test — and only that test — goes red, reverted) except where noted.

## Fix 1 (P0) — V97's four columns wired end-to-end

`meal_allowance`, `per_diem_exempt`, `per_diem_taxable`, `per_diem_basis` were read from the request,
folded into tax/SSO arithmetic, then discarded — absent from the INSERT, the SELECT, `PayrollLineDto`,
and the payslip.

- `PayrollClassifiedCalculationDtos.java`: appended `mealAllowance`/`perDiemTaxable` to
  `PayrollClassifiedCalculation`.
- `PayrollCalculator.java` (`calculateClassified`, end of method): echoes
  `input.amountOf(MEAL_ALLOWANCE)`/`PER_DIEM_TAXABLE` onto the two new fields.
- `PayrollLineDto.java`: appended `mealAllowance`/`perDiemExempt`/`perDiemTaxable`/`perDiemBasis`
  (canonical record now 53 fields); added a legacy 49-arg constructor so every prior positional call
  site keeps compiling.
- `PayrollService.java` (`calculateLine`): passes `calculation.mealAllowance()`/`perDiemTaxable()` and,
  since `perDiemExempt`/`perDiemBasis` never reach the calculator (exempt is folded into
  `NON_TAXABLE_INCOME`; basis is pure metadata), the raw request values straight through.
- `PayrollRepository.java`: `findLines` SELECT, `insertLine` INSERT column list + binding, `mapLine` —
  all four columns added.
- `PayslipRenderer.java`: itemises ค่าอาหาร / เบี้ยเลี้ยง (ส่วนเกิน) as earnings rows so รวมรายได้
  still equals the sum of the itemised lines (the class's own documented invariant).
- `V97__payroll_meal_and_per_diem_components.sql` (**edited in place** — unapplied to any real DB):
  added `chk_payroll_line_meal_allowance_non_negative`,
  `chk_payroll_line_per_diem_exempt_non_negative`, `chk_payroll_line_per_diem_taxable_non_negative`
  (Fix 7, same file).
- New test: `PayrollMealAndPerDiemIntegrationTest.java` — pays ฿1,680 meal + ฿700 exempt/฿300 taxable
  per-diem through `PayrollService#process`, re-reads from the DB (not the in-memory return value),
  asserts all four fields and that gross/non-taxable income reflect them; a reprocess-path test; and
  a test proving `chk_payroll_line_per_diem_basis_present` is reachable through the real insert path
  (a hand-crafted line with a non-zero per-diem and a null basis is rejected with
  `DataIntegrityViolationException`).

## Fix 2 (P0) — slot renumbering was half-done

`PayrollRepository`'s private `specialPays(ResultSet)` still carried the OLD slot→label mapping after
`PayrollService#specialPayDtos` moved to the accountant's workbook numbering — every PROCESSED period
read back the wrong Thai label for slots 2-9 (a fresh PREVIEW, never round-tripped through the DB,
showed the correct label for the same money).

- `PayrollRepository.java`: `specialPays(ResultSet)` relabelled to match `PayrollService
  #specialPayDtos` exactly (confirmed against that method and `PayrollPage.jsx`'s `specialPayFields`
  — both already correct — rather than the plan document's own transcription of the numbering, which
  did not match either of them).
- `PayrollComponent.java`: javadoc for `SPECIAL_PAY_2`..`SPECIAL_PAY_9` corrected to match; flagged
  the branch-117 `COMMISSION_SPECIAL_PAY_INDEX` cross-branch break on `SPECIAL_PAY_7`.
- `frontend/.../PayrollPage.jsx`: fixed a stale comment that still described the OLD numbering AND the
  old (now superseded by V98) hardcoded carry-forward exclusion list.
- No other slot→label mapping exists elsewhere in the repo (searched `mockApi.js`, exporters, tests).
- New test: `PayrollSlotLabelAlignmentIntegrationTest.java` — previews a period (labels from
  `PayrollService`), processes and re-reads it (labels from `PayrollRepository`), asserts the two
  sets of nine labels are identical, then pins the actual nine workbook labels so a future edit that
  moves both copies together in the same wrong direction still fails.

## Fix 3 (P1) — garnishment OT/diligence base off-by-one

`PayrollCalculator`'s `OVERTIME_OR_DILIGENCE` garnishment base read `SPECIAL_PAY_4`, which is
ค่าตำแหน่ง (a position allowance) under the new numbering, not เบี้ยขยันประจำ (which moved to
`SPECIAL_PAY_5`) — letting a position allowance inflate the ป.วิ.พ. ม.302 cap and excluding the
diligence pay the cap is actually supposed to be based on.

- `PayrollCalculator.java`: `SPECIAL_PAY_4` → `SPECIAL_PAY_5` in `garnishmentDeduction`'s
  `OVERTIME_OR_DILIGENCE` case.
- `PayrollClassifiedEngineIntegrationTest.overtimeGarnishmentCapsAtThirtyPercentOfOvertimeAndDiligencePaidThisPeriod`:
  rewritten to give the employee BOTH a large `SPECIAL_PAY_4` (ค่าตำแหน่ง, ฿5,000, must be excluded)
  and a `SPECIAL_PAY_5` (เบี้ยขยัน, ฿2,000, must be the actual base) so the test proves exclusion, not
  just a slot swap. Expected cap corrected from the unlawful ฿1,500 (30% of the wrong ฿5,000) to the
  lawful ฿600 (30% of the correct ฿2,000).
- **Mutation-checked**: reverting to `SPECIAL_PAY_4` reproduced exactly the old (larger, unlawful) cap
  and failed only this one test.

## Fix 4 (P1) — `resolveEffectiveTaxYear` was a table-wide MAX, not per-employee

`PayrollRepository`'s `findComponentTaxTreatmentsByEmployee`/`findComponentSsoInclusionByEmployee`
resolved ONE effective tax year for the entire table (`SELECT MAX(tax_year) WHERE tax_year <=
:taxYear`, no `GROUP BY employee_id`). The first employee hired in a new tax year flips that single
MAX forward for **every** employee — every pre-existing employee's classification/SSO-inclusion map
would read back completely empty on the very next payroll run.

- `PayrollRepository.java`: both methods rewritten with a `WITH employee_years AS (SELECT employee_id,
  MAX(tax_year) ... GROUP BY employee_id)` CTE, so each employee independently rolls forward from
  their own most recent `tax_year <= :taxYear`. The now-dead `resolveEffectiveTaxYear` private helper
  and its stale "January cliff" javadoc (which described the OLD, still-buggy fix) were removed.
- New test: `PayrollClassificationReviewIntegrationTest
  .resolvesTheEffectiveTaxYearPerEmployeeNotForTheWholeTable` — employee A gets a 2027 row, employee B
  has only a 2026 row; reading at `taxYear=2027` must resolve both independently.
- **Mutation-checked**: reverting to a table-wide MAX reproduced the exact failure mode — employee B
  vanishes from the result entirely.

## Fix 5 (P1) — 21 of V98's 44 carry-forward flags were dead, plus a second year-rollover cliff

`findCarryForwardSuggestions` joined only `cf1`-`cf5` (special_pay_1..5); V98 also seeds
`SPECIAL_PAY_6`/`SPECIAL_PAY_9`/`MEAL_ALLOWANCE` flags that were stored but never read. Separately,
each `cfN` join matched the SOURCE row's own year exactly, so a flag seeded only for 2026 (V98's
actual one-time seed) stopped applying the moment a source period crossed into 2027.

- `PayrollRepository.java`: `findCarryForwardSuggestions` extended to `LEFT JOIN LATERAL` per slot
  (all nine พิเศษ + meal allowance), each picking the closest `tax_year <= EXTRACT(YEAR FROM
  pp.payroll_month)` for that employee/component — the same "roll forward" resolution as Fix 4, done
  per row via `LATERAL` because the source period differs per employee here.
- `PayrollCarryForwardDtos.java`: `SuggestedInputRow` extended with `specialPay6..9`/`mealAllowance`.
- `PayrollService.java` (`suggestedInputs`): merge logic threads the five new fields through.
- No frontend change needed — `PayrollPage.jsx`'s `adjustmentFromLine` already loops over all nine
  `specialPayFields` reading `suggestion[key]` generically; it was already asking for slots 6-9, just
  getting `undefined` back.
- New tests in `PayrollCarryForwardSuggestionsIntegrationTest.java`: all nine slots + meal carry when
  flagged; a slot with no flag carries zero even when the other eight do (wrong-way-round); a 2026
  flag still applies to a January 2027 source period.
- **Mutation-checked** both halves: reverting the `cf1` LATERAL to an exact-year match reproduced the
  year-rollover cliff and failed only the rollover test. (The slot-extension half has no single line
  to mutate — it is new code with no prior narrower version to revert to; its regression protection is
  the "flag off → zero" wrong-way-round test.)

## Fix 6 (P2) — `PayrollExcelReconciliationTest` reconciled a dead engine

`PayrollService#calculateLine` calls only `calculateClassified`; `PayrollCalculator#calculate` has
**zero** production callers. All 7 tests in `PayrollExcelReconciliationTest` — the only test
reconciling against the accountant's real May 2026 workbook — drove `calculate` directly.

- `PayrollExcelReconciliationTest.java`: repointed at `calculateClassified`, driven with every
  non-zero component classified `REGULAR_REPROJECT` (the layered engine's equivalent of the legacy
  single-limb annualisation when nothing is KNOWN/CUMULATIVE — the two engines share `progressiveTax`/
  `allowanceBreakdown`/etc., so this asks the same question of the same underlying math).
  **Every expected figure is UNCHANGED** — all 7 tests pass with the original transcribed sheet values,
  confirming the classified engine reproduces the workbook exactly (this was the finding to watch for;
  it did not occur).
- `PayrollCalculationInput.java`/`PayrollTaxAllowanceInput.java`: updated the stale javadoc claim that
  `PayrollExcelReconciliationTest` "must not be edited" (it now is, deliberately, per this task) —
  the legacy constructors themselves are untouched and still needed by `PayrollCalculatorTest`.
- **The other ~33 tests exercising `calculate` directly** (`PayrollCalculatorTest`, 27 tests;
  `PayrollClassifiedLimbClampReviewTest` touches `calculate` once incidentally) are **left as-is, not
  deleted** — that is the reviewer's call, per instruction. `calculate` and `PayrollCalculatorTest`
  are genuinely dead in production. Coverage assessment: `progressiveTax`/`allowanceBreakdown`/
  `retirementAllowance`/`parentCareAllowance` are **shared private methods** both engines call, so
  `PayrollCalculatorTest`'s bracket-boundary and allowance-cap tests exercise the same code
  `calculateClassified` uses. Garnishment/SSO-inclusion/limb-layering-specific behaviour has dedicated
  classified coverage in `PayrollClassifiedEngineIntegrationTest` (all 4 garnishment types, SSO
  inclusion flips, 12-month simulation) and `PayrollClassifiedLimbClampReviewTest` (allowance headroom
  clamp, unpaid-leave SSO). Withholding-tax-override behaviour is covered for the classified engine at
  the service layer by `PayrollWithholdingTaxOverrideIntegrationTest` (drives `PayrollService`, which
  calls only `calculateClassified`). **Not independently re-verified for `calculate`'s narrower
  scenarios** (e.g. the exact "byte-identical at zero" regression, leave-refund SSO recompute) — flag
  this to the owner/reviewer as a residual gap if `calculate`/`PayrollCalculatorTest` are ever deleted.

## Fix 7 (P2) — missing non-negative CHECKs

Bundled into Fix 1 above (same migration file, `V97`). `chk_payroll_line_meal_allowance_non_negative`,
`chk_payroll_line_per_diem_exempt_non_negative`, `chk_payroll_line_per_diem_taxable_non_negative`
added, matching the `chk_payroll_line_<column>_non_negative CHECK (<column> >= 0)` style V95/V96 set.

## Explicitly NOT done (per instruction)

- No frequency-count column for `EXTRA_KNOWN_FREQUENCY`. Recorded instead as a javadoc addition on
  `PayrollTaxTreatment#EXTRA_KNOWN_FREQUENCY` stating that `calculateClassified` assumes a count of 1
  unconditionally (correct for the archetypal annual bonus, not for a component known to recur more
  than once a year) — a comment only, no behaviour change. Adding an actual count is new scope for
  the owner to decide.
- PVD untouched.

## Files changed (task 3)

**Edited in place** (both unapplied to any real database, per the migration-numbering rule in this
file's header): `V97__payroll_meal_and_per_diem_components.sql` (three CHECK constraints added).
`V95`/`V96`/`V98` were read but not modified.

| File | Change |
|---|---|
| `PayrollRepository.java` | Fix 1 (INSERT/SELECT/mapLine columns), Fix 2 (label relabel), Fix 4 (per-employee CTE ×2, removed `resolveEffectiveTaxYear`), Fix 5 (LATERAL joins ×10, DTO fields) |
| `PayrollCalculator.java` | Fix 1 (`mealAllowance`/`perDiemTaxable` echoed), Fix 3 (`SPECIAL_PAY_5`) |
| `PayrollClassifiedCalculationDtos.java` | Fix 1 (two new fields) |
| `PayrollLineDto.java` | Fix 1 (four new fields + legacy 49-arg constructor) |
| `PayrollService.java` | Fix 1 (`calculateLine` passthrough), Fix 5 (`suggestedInputs` merge) |
| `PayrollCarryForwardDtos.java` | Fix 5 (`SuggestedInputRow` extended) |
| `PayrollComponent.java` | Fix 2 (javadoc) |
| `PayslipRenderer.java` | Fix 1 (itemised earnings rows) |
| `V97__payroll_meal_and_per_diem_components.sql` | Fix 1/7 (CHECK constraints, edited in place) |
| `frontend/.../PayrollPage.jsx` | Fix 2 (stale comment) |
| `PayrollCalculationInput.java`, `PayrollTaxAllowanceInput.java` | doc-only (stale "must not be edited" claims corrected) |
| `PayrollExcelReconciliationTest.java` | Fix 6 (repointed at `calculateClassified`) |
| `PayrollClassifiedEngineIntegrationTest.java` | Fix 3 (test rewritten) |
| `PayrollClassificationReviewIntegrationTest.java` | Fix 4 (new test) |
| `PayrollCarryForwardSuggestionsIntegrationTest.java` | Fix 5 (3 new tests) |
| `PayrollMealAndPerDiemIntegrationTest.java` | new — Fix 1 |
| `PayrollSlotLabelAlignmentIntegrationTest.java` | new — Fix 2 |

## Commands run

```
cd backend && ./mvnw -B clean verify        # Testcontainers, Docker up
cd frontend && npm run lint && npm test && npm run build
```

## Tests / build results

**Backend: BUILD SUCCESS — 1261 tests, 0 failures, 0 errors, 0 skipped.** Integration tests **RAN**
on Testcontainers (log confirms `Testcontainers version: 2.0.5`, Flyway migrated to v98), not
`TEST_DB_URL`. All 6 new/rewritten test classes confirmed running against real Postgres in the log
(`PayrollCarryForwardSuggestionsIntegrationTest`: 8, `PayrollClassifiedEngineIntegrationTest`: 13,
`PayrollSlotLabelAlignmentIntegrationTest`: 1, `PayrollClassificationReviewIntegrationTest`: 5,
`PayrollExcelReconciliationTest`: 7, `PayrollMealAndPerDiemIntegrationTest`: 3).

**Frontend: lint 0 errors** (1 pre-existing unrelated warning on `PayrollPage.jsx:355`, a missing
`useEffect` dependency, not touched by this task). **715/715 tests pass. Build succeeds.**

## Authz evidence

**No authorization change.** Every fix here is either a data-completeness/business-logic correction
(garnishment base, slot labels, per-employee year resolution, carry-forward join) or a persistence
gap (V97 columns). No role gate, scope filter, or permission check was added, removed, or altered.
`PayrollService`'s existing `PAYROLL_VIEW_ROLES`/`PAYROLL_EDIT_ROLES` are untouched.

## Known risks — carried into the next task

1. **`calculate`/`PayrollCalculatorTest` are genuinely dead code**, kept per instruction. The
   reviewer's call on whether to delete them; if so, port the "byte-identical at zero" and
   leave-refund-SSO-recompute scenarios to a classified-engine test first (see Fix 6 above).
2. **`EXTRA_KNOWN_FREQUENCY`'s "assumes a count of 1" assumption is not documented on the enum** — see
   "Explicitly NOT done" above. A one-line javadoc addition, no code change.
3. Every known risk from task 1's section, still open: `parent_care_count` reconciliation with the
   legacy `parent_care_allowance` baht field (PayrollCalculator's `parentCareAllowance` method already
   resolves this at read time, but the two columns are still both writable with no guard against
   disagreement); the three verification-state writers still ignore update row counts; §10's
   structured dependant records are still unbuilt.
4. **V95/V96/V97/V98 and every file in this section are still uncommitted** on this branch. Nothing
   here has touched any real database — verified against Testcontainers only, per this session's
   explicit instructions.

## The exact next prompt for the next agent

> Rebase `feat/payroll-classification-and-hr-declarations` onto `fix/payroll-wht-po96-compliance`
> (branch 117) once 117 merges, per this handoff's cross-branch break note: 117's
> `COMMISSION_SPECIAL_PAY_INDEX` hardcodes the OLD slot 6 (คอมมิชชั่น before the 2026-07-29
> realignment); it must be updated to `PayrollComponent.SPECIAL_PAY_7` or commission silently lands in
> the wrong ป.96 limb. Also resolve the V94 collision (117 supplies bonus/one-off columns via V94;
> this branch supplies the same via V96 — pick one, forward-only). Read this file in full first, and
> confirm — do not assume — the numbering table in section 9d before touching anything with a พิเศษ
> slot number in it.
