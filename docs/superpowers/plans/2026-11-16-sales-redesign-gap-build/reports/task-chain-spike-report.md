# Spike report: document chain on the ใบเสนอราคา page (THROWAWAY, not for merge)

Worktree `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\chain-spike`, branch `spike/quotation-document-chain`,
cut from `origin/develop` at 30f86f6b. Nothing is committed, staged or pushed. Frontend only, mock mode only.
The Vite server (port 5281) has been stopped.

## Files changed
- `frontend/src/features/quotations/QuotationDocumentChain.jsx` (new): `useQuotationChain` (reads the deal facts) and
  `QuotationDocumentChain` (the card), plus `DepositNoticeIssueDialog` (the notice issue dialog on this page).
- `frontend/src/features/quotations/quotationChainMarks.js` (new): the list's per-row chain mark (`useQuotationChainMarks`).
- `frontend/src/features/quotations/QuotationEditorPage.jsx`: renders the card under the deal strip; hides the
  header's ยืนยันคำสั่งซื้อ / ไปที่ดีล slot while the card shows; after a confirm made here the rep stays on the page
  (it used to navigate to the deal); only the exact bare `ticketDetail` cache key is dropped.
- `frontend/src/features/quotations/QuotationListPage.jsx`: rows carry `chainMark`; the status cell (desktop) and the
  card's status corner (phone) stack the mark under the status badge.

## Screenshots
All in `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\chain-spike\out\`.
Each name exists for desktop (1440x900, dsf 1) and phone (375x812, dsf 2, mobile, touch).
The BEFORE set was taken before any file changed, from the untouched worktree at 30f86f6b.
On desktop the top bar is cropped off because it prints the signed-in user's @glr.co.th e-mail. The phone top bar
shows no e-mail, so phone shots are uncropped.

| State | Before | After | Mock data used |
|---|---|---|---|
| A: approved, not confirmed | before-A-sales-* | after-A-sales-* | QT-2026-0005-1 / DL-2026-0026 (30%, all stock lines) |
| B: confirmed, no notice | before-B-sales-* | after-B-sales-* | same; confirmed by clicking (header button before, card button after) |
| C: notice | before-C-sales-* (draft on /tickets/26/deposit) + before-C0-* (that page before สร้างเอกสารฉบับร่าง) + before-Cdeal-* (the deal page the rep would come from) | after-C-sales-* (dialog on the quotation page; a bottom sheet on the phone) | same |
| D: notice issued | before-D-sales-* | after-D-sales-* | same; issued with ออกเอกสาร (on the notice page before, in the dialog after) |
| E: remaining invoice available | before-F-sales-* (same page) | after-F-sales-* step 4 | QT-2026-0006-1 / DL-2027 (see below) |
| F: 0% deposit | before-F-sales-* | after-F-sales-* | QT-2026-0006-1 / DL-2026-0027 (0%, ON_DELIVERY, all stock) |
| G: read-only, state D | before-G-manager-*, before-G-account-* | after-G-manager-*, after-G-account-* | QT-2026-0009-1 / DL-2026-0030 (confirmed, notice issued) |
| G: read-only, state B (manager) | before-GB-manager-* | after-GB-manager-* | QT-2026-0008-1 / DL-2026-0029 (confirmed, no notice) |
| H: list | before-H-sales-*, before-H-manager-* | after-H-sales-*, after-H-manager-* | all of the above + QT-2026-0007-1 (not confirmed) |

How the data was set up: on the login page, the scripts called the same in-page mock API module the app uses
(`import('/src/api/mockApi.js')`). As ฝ่ายขาย they created 5 deals and quotations, then submitted them. As
ผู้จัดการฝ่ายขาย they approved them. QT-0006, 0008 and 0009 were then confirmed through the API, and the QT-0009 notice
was created and issued the same way. The QT-0005 chain was then driven through the UI in both runs. The customers are
the mock's fictitious ones, with 02-555-xxxx numbers. No contact person was set, so no phone number or e-mail prints.
I opened every PNG: Thai renders, and there is no toast, spinner, bank account or e-mail in frame.
The scripts are in the same `chain-spike` folder: `lib.cjs`, `before.cjs`, `after.cjs`, `probe-e.cjs`,
`probe-account.cjs` and `check-phone.cjs`.

## States not reached / caveats
- **E "remaining invoice issued": not reachable honestly** (proved by `probe-e.cjs`).
  - In the mock, a direct-quotation deal's payable is the deposit notice total. Recording that deposit as ฝ่ายบัญชี
    therefore makes the deal FULLY_PAID, so step 4 would honestly say "ไม่ต้องออก".
  - The mock builds remaining-invoice lines from the ticket items' approvedPrice, which a direct-quotation deal does
    not have. The draft gets 0 lines, and issuing it is refused with "ยังไม่มีรายการสินค้า".
- **E "available" was reached honestly** on the 0% deal: all stock lines, so the deal went to FROM_STOCK on confirm.
  It is shown in after-F, where step 4 is the one primary button.
  - The card's button does open the existing RemainingInvoiceDialog on the quotation page (checked by the script).
  - That dialog was not kept as a screenshot: in the mock it shows ฿0.00 lines, and its notes print the company bank
    account and e-mail.
- **before-C:** on this deal the deal page offers no button to create the notice (pre-existing issue 1 below), so the
  notice page was opened by its URL.

## Data the server would need to provide (not on the quotation page today)
1. **Was the order confirmed from this quotation, and when?** The spike reads the ticket's
   DEAL_PROMOTED_FROM_QUOTATION event plus `orderConfirmedQuotationId` from `GET /deal-quotations/ticket/{id}`.
   ฝ่ายบัญชี cannot read ticket events (403), so it gets no confirm date.
2. **Can the deal still take the order?** This needs deal status `draft` + ACTIVE, read today from the ticket
   summary, which ฝ่ายบัญชี cannot read. The spike falls back to `finance.getDeal` for that role.
3. **Deposit facts:** depositPolicy and paymentStatus (bypassed, notice issued, paid). Today these come from the
   ticket summary or the finance deal.
4. **Deposit notice:** id, status, docNumber, date and figures (`listByTicket`). The date field is inconsistent: the
   mock sends `issueDate`, while DealPaymentSection reads `docDate`.
5. **Remaining invoice:** id, status, docNumber, date (`listForTicket`), and readiness (fulfillmentStatus).
6. **Deal owner/keyer** for the act gate. The quotation carries only `salesRepId`, not the keyer.
7. **The list:** one chain-stage field per row. The spike makes 4 reads per approved direct quotation's deal.

Suggestion: add a `documentChain` block to the quotation detail DTO and a `chainStage` field to list rows.

## Decisions the brief did not settle (what I chose)
**Placement and header**
- The card sits after the deal strip ("ดีล DL-… · เปิดดีล") and before the document.
- The card shows only for an APPROVED direct quotation whose deal is still draft, or whose order was confirmed from
  this quotation. Otherwise the old header behaviour stays.
- While the card shows, the header's ยืนยันคำสั่งซื้อ and ไปที่ดีล are removed. The strip's เปิดดีล remains.
- The header ดาวน์โหลด menu (PDF/Excel) is kept.
- After confirming here, the rep stays on the page instead of being sent to the deal.

**Deposit notice flow**
- Step 3's button makes the draft first (the deal page's own path), then opens the dialog.
- If a draft already exists, the button reads "ออกใบแจ้งยอดเงินรับมัดจำ".
- Cancelling the dialog keeps the draft.
- The dialog is the shared Modal (a bottom sheet on the phone):
  - title "ออกใบแจ้งยอดเงินรับมัดจำ", subtitle "ฉบับร่างจากใบเสนอราคา …";
  - body: DepositIssueSummary, unchanged;
  - footer: แก้รายละเอียด (text link with a pencil icon) on the left, ยกเลิก and ออกเอกสาร on the right.
- Step 3 counts as done once the notice is issued, not once it is paid. A paid notice adds "· รับเงินแล้ว".

**Markers and lines**
- Markers match DealStepper: green check, primary ring, outlined number.
- A skipped step (0%, or "ไม่ต้องออก") gets a dashed circle with "–".
- Each finished step's rail is green.
- The "current" ring sits on the next step even when it waits on someone else.
- The "ขั้นถัดไป: …" line shows only when the viewer has no button. Example: "ขั้นถัดไป: ฝ่ายบัญชียืนยันรับมัดจำที่หน้าการเงิน".

**Phone**
- A finished step is one line: its name, the number, and a 44px icon-only download.
- The current step's button is full width.
- At 320px the one-line rows wrap rather than truncate the number.

**List marks**
- Tones: warning for "รอยืนยันคำสั่งซื้อ" and "…รอใบแจ้งมัดจำ", info for "ออกใบแจ้งมัดจำแล้ว", success for
  "ออกใบแจ้งหนี้แล้ว".
- I added a fifth mark, **"ยืนยันแล้ว · ไม่รับมัดจำ"** (neutral), for 0% quotations.
- No mark where the deal was confirmed from another quotation, or where the deal is past draft with no confirmation
  (the seeded QT-0003/0004).

## Felt wrong once rendered
- **Phone height:** the card adds about 265–305 css px. The document start is only just in frame at 375x812, because
  the phone header is already tall.
- **Two download controls** for the quotation: the header ดาวน์โหลด and step 1's PDF button.
- **State D for the rep:** the "current" ring on step 4 reads as "your turn", but it is waiting on ฝ่ายบัญชี. A
  separate "waiting" marker would be clearer.
- **Step 4 at state A** shows "ออกได้หลังฝ่ายบัญชียืนยันรับมัดจำ". It is true, but it is a distant reason.
- **List rows** with two stacked badges are about 12px taller.
- **Notice date:** the mock stamps the issue date in UTC, so it shows 16 พ.ย. next to a 17 พ.ย. approval.
- **Dialog close button at 320px:** the shared Modal's ✕ shrinks to 37px wide because of the long title (the shared
  component has no shrink-0).

## Pre-existing issues found (not fixed)
1. **No create-notice button on the deal page.** A direct-quotation BUYER_DIRECT deal confirmed from the quotation keeps
   its party "bidding". The deal page's main button is then "บันทึกผลประมูล", while the เงิน tab says to press
   "สร้างใบแจ้งยอดเงินรับมัดจำ ด้านบน", and no such button exists. Seen in mock mode only; the real backend is unverified.
2. **Mock money for direct-quotation deals:** payable = the notice total, and the remaining invoice has no lines. Before
   the notice, the deal page shows มูลค่างาน ฿0.00 "ไม่ต้องชำระ".
3. **Notice date never shows on the deal page in mock mode:** DealPaymentSection reads `docDate`, but the mock notice
   only has `issueDate`.

## Verification
**Done**
- eslint on the 4 changed files: clean.
- Every screenshot taken by headless Playwright and opened by me.
- `check-phone.cjs` at 320 and 375 px: no horizontal scroll on the page or the content scroller, in states A–D and on
  the list. Every visible card control is at least 44px. The only exception is the shared Modal ✕ at 320px, noted
  above.

**Not run**
- vitest, `npm run build`, and anything against the real backend.

**Not verified:** who-can-act is reused from existing front-end predicates, and all evidence here is mock-only, so the
permission side is unverified against the Java services.
