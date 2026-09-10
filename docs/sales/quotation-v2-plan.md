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
Header: B4 date = approved date (drafts: today), I4 = number, H5 = `ฝ่าย {deptCode}` / unit code where the
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
- `GET  /deal-quotations?status=PENDING_APPROVAL|…` → `{items}` (approver queue; sales sees own)
- `GET  /deal-quotations/{id}` → `{quotation}`
- `PUT  /deal-quotations/{id}` body = UpsertDealQuotationRequest (FULL replace of items) → `{quotation}`
- `POST /deal-quotations/calculate-line` body = one item input → `{item}` (stateless preview, same calc)
- `POST /deal-quotations/{id}/submit` `{}` ; `/approve` `{note?}` ; `/reject` `{reason}` (required) ;
  `/revisions` `{}` ; `/cancel` `{reason?}` → `{quotation}`
- `GET  /deal-quotations/{id}/file?format=pdf|xlsx`
- `PUT  /employees/{id}/signature` multipart `file` (png/jpeg ≤ 1 MB); `GET …/signature` (bytes);
  `DELETE …/signature`
UpsertDealQuotationRequest: `{ deptCode, unitCode, offerDate, depositPercent, remainderMode, creditDays,
validityDays, customerNotes, items: [ItemInput] }`; ItemInput: `{ locationLabel, catalogPriceId, productCode,
brand, model, color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
leadTimeMaxDays, itemNotes }`.
DealQuotationDto: `{ id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById,
createdByName, salesRepId, salesRepName, salesRepPhone, submittedAt, approvedById, approvedByName,
approvedAt, approvalNote, quotationDate, customerName, customerAddress, customerTaxId, customerPhone,
contactName, projectName, deptCode, unitCode, offerDate, depositPercent, remainderMode, creditDays,
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
