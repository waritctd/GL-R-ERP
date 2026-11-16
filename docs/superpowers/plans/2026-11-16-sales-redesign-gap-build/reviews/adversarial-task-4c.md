# Adversarial review: task 4c, grouped finance worklist (commit 8899d84b)

## Verdict: FIX BEFORE MERGE

There is no money-rule change, no change to which deals are visible, and no authz change. Two problems still need fixing before merge: the CEO gets action buttons it cannot use, and the tests do not pin the amount fields the slice newly introduced.

Base: 5b07639b. Detached checkout in an isolated worktree. `npx vitest run src/features/finance/ src/features/dashboard/ src/features/tickets/accountActions.test.js` gave 25 files and 491/491 tests passing at the slice. All mutations ran by script and were restored byte-for-byte. `git status --short` is clean.

---

## Findings

### F1 (MEDIUM, proven by reading code): the CEO gets action buttons it cannot perform
- **State and role:** CEO on `/finance`, with any deal where `confirmCloseReady` applies (FULLY_PAID + FULLY_DELIVERED + invoice on file + not close-confirmed), or `recordInvoiceCommission` applies (CLOSED_PAID, commission not recorded).
- **What happens:** `WorkRow` renders `group.buttonLabel` regardless of role (`AccountFinancePage.jsx:132-141`, labels from `financeWorkGroups.js:12-30`). The CEO therefore sees a primary button "ยืนยันพร้อมปิดงาน" and a button "บันทึกใบกำกับ". The server offers neither action to the CEO:
  - `TicketService.java:99`: `CLOSE_CONFIRM_ROLES = Set.of("account")`
  - `FinanceDealService.java:220-224`: RECORD_INVOICE is added only when `"account".equals(actor.role())`, with the comment "never offered to ceo".
- **Before the change:** the CEO saw the same words as a status in the "ขั้นตอนที่ต้องทำ" column of a row link. That was descriptive text, not a call to action.
- **Now:** an imperative, primary-styled button whose aria-label is "ยืนยันพร้อมปิดงาน · <name>" leads to a deal page with no such action. The old row link never created this dead end.
- **The tests pin the bug:** `AccountFinancePage.test.jsx` (CEO wrong-way-round test, ~line 222-224) asserts the CEO's 206 row link text is `'ยืนยันพร้อมปิดงาน'` and the 203 row link text is `'บันทึกใบกำกับ'`.
- **Fix:** for a viewer who is not `account`, label the button "เปิด" (secondary), or show the step as text plus "เปิด". Change the test to assert the CEO cannot be offered those verbs.
- **Caveat:** verified from Java source only; not run against the service. The implementer's report flags the invoice case but describes the close case as "server-gated", which is the problem itself.

### F2 (MEDIUM, proven by mutation): the new amount fields are not pinned
- **What survives:** M8a (closeReady shows `amountPayable` instead of `amountPaid`) and M8b (invoice shows `amountPayable` instead of `amountPaid`) both pass all 491 tests.
- **Why:** every fixture has `amountPaid === amountPayable` (closeReadyTicket 596000/596000, closedPaidTicket 90000/90000).
- **Why it matters:** these are the two places where the slice changed which money field a row shows. The old table showed `amountOutstanding` on every row. Without pinning, a swap to the gross payable would ship green.
- **Fix:** use fixtures where paid ≠ payable, for example with an ADJUSTMENT receipt or a credit note. Paid is net of ADJUSTMENT (`TicketRepository` `SIGNED_RECEIPT_SUM`, around line 2120).

### F3 (LOW to MEDIUM, mutation survives): an unknown action key is silently dropped, and the tab badge disagrees
- `groupFinanceWork` drops any row whose key is not mapped (`financeWorkGroups.js:47-49`), and the unit test makes that the intended behaviour (`financeWorkGroups.test.js` "an action key it does not know is not silently mapped anywhere").
- The "ต้องดำเนินการ N" badge still counts `actionable.length`, which includes such a row (`AccountFinancePage.jsx:216,271`).
- **Effect:** the day `nextAccountAction` gains a key (for example a credit-note or partial-payment step), that deal is counted in the badge but appears in no group. It is reachable only in ทั้งหมด, with nothing to show it is missing.
- **Mutation M4b** (route unknown non-null keys into ติดตามชำระ) SURVIVES, so nothing pins the behaviour either way at page level.
- **Today:** `nextAccountAction` returns exactly five keys (`accountActions.js:94-113`) and all five are mapped once. Nothing is hidden today; this is a latent trap.
- **Fix:** export the key list from accountActions (or reuse `ACTION_FILTERS`) and add an exhaustiveness test. Better still, give unmapped actionable rows a fallback group or line so the total stays true.

### F4 (LOW, mutation survives): order within ยืนยันพร้อมปิดงาน and บันทึกใบกำกับ is not tested
- Mutation M9b (reverse only those two groups) SURVIVES, because each group has one row in the fixtures. Only the ติดตามชำระ order is pinned.
- The behaviour itself is correct: groups come from the already-sorted `allRows`, and `Array.prototype.sort` is stable.

### F5 (LOW, suspected; no database to check): "รับครบ ฿0.00" on legacy close-ready deals
- `closePrerequisitesMet` accepts legacy `document_issued` deals whose `paymentStatus` is null (`accountActions.js:63-65`).
- `amountPaid` is the signed sum of `sales.payment_receipt` rows, so a legacy deal with no receipt rows shows "รับครบ ฿0.00", claiming the deal is fully paid at zero.
- The old table showed outstanding instead (— or ฿0.00, which is not a claim).
- Whether such rows exist in prod is unverified. The repo's memory note says prod is read-only, and I did not query it.

### F6 (LOW, read code): the figures are not byte-for-byte what the old table showed for the same deal
Old: one "คงค้าง" column = `amountOutstanding` on every row. New:

| Group / case | New label | Field |
|---|---|---|
| closeReady | รับครบ | `amountPaid` |
| invoice | ยอดรับจริง | `amountPaid` |
| confirmDeposit with `depositDue` | มัดจำ | `depositDue` |
| otherwise | คงค้าง | `amountOutstanding` |

All of these are server fields and nothing is recomputed, which the brief allows. Two points of meaning:
- `depositDue` is the full total of the newest deposit notice, not the remaining amount. A partially paid deposit shows the full notice under a hint that says "no money found". It is consistent with AccountOverview's มัดจำ sum.
- "ยอดรับจริง" is the commission chain's own term for `actualReceived` (`ReceiptChain.jsx:35`), and the invoice form defaults its "ยอดรับจริง (รวม VAT)" to `amountPayable` (`FinanceDealPage.jsx:312`), not `amountPaid`. Next to the hint "ใช้คำนวณคำขอค่าคอม", it can read as the commission base. The label and value came from the drawing; flagged for the owner.

There are no group money totals. Group counts are counts only. "ค้างรับทั้งหมด" is the same `serverOutstanding` expression, unchanged, now only in ทั้งหมด. It is one tap away, and AccountOverview still leads with it. The default view implies no total.

### F7 (LOW, suspected visual; not phone): chip may overlap the button on desktop
- At widths just above the 1040px nav-drawer breakpoint, the five-column row (`8rem | 1.6fr | 1.2fr | 1fr | auto`) leaves the chip column at roughly 100px.
- `.status-badge` is `white-space: nowrap` (`styles.css:835-847`), and "เกินกำหนดชำระ 1 ก.ย. 2569" is wider than that, so it can overlap the button column.
- Not measured.

### F8 (LOW): the old test "CLOSED_PAID row action reads บันทึกใบกำกับ" is now vacuous on the default view
- It asserts `getAllByText('บันทึกใบกำกับ').length > 0` on `/finance`. The group heading "บันทึกใบกำกับ" always renders, even with zero rows.
- The ออกค่าคอม-absent half still holds.

---

## Attacks that found nothing

1. **Hidden deals.** The old default view was already the "ต้องดำเนินการ" tab (`actionable` = `nextAccountAction(ticket, role) != null`), not everything. The new default is `groupFinanceWork(allRows)`. With all five keys mapped, it is the identical set for account and for the CEO, since the same `user?.role` is passed (line 202, unchanged). Nothing that was visible by default is now ทั้งหมด-only.
   - What left the default view: the summary block (ค้างรับทั้งหมด, overdue count, nearest due date), the two filter selects, the per-row milestone and stage-code chip, and the due date on non-chase rows. All of it is intended per the drawing, and all is still in ทั้งหมด.
   - Overdue rows are always chaseOverdue (first branch, no role check), so they land in ติดตามชำระ.
2. **Exactly one group.** Mutations M1a–e, M2a–b, M4, M5, M6 and M6b were all killed.
3. **Who sees what.** `/finance` is still `canConfirmPayments` = account and ceo (`routes.js:729`, `permissions.js:95`), so no other role reaches the page. M3 (ignore role) is killed by the two CEO tests. No authz code changed. The mock is not authz evidence, and this review did not run the Java service; the server gates were read, not run.
4. **Deep links.**
   - AccountOverview's `/finance?action=<key>`, legacy `?stage=`, and `?milestone=` still open the filtered table in the todo tab, the same rows as before (M13 killed).
   - `?view=all` is unchanged (M7, M7b killed). `?view=junk` falls back to todo, as before. `?action=junk` gives the filtered empty state plus ล้างตัวกรอง, as before.
   - The FinanceDealPage back link `/finance`, e2e-real `cash-and-ops.spec.js:339,479` (Express card only), `route-coverage`, and the visual baseline list are unaffected. Notifications link to `/finance/deals/:id`, not this page.
   - The What's-new 2026-11-16 entry says "เลือกดีล". That is release copy, not this page.
5. **ทั้งหมด view.** Filters and sort are identical code. The page has no search, so the brief's "search" does not exist. The summary moved inside the panel, below the Tabs.
6. **Express card.** Same component, same flag condition (`ReceiptsImportPage.jsx:39`, untouched). Still placed directly after the PageHeader and before the Tabs; only the summary block that used to sit between them moved. M14 killed.
7. **Wording.** No "ดีล" in this page's text or innerHTML (tested). No identifier, route, enum or API value was renamed. Testids are `fin-work-row-*` and `fin-group-*`.
8. **Phone.** Below 1040px the row collapses to `minmax(0,1fr) auto`, text uses `overflow-wrap:anywhere`, and buttons get `mobile:min-h-11 pointer-coarse:min-h-11` (44px). The chip fits beside "เปิด" at 320px. No horizontal-scroll source found in code. Not measured by me; the implementer reports measuring 320 and 375px.
9. **Old-to-new test mapping.** All 21 old tests are present.
   - 15 run with the same assertions, either on `?view=all` or on the default view.
   - Wording changes are limited to ดีล→งาน.
   - "every row is one link" now covers 4 rows plus the idle row href.
   - The load-error test asserts the old and new empty copy are both absent.
   - The default-view properties that moved (sort order, one control per row, the outstanding figure) are each re-pinned by new grouped-view tests: in-group order for ติดตามชำระ, one link per row, figures.
   - The only weakened assertion is F8.
   - AccountOverview's adapted test renders `?view=all` with an unchanged assertion, which is legitimate.

## Drawing fidelity

| Element | Status |
|---|---|
| Subtitle, the three group labels, hints, group order, buttons, primary style for close | Verbatim |
| Express source chip | Missing; needs the API |
| Sales rep under the name | Missing; the row shows customer + project instead |
| "เกินกำหนด N วัน" | Replaced by "เกินกำหนดชำระ <date>" |

Judgement on the gaps: the rep and source chip are acceptable gaps. The day count is acceptable because the date carries the same decision. The deposit row's "ครบกำหนด" chip in the drawing can never appear in practice, because `paymentDueDate` is null until delivery and an unpaid deposit is never overdue (`TicketSummaryDto.java:110-123`).

**confirmDeposit and confirmFinalPayment under ติดตามชำระ: defensible.** The drawing's own ติดตามชำระ group holds a non-overdue "มัดจำ ฿145,800" row, there is no other group for them, and the step label shown under the amount ("ยืนยันรับมัดจำ" / "รับชำระส่วนที่เหลือ") keeps finance's real action visible. Strictly, confirming money that has already arrived is not chasing it, so the owner may want a fourth group, but the mapping follows the drawing.

## nextAccountAction key × role

| key | condition (`accountActions.js`) | account → group | ceo → group | default-visible before / now |
|---|---|---|---|---|
| chaseOverdue | overdue && outstanding > 0 (any role) | ติดตามชำระ | ติดตามชำระ | yes / yes (both roles) |
| confirmDeposit | account only; quotation_issued && DEPOSIT_NOTICE_ISSUED | ติดตามชำระ | null → none | account yes/yes; ceo no/no |
| confirmFinalPayment | account only; offersFinalPayment (past procurement) | ติดตามชำระ | null → none | account yes/yes; ceo no/no |
| confirmCloseReady | closeReady (any role) | ยืนยันพร้อมปิดงาน | ยืนยันพร้อมปิดงาน (dead-end button, F1) | yes / yes |
| recordInvoiceCommission | CLOSED_PAID && !commissionRecorded (any role) | บันทึกใบกำกับ | บันทึกใบกำกับ (dead-end button, F1) | yes / yes |
| null | none of the above | none (ทั้งหมด only) | none | no / no |

For the CEO, a deposit or final-payment deal falls through the role-gated branches and may then match closeReady or invoice. In practice it does not, because those require FULLY_PAID or CLOSED_PAID.

## Mutations (25 killed, 4 survived)

| Mutation | Result |
|---|---|
| M1a drop confirmCloseReady | killed |
| M1b drop chaseOverdue | killed |
| M1c drop confirmDeposit | killed |
| M1d drop confirmFinalPayment | killed |
| M1e drop recordInvoiceCommission | killed |
| M2a confirmFinalPayment → closeReady group | killed |
| M2b recordInvoice → chase group | killed |
| M3 ignore role | killed (2 CEO tests) |
| M4 null-action rows shown in chase | killed |
| **M4b unknown non-null key routed into chase** | **SURVIVED** |
| M5 swap group order | killed |
| M6 count off by one | killed |
| M6b hide empty groups | killed |
| M7 ทั้งหมด tab click no-op | killed |
| M7b `?view=all` ignored | killed |
| **M8a closeReady amount = amountPayable** | **SURVIVED** |
| **M8b invoice amount = amountPayable** | **SURVIVED** |
| M8c chase amount = amountPayable | killed |
| M8d deposit amount = outstanding | killed |
| M8e closeReady amount = outstanding | killed |
| M9 groups from reversed rows | killed |
| **M9b reverse close/invoice groups only** | **SURVIVED** |
| M10 button href /tickets | killed |
| M11 overdue chip drops date | killed |
| M12 summary on default view | killed |
| M13 filter deep link ignored | killed |
| M14 Express card removed | killed |
| M15 chase step text dropped | killed |
| M17 todo badge = all rows | killed |
| M19 all-empty line removed | killed |

Not mutated: the flag-off Express test. It asserts absence right after `latest` is called, possibly before the query resolves, so it may be weak. The component is untouched, so the risk is low.

## Proven vs suspected
- **Proven:** F1 (by code), F2, F3, F4 and F8 (by mutation or reading), and every "found nothing" item.
- **Suspected:** F5 (data-dependent), F6's meaning of "ยอดรับจริง", F7 (not measured).
