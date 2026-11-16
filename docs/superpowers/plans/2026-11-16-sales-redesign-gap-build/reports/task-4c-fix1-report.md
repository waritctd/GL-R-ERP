# Task 4c — fix round 1 (finance worklist)

Worktree `.claude/worktrees/finance-worklist`, branch `feat/finance-grouped-worklist`, on top of 8899d84b. Uncommitted.

## Action × role encoded (`frontend/src/features/finance/financeActionPerformers.js`)
| nextAccountAction key | server action | performer roles | Java |
|---|---|---|---|
| confirmDeposit | DEPOSIT_PAID | account | `TicketService.java:82` DEPOSIT_CONFIRM_ROLES; offered `:3476` |
| confirmFinalPayment | FINAL_PAYMENT | account | `TicketService.java:91` PAYMENT_RECORD_ROLES; offered `:3515` |
| confirmCloseReady | CONFIRM_CLOSE | account | `TicketService.java:99` CLOSE_CONFIRM_ROLES; `canConfirmClose` `:3742` |
| recordInvoiceCommission | RECORD_INVOICE | account | `FinanceDealService.java:220-224` (role == "account" only) |
| chaseOverdue | none (follow-up, no write) | nobody | — |
CEO is on none. A row's button carries the group verb only when `canPerformFinanceStep(key, role)`; otherwise "เปิด" (secondary) and the action label shows as plain text in the row. Read from source, not run against the service.

## Per ruling
1. F1: done as above. CEO test that pinned the verbs replaced by a wrong-way-round test (every CEO button "เปิด", aria-label without a verb, step as non-link text); helper tested for 5 keys × 11 roles.
2. F6/F2: every row shows only `คงค้าง ${amountOutstanding}`, the old table's column (base 5b07639b `DealRow`, header "คงค้าง"). Removed รับครบ / ยอดรับจริง / มัดจำ. Fixtures now have payable ≠ paid ≠ outstanding ≠ depositDue. Drawn figures left out: "รับครบ ฿596,000" (close), "มัดจำ ฿145,800" (deposit), "ยอดรับจริง ฿388,500" (invoice).
3. F5: legacy document_issued / paymentStatus null / no receipts deal → row shows "คงค้าง —" and no ครบ claim (test). Still standing: the drawn group hint "ส่งของครบและรับเงินครบแล้ว" is a group-level claim that such a legacy deal also falls under; kept as drawn, owner call.
4. F3: fallback group `other` "อื่น ๆ ที่ต้องดำเนินการ", last, only when non-empty, button "เปิด", step as text. Badge = sum of group counts (tested both with and without the fallback). Exhaustiveness: accountActions.js exports no key list, so the test scans `nextAccountAction`'s body for `key: '…'` AND pins the five keys.
5. F4: order = the old table's default sort, applied before grouping: overdue (urgent) first, then paymentDueDate ascending with none last, then rowAmount descending (outstanding if > 0, else payable). Pinned for ยืนยันพร้อมปิดงาน and บันทึกใบกำกับ (two rows each) plus the existing ติดตามชำระ test.
6. F7: chip track `minmax(max-content,1fr)`. Measured on-screen (mock, 5291, stopped): at 1041px the OLD track overlapped 3 rows (chip 172px), the new one 0; 0 at 1300/375/320, no horizontal scroll, buttons 44px on phone. F8: the CLOSED_PAID test asserts the 203 row's own link.

## Tests
New: `financeActionPerformers.test.js`, `AccountFinancePage.otherGroup.test.jsx` (mocks nextAccountAction for one id). Edited (targeted): `financeWorkGroups.test.js` (unknown key → other; exhaustiveness; fallback tests), `AccountFinancePage.test.jsx` (fixtures, figures, legacy, CEO, account verb, badge sum, order, chip track, F8).
Red run before fixes: 15 failed / 57 passed, all assertion failures (helper stub returned true). Passing-at-red: exhaustiveness, account-verb, badge sum, order, F8 — these pin behaviour that was already correct; the mutations below show each can fail.

## Mutations (13, all KILLED, files restored byte-identical by sha1)
M4b unknown→chase; M8a close=payable; M8b invoice=payable; M9b reverse close/invoice; R1a helper lets ceo; R1b page ignores helper; R1c non-performer loses status text; R2 rows show amountPaid; R3 close rows "รับครบ"; R4 unknown dropped; R5 reverse every group; R6a chip track reverted; R6b account invoice button "เปิด".

## Commands
- Targeted: `npx vitest run src/features/finance/ src/features/dashboard/AccountOverview.test.jsx` → 15 files, 248/248.
- Once at the end: `npm run lint` exit 0; `npm test` 304 files, 6592/6592 passed (no flake this run); `npm run build` exit 0.
- Mutation script: scratchpad `mutate-4c.mjs`, results in scratchpad `mutations-4c-fix1.txt`.
- No Maven, no backend change, no authz change (the helper only chooses a label; the server still gates every action).
