# Task 3b report — four document dialogs (slice C2)

**Status: DONE_WITH_CONCERNS.** All four dialogs show the drawn body. Every request is unchanged. Some drawn elements were not built because the server does not send the data, or because the drawn wording is false against the Java code. Those are listed below.

- Worktree: `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\deal-doc-dialogs`
- Branch: `feat/deal-document-dialogs`, cut from `origin/feat/deal-page-redesign` at b9b0f9bc.
- **Nothing is committed.** My rules forbid committing, staging or pushing, so the work is left uncommitted in the worktree.
- No backend, schema, permission or API-contract change. `hrApi.js` and `mockApi.js` are untouched. I did not touch any file from the sibling agent's list, any file under `finance/`, `quotations/` or `salesBoard/`, or `TicketDetailPage.jsx`. Maven was not run.

## Files changed
| File | Change |
|---|---|
| `frontend/src/components/common/DocumentDialogParts.jsx` (new) | The drawn parts the dialogs share: `DialogSummary` (the drawing's `.sum` line), `DialogCallout` (warn/info note), and `DialogTable` (a compact table inside its own horizontal scroller), plus th/td class strings. Display only, no logic. |
| `frontend/src/features/deposits/DepositNoticePage.jsx` | `DepositIssueSummary` rebuilt to drawing m-deposit-issue. It takes two new display props, `subtotal` and `reference`. The page passes its existing `subtotal` and `form.reference`. |
| `frontend/src/features/tickets/DealPaymentSection.jsx` | Passes `doc.subtotal` and `doc.reference` to `DepositIssueSummary`. This is a one-line change. |
| `frontend/src/features/tickets/RemainingInvoiceDialog.jsx` | Summary line added to the create, draft and issued states. Drawn footer added to the create and draft states: the source note, then บันทึกร่าง, then ออกใบแจ้งหนี้. "สร้างร่าง" and "บันทึก" now read "บันทึกร่าง". "ดีลนี้" became "งานนี้" in the waiting state. |
| `frontend/src/features/tickets/DealFulfilmentPanel.jsx` | IR confirm body rebuilt (summary, โรงงาน table, warning). Its confirm label "ออกใบขอซื้อ (IR)" became "ออกใบขอซื้อ". Delivery modal rebuilt (summary, 5-column table, info note) with the submit button renamed "บันทึกการส่ง" and the project name as subtitle. |
| Tests: `DealPaymentSection.test.jsx`, `DepositNoticePage.test.jsx`, `RemainingInvoiceDialog.test.jsx`, `DealFulfilmentPanel.test.jsx`, `TicketDetailPage.test.jsx` | New describe blocks, plus the targeted adaptations listed below. |

## Per dialog: what is shown and where each figure comes from

### 1. ยืนยันการออกเอกสาร — ใบแจ้งมัดจำ (m-deposit-issue)
There are two openers. Both render the same `DepositIssueSummary`.

| Drawn element | Deal page เงิน tab (`DealPaymentSection`) | Notice page (`DepositNoticePage`) |
|---|---|---|
| Summary "มัดจำ N% · X บาท รวม VAT · ถึง Y" | `doc.depositPercent`, `doc.totalPayable`, `doc.customerName` | `form.depositPercent`, `total`, `form.customerName` |
| มูลค่าสินค้า · อ้างอิง R | `doc.subtotal`, `doc.reference` | `subtotal`, `form.reference` |
| มัดจำ N% (ก่อน VAT) | `doc.depositAmount` | `deposit` |
| VAT n% | `doc.vatPercent`, `doc.vatAmount` | `vatPercent`, `vat` |
| ยอดที่ลูกค้าต้องชำระ | `doc.totalPayable` | `total` |

- The notice page's figures are the same `subtotal`, `deposit`, `vat` and `total` its สรุปยอด panel already computed and its old confirm already showed. I added no new calculation.
- Every figure uses the file's existing `money()` formatter. The percentage uses the existing `Math.round(p*100)`.
- The irreversibility warning is kept, word for word: "หลังจากนี้จะไม่สามารถแก้ไขได้".
- Hint: "ยอดเริ่มต้นเติมจากใบเสนอราคาของงานนี้ แก้รายการได้จนกว่าจะออกเอกสาร".

### 2. ใบแจ้งหนี้ส่วนที่เหลือ (m-remaining)
- Summary: "ส่วนที่เหลือ ฿net (ก่อน VAT) = มูลค่า ฿itemsTotal − มัดจำ ฿deposit". Each state takes all three figures from one served row:
  - create: `options.netAmount`, `options.itemsTotal`, `options.depositAmount`
  - draft and issued: the row's own `netAmount`, `itemsTotal`, `depositDeduction`
- The three figures agree by construction. `DepositNoticeService.java:444` sets `netAmount = itemsTotal.subtract(depositAmount).setScale(2, HALF_UP)`. The stored row stores that same result (`RemainingInvoiceService.java:157,176`).
- With a null deduction the summary shows the remaining amount without the equation. With a `blockingReason` it shows no summary at all.
- The figures use the existing `formatMoney`.
- The nested issue confirm is unchanged and still shows the fresh server preview.

### 3. ยืนยันการออกใบขอซื้อ (IR) (m-ir)
- Summary: "N โรงงาน · ส่งถึงฝ่ายนำเข้าทันที".
- Table: one โรงงาน column, one row per name in `confirmNames`. That is the server's coverage `uncoveredFactoryNames` plus the rep's own factory picks, the same list the old one-line confirm used.
- "ส่งถึงฝ่ายนำเข้าทันที" is what the backend does:
  - `ImportRequestService.java:351`: createDrafts issues each new row.
  - `ImportRequestService.java:693,707`: `notifyImportOfIssuedIr` → `notifyByRoleAtLink("import", …)`.
- Warning: "ออกเลข IR แล้วแก้ไขหรือลบไม่ได้ — การดำเนินการนี้ย้อนกลับไม่ได้".
- The loading and all-covered texts are unchanged. When the backend sends no names, the summary keeps the old generic sentence.

### 4. บันทึกการส่งสินค้า (m-delivery)
- Summary: "เหลือส่ง N รายการ". N counts the lines whose `qty − qtyDelivered > 0`.
- Table columns: รายการ / สั่ง (`item.qty`) / ส่งแล้ว (`item.qtyDelivered`) / เหลือส่ง (`max(0, qty − qtyDelivered)`, as the old modal derived it) / ส่งครั้งนี้ (the same input, aria-label and prefill as before).
- แหล่งสินค้า and หมายเหตุ are kept.
- Info note: "ถ้าบันทึกครั้งนี้ทำให้ส่งครบทุกรายการ งานจะย้ายไป S9 ส่งสินค้าครบแล้ว เอง และระบบจะแจ้งฝ่ายบัญชีให้ดำเนินการต่อ". `TicketService.recordDeliveryInternal` confirms it:
  - `TicketService.java:1808-1809`: `fullyDelivered = items.allMatch(qtyDelivered >= qty)`
  - `:1815`: `updateFulfillmentStatus(FULLY_DELIVERED)`
  - `:1820`: `autoAdvanceStage(updatedSummary, DealStage.DELIVERED, actor)`
  - `:1828`: `notifications.notifyByRoleAtLink("account", DELIVERY_COMPLETED, …)`
  - `DealStage.java:237`: `DELIVERED → "S9"`
- `autoAdvanceStage` (`:2950-2955`) does nothing if the deal is not ACTIVE or is already at or past DELIVERED. That is why the note is worded conditionally.

## Payload-identity evidence
I wrote these tests first and ran them against the unchanged code, where they passed (257 tests passed in the red run, these included). They stay green after the rebuild, and each asserts the exact `mock.calls`:
- **Deposit, deal page:** `issue.mock.calls == [[31, {expectedUpdatedAt:'stamp-7'}]]`. Preview is never called.
- **Deposit, notice page:** `update.mock.calls == [[9002, {all 9 body fields + items[...] exact}]]`, then `issue == [[9002,{expectedUpdatedAt:'T2'}]]`, with update called before issue.
- **Remaining, บันทึกร่าง:** `update == [[900,{reference:'PO-77', depositReference, docDate, notes:[2]}]]`, and issue is not called.
- **Remaining, issue:** `update == [[900,{4 fields}]]`, then `issue == [[900]]`. The existing exact `createDraft` assertion is unchanged.
- **IR:** `createDrafts == [[1, undefined]]`. With a factory picked, `[[1, {itemFactories:[{ticketItemId:3,factoryId:7}]}]]`.
- **Delivery:**
  - Untouched: `[[1,{source:'WAREHOUSE',note:null,lines:[{21,64},{23,75.5}]}]]`.
  - Edited: `[[1,{source:'STOCK',note:'ส่งรอบแรก',lines:[{21,10}]}]]`. This shows the note is trimmed and a zero line is dropped.
  - A FROM_STOCK deal defaults to `STOCK`.
- **Mutation check:** I broke the delivery note trim and the deposit `expectedUpdatedAt`. Exactly the payload tests went red (3 tests, one of them the pre-existing deposit test), and nothing else failed. Both mutations were reverted, and the diff on those lines is empty.

## Opener and confirm conditions, before and after (identical)
I did not edit any opener, gate or disabled condition.

| Dialog | Opener visible when | Opener disabled when | Confirm disabled/busy when |
|---|---|---|---|
| Deposit, deal page | `dep==='draft' && isOwnerSales` | `issueMutation.isPending` | busy `issueMutation.isPending` |
| Deposit, notice page | `doc && !isIssued`. Opens only after `validateIssueForm` passes | `saving` | busy `saving` |
| Remaining | Deal page: `rem==='create' && canManageRemainingInvoice && remainingReady`, or `invoice && canManageRemainingInvoice`. Inside the dialog, writes are gated by `canWrite` (draft editor only `draft && canWrite`) | — | Save/issue/delete `busy`. Create `!canCreate`. Issue confirm `previewLoading \|\| previewBlocking` |
| IR | `canFullWriteStoredIr && !allFactoriesCovered && !allLinesFromStock`. Also the imperative `createIrDrafts()` | `isPending \|\| !allLinesPicked \|\| !depositReady \|\| sourcePending` | `coverageLoading \|\| allFactoriesCovered`, busy `isPending` |
| Delivery | `hasAction('RECORD_PARTIAL_DELIVERY')` | `recordDeliveryMutation.isPending` | Submit `isPending`. A toast when no line is > 0 |

## Test-first coverage list
Red run, before any implementation: 15 failed and 257 passed across the four files. Every failure was the drawn element being absent ("Unable to find [data-testid=…summary/warning/footer/info]"), or the waiting state containing "ดีล". None was a compile or import error.

- **DealPaymentSection.test.jsx, "ใบแจ้งมัดจำ issue confirm (drawing m-deposit-issue)":**
  - Payload identity.
  - ยกเลิก, Escape and ✕ each send nothing.
  - The summary and rows come from distinct served fields.
  - The warning is present and there is no ดีล.
  - With no reference, no อ้างอิง is shown.
  - The wrong-way-round opener matrix (otherSales, salesManager and ceo get no ออกเอกสาร) is the existing `%s as %s` test, unchanged.
- **DepositNoticePage.test.jsx, "issue confirm (drawing m-deposit-issue)":**
  - Payload identity with save-then-issue ordering.
  - Cancel sends nothing.
  - The summary and rows use the page's figures, plus the warning, with no ดีล.
- **RemainingInvoiceDialog.test.jsx, "drawing m-remaining":**
  - Save and issue payload identity.
  - ปิด sends nothing.
  - The summary line in the create, draft and issued states.
  - No summary when blocked. No equation when the deduction is null.
  - The draft and create footers.
  - No วันครบกำหนด or เงื่อนไข field.
  - No ดีล.
  - The canWrite wrong-way-round tests are existing and unchanged.
- **DealFulfilmentPanel.test.jsx, "ยืนยันการออกใบขอซื้อ (IR) (drawing m-ir)":**
  - Two payload-identity tests.
  - The summary and table.
  - The warning, the button label and no ดีล.
  - Wrong way round: import and sales_manager have no opener.
- **DealFulfilmentPanel.test.jsx, "บันทึกการส่งสินค้า (drawing m-delivery)":**
  - Untouched, edited and FROM_STOCK payloads.
  - All lines zero gives a toast and sends nothing.
  - Cancel and ✕ send nothing.
  - A double click while pending sends once.
  - Wrong way round: without RECORD_PARTIAL_DELIVERY neither the owner nor the CEO gets the opener.
  - The table cells are exact for 3 lines with distinct ordered, delivered and remaining values.
  - The info note, the kept fields, no วันที่ส่ง, no ดีล, and the button label.
- **Not covered:**
  - Visual layout is checked only by eye, and only under mocks.
  - Permission behaviour is not verified against Java. Nothing permission-related changed, and the mocks are not evidence for it.

**Adapted existing tests (labels and copy of the old bodies only; no assertion weakened):**
- `DepositNoticePage.test.jsx` VAT test: in the dialog, `'ภาษีมูลค่าเพิ่ม 10%'` became `'VAT 10%'`. The rate and amount assertions are unchanged.
- `DealFulfilmentPanel.test.jsx`, W3-11 block, five tests: the confirm button name is now ออกใบขอซื้อ (`IR_CONFIRM_LABEL`). The sentence "จะออกใบขอซื้อ (IR) N ใบ: A, B" is replaced by `irConfirmFactories()`, which asserts the exact summary (count) and the exact ordered names. The loading-state "no sentence yet" check became "no summary and no table yet".
- `TicketDetailPage.test.jsx`: the delivery button `'บันทึก'` is now `'บันทึกการส่ง'` (twice), and the IR confirm `'ออกใบขอซื้อ (IR)'` is now `'ออกใบขอซื้อ'`.

## Commands and results
- Red run, the four files, unchanged code: 15 failed and 257 passed. Every failure was for the right reason.
- After implementation, the targeted five files: 564 passed.
- `npm run lint`: exit 0.
- `npm test`: 305 files, 6710 tests, all passed. No flake.
- `npm run build`: exit 0. The only warning is the existing chunk-size warning.
- On-screen check under `VITE_USE_MOCKS=true`:
  - Port 5261. The proxy was pointed at a dead port, so the mock login's fire-and-forget POST could not reach any backend. The server has been stopped and the port is free.
  - Deposit confirm (ticket 18), remaining invoice (ticket 13), delivery (ticket 13) and IR confirm (ticket 12), at 375px. Delivery and IR were also checked at 320px.
  - The page never scrolls horizontally: `scrollWidth` equals the viewport width.
  - Buttons and inputs measure 44px.
  - The first look found that the delivery table pushed the ส่งครั้งนี้ input off-screen at 375px. I fixed it by letting the name column wrap and using a 18rem min-width. At 375 it now fits. At 320 it scrolls inside its own wrapper.
  - This is not permission evidence.

## Drawn elements not built, and why
- **m-remaining "วันครบกำหนด" and "เงื่อนไข" fields:** the request does not accept them. `RemainingInvoiceDraftRequest.java` is `record RemainingInvoiceDraftRequest(Long quotationId, String reference, String depositReference, LocalDate docDate, List<String> notes)`. There is no due-date or terms field; `docDate` is the existing วันที่ (H7).
- **m-remaining footer wording "ทุกบรรทัดเติมจากใบเสนอราคาและการส่งของแล้ว":** false. Lines come from the matched deposit notice's item snapshot, or from the quotation when there is no notice, never from deliveries (`DepositNoticeService.java:728-736,744`). I wrote "ทุกบรรทัดเติมจากใบเสนอราคาและใบแจ้งยอดเงินรับมัดจำแล้ว" instead.
- **m-remaining "มัดจำที่ได้รับ" label:** the deduction is the notice's billed `depositAmount` before VAT (`:736`), not an amount received. It reads "มัดจำ" instead. "(ก่อน VAT)" was added because the net is before VAT, while the drawing equated it with the invoice total.
- **m-remaining line-item table:** the brief did not ask for it, and it is not built. The existing ยอด box stays.
- **m-ir รายการ count and ระยะเวลานำเข้า columns:** the coverage endpoint returns only `ImportRequestCoverageResponse(boolean hasUncoveredFactories, List<String> uncoveredFactoryNames)` (`ImportRequestDtos.java:89`). The line-to-factory cascade (`ImportRequestQueryRepository.factoryResolutionCandidates`) and the lead times (`ImportRequestService.java:301-316`, from the quotation or the country default) are resolved inside `createDrafts`. A client count from `item.factory` could disagree with what is issued. A muted line says these appear on each IR after issue.
- **m-delivery "งวดที่ N · นัดส่ง …":** no delivery round and no appointment date reach the panel.
- **m-delivery "วันที่ส่ง":** `RecordDeliveryRequest(String source, String note, List<Line> lines, String recipientName)` has no date.
- **m-delivery ที่มา column:** the brief's column list omits it. The panel's tracker table already shows it.
- **m-deposit-issue "มูลค่าตามใบเสนอราคา QT-…":** it reads "มูลค่าสินค้า · อ้างอิง {reference}" instead. The reference field also takes PO numbers, and draft lines are editable after drafting, so "ตามใบเสนอราคา" could name the wrong document or figure.
- **m-deposit-issue hint "…และ “เงินที่คาดว่าจะเข้า”":** false. The percent comes from the quotation (`DepositNoticeService.java:148-150`). I dropped that clause.
- **Subtitles on the two ConfirmDialogs (deposit, IR):** `ConfirmDialog` has no subtitle prop, and I did not change a shared component the sibling agent may also use. The delivery Modal does show the project name.

## Judgment calls
- I added the shared `DocumentDialogParts.jsx` instead of four copies of the same markup.
- "เหลือส่ง N รายการ" is a count of lines whose already-derived remaining is > 0. It is new on screen, but it is not new money or stock math.
- The remaining-invoice summary in the draft state uses the stored draft figures, as the old body's preview box did. The issue confirm still re-fetches the server preview, which is the only figure shown at the moment of commit.
- Confirm button "ออกใบขอซื้อ (IR)" became "ออกใบขอซื้อ", and the delivery submit "บันทึก" became "บันทึกการส่ง", to match the drawing. The panel's create button keeps "ออกใบขอซื้อ (IR)".
- The breadcrumb "ดีล" on DepositNoticePage is page chrome, not dialog chrome. An existing test pins it, so I left it.

## What a reviewer should attack first
1. **The deposit notice page confirm still shows client-computed figures.** `subtotal`, `deposit`, `vat` and `total` are rounded per DepositNoticeRepository. The page saves the form and then the server recomputes. This is pre-existing and unchanged, but the summary line makes those figures more prominent.
2. **The S9 info note.** It promises a stage move and an account notification. Check that against `TicketService.java:1808-1835`.
3. **Whether the deposit row label "มูลค่าสินค้า · อ้างอิง …" and the reworded hint and footer are acceptable departures from the owner-approved drawing.**
