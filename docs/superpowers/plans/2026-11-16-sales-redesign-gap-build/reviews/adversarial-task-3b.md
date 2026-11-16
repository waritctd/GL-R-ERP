# Adversarial review — task 3b (four document dialogs), commit 2ab27ffd vs base b9b0f9bc

## Verdict: FIX BEFORE MERGE (small)

Nothing blocks. Every request is unchanged, and so is every opener and confirm gate. Two things should be fixed or explicitly accepted first:
- **F1.** A newly drawn statement in the IR confirm is false against Java.
- **F2.** The notice-page deposit confirm can show satang-wrong figures. This is pre-existing, but the summary line and the new subtotal row now present those figures as the document's content.

Worktree is left clean: `git status --short` is empty, at HEAD `2ab27ffd`.

---

## Findings (proven unless marked SUSPECTED)

### F1 — MEDIUM (new text). The IR warning "ออกเลข IR แล้วแก้ไขหรือลบไม่ได้" is false for the person reading it
- **Where:**
  - `frontend/src/features/tickets/DealFulfilmentPanel.jsx` ~line 711, the `ir-confirm-warning` callout.
  - The text was taken verbatim from drawing m-ir, line 1066.
- **Java:** `ImportRequestService.revise(long id, …)` (`ImportRequestService.java:903-913`).
  - An ISSUED IR can be corrected with a new version (ฉบับแก้ไข) that supersedes it.
  - The import role is notified with `IMPORT_REQUEST_REVISED` (`:700-709`).
  - The gate is `requireFullWrite`, the same gate as `createDrafts` (`:269`), so whoever can press this confirm can later revise.
  - Revision is refused only once the IR reaches `RECEIVED`.
  - What is true: the issued row cannot be edited in place or deleted, and there is no cancel.
- **State that shows it:** any deal where an IR is issued and not yet RECEIVED. The confirm says it cannot be corrected; the IR panel later offers ฉบับแก้ไข.
- **How established:** read the code.
- **Fix:** drop "แก้ไข", or keep the old "การดำเนินการนี้ย้อนกลับไม่ได้" alone. Over-warning is the safer direction, so this is not a blocker. But the brief requires every statement to be true, and the implementer checked the four statements they reworded but not this drawn one they kept.

### F2 — MEDIUM (pre-existing, now more prominent). Notice-page deposit confirm figures can differ from the issued document by 1 satang
- **Where:** `DepositNoticePage.jsx:524-530` computes all four figures with float math and `round2` (`quotationMeta.js:950`, `Math.round((n+EPSILON)*100)/100`):
  - `subtotal = round2(Σ lineNet·qty)`
  - `deposit = round2(subtotal·pct)`
  - `vat = round2(deposit·vatPercent)`
  - `total = round2(deposit+vat)`
- The server recomputes the same four figures in BigDecimal HALF_UP: `DepositNoticeRepository.update` `:218-225` (create `:161-167`).
- `EPSILON` only helps for values below about 2. Above that, a float product that should end in exactly .xx5 lands just below it and rounds down.
- **Concrete cases** (script `scratchpad/r2.mjs`, which compares JS `round2` with exact integer-cents HALF_UP):

  | Input | Screen | Issued |
  |---|---|---|
  | subtotal 1,000.15 at 30% | deposit 300.04 | deposit 300.05 |
  | subtotal 1,000.10 at 15% | deposit 150.01 | deposit 150.02 |
  | one line, 100.57 × 10.5 (sqm) | subtotal 1,055.98 | subtotal 1,055.99 |
  | one line, 101.55 × 10.5 | subtotal 1,066.27 | subtotal 1,066.28 |

  Each of these carries through to VAT and the total.
- **Before vs after:** deposit, VAT and total were already client-computed in the old confirm. The slice adds:
  - the subtotal row, which can also diverge (cases above);
  - the bold summary line "มัดจำ N% · X บาท รวม VAT · ถึง …", which presents the client total as the document's content.
- This is the same risk in the same form, presented with more authority.
- **Deal-page (เงิน tab) opener is safe:** it shows the stored draft's figures (`DealPaymentSection.jsx:471-473`), and `DepositNoticeService.issue` (`:217-`) issues the stored row under the `expectedUpdatedAt` guard without recomputing.
- **SUSPECTED, second mechanism:**
  - The client uses the served `doc.vatPercent` (`:528`).
  - `DepositNoticeRepository.update` hardcodes `vatPct = 0.07` and does not write `vat_percent` (`:213`, SET list `:230-242`).
  - So a row whose stored `vat_percent ≠ 0.07` shows n% and n%-VAT in the confirm but issues 7% VAT under an n% label.
  - Real rows probably all hold 0.07. I did not query production.
- **Fix options:**
  - (a) Integer-cents HALF_UP in the page's four figures. This is display math only, and the server stays the truth. Owner call, because it touches money display.
  - (b) Accept it, and state in the PR body that the notice-page confirm is an estimate.

### F3 — LOW. Three drawn/new elements have no failing test (survived mutations)
- **V8:** the S9 note reworded to the unconditional "ส่งครบทุกรายการแล้ว งานจะย้ายไป…" survived. The test only checks the note's presence or substrings, so the conditional wording (the implementer's own justification) is unpinned.
- **V11:** input `max={remaining}` changed to `max={ordered}` survived. This was also unpinned in base, but the input was rebuilt.
- **W3:** the เหลือส่ง column without `Math.max(0, …)` survived. No fixture has `qtyDelivered > qty`, so over-delivery display is unpinned. The server refuses over-delivery (`TicketService.java:1785-1788`), so only legacy or corrupt rows reach this.
- **V13:** dropping the delivery subtitle survived. Cosmetic.

### F4 — LOW, SUSPECTED. Phone: the irreversibility warning moved from the first line to the bottom of the body
- `Modal.jsx:60` makes the body `overflow-auto` with the footer fixed below it.
- **Base:** "หลังจากนี้จะไม่สามารถแก้ไขได้" was in the first paragraph. The IR "ย้อนกลับไม่ได้" ended the one-line message.
- **Now:** both are the last element, after the summary, the table and the hint.
- **Estimate** (Safari 375×~553 visible, so `100dvh` ≈ 553):
  - The body area is about 360px.
  - The deposit body with a 3-line summary is about 410px, so the warning sits below the fold while ออกเอกสาร is visible in the footer.
  - The IR confirm with 3 or more factories is the same.
- I did not measure this in a browser. The implementer measured only `scrollWidth` and tap targets, not warning visibility.
- **Fix:** put the warn callout directly under the summary, or accept.

### F5 — LOW. Delivery table at 320px
- The table is 18rem (288px) min-width inside a body about 240px wide at 320px.
- The ส่งครั้งนี้ input (the only control) starts off-screen and needs horizontal scrolling inside the table. The implementer admits this.
- 375px fits.

### F6 — LOW. Wording precision, not false
- **`SOURCE_HINT` "ทุกบรรทัดเติมจากใบเสนอราคาและใบแจ้งยอดเงินรับมัดจำแล้ว"** (`RemainingInvoiceDialog.jsx:19`):
  - In the matched-notice case, lines come only from the notice snapshot (`DepositNoticeService.java:728-736`).
  - In the bypass case they come only from the quotation (`:744`).
  - In the legacy case they may come from `ticket_item.approved_price` (`:775-781`).
  - So "and" overstates. It is still far truer than the drawn "การส่งของ".
- **Deposit hint "ยอดเริ่มต้นเติมจากใบเสนอราคาของงานนี้":** legacy ticket-route drafts are not quotation-sourced. This is an edge case.
- **S9 note in a fully-paid deal:** the stage passes through DELIVERED and then CLOSED_PAID (`maybeAdvanceClosedPaid`, `:3792`). Both have rep code S9 (`DealStage.java:237`), so "S9" is true, but "ส่งสินค้าครบแล้ว" is not the final stage name.

---

## Attack results

### 1. Requests are unchanged — CONFIRMED
- The diff touches no handler. These are byte-identical to base:
  - `handleRecordDelivery` (`DealFulfilmentPanel.jsx:645-654`)
  - `buildCreatePayload` / `createDrafts`
  - remaining `handleSave` / `handleIssue` / `handleCreateDraft`
  - deposit `issueMutation`
  - the notice page `buildPayload`
- No `useQuery` or `api.*` call was added, so no extra fetch happens on open. The remaining issue button still calls `depositReferenceOptionsQuery.refetch()` before the confirm, as before.
- Payload tests were mutation-checked field by field. All killed: D13, R11, R12, R13, R14, I5, V9, V10, V12.

### 2. Figures
| Dialog | Figure | Source field | Server meaning | Can it disagree with the issued doc? |
|---|---|---|---|---|
| Deposit (deal page) | summary %, total, customer | `doc.depositPercent`, `totalPayable`, `customerName` | stored draft | No (issued as stored, under the stamp guard) |
| Deposit (deal page) | มูลค่าสินค้า / มัดจำ / VAT | `doc.subtotal`, `depositAmount`, `vatPercent` + `vatAmount` | stored | No |
| Deposit (notice page) | all four figures + summary | client `round2` float math | recomputed by `update` in BigDecimal | **Yes, ±0.01 (F2)**; and a vat% label if the stored rate is not 7% (suspected) |
| Remaining (create) | net / itemsTotal / deposit | `options.netAmount` / `itemsTotal` / `depositAmount` | `net = round2(items − deposit)`; deposit = the matched notice's billed pre-VAT `depositAmount` | No (the issue confirm re-reads the preview). The three always add up: all are 2dp, and `formatMoney` is 2dp. |
| Remaining (draft) | same | the stored draft row | issue re-snapshots (`RemainingInvoiceService.java:249-266`) | Yes, if the notice was re-issued since the draft (stale stored figures, same as the old ยอด box); the issue confirm shows the fresh preview |
| Remaining (issued) | same | the issued row | frozen | No |
| IR | N โรงงาน, names | coverage `uncoveredFactoryNames` ∪ picks | `createDrafts` creates one IR per uncovered factory; stock lines are excluded in coverage too (`ImportRequestQueryRepository:127`) | Same as base (old "N ใบ"); the picks are filtered against live IRs |
| Delivery | สั่ง / ส่งแล้ว / เหลือส่ง / count | `qty`, `qtyDelivered`, `max(0, q−d)` | the same fields; `fullyDelivered` = all `qtyDelivered ≥ qty` | No. Decimals are shown up to 3 places. |

- **Remaining-invoice paths checked:**
  - Deposit not received: the deduction is the billed notice amount. The label "มัดจำ" is correct; the drawn "มัดจำที่ได้รับ" would have been false.
  - Several notices: only the matched one counts.
  - Bypass or no notice: deposit is 0, so the line shows "มัดจำ ฿0.00".
  - Deposit larger than value: blocked, so no summary is shown (R9 killed).
- **"ทันที":** true.
  - `createDrafts` issues inside the same transaction (`:351`).
  - `notifyByRoleAtLink("import")` inserts synchronously (`NotificationRepository:236`).
  - It silently reaches nobody only if no import-role employee exists.

### 3. Reworded statements
| Statement | Verdict |
|---|---|
| "มัดจำ" instead of "มัดจำที่ได้รับ" | Rewording correct; the drawn text was false |
| "(ก่อน VAT)" | Correct |
| Footer source note | Mostly correct; the drawn text was false; see F6 |
| Deposit hint without "เงินที่คาดว่าจะเข้า" | Correct; the percent comes from the quotation |
| "มูลค่าสินค้า · อ้างอิง" instead of "ตามใบเสนอราคา QT" | Correct and defensible |
| S9 note made conditional, "แจ้งฝ่ายบัญชีให้ดำเนินการต่อ" | True (`:1808-1835`); the drawn "ให้ออกใบแจ้งหนี้ส่วนที่เหลือ" was too specific |
| IR warning, kept verbatim from the drawing | **False (F1)** |

### 4. Who can open and confirm
- No opener, `disabled`, `busy`, `confirmDisabled` or gate expression changed.
- Diff hunks were read in full: only bodies, labels and the delivery subtitle changed.
- Mutation I4 (confirm enabled while coverage is loading) was killed.
- The permission aspect is otherwise unverified against Java, but nothing permission-shaped changed.

### 5. Irreversibility warnings
- **Deposit:** the warning is kept verbatim.
- **IR:** "ย้อนกลับไม่ได้" is kept, with the false addition (F1).
- **Remaining:** the nested issue confirm is unchanged.
- **Delivery:** had no warning before and has none now.
- Prominence on phones is worse (F4, suspected).

### 6. Shared component
- `DocumentDialogParts.jsx` is imported only by the three slice files.
- It does not wrap or alter `ConfirmDialog` or `Modal`, so focus trap, Escape and ✕ are untouched.
- Cancel and Escape send nothing: D13 was killed, and the existing cancel tests pass.

### 7. Tests
- The removed assertions were inspected:
  - the VAT label, now 'VAT 10%' and still asserting the served rate;
  - five IR tests, which now assert the exact summary and the ordered names;
  - two TicketDetailPage button names;
  - IR loading "no sentence", now "no summary and no table".
- None was weakened.
- Mutations: 46 applied, 42 killed, 4 survived (V8, V11, V13, W3 — see F3).
- The targeted directories pass: 64 files, 2048 tests.

### 8. Fidelity to the drawing — gaps verified against the DTOs
- `RemainingInvoiceDraftRequest(quotationId, reference, depositReference, docDate, notes)` has no due date or terms.
- `ImportRequestCoverageResponse(boolean, List<String>)` has no per-factory counts or lead times.
- `RecordDeliveryRequest(source, note, lines, recipientName)` has no date and no round.
- `ConfirmDialog` has no subtitle prop.
- All admitted gaps hold. The remaining-invoice line table and the delivery ที่มา column were omitted per the brief.

### 9. Wording
- No new "ดีล" in dialog labels.
- "งาน" is not applied to any document name, code or printed text.

### 10. Phone
See F4 and F5. Inputs and selects are 44px.

## Mutation list
- **Killed:** D1–D13, R1–R15, I1–I6, V1, V4, V5, V6, V7, V9, V10, V12, W2.
- **Survived:** V8, V11, V13, W3.
- **Not applied:** V2 and V3 (ambiguous match), re-run as W2 and W3.
- Runner: `scratchpad/mut3b.mjs`. Each mutation is restored after it runs, and the final `git status` is clean.
