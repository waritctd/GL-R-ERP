# Frontend reconciliation audit

Snapshot taken 2026-08-14 at `9c15039b` (after PRs #703–#726), read against `origin/main`.

This document answers one question: **what is now untrue in the frontend, and what does a user see
because of it?** It is not a design review and makes no recommendation about component structure or
design language.

It is organised around two directions:

- **[Direction A](#direction-a--what-the-frontend-needs-that-the-backend-does-not-serve)** — capabilities the UI needs that the backend does not serve, so the UI
  computes, guesses or hardcodes them.
- **[Direction B](#direction-b--what-the-backend-carries-that-the-frontend-no-longer-needs)** — server surface the frontend no longer reaches: the 21 known endpoints classified
  three ways, then the larger set both guards miss.
- **[Stale mirrors](#stale-mirrors--a-served-answer-the-frontend-copied-ignored-or-let-drift)** — the frontend holds a copy of an answer the backend already gives, and the
  copy has drifted. These are neither direction, so they have their own section.

## Baseline

Run once in a clean worktree at `9c15039b`, before anything here was written:

| Command | Result |
|---|---|
| `npm run lint` | pass (exit 0, no output) |
| `npm test` | **132 files / 1562 tests, all passing** |
| `npm run build` | pass, 379 modules, `vite build` in 388 ms |

That green suite is the point. Every item below is invisible to it.

## The easy question is already answered — do not re-derive it

Two independent guards, written a day apart from different sources, agree:

| | `serverContract.test.js` (#722, parses controllers) | `reconcile.test.js` (#726, reads springdoc `docs/api/api-surface.json`) |
|---|---|---|
| Client calls with **no server endpoint** | **0** | **0** |
| Path or verb **mismatches** | **0** | — |
| Orphaned `routes.js` entries | **0** | — |
| Server endpoints with **no `hrApi` caller** | 21 | 21 |

**Every path and verb `hrApi.js` issues is served by a controller.** Nobody needs to look for 404s
again. The interesting failures are all elsewhere: severed-but-routed endpoints (which match on path
and verb and still always fail), fields a DTO does not carry, and decisions the frontend re-derives.

## Counts

| Severity | Count |
|---|---|
| **Broken now** — a user hits this today | 7 |
| **Invisible-but-wrong** — wrong, but nothing on screen says so | 11 |
| **Untidy** — dead or latent, with no user-visible symptom | 11 |

Plus, in Direction B: **21** server endpoints with no `hrApi` caller (of which **2 are safe to
delete**), and a further **36** that both guards count as reached while no user can get to them.

**Scope note.** Items are keyed to the #703–#726 window unless marked **[pre-existing]**. Two
findings — A7 and B6.1 — predate this session entirely; they surfaced because Direction A/B is a
capability sweep rather than a diff review. They are flagged so nobody mistakes them for a regression
introduced this week.

## Lead with what a user hits today

| # | Symptom | Where |
|---|---|---|
| **S1** | Import's one hand-off button always shows an error — *after* the hand-off already succeeded | [Stale mirrors](#s1-import-cannot-hand-a-price-to-the-ceo-the-one-button-that-does-it-always-errors--after-the-work-has-already-succeeded) |
| **A1** | A from-stock deal displays four import milestones, in green, that never happened | [Direction A](#a1-a-from-stock-deal-shows-four-import-milestones-it-never-performed) |
| **S2** | The cancel button is absent in the three states #718 added it for | [Stale mirrors](#s2-the-cancel-button-is-missing-from-the-three-states-718-added-it-for) |
| **S3** | The stock-declaration button is hidden from the deal owner #706 built it for | [Stale mirrors](#s3-the-stock-declaration-button-is-hidden-from-the-deal-owner-706-built-it-for) |
| **A2** | Discounts vanish on a revision, and typing them back 409s | [Direction A](#a2-the-quotation-revision-discount-input-is-live-and-saving-it-409s) |
| **A3** | "สร้างรอบแก้ไข" is offered after the customer accepted, and 409s | [Direction A](#a3-a-customer-change-revision-is-offered-after-the-customer-accepted-and-refused-on-click) |
| **B6.1** | An overseas per-diem claim cannot be filed at all — the form always says DOMESTIC | [Direction B](#b61-the-overseas-per-diem-branch-cannot-be-reached-from-the-portal-pre-existing) |

## The pattern

The recurring defect this session was **the frontend deciding something the backend already decides**,
with nothing failing when the two disagree. #712 (commission tier math) and #713 (deal-stage rules)
removed the two largest instances by *adding the server answer and deleting the frontend copy*. That
is the fix shape to prefer throughout, and it is what most items below reduce to.

#713 also established the only durable defence: where a mirror genuinely must exist, a test **parses
the Java source and asserts against it**. Exactly three frontend tests do this —
`features/tickets/stageCatalog.test.js`, `features/tickets/dealTrackingMeta.test.js` and
`api/serverContract.test.js`. Everything else that mirrors a backend rule has no tripwire.
`api/contract.test.js` compares method names and parameter *counts* only — never values, never logic.

---

# Direction A — what the frontend needs that the backend does not serve

Not "a call 404s" (that set is empty). These are places where the UI must express something the
server has no field, endpoint or decision for, so it computes, guesses or hardcodes it.

## A1. A from-stock deal shows four import milestones it never performed

**What.** `frontend/src/features/tickets/DealFulfilmentPanel.jsx:43-64`, `SubstepChips`:

```js
const currentIdx = PROCUREMENT_SUBSTEPS.findIndex((s) => s.code === currentCode);
…
const done = currentIdx >= 0 && i < currentIdx;
```

rendered unconditionally at `:246` as `<SubstepChips currentCode={fs} />`, where `fs` is the deal's
`fulfillmentStatus`. `PROCUREMENT_SUBSTEPS` (`features/tickets/stageMeta.js:79-87`) is a flat
seven-element list:

```
0 IR_ISSUED  1 IR_SENT  2 SHIPPING  3 GOODS_RECEIVED  4 FROM_STOCK  5 PARTIALLY_DELIVERED  6 FULLY_DELIVERED
```

**Why it is wrong.** `FulfilmentStatus` is not one ordered chain. It is an import **SEQUENCE**
(`IMPORT_SEQUENCE = [IR_ISSUED, IR_SENT, SHIPPING, GOODS_RECEIVED]`,
`backend/.../ticket/FulfilmentStatus.java:33`) plus separate states — `FROM_STOCK` is a **branch that
skips the sequence entirely**, not a fifth step after it. `stageMeta.js:67-70` says this in its own
words: *"FulfilmentStatus.java publishes an import SEQUENCE … and a delivery-complete SET, not one
ordered whole, so there is no single canonical list for the backend to serve."* The frontend
flattens it into one list anyway and then treats position as progress.

**User-visible symptom.** A deal whose items were declared fully from stock sits at `FROM_STOCK`, so
`currentIdx = 4` and indices 0–3 all render in the green "done" style:

> ✅ ออกใบขอซื้อ (IR) แล้ว  ✅ สั่งซื้อไปยังผู้ผลิตแล้ว  ✅ สินค้าอยู่ระหว่างเดินทาง  ✅ สินค้าถึงโกดังแล้ว

None of that happened. The deal never issued an import request — `issueImportRequest`'s own backend
guard requires `fulfillmentStatus == null`, so a `FROM_STOCK` deal is *forbidden* from ever doing so.
It gets worse downstream: at `FULLY_DELIVERED` (`currentIdx = 6`) every earlier chip is green, so the
strip simultaneously claims the full import journey **and** "สินค้าจากสต็อก" on the same deal.

This is exactly the defect PR #715 fixed for `PICKED_UP` / `CUSTOMS_CLEARANCE` — its own comment
describes it: *"SubstepChips marks every entry before the current one as done, so a deal on SHIPPING
rendered 'รับจากผู้ผลิตแล้ว' as a completed step."* #715 fixed it by deleting two codes nothing ever
writes. `FROM_STOCK` **is** written — by `reserveStock` on full coverage — so this instance is live,
and PR #706 (which widened who may declare stock) makes it more common.

**Severity.** Broken now. A false statement of fact on screen, on the fulfilment panel.

**Fix shape.** Do not render one linear strip over two different journeys — branch on `FROM_STOCK`
the way `DealMoneyTimeline.jsx:186` already branches on deposit policy (see A6). The durable fix is
Direction-A-shaped: have the backend serve the ordered path this deal is actually on, the way
`PaymentTrack.path(policy)` already computes one server-side.

## A2. The quotation-revision discount input is live, and saving it 409s

**What.** `frontend/src/features/pricingRequests/PricingRequestDetailPage.jsx:1279-1291` renders a
per-line **ส่วนลด/หน่วย** input, gated at `:1251`:

```js
const editable = isCustomerQuotationEditable(quotation) && canManageCustomerQuotation(user, summary);
```

`isCustomerQuotationEditable` (`pricingRequestMeta.js:174-176`) is only
`docStatus ∈ {DRAFT, READY_TO_ISSUE}`. **Nothing consults `parentQuotationId`** — in fact no frontend
component reads that field at all; it appears only in `data/demoSales.js` fixtures.

**Why it is wrong now.** PR #703 refused exactly this. `CustomerQuotationService.update` (`:235-240`):

```java
if (quotation.parentQuotationId() != null && discount.compareTo(BigDecimal.ZERO) != 0) {
    throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาฉบับแก้ไขให้ส่วนลดไม่ได้ — …");
}
```

checked on the **resolved** discount, so omitting the field cannot slip a stored value past it. The
same PR stopped `createRevision` carrying prior discounts forward (`:379+`, every line rebuilt at
`buildItem(item, BigDecimal.ZERO)`); the two halves only close the loophole together.

**The missing capability.** The UI needs to know *whether this quotation's discount is writable*.
No DTO field says so. It infers writability from `docStatus`, and a revision is a `DRAFT`, so the
inference is wrong.

**User-visible symptom.** A rep opens a revision to fix a typo or a validity date. **Every discount
resets to zero** — the reissue is *higher* than what the customer was last shown (a stated
consequence of the owner ruling, not a bug). The rep sees the zeros, retypes the discount into the
input the page is still offering, saves, and gets a raw 409 toast. Nothing on the page warns first.

Scope: an *untouched* save sends `0` and succeeds. The 409 needs the user to actually type a
discount — which is the natural reaction to seeing them all zeroed.

**Severity.** Broken now.

**Fix shape.** Have the quotation DTO carry the writability (a `discountEditable` / decision field,
the way `stageDecisions` carries `allowed`/`requiresReason` for stages), and render the field
read-only with the ruling's own pointer when it is false.

## A3. A customer-change revision is offered after the customer accepted, and refused on click

**What.** `frontend/src/features/pricingRequests/PricingRequestDetailPage.jsx:580-582`:

```js
const canCreateCustomerRevision = isSales(user)
  && summary?.ticketCreatedById === user?.employeeId
  && !['DRAFT', 'CANCELLED', 'SUPERSEDED'].includes(summary?.status);
```

**Why it is wrong now.** That is verbatim the hand-maintained denylist PR #703 **deleted from the
backend**, replacing it with the state machine. `PricingRequestService.createCustomerChangeRevision`
(`:441-451`) says so:

> the state machine is now the ONLY authority on which statuses a customer-change revision may start
> from, replacing the hand-maintained `{DRAFT, CANCELLED, SUPERSEDED}` denylist that used to live
> here. That denylist and `PricingRequestStatus.ALLOWED` had drifted apart in both directions…

and adds an explicit refusal for the case that changed (`:457-461`):

```java
if (PricingRequestStatus.QUOTATION_ACCEPTED.equals(parent.status())) {
    throw new ApiException(HttpStatus.CONFLICT,
        "ลูกค้ายอมรับใบเสนอราคาแล้ว จึงแก้ไขผ่าน revision ของคำขอราคาไม่ได้ …");
}
```

`QUOTATION_ACCEPTED` is terminal — `Map.entry(QUOTATION_ACCEPTED, Set.<String>of())`
(`PricingRequestStatus.java:177`), no `SUPERSEDED` edge. The frontend kept the copy the backend threw
away, and that copy does not list `QUOTATION_ACCEPTED`.

**The missing capability.** The UI needs a served answer for "may this pricing request take a
customer-change revision". There is none, so it keeps a denylist — the very artefact #703 removed.

**User-visible symptom.** On a deal the customer has already accepted, the
"รอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า" panel and its **สร้างรอบแก้ไข** button render (`:775-783`), open a
modal (`:1531-1543`), and the POST 409s. The rep is invited into a workflow that cannot complete.

The *other* revision button (`:1383-1386`) is fine — gated on `quotation.docStatus === 'REVISION_REQUESTED'`,
which leaves the request at `QUOTATION_ISSUED`, a status that does have a `SUPERSEDED` edge.

**Severity.** Broken now.

**Fix shape.** Delete the denylist; serve the verdict alongside the pricing request (same shape as
`stageDecisions`), which also fixes **S2**.

## A4. ฝ่ายบัญชี's worklist recommends "record the commission" forever, including on deals where it already was

**What.** `frontend/src/features/tickets/accountActions.js:78-88`, a `KNOWN LIMITATION` the code
states itself:

> the backend has no per-ticket "commission already recorded?" flag on `TicketSummaryDto`, and
> account has no route to `GET /api/commissions` … So a `CLOSED_PAID` deal always resolves to step 5
> here even if its commission was already recorded.

Confirmed on both halves: `TicketSummaryDto` carries no such field, and
`ROLE_PERMISSIONS.canListCommissionRecords = ['sales', 'sales_manager', 'ceo']`
(`api/routes.js:446`) mirrors `CommissionController`'s `@PreAuthorize` — **account is excluded from
the list endpoint by design**; it holds only `createFromDeal`.

**User-visible symptom.** Every `CLOSED_PAID` deal keeps showing **บันทึกใบกำกับ + ออกค่าคอม** as
account's next action on the Overview worklist, permanently, whether or not the commission exists.
The accountant cannot tell a done deal from an outstanding one without opening each. It cannot cause
a double-submit — `ticketId` uniqueness on the create form catches that — so the damage is a
worklist that over-counts its own backlog and never empties.

`CommissionPage.jsx`'s `eligibleTickets` carries the identical gap (its own comment cross-references
this one).

**Severity.** Invisible-but-wrong — the number is wrong, and nothing on screen says so.

**Fix shape.** Either a `commissionRecorded` boolean on `TicketSummaryDto`, or a narrow
account-readable existence check. Both are backend work; there is no frontend-only fix.

## A5. The manual-commission rep picker cannot list a rep who owns no deals

**What.** `frontend/src/features/commissions/CommissionPage.jsx:420-436`:

> `sales_manager`/`ceo` has no `/api/employees` access (that's hr-only, see
> `ROLE_PERMISSIONS.canViewEmployees`), so the rep picker is a best-effort convenience list derived
> from tickets `sales_manager`/`ceo` can already see … It will not include every employee (e.g. a
> manager who owns no deals).

It builds the list by iterating `api.tickets.list({})` and collecting distinct `createdById`.

**User-visible symptom.** When `sales_manager` or the CEO files a manual commission for someone who
owns no deals, that person is simply absent from the dropdown. The fallback is typing a numeric
Employee ID — which the code correctly calls the authoritative path, but the user has to know the
number.

**Severity.** Invisible-but-wrong. Nothing errors; a name is quietly missing.

**Fix shape.** A minimal name-lookup endpoint readable by `sales_manager`/`ceo`. Backend work.

## A6. `effectiveWinProbability` is computed by the backend and never sent

**What.** `backend/.../ticket/TicketSummaryDto.java:75`:

```java
public int effectiveWinProbability() {
    return WinProbabilityDefaults.effective(winProbabilityOverride, salesStage);
}
```

It is a record **method**, not a record component, so Jackson does not serialize it. Two frontend
consumers re-derive it from a mirrored table — `features/tickets/DealTrackingPanel.jsx:46` and
`features/tickets/TicketListPage.jsx:221`, the latter feeding the **win-weighted pipeline forecast**.

**Why it is on this list.** This is the last surviving instance of the exact pattern #712 and #713
removed: a number the backend already computes, recomputed across the wire from a copied table. The
copy is currently correct *and* guarded — `dealTrackingMeta.test.js` parses `WinProbabilityDefaults.java`
and `DealStage.java` and fails on any disagreement, which is why V143's `QUOTE_OWNER` insertion was
caught (#714) rather than silently showing 0%.

**User-visible symptom.** **None today.** Recorded because the guard, not the design, is what makes
it safe, and because the consequence of drift is a wrong money-shaped forecast on the deal list.

**Severity.** Invisible-but-wrong (currently benign).

**Fix shape.** Add it as a serialized field and delete both client derivations. Cheap, and it retires
the pattern completely.

## A7. The overtime retroactive window is a deployment setting, and the form hardcodes it **[pre-existing]**

**What.** `frontend/src/features/overtime/OvertimePanel.jsx:91`:

```js
export const OT_RETROACTIVE_WINDOW_DAYS = 60;
```

drives three things: the zod cutoff (`:139`), the user-facing policy text
`ย้อนหลังได้ไม่เกิน 60 วัน` (`:93`, shown at `:143`), and — critically — the date input's `min`
attribute at `:707`:

```jsx
min={addDaysIso(-OT_RETROACTIVE_WINDOW_DAYS)}
```

which physically prevents the browser's date picker from selecting an earlier day.

**Why it is wrong.** The backend does not hold this as a constant. `OvertimeService.java:750` reads it
per request from configuration:

```java
int windowDays = Math.max(0, appProperties.getOvertime().getRetroactiveWindowDays());
```

sourced from `application.yml:94` — `retroactive-window-days: ${APP_OVERTIME_RETROACTIVE_WINDOW_DAYS:60}`.
**It is an environment variable**, changeable per deployment with no code change and no migration. No
controller or DTO in `backend/src/main/java` exposes it: the frontend has no way to ask.

The frontend even has the seam and never uses it. `createOvertimeFormSchema` (`:97-99`) accepts
`retroactiveWindowDays` as an option — and its single call site (`:281`) is
`createOvertimeFormSchema({ requireEmployeeId: hasMultipleSubmitOptions })`, which never passes it.
The parameter is dead.

**User-visible symptom.** **None today** — both sides read 60. The moment ops sets
`APP_OVERTIME_RETROACTIVE_WINDOW_DAYS` to anything else, it becomes user-visible in whichever
direction is worse:

- **Raised to 90** — the date picker refuses days 61–90. The employee cannot file a claim the server
  would accept, and the form states a policy that is factually false.
- **Lowered to 30** — the form accepts days 31–60, the employee fills the whole thing in, and the
  submit is rejected server-side with a *different number* in the error message than the one the form
  just told them.

**Severity.** Invisible-but-wrong, latent. It is listed above the other latent items because the
trigger is an ops action with no code review attached.

**Fix shape.** Serve the window (it already has a natural home on any overtime meta/config read) and
pass it into the schema through the parameter that is already there.

## Checked and clean

Three suspected Direction A items that turned out **not** to be gaps. Recorded so nobody re-opens them.

- **`features/taxAllowance/taxAllowanceCaps.js`** — every ceiling comes from
  `GET /api/payroll/tax-allowances/caps`. Its header states that nothing in the file invents a
  threshold, and reading it bears that out: nulls stay null, and the only arithmetic is summing the
  employee's own declared amounts for a progress bar. **Model of the right pattern.**
- **`PAYMENT_SUBSTEPS`** (`stageMeta.js:92-98`) — a hardcoded 5-step list where
  `PaymentTrack.path(policy)` returns a *different* sequence on a deposit-bypass policy
  (`PaymentTrack.java:126-131` → `BYPASS_PATH` vs `REQUIRED_PATH`). The obvious inference — and one a
  parallel reading of this codebase reached — is that bypass deals display two deposit steps they
  will never reach, an exact twin of [A1](#a1-a-from-stock-deal-shows-four-import-milestones-it-never-performed).
  **They do not.** `DealMoneyTimeline.jsx:186` renders the chip row only when
  `summary.paymentStatus && !depositBypassesNotice(summary.depositPolicy)`, and that is its only
  render site. Correctly handled, and it is what A1 should look like. Worth noting how narrowly this
  missed being a second live defect: the list is equally wrong in the abstract, and only the guard at
  the call site saves it.
- **`GET /api/meta/deal-stages` and `stageDecisions`** — both fully consumed. See
  [seed corrections](#seed-items-that-did-not-hold-up).

One standing risk, not a defect: the literal `['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER']` mirrors
`DepositPolicy.NON_REQUIRED` (`DepositPolicy.java:12`) and is duplicated in **three** unguarded
places — `DealMoneyTimeline.jsx:59`, `DealDepositPanel.jsx`, `accountActions.js:24`. All three
currently agree with the Java.

---

# Direction B — what the backend carries that the frontend no longer needs

**Start from the 21**, do not re-derive them. Both guards list the same 21 endpoints with no `hrApi`
caller, each already carrying a written reason: `SERVER_ONLY` in
`frontend/src/api/serverContract.test.js:384-457` and `UNCALLED` in
`frontend/src/api/reconcile.test.js:31-77`. What follows is the three-way verdict, which neither file
provides.

**"No caller" is not a verdict.** Of the 21, exactly **2** are safe to delete.

## B1. Genuinely dead — safe to delete (2)

| Endpoint | Proof |
|---|---|
| `GET /api/deal-estimate-markup` | |
| `PUT /api/deal-estimate-markup` | |

The ราคาตั้ง (ประมาณการ) display multiplier (V112, PR #438), removed from the frontend **entirely** by
PR #682 after UAT — reps were reading catalog-price-times-markup as a selling price. Verified: the
only remaining references in `frontend/src` are two tests asserting it must **not** come back
(`TicketCreateModal.test.jsx:39`, `CeoSettingsPage.test.jsx:297`) plus the two allowlists. The
controller, repository, DTOs, the V112 table and two backend test classes all survive, asserting a
contract nothing consumes.

It is a *display* multiplier, so deleting it strands no data and breaks no calculation. Note
`FxRateController` cites this controller as precedent for its own open-read decision, so the deletion
should carry that forward. **Owner ruling needed, not analysis.**

## B2. Reached by something other than `hrApi.js`, or retained by explicit ruling (10)

These two sub-cases are kept apart on purpose. Collapsing them is how "no caller" becomes a wrong
deletion.

**B2a — a real non-`hrApi` client exists (2).** Deleting these breaks production.

| Endpoint | Client |
|---|---|
| `POST /api/attendance/punch` | The physical scanners, authenticating with `X-GLR-Agent-Token` rather than a session. Confirmed: `agents/attendance/showroom_agent.py` exists, and `agents/attendance/README.md:58` / `SC700_FIELD_TEST.md` configure `ATTENDANCE_API_URL` against this path. One of `SecurityConfig`'s four anonymous exceptions. |
| `POST /api/attendance/devices/{}/agent-token` | Run by hand from an HR session; the runbook is `agents/attendance/WAREHOUSE_SCANNER_SETUP.md`. The plaintext token is shown exactly once, which is *why* there is no UI. |

**B2b — no caller at all, retained by owner ruling (8).** All eight `ProcurementController` mappings
(`GET`/`POST` `/api/factory-purchase-orders…`, `GET`/`POST`
`/api/pricing-requests/{}/factory-purchase-orders`). PR #683 deleted the จัดซื้อ & นำเข้า page and
every client layer together and kept the backend deliberately; its own body **predicts this flag** —
*"a future contract-style sweep will flag them as unreachable. That is intended."* 0 factory POs have
ever existed in production.

These are genuinely unreached — the honest label is "dormant by ruling", not "reached by something
else". Do not delete without revisiting #683.

## B3. Built backend-first; the UI never landed (9)

Not dead. Unexposed. Each is a capability that exists, is tested, and no user can reach.

| Endpoint | What is stranded |
|---|---|
| `POST /api/pricing-decisions/{}/recalculate-cost` | **The replacement for a route the frontend still calls.** This is the CEO-side successor to the severed `/pricing-costings/{}/recalculate` — the other half of [S1](#s1-import-cannot-hand-a-price-to-the-ceo-the-one-button-that-does-it-always-errors--after-the-work-has-already-succeeded). |
| `PUT /api/pricing-decisions/{}/items/{}/cost-override` | Genuinely new behaviour from V141: a per-line manual cost beside the computed figure, with a mandatory reason and staleness re-stamping. No `routes.js` entry either, so even the e2e route sweep cannot see it. |
| `POST /api/pricing-formula-config/freight-rates` | Freight **row** add. |
| `DELETE /api/pricing-formula-config/freight-rates/{}` | Freight **row** delete. Verified against `CeoSettingsPage.jsx`: it maps over `config.freightRates` and writes only `amountThb` per existing row (`:273-279`), and derives the matrix dimensions *from the rows that already exist* (`buildFreightMatrixDims`, `:234-243`). So there is no add/remove control at all — V109's blank cells stay unfillable and a new origin country still needs a migration, which is the exact gap issue #436 opened. |
| `GET`/`PUT /api/payroll/deduction-consents` | HR's record of which deductions have written employee consent. Deliberately a recorded field, not an enforcement gate. |
| `GET /api/payroll/deduction-shortfalls` | **The data is accumulating.** `DeductionObligationService#recordGarnishmentShortfalls` writes this table on every payroll run; only the read surface has no client, so HR cannot see what the system is recording about garnishment shortfalls. |
| `POST /api/leave/policy-document` | The upload half of the §5 announcement PDF. V133 says rows reach the table only through this endpoint, so the table is necessarily **empty in every environment**. |
| `POST /api/employees/{}/reset-password` | **Operationally significant.** `README.md:86` designates the HR reset-password flow as *the* path for a new employee's first password (employee-code-derived passwords were removed for security in PR #150). There is no button and no documented curl recipe, so onboarding needs a hand-crafted POST today. |

## B4. The "21" is an undercount — and the missing class is invisible to both guards

**Mechanism, verified.** Neither guard traces a call from a component. `reconcile.test.js` uses
`frontendCallSites()` in `frontend/src/api/apiSurface.js:97-98`, whose first line is
`const source = read('./hrApi.js')` — it parses **hrApi.js's source and nothing else**.
`serverContract.test.js` mocks `./client.js` and **calls every `hrApi` method itself**.

So an endpoint that `hrApi.js` declares a method for, but which **no component, hook or page ever
calls**, counts as "reached" by both guards while being exactly as unreachable to a user as the 21.

**Confirmed instance: `tickets.setEntryChannel`.** Declared at `frontend/src/api/hrApi.js:257`
(`POST /api/tickets/{id}/entry-channel`). Grepping `frontend/src` for a caller returns only the mock's
own action advertisement (`mockApi.js:4210`) and an unrelated React state setter of the same name in
`TicketCreateModal.jsx:481,1208`. **No component calls it.**

The backend advertises it: `TicketService.addPolicyActions` (`:2342-2346`) adds `SET_ENTRY_CHANNEL`
to `availableActions` for **any deal owner**. V144 (#709) made `UNSPECIFIED` the stored default, and
#711 made the create modal demand a choice — but only client-side (`TicketService.java:189-193`
accepts a blank and substitutes `UNSPECIFIED`; the create path never refuses). So a deal created
before #711, or by any other client, sits at `UNSPECIFIED` forever.

**User-visible symptom.** The deal reads **ยังไม่ระบุช่องทาง** and there is no control anywhere to
correct it. The `changingExistingNonDefault` note rule (`TicketService.java:1934-1939`) is unreachable
from this frontend.

**Severity.** Invisible-but-wrong.

**Someone already noticed, and wrote a comment instead of a guard.**
`frontend/src/features/leave/LeavePolicyBar.jsx:24-28` states the whole class in passing:

> `api.leave.policyDocumentAvailable` / `downloadPolicyDocument` are now called by NOTHING. They stay
> in hrApi.js and mockApi.js because contract.test.js pins that surface against the real
> LeaveController, which still exposes the endpoint — **an unused client method is not a dead
> endpoint.**

That is exactly right, and exactly why no guard catches it.

## B5. The size of the blind spot: the undercount is larger than the reported number

`hrApi.js` declares **261 methods** across 30 namespaces. **39 have no caller anywhere in
`frontend/src`** outside the API layer itself (excluding `api/**`, `data/**` and all test files).

Netting out methods that share an endpoint with a live caller — `tickets.createDocDraft` and
`tickets.listDocs` hit the identical routes as the wired `depositNotices.createDraft` /
`listByTicket`, and the two `leave.policyDocument*` methods hit one endpoint between them — that is:

> **36 distinct backend endpoints are unreachable to a real user while both guards count them as
> "reached."**

Against the 21 each guard reports, the true count of user-unreachable endpoints is **≈57**. The
guards see about 37% of it, and **the part they miss is bigger than the part they report.**

**Every one of the 39 hits a live endpoint. None is `@Deprecated`** — the three severed
pricing-costing routes all *do* have component callers ([S1](#s1-import-cannot-hand-a-price-to-the-ceo-the-one-button-that-does-it-always-errors--after-the-work-has-already-succeeded)), so the two sets are disjoint.

By cause:

| Cause | Count | Examples |
|---|---|---|
| **Backend advertises the action to the user; no component listens** | **1** | `tickets.setEntryChannel` — see [B4](#b4-the-21-is-an-undercount--and-the-missing-class-is-invisible-to-both-guards). Different in kind from the rest: this is a live capability discarded, not unreferenced code. |
| **Superseded by another `hrApi` method** | 13 | The four staged `priceImport` steps (collapsed into `uploadAndCommit`); four by-id pricing GETs whose list endpoints already return detail shape; `commissions.create` (split into `from-deal` + `manual`); `payroll.saveTaxAllowances` (the **write** half died, the GET is still live). |
| **UI removed, method deliberately kept** | 2 | `leave.policyDocumentAvailable` / `downloadPolicyDocument`. **Do not delete** — the comment quoted above explains why. |
| **Never wired** | 23 | Below. |

**The largest single gap: deduction obligations (issue #373) — 9 methods, an entire feature with no
UI.** `hrApi.js:609-623` declares the full CRUD-plus-workflow surface
(`getMyDeductionObligations`, `getDeductionObligations`, `getDeductionObligationProgress`,
`createDeductionObligation`, `updateDeductionObligation`, `stopDeductionObligation`,
`acknowledgeDeductionObligationCompletion`, `overrideDeductionObligationContinue`,
`clearDeductionObligationOverride`). All nine are implemented in `mockApi.js:6899-7011`, backed by
demo fixtures in `data/demoPayroll.js:610`, and declared in `routes.js:274`. **No component calls any
of them.** The only trace in the UI is a static label "หัก กยศ." at `PayrollPage.jsx:2211` with no
request behind it. Note this sits alongside `GET /api/payroll/deduction-shortfalls` in
[B3](#b3-built-backend-first-the-ui-never-landed-9) — the two halves of the same unshipped feature,
one invisible to the guards and one visible.

Other never-wired methods worth naming, each hitting a live endpoint: `payroll.getYtdSeed` /
`saveYtdSeed`; `payroll.estimateMyTaxAllowanceDeclaration` (the tax-effect preview, built and never
surfaced); `leave.reviewSummary` (whose own `hrApi.js:168` comment says the consuming IA rebuild is
"not yet landed"); the whole `factoryConfigs` namespace; `pricingRequests.markFactoryQuoteNotAvailable`
(the UI wires `markFactoryQuoteReady` but not the negative branch);
`pricingRequests.deleteFactoryQuoteAttachment` (upload and view are wired, delete is not);
`attendance.backfillCards`; `profileRequests.update`.

**Partial mitigation worth knowing.** `frontend/e2e-real/route-coverage.spec.js` opens every route in
`App.jsx` as every seeded role against the real backend and fails on a 5xx, so endpoints a page fires
**on load** are genuinely exercised. The blind spot is therefore narrower than "any uncalled hrApi
method": it is **endpoints reachable only through a control no component renders**.

**How solid this is.** The enumeration method is a grep for `api.<namespace>.<method>`, which is sound
here only because the corpus contains no indirection: I verified independently that
`frontend/src` outside `api/**` has **zero** matches for `api[` and **zero** for a destructuring
`const { … } = api`, so every one of the 370 `api.` occurrences is the direct member-expression form.
I spot-verified the deduction-obligation nine, the two `ytdSeed` methods and `tickets.setEntryChannel`
by hand; the remaining 27 I did not re-check individually — see
[Could not verify](#could-not-verify).

---

## B6. Capabilities stranded *below* the endpoint level

An endpoint-level sweep cannot see these: the route is called, the method is wired, and a whole branch
of the backend's behaviour is still unreachable because the client hardcodes the input that selects it.

### B6.1 The overseas per-diem branch cannot be reached from the portal **[pre-existing]**

`frontend/src/features/specialmoney/SpecialMoneyPanel.jsx:504-505` builds every travel claim's detail
payload with the destination **hardcoded**:

```js
if (values.requestType === 'TRAVEL_PER_DIEM') return { destination: 'DOMESTIC', province: values.province, role: values.role };
if (values.requestType === 'TRAVEL_LODGING') return { destination: 'DOMESTIC', province: values.province };
```

`SpecialMoneyPolicyEvaluator.java:401-427` branches on exactly that value:

```java
String destination = request.detailValue("destination");
boolean overseas = "OVERSEAS".equalsIgnoreCase(destination);
…
String region = request.detailValue("region");
BigDecimal rate =
    "ASIA".equalsIgnoreCase(region) ? amounts.amountOrZero("rate_asia") : amounts.amountOrZero("rate_other");
```

and `V66__special_money_request_schema.sql:173-176` seeds all four rates — `rate_driver` 400,
`rate_loader` 200, **`rate_asia` 600, `rate_other` 800**.

`specialMoneyRules.js:269` already lists `'destination'` in `DETAIL_KEYS` for `TRAVEL_PER_DIEM` — the
key is declared and never given a control. `region` is not in `DETAIL_KEYS` at all, and the frontend's
own estimate helper knows only the two domestic rates, so the live preview confirms the domestic
number before submit.

**User-visible symptom.** There is no control anywhere in the form to say a trip was overseas. Every
per-diem claim filed through the portal is evaluated on the domestic branch at ฿400/฿200 per day; the
฿600/฿800 overseas rates are seeded, implemented, and unreachable.

**Severity.** Stated as **broken now for the capability** — the portal cannot express the claim at
all. Whether that is currently harming anyone depends on whether GL&R has overseas travel and how such
claims are handled today, which **I could not determine** (see
[Could not verify](#could-not-verify)). This is welfare/HR, not sales, and it is **not** a regression
from this session — it is flagged because a capability sweep is the only thing that finds it.

### B6.2 `reopenedAt` / `reopenCount` are written to the database and never serialized

`TicketRepository.java:1148-1149` sets `reopened_at = now(), reopen_count = reopen_count + 1` on
reopen, and `:1137-1138` explains the columns were added so "reopened deals" would be answerable.
`TicketSummaryDto` declares **neither** — verified, no `reopen` token in the file.

`mockApi.js:2510` puts both on its ticket summary anyway (`reopenedAt`, `reopenCount`), and
`:4660-4661` maintains them. **No component reads either** — the only three hits in `frontend/src` are
those mock lines.

**User-visible symptom.** None. It is a **latent trap**: the next developer to write
`summary.reopenCount` will watch it work perfectly under `VITE_USE_MOCKS=true` and render blank in
production. That is CLAUDE.md's third named failure shape — the mock supplying a field the real API
omits — sitting pre-armed.

**Severity.** Untidy today. **Fix shape:** either serialize the two columns or drop them from the mock;
the current state is the worst of both.

### B6.3 `import` is granted quotation VIEW server-side and shown nothing

`frontend/src/features/tickets/ticketDetailTabs.js:55-75` carries an explicit `KNOWN GAP`:
`CustomerQuotationService` grants `import` VIEW access (pinned by its own integration test), while
`visibleSections('import')` sets both `dealQuotation` and `quotation` to `false`.

**Not a defect.** The comment records it as a deliberate product decision — exposing it would hand an
import assignee the approved customer-facing price. Listed only because it is the same reconciliation
axis pointing the other way, and because leaving it undocumented here would invite someone to "fix"
it.

---

# Stale mirrors — a served answer the frontend copied, ignored, or let drift

Neither direction: the backend *does* answer, and the frontend either keeps its own copy of the
answer or discards the one it is given.

## S1. Import cannot hand a price to the CEO. The one button that does it always errors — after the work has already succeeded.

**What.** `frontend/src/features/pricingRequests/PricingRequestDetailPage.jsx:850-853` renders
**ส่งให้ CEO อนุมัติราคา** (`data-testid="pcr-submit-to-ceo"`) for `import` on any current factory
quote in `RESPONSE_RECEIVED` / `NEGOTIATING` / `READY_FOR_COSTING`. It fires `submitToCeo`
(`:332-350`), a four-call chain:

```
1. markFactoryQuoteReady(quote.id)   ← live
2. createCosting(pricingRequestId)   ← 409, always
3. recalculateCosting(costingId)     ← never reached
4. submitCosting(costingId)          ← never reached
```

**Why it is wrong now.** V141 (PR #702) moved landed costing to the CEO and **severed** steps 2–4
rather than deleting them. `PricingCostingController` still routes all three (`:48`, `:60`, `:72`),
each `@Deprecated`, each delegating to a service method whose whole body is
`throw new ApiException(HttpStatus.CONFLICT, COSTING_MOVED_TO_CEO)` (`PricingCostingService.java:69-96`).
PR #722 found this and recorded it in [`api-surface-reconciliation.md`](api-surface-reconciliation.md) §4;
the frontend pass never landed.

Step 1 is now the **whole job**. `FactoryQuoteService.markReadyForCosting` (`:612-660`) auto-advances
the request `AWAITING_FACTORY_RESPONSE → READY_FOR_CEO_REVIEW` once every item's quote is resolvable,
logs `PRICING_COSTING_SUBMITTED`, and **notifies the CEO**.

**User-visible symptom.** Import clicks. The request really does advance and the CEO really is
notified — then step 2 throws, `onError` fires a red toast, and because `onSuccess` never runs
**`invalidate()` never runs**, so the page still shows the old status. Import sees an unchanged page
and an error, with no reason to think the CEO was told. Clicking again is worse: `quote.status` is now
`READY_FOR_COSTING`, so step 1 is skipped by the `if` at `:334` and the retry is a pure 409 that does
nothing at all.

**Severity.** Broken now, and the highest-impact item here — it is Import's only route out of the
pricing stage, and it misreports in both directions.

**Fix shape.** Delete steps 2–4; `markFactoryQuoteReady` alone is the action. Then drop
`createCosting` / `recalculateCosting` / `submitCosting` from `hrApi.js`, `mockApi.js`, `routes.js`
and the `CALLS_DEPRECATED` block together, and wire the CEO-side replacements listed in
[B3](#b3-built-backend-first-the-ui-never-landed-9).

**Related dead code (untidy, no symptom).** `:1009` (**สร้างร่างต้นทุน**), `:1034` (**คำนวณใหม่**),
`:1035` (**ส่งให้ CEO ตรวจ**) and the หมายเหตุต้นทุน field at `:1013-1023` also drive the severed
routes — but they sit inside a panel gated `canSeeRaw(user) && !isImport(user)` (`:1005`, CEO only)
while each button requires `isImport(user)`. The two conditions cannot both hold: **nobody has ever
been able to click them.** They matter only as three more call sites to delete alongside the fix.

## S2. The cancel button is missing from the three states #718 added it for

**What.** `frontend/src/features/pricingRequests/pricingRequestMeta.js:23-51` hand-copies the
pricing-request state machine as `ALLOWED_TRANSITIONS`. `canCancelPricingRequest` (`:213-218`) gates
on `canTransition(pr.status, 'CANCELLED')`, and that shows the **ยกเลิก** button at
`features/pricingRequests/PricingRequestPanel.jsx:178`.

**Why it is wrong now.** PR #718 widened the backend: cancel is legal up to and including
`APPROVED_FOR_QUOTATION`, refused from `QUOTATION_ISSUED` onward
(`PricingRequestStatus.java:107-179`). Three `→ CANCELLED` edges were added; the frontend has none:

| From status | Backend allows CANCELLED | Frontend allows CANCELLED |
|---|---|---|
| `READY_FOR_CEO_REVIEW` | **yes** (#718) | no |
| `CEO_REVIEWING` | **yes** (#718) | no |
| `APPROVED_FOR_QUOTATION` | **yes** (#718) | no |

**User-visible symptom.** A deal dies while the CEO is looking at the price, or after the CEO approved
one but before anything reached the customer. The owning rep — or the CEO — opens the pricing request
and there is **no ยกเลิก button**, with nothing explaining why. The request stays open, and the only
way to close it is to kill the whole deal. That is precisely the dead end #718 existed to remove.

**Severity.** Broken now. The capability shipped and is unreachable.

**Fix shape.** Delete `ALLOWED_TRANSITIONS` and `canTransition`; serve the verdict on the pricing
request the way `stageDecisions` does for deal stages. Same fix as [A3](#a3-a-customer-change-revision-is-offered-after-the-customer-accepted-and-refused-on-click).

**On the rest of that table.** It diverges from the backend in **twelve** places, but only these three
have a consumer: `canTransition` is called from exactly two sites — this predicate and
`mockApi.js:9313` — and both ask only about `'CANCELLED'`. The nine missing `SUPERSEDED` /
`AWAITING_FACTORY_RESPONSE` / `SUBMITTED → READY_FOR_CEO_REVIEW` edges are inert today. They are still
wrong, and they are why deleting the table beats patching it.

## S3. The stock-declaration button is hidden from the deal owner #706 built it for

**What.** `frontend/src/features/tickets/DealFulfilmentPanel.jsx:190`:

```js
reserveStock: hasAction('RESERVE_STOCK') && isFulfilment,
```

with `isFulfilment = isImport || role === 'ceo'` at `:100` (`isImport` from
`ROLE_PERMISSIONS.canPickupTickets = ['import']`, `routes.js:432`). It gates the
**จองสินค้าจากสต็อก** button at `:272-276`.

**Why it is wrong now.** PR #706 widened the backend to the deal owner
(`TicketService.java:2408-2411`):

```java
private boolean canDeclareStockCoverage(TicketSummaryDto s, UserPrincipal actor) {
    return FULFILMENT_ROLES.contains(actor.role())
        || (SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id());
}
```

`actions()` **already advertises it** — `canReserveStock` reads the same predicate and adds
`RESERVE_STOCK` to `availableActions` (`:2293-2296`, `:2446-2451`). The frontend receives the action
and then ANDs it away with the *pre-#706* role set. The panel is genuinely reachable by sales
(`salesViewScope.js:72` returns `allTrue()` for `sales`).

The backend's own Javadoc names this failure mode: the gate is one predicate so the two "cannot drift
into offering an action that immediately 403s" (`:2396-2398`), and the stage-floor comment adds that
applying it unevenly leaves the capability "live but invisible, or advertised and then refused on
click" (`:2417-2419`). The frontend reintroduces the first half.

**User-visible symptom.** A sales rep who owns a deal at `ORDER_RECEIVED` or later, with items still
undelivered, sees no way to declare which lines come from stock — the button is simply absent. The
server would accept the call.

**Severity.** Broken now. Affects exactly one role: `sales`, deal owner only. (`sales_manager` is
correctly hidden — the backend refuses it too.)

**Fix shape.** Drop `&& isFulfilment` from `reserveStock` alone. `hasAction('RESERVE_STOCK')` already
carries the ownership rule, the S10 stage floor and the remaining-delivery check. The other six
entries in that `can` object keep `isFulfilment` — their backend gates really are
`FULFILMENT_ROLES`-only.

## S4. In mock mode the CEO's "return to Import" writes a status production deleted

`frontend/src/api/mockApi.js:8805-8806` sets `pr.status = 'COSTING_REVISION_REQUIRED'` on
`returnToImport`. V141 removed that status from `chk_pricing_request_status` and from
`PricingRequestStatus.VALUES` (`:63-66`); the real
`PricingDecisionService.returnToImport` transitions `CEO_REVIEWING → AWAITING_FACTORY_RESPONSE`
(`PricingDecisionService.java:459-460`). `pricingRequestMeta.js` still declares it too, as a live
target of `CEO_REVIEWING` (`:38`) and as its own key row (`:39`).

**Symptom:** none in production. In mock mode, after the CEO returns a price the badge reads
**CEO ตีกลับให้แก้ไขต้นทุน** in `danger` tone (`format.js:169`); against the real backend the same
action produces **เจรจาราคากับโรงงาน** in `info` tone. Same click, different label and colour by
backend. **Severity:** invisible-but-wrong — and it makes mock-mode screenshots of the CEO return path
useless as design reference.

## S5. The one fixture field that drives mock-mode stage gating is the one its guard does not check

`frontend/src/data/dealStageCatalog.js` is the canned `/api/meta/deal-stages` payload.
`features/tickets/stageCatalog.test.js` parses `DealStage.java` and asserts this file's **codes,
order, `gate` and `no`** — the model tripwire. It does **not** compare `auto` to `AUTO_ADVANCED`; its
only `auto` assertion checks that each `auto: true` stage has an `AUTO_STAGE_HINT`. `phase` is checked
for monotonicity only; `businessCode` not at all.

`auto` is exactly what the mock's fact-gate stub keys off (see
[mock mode](#what-mock-mode-cannot-show)). The guard that exists to stop this class of drift does not
cover the field that drifted. **Symptom:** none directly — this is the missing tripwire under the
mock's most dangerous divergence. **Severity:** invisible-but-wrong.

## S6. The pricing-request test suite pins the stale machine, under a name that claims the opposite

`frontend/src/features/pricingRequests/pricingRequestMeta.test.js:44-45` opens
`describe('canTransition') → it('mirrors PricingRequestStatus.ALLOWED')`. Inside:

| Line | Assertion | Backend today |
|---|---|---|
| `:67` | `canTransition('CEO_REVIEWING', 'COSTING_REVISION_REQUIRED')` → **true** | status deleted by V141 |
| `:69` | `canTransition('COSTING_REVISION_REQUIRED', 'AWAITING_FACTORY_RESPONSE')` → **true** | status deleted by V141 |
| `:71` | `canTransition('READY_FOR_CEO_REVIEW', 'CANCELLED')` → **false** | **true** since #718 |

These are load-bearing in the wrong direction: anyone correcting `ALLOWED_TRANSITIONS` turns the suite
red and is told they broke the mirror. Unlike `stageCatalog.test.js` and `dealTrackingMeta.test.js`,
this file reads nothing from the backend source — every expectation is hand-written. **Symptom:** none.
It is the reason S2 and S4 survived. **Severity:** invisible-but-wrong.

## S7. Untidy residue

| Item | Detail | Symptom |
|---|---|---|
| `CUSTOMS_CLEARANCE` in `features/dashboard/ImportOverview.jsx:52` | `IN_TRANSIT_STATUSES = ['IR_SENT', 'SHIPPING', 'CUSTOMS_CLEARANCE']`. Deleted from `FulfilmentStatus.java` in `7991b9f1`; PR #715 removed the frontend labels in `format.js` and `stageMeta.js` and both now carry a comment warning against re-adding it. This is the copy #715 missed. | None — the literal can never match, so the bucket counts the right two. |
| `COSTING_REVISION_REQUIRED` in `ImportOverview.jsx:47` | In `PRICING_IN_FLIGHT_STATUSES`. The retention comment at `:39-45` justifies only `COSTING_IN_PROGRESS` and `MORE_INFO_REQUIRED` (V140); this one was dropped later by V141 and is **not** covered by that reasoning. | None. |
| `COSTING_REVISION_REQUIRED` in `utils/format.js:169` | A label for the V141-deleted status. Arguably still needed for historical `pricing_request_event` rows — which is exactly why the neighbouring `COSTING_IN_PROGRESS` (`:161`) and `MORE_INFO_REQUIRED` (`:177`) are retained, each with a written reason. This one has none. | None. Decide rather than leave it looking like an oversight. |
| `hasPdf` / `hasXlsx` on `issue()` | PR #721 moved the deposit-notice render to an `afterCommit` callback (`DepositNoticeService.java:388-430`), so `issue()` now returns both as `false` and a later read returns `true`. A real response-shape change, stated deliberately in the code. **No frontend consumer exists** — the three hits in `frontend/src` are `data/demoSales.js:351` and two mock DTO literals. Download buttons gate on `doc.status === 'ISSUED'` (`DealDepositPanel.jsx:271`), and `issueMutation.onSuccess` invalidates and refetches anyway. | None — but wiring a download affordance to these flags would make the button vanish for one render after issue. |
| Nine inert `ALLOWED_TRANSITIONS` divergences | See [S2](#s2-the-cancel-button-is-missing-from-the-three-states-718-added-it-for). | None. |
| `data/demoSales.js:398,420,443` | Every seeded deal hardcodes `entryChannel: 'DESIGNER_LED'`, so no mock-mode deal ever shows the `UNSPECIFIED` state V144 made the stored default — the state [B4](#b4-the-21-is-an-undercount--and-the-missing-class-is-invisible-to-both-guards)'s missing UI is needed for. | None, but it hides the gap. |
| `data/demoSales.js:351` | `hasPdf: status === 'ISSUED'` — now more optimistic than production's `issue()` response. | None (nothing reads it). |
| `features/tickets/salesActions.js:74-76` | Comment still narrates `COSTING_REVISION_REQUIRED` / `COSTING_IN_PROGRESS` / `MORE_INFO_REQUIRED`. The actual `LIVE_PR_STATUSES` set (`:80`) does not contain them. | None — comment only. |
| VAT hardcoded in the deposit-notice preview | `features/deposits/DepositNoticePage.jsx:464` computes `const vat = deposit * 0.07;` for the live summary the user watches while editing. `DepositNoticeDto` **serves `vatPercent`** (`:25`) alongside `vatAmount` and `totalPayable` — the answer is on the wire and ignored. The literal also duplicates `CustomerQuotationService.VAT_RATE` and `QuotationRenderer.VAT_RATE`, so the frontend is the third copy. All three agree today. | None today. A Thai VAT-rate change would make the preview disagree with the saved document, silently. |
| `reopenedAt` / `reopenCount` | See [B6.2](#b62-reopenedat--reopencount-are-written-to-the-database-and-never-serialized). | None — a pre-armed mock/production divergence. |

---

# What mock mode cannot show

`VITE_USE_MOCKS=true` is the default verification surface. After this session it diverges from
production in ways that matter for design work. **These are behaviours you will not see, or will see
wrongly, by clicking through mocks:**

| Behaviour | Mock | Production |
|---|---|---|
| Manual stage move to `DELIVERED` | **allowed** on an undelivered deal | 409 — `ยังส่งมอบสินค้าไม่ครบ` |
| Manual stage move to `PROCUREMENT` | blocked | allowed for `import`/`ceo` |
| The stage-move note requirement | `requiresReason` hardcoded `false` — never demanded | demanded per `DealStage.requiresJustification` |
| Discount on a quotation **revision** | **succeeds** — `mockApi.js:8873-8892` has no `parentQuotationId` gate | 409 (see [A2](#a2-the-quotation-revision-discount-input-is-live-and-saving-it-409s)) |
| Creating a revision | carries prior per-line discounts forward (`mockApi.js:8987-8992`, commented *"same as the real service"* — which #703 made false) | every line rebuilt at discount zero |
| CEO "return to Import" | status becomes `COSTING_REVISION_REQUIRED` (red) | becomes `AWAITING_FACTORY_RESPONSE` (blue) |
| The three costing calls behind [S1](#s1-import-cannot-hand-a-price-to-the-ceo-the-one-button-that-does-it-always-errors--after-the-work-has-already-succeeded) | **succeed** — the mock never learned they were severed | 409, always |
| Stock declaration (`RESERVE_STOCK`) | offered to `import`/`ceo` only; **no S10 stage floor** | offered to the deal owner too; refused below `ORDER_RECEIVED` |
| Pricing-request cancel | uses the same stale frontend table — refused in the three new states | allowed |
| Deposit `hasPdf`/`hasXlsx` after issue | fixture says `true` when `ISSUED` | `false` on the issue response, `true` on the next read |

**The `DELIVERED` row is the dangerous one.** `mockApi.js:2415-2417` derives its fact-gate stub from
the wrong set:

```js
const MOCK_FACT_GATED_STAGES = new Set(
  DEAL_STAGE_CATALOG.stages.filter((stage) => stage.auto).map((stage) => stage.code),
);
```

`auto` is `DealStage.AUTO_ADVANCED` = `{ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT, CLOSED_PAID}`
(`DealStage.java:170-171`). The stages #710 fact-gates are
`{ORDER_RECEIVED, DEPOSIT_RECEIVED, DELIVERED, CLOSED_PAID}`
(`TicketService.requireStageFactsHold`, `:1619-1636`). Different sets, differing both ways. The
mock's own comment at `:2399-2405` asserts the stub is *"STRICTER than production, never looser"* and
names this as "the exact hazard CLAUDE.md names" — for `DELIVERED` that claim is false.

**The stock-declaration row is the subtlest:** the mock is **more restrictive on who** and **more
permissive on when** than production, simultaneously. Neither direction is visible from the UI.

**What mock mode does model faithfully**, and can be trusted for: the deal-stage catalog
(`/api/meta/deal-stages`, guarded against `DealStage.java`); the commission monthly summary, whose
mock is an explicitly canned snapshot rather than a re-implementation of the tier math; and
`effectiveWinProbability`, which the mock deliberately omits because the real backend does not
serialize it either (`mockApi.js:2522-2525`).

One consuming component is worth calling out as correct: `UpdateStageModal.jsx` decides nothing. It
filters on the server's `allowed` (`:27`), reads `requiresReason` (`:36`) and nothing else, and
renders `blockedReason` verbatim (`:136`). It is the shape every other item here should be fixed
toward — the mock's `requiresReason: false` is the only reason its note path goes unexercised.

---

# Seed items that did not hold up

Recorded so nobody re-investigates them.

| Claim carried into this audit | Verdict |
|---|---|
| `GET /api/commissions/monthly-summary` (#712) is a new backend capability with no UI | **False.** Consumed by `features/commissions/CommissionPage.jsx:380` and `features/dashboard/SalesOverview.jsx:76`. #712 wired both in the same PR. |
| `/api/meta/deal-stages` and `stageDecisions` (#713) have no UI | **False.** `features/tickets/stageCatalog.js:43` fetches the catalog; `TicketListPage.jsx:606` and `TicketDetailPage.jsx:388` consume it. `stageDecisions` is read at `TicketDetailPage.jsx:402` → `DealStagePanel` → `UpdateStageModal`. |
| `features/tickets/salesViewScope.js` carries stale payment literals | **False.** Its only payment set is `{DEPOSIT_NOTICE_ISSUED, AWAITING_FINAL_PAYMENT}` (`:101`); both are current `PaymentTrack` values, as are `closed`/`cancelled` at `:100`. A deliberate subset, not a stale mirror. |
| Dead fulfilment literals in `utils/format.js` | **False — already fixed.** `fulfilmentStatusLabel` (`:470-481`) is exactly `FulfilmentStatus.java`'s seven values. PR #715 removed `PICKED_UP` and `CUSTOMS_CLEARANCE` from it and from `PROCUREMENT_SUBSTEPS`. Only `ImportOverview.jsx` still carries one. |
| Mock-mode clicking "walks a deal from lead to paid" | **False.** The mock blocks manual moves into all four `auto` stages, so `ORDER_RECEIVED` / `DEPOSIT_RECEIVED` / `PROCUREMENT` / `CLOSED_PAID` are reachable only via the operational action that records the fact. The real gap is `DELIVERED`. The `requiresReason` half of the claim **is** true. |
| #710 changed frontend files | **False.** All six files in `f50a6207` are under `backend/`. The mock's fact-gate stub arrived in #713. |
| V144 made the backend refuse a ticket created without an entry channel | **False.** `UNSPECIFIED` is in `EntryChannel.VALID` and is substituted for a blank on create (`TicketRepository.java:260-261`). The only refusal is on the `setEntryChannel` action (`TicketService.java:1923-1926`). #711's "must choose" is a client-side zod rule. |
| `format.js` / `stageMeta.js` mirror stale stage data after V143 | **False.** `QUOTE_OWNER` is present and correct in `format.js:371`, `dealStageCatalog.js:24` and `dealTrackingMeta.js:34`; `stageMeta.js` is now labels-only. #713/#714/#715 finished this. |
| `PAYMENT_SUBSTEPS` shows deposit steps a bypass deal never reaches | **False.** `DealMoneyTimeline.jsx:186` suppresses the row entirely on a bypass policy. |
| `taxAllowanceCaps.js` hardcodes tax ceilings | **False.** Every figure comes from `GET /api/payroll/tax-allowances/caps`. |

---

# Could not verify

Stated rather than omitted.

1. **Nothing here was executed against a running backend.** Every claim is read from source at
   `9c15039b` plus the merge diffs. The 409s in S1, A2 and A3, and the widened gates in S2 and S3, are
   read off the Java control flow and the integration tests shipping with those PRs — not observed in
   a browser. The real-backend e2e suite (`npm run test:e2e`) was **not** run: it needs Postgres and a
   running Spring backend, neither of which was started for a docs-only change.
2. **No authorization claim here is verified evidence.** S2 and S3 turn on who may act. Per
   `CLAUDE.md` that requires a real-DB integration test through the Java service. The backend side has
   one (`StockDeclarationAuthzIntegrationTest`, `PricingRequestCancelCutoffIntegrationTest`); the
   *frontend consequence* — that the button is absent for the permitted user — is inferred from the
   gate expression, not observed. Treat "the deal owner cannot see the button" as **unverified** until
   someone clicks it against the real stack.
3. **[B5](#b5-the-size-of-the-blind-spot-the-undercount-is-larger-than-the-reported-number)'s 39 are
   not all individually re-checked.** The mechanism is proven, the no-indirection precondition that
   makes the grep sound is verified, and I hand-checked twelve of them (the deduction-obligation nine,
   both `ytdSeed` methods, `tickets.setEntryChannel`) plus the `LeavePolicyBar` keep. **The other 27
   I did not re-verify individually.** Treat the headline "36 endpoints / ≈57 true total" as a
   well-founded estimate, not a deletion list — confirm each method before removing it.
4. **Nobody should delete anything from B5 on this document alone.** Two entries are explicitly
   marked keep (`leave.policyDocument*`), and thirteen more are "superseded" verdicts that rest on
   reading two methods as equivalent. Every one needs its own check.
5. **Whether the overseas per-diem gap ([B6.1](#b61-the-overseas-per-diem-branch-cannot-be-reached-from-the-portal-pre-existing))
   harms anyone is unknown.** I confirmed the portal cannot express the claim and that the backend
   implements and seeds the rates. I could **not** determine whether GL&R has overseas travel, whether
   such claims are filed on paper, or whether any have been paid at the domestic rate. That is a
   question for the owner, and it decides the item's real severity.
6. **S1's exact toast text is inferred.** `onError` falls back to
   `error.message || 'ส่งให้ CEO ไม่สำเร็จ'` (`PricingRequestDetailPage.jsx:349`). Whether the user
   sees the backend's Thai 409 or the generic string depends on how `apiRequest` surfaces a 409 body,
   which was not traced end to end. That the toast is an **error** either way is certain.
7. **S1's "the request really did advance" depends on `isFullyResolvable`.** Step 1 only auto-advances
   when *every* item's factory quote is resolvable. On a multi-factory request with one quote
   outstanding, the click fails at step 2 with no advance at all — a different symptom (plain failure)
   from the one described. Both are broken; which one a given user hits was not enumerated.
8. **Production data was not consulted.** How many pricing requests sit in the three states S2 makes
   uncancellable, how many deals sit at `UNSPECIFIED`, or how many are `FROM_STOCK` and therefore
   hitting A1 today, would change the priority order and is not known here.
9. **PRs #723–#726 were only skimmed.** They landed after the #703–#722 window. #725 in particular
   changed upload error statuses to 415/400/413 and made `GET /api/tickets` paging deterministic; the
   frontend has **no client-side file-size or MIME guard** anywhere, so a 413 now surfaces as whatever
   `apiRequest` renders. Not chased down.
10. **A local branch is in flight on S3's surface.** `feat/stock-declaration-notify` exists in a sibling
   worktree, unpushed, with no open PR (the only open PRs are five dependabot bumps). Whether it
   already fixes the `DealFulfilmentPanel` gate was not checked — it was left untouched by
   instruction.
