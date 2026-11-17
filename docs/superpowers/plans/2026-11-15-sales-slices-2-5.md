# Sales flow redesign — Slices 2–5 Implementation Plan

> **For agentic workers:** implement task-by-task, test-first. Steps use `- [ ]`.

**Goal:** Finish the redesign on top of slice 1: visits (slice 2), expected payments + cash card + receipts upload
(slice 3), one-time pipeline import (slice 4), so the weekly email can stop.

**Architecture:** All new backend code lives in `th.co.glr.hr.salesboard` (visits, expected payments, receipt
import, pipeline import), reusing `sales.payment_receipt`, `TicketService` auto-advance and the slice-1 `MyWork` /
`TeamOverview` payloads, which gain new sections. Frontend adds sections to `MyWork.jsx` / `TeamOverview.jsx` and an
account-facing upload page.

**Spec:** `docs/superpowers/specs/2026-11-15-sales-flow-redesign-design.md`. **Builds on:** the slice-1 plan.

## Global Constraints

Everything in the slice-1 plan's Global Constraints, plus:
- Migrations continue from the last one slice 1 used (V209 → V210, V211, …), one per task that needs schema.
- New roles in gates are the existing role strings: `sales`, `sales_manager`, `ceo`, `account`.
- Money is `NUMERIC(14,2)` / `BigDecimal`, THB; weeks are ISO weeks starting Monday, Asia/Bangkok.
- No real customer data in git: fixtures use invented names; sample files under `backend/src/test/resources/` are
  synthetic.

## Decisions made while planning (documented assumptions)

1. **Visits get their own table** `sales.visit` (not a widened `deal_activity`): a visit can exist with no deal, has a
   plan/done lifecycle, and must not change the meaning of existing activity rows. Marking a visit DONE when it is
   linked to a deal also inserts a `deal_activity` row of kind `SITE_VISIT` (so the quiet clock resets through the
   one existing path).
2. **Expected payments are rep-entered** (kind, amount, expected week). There is no structured payment-terms field on
   quotations to seed from; the "add deposit" shortcut pre-fills `DepositPolicy`'s deposit % of the deal value when
   the deal has one.
3. **Receipts upload uses a documented column template** because no Express export sample exists. Header aliases
   are matched case/space-insensitively (`เลขที่เอกสาร|doc no|document no`, `วันที่|date`, `ลูกค้า|customer`,
   `จำนวนเงิน|amount`, `อ้างอิง|reference|ref`); `.xlsx` and `.csv` (UTF-8) both accepted. When a real export
   arrives, only the alias table changes. This is the external-dependency limitation, stated in the PR.
4. **Upload writes `sales.payment_receipt` rows** with `source='EXPRESS'` and dedupe on `(source, receipt_ref)`;
   manual entry stays for resolving unmatched rows (owner decision). Receipt `kind` = `DEPOSIT` when the deal has not
   yet reached DEPOSIT_RECEIVED and has a required deposit, else `BALANCE`. Existing `reconcilePaymentStatus` drives
   DEPOSIT_RECEIVED; CLOSED_PAID stays on the CEO close flow.
5. **Pipeline import uses one standard template**, not five per-rep parsers: the five reps' workbooks differ in
   sheet names and columns (verified on OIL / Muay samples), so the office copies rows into the template once. Dry
   run first, then commit; idempotent on `(owner, quotation no)` or `(owner, project title)`.

---

## Slice 2 — Visits

### Task 2.1: `sales.visit` table and API

**Files:** Create `salesboard/VisitController.java`, `VisitService.java`, `VisitRepository.java`, `VisitDtos.java`,
`db/migration/V210__sales_visit.sql`; Test `salesboard/VisitScopeIntegrationTest.java`, `VisitServiceTest.java`.

**Interfaces — Produces:**
- Table: `visit_id BIGSERIAL PK, owner_id BIGINT NOT NULL → hr.employee, ticket_id BIGINT NULL → sales.ticket,
  contact_name TEXT NOT NULL, planned_date DATE NOT NULL, status TEXT NOT NULL CHECK IN
  ('PLANNED','DONE','CANCELLED') DEFAULT 'PLANNED', done_at TIMESTAMPTZ, note TEXT, created_at, updated_at`;
  index `(owner_id, planned_date)`.
- `record VisitDto(long id, long ownerId, String ownerName, Long ticketId, String ticketTitle, String contactName,
  LocalDate plannedDate, String status, Instant doneAt, String note)`.
- `POST /api/sales/visits` `{ticketId?, contactName, plannedDate, note?}` → VisitDto (owner = caller; roles sales,
  sales_manager, ceo; a `ticketId` must be a deal the caller may write — reuse `TicketService` ownership check).
- `POST /api/sales/visits/{id}/done` `{note?}` and `/{id}/cancel` — owner only (sales_manager/ceo may also).
  Done on a linked deal inserts `deal_activity(kind='SITE_VISIT', activity_date=today, note)`.
- `GET /api/sales/visits?from=YYYY-MM-DD&to=YYYY-MM-DD` → own visits for sales; `GET /api/sales/visits/team?from&to`
  → all reps' visits, sales_manager/ceo only. Range max 31 days (400 beyond).

- [ ] Tests first: `repCannotSeeOrCompleteAnotherRepsVisit` (403 + zero rows), `salesRoleRefusedTeamVisits`,
  `accountRefusedEverything`, `doneOnLinkedDealResetsQuiet` (uses `QuietRule.isQuiet`), `doneTwiceIs409`,
  `cancelledVisitCannotBeDone`, `visitOnSomeoneElsesDealIs403`, `rangeOver31DaysIs400`. Red, implement, green,
  mutation-check the owner predicate. Commit `feat(sales): visits`.

### Task 2.2: Visits in My work and Team overview

**Files:** Modify `SalesBoardService` (MyWork gains `List<VisitDto> visitsThisWeek`; TeamOverview gains
`List<VisitDto> teamVisitsThisWeek` and per-rep `visitsDone`, `visitsPlanned`), `hrApi.js`/`mockApi.js`
(`salesBoard.visits.{list,team,create,done,cancel}` mirrored), `MyWork.jsx` (section `นัดเข้าพบสัปดาห์นี้`,
button `+ นัดเข้าพบ`, tick `เข้าพบแล้ว`, follow-up action `นัดเข้าพบ` now enabled), sticky mobile button
`บันทึกเข้าพบ` (logs a DONE visit for today in one step), `TeamOverview.jsx` (tile `เข้าพบสัปดาห์นี้ {done}/{planned}`,
week grid reps × จ–ศ with done / planned / cancelled markers that carry a text label, not colour only).
Tests: `MyWork.visits.test.jsx`, `TeamOverview.visits.test.jsx`, `mockApi.visits.test.js`, backend
`SalesBoardScopeIntegrationTest` extended. Commit `feat(sales): visits on my work and team overview`.

---

## Slice 3 — Expected payments, cash card, receipts upload

### Task 3.1: Expected payments

**Files:** Create `salesboard/ExpectedPayment*.java`, `V211__sales_expected_payment.sql`; tests
`ExpectedPaymentScopeIntegrationTest`.
- Table: `expected_payment_id, ticket_id NOT NULL, kind CHECK IN ('DEPOSIT','ON_DELIVERY','BALANCE'), amount
  NUMERIC(14,2) > 0, expected_week DATE NOT NULL CHECK (extract(isodow from expected_week)=1), status CHECK IN
  ('OPEN','RECEIVED','CANCELLED') DEFAULT 'OPEN', receipt_id NULL → sales.payment_receipt, created_by, timestamps`.
- `GET/POST /api/tickets/{id}/expected-payments`, `PUT/DELETE …/{epId}` — deal owner, sales_manager, ceo write;
  account read. Tests: other rep 403, account cannot write, Monday check, delete of RECEIVED is 409.

### Task 3.2: Cash card (rep) and cash KPI (team)

`SalesBoardService.cashForMonth(scope, YearMonth)` → `record CashMonth(BigDecimal forecast, BigDecimal received,
BigDecimal deliveredAwaiting, BigDecimal deliveriesScheduled, BigDecimal depositsExpected, List<CashWeek> weeks)`;
`record CashWeek(LocalDate weekStart, BigDecimal deliveredAwaiting, BigDecimal deliveriesScheduled, BigDecimal
depositsExpected, BigDecimal received)`.
- forecast = sum of OPEN+RECEIVED expected payments with `expected_week` in the month;
- received = sum of `payment_receipt.amount` with `received_at` in the month on the scope's deals;
- deliveredAwaiting = OPEN `ON_DELIVERY|BALANCE` on deals at DELIVERED; deliveriesScheduled = OPEN `ON_DELIVERY` on
  deals at DELIVERY_SCHEDULING or PROCUREMENT; depositsExpected = OPEN `DEPOSIT`.
- MyWork gains `cash`; TeamOverview gains `cash` and per-rep `expected`, `received`.
- UI: `เงินที่จะเข้า {เดือน}` card, `รับแล้ว ฿x จากเป้า ฿y`, stacked weekly bars with legend + direct labels, palette
  `#4f46e5, #0284c7, #14b8a6`, table fallback. Team KPI tile `เงินเข้าเดือนนี้`.
- Tests: SQL integration test with a fixture month covering each bucket and a receipt outside the month; scope test
  (rep sees only own); UI tests for numbers and the empty state `ยังไม่มีเงินที่คาดว่าจะเข้าเดือนนี้`.

### Task 3.3: Receipts upload + matching + unmatched queue

**Files:** `salesboard/receipts/ReceiptImport*.java`, `V212__payment_receipt_source.sql`
(`ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL' CHECK IN ('MANUAL','EXPRESS')`, `ADD COLUMN customer_name TEXT`,
partial unique index on `(receipt_ref) WHERE source='EXPRESS'`, table `sales.receipt_import_row` for unmatched rows:
`row_id, import_id, doc_no, doc_date, customer_name, amount, reference, status CHECK IN ('UNMATCHED','RESOLVED',
'IGNORED'), resolved_ticket_id, resolved_by`), and table `sales.receipt_import(import_id, file_name, uploaded_by,
uploaded_at, total_rows, matched, unmatched, duplicates)`.
- `POST /api/sales/receipt-imports` (multipart, `account`/`ceo`) → summary; `GET /api/sales/receipt-imports/unmatched`
  (sales: rows whose candidate deals are theirs + all rows with no candidate for sales_manager/ceo/account);
  `POST …/unmatched/{rowId}/resolve {ticketId}` and `/ignore`.
- Matching order (pure function `ReceiptMatcher.match(row, candidates)` unit-tested): (1) `reference` equals an
  invoice/IV number on a deal's billing; (2) `reference` equals a quotation number (`QN…`, case-insensitive, trailing
  `-n` revision allowed); (3) exactly one ACTIVE/WON deal whose customer name normalised (trim, collapse spaces, drop
  บริษัท/จำกัด/(มหาชน)/Co.,Ltd.) equals the row's and has an OPEN expected payment within ±1% or ±฿10 of the amount.
  Ambiguous → unmatched, never a guess.
- Matched row → insert `payment_receipt` (source EXPRESS, ref = doc no, recorded_by = uploader), mark the nearest
  OPEN expected payment RECEIVED, call the existing payment reconciliation so DEPOSIT_RECEIVED advances.
- Re-upload of the same file → all duplicates, zero writes. Tests: idempotency, each match rule, ambiguity,
  authz wrong-way (sales cannot upload; rep cannot resolve onto another rep's deal), deposit auto-advance, CLOSED_PAID
  NOT reached.
- UI: account page `/sales/receipts-import` (nav item for account/ceo under sales), upload + summary + template
  download link; rep/manager section `รายการรับเงินที่ยังจับคู่ไม่ได้` on My work / Team overview with one-tap
  `จับคู่กับดีลนี้` per candidate.

---

## Slice 4 — Pipeline import

### Task 4.1: Template import with dry run

**Files:** `salesboard/pipelineimport/*`, test resources `pipeline-import-sample.xlsx` (synthetic).
- Template columns (Thai header row): `พนักงานขาย (รหัส/อีเมล)`, `โครงการ`, `ผู้ออกแบบ`, `เจ้าของโครงการ`,
  `ผู้ซื้อ`, `เลขที่ใบเสนอราคา`, `มูลค่า`, `สถานะ (S1–S9)`, `โอกาสขาย %`, `ช่วงเวลา (เช่น 2026-Q4)`, `หมายเหตุ`.
- `POST /api/sales/pipeline-imports?dryRun=true|false` (multipart; sales_manager, ceo) → `record
  PipelineImportResult(int rows, int toCreate, int toUpdate, int skipped, List<RowIssue> issues)`.
- Rep code → stage: S1→PRESENTATION, S2→SPEC_APPROVED, S3→QUOTE_DESIGN_SIDE, S5→AWAITING_BUYER, S6→NEGOTIATION,
  S7→DEPOSIT_RECEIVED, S8→DELIVERY_SCHEDULING, S9→DELIVERED. Imported deals are created through the existing ticket
  creation path (entry channel `IMPORT`/existing equivalent), owner = resolved employee, with stage set directly and
  a `STAGE_CHANGED` event noted `นำเข้าจากไฟล์ pipeline`.
- Idempotent: an existing deal of that owner with the same quotation no (or same project title when no QN) is
  updated only in blank fields; never duplicated.
- Tests: dry run writes nothing; unknown rep → issue, row skipped; bad S-code → issue; second run creates nothing;
  sales role 403; account 403.
- UI: `/sales/pipeline-import` page (sales_manager/ceo), upload → dry-run table → `นำเข้า` button; template download.

---

## Review Focus

1. Same receipt uploaded twice, or present both as a manual and an Express row → counted once (3.3 idempotency +
   manual-with-same-ref test).
2. A receipt whose amount matches two deals of the same customer → unmatched, not guessed (3.3 ambiguity).
3. A visit or expected payment created on another rep's deal by ID guessing → 403 (2.1, 3.1).
4. Month boundary: a receipt at 23:30 Bangkok on the last day belongs to that month, not the next (3.2 test with
   `received_at` in UTC next day).
5. Re-running the pipeline import never duplicates a deal (4.1).

---

## Slice 5 — Import and account screens (spec §7.11)

### Task 5.1: Import landing worklist
Replace `ImportOverview.jsx` body with `ImportWorklist` grouped รอรับเรื่อง / รอราคาโรงงาน / กำลังขนส่ง / ถึงโกดังแล้ว,
built from the existing endpoints the overview already calls (no new backend unless a group cannot be derived — then
add a read-only `GET /api/import/worklist`, import/ceo only, with a scope integration test). Inline รับเรื่อง reuses
the existing claim call. Tests: grouping from fixtures, claim removes the row, empty states.

### Task 5.2: Pricing workspace — no duplicate currency/unit
In the import pricing workspace (`PricingRequestDetailPage` import branch), when the request line/factory already has
currency / price unit from sales, render a read-only chip `ตามที่ฝ่ายขายระบุ` and omit the select/toggle; otherwise keep
today's controls. Tests both branches; no backend change (verify the confirm-price payload still carries the values).

### Task 5.3: Single place to advance a shipment + undo
Hide the step-advance control on `/import/deals/:id` and the sales/CEO deal fulfilment tab (render tracker
read-only); keep it on `/fulfilment`. After an advance, show an undo snackbar for 30 s. Backend: if no reverse
transition exists, add `POST /api/import-requests/{id}/factories/{fid}/step/undo` allowed only for the actor who made
the advance, only within 30 s, only when the step has not changed since (409 otherwise); records an event. Same role
gate as advance. Real-DB integration tests: other user 403, after 30 s 409, after a later advance 409, happy path.

### Task 5.4: Small import dialog fixes
`FactoryContactDialog` date defaults to today (Asia/Bangkok); `FactoryEmailDraftModal` gains `คัดลอกทั้งหมด`
(clipboard of ถึง/หัวข้อ/เนื้อหา). Component tests.

### Task 5.5: Account landing + payment fallback
`AccountOverview` gains the Express upload card (from slice 3.3) with last-import summary + unmatched count above the
existing worklist. `FinanceActionModals` บันทึกรับชำระ: amount pre-filled with outstanding of the open notice/invoice,
kind pre-selected (มัดจำ when deposit outstanding, else ส่วนที่เหลือ), note collapsed, header line
`ใช้เมื่อรายการจาก Express จับคู่ไม่ได้`. Receipts list shows a source chip. Remove the unreachable sales-page
"บันทึกรับชำระเงิน" dialog from `TicketDetailPage` (and its tests), after confirming by test that account is always
redirected. Tests per change.
