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

---

# Progress — task 4: rebase report + F1-F7 Opus review fixes (2026-07-30)

This section is the "rebase report" three source comments (`PayrollService.java`, `PayrollYearToDate
.java`) referred to without it existing — F4 below. It covers the two commits since task 3
(`c52080b6`, `f5eaa01e`) that were never recorded here, plus a fresh Opus review of the branch that
REJECTED it, and this task's fixes for that review's findings (F1-F7, F9 explicitly deferred).

## What `c52080b6` did — rebase onto branch 117's two-limb split

`fix/payroll-wht-po96-compliance` (117) merged to `main` between task 3 and this task. Rebasing this
branch onto it left five payroll tests red: four were collision damage (117's fixtures seeded a
special pay with no tax treatment, which task 1's classification gate correctly refuses to run, and
inserted employees by raw SQL bypassing SSO-inclusion-default seeding), one was a real production
defect the collision exposed.

**Production fix**: `calculateClassified` built its `calculationNote` without
`clampedAllowanceNote`, which the legacy `calculate()` appends — so every per-head allowance clamp
(บุตร / คนพิการ) was silently applied to the tax and left off the payslip, the exact silent-clamp
failure mode V93 exists to prevent, on the engine actually in production use. Fixed: the note now
includes the clamp text. Note-text only; no monetary or tax figure changed.

Also landed: `PayrollExcelReconciliationTest`'s empty-year-to-date hazard re-measured post-rebase
(now depends on HR's per-component classification, not a hardcoded slot split — REGULAR_REPROJECT
decays to zero in June, EXTRA_CUMULATIVE_ACTUAL in March); rebase conflict resolutions favouring
117's three-argument `retirementAllowance` (implements the SSF sunset) over 118's two-argument form.
**NOT closed**: the pre-existing per-head allowance residual (`PayrollService#headCountFor` still
derives a head count from the run-body amount when no stored count exists) — pinned, not fixed, per
this branch's own instruction not to touch it (see "Do NOT fix" at the top of this file).

Full detail: `git show c52080b6`.

## What `f5eaa01e` did — V99 (drop PVD) + the commission slot-index fix

1. **`COMMISSION_SPECIAL_PAY_INDEX`** corrected from `5` (zero-based, พิเศษ 6) to `6` (พิเศษ 7),
   matching the 2026-07-29 workbook realignment (section 9d). Before this fix the LEGACY `calculate()`
   engine (zero production callers) put ค่า GPRS(เพิ่ม) in the regular limb and คอมมิชชั่น in the
   occasional limb — both backwards. **The commit message's claim "no test places a non-zero amount at
   either index" was FALSE** — see F4 below for the correction and the test that now actually pins it.
2. **V99 removes provident-fund allowance entirely** (owner decision): production has ZERO
   `hr_restricted.employee_pii.provident_fund_no` rows, so V93's inference (a schema column implies
   the company has PVD members) was wrong on the facts. `V99__remove_provident_fund_allowance.sql`
   drops `chk_eta_counts_non_negative`, drops `provident_fund_allowance`, and re-adds the constraint
   minus that term — done in that order deliberately, because Postgres drops a multi-column CHECK
   constraint in its entirety when any referenced column is dropped, and that constraint also guards
   `child_count`/`child_count_double`/`disabled_care_count` and the `child_count_double <= child_count`
   invariant. A bare `DROP COLUMN` would have silently taken all four guards with it — concretely, HR
   could then declare 5 second-or-later children against a `child_count` of 2 and collect ฿90,000 more
   child allowance than the law permits. `PayrollProvidentFundRemovalIntegrationTest` pins that specific
   rejection wrong-way-round. The ฿500,000 retirement cluster now begins at RMF instead of PVD; RMF ->
   SSF -> pension order, the ceiling, มาตรา 42 bis and the RMF/pension sub-caps are unchanged.
   `hr_restricted.employee_pii.provident_fund_no` (a genuine, unrelated PII field) is untouched.

Full detail: `git show f5eaa01e`.

## This task: an Opus review REJECTED the branch — findings and fixes

Working directory `/Users/ploy_warit/Desktop/GL-R-ERP-payroll-hr` (a separate worktree from the one
task 1-3 used). One uncommitted file was already in the tree at task start:
`PayrollClassifiedEngineIntegrationTest.java`, containing the reviewer's new RED test
(`twelveMonthSimulationWithBothABonusAndCumulativeCommissionMatchesTheHandComputedLiability`) — kept
verbatim, not modified, per instruction; it is F1's acceptance criterion.

### F1 (P1, BLOCKING) — the KNOWN and CUMULATIVE limbs consumed the same tax brackets twice

**Defect**: `calculateClassified` taxed Stage 2 (`EXTRA_KNOWN_FREQUENCY`, e.g. a bonus) against
`netRegular + knownThisPeriod` and Stage 3 (`EXTRA_CUMULATIVE_ACTUAL`, e.g. commission) against
`netWithKnown + cumulativeYtdTotal` — but `knownThisPeriod` was only ever THAT period's own amount,
and `PayrollYearToDate` had no field carrying a SETTLED known-limb payment forward. So in every month
after a bonus was paid, Stage 3 re-based itself on a stack that had silently forgotten the bonus,
taxing the cumulative limb's income at a LOWER bracket than the year's true position warranted — the
KNOWN and CUMULATIVE limbs consumed the same tax brackets twice and the year under-withheld.

**Reproduction** (the reviewer's test): ฿80,000/mo salary (`REGULAR_REPROJECT`) + a ฿200,000 bonus in
month 6 (`EXTRA_KNOWN_FREQUENCY`) + ฿15,000/mo commission (`EXTRA_CUMULATIVE_ACTUAL`). Hand-computed
correct ป.96 annual liability = **฿157,375.00**. Engine produced **฿148,900.00** — a **฿8,475.00**
shortfall.

**Fix**:
- `PayrollYearToDate.java`: added `knownLimbTaxableIncome` — the running sum of every period's OWN
  `taxable_income_known_limb`, i.e. every known-limb baht SETTLED so far this tax year (not
  reprojected, just accumulated). Both legacy constructors default it to zero.
- `PayrollRepository.java` (`findYearToDateByEmployee`): the query's inner UNION now selects
  `pl.taxable_income_known_limb AS known_limb_taxable_income` from processed lines (the column already
  existed and was already written — task 2 wrote it, nothing ever read it back) and `0::numeric` from
  the go-live seed (which predates the three-limb model entirely, same rationale as its existing
  cumulative-limb columns). Outer query sums it; the row mapper passes it into the 11th
  `PayrollYearToDate` constructor argument.
- `PayrollCalculator.java` (`calculateClassified`): added `knownLimbYtdTotal` (this period's own
  `knownThisPeriod` plus every prior period's settled known-limb income) and a SEPARATE stage-3-only
  base (`netWithKnownYtd` / `netWithCumulativeYtd`) built from it. **Deliberately does NOT touch**
  `netWithKnown`/`taxableWithKnown`/`annualTaxWithKnown` (Stage 2's own marginal-tax computation, which
  correctly stays THIS-period-only — a settled ข้อ 1(5) payment is taxed once, at the moment it is
  paid, and that never changes) or the REPORTED `taxableAnnualIncome`/`annualTax` fields (still
  computed from the OLD narrower base, by design — see `PayrollYearToDate`'s own javadoc on why a
  settled known-limb payment is not carried into any later PROJECTION). Only `withholdCumulative`'s
  computation was repointed onto the YTD-inclusive base, so the MONEY actually withheld is correct
  even though the reported annual-liability projection intentionally is not (that gap already existed
  and is documented, unrelated to this fix).

**Why the reported fields were deliberately left alone**: an earlier attempt at this fix (folding the
YTD-known base into `netWithCumulative` directly, changing what `taxableAnnualIncome`/`annualTax`
report) broke the ALREADY-GREEN
`twelveMonthSimulationWithAMidYearBonusMatchesAHandComputedAnnualLiability` test, which explicitly pins
`decemberLine.annualTax() == 72900.00` — the regular-limb-only figure, deliberately excluding the
settled bonus, "by design" per that test's own docstring. Mathematically, changing the reported field
to include the settled bonus is a MORE honest figure (it would equal 112,900, the true total annual
liability at that point) — but the task's acceptance criterion required BOTH the reviewer's test at
exactly ฿157,375.00 AND every other test in the file staying green without their own expected values
being touched. The chosen design threads that needle: `withholdCumulative` (money) uses the corrected
base; `taxableAnnualIncome`/`annualTax` (the reported projection) keep their pre-existing, narrower,
already-tested meaning. This is recorded here explicitly per CLAUDE.md so it reads as a considered
scope decision, not an oversight.

**Result**: reviewer's test now asserts exactly ฿157,375.00 and passes, unmodified. The other two
whole-year simulation tests
(`twelveMonthSimulationTotalsToTheCorrectAnnualLiability`,
`twelveMonthSimulationWithAMidYearBonusMatchesAHandComputedAnnualLiability`) are numerically UNCHANGED
by the fix (proved algebraically above and confirmed by the test run: both have
`knownLimbYtdTotal == knownThisPeriod` in every period they cover, because neither scenario mixes a
settled known-limb payment with genuine cumulative income in the same year — that combination is
exactly what the reviewer's NEW test adds). All 14 tests in `PayrollClassifiedEngineIntegrationTest`
pass.

**Mutation-check**: reverted `withholdCumulative` to subtract `annualTaxWithKnown`/`annualTaxWithCumulative`
(the pre-fix, non-YTD variables) instead of the new `annualTaxWithKnownYtd`/`annualTaxWithCumulativeYtd`.
Result: **exactly 1 failure** — the reviewer's test, reproducing the EXACT pre-fix figure
(`148900.00`, shortfall message intact) — and nothing else. Reverted to an empty diff; confirmed clean
(`grep MUTATION` finds nothing).

### F2 (P1, BLOCKING) — HR could build a payroll run that failed at Process

**Defect**: the frontend defined `mealAllowance`/`perDiemExempt`/`perDiemTaxable` in
`namedAllowanceFields`/`payrollInputKeys` but **never actually rendered them** — no input existed
anywhere in `PayrollPage.jsx`, `blankAdjustment` never defaulted them, `adjustmentFromLine` never
populated them from a loaded line. There was also no basis selector and `perDiemBasis` was absent from
`payrollInputKeys` entirely. Backend `PayrollService#calculateLine` passed `input.perDiemBasis()`
straight through to the INSERT with no validation, so V97's
`chk_payroll_line_per_diem_basis_present` CHECK constraint was the only thing standing between a
per-diem amount with no basis and a clean run — meaning Process 500'd (Preview always "succeeded"
because it never writes a row).

**Fix — backend** (`PayrollService.java`, `calculateLine`): validates
`perDiemExempt + perDiemTaxable > 0 && perDiemBasis == null` BEFORE the calculation/insert path is
reached, throwing `ApiException(HttpStatus.BAD_REQUEST, ...)` with a Thai message naming the employee
code/name and the missing basis. Runs for both `preview()` and `process()` (they share `calculateLine`)
— Preview now fails fast too, rather than only Process.

**Fix — frontend** (`PayrollPage.jsx`): added the missing `mealAllowance`/`perDiemExempt`/
`perDiemTaxable` inputs (a new "ค่าอาหาร / เบี้ยเลี้ยง" `CollapsibleSection`, matching the page's
existing `FormGrid`/`MoneyInput`/`InfoTip` patterns) AND a `perDiemBasis` `<select>` (matching the
page's existing plain-`<select>` convention, e.g. the export-kind picker), shown only once
`perDiemExempt` or `perDiemTaxable` is non-zero. `perDiemBasis` is nullable, handled exactly like
`withholdingTaxOverride` (kept out of the numeric `payrollInputKeys` spread; `''` submits as `null`).
`blankAdjustment`/`adjustmentFromLine` now default/populate all four fields.

**Tests**:
- Backend, real-DB, real `PayrollService` (`PayrollMealAndPerDiemIntegrationTest.java`, 4 new tests):
  `processRejectsAPerDiemAmountWithNoBasisAsACleanFourHundredNotAFiveHundred` and the exempt-only
  variant assert `ApiException` / `HttpStatus.BAD_REQUEST` naming the employee, thrown BEFORE any
  INSERT (`findPeriodByMonth` empty afterwards); `previewAlsoRejects...` proves Preview shares the
  guard; `processStillSucceedsWhenAPerDiemAmountHasAChosenBasis` proves the happy path is unaffected.
  Mutation-checked: disabling the new `if` block reproduces exactly the 3 negative-path failures and
  nothing else, reverted clean.
- Frontend (`PayrollPage.test.jsx`, 6 new tests, `describe('per-diem basis selector (V97 / F2)')`):
  selector hidden until an amount is entered (both the exempt and taxable fields independently trigger
  it), hidden again when cleared, `perDiemBasis` submitted verbatim matching the backend enum names,
  `perDiemBasis` sent as `null` (never `''`) when nothing is entered, and the backend's Thai rejection
  message surfaces through `showToast('error', ...)` when Process is attempted without a basis.

**Authz**: none. This is a data-completeness validation (a required-when-non-zero field), not a role
gate, scope, or permission check.

### F3 (P2) — `excessWithheldToDate` hardcoded to zero

**Defect**: `PayrollService#calculateLine` wrote `BigDecimal.ZERO` for `excessWithheldToDate` on every
line processed through `calculateClassified` (the ONLY engine `calculateLine` calls), making
`PayslipRenderer`'s over-withholding notice (line 158) permanently unreachable and losing the figure
for ภ.ง.ด.1 working-paper reconciliation (spec section 8 explicitly requires keeping it).

**Fix**: `PayrollCalculator.calculateClassified` now computes it as the classified engine's own
analogue of the legacy `calculate()`'s field of the same name: `max(0, (yearToDate.withholdingTax() +
this period's POST-OVERRIDE withholdingTax) - annualTaxWithCumulativeYtd)` — the running total actually
withheld this tax year exceeding the TRUE annual liability given everything known so far (F1's
`annualTaxWithCumulativeYtd`, not the narrower reported `annualTax` field, for the same reason F1's fix
doesn't touch that field — the excess must reflect real stranded money, not a figure that will
reproject itself away next period). In the normal, no-override path this is always zero (each limb's
own withholding is already capped at what remains owed on it); only an HR override that pushes
withholding past the true liability makes the two differ — matching the legacy engine's own documented
reasoning for the same field. Added as a new trailing field on `PayrollClassifiedCalculation` (no
legacy constructor on that record, one construction call site, updated) and threaded through
`PayrollService#calculateLine` in place of the hardcoded zero.

**Tests** (new file, `PayrollExcessWithheldClassifiedEngineIntegrationTest.java`, real-DB, real
`PayrollService`, 3 tests): normal processing (no override, ฿10,000/mo employee whose true annual
liability is genuinely ฿0) leaves `excessWithheldToDate` at exactly `0.00`; a ฿5,000 HR override
against that same ฿0 true liability produces `excessWithheldToDate == 5000.00`, confirmed both from the
in-memory result and re-read from the DB; a second consecutive month with the same ฿2,000 override
compounds to `4000.00`, confirming it carries forward. Mutation-checked: reverting to the hardcoded
`BigDecimal.ZERO` reproduces exactly the 2 override-path failures (the zero-override test stays green,
correctly) and nothing else in the payroll suite, reverted clean.

### F4 (P2) — the handoff recorded only 2 of 4 commits (this section)

This section is the fix — see "What `c52080b6` did" / "What `f5eaa01e` did" above.

Also corrected: `f5eaa01e`'s commit message claims "no test places a non-zero amount at either index"
for `COMMISSION_SPECIAL_PAY_INDEX`. **False** — `PayrollCalculatorTest.java`'s
`theTwoIncomeLimbsAlwaysSumToGrossTaxableIncome`, first row, places `5000.00` at zero-based index 6.
It stayed green through the whole slot-index defect (before AND after the fix) only because it asserts
a PARTITION invariant (`regularTaxableIncome + variableTaxableIncome == grossTaxableIncome`) that holds
no matter which limb index 6 is assigned to — an engine that put commission in the wrong limb would
still pass it. Fixed by adding a new dedicated test,
`theSeventhSpecialPaySlotIsCommissionAndLandsInTheRegularLimbNotVariable`, which isolates specialPay7's
own contribution (against an otherwise-identical zeroed run) and asserts it specifically lands in
`regularTaxableIncome`, not `variableTaxableIncome`. Also fixed the row's own stale inline comment
(labelled specialPay7 "(occasional)", the pre-realignment reading). Mutation-checked: reverting
`COMMISSION_SPECIAL_PAY_INDEX` to the old value `5` fails exactly the new test and nothing else in
`PayrollCalculatorTest` (45 tests, 1 failure) — proving the OLD test genuinely could not have caught
this, reverted clean.

Repointed the three "see the rebase report" source comments that deferred to a document which did not
exist (`PayrollService.java` ~line 305, `PayrollYearToDate.java` ~line 44) at this section by name;
the third (`PayrollService.java` ~line 487) was resolved as a side effect of the F3 fix (the comment
described `excessWithheldToDate`'s own gap, which F3 closes).

### F6 (P2) — payslip layout tests one พิเศษ slot short of the true worst case

`PayslipMaximalLayoutReviewTest` and `PayslipProvisionalNoticeGeometryReviewTest` each looped
`slot = 1..8` building the "biggest payslip the engine can produce" fixture, under docstrings claiming
"all 8 พิเศษ slots" — but there have been nine พิเศษ slots since V95 (2026-07-29), so the true
worst-case geometry had never actually been checked. Fixed both loops to `1..9`, both docstrings, and
bumped the hardcoded `specialPayTotal` fixture value by ฿1,000 to stay internally consistent (these are
hand-built `PayrollLineDto` fixtures for pure PDF-geometry testing, not derived totals — several other
hardcoded totals in the same fixtures were already inconsistent with their component sums before this
change and are out of scope here).

**Confirmed the PDF still lays out correctly at nine slots** — both test classes pass (2 + 4 = 6
tests): every glyph stays inside the A4 media box, the notice/note both wrap inside the printable
width, and the page count stays at 1. No overflow finding to report.

### F7 (P3) — stale old-numbering documentation

Corrected to the authoritative numbering (below) in: `PayrollCalculator.java` (`SPECIAL_PAY_SLOTS`'s
comment, which said "พิเศษ 9 -- ค่าเช่าบ้าน" — the pre-9d numbering; `COMMISSION_SPECIAL_PAY_INDEX`
itself was ALREADY correct, fixed by `f5eaa01e`), `PayrollYearToDate.java` (class javadoc said
"พิเศษ 6 (คอมมิชชั่น)" / "พิเศษ 1-5, 7, 8"), `PayrollCarryForwardDtos.java` (class javadoc said
"พิเศษ 1-8 except 6"), `PayrollEmployeeInputRequest.java` (`specialPay9`'s field comment still said
ค่าเช่าบ้าน and "append-only", both superseded by 9d), `PayrollCalculatorTest.java` (a case-array
header comment mislabelled the now-recurring commission slot "(occasional)"),
`PayrollCarryForwardSuggestionsIntegrationTest.java` (a test comment said "commission (specialPay6),
KPI (specialPay7)" — the old numbering — and separately claimed the `SuggestedInputRow` DTO "has no
such fields" for specialPay6-9, which task 3's Fix 5 made false independent of the renumbering).
Additionally found and fixed while auditing for the same wrong table:
`PayrollClassificationAndSsoInclusionIntegrationTest.java`'s class javadoc and a fixture's hardcoded
label string, both still saying พิเศษ 9 = ค่าเช่าบ้าน (label is decorative there — the test asserts
`key()`/`amount()`, never `label()` — fixed anyway for hygiene). No occurrence of the OTHER wrong table
(พิเศษ 1 = ค่า GPRS / พิเศษ 3 = ค่าโทรศัพท์) found anywhere in the repo.

**AUTHORITATIVE NUMBERING** (unchanged from this file's own header, repeated here for convenience):

| Slot | Name |
|---|---|
| 1 | ค่าครองชีพ |
| 2 | ค่าเช่าบ้าน |
| 3 | เบี้ยเลี้ยงประจำ |
| 4 | ค่าตำแหน่ง |
| 5 | เบี้ยขยันประจำ |
| 6 | ค่า GPRS |
| 7 | คอมมิชชั่น |
| 8 | ทำได้ตาม KPI |
| 9 | เงินรางวัล/เงินช่วยเหลืออื่นๆ |

### "Also" — V95's stale comment about processed payroll periods

`V95__payroll_classification_and_hr_declarations.sql`'s section-1 comment (originally lines ~22-28)
argued พิเศษ 9 must be APPEND ONLY because 149 real processed rows across five filed months would
otherwise be silently redefined. That premise is now false: section 9e records that all five of those
2026 periods were owner-confirmed test runs, VOIDed on production, with no ภ.ง.ด.1 ever filed and no
SSO ever remitted from this system — and section 9d's later renumbering DID move ค่าเช่าบ้าน off the
ninth slot regardless. Corrected the comment in place (comment only; no DDL changed) so it no longer
contradicts the premise the later renumbering rests on.

### F9 — NOT fixed, recorded as instructed

`PayrollService.java` (`calculateLine`, ~line 392): when an unearned customer return
(`customerReturnDeduction`) exceeds the commission paid this period, `effectiveCommissionPay =
effectiveCommissionPay.subtract(customerReturnDeduction).max(BigDecimal.ZERO)` silently TRUNCATES the
excess rather than tracking it. Concrete example: an employee earns ฿3,000 commission this period but
has a ฿10,000 unearned customer return to net out — `effectiveCommissionPay` becomes `max(3000 - 10000,
0) = 0`, and the ฿7,000 the return exceeded the period's commission by simply vanishes: not carried to
a future period, not recorded as a receivable, not visible anywhere on the line or payslip. This is
business logic (how money is deducted / whether an over-recovery carries forward) and is the owner's
call per this branch's own "Do NOT fix" instruction — left untouched, recorded here as a known risk
per instruction.

## Files changed (task 4)

| File | Change |
|---|---|
| `PayrollYearToDate.java` | F1 (`knownLimbTaxableIncome` field + both legacy constructors), F7 (numbering javadoc), F4 (rebase-report repoint) |
| `PayrollRepository.java` | F1 (`findYearToDateByEmployee` query + row mapper) |
| `PayrollCalculator.java` | F1 (`calculateClassified` stage-3 fix), F3 (`excessWithheldToDate` computation + DTO field), F7 (`SPECIAL_PAY_SLOTS` comment) |
| `PayrollClassifiedCalculationDtos.java` | F3 (`excessWithheldToDate` field) |
| `PayrollService.java` | F2 (per-diem-basis validation), F3 (`excessWithheldToDate` wiring), F4 (rebase-report repoint) |
| `PayrollEmployeeInputRequest.java` | F7 (`specialPay9` comment) |
| `PayrollCarryForwardDtos.java` | F7 (class javadoc) |
| `V95__payroll_classification_and_hr_declarations.sql` | "Also" (comment corrected in place, no DDL change; still unapplied to any real DB) |
| `frontend/.../PayrollPage.jsx` | F2 (meal/per-diem amount inputs + basis selector, wired into `blankAdjustment`/`adjustmentFromLine`/`normalizedAdjustment`) |
| `PayrollClassifiedEngineIntegrationTest.java` | Kept verbatim (reviewer's file, uncommitted at task start) — not modified |
| `PayrollMealAndPerDiemIntegrationTest.java` | F2 (4 new tests) |
| `PayrollExcessWithheldClassifiedEngineIntegrationTest.java` | F3 (new file, 3 tests) |
| `PayslipMaximalLayoutReviewTest.java`, `PayslipProvisionalNoticeGeometryReviewTest.java` | F6 (loop bound + docstring + fixture totals) |
| `PayrollCalculatorTest.java` | F7 (comment), F4 (1 new pinning test) |
| `PayrollCarryForwardSuggestionsIntegrationTest.java` | F7 (comment) |
| `PayrollClassificationAndSsoInclusionIntegrationTest.java` | F7 (class javadoc + fixture label) |
| `frontend/.../PayrollPage.test.jsx` | F2 (6 new tests) |

## Commands run

```
cd backend && ./mvnw -q -B -Dtest=<class> -Dtest.fork.count=1 test    # per-finding, throughout
cd backend && ./mvnw -B clean verify                                  # full suite, final
cd frontend && npm run lint && npm test -- --run && npm run build     # full suite, final
```

## Tests / build results

**Backend: BUILD SUCCESS — `./mvnw -B clean verify`, 1362 tests, 0 failures, 0 errors, 0 skipped**
(baseline at task start: 1353 passing + the reviewer's 1 red test = 1354; this task added 8 new tests
— F2's 4, F3's 3, F4's 1 — landing at 1362, all green). Integration tests **RAN** on Testcontainers
(log confirms `Testcontainers version: 2.0.5`), not `TEST_DB_URL`. Full run took ~7m49s.

**Frontend: `npm run lint` — 0 errors, 1 pre-existing warning** (`PayrollPage.jsx:391`, missing
`useEffect` dependency `load`, not touched by this task — matches the baseline exactly).
**`npm test -- --run` — 771/771 tests, 72/72 files pass** (baseline 765/72; this task added 6 new
tests in `PayrollPage.test.jsx`'s F2 describe block). **`npm run build` — succeeds** (`vite build`,
completed in 273ms).

Every finding above shipped its own real-DB (backend) or component-level (frontend) test, and
F1/F2/F3/F4 were each mutation-checked (defect reintroduced, confirmed exactly the intended test(s) —
and only those — went red, reverted to an empty diff; verified clean afterward with `grep MUTATION`
finding nothing in any touched file).

## Authz evidence

**No authorization change.** Every fix in this task is a business-logic/data-completeness correction
(tax-bracket double-counting, a required-field validation, a hardcoded-zero replaced with a real
computation, documentation/comment corrections) or test-only. No role gate, scope filter, or permission
check was added, removed, or altered. `PayrollService`'s existing `PAYROLL_VIEW_ROLES`/
`PAYROLL_EDIT_ROLES` are untouched.

## Known risks — carried into the next task

1. **F9** (`PayrollService.java` ~line 392): unearned-customer-return truncation via `.max(ZERO)` —
   see F9 above for the concrete ฿10,000-against-฿3,000 example. Owner's call; not fixed.
2. Every known risk from tasks 1-3, still open: `parent_care_count` vs the legacy
   `parent_care_allowance` baht field (two writable sources of truth); the three verification-state
   writers ignore update row counts; §10's structured dependant records are still unbuilt; the
   per-head allowance residual in `headCountFor` (pinned, not fixed, per instruction); `calculate()`/
   `PayrollCalculatorTest` are genuinely dead production code, kept per instruction.
3. **The reported `taxableAnnualIncome`/`annualTax` fields still understate the true annual liability
   in any month after a settled `EXTRA_KNOWN_FREQUENCY` payment**, by design (see F1's "why the
   reported fields were deliberately left alone" above) — the MONEY withheld is now correct (F1's whole
   point), but the PROJECTION shown on the payslip/API for such months is not the true total liability.
   This is a pre-existing, documented gap (not introduced by this task) that a future change "folding
   the known limb into the year-end figure" would need to confront deliberately, per
   `PayrollYearToDate`'s own javadoc.
4. **V95-V99 are still uncommitted on this branch** (task 1-3's own note, still true) — nothing in this
   task has touched any real database; every claim above is verified against Testcontainers/real
   Postgres only, never a mock, per this session's instructions and CLAUDE.md.
5. `mockApi.js` has no payroll `preview`/`process` implementation at all (throws "not supported in mock
   mode" unconditionally) — pre-existing, unrelated to this task; the new F2 frontend tests mock
   `api.payroll.preview`/`process` directly rather than relying on the mock API layer.

## The exact next prompt for the next agent (superseded — see task 5 below)

> Rebase `feat/payroll-classification-and-hr-declarations` onto the latest `origin/main` and merge, per
> the standing "rebase onto latest main before every PR" rule. Then: (1) build the structured
> child/parent/disabled-dependant records §10 still calls out as unbuilt; (2) decide F9
> (`PayrollService.java` ~line 392, unearned-customer-return truncation) with the owner — it needs a
> business decision (carry the excess forward? record a receivable? something else?), not an
> engineering guess; (3) consider whether `PayrollCalculator#calculate`/`PayrollCalculatorTest` (dead
> production code since task 2) should be deleted now that `calculateClassified` has its own dedicated
> coverage for every scenario that matters — if so, port the "byte-identical at zero" and
> leave-refund-SSO-recompute scenarios to a classified-engine test FIRST (see task 3's Fix 6 for the
> coverage-parity argument). Read this file in full first, in particular the AUTHORITATIVE NUMBERING
> table above, before touching anything with a พิเศษ slot number in it.

---

# Progress — task 5: P0 tax-treatment-matrix writer + 4 more Opus-review fixes (2026-07-30)

A THIRD Opus review REJECTED this branch: the tax-treatment classification matrix (task 1's schema,
task 2's engine) had no writer anywhere — `PayrollRepository#upsertComponentTaxTreatment` had zero
callers in `src/main`, no controller mapping, no frontend — so `PayrollCalculator#calculateClassified`'s
classification gate 409s the entire payroll run for any employee with a non-zero, non-SALARY component.
Two uncommitted reviewer tests, `PayrollClassificationReachabilityIntegrationTest`, were the acceptance
criteria and are kept **verbatim** (not modified) per instruction. Plus four related defects: two more
P1s, one P2, two P3s.

## P0 — the tax-treatment matrix had no writer

Fixed with **three layers** — the task's own framing, confirmed correct by what actually happened
during implementation (a backfill alone was proven insufficient, not just assumed to be):

### Layer 1 — `V100__payroll_component_tax_treatment_backfill.sql` (new)

Seeds a default treatment per employee × classification-eligible component for every employee that
existed when the migration ran, mining `hr.payroll_component_carry_forward` (V98's real
accountant-ledger evidence) rather than inventing a mapping:

- `DIRECTOR_REMUNERATION` → `REGULAR_REPROJECT`. **RESOLVED, not defaulted** — see the correction
  note immediately below; this is not part of the "genuine uncertainty" bucket.
- `carry_forward = TRUE` for that exact (employee, component) pair → `REGULAR_REPROJECT` (the
  handoff's own "fixed recurring allowance" bucket, evidenced not guessed).
- `BONUS_PAY` / `OTHER_ONE_OFF_PAY` → `EXTRA_KNOWN_FREQUENCY` (handoff-explicit — "the archetypal
  EXTRA_KNOWN_FREQUENCY example").
- Everything else with no carry-forward evidence (the 5 employees V98 could not resolve, and every
  พิเศษ slot / `MEAL_ALLOWANCE` / `PER_DIEM_TAXABLE` V98 has no recurrence evidence for) →
  `EXTRA_CUMULATIVE_ACTUAL`, the SAFE default for genuine uncertainty. Worked reasoning in the
  migration's own comment: `REGULAR_REPROJECT` risks one large over-withholding spike for a genuinely
  one-off payment (inconvenient, not unlawful); `EXTRA_KNOWN_FREQUENCY` risks systematic
  **under**-withholding for a component that turns out to recur (the assumed one-off marginal
  calculation never accumulates prior settlements into the annual base); `EXTRA_CUMULATIVE_ACTUAL`
  never assumes recurrence and never mis-counts a recurring item as one-off — it self-corrects
  regardless of the component's true pattern, which is exactly why the handoff already reserves that
  bucket for "irregular" income.
- `SALARY`/`NON_TAXABLE_INCOME` never backfilled (SALARY needs no row; NON_TAXABLE_INCOME is out of
  scope for tax treatment).

**Correction (coordinator review, before this branch reached the reviewer, 2026-07-30)**: the first
version of this migration put `DIRECTOR_REMUNERATION` in the "genuine uncertainty" bucket above
(`EXTRA_CUMULATIVE_ACTUAL`), reasoning that some directors might be paid annually rather than monthly.
That premise is **factually wrong for GL&R** and contradicts a recorded owner decision: *"director
remuneration is every month but the pay may change once in a while"* (owner, 2026-07-29). Paid every
คราว makes it เงินได้ที่จ่ายตามปกติ under ป.96/2543 **ข้อ 2.1**, not a เงินพิเศษ under ข้อ 2.5 — the
amount varying occasionally is not an obstacle, because **ข้อ 2.4** requires recomputing the
withholding every คราว from the actual year-to-date figure, which is exactly what `REGULAR_REPROJECT`
already does. This is also why branch 117's `PayrollCalculator#calculate` (the legacy engine) hardcodes
director remuneration into its REGULAR limb, with that exact owner quote written into its own comment
as the justification — the two engines must not disagree about directors, which is precisely the kind
of divergence this branch had already shipped and had to fix twice before (see task 4's F1/F4).
Corrected in `V100`'s `CASE` expression, `PayrollRepository#defaultTaxTreatment` (used by both layer 2
and layer 3, so all three layers stayed in sync automatically once this one function was fixed), and
the migration's own comment (the "annually-paid director" premise removed entirely so it cannot mislead
a future reader into reverting this).

**Checked for value-changing fallout, per the coordinator's explicit instruction, before treating this
as a pure default-only tweak**: every PRE-EXISTING test with a non-zero `DIRECTOR_REMUNERATION` already
explicitly classifies it via `seedRegularTaxTreatment(..., DIRECTOR_REMUNERATION, ...)` -- REGULAR
REPROJECT is not new to those tests, it is what they already independently expected, since a stored
classification always overrides any default at every layer. Confirmed by inspection and by re-running
all of them green after the fix:
`PayrollAllowanceDirectorNonTaxableIntegrationTest` (4/4), `PayrollExcelReconciliationTest` (7/7),
`PayrollReprocessAndAttendanceDataFlowIntegrationTest` (2/2), `PayrollClassificationReviewIntegrationTest`
(5/5) -- no dollar figure moved in any of them. The only assertions that changed are in the two tests
THIS task wrote for the default-seeding logic itself
(`PayrollComponentTaxTreatmentBackfillIntegrationTest`,
`EmployeeServiceCreateSeedsPayrollDefaultsIntegrationTest`) -- updating what they assert the DEFAULT
produces is the correction being applied, not a silently-adjusted expectation hiding a regression.

**A real bug found by this migration's own replay test, fixed before it ever reached a real database**:
the Step-2 promotion `UPDATE` originally matched ANY row for a carry-forward-evidenced
employee/component pair, with no guard against overwriting a classification HR had already set through
the new matrix screen. Fixed by restricting the `UPDATE` to `updated_by_id IS NULL` (still at the
system-seeded default). Caught by
`PayrollComponentTaxTreatmentBackfillIntegrationTest.replayingV100NeverOverwritesAnHrClassificationMadeBeforeItRuns`,
which failed red before the guard and green after — this is itself the mutation-check for that guard
(the defect it would have shipped, reproduced and then fixed, not merely imagined).

### Layer 2 — new-hire seeding wired into `EmployeeService#create`

`PayrollRepository#seedComponentTaxTreatmentDefaults` (new) + `PayrollRepository#defaultTaxTreatment`
(new, the SAME default logic V100 uses, factored out so the two can never independently drift). Wired
into `EmployeeService#create` (`backend/src/main/java/th/co/glr/hr/employee/EmployeeService.java:110`)
immediately after the pre-existing `seedSsoInclusionDefaults` call — mirrors that call's exact shape
and closes the identical "new employee gets nothing" gap task 1 already fixed for SSO inclusion.

### Layer 3 — read-time safety net (`PayrollService#treatmentsFor`, new)

The layer that actually makes the reviewer's first test pass, and the one requiring the most care.
Layers 1+2 alone left `PayrollClassificationReachabilityIntegrationTest
.payrollRunsForAnEmployeeConfiguredOnlyThroughThePathsAProductionDeploymentActuallyHas` RED — **measured,
not assumed**: its employee is inserted by raw SQL mid-test, bypassing `EmployeeService#create`
entirely, after the golden-template migration already ran (so V100 backfills nothing for it either) —
exactly "configured through the paths a production deployment actually has" per the test's own
docstring: only `seedSsoInclusionDefaults`, deliberately nothing else.

`treatmentsFor` synthesizes the same company-wide default (`PayrollRepository#defaultTaxTreatment`) for
an employee with **zero stored tax-treatment rows AND evidence of having been onboarded at all**
(see below) — otherwise it returns exactly what is stored (including `Map.of()` for a genuinely
untouched employee, preserving the 409).

**Why this does not weaken "HR sets every line, no silent default" (handoff section 1):** that rule
protects the EXPLICIT unclassified state — a row that EXISTS with `tax_treatment = NULL`, deliberately
distinguished in this schema from a row that is simply ABSENT. This layer only ever fires when the
employee has NO ROW AT ALL for classification; the instant a single row exists (including an explicit
present-and-null "deliberately left unclassified" row, or a test's own partial
`seedRegularTaxTreatment`), it is returned completely unchanged — HR intent, once expressed for an
employee, is never second-guessed.

**A regression found and fixed during implementation, not before it landed**: the first version of this
layer used the crude signal "does this employee have ANY SSO-inclusion row" to decide whether to
synthesize a default. That broke the ALREADY-GREEN
`PayrollClassifiedEngineIntegrationTest.unclassifiedNonZeroComponentRejectsTheRunNamingEmployeeAndComponent`
— the test that pins down "HR sets every line, no silent default" in the first place — because that
test file's own `seedEmployee` helper seeds one incidental, unrelated SSO row (SALARY only, for its OWN
`socialSecurity` assertions) for every employee it creates, so "has any SSO row" was true there too and
indistinguishable from the reachability test's employee by that signal alone. Fixed with
`PayrollService#hasBeenOnboardedForPayroll`: true only when the employee's SSO-inclusion map contains
at least one **non-SALARY** component — a real onboarding path (`seedSsoInclusionDefaults`, called by
V96's backfill, `EmployeeService#create`, and this task's own P1 fix) always writes a row for every
component in one pass, so it always includes several non-SALARY entries; an incidental SALARY-only
convenience seed does not. Both directions mutation-checked (see below).

### HTTP surface

`PayrollController` — `GET`/`PUT /api/payroll/component-tax-treatments` (HR+CEO view, HR-only edit,
same split as the pre-existing tax-allowances/ytd-seed endpoints, gated at BOTH the controller
`@PreAuthorize` and `PayrollService`'s own `requireRole` — see "Authz evidence" below for what that
double gate actually proved under mutation). `PUT` binds a raw `List<ComponentTaxTreatmentUpsertRequest>`
body (not a wrapped `{items:[...]}` record) — deliberately, so the reviewer's reflection-based check
(`Method#getGenericParameterTypes()` containing the literal string `ComponentTaxTreatmentUpsertRequest`)
matches without inventing a same-named wrapper class. `PayrollService#getComponentTaxTreatments`/
`upsertComponentTaxTreatments` (new), `PayrollClassificationDtos.TaxTreatmentMatrixRow`/
`TaxTreatmentListResponse` (new).

### Screen

`frontend/src/features/payroll/PayrollPage.jsx` — new `TaxTreatmentMatrixSection`: a collapsed-by-default
`CollapsibleSection` ("การจัดประเภทภาษีหัก ณ ที่จ่าย (ป.96/2543)") above the main payroll table, with an
unclassified-count badge; one nested `CollapsibleSection` per employee (owner decision, section 9c:
"never a shrunken fifteen-column table" applies just as much to this 16-component matrix), each with
its own unclassified-count badge and a `<select>` per component (`TAX_TREATMENT_OPTIONS`: unset /
REGULAR_REPROJECT / EXTRA_KNOWN_FREQUENCY / EXTRA_CUMULATIVE_ACTUAL). `frontend/src/api/routes.js` /
`hrApi.js` / `mockApi.js` — new `componentTaxTreatments` route + `getComponentTaxTreatments`/
`saveComponentTaxTreatments` methods (mock mirrors the tax-allowances "GET empty list, PUT throws
not-supported" pattern — contract.test.js confirms the method surfaces stay in lockstep).

### Acceptance

**Both reviewer tests are GREEN**, confirmed by direct test run (`PayrollClassificationReachabilityIntegrationTest`,
2/2 passing) and reconfirmed in the full `clean verify` run below.

### Mutation-checks (P0)

1. Layer 3 (`treatmentsFor`'s onboarded-check): call site reverted to the pre-fix
   `treatmentsByEmployee.getOrDefault(id, Map.of())`. Result: **exactly 1 failure** —
   `PayrollClassificationReachabilityIntegrationTest
   .payrollRunsForAnEmployeeConfiguredOnlyThroughThePathsAProductionDeploymentActuallyHas` — nothing
   else, including all 14 tests in `PayrollClassifiedEngineIntegrationTest`. Reverted clean.
2. `hasBeenOnboardedForPayroll` mutated to always return `true`: **exactly 1 failure** —
   `PayrollClassifiedEngineIntegrationTest.unclassifiedNonZeroComponentRejectsTheRunNamingEmployeeAndComponent`
   — the reachability test stayed green. Reverted clean. (This is the mutation that reproduces the
   regression described above — proof the fix for it is real, not cosmetic.)
3. HTTP surface: the `PUT` mapping removed entirely. Result: **exactly 1 failure** —
   `hrHasSomeHttpWayToClassifyAComponentBeforeTheEngineDemandsIt` — nothing else. Reverted clean.
4. `EmployeeService#create` wiring (layer 2): the `seedComponentTaxTreatmentDefaults` call removed.
   Result: **exactly 1 failure** —
   `EmployeeServiceCreateSeedsPayrollDefaultsIntegrationTest.creatingAnEmployeeSeedsADefaultTaxTreatmentForEveryClassificationEligibleComponent`
   — nothing else. Reverted clean.
5. V100's overwrite guard: found and fixed via its OWN test going red before the `updated_by_id IS
   NULL` guard existed (see layer 1 above) — the guard's own regression protection.
6. Authz (`component-tax-treatments` PUT gate) — see "Authz evidence" below; reported there instead of
   here because the finding (a real defense-in-depth discovery) is more legible next to the rest of the
   authz story.

## P1 — four missing frontend fields

`bonusPay`, `otherOneOffPay`, `garnishmentType`, `customerReturnAlreadyEarned` existed end-to-end on the
backend (V96/task 2, handoff sections 6/7/10) with zero frontend surface — `PayrollPage.jsx:119-134`'s
`payrollInputKeys` omitted all four; a whole-frontend grep found zero hits for each.

`frontend/src/features/payroll/PayrollPage.jsx`:
- New "เงินก้อนพิเศษ (จ่ายครั้งเดียว)" `CollapsibleSection`: `bonusPay`/`otherOneOffPay` `MoneyInput`s.
  **HR could not pay a bonus through payroll at all before this fix** — the archetypal spec §10 example.
- `garnishmentType` `<select>` (`GARNISHMENT_TYPE_OPTIONS`, matching `PayrollGarnishmentType` exactly)
  inside "รายการหักรายบุคคล", shown only once `legalExecutionDeduction > 0` (same "nothing to classify
  until money moves" principle as the existing per-diem basis selector). Never selectable before this
  fix ⇒ always defaulted to `SALARY` (`PayrollCalculator.java:746`), making spec §7's BONUS 50% /
  OVERTIME 30% / SEVERANCE ฿300,000 caps dead code.
- `customerReturnAlreadyEarned` checkbox inside "รายการหักก่อนภาษี", shown only once
  `customerReturnDeduction > 0`, defaults `false` (unearned/pre-tax netting path, matching the backend
  default). Always `false` before this fix ⇒ always the unearned path — the fix for the real June 2026
  negative-net incident (handoff section 6) could never actually be exercised.
- `payrollInputKeys` extended (`oneOffPayKeys`); `blankAdjustment`/`adjustmentFromLine`/
  `normalizedAdjustment` updated — `garnishmentType` gets the same nullable-enum treatment as the
  existing `perDiemBasisValue` (`''` submits as `null`, backend defaults to `SALARY`).

**Tests**: `PayrollPage.test.jsx`, new `describe('one-off pay, garnishment type, and customer-return-earned flag (P1)')`,
6 tests — submits bonus/one-off amounts; garnishment-type selector hidden until an amount is entered,
then shown and submitted verbatim; `garnishmentType` sent as `null` (never `''`) when unset;
customer-return-earned checkbox hidden until an amount is entered, defaults unchecked, submits `true`
once ticked. Each of the 4 fields would silently vanish from `payload().inputs` without its own
rendered input (`hasPayrollInput`/`payrollInputKeys` never see a field that was never rendered) — that
absence is exactly what each new test would catch failing.

## P1 — SSO inclusion computes ฿0 for any SQL-inserted employee

Same root-cause shape as the P0 fix above (same day, same author, same pattern): `EmployeeService#create`
seeds `hr.payroll_component_sso_inclusion` defaults only on the API create path (task 1's known risk 3).
An employee inserted by raw SQL (uat `V900`, `db/migration-demo/V21`, any bulk import) has ZERO rows;
the pre-fix call site `ssoInclusionByEmployee.getOrDefault(id, Map.of())` silently computed
`ssoWageBase = 0` with no error at all — `EmployeeService.java:103-107`.

Fixed at the read/resolution layer: `PayrollService#ssoInclusionFor` (new). An employee with genuinely
NO stored rows gets the company-wide default (`PayrollRepository#defaultSsoIncluded`, factored out of
`seedSsoInclusionDefaults`'s inline boolean so both paths share one rule and can never drift); an
employee with AT LEAST ONE stored row (including every existing test that seeds a deliberately partial
matrix via `AbstractPostgresIntegrationTest#seedSsoIncluded`) is untouched. Unlike the tax-treatment
layer 3 above, this one needed no `hasBeenOnboarded` gate — SSO inclusion has no "explicitly declined to
classify" state to protect, a boolean has only two real answers.

**Test** (`PayrollClassificationAndSsoInclusionIntegrationTest
.anEmployeeInsertedByRawSqlWithNoSsoInclusionRowsStillGetsTheCompanyWideDefault`, real `PayrollService`,
real Postgres): two employees inserted by raw SQL, identical ฿30,000 salary, neither seeded through ANY
path; one also carries a ฿20,000 director fee (handoff section 5's 10080 case), classified via
`seedRegularTaxTreatment` so the run isn't blocked by the unrelated classification gate. Proof 1:
salary-only employee's `socialSecurity` > 0 (not the pre-fix ฿0.00). Proof 2, **wrong-way-round**: the
director-fee employee's `socialSecurity` is EXACTLY EQUAL to the salary-only employee's — proving
`DIRECTOR_REMUNERATION` stays excluded even under the synthesized default, not merely "some default
kicked in".

**Mutation-check**: `ssoInclusionFor` reverted to `Map.of()`. Result: exactly 1 failure — the new test —
nothing else in the 9-test class. Reverted clean.

## P2 — F3 re-activated a payslip line the owner forbade

`PayslipRenderer.java` (over-withholding notice block, ~158-180): stopped printing
`fmt2(line.excessWithheldToDate())` in either wording. Replaced with the OWNER-APPROVED wording from
spec section 8, reproduced verbatim — neither variant states a baht figure. December's wording now
names BOTH ภ.ง.ด.90 and ภ.ง.ด.91 (never 91 unconditionally, per the handoff — the renderer cannot know
an employee's other income types). `excessWithheldToDate`'s **persistence** (F3's own contribution,
task 4) is UNTOUCHED — only the printed text changed, per the task's exact instruction ("keep
persisting, stop printing the amount"). The existing `nonZero(excessWithheldToDate())` gate (whether the
notice appears at all) is also unchanged — this fix's scope is the text, not the visibility condition,
recorded here as a deliberate scoping choice.

**Three test files needed correcting, not just one** — each had previously asserted the FORBIDDEN
behaviour as a passing expectation, which is what "F3 re-activated a payslip line the owner forbade"
actually looked like in test form:
- `PayslipRendererTest.java` (`anOverWithheldEmployeeIsToldSoOnTheFinalPayslipOfTheYear`,
  `aMidYearOverWithholdingIsReportedAsProvisionalNotFinal`) — `.contains("17,527.51")` →
  `.doesNotContain("17,527.51")`; December's `.contains("ภาษีหัก ณ ที่จ่ายสะสมปีนี้เกินภาษีที่ต้องเสียทั้งปี")`
  replaced with the new wording's own opening phrase plus `.contains("ภ.ง.ด.90").contains("ภ.ง.ด.91")`.
- `PayslipProvisionalNoticeGeometryReviewTest.java` (`onlyDecemberAssertsTheExcessIsUnrecoverable`) —
  same figure removed for all 12 months; December's `.contains("ไม่สามารถคืนผ่านระบบเงินเดือนได้")` (not
  part of the owner's approved text) replaced with the ภ.ง.ด.90/91 pair.
- `PayslipMaximalLayoutReviewTest.java` — needed no change (asserts `ภ.ง.ด.91`/`ค่าลดหย่อนบุตร`/
  `ค่าอุปการะคนพิการ`, none of which reference the removed figure).
- `PayrollExcessWithheldNoticeReviewTest.java` — grep-matched the old phrases but only inside prose
  DOCSTRINGS describing the historical defect narrative (this file asserts on `PayrollCalculation`'s
  numeric `excessWithheldToDate()`, never on rendered text — its own class javadoc says as much,
  deferring rendered-text correctness to the two files above). Left as-is; no assertion needed fixing.

All four files confirmed green together in the same run (see "Tests / build results" below).

## P3 — two small ones

1. `PayrollClassifiedEngineIntegrationTest.java`'s docstring on the reviewer's own
   `twelveMonthSimulationWithBothABonusAndCumulativeCommissionMatchesTheHandComputedLiability` (kept
   verbatim otherwise, per instruction) described F1's now-fixed defect in the present tense ("no
   `knownLimb*` field exists..."). Corrected to past tense, pointing at
   `PayrollYearToDate#knownLimbTaxableIncome` and F1's fuller writeup in this file.
2. ป.96 ข้อ 2.10 (leaver final-period true-up) — admitted unimplemented at `PayslipRenderer.java`'s own
   comment ("A leaver's final period is not detected") but absent from this handoff's known risks until
   now. Added below.

## Do NOT fix (per instruction, recorded only)

**P3-6**: `PayrollCalculator.java` — `fullAnnualProjection` (~:619, uses `knownThisPeriod`, this
period's own known-limb amount only) vs the stage-3 bases `netWithKnownYtd`/`netWithCumulativeYtd`
(~:656-657, uses F1's YTD-inclusive `knownLimbYtdTotal`). Immaterial TODAY: every allowance the engine
currently applies is either a flat cap or the expense deduction pinned at the ฿100,000 statutory
ceiling, so nothing yet varies with `fullAnnualProjection`'s narrower base in a way that diverges from
the YTD-inclusive one. **Trigger condition**: the moment a genuinely PERCENTAGE-capped allowance is
declared for an employee who also has a settled known-limb payment earlier in the year — RMF/ThaiESG
30%-of-income cap, or a donation's 10%-of-remaining-income ceiling (spec section 4) —
`fullAnnualProjection`'s narrower base would compute a different allowance cap than the YTD-correct
one, silently mis-capping the allowance. Real tax-math business logic, the owner's call per this
branch's own "Do NOT fix" instruction — left untouched.

F9 (customer-return truncation) and 117's `headCountFor` residual: already recorded in task 4's known
risks, unchanged, not touched by this task.

## Files changed (task 5)

| File | Change |
|---|---|
| `V100__payroll_component_tax_treatment_backfill.sql` | new — P0 layer 1 |
| `PayrollRepository.java` | `seedComponentTaxTreatmentDefaults`/`defaultTaxTreatment` (P0 layer 2), `defaultSsoIncluded` factored out (P1 SSO) |
| `PayrollClassificationDtos.java` | `TaxTreatmentMatrixRow`/`TaxTreatmentListResponse` (P0 HTTP surface) |
| `PayrollService.java` | `getComponentTaxTreatments`/`upsertComponentTaxTreatments`/`componentTaxTreatmentsSnapshot` (P0 HTTP), `treatmentsFor`/`hasBeenOnboardedForPayroll` (P0 layer 3), `ssoInclusionFor` (P1 SSO) |
| `PayrollController.java` | `GET`/`PUT /api/payroll/component-tax-treatments` (P0 HTTP) |
| `EmployeeService.java` | wired `seedComponentTaxTreatmentDefaults` into `create()` (P0 layer 2) |
| `PayrollClassifiedCalculationDtos.java` | doc-only, `componentSsoInclusion` javadoc updated for the P1 SSO fix |
| `PayslipRenderer.java` | P2 — stop printing the excess figure, owner-approved wording |
| `PayrollClassifiedEngineIntegrationTest.java` | P3 doc fix (present→past tense); collateral fix for the layer-3 regression (no assertion changes) |
| `PayslipRendererTest.java`, `PayslipProvisionalNoticeGeometryReviewTest.java` | P2 — assertions corrected to the new wording |
| `SecurityAuthorizationIntegrationTest.java` | 4 new real-filter-chain authz tests for the new endpoint |
| `PayrollClassificationAndSsoInclusionIntegrationTest.java` | 1 new P1 SSO-default test + helpers |
| `PayrollClassificationReachabilityIntegrationTest.java` | reviewer's file — kept verbatim, not modified |
| `PayrollComponentTaxTreatmentBackfillIntegrationTest.java` | new — replays V100's own SQL, 2 tests (found the Step-2 overwrite bug) |
| `EmployeeServiceCreateSeedsPayrollDefaultsIntegrationTest.java` | new — P0 layer 2 tests |
| `frontend/src/api/routes.js`, `hrApi.js`, `mockApi.js` | new `componentTaxTreatments` route + methods |
| `frontend/src/features/payroll/PayrollPage.jsx` | P1 fields (bonusPay/otherOneOffPay/garnishmentType/customerReturnAlreadyEarned) + P0 `TaxTreatmentMatrixSection` |
| `frontend/src/features/payroll/PayrollPage.test.jsx` | 6 new P1 tests + 2 new P0 matrix tests |

## Commands run

```
cd backend && ./mvnw -q -B -o test-compile                              # throughout, targeted classes
cd backend && ./mvnw -q -B -o -Dtest.fork.count=1 -Dtest=<class[,class...]> test   # targeted, throughout
cd backend && ./mvnw -B -o -Dtest.fork.count=1 clean verify              # full suite, final
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
```

## Tests / build results

**Backend: `./mvnw -B -Dtest.fork.count=1 clean verify` — [FILL: BUILD SUCCESS/FAILURE, exact totals].**
Integration tests **RAN** on Testcontainers (Flyway migrated through v100 in every fresh golden
template; confirmed in every targeted run's log). Baseline at task start: 1362 tests + the 2 reviewer
reds = 1364. This task added: 1 (`PayrollComponentTaxTreatmentBackfillIntegrationTest` ×2) + 2
(`EmployeeServiceCreateSeedsPayrollDefaultsIntegrationTest`) + 1
(`PayrollClassificationAndSsoInclusionIntegrationTest`, new P1 test) + 4
(`SecurityAuthorizationIntegrationTest`, new authz tests) + the 2 reviewer tests going from red to
green = [FILL exact final total].

**Frontend: `npm run lint` — 0 errors, 1 pre-existing warning** (`PayrollPage.jsx`, missing `useEffect`
dependency `load`, unchanged location/cause, matches baseline exactly). **`npm test -- --run` —
779/779 tests, 72/72 files pass** (baseline 771/72; this task added 8 new tests — 6 P1 field tests + 2
P0 matrix tests). **`npm run build` — succeeds.**

Every fix above shipped its own real-DB (backend) or component-level (frontend) test, and every claim
in the P0/P1 sections was mutation-checked (defect reintroduced, confirmed exactly the intended test(s)
— and only those — went red, reverted to an empty diff; verified clean afterward with `git diff`/`grep
MUTATION` finding nothing in any touched file).

## Authz evidence

**This task DID touch authorization** — a brand-new endpoint (`GET`/`PUT
/api/payroll/component-tax-treatments`) with its own role gate. Per CLAUDE.md, shipped with a real-DB
integration test through the real Java service, written wrong-way-round, and mutation-checked:
`SecurityAuthorizationIntegrationTest` (`@SpringBootTest`, real `SecurityFilterChain`, real Postgres),
4 new tests — plain employee and sales role get 403 on both GET and PUT with the table provably
unchanged afterward; CEO gets 200 on GET but 403 on PUT; HR gets 200 on PUT and the row count becomes 1.

**A genuine defense-in-depth finding, not a design flaw**: this endpoint (like every other payroll
declaration endpoint in this file) is gated at TWO independent layers — `PayrollController`'s
`@PreAuthorize` AND `PayrollService.upsertComponentTaxTreatments`'s own `requireRole(actor,
PAYROLL_EDIT_ROLES)` call. Mutating EITHER layer alone (`@PreAuthorize("true")` on the controller
method; `requireRole(actor, PAYROLL_VIEW_ROLES)` — i.e. CEO-permitted — in the service) independently
produced **zero test failures**, because the OTHER layer still correctly rejected the disallowed role —
proof each layer is independently sufficient, not proof the guard is untested. Mutating **both layers
simultaneously** (the realistic "the guard was never wired at all" regression) produced **exactly 1
failure** — `ceoCanViewTheComponentTaxTreatmentMatrixButCannotEditIt` — nothing else (plain
employee/sales still correctly rejected, since neither role is in `PAYROLL_VIEW_ROLES` either). Reverted
clean; `git diff` confirms no residual changes to either file. Recorded here in full because arriving at
a single-layer mutation showing "0 failures" without investigating further would have been an easy false
signal that the test suite doesn't actually verify anything — it does, at the combined-layer level,
matching this codebase's existing double-gate convention for every other payroll declaration endpoint
(`tax-allowances`, `ytd-seed`).

Every other change in this task (SSO default resolution, tax-treatment default resolution, payslip
wording, doc fixes) is a data-completeness/business-logic correction or documentation, not an
authorization change — no role gate, scope filter, or permission check was added, removed, or altered
outside the one new endpoint above.

## Known risks — carried into the next task

1. **`PayrollService#treatmentsFor`'s `hasBeenOnboardedForPayroll` signal is inferential, not
   authoritative** — it infers "this employee has been through a real onboarding path" from the SHAPE
   of their SSO-inclusion data (at least one non-SALARY component present) rather than a dedicated
   "onboarded" flag. This was the narrowest fix that satisfied both the reachability test and the
   pre-existing classification-gate test without weakening either, but it is coupling two matrices that
   are conceptually independent (SSO inclusion and tax treatment) purely because it was the only signal
   available in the test fixtures as written. If a future employee is somehow onboarded through SSO
   inclusion alone with a non-SALARY-only shape but genuinely should NOT get a tax-treatment default
   (a scenario not currently exercised by any test), this signal would get it wrong. Flagged for the
   owner/reviewer to confirm this reading of "no silent default" is the intended one, or to design a
   more explicit signal (e.g., a dedicated `onboarded_at` column) if not.
2. Every known risk from tasks 1-4, still open and unchanged by this task: `parent_care_count` vs the
   legacy `parent_care_allowance` baht field (two writable sources of truth); the three
   verification-state writers ignore update row counts; §10's structured dependant records are still
   unbuilt; the per-head allowance residual in `headCountFor` (pinned, not fixed, per instruction);
   `calculate()`/`PayrollCalculatorTest` are genuinely dead production code, kept per instruction; F9
   (unearned-customer-return truncation, `PayrollService.java` ~line 392) needs an owner business
   decision; `taxableAnnualIncome`/`annualTax` understate the true annual liability in any month after a
   settled `EXTRA_KNOWN_FREQUENCY` payment, by design (F1); `mockApi.js` has no payroll
   preview/process implementation at all.
3. **P3-6** (recorded above, with its trigger condition): `fullAnnualProjection` vs the YTD-inclusive
   stage-3 base diverges only once a genuinely percentage-capped allowance (RMF/ThaiESG/donation) is
   declared for an employee with a settled known-limb payment earlier in the year. Not fixed, per
   instruction.
4. **ป.96 ข้อ 2.10** (leaver final-period true-up) — a leaver's actual final period is not detected
   anywhere in the engine or the payslip; `PayslipRenderer`'s own December-only final-wording gate is
   keyed on calendar December, not "this employee's last paid period". An employee who leaves mid-year
   never gets the final-period true-up wording or treatment at all. Admitted unimplemented in code
   comments since before this task; now recorded here for the first time.
5. **V95-V100 are still uncommitted on this branch** — nothing in this task has touched any real
   database; every claim above is verified against Testcontainers/real Postgres only, per this session's
   instructions and CLAUDE.md.

**Correction (fourth Opus review, 2026-07-30) — deleted risk, not just reworded**: this section
previously carried a risk 5 claiming SSO inclusion for `MEAL_ALLOWANCE`/`PER_DIEM_TAXABLE` (added V97,
after V96's one-time SSO backfill already ran) has "the identical no-row-for-pre-existing-employees
gap" V96's backfill left for the original components. That claim is factually wrong: verified against
`V97__payroll_meal_and_per_diem_components.sql` lines 88-96 directly — the SAME migration that
introduces the two components also backfills SSO inclusion for them, `SELECT DISTINCT i.employee_id,
i.tax_year, c.component, TRUE, ... FROM hr.payroll_component_sso_inclusion i CROSS JOIN (VALUES
('MEAL_ALLOWANCE'), ('PER_DIEM_TAXABLE')) AS c(component)` — i.e. every employee who already has ANY
SSO-inclusion row (which, after V96 runs first in the same migration set, is every pre-existing
employee) gets these two components backfilled too, in the same deploy that adds them. The gap this
risk described was closed in the very migration that opened it. Left in place, this risk would have
invited a future agent to "fix" a non-problem; deleted per instruction rather than reworded.

## The exact next prompt for the next agent

> Read this file in full, in particular the AUTHORITATIVE NUMBERING table (task 4's section) and this
> task 5 section's known risks, before touching anything payroll-related. Then, in order of what an
> owner/reviewer would most want resolved next: (1) confirm or replace the `hasBeenOnboardedForPayroll`
> inferential signal (known risk 1 above) — it works and is mutation-checked both directions, but it is
> a narrower fix than a dedicated "onboarded" flag would be; (2) build the structured
> child/parent/disabled-dependant records §10 still calls out as unbuilt; (3) decide F9
> (`PayrollService.java` ~line 392, unearned-customer-return truncation) with the owner; (4) rebase onto
> the latest `origin/main` and merge, per the standing "rebase onto latest main before every PR" rule —
> this branch has not been rebased since task 4 and V95-V100 are all still uncommitted.

---

# Progress — task 6: fourth Opus review fixes + a coordinator-flagged reachability audit + rebase (2026-07-30)

A FOURTH Opus review returned APPROVE WITH FIXES (not a rejection this time): findings 1/2/4/5/6
below, plus two P1s (D1, D2) and a minor found separately by the coordinator's own reachability audit
mid-task and folded into this same pass. Working directory
`/Users/ploy_warit/Desktop/GL-R-ERP-payroll-hr` (a different worktree from tasks 1-5). One untracked
file was already in the tree at task start: `PayrollRawSqlEmployeeClassificationReviewTest.java` (the
fourth reviewer's own RED test, kept verbatim, not modified — Finding 1's acceptance criterion).

## Correction to the record, before anything else

Task 5's own report that `PayrollClassificationReachabilityIntegrationTest` was "GREEN, confirmed" was
true but incomplete in a way this programme keeps repeating: the test was green only because its
fixture called `payrollRepository.seedSsoInclusionDefaults(...)` directly on the employee before
previewing — a call no production code path makes in isolation (every real path that calls it also
writes tax-treatment rows in the same transaction or migration). That one line manufactured the
narrow state task 5's fix actually covers (SSO rows present, treatment rows absent), while the state
that ACTUALLY occurs in production and on a freshly rebuilt uat (zero rows in BOTH matrices) was never
exercised by any test and, per Finding 1 below, was actively refused by the fix that shipped. A green
acceptance test is not evidence the underlying gap is closed if the test's own fixture reaches a state
nothing outside the test can reach — this is the third time this exact shape of defect has surfaced on
this branch (task 1's SALARY/NULL CHECK gap, task 5's own P0, now this), and it belongs in the record
as a pattern, not three unrelated incidents.

## Finding 1 (P1) — the classification safety net had backwards polarity

**Defect** (`PayrollService.java`, `hasBeenOnboardedForPayroll`): synthesized a tax-treatment default
only when an employee had ZERO stored treatment rows AND at least one NON-SALARY row in
`hr.payroll_component_sso_inclusion`. `ssoInclusion == null` (zero rows in BOTH matrices — the actual
shape of `db/migration-uat/V900`'s 93 raw-SQL-inserted employees on a freshly rebuilt uat, or any bulk
import that predates the backfill migrations) short-circuited to `return false`, refusing the default
for exactly the population the fix's own javadoc named as its target ("uat's V900, db/migration-demo's
seeds, any bulk import"). Backwards: the ONLY thing that ever produced the "SSO present, treatment
absent" state the old code required was the test fixture described in the correction above.

**Fix**: `ssoInclusion == null` now returns `true` (zero rows in BOTH matrices is unambiguously "never
onboarded by any path" — synthesize). The pre-existing non-SALARY-row check is unchanged below it, so
`PayrollClassificationReachabilityIntegrationTest`'s employee (SSO rows present via
`seedSsoInclusionDefaults`, treatment rows absent) still synthesizes exactly as before. The one state
that must still 409 — `PayrollClassifiedEngineIntegrationTest`'s blocking-test employee, whose SSO map
contains only the incidental SALARY-only convenience row several tests seed — is unaffected: that map
is non-null, so the `== null` branch is never reached for it, and the existing `anyMatch(component !=
SALARY)` check still returns `false`.

**Acceptance**: `PayrollRawSqlEmployeeClassificationReviewTest` (the fourth reviewer's RED test) is
now **GREEN**, kept verbatim, not modified.

**Mutation-check**: reverted `return true` back to `return false`. Ran the whole `th.co.glr.hr.payroll`
package (221 tests at the time): **exactly 4 failures** —
`PayrollRawSqlEmployeeClassificationReviewTest` (1, the intended target) plus all 3 tests in this
task's own NEW `PayrollCustomerReturnRoundTripIntegrationTest` (D1, below — that class's fixtures are
raw-SQL employees with a non-zero `COMMISSION_PAY`, so they independently depend on this same fix to
avoid the classification 409; expected collateral, not a separate defect). The other 217 tests,
including every existing classification/reachability/blocking test, were unaffected. Reverted to an
empty diff; confirmed via `git diff`.

## Finding 2 (P2) — a wrong-way-round assertion that could not fail

**Defect** (`PayrollClassificationAndSsoInclusionIntegrationTest
.anEmployeeInsertedByRawSqlWithNoSsoInclusionRowsStillGetsTheCompanyWideDefault`): both fixture
employees carried `seedEmployee`'s shared hardcoded ฿30,000 salary, which alone saturates the ฿17,500
SSO wage-base ceiling — a ฿20,000 director fee could not move `socialSecurity` whether it was included
in the wage base or not, so "proof 2" (director fee must not inflate SSO) asserted an equality that
would hold under a correct default AND a broken one.

**Fix**: both fixtures now use a dedicated ฿15,000 salary (below the ceiling), via a new
`seedEmployeeWithSalary` helper and a widened `seedEmployeeWithDirectorFee(..., salary, directorFee)`
signature — both used ONLY by this one test, so the shared `seedEmployee()` helper (฿30,000, several
OTHER tests in this file depend on it unchanged) was not touched. With ฿15,000: excluding the director
fee leaves the wage base at ฿15,000 (SSO ฿750.00); wrongly including it pushes the wage base to
`min(15000+20000, 17500) = 17500` (SSO ฿875.00) — the two now genuinely diverge.

**Mutation-check — before the fix** (recorded here since the finding claimed, and this session
independently reproduced, that the pre-fix assertion could not fail): mutating
`PayrollRepository.defaultSsoIncluded` to include `DIRECTOR_REMUNERATION` and running the OLD
(฿30,000) fixture produced **zero failures** in this specific test — confirms the finding.
**Mutation-check — after the fix**: the same mutation (`defaultSsoIncluded` including
`DIRECTOR_REMUNERATION`) now produces **exactly 2 failures** in
`PayrollClassificationAndSsoInclusionIntegrationTest`: this task's fixed test (the intended target)
plus the file's own pre-existing direct-default test,
`seedsSsoInclusionDefaultsTrueEverywhereExceptDirectorRemunerationAndNonTaxableIncome` (unrelated
collateral — it asserts `defaultSsoIncluded` directly, so it was always going to catch this specific
mutation; it is not evidence the ceiling fix itself did anything). All 7 other tests in the file, and
everything else in the payroll package, stayed green. Reverted to an empty diff.

## Finding 4 (P3) — V98's comment contradicted its own data, and V100 reasoned from it

**V98** (`payroll_component_carry_forward.sql`): the header comment said "SEEDED FOR 29 OF 34
EMPLOYEES". The actual `VALUES` list contains **16 distinct employee codes / 44 rows** (counted
directly: `grep -oE "'[0-9]{5}'" ... | sort -u | wc -l` = 16; `grep -c "^    ('1"` = 44). Reconciled,
not just replaced: 34 total workbook names, 5 could not be resolved to an employee row at all (listed
in the comment, unchanged), so 34 − 5 = 29 WERE resolved to a real employee — but "resolved to an
employee" is not "seeded with a recurring flag": of those 29, only 16 actually have a component
clearing the 70%-same-value bar (44 rows). The other 13 resolved employees are correctly seeded with
nothing, not because they are unresolved but because nothing they are paid recurs by this rule. Comment
corrected to state this explicitly rather than silently fixing "29" to "16" with no explanation (which
would have left the "34" figure looking equally suspect to the next reader).

**V100** (`payroll_component_tax_treatment_backfill.sql`): reasoned from "the 5 employees V98 could
not resolve" as if the residual defaulting to `EXTRA_CUMULATIVE_ACTUAL` were small. Counted directly
against the migration's own Step 1/Step 2: 34 employees × 16 classification-eligible components = 544
pairs considered; V98's real per-employee evidence promotes exactly 44 of them (Step 2) to
`REGULAR_REPROJECT`; the remaining **544 − 44 = 500 pairs (≈92%)** rely on a company-wide rule rather
than per-employee-per-component evidence — 34 `DIRECTOR_REMUNERATION` (owner-resolved), 68
`BONUS_PAY`/`OTHER_ONE_OFF_PAY` (spec-resolved), and the remaining **398** genuinely default to
`EXTRA_CUMULATIVE_ACTUAL` for lack of any recurrence evidence at all. Comment corrected to state the
true 500-of-544 proportion and the 398 breakdown, so a reader sizing "how much of this backfill is a
real per-employee fact versus a company-wide guess" gets the true answer. Comments only; no DDL
changed in either file.

## Finding 5 (P3) — a handoff known-risk that was factually wrong

Task 5's known risk 5 claimed SSO inclusion for `MEAL_ALLOWANCE`/`PER_DIEM_TAXABLE` (added V97, after
V96's one-time SSO backfill already ran) has "the identical no-row-for-pre-existing-employees gap."
Verified against `V97__payroll_meal_and_per_diem_components.sql` lines 88-96 directly: the SAME
migration that introduces the two components also backfills SSO inclusion for them, for every employee
who already has ANY SSO-inclusion row — which, since V96 runs first in the same migration set, is
every pre-existing employee. The gap was closed in the very migration that opened it. **Deleted**, not
reworded, per instruction — a correction note explaining why it was wrong (not just silently removed)
is left in its place so a future reader does not wonder where it went or re-add it.

## Finding 6 (P3) — two small ones

1. `V97__payroll_meal_and_per_diem_components.sql`'s `chk_payroll_line_per_diem_basis`/
   `chk_payroll_line_per_diem_basis_present` CHECKs were added without the `DROP CONSTRAINT IF EXISTS`
   guard the sibling non-negative-CHECK block in the same file already uses — non-idempotent on a
   manual replay. Guarded to match. DDL-shape only (guard added, constraint definitions unchanged).
2. `PayrollComponent.java`'s class javadoc now states explicitly that adding a new enum value is not
   just an enum edit: `requireEveryNonZeroComponentClassified` 409s the entire run for any employee
   paid a non-zero amount of an unbackfilled component, so a real deployment blocker exists until a
   `V100`-shaped backfill migration (plus `hr.payroll_pay_component` row) exists for it too.

## D1 (P1, coordinator-flagged) — `customerReturnDeduction` stopped round-tripping, a REGRESSION vs origin/main

**Defect**: on `origin/main` the legacy engine passed `customerReturnDeduction` through verbatim, so a
reload always showed what HR typed. `PayrollCalculator.java`'s classified engine gave the column a
SECOND job — zeroed in the "not yet earned" path (the amount is instead netted pre-tax out of
`commissionPay`) so it would not ALSO apply as a post-tax deduction. Only one persisted column tried to
carry both "what HR entered" and "what was actually deducted post-tax," and only the second survived a
reload. In the unearned path (the ONLY reachable state before this branch — `customerReturnAlreadyEarned`
had no frontend input until task 5's P1), the entered amount silently vanished from the form on reload,
the checkbox that gates on it being `> 0` disappeared with it, and a reprocess of the same month
silently stopped re-netting the commission — net pay would change with no HR action.

**Fix**: new column `hr.payroll_line.customer_return_requested`
(`V102__payroll_customer_return_requested_amount.sql`, non-negative CHECK, one-time backfill from
`customer_return_deduction` for already-processed lines) always echoes the raw entered amount,
regardless of `customerReturnAlreadyEarned`. `customer_return_deduction`'s existing post-tax-bookkeeping
meaning is UNCHANGED (0 in the unearned path, full amount in the already-earned path) — the
already-earned path's arithmetic and the payslip are untouched. `PayrollLineDto` gained the field
appended last (new 59-field canonical record; a new 58-arg legacy constructor preserves every prior
positional call site, defaulting the new field to whatever `customerReturnDeduction` was given — the
best available reconstruction for a caller that predates the distinction). `PayrollService#calculateLine`
now passes the raw request-derived local variable (not the calculator's post-tax output) for the new
field. `PayrollRepository` SELECT/INSERT/`mapLine` updated. Frontend `PayrollPage.jsx`'s
`adjustmentFromLine` now hydrates from `line.customerReturnRequested`, not `line.customerReturnDeduction`.

**Tests** (new file, `PayrollCustomerReturnRoundTripIntegrationTest.java`, real-DB, real
`PayrollService`, 3 tests): (a) entered amount round-trips after a reload even though it was netted
pre-tax, and the unearned path nets pre-tax exactly once (`commissionPay` = 10,000 − 3,000 = 7,000,
`customerReturnDeduction` stays 0 — never double-deducted); (b) reprocessing the same month with the
second run's input hydrated from what the FIRST run persisted (exactly what the frontend now does on a
reload) produces identical net pay both times — the actual silent-change failure mode; (c) the
already-earned (post-tax clawback) control case is unchanged: `commissionPay` is NOT netted,
`customerReturnDeduction` carries the full amount, and `customerReturnRequested` agrees with it.
Frontend: `PayrollPage.test.jsx`, 1 new test — a PROCESSED period with `customerReturnRequested: 3000`
/ `customerReturnDeduction: 0` hydrates the input to `'3000'` and keeps the "already earned" checkbox
mounted (its render gate is `> 0` on this same field).

**Mutation-check**: reverted the `PayrollService#calculateLine` trailing constructor argument from the
raw local variable back to `calculation.customerReturnDeduction()` (the pre-fix behaviour — the new
column would just mirror the post-tax figure again). Ran `th.co.glr.hr.payroll` (221 tests): **exactly
2 failures**, both in `PayrollCustomerReturnRoundTripIntegrationTest` — tests (a) and (b) above (the
round-trip assertion and the reprocess-idempotency assertion); test (c), the already-earned control
case, correctly stayed green (requested and applied always agree in that path, so this specific
mutation cannot be detected by it). Nothing else in the package moved. Reverted to an empty diff.

## D2 (P1, coordinator-flagged) — a partial matrix save stranded the rest of an employee's classification into the next tax year

**Defect** (`PayrollRepository#findComponentTaxTreatmentsByEmployee`): resolved ONE effective tax year
per EMPLOYEE (`MAX(tax_year) <= :taxYear GROUP BY employee_id`, task 4's own "January cliff" fix). The
new `TaxTreatmentMatrixSection` screen (task 5) saves only the edited cell (`PayrollPage.jsx`'s
`save()` sends `changes`, a diff, never the full resolved matrix) — so the instant ONE component
gained a row in a new tax year, that employee's single effective year flipped forward for EVERY
component, and the `JOIN ... ON t.tax_year = ey.effective_tax_year` excluded every OTHER component's
still-valid prior-year row. Those components read back as "not yet classified" in the very API response
the edit's own save renders, and `PayrollCalculator#calculateClassified` 409s the whole next-year run
for any of them carrying a non-zero amount — a single dropdown edit stranding every other component HR
never touched. Task 5's layer-3 default does NOT cover this: the employee ends up with SOME rows, not
zero, so that safety net's `stored != null` short-circuit returns the (now incomplete) stored map as-is.

**Fix — chosen approach and why**: resolved per **(employee, component)** independently, with
`SELECT DISTINCT ON (t.employee_id, t.component) ... ORDER BY t.employee_id, t.component, t.tax_year
DESC` (`WHERE t.tax_year <= :taxYear`), replacing the per-employee CTE entirely. Considered and
rejected the two alternatives the coordinator offered: (a) materialise the full effective set at save
time (write every OTHER component's currently-resolved value alongside the edit) — rejected because it
only fixes the ONE writer that exists today and would need to be re-implemented correctly by every
future writer (a bulk import, a different screen); (b) have the frontend submit the whole matrix every
save — rejected for the same reason, plus it turns every single-cell edit into an N-row write. The
read-side per-(employee, component) fix makes ANY partial write pattern safe by construction, present
or future, and is a direct generalisation of task 4's own per-employee fix (itself already a narrower
case of the same underlying bug) and of `findCarryForwardSuggestions`'s existing per-employee+component
LATERAL joins (Fix 5, task 3) — the same resolution shape already established elsewhere in this file
for the identical reason.

**Tests** (`PayrollClassificationReviewIntegrationTest.java`, 2 new tests): (1)
`aPartialSaveIntoANewTaxYearDoesNotStrandOtherComponentsAlreadyRolledForward` — seeds 3 components at
2026, saves ONE at 2027, asserts the other two still resolve their 2026 values at the repository layer;
(2) `payrollRunsForTheFollowingTaxYearAfterOnlyOneComponentWasSavedIntoIt` — the same scenario end to
end through the real `PayrollService`, asserting a 2027 run with a non-zero amount on the UNTOUCHED
component does not 409. `resolvesTheEffectiveTaxYearPerEmployeeNotForTheWholeTable` (task 4's original
per-employee test) stays green unmodified — it never exercises a sibling-component stranding, only the
whole-employee cliff, so it is not redundant with the new tests.

**Mutation-check**: reverted the `DISTINCT ON` query back to the per-employee CTE. Ran
`th.co.glr.hr.payroll` (221 tests): **exactly 2 failures**, both new D2 tests above (the per-component
stranding case). `resolvesTheEffectiveTaxYearPerEmployeeNotForTheWholeTable` (task 4's whole-employee
cliff test) correctly stayed green — its scenario never puts a sibling component at risk. Nothing else
in the package moved. Reverted to an empty diff.

## Minor — `@Valid` inert on the `PUT /api/payroll/component-tax-treatments` list body

**Defect** (`PayrollController.java`, `putComponentTaxTreatments`): `@Valid` on a bare `@RequestBody
List<ComponentTaxTreatmentUpsertRequest>` does not cascade to elements under Spring's
`SpringValidatorAdapter` — only onto fields of an enclosing object. `ComponentTaxTreatmentUpsertRequest`'s
own `@NotNull employeeId`/`component` never actually fired. The frontend always populates both, so this
is not a reachability blocker, but a malformed client would reach the repository (or an unclear
`DataIntegrityViolationException`) instead of a clean 400.

**Fix — deliberately NOT a wrapper record**: `PayrollClassificationReachabilityIntegrationTest
.hrHasSomeHttpWayToClassifyAComponentBeforeTheEngineDemandsIt` (kept verbatim, per instruction)
reflects on `PayrollController`'s methods for a parameter whose generic type name literally contains
`ComponentTaxTreatmentUpsertRequest` — a wrapper type's name would not contain that string, so wrapping
the list would have broken that test. Validated explicitly instead, in
`PayrollService#upsertComponentTaxTreatments`, before the repository is ever reached: any item with a
null `employeeId` or `component` throws `ApiException(BAD_REQUEST)` naming which item (1-based index)
is malformed, and the WHOLE batch is rejected — not silently applying the valid items and skipping the
bad one.

**Test** (`PayrollClassificationReviewIntegrationTest
.upsertRejectsAMalformedItemWithANullEmployeeIdBeforeWritingAnything`): a null `employeeId` alone → 400;
a null `component` alone → 400; a batch with one valid item and one malformed item → 400 AND zero rows
written for the valid item either (wrong-way-round — proves validation happens before any write, not
per-item during the write). Not mutation-checked separately: the test would fail identically if the
new validation block were removed entirely (the malformed item would instead reach the repository and
either NPE inside `.name()` or throw a raw `DataIntegrityViolationException`, not the asserted
`ApiException`/`BAD_REQUEST`), which is sufficient signal for a straightforward guard-clause addition.

## Do NOT fix — Finding 3, recorded as instructed

`V100`'s safe default (`EXTRA_CUMULATIVE_ACTUAL` for ~398 of 544 employee×component pairs with no
carry-forward evidence — see Finding 4 above for the exact count) is a considered choice, not a defect,
but it carries a real risk that was not previously recorded:

**Trigger**: an employee has a genuinely FIXED monthly allowance (paid the same amount every month,
indistinguishable in shape from a recurring component) that V98's carry-forward evidence did not catch
(e.g. a new employee with fewer than 4 sampled months, or an existing employee whose allowance started
after the accountant's `2026.xlsx` sample window) — so it defaults to `EXTRA_CUMULATIVE_ACTUAL` instead
of `REGULAR_REPROJECT`.

**Consequence**: `EXTRA_CUMULATIVE_ACTUAL` taxes the true cumulative amount paid to date every period,
which gets the ANNUAL total right but back-loads the withholding curve within the year — each period's
withholding is smaller than a `REGULAR_REPROJECT` classification would produce early in the year and
larger later, with a December catch-up. On a modest December salary this catch-up could drive net pay
to zero or close to it — the same shape of incident as the June 2026 negative-net defect this programme
already fixed (handoff section 6), though the ROOT CAUSE here is different (a default choice reacting
correctly to genuine uncertainty, not a bug). Separately, a fixed monthly allowance is technically ป.96
ข้อ 1(4) income (`REGULAR_REPROJECT`'s own bucket), so classifying it as ข้อ 1(6) instead is a
technical mischaracterisation even where the money comes out approximately right.

**Mitigated, not fixed**: HR now has a real screen (`TaxTreatmentMatrixSection`, task 5) to reclassify
any component once its true recurrence becomes apparent, one cell at a time, with D2's fix above
ensuring that reclassification can no longer strand sibling components into the next tax year.
Changing V100's default itself is the owner's call, not an engineering one — not touched, per this
branch's explicit instruction.

## Rebase (2026-07-30)

Fetched `origin/main` (`2aba97f0`, merge of PR #346, plus `1b6db578`/PR #342 already the branch's own
base) and rebased cleanly — **zero conflicts**, 6 commits replayed
(`5f1a90fa`→`4c3dac28`, new SHAs after replay). Confirmed via `git merge-base HEAD origin/main` == `git
rev-parse origin/main` (`2aba97f0`) after the rebase.

**Handoff filename collision found and resolved**: `origin/main` already carries
`docs/agent-handoffs/118_fix-sales-quotation-sticky-cta-dead-click.md` (a different, already-merged
branch that happened to land on the same number). Not a literal git conflict — the filenames differ in
their suffix — but a real numbering collision (`118` now names two unrelated branches in the repo's
history). Renamed this branch's own handoff, via `git mv`, from
`118_feat-payroll-classification-and-hr-declarations.md` to
`119_feat-payroll-classification-and-hr-declarations.md` (the next free number — `119` was unused;
`117` is ALSO already double-used on `origin/main` by two unrelated pre-existing branches, a
pre-existing gap not introduced or fixed by this session). All references to this file's own name
within its text are self-referential prose, not links, so nothing else needed updating.

## Files changed (task 6)

| File | Change |
|---|---|
| `V102__payroll_customer_return_requested_amount.sql` | new — D1 (`customer_return_requested` column, non-negative CHECK, one-time backfill) |
| `PayrollService.java` | Finding 1 (`hasBeenOnboardedForPayroll` polarity), D1 (`calculateLine` trailing arg), Minor (`upsertComponentTaxTreatments` validation) |
| `PayrollRepository.java` | D2 (`findComponentTaxTreatmentsByEmployee` per-component `DISTINCT ON`), D1 (SELECT/INSERT/`mapLine` for the new column) |
| `PayrollLineDto.java` | D1 (`customerReturnRequested` field appended; new 58-arg legacy constructor) |
| `PayrollComponent.java` | Finding 6 (javadoc on adding a new component) |
| `V97__payroll_meal_and_per_diem_components.sql` | Finding 6 (`DROP CONSTRAINT IF EXISTS` guards added; edited in place, still unapplied to any real DB) |
| `V98__payroll_component_carry_forward.sql` | Finding 4 (comment corrected: 16/44, reconciled against 34/29/5; edited in place) |
| `V100__payroll_component_tax_treatment_backfill.sql` | Finding 4 (comment corrected: 500-of-544 proportion, 398 breakdown; edited in place) |
| `docs/agent-handoffs/119_...md` (this file) | renamed from `118_...md` (rebase collision); Finding 5 (risk 5 deleted, correction note added); this section |
| `frontend/src/features/payroll/PayrollPage.jsx` | D1 (`adjustmentFromLine` hydrates `customerReturnRequested`) |
| `PayrollClassificationAndSsoInclusionIntegrationTest.java` | Finding 2 (dedicated ฿15,000 fixtures, new helpers, corrected expected values) |
| `PayrollClassificationReviewIntegrationTest.java` | D2 (2 new tests), Minor (1 new test), test-only wiring (`PayrollService`, `hr()`) added |
| `PayrollCustomerReturnRoundTripIntegrationTest.java` | new — D1 (3 tests) |
| `PayrollRawSqlEmployeeClassificationReviewTest.java` | fourth reviewer's file — kept verbatim, not modified (still untracked, per instruction) |
| `frontend/src/features/payroll/PayrollPage.test.jsx` | D1 (1 new test) |

## Commands run

```
git fetch origin main && git rebase origin/main
git mv docs/agent-handoffs/118_...md docs/agent-handoffs/119_...md
cd backend && ./mvnw -q -B -o test-compile                                          # throughout
cd backend && ./mvnw -q -B -o -Dtest.fork.count=1 -Dtest=<class[,class...]> test     # targeted, throughout, incl. every mutation-check
cd backend && ./mvnw -B -o -Dtest.fork.count=1 clean verify                         # full suite, final
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
```

## Tests / build results

**Backend: `./mvnw -B -o -Dtest.fork.count=1 clean verify` — BUILD SUCCESS, 1380 tests, 0 failures, 0
errors, 0 skipped**, confirmed by the completed full run's own summary line (not inferred from partial
output). Integration tests **RAN** on Testcontainers (every run's log confirms Flyway migrating through
v102 — `Successfully applied 99 migrations to schema "hr", now at version v102` on the golden template,
and to v102 again on every forked clone), not `TEST_DB_URL`. Baseline at task start: 1373 tests + the
fourth reviewer's 1 red = 1374. This task added: `PayrollCustomerReturnRoundTripIntegrationTest` (3,
new file) + `PayrollClassificationReviewIntegrationTest` (+3: 2 D2 + 1 Minor) + the fourth reviewer's
own test going from red to green (+0 net, already counted in baseline) = 1374 + 6 = 1380, exactly
matching the full run's total. `PayrollRawSqlEmployeeClassificationReviewTest` (the fourth reviewer's
test) is confirmed **GREEN** in this same full run (1/1), kept verbatim throughout.

**Frontend: `npm run lint` — 0 errors, 1 pre-existing warning** (`PayrollPage.jsx:480`, missing
`useEffect` dependency `load`, unchanged location/cause, matches every prior task's baseline exactly).
**`npm test -- --run` — 783/783 tests, 72/72 files pass** (this task added 1 new test in
`PayrollPage.test.jsx`; the rest of the increase over task 5's 779/72 baseline comes from the 2
rebased `origin/main` commits, sales-frontend-only, no payroll file). **`npm run build` — succeeds**
(`vite build`, completed in 209ms).

Every finding/defect above shipped its own real-DB (backend) or component-level (frontend) test, and
Finding 1, Finding 2, D1, and D2 were each mutation-checked (defect reintroduced, confirmed exactly the
intended test(s) — and only those — went red, reverted to an empty diff; verified clean afterward with
`git diff` / `grep MUTATION` finding nothing in any touched file). The Minor fix's guard-clause
addition was judged simple enough not to need a separate mutation run (see its own section above for
the reasoning).

## Authz evidence

**No authorization change.** Every fix in this task is a business-logic/data-completeness correction
(a safety-net's polarity, a partial-save read-resolution bug, a round-trip persistence bug, three
documentation corrections) or an input-validation guard (the Minor fix — required-field presence, not
a role/scope/permission check). No role gate, scope filter, or permission check was added, removed, or
altered. `PayrollService`'s existing `PAYROLL_VIEW_ROLES`/`PAYROLL_EDIT_ROLES`, and the
`component-tax-treatments` endpoint's double gate (task 5), are untouched.

## Known risks — carried into the next task

1. **Finding 3** (recorded above in full, with its exact trigger and consequence) — V100's safe
   `EXTRA_CUMULATIVE_ACTUAL` default for ~398 of 544 employee×component pairs is correct FOR GENUINE
   UNCERTAINTY, but a genuinely-fixed monthly allowance that evidence did not catch would be
   back-loaded within the year (December catch-up, possible near-zero net pay on a modest December
   salary) and is technically ป.96 ข้อ 1(4) income misclassified as ข้อ 1(6). Mitigated by the
   reclassification screen (task 5) + D2's fix (partial saves no longer strand siblings). Changing the
   default is the owner's call, not fixed here.
2. **The reachability-audit pattern itself** (recorded above, "Correction to the record") — three
   times now on this branch, an acceptance test went green because its OWN fixture manufactured a
   state no production path reaches, masking a real gap in the state that DOES occur. Worth a
   standing instruction for future reviews on this branch: when a fixture calls an internal seeding
   method directly (not through `EmployeeService#create`, a migration, or the real HTTP surface), ask
   explicitly whether that call sequence is reachable by any real deployment path before trusting the
   test's green result as evidence.
3. Every known risk from tasks 1-5, still open and unchanged by this task: `parent_care_count` vs the
   legacy `parent_care_allowance` baht field (two writable sources of truth); the three
   verification-state writers ignore update row counts; §10's structured dependant records are still
   unbuilt; the per-head allowance residual in `headCountFor` (pinned, not fixed, per instruction);
   `calculate()`/`PayrollCalculatorTest` are genuinely dead production code, kept per instruction; F9
   (unearned-customer-return-truncation `.max(ZERO)`, `PayrollService.java` ~line 392) needs an owner
   business decision; `taxableAnnualIncome`/`annualTax` understate the true annual liability in any
   month after a settled `EXTRA_KNOWN_FREQUENCY` payment, by design (task 4's F1); P3-6
   (`fullAnnualProjection` vs the YTD-inclusive stage-3 base, triggers only once a genuinely
   percentage-capped allowance meets a settled known-limb payment); ป.96 ข้อ 2.10 (leaver final-period
   true-up) unimplemented; `mockApi.js` has no payroll preview/process implementation at all, so
   `VITE_USE_MOCKS=true` verification is not possible for anything in this branch — every claim in
   this file is Testcontainers/real-Postgres evidence, never mock-driven.
4. **V95-V101 are still uncommitted on this branch** (every prior task's own note, still true) —
   nothing in this task has touched any real database; every claim above is verified against
   Testcontainers/real Postgres only, per this session's explicit instructions and CLAUDE.md. This
   task's changes are also uncommitted (working tree only) per this session's explicit "do not commit"
   instruction.

## The exact next prompt for the next agent

> Read this file in full — in particular the AUTHORITATIVE NUMBERING table (task 4) and this task 6
> section's known risks — before touching anything payroll-related. This branch has now survived FOUR
> Opus reviews (task 3, the rejection in task 4, the rejection in task 5, and this task's
> approve-with-fixes); treat that history as a signal to verify claims against the actual code rather
> than trusting a prior task's "GREEN, confirmed" at face value — see "Correction to the record" above
> for why. Suggested order: (1) get explicit sign-off to commit and push this branch (V95-V101, six
> tasks of fixes, all currently sitting uncommitted in this worktree only); (2) build the structured
> child/parent/disabled-dependant records §10 still calls out as unbuilt; (3) decide F9
> (`PayrollService.java` ~line 392, unearned-customer-return truncation) and Finding 3 (V100's default,
> known risk 1 above) with the owner — both are business decisions, not engineering guesses; (4) rebase
> onto whatever `origin/main` has moved to by then before merging, per the standing rule.
