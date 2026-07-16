# Agent Handoff

## Task
Phase-2 Branch 2 (user-approved, legal compliance): **freeze issued quotations**. Before this
branch, `TicketService.getQuotationXlsx/getQuotationPdf` re-rendered from LIVE ticket data on
every download — a quotation re-downloaded after a later revision would show its original
number/date with today's edited items and prices. Fixed with an **item-data snapshot at issue
time**, following the codebase's own precedent (`sales.deposit_notice_item`, V12 "Snapshot of
items at issue time"). No rendered bytes and no filesystem storage — only structured data is
snapshotted, and the existing `QuotationRenderer` is unchanged; it is simply fed
snapshot-derived inputs instead of live ones when a snapshot exists.

## Branch
`feat/quotation-freeze` (branched from `origin/main` at `3ae79ad`, tip = PR #219
"give sales_manager read+comment oversight of the sales stack")

## Base Commit
`3ae79ad`

## Current Commit
(set after commit — see `git log -1`)

## Agent / Model Used
Claude Sonnet 5 (implementation agent in the Sonnet-implements/Opus-reviews loop)

## Scope

### In Scope
- `backend/src/main/resources/db/migration/V49__quotation_snapshot.sql` — new migration
- `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` — snapshot read/write methods
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — `generateQuotation` (write the
  snapshot) and `loadQuotationContext` (read snapshot-first, legacy fallback)
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java` — new/updated tests
- `backend/src/test/java/th/co/glr/hr/ticket/TicketRepositoryIntegrationTest.java` — new tests
- `frontend/src/api/mockApi.js` — mirror the snapshot at generate time + snapshot-first downloads

### Out of Scope
- `QuotationRenderer.java` / `QuotationRendererTest.java` — untouched; the renderer's inputs
  (`TicketDto`, `QuotationDto`, `CustomerDto`) are unchanged shapes, only their *values* are
  swapped for frozen ones by the service layer before the renderer is called.
- `TicketController.java` — untouched; the download endpoints already stream opaque bytes, so
  no controller/route/contract change was needed (frontend has zero changes, as designed).
- `frontend/src/features/tickets/*` — no UI changes; quotation downloads are opaque
  `Blob`/byte-stream contracts on both hrApi and mockApi.
- Deposit notice / commission / pricing — untouched.
- Any business-math change to quotation totals, VAT, or pricing — untouched. This branch only
  changes *when* the rendered numbers are captured (issue time vs. download time), never how
  they're calculated.

## Files Changed
- `backend/src/main/resources/db/migration/V49__quotation_snapshot.sql` (new) —
  - `CREATE TABLE sales.quotation_item` (quotation_item_id PK, quotation_id FK, seq,
    brand/model/color/texture/size, qty, qty_sqm, unit_basis, raw_unit, unit_price NOT NULL,
    amount NOT NULL) + `idx_quotation_item_quotation` index.
  - `ALTER TABLE sales.quotation ADD COLUMN customer_name, customer_address, customer_tax_id,
    customer_phone, project_name` (all nullable — legacy rows stay NULL).
  - A defensive dedupe (window-function renumber, no deletes) for any pre-existing duplicate
    `(ticket_id, quotation_version)` rows, followed by
    `CREATE UNIQUE INDEX ux_quotation_ticket_version ON sales.quotation(ticket_id, quotation_version)`
    — see "Migration Safety" below.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` — added:
  - `insertQuotationItems(quotationId, items)` — filters to `approvedPrice != null`, computes
    `amount = unit_price × qty`, batch inserts.
  - `updateQuotationHeader(quotationId, customerName, customerAddress, customerTaxId,
    customerPhone, projectName)`.
  - `findQuotationItemsByQuotationId(quotationId, ticketId)` — maps `quotation_item` rows back
    into `TicketItemDto` (approvedPrice = frozen unit_price; factory/rawPrice/rawCurrency/
    proposedPrice/calced*/manual* fields are not snapshotted, since `QuotationRenderer` never
    reads them — set to `null`). Empty list = no snapshot (legacy quotation or zero priced
    items at issue time).
  - `findQuotationHeaderSnapshot(quotationId)` → `Optional<QuotationHeaderSnapshot>` (new public
    nested record) — reads the 5 new `sales.quotation` columns.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java`:
  - `generateQuotation`: captures the `QuotationDto` returned by `tickets.createQuotation(...)`
    (previously discarded), then in the same `@Transactional` method calls
    `insertQuotationItems(created.id(), full.items())` and `updateQuotationHeader(...)`. Customer
    header (name/address/taxId/phone) is sourced from `CustomerRepository.findById(customerId)`
    when the ticket has a linked customer, falling back to the ticket's own free-text
    `customerName` (and null address/tax/phone) when it doesn't. `projectName` comes from the
    ticket summary.
  - `loadQuotationContext`: now calls `findQuotationItemsByQuotationId` first. If non-empty,
    builds a synthetic `TicketDto` (new `TicketSummaryDto` with customerName/projectName
    overridden from the header snapshot via a new `withCustomerAndProject` helper, and
    `items()` = the snapshot rows) and a synthetic `CustomerDto` from the header snapshot, then
    passes those to `QuotationRenderer` exactly as before. If empty (legacy, pre-V49
    quotation), falls back to the original live-data path unchanged.
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java`:
  - Stubbed `ticketRepo.createQuotation(...)` to return a real `QuotationDto` in all 4 existing
    `generateQuotation_*` tests (required — `generateQuotation` now calls `.id()` on that return
    value, which previously defaulted to Mockito's `null`).
  - Added `generateQuotation_snapshotsOnlyPricedItemsAndCustomerHeaderInSameCall` and
    `generateQuotation_snapshotsCustomerHeaderFromCustomerRepositoryWhenLinked`.
  - Added `getQuotationXlsx_rendersFromSnapshotNotLiveEditedItems`,
    `getQuotationPdf_rendersFromSnapshotNotLiveEditedItems` (renderer is the *real*
    `QuotationRenderer`, not mocked, in this test class — these assert on actual xlsx cell
    content / stripped PDF text), and
    `getQuotationXlsx_legacyFallback_rendersLiveDataWhenNoSnapshotRows`.
- `backend/src/test/java/th/co/glr/hr/ticket/TicketRepositoryIntegrationTest.java` — added:
  - `insertQuotationItems_snapshotsOnlyPricedItemsWithFrozenUnitPriceAndAmount`
  - `insertQuotationItems_noOpWhenAllItemsUnpriced`
  - `updateQuotationHeader_persistsCustomerAndProjectSnapshot`
  - `uniqueIndex_rejectsDuplicateTicketAndVersionPair` (raw `jdbc.update` bypassing the
    repository to simulate the double-click race; asserts `DataIntegrityViolationException`)
- `frontend/src/api/mockApi.js`:
  - `quotation(id)` (generate): deep-copies priced items (`{ ...it }`) and snapshots
    customerName/customerAddress/customerTaxId/customerPhone (from `mockCustomers`, same
    source-of-truth choice as the backend) + projectName (from `mockProjects`) onto the new
    quotation object.
  - `buildMockQuotationXlsx` / `buildMockQuotationHtml`: both now check
    `Array.isArray(quotation.items) && quotation.items.length > 0` and render from the
    snapshot when present, else fall back to live `ticket.items`/`ticket.customerName`/
    `ticket.projectName` (mirrors the backend's legacy fallback for quotations created before
    this change).
  - `// Mirrors TicketService...` comments added/kept accurate at each touch point.

## Commands Run
```bash
git fetch origin && git switch -c feat/quotation-freeze origin/main
cd backend && ./mvnw -q -B compile
cd backend && ./mvnw -q -B test-compile
cd backend && ./mvnw -q -B test -Dtest=TicketServiceTest,QuotationRendererTest
cd backend && ./mvnw -B test -Dtest=TicketRepositoryIntegrationTest
cd backend && ./mvnw -B clean verify
cd frontend && npm ci && npm run lint && npm test && npm run build
```

## Test / Build Results
- **Backend**: `./mvnw -B clean verify` — **BUILD SUCCESS**, **462 tests, 0 failures, 0 errors,
  0 skipped**. Docker was available in this environment, so `TicketRepositoryIntegrationTest`
  (Testcontainers Postgres) ran for real rather than being skipped, and its Flyway migration
  log confirms the schema reaches `v49` cleanly (`Successfully applied 47 migrations to schema
  "hr", now at version v49`). Jacoco coverage checks met.
- **Frontend**: `npm run lint` — 0 errors, the same 7 pre-existing `react-hooks/exhaustive-deps`
  warnings noted in the prior handoff (unrelated files, unchanged by this branch). `npm test` —
  **94/94 passed** (19 test files), including `contract.test.js` (mockApi/hrApi surface parity —
  unaffected, since this branch adds fields to existing objects rather than new methods).
  `npm run build` — succeeds.

## Migration Safety (V49)
- **Version check**: `V49` was confirmed the next free number across both
  `db/migration` (latest was `V48`) and `db/migration-demo` (latest was `V46`) before writing
  the file.
- **Column types**: spec said "match `sales.ticket_item`'s column types (read V6)", but
  `ticket_item`'s shape has moved since V6 — `brand`/`model`/`texture` were renamed from
  `product_name`/`product_code`/`size` in **V8**, and a separate `size` column was added in
  **V9**. I matched the *current* types (as of V48): `brand VARCHAR(255)`, `model VARCHAR(80)`,
  `color VARCHAR(80)`, `texture VARCHAR(80)`, `size VARCHAR(80)`, `qty NUMERIC(12,2)`,
  `qty_sqm NUMERIC(12,4)`, `unit_basis VARCHAR(10)`, `raw_unit VARCHAR(30)` — noting this
  deviation explicitly since the prompt named V6.
- **Unique index duplicate-data risk**: checked `db/migration-demo` for any direct
  `sales.quotation` inserts — there are none (the one quotation-adjacent demo migration, V32,
  only backfills `sales.ticket.customer_id`, never touches `sales.quotation`). So a fresh
  seed/demo database has nothing that could violate `ux_quotation_ticket_version`. For any
  hosted environment where the pre-existing double-click-generate race *did* already produce
  duplicate `(ticket_id, quotation_version)` rows, the migration includes a **preliminary,
  non-destructive dedupe** (a window-function `UPDATE`) that renumbers every row but the
  earliest in each duplicate group to a version past that ticket's current max — no row is
  ever deleted, so no quotation content is lost, only a version number ambiguity is resolved.
  This is a stronger guarantee than V45's `ux_deposit_notice_ticket_version` got (that one
  shipped with no visible pre-dedupe step); I could not query the actual hosted demo/uat/prod
  databases from this sandboxed environment to confirm zero duplicates exist there today, so
  the dedupe step is the defensive answer to that unknown rather than an assumption that it's
  fine.

## Decisions Made
- **Snapshot only priced items** (`approvedPrice != null`), matching exactly which items
  already feed `generateQuotation`'s `total_amount` calculation and which items
  `QuotationRenderer` already filters to when rendering — this branch changes *when* that
  filtered set is captured, not *what* gets captured.
- **Customer header source of truth = `CustomerRepository`, not `ticket.summary().customerName()`**,
  when the ticket has a linked `customerId` — per the task's explicit instruction ("customer
  name/address/taxId/phone from CustomerRepository"). Falls back to the ticket's own free-text
  `customerName` (with null address/tax/phone, since there's no customer record to pull from)
  when there's no linked customer. Note this is a subtle behavior difference from the
  *pre-existing* live-render path, which always used `ticket.summary().customerName()` for the
  customer-name display regardless of whether a customer record was linked — I followed the
  task's explicit spec here since a frozen legal document arguably should carry the
  authoritative customer-record name rather than a possibly-stale free-text field. Flagging for
  reviewer attention as the one place where the snapshot's *content* could differ from what a
  live render on the same day would have shown.
- **`amount` is stored but not read back into `TicketItemDto`** — `QuotationRenderer` always
  recomputes `amount = approvedPrice × qty` itself from the items it's given, so there's no
  code path that needs the persisted `amount` column back as a Java field. It's stored purely
  so the snapshot is a complete, self-describing row (useful for any future direct SQL/report
  needing subtotal-per-line without recomputation) — verified directly via a raw `jdbc` query
  in the integration test rather than adding an unused DTO field.
- **`insertQuotationItems`/`updateQuotationHeader` as separate repository methods** (not folded
  into `createQuotation`) — the task offered both options; separate methods keep
  `createQuotation`'s existing (already-tested) versioning/supersede logic untouched, and let
  the service call them explicitly in sequence, which reads clearly at the call site in
  `generateQuotation`.
- **Legacy-quotation detection is "no snapshot rows exist"**, not a stored flag — a quotation
  generated after V49 whose every item happened to be unpriced would also have zero snapshot
  rows and would take the legacy (live-render) path. This is an accepted edge case: `approve()`
  only sets `approved_price` for items with a non-null `proposed_price`, so an all-unpriced
  approved ticket is unusual, and even in that case the live render's numeric content would be
  identical (all zeros) — the only theoretical drift is the customer/project header, which is a
  much smaller compliance risk than the item-price drift this branch primarily fixes. Noted
  here rather than silently accepted.

## Assumptions
- No access to the actual hosted demo/uat/prod `sales.quotation` table from this sandboxed
  environment, so I could not directly confirm zero pre-existing duplicate
  `(ticket_id, quotation_version)` rows — addressed via the defensive dedupe in the migration
  itself (see Migration Safety) rather than assuming the data is clean.
- Assumed `QuotationRenderer`'s unused `TicketItemDto` fields (`factory`, `rawPrice`,
  `rawCurrency`, `proposedPrice`, `calcedCost`, `calcedPrice`, `calcConfigVersion`,
  `manualPrice`, `manualOverrideReason`) are safe to leave `null` on snapshot-sourced items
  since a full read of `QuotationRenderer.java` (`toXlsx`/`toPdf`/`buildDesc`) confirms none of
  them are referenced anywhere in the render path.

## Known Risks
1. The customer-header source-of-truth decision above (CustomerRepository vs. ticket's
   `customerName`) is a judgment call following the task's literal wording — worth explicit
   Opus review since it's a subtle semantic difference from the pre-existing live path (see
   Decisions Made).
2. Could not verify against a real hosted demo/uat database that zero duplicate
   `(ticket_id, quotation_version)` rows exist today; the migration's dedupe step is designed
   to make this a non-issue regardless, but the reviewer with DB access should ideally confirm
   the dedupe UPDATE affects 0 rows on the actual demo/uat/prod databases before/after applying,
   just to be sure the window-function logic behaves as intended on real data shapes.
3. Live browser/UI verification was not attempted — this task is backend-snapshot-plus-mock-mirror
   only, with an explicit "no frontend changes" design (opaque bytes contract), so there is no
   new UI surface to click through. The existing quotation-download buttons are unchanged.

## Things Not Finished
- None — all planned scope items (migration, repository, service, tests at 3 levels, mockApi
  mirror, handoff) are complete.

## Recommended Next Agent
Claude Opus review (per this repo's standing Sonnet-implements/Opus-reviews loop) — should
focus on: (1) the customer-header source-of-truth decision in Decisions Made #2, (2) the
migration's dedupe-then-unique-index logic in V49 (window-function correctness under multiple
duplicate groups per ticket), and (3) whether `loadQuotationContext`'s synthetic
`TicketDto`/`CustomerDto` construction correctly covers every field `QuotationRenderer` reads
(cross-check against `QuotationRenderer.java` directly rather than trusting this handoff).
Merge only on the user's explicit say-so.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch feat/quotation-freeze (based on origin/main at 3ae79ad, includes PR #219).
Read CLAUDE.md and docs/agent-handoffs/59_feat-quotation-freeze.md — it documents freezing
issued quotations with an item-data snapshot at issue time (V49: sales.quotation_item table +
customer/project header columns on sales.quotation + a unique index on
(ticket_id, quotation_version) with a defensive pre-dedupe). TicketService.generateQuotation now
snapshots priced items + customer header (from CustomerRepository) in the same transaction as
createQuotation; loadQuotationContext renders from the snapshot when present, else falls back to
live data for pre-V49 quotations. mockApi.js mirrors both the snapshot-at-generate-time and the
snapshot-first download behavior. Please independently review: (1) the customer-header
source-of-truth choice (CustomerRepository vs. ticket.summary().customerName() — see Decisions
Made #2 in the handoff, a subtle behavior difference from the pre-existing live-render path),
(2) the V49 migration's window-function dedupe logic before the unique index (simulate multiple
duplicate groups per ticket if possible), and (3) cross-check loadQuotationContext's synthetic
TicketDto/CustomerDto construction against every field QuotationRenderer.java actually reads.
Merge only on the user's say-so.
```
