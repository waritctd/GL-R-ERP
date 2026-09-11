# Quotation v2 — direct deal quotation (bypass the pricing chain) — PLAN

Owner rulings (Ploy, 2026-09-09): for THIS release the pricing-request → factory-quote → CEO-costing chain is
BYPASSED. Sales creates a deal, adds items straight onto a quotation, types unit price + discount, the
system does all arithmetic, ผึ้ง (sales_manager, employee 47) or ราม (ceo, employee 136) approves, the
approved PDF (with the approver's signature image) is emailed to the rep AND the creator. Editing an
approved quotation creates a new revision. Approver amount rule (<1M ผึ้ง, >1M CEO) is OUT OF SYSTEM —
never write amount logic. Test run Fri 2026-09-11 13:00; PRs go to `develop`.

Existing code stays untouched: `customerquotation/*` (PCR chain), `TicketService.generateQuotation`
(deprecated). The new feature is a sibling package `th.co.glr.hr.dealquotation` + frontend pages.

## Data — migration `V165__deal_quotation_direct.sql` (additive; develop's max is V164 from #899, so V165 is the next free number; PR #895 must renumber upward)

`sales.quotation` ADD COLUMN (all NULL unless stated):
- `origin VARCHAR(16)` — `'DEAL_DIRECT'` for every row this feature writes. Every read in the new
  repository filters `origin = 'DEAL_DIRECT'`; `CustomerQuotationRepository` already filters
  `pricing_request_id IS NOT NULL` so the two never see each other's rows. **Also** make
  `TicketRepository.findQuotationsByTicketId` (the legacy list rendered by `DealLegacyQuotations.jsx`)
  exclude `origin = 'DEAL_DIRECT'` so direct rows do not appear twice.
- `created_by BIGINT REFERENCES hr.employee` (ผู้พิมพ์ = actor who created the row)
- `sales_rep_id BIGINT REFERENCES hr.employee` (ผู้ตรวจ + "Sales : name T.phone"; = `ticket.created_by`)
- `submitted_at TIMESTAMPTZ`, `submitted_by BIGINT`
- `approved_at TIMESTAMPTZ`, `approved_by BIGINT REFERENCES hr.employee`
- `approval_decided_at TIMESTAMPTZ`, `approval_decided_by BIGINT`, `approval_note TEXT`
  (last decision; on REJECT status goes back to DRAFT and the note is the reason shown to the rep;
  cleared on the next submit)
- `dept_code VARCHAR(20)`, `unit_code VARCHAR(20)` (ฝ่าย / หน่วยงาน — sales types them, optional)
- `deposit_percent SMALLINT` (30/50/other; do NOT reuse `deposit_pct NUMERIC(5,4)` — it cannot hold 50)
- `remainder_mode VARCHAR(20)` (`CREDIT` | `ON_DELIVERY`), `credit_days SMALLINT`
- `validity_days SMALLINT` (15/30/45/60; `validity_date` = quotation date + N, written at approval)
- `updated_at TIMESTAMPTZ`
- widen `chk_quotation_doc_status` to add `'PENDING_APPROVAL'`, `'APPROVED'` (DROP + re-ADD).
NOT NULL columns to satisfy on insert: `ticket_id, number, issued_by (= sales_rep_id), issued_at (= now()),
currency ('THB'), doc_status, quotation_version, recipient_type ('UNSPECIFIED'), quotation_revision_no`.

`sales.quotation_item` ADD COLUMN:
- `location_label VARCHAR(255)`, `catalog_price_id BIGINT`, `product_code TEXT`
- `thickness_mm NUMERIC(6,2)`, `sqm_per_piece NUMERIC(10,6)`
- `quantity_mode VARCHAR(10)` (`AREA`|`PIECES`), `area_sqm NUMERIC(12,2)`, `pieces_input INTEGER`
- `wastage_mode VARCHAR(10)` (`PERCENT`|`PIECES`|`NONE`), `wastage_value NUMERIC(10,2)`
- `pieces_per_box SMALLINT`, `pieces_before_wastage INTEGER`, `pieces_after_wastage INTEGER`, `boxes INTEGER`
- `discount_pct NUMERIC(5,2)`, `origin_country VARCHAR(40)`, `lead_time_min_days SMALLINT`, `lead_time_max_days SMALLINT`
Existing columns reused: `qty` = pieces_final, `unit_price` = typed price, `sales_discount` = per-unit discount
AMOUNT derived from pct (NOT NULL), `final_unit_price` = net unit price, `amount`/`line_subtotal`, `vat`,
`line_total`, `brand/model/color/texture/size/raw_unit('แผ่น')/description/item_notes`.

`hr.employee_signature (employee_id BIGINT PK REFERENCES hr.employee, mime_type VARCHAR(40) NOT NULL,
image BYTEA NOT NULL, uploaded_by BIGINT, uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now())`.

## Arithmetic (backend-owned, pure class `WastageCalculator`, unit-tested; meeting rule = ROUND UP)
- `piecesPerSqm = round(1 / sqmPerPiece, 2, HALF_UP)`; `sqmPerPiece` from catalog, else W×H(mm)/1e6 parsed
  from the size text ("60x120" cm → 0.72), else sales types it.
- AREA mode: `piecesBefore = ceil(areaSqm × piecesPerSqm)`; PIECES mode: `piecesBefore = piecesInput`.
- PERCENT: `piecesAfter = ceil(piecesBefore × (1 + pct/100))`; PIECES: `piecesBefore + n`; NONE: `piecesBefore`.
- `piecesFinal = ppb > 0 ? ceil(piecesAfter / ppb) × ppb : piecesAfter`; `boxes = ppb > 0 ? piecesFinal / ppb : null`.
- `netUnitPrice = round(unitPrice × (1 − discountPct/100), 2)`; `lineAmount = round(piecesFinal × netUnitPrice, 2)`.
- `subtotal = Σ lineAmount`; `vat = round(subtotal × 0.07, 2)`; `grandTotal = subtotal + vat`.
Reference figures to pin (QN6900595-3): item3 87 ตร.ม., 2.78, 10%, 4/box → 242 → 267 → **268**;
item5 124 ตร.ม. → 345 → 380 → **380**; item1 569 ตร.ม., 1.39, 10%, 2/box → 791 → 871 → **872** (the
human-made reference printed 870 by rounding down twice — the meeting rule is round UP; this divergence
is recorded, not "fixed").

## Printed lines (renderer)
Per item, rows: [optional location heading row, underlined, no borders, only when the label changes from
the previous item] then
1. `กระเบื้อง รุ่น {model} สี {color} ผิว {texture}` (+ ` No.{productCode}` when present)
2. `ขนาด {size}x{thickness} cm. (ขนาดโดยประมาณ)` — size "60x120" + thickness 2 → `60x120x2 cm.`; 0.9 → `60x60x0.9 cm.`
3. `(พื้นที่ {area} ตร.ม.ๆละ {piecesPerSqm} แผ่น รวม {before} แผ่น + เผื่อ {10%|N แผ่น} และปัดลงกล่อง = {final} แผ่น) (บรรจุ {ppb} แผ่น/กล่อง)`
   (PIECES quantity mode: `(จำนวน {before} แผ่น + เผื่อ … = {final} แผ่น) (บรรจุ …)`; NONE wastage: omit the `+ เผื่อ` part;
   no ppb: omit `และปัดลงกล่อง` and the `(บรรจุ …)` tail)
Qty/unit/price/discount/net/amount go on row 1. Discount column prints `Net` when pct = 0 else `{pct}%`.
"Project : {projectName}" above the table (already exists). Remarks block (8 lines):
1. `1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่ {offerDate dd/mm/BE}`
2. `2.บริษัทฯ ขอรับมัดจำ {deposit}% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือ{ขอรับก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า | เครดิต N วัน}`
3. `3.กำหนดส่งมอบสินค้า : ` + groups of consecutive item numbers sharing (min,max): `รายการที่ 1-2 ระยะเวลานำเข้า 75-90 วัน  รายการที่ 3 ระยะเวลานำเข้า 30-45 วัน`
4–6, 8 fixed (template text as today). 7. `7.กำหนดยืนยันราคา {validityDays} วัน นับจากวันที่ในใบเสนอราคา`.
Header: B4 date = the date the rep CREATED the quotation, for every status (owner feedback F8,
2026-09-10 — it used to be the approved date, drafts today), I4 = number, H5 = `ฝ่าย {deptCode}` / unit code where the
template has them (inspect the template cells; if there is no หน่วยงาน cell, put `ฝ่าย P003  หน่วยงาน D002` on H5),
H6 = `Sales/{repName} T.{repPhone}`.
Signature block labels → `ผู้พิมพ์ / ผู้ตรวจ / ผู้อนุมัติ / ผู้สั่งซื้อ`; names in brackets under each:
`(creator)`, `(sales rep)`, `(approver)` — approver only once APPROVED; DRAFT/PENDING print blank.
Approver signature image (hr.employee_signature) anchored in the ผู้อนุมัติ box via HSSF drawing; if none,
plain-text name. Pagination: keep the 3 existing layouts but count ROWS not items; footer `หน้า &P/&N`.

## Status machine (QuotationStatus gains PENDING_APPROVAL, APPROVED)
DRAFT → (submit, sales/sales_manager) → PENDING_APPROVAL → (approve) → APPROVED | (reject+reason) → DRAFT
DRAFT → (cancel) → CANCELLED. APPROVED → (revise) → new DRAFT child (revision_no+1, number `{base}-{n}`,
everything copied); when the child is APPROVED the parent becomes SUPERSEDED (not before — the customer's
last approved document stays valid until replaced). Editing is DRAFT-only (WHERE clause enforced).

## Authz (STATED contract change; real-DB ITs with wrong-way-round cases + mutation check)
- create/edit/submit/cancel/revise: role `sales` (own deals only: `ticket.created_by = actor`) or
  `sales_manager` (any deal). ⚠ open question for Ploy: whether plain `sales` may act on another rep's deal.
- approve/reject: `sales_manager`, `ceo`. No self-exclusion (matches repo convention; flagged).
- view/list/download: `sales` (own), `sales_manager`, `ceo`, `import`, `account` read-only.
- signature upload: self, `ceo`, or `admin` capability. Everyone else 403. `qc`/`employee`/`warehouse` 403 everywhere.

## API (all under `/api`, JSON envelopes `{quotation: …}` / `{items: […]}` like CustomerQuotationController)
- `POST /tickets/{ticketId}/deal-quotations` body = UpsertDealQuotationRequest → 201 `{quotation}`
- `GET  /tickets/{ticketId}/deal-quotations` → `{items}` (all revisions, newest first)
- `GET  /deal-quotations?status=PENDING_APPROVAL|…&needsRework=true` → `{items}` (approver queue; sales sees own)
- `GET  /deal-quotations/counts` → `{all, pendingApproval, needsRework, cancelled, approved}` (same scope)
- `GET  /deal-quotations/{id}` → `{quotation}`
- `PUT  /deal-quotations/{id}` body = UpsertDealQuotationRequest (FULL replace of items) → `{quotation}`
- `POST /deal-quotations/calculate-line` body = one item input → `{item}` (stateless preview, same calc)
- `POST /deal-quotations/{id}/submit` `{}` ; `/approve` `{note?}` ; `/reject` `{reason}` (required) ;
  `/revisions` `{}` ; `/cancel` `{reason?}` → `{quotation}`
- `GET  /deal-quotations/{id}/file?format=pdf|xlsx`
- `PUT  /employees/{id}/signature` multipart `file` (png/jpeg ≤ 1 MB); `GET …/signature` (bytes);
  `DELETE …/signature`
UpsertDealQuotationRequest: `{ contactId?, deptCode, unitCode, offerDate, depositPercent, remainderMode, creditDays,
validityDays, customerNotes, items: [ItemInput] }`; ItemInput: `{ locationLabel, catalogPriceId, productCode,
brand, model, color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
leadTimeMaxDays, itemNotes }`.
DealQuotationDto: `{ id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById,
createdByName, salesRepId, salesRepName, salesRepPhone, submittedAt, approvedById, approvedByName,
approvedAt, approvalNote, quotationDate (= the CREATED date, Bangkok — same rule as the printed
B4 header, F8), customerName, customerAddress, customerTaxId, customerPhone,
contactId, contactName, contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent, remainderMode, creditDays,
validityDays, validityDate, customerNotes, subtotalAmount, vatAmount, grandTotal, currency,
approverHasSignature, items: [ItemDto], createdAt, updatedAt }`
ItemDto = ItemInput fields + `{ id, seq, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine }`.
Catalog: add `thicknessMm`, `pcsPerBox`, `sqmPerBox` to `ProductPriceDto` + its SELECT (additive).

## Notifications / email
- submit → in-app to roles `sales_manager` + `ceo`, link `/quotations/{id}`, kind `DEAL_QUOTATION_SUBMITTED`
  (add Thai titles to `TICKET_EVENT_TITLES`). Ticket event too.
- approve → in-app + EMAIL to rep and creator (dedupe by email) via `NotificationEmailService.sendWithAttachment`
  with the PDF; subject `ใบเสนอราคา {number} ได้รับอนุมัติแล้ว`; body includes link `{app-base-url}/quotations/{id}`.
- reject → in-app to rep + creator with the reason. Kinds: `DEAL_QUOTATION_APPROVED`, `DEAL_QUOTATION_REJECTED`.
- On approve also `tickets.addEventWithDocument(... QUOTATION_ISSUED ...)` and call
  `ticketService.advanceStageForCustomerQuotationIssue(ticketId, "UNSPECIFIED", actor)` only if it is a
  no-op for UNSPECIFIED (it is — returns null stage); so just the event.

## Frontend (branch `feat/quotation-generation-ui`, Tailwind-first, Thai-first, phone-usable)
Routes (inside `SALES_ENABLED`): `/quotations` (list: approvers see "รออนุมัติ" first; sales sees own),
`/quotations/new?ticket={id}` (editor), `/quotations/{id}` (editor when DRAFT + may edit; otherwise
read view with the actions the role has). Nav item "ใบเสนอราคา" in the งานขาย group. `PATH_GUARDS`
entries. Ticket detail `documents` tab: new `DealDirectQuotationPanel` listing this deal's direct
quotations (number, status, total, approver, download) + "สร้างใบเสนอราคา" link; the PCR-chain
`DealQuotationPanel` stays as is.
Editor: header (customer/project/rep auto from the deal; ฝ่าย/หน่วยงาน optional inputs), item rows with
catalog typeahead (`api.catalog.prices(q, undefined, 20)`) that autofills brand/model/color/texture/size/
thickness/sqmPerPiece/piecesPerBox and leaves everything editable; location label with a datalist of
labels already used on this quotation; quantity toggle ตร.ม.|แผ่น; wastage segmented 0/5/10/กำหนดเอง %
or +N แผ่น; แผ่น/กล่อง; ราคา/หน่วย; ส่วนลด %; ประเทศต้นทาง select (อิตาลี/สเปน/จีน/ไทย-สต็อก/อื่นๆ) with
default lead-time ranges (IT/ES 75–90, CN 60–75, TH 30–45, editable min/max); live calculation line
from `calculate-line` (debounced 300 ms). Terms card: วันที่รับจำนวน (default today), มัดจำ 30|50|อื่นๆ,
ส่วนที่เหลือ เครดิต N วัน | ชำระเมื่อส่งมอบ, ยืนราคา 15/30/45/60, หมายเหตุเพิ่มเติม. Totals card.
Actions by state/role: บันทึกร่าง, ส่งขออนุมัติ, ดาวน์โหลด PDF/Excel, อนุมัติ / ไม่อนุมัติ(เหตุผล),
สร้างฉบับแก้ไข, ยกเลิกร่าง. hrApi + routes + mockApi mirror (mock STUBS the calc — does not reimplement it)
+ contract tests + unit tests for the pages.

## Owner feedback pass 1 — backend (2026-09-10 evening, after the demo)

Owner's words in quotes; F1 has no backend part (the editor groups items by `locationLabel`
client-side and sends the same flat array). Migration `V167__deal_quotation_contact_snapshot.sql`
(develop's max was V166).

**F2 "change from ผู้ติดต่อ -> ผู้สั่งซื้อ make mandatory and use that name to auto fill in the
name for signature in the quotation pdf"** — `sales.quotation` gains a FROZEN snapshot
`contact_id` (soft reference, no FK — the snapshot must outlive the contact row), `contact_name`,
`contact_phone`, `contact_email`, written by `DealQuotationService#resolveContact` at create/update
and copied verbatim onto a revision; V167 backfills existing `DEAL_DIRECT` rows from their ticket's
contact. The repository's read-time `LEFT JOIN customers.contact` is GONE — `contactName` is now the
snapshot only, so an approved, emailed document keeps the name it was approved with.
`UpsertDealQuotationRequest.contactId` is optional: precedence is the request's id, else the draft's
own contact (update), else the ticket's contact; none → 400 `กรุณาระบุผู้สั่งซื้อ`, and so is a contact
that does not exist or belongs to another customer (same wording; nothing leaks). `submit` re-checks
the stored row (a pre-V167 row with no snapshot cannot reach an approver). DTO gains `contactId`,
`contactPhone`, `contactEmail`. Renderer: `Signatories.orderedBy` prints `(ชื่อ นามสกุล)` under
ผู้สั่งซื้อ (slot 4) instead of the dotted placeholder.

**F4 "also autofill in the dates"** — `Signatories` gains `printedOn` / `checkedOn` / `approvedOn`
(nullable `LocalDate`); the dates row prints `วันที่ d/M/BBBB` (unpadded, Buddhist year —
`วันที่ 10/9/2569`, `QuotationRenderer#signatureDateText`) under ผู้พิมพ์ = created, พนักงานขาย =
submitted, ผู้จัดการฝ่ายขาย = approved, each only once it exists; ผู้สั่งซื้อ's slot is always
dotted. The five-argument `Signatories` constructor is the pre-feedback shape (no name, no dates)
and is what the legacy/PCR wrappers still use — their output is unchanged.

**F3 "the attached photo is the ceo signature so when he อนุมัติ auto paste it in the pdf"** — no
mechanism change (slot 3 = the approver, whoever they are); pinned by two real-DB tests in
`DealQuotationIntegrationTest` (`approvedByCeo…` / `approvedBySalesManager…`): a real PNG uploaded
through `EmployeeSignatureService`, approve as each role, the rendered XLS carries a picture
anchored in the signature rows, the name row carries the approver, a draft on the same deal does
not carry the image, and the PDF has one XObject more than the template's own two. Uploading the
owner's real PNG for demo employee 5 / prod employee 136 is still an action item, not code.

**F6 (found verifying F3 on the demo, amended twice the same evening) — the signature was
detached from its rule, then drawn over the label words.** Originally anchored
`labelsRow-2..labelsRow` with `dy2 = 0` (bottom on the TOP edge of the labels row), left edge at
"slot centre minus half of a 30 mm guess". The first fix put the bottom on the rule and centred it
on the whole ผู้จัดการฝ่ายขาย quarter — which drew the ink across the words themselves. The owner's
final wording: *"still make the line visible but put the signature on top of the line in the middle
and not too high up."* `QuotationRenderer#placeSignaturePicture` now sets all four anchor corners
from the image's real scaled size and the measured geometry of the labels string's own
**underscore run** — the blank stretch AFTER the label text, computed in `writeSignatureBlock` from
the same AWT metrics that lay the string out, never from a hardcoded millimetre figure:

- centred on the RUN's centre, so it can never overlap the label;
- width capped at `SIGNATURE_RUN_WIDTH_FRACTION` (60%) of the run and 8 mm tall, aspect preserved,
  so the rule stays visible on both sides of the ink (nothing is ever painted behind the picture —
  the PNG's own alpha does the rest);
- bottom edge `SIGNATURE_STRADDLE_MM` (1.5 mm) BELOW the rule, so the strokes cross the line.

One correction was needed to make that land: **text is positioned by FONT advances, an anchor by
COLUMN widths, and LibreOffice does not size a column the way POI's `getColumnWidthInPixels` does**
— it scales the XLS 1/256-character unit by the default font's widest digit
(`LibreOfficeMetrics#columnTwips`), ~3% narrower on this template. Uncorrected, the picture landed
~5 mm left of the run it was computed from. `QuotationRenderer#fontPixelToAnchorPixelScale` converts
between the two spaces. Measured on a rendered PDF at 110 dpi: centre 136.6 mm against a run centred
at 137.6 mm (−1.0 mm), width 15.9 mm = 55% of the run, bottom 1.6–2.1 mm below the rule.
`QuotationRendererTest#…straddlesTheRuleCentredOnTheUnderscoreRun` pins all of it from the rendered
anchor for a wide, a tall (height-capped) and a 2000×400 source. Both PDF engines read the same
anchor (`SheetHtmlRenderer#anchorY` uses the same 1/256-row units), so the fidelity gate covers the
HTML side by construction.

**F7 "for the customer information you also have to have a field for เลขที่ผู้เสียภาษี and โทร."** —
the columns and the printed lines already existed; there was no way to CORRECT them. New
`PUT /api/customers/{id}` `{name?, taxId?, address?, branch?, phone?}`, PATCH semantics (a field the
body omits is left alone; a field it sends is written, blank included, so a wrong value can be
cleared — `name`/`branch` are NOT NULL, so a blank for those is a 400). **Stated authz change:**
gated by `DealEntryAccess.requireCanEnterDeal`, exactly the gate `POST /api/customers` uses and no
wider — sales / sales_manager / a live `canCreateQuotation` grant write; import, account, employee,
hr, warehouse and an ungranted qc get 403. Real-DB evidence in
`DealEntryAccessIntegrationTest` (denials written wrong-way-round: 403 AND the row re-read to prove
it did not move), plus decision-level coverage in `CustomerControllerTest`.

**Correcting a customer never rewrites an issued document.** `sales.quotation` freezes
`customer_name` / `customer_tax_id` / `customer_address` / `customer_phone` at create/update time
(`DealQuotationRepository`), and the renderer prints those frozen columns — so an edit through this
endpoint changes what the NEXT quotation captures, and leaves every already-issued ใบเสนอราคา
byte-identical. The corollary the UI must honour: the values used at save time are the ones on
screen, since the snapshot is taken then.

**F8 "for วันที่ at the top of the page it should be the date it was created by the sale"** — the
header date was the approved date once approved, else today; it is now `issued_at` (Bangkok) for
every status, in `DealQuotationRenderAdapter#toRenderModel` (printed B4) and
`DealQuotationRepository#mapQuotation` (`quotationDate` in the DTO) so the UI and the document can
never disagree. `offerDate` (remark 1, วันที่รับจำนวน) is a different, rep-editable date and is
untouched. Pinned in `DealQuotationIntegrationTest` on the only case that can tell the two rules
apart: a document created on one day (backdated in the DB) and approved on another.

**F5 "for สถานะ make it ทั้งหมด , รออนุมัติ, แก้, ยกเลิก"** — `GET /api/deal-quotations` gains
`needsRework=true`: DRAFT rows with a non-null `approval_note` (sent back) OR a non-null
`parent_quotation_id` (revision in progress), in SQL (`DealQuotationRepository.NEEDS_REWORK_PREDICATE`),
composed with `status` (AND), owner-scoped for `sales` exactly like the status filter. New
`GET /api/deal-quotations/counts` → `{all, pendingApproval, needsRework, cancelled, approved}` for
the caller's own scope in ONE statement (`COUNT(*) FILTER`), sharing the one scope decision
(`DealQuotationService#listOwnerScope`) and the one rework predicate with the list, so a tab's count
can never disagree with the rows it lists. `all` counts every status (SUPERSEDED included), matching
an unfiltered list. Real-DB tests are wrong-way-round first (rep B never sees rep A's rework rows or
counts; a plain DRAFT is not "rework"); the owner scope was mutation-checked (see the PR body).

## PDF renderer — two engines, one sheet (2026-09-10)
The direct-deal quotation's PDF can be printed by either of two engines, selected by
`app.quotation.pdf-renderer` (`APP_QUOTATION_PDF_RENDERER`, default `xls`) in
`QuotationRenderer#toPdf(QuotationRenderModel)`:

- **`xls`** — the POI-filled `quotation_template.xls` converted by LibreOffice
  (`LibreOfficePdfConverter`), the long-standing path. Excel export (`renderXlsx`) is this same
  workbook and is unchanged.
- **`chromium`** — the SAME POI workbook `toXls` just wrote is read back as one row plan
  (`th.co.glr.hr.common.sheet.SheetPlan`: rows, columns, merges, evaluated cell text, fonts,
  alignment, borders, pictures, print setup) and drawn by `SheetHtmlRenderer` as absolutely
  positioned cells + SVG border rules on exact A4 pages, then printed by headless Chromium
  (`ChromiumPdfPrinter`, Playwright). There is no second HTML layout of the quotation: whatever
  the XLS path emits — its v2 row arithmetic, cloned item rows, compacted remarks, relocated
  footer block, money-column sizing, no-split row breaks, signature rows, anchored approver
  image — is what prints, by construction. 503 "ระบบสร้าง PDF ไม่พร้อมใช้งาน" when no Chromium
  can be launched; never a silent fallback to the other engine.

`ChromiumPdfPrinter` owns ONE Playwright connection + browser on ONE dedicated daemon thread and
serialises every render through it — Playwright for Java is thread-confined (a 4-thread probe of
the first cut failed 4/4 with `Cannot find object to call __adopt__`), so `print()` submits to
that worker and waits (queue + possible relaunch + render, 2 min cap). The browser handle is
checked with `isConnected()` before every use and relaunched when the process has died; a
FAILED launch caches "unavailable" for 60 s only, then the next caller retries, so a transient
failure cannot turn every later quotation into a 503 until the pod restarts. One JVM shutdown
hook, registered at class-init. With a system Chromium (`CHROMIUM_PATH`, or the Dockerfile's
`/usr/bin/chromium`) it launches with `--no-sandbox --disable-dev-shm-usage --disable-gpu
--font-render-hinting=none` (non-root `USER 10001`, 64 MB `/dev/shm`); Playwright's own bundled
Chromium (local dev) gets Playwright's defaults. Pinned by `ChromiumPdfPrinterConcurrencyTest`
(six overlapping renders, each carrying its own number; a killed browser relaunches exactly
once; a failed launch is not retried inside the cooldown and IS retried after it).

`SheetHtmlRenderer` reproduces LibreOffice's page geometry, measured out of LibreOffice's own PDF
vectors (`LibreOfficeMetrics`): column widths from the default font's widest-digit advance in
twips — measured on the font the HOST resolves that family to through fontconfig (`FontResolver`:
Cordia New = 102 twips with the licensed font, Umpush = 156 on a tlwg-only host such as CI), and
the same resolved family is placed second in every cell's CSS font stack so Chromium substitutes
exactly what LibreOffice did; rules are SVG strokes, with LibreOffice's doubled outline strokes
(print-range right edge, repeated-title-block bottom, page-bottom closer) mirrored — integer fit zoom over the print range trimmed to its used rows, 1/100 mm truncation, unscaled
margins, horizontal centring, THIN = 0.75 pt × zoom centred on the grid line, fonts at
`floor(pt × zoom)`, repeat rows + manual row breaks for pagination, the in-page "หน้า X/Y"
footer. One LibreOffice quirk worth knowing: it decides fit-vs-scale from the WSBOOL bit POI
calls `autobreaks`, not from `setFitToPage`.

**Acceptance** is `HtmlXlsFidelityTest` (guarded on LibreOffice + Chromium): both engines render
the 5-item reference, the owner's real workbook (`src/test/resources/fixtures/
QN6900-CHN1A-HUB.xls`, fit-to-page copy) and 12-/30-item paginated references; both PDFs are
rasterised at 110 dpi, EVERY page, and every ruled line is measured — each LibreOffice rule must
have an HTML counterpart within 1.0 mm (position and both ends), no extra rules, strokes within
0.2 pt, equal page counts, every planned rule continuous (gap ≤ 0.3 mm), and — text — every
page's text in geometric order (PDFBox `sortByPosition`, NFC, all whitespace removed, because
PDFBox's word-gap heuristic splits a Thai glyph run differently in the two PDFs) must be
IDENTICAL. Side-by-side and diff PNGs, page rasters, the per-page text and the numeric reports
land in `-Dfidelity.outDir` (default `target/fidelity`). The engine is generic: the deposit
notice / invoice templates render through the same plan.

Single known text exception, declared and asserted-present rather than silently skipped
(`HtmlXlsFidelityTest.WORKBOOK_DATE_CELL`): the owner's real workbook's B4 is `=TODAY()` in
Excel's Thai-locale date format `d ดดดด bbbb` (Thai month name, Buddhist year). Neither engine
understands it — LibreOffice prints the codes literally ("5 ดดดด bbbb"), POI's `DataFormatter`
does not see a date and prints the serial ("46178.0"); Excel would print "5 มิถุนายน 2569". The
fixture pins the cell to its cached serial (the volatile formula is removed on the POI copy, or
both engines recompute it on load) so the gate does not move with the calendar. The product's
own XLS path writes the date as text, so this never reaches a real quotation. The fixtures'
quotation numbers are product-shaped (`QT-2026-0005`): a longer, fixture-only number spilt past
column I, where the two engines clip a glyph run at different points.

**CI** (`.github/workflows/backend-ci.yml`): the gate RUNS on every backend PR. The runner uses
the ubuntu-24.04 image's own Chromium (`/usr/bin/chromium`; Ubuntu's `chromium-browser` apt
package is a snap stub and there is no `chromium` .deb in Ubuntu's archive), installs
`fonts-thai-tlwg` so both engines share a Thai-capable fontconfig, exports `CHROMIUM_PATH` +
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` (never the 170 MB driver download), and sets
`REQUIRE_CHROMIUM=1`, under which `ChromiumTestGate` turns the tests' availability guard from
"skip" into "fail" — a missing browser (or LibreOffice) can never turn the gate green by absence.

**Remark box (owner, 2026-09-10)**: "หมายเหตุ :" + lines 1–8 are ONE closed box B..H inside the
form — each row a merged B..H cell (not B..I), a top rule B..H directly under the last item line,
the A|B and H|I separators as its edges, no interior separators, closed by the form's A..I rule
under line 8; ลำดับ and เป็นเงิน stay open columns beside it (`QuotationRenderer#openRemarkBox`).
The gate asserts the box from the plan and from the ink of both renders.

**Multi-page (owner, 2026-09-10)**: "หน้า X/Y" on every page of a multi-page document in both
engines (the XLS footer `&P/&N`; the HTML engine draws it in the bottom-margin band at
LibreOffice's position — measured as ink, centre/baseline within 1.5 mm, glyph height within
0.6 mm), nothing on a single page. Breaks fall only between whole item blocks (heading kept with
its item, an item's lines together), the whole tail (remark box + totals + ตกลงสั่งซื้อ +
signature + F-SM-002) moves as one block, the box closes A..I at every page bottom and re-opens
under the repeated rows 1–7. The page budget is LibreOffice's own (`LibreOfficeMetrics.
fitZoomPercent` + 1/100 mm row truncation, shared with `SheetPlan#paginate`), minus one item row
of headroom — a raw-point budget broke pages a third early.

**Local dev**: install Playwright's Chromium once —
`mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt && java -cp "$(cat /tmp/cp.txt)"
com.microsoft.playwright.CLI install chromium` (there is no `exec-maven-plugin` binding, so the
plain `mvn exec:java` form does nothing). macOS caches it under
`~/Library/Caches/ms-playwright`, Linux/Docker under `~/.cache/ms-playwright`.

**Docker**: `backend/Dockerfile` installs the `chromium` Debian package alongside
`libreoffice-calc` and sets `CHROMIUM_PATH=/usr/bin/chromium` +
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` so the runtime image never downloads its own copy, plus
`XDG_CONFIG_HOME`/`XDG_CACHE_HOME` under `/tmp` for the home-less non-root user. It reads the
same fontconfig setup (licensed fonts, or `fonts-thai-tlwg` substitutes) LibreOffice already
uses. **Size**: the Playwright dependency adds ~200 MB to `app.jar` — `driver-bundle` carries a
Node runtime for five platforms and Maven Central publishes no per-platform artifact (checked
2026-09-10: only `playwright` / `driver` / `driver-bundle` / `parent-pom`), so it cannot be
excluded and replaced; the Debian `chromium` package adds ~250 MB on top. Not build-verified on
this Mac (Docker registry pulls are blocked by broken host IPv6 — see the memory note) — **the
first deploy of the image needs a smoke render** (download one direct-deal quotation PDF with
`APP_QUOTATION_PDF_RENDERER=chromium` and read the logs) before the engine is switched on; the
default `xls` engine is unaffected either way.
