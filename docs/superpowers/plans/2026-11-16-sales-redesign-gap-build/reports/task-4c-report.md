# Task 4c — grouped finance worklist on "งานการเงิน" (slice D2) — report

Worktree: `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\finance-worklist`
Branch: `feat/finance-grouped-worklist`, cut from `origin/develop` @ 5b07639b. **Uncommitted** (rules forbid committing).
Frontend only. No backend, schema, API, permission, mockApi/hrApi change. Nothing under `features/tickets/` or `features/salesBoard/` modified (only `nextAccountAction` imported, as before).

## Grouping rule (no new money rule)
`frontend/src/features/finance/financeWorkGroups.js` — a pure lookup on the key the EXISTING
`nextAccountAction(ticket, viewerRole)` already returns (`frontend/src/features/tickets/accountActions.js:84`, keys at :94/:97/:100/:103/:107).
The page still calls it exactly as before (`AccountFinancePage.jsx:202`, `nextAccountAction(ticket, user?.role)`), then groups the already-sorted rows (`:229`).

| nextAccountAction key | group (drawn label) |
|---|---|
| `confirmCloseReady` | ยืนยันพร้อมปิดงาน |
| `chaseOverdue`, `confirmDeposit`, `confirmFinalPayment` | ติดตามชำระ |
| `recordInvoiceCommission` | บันทึกใบกำกับ |
| `null` (nothing for finance) | no group — still listed in ทั้งหมด |
| unknown future key | no group (tested) — still listed in ทั้งหมด |

Why deposit/final-payment are in ติดตามชำระ: the drawing's ติดตามชำระ group contains a not-overdue row "มัดจำ ฿145,800 · ครบกำหนด 20 พ.ย." — i.e. a deposit notice issued and not yet paid; the hint "ออกใบแจ้งหนี้แล้ว ยังไม่พบเงินในไฟล์ล่าสุด" describes both "money not in yet" steps. The drawing has no other group for them. This is the one interpretive mapping — reviewer should confirm it.

## Drawing elements used (verbatim)
- Subtitle: "รับเงินมาจากไฟล์ Express · ที่เหลือคือสิ่งที่ต้องทำต่อ"
- Groups, in order, with hints: "ยืนยันพร้อมปิดงาน" / "ส่งของครบและรับเงินครบแล้ว"; "ติดตามชำระ" / "ออกใบแจ้งหนี้แล้ว ยังไม่พบเงินในไฟล์ล่าสุด"; "บันทึกใบกำกับ" / "ใช้คำนวณคำขอค่าคอม (เหมือนเดิม)"
- Row buttons: "ยืนยันพร้อมปิดงาน" (primary), "เปิด", "บันทึกใบกำกับ" (secondary)
- Row figures: "รับครบ ฿…" = `amountPaid`; "คงค้าง ฿…" = `amountOutstanding`; "มัดจำ ฿…" = `depositDue` (confirmDeposit rows; falls back to คงค้าง when the field is null); "ยอดรับจริง ฿…" = `amountPaid`. All server-sent TicketSummaryDto fields, formatted with the existing `formatMoney`, nothing recomputed.
- ติดตามชำระ chip: overdue → danger "เกินกำหนดชำระ <paymentDueDate>" (the page's existing overdue wording); else due date → warning "ครบกำหนด <date>".

## Before / after — actions and who sees them
Page access unchanged: `/finance` = `canConfirmPayments` = account, ceo (`app/permissions.js:95`, `api/routes.js:729`).

| Action | Before | After | Condition (before = after) |
|---|---|---|---|
| Open deal finance page from a worklist row | whole row was a link to `/finance/deals/:id` (todo + all views) | todo view: the row's one button links to `/finance/deals/:id`; ทั้งหมด view: unchanged whole-row link | row present iff `nextAccountAction(ticket, role) != null` (todo) / always (ทั้งหมด) |
| ยืนยันรับมัดจำ / รับชำระส่วนที่เหลือ step shown | action label in row; account only (`nextAccountAction` drops them for ceo, GLA-118) | row in ติดตามชำระ with step text + "เปิด"; account only | same helper, same `viewerRole` |
| ติดตามชำระ (overdue) | account + ceo | account + ceo, "เปิด" | same |
| ยืนยันพร้อมปิดงาน | account + ceo (label; action itself on deal page, server-gated) | account + ceo, button links to deal page | same |
| บันทึกใบกำกับ | account + ceo (label; RECORD_INVOICE on the deal page is server-offered) | account + ceo, button links to deal page | same — note CEO sees the button as before saw the label; the deal page decides |
| Filters (ขั้นการเงิน, ขั้นตอนที่ต้องทำ) | both views | ทั้งหมด view, and todo view whenever a filter param is present (deep links) | n/a |
| Express upload card | `<ExpressUploadCard />` | identical element, same position, untouched component | server flag `uploadEnabled` |

No button opens a modal from the list: the modals live in FinanceDealPage and are driven by the server's `availableActions` on the FinanceDealDto, which the list does not have. Every button therefore does exactly what the row did before: go to the deal finance page, where the action and its confirm live (checked on-screen: closeReady button → `/finance/deals/24`, deal page's primary button is ยืนยันพร้อมปิดงาน).

## Page structure
- Tabs kept: "ต้องดำเนินการ" (default) = grouped worklist; "ทั้งหมด" (`?view=all`) = the previous flat subgrid table with filters, sort, and the ค้างรับทั้งหมด block.
- Deep links: `?action=`, legacy `?stage=`, `?milestone=` on the todo view show the filtered table with its select, exactly as before (AccountOverview links `/finance?action=<key>`). `?view=all` unchanged.
- ค้างรับทั้งหมด: the drawing drops it from the landing; kept, unchanged, at the top of the ทั้งหมด view (also still on the account dashboard).
- Empty: an empty group shows count 0 + "ไม่มีรายการ" (ImportOverview pattern); all three empty → one line "ไม่มีงานการเงินที่ต้องทำตอนนี้ · ดูงานที่เสร็จแล้วได้ที่มุมมอง "ทั้งหมด"" (ImportOverview's "nothing" pattern).
- Wording: every "ดีล" in this page's labels → "งาน" (tab counts, summary, column header, empty states).
- Sorting unchanged; groups keep it (overdue first, due date, amount desc). There is no search on this page today (brief mentions "search") — nothing to keep.

## Files changed
- `frontend/src/features/finance/financeWorkGroups.js` (new) — group table + `financeWorkGroupKey` + `groupFinanceWork`.
- `frontend/src/features/finance/financeWorkGroups.test.js` (new) — 16 tests.
- `frontend/src/features/finance/AccountFinancePage.jsx` — subtitle; `WorkGroup`/`WorkRow`; grouped todo view; summary + FilterBar moved to ทั้งหมด / filtered; ดีล→งาน.
- `frontend/src/features/finance/AccountFinancePage.test.jsx` — 18 new tests, 12 adapted (below). Note: rewritten via file write, so the file is now LF (was CRLF); git warns it will normalise.
- `frontend/src/features/dashboard/AccountOverview.test.jsx` — one adapted test (below).

## Test-first coverage
financeWorkGroups.test.js:
- drawn groups exact (keys, labels, hints, buttons, order)
- each of the 5 nextAccountAction keys → its group, through the REAL nextAccountAction on fixtures that produce that key
- 5 no-step states (idle procurement, final payment before delivery gate, close already confirmed, commission already recorded, cancelled) → no group
- unknown key / undefined → no group
- every actionable deal in exactly one group, none twice, none dropped, idle in none
- in-group order preserved; all three groups returned when empty
- CEO: deposit/final-payment deals in no group, overdue still chased
AccountFinancePage.test.jsx (new): subtitle; group order/labels/hints/counts; one group per deal, idle in none; ติดตามชำระ order; each row's ONE link: label + href, no other control; click → `/finance/deals/:id`; figures (รับครบ/ยอดรับจริง/คงค้าง); depositDue → "มัดจำ" and null fallback; chase step text + overdue/due chips; CEO wrong-way-round (no row/button for deposit/final deals, count 1); CEO in ทั้งหมด sees them without a step; empty group "ไม่มีรายการ"; all-empty line; ค้างรับทั้งหมด absent on groups, present in ทั้งหมด; no filter selects on groups; no "ดีล" in grouped, ทั้งหมด, filtered-empty (textContent + innerHTML incl. aria); filter deep link shows filtered table; Express card under flag on (renders, above groups) and flag off (absent, worklist renders).

Red run (before implementation): financeWorkGroups.test.js 16/16 failed on assertions against an empty stub module (first run failed on the missing import; a stub was added so the red is assertion-level). AccountFinancePage.test.jsx 19 failed / 19 passed — every failure an assertion / element-not-found on the missing behaviour (no region "ติดตามชำระ", no subtitle text, text still contains "ดีล", select still present, …). The passing ones were the existing behaviours kept (and two new ones that pin preserved behaviour: CEO in ทั้งหมด, Express flag off). The depositDue test was added later, red alone (1 failed: "Unable to find … มัดจำ ฿37,450.00"), then implemented.

Mutation checks (each reverted, diff back to final state):
- page calls `nextAccountAction(ticket)` without the role → exactly the two CEO tests go red.
- `confirmDeposit` removed from ติดตามชำระ → grouping unit tests + the grouped page tests go red.

Adapted existing tests (no assertion weakened):
1. sort test → `?view=all` (rows are no longer links in the grouped view; grouped order has its own test).
2. Tabs badge "3 ดีล/4 ดีล" → "3 งาน/4 งาน" (wording ruling).
3. subgrid columns → `?view=all`, now 5 items (header + 4 rows incl. idle).
4. compact due-date dash → `?view=all`.
5. one-link-per-row → `?view=all`, 4 links, plus idle row href asserted.
6. milestone chip row → `?view=all`.
7. paymentDueDate column → `?view=all`.
8. overdue row text → `?view=all`.
9. summary line → `?view=all`, "3 งาน".
10. em-dash summary → `?view=all`, "0 งาน".
11. milestone filter → `?view=all` (plus asserts idle milestone-3 row hidden).
12. action filter → `?view=all`.
13. load error: absent-copy assertion updated to the new wording (old string still asserted absent).
14. empty filtered: "ไม่มีงานในขั้นนี้" (was ดีล).
15. AccountOverview.test.jsx "ค้างรับทั้งหมด equals the figure AccountFinancePage prints" → renders `/finance?view=all` (block moved there); assertion unchanged.
Unchanged: defaults-to-todo, บันทึกใบกำกับ wording, ทั้งหมด tab, ?action=/?stage= deep link, URL state, ล้างตัวกรอง restore, skeleton.

Not covered: layout at phone widths (jsdom cannot measure; checked on screen, below); Express card rendering on-screen with the flag on (the mock flag has no UI switch; covered by the unit test); permission behaviour against the Java service (no authz change; mock-only on screen).

## Commands and results
- `npx vitest run src/features/finance/ src/features/dashboard/` → 24 files, 425 tests passed.
- `npm run lint` → exit 0, no problems.
- `npm test` (full) → 1 failed | 6574 passed (6575); 301/302 files. The failure is `src/features/quotations/QuotationEditorPage.v3.test.jsx > lead time required to submit … multiple rows missing a lead time are ALL named` ("TypeError: rows.map is not a function" then an assertion on a quotation message). Untouched area. Re-run alone: `npx vitest run src/features/quotations/QuotationEditorPage.v3.test.jsx` → 45/45 passed. Treated as a load flake (another agent was running frontend tests on the machine).
- `npm run build` → exit 0 (only the usual chunk-size warning).
- Maven not run (frontend-only; brief forbids it). No integration tests: no authz change.

## On-screen check (VITE_USE_MOCKS=true, port 5291, stopped afterwards — NOT permission evidence)
- Account, 1300px: three groups, counts 1/5/1, rows and buttons as drawn; deposit rows show "มัดจำ ฿…" + "ยืนยันรับมัดจำ".
- 320px and 375px: `scrollWidth == innerWidth` (320/375) on the grouped and ทั้งหมด views; all 7 row buttons 44px tall; no element past the viewport; no "ดีล" in main.
- Click ยืนยันพร้อมปิดงาน → `/finance/deals/24`, whose primary action is ยืนยันพร้อมปิดงาน.

## Drawn elements not built
- "Express" source chip on ยืนยันพร้อมปิดงาน rows: TicketSummaryDto carries no receipt source; would need an API change.
- Sales rep name under the project ("คุณวิภา"): no finance page shows a rep today and which summary field is "the rep" (createdByName vs assignedToName) is not settled; row shows customer + project instead.
- "เกินกำหนด 6 วัน" day count: would be new date arithmetic (and Bangkok-today); the existing "เกินกำหนดชำระ <date>" is shown instead.

## Judgment calls
1. confirmDeposit + confirmFinalPayment → ติดตามชำระ (see above).
2. Hint "ใช้คำนวณคำขอค่าคอม (เหมือนเดิม)" used verbatim as the brief says; "(เหมือนเดิม)" reads like a before/after annotation — owner may want it dropped.
3. Subtitle and ติดตามชำระ hint mention the Express file even when the server flag is off (as drawn).
4. Buttons are links (navigation), not in-row modals — see table.
5. Filtered deep links show the old filtered table in the todo tab rather than filtering groups.
6. depositDue shown as "มัดจำ" only for confirmDeposit rows; an overdue deposit deal is chaseOverdue and shows คงค้าง (drawing: overdue row shows คงค้าง).
7. Primary button style only for ยืนยันพร้อมปิดงาน (drawing "btn p").

## What a reviewer should attack first
1. The ติดตามชำระ membership of confirmDeposit/confirmFinalPayment (judgment 1).
2. That every button only navigates — confirm nobody expects an in-row confirm.
3. CEO seeing a "บันทึกใบกำกับ" button (pre-existing: the CEO saw the same label before; RECORD_INVOICE is server-offered only to account).
4. Deep-link behaviour of `?milestone=` in the todo tab (shows the table, not groups).
