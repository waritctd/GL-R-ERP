# Agent Handoff — Phase 2 of the branching-workflow program (FOR CODEX)

> **Program context.** Phase 1 (lifecycle, policies, actions API) is done and Opus-reviewed on
> `feat/deal-workflow-p1-lifecycle` (PR #228, draft). This is Phase 2 of 5. Phases 3–5
> (payment ledger, partial delivery, reports/docs) come later as separate handoffs AFTER this
> passes Opus review. Implement ONLY what this document specifies.

## Task
On a new branch `feat/deal-workflow-p2-quotations` **branched from
`feat/deal-workflow-p1-lifecycle`** (NOT from base — Phase 2 stacks on Phase 1):

Turn the single linear quotation chain into **per-recipient quotation records** so a designer
quotation (S4), an owner quotation (S5), and a buyer/contractor quotation (S8) are DISTINCT
and coexist — each with its own version history and accept/reject lifecycle — while the CEO
price gate stays exactly as it is.

1. `recipient_type` (DESIGNER / OWNER / BUYER) + commercial terms on `sales.quotation`.
2. Supersession + versioning become **per recipient chain**, not per ticket.
3. Quotation lifecycle: SENT / ACCEPTED / REJECTED / EXPIRED / CANCELLED (on top of the
   existing DRAFT / ISSUED / SUPERSEDED) with `markQuotationSent/Accepted/Rejected` actions.
4. Amendment reason required for a new version after an ACCEPTED version exists, or after the
   customer has confirmed (paymentStatus past null).
5. Frontend quotation section grouped by recipient with version chains + status + actions.

## Non-negotiable invariants (violating any fails review — repeated from Phase 1)

1. **Owner scoping.** Plain `sales` users view/act on ONLY their own deals
   (`ticket.created_by = user.id`), through `requireViewAccess` / existing owner gates.
   Every new endpoint too.
2. **CEO price gate — DO NOT WEAKEN.** Quotations of EVERY recipient type still generate only
   from CEO-approved prices: `generateQuotation` keeps requiring
   `QUOTATION_ALLOWED_STATUSES` (approved / quotation_issued) and owner-only. The line items
   and amounts still come from `approvedPrice` (frozen into the V49 snapshot). Any price
   change after approval still re-enters Import-proposes → CEO-approves via the existing
   revision flow — Phase 2 adds NO path that quotes an unapproved price. A new recipient's
   quotation reuses the SAME approved item prices; recipient-specific quantity/discount/terms
   differences live in the terms fields + (future) editable line snapshot, NOT by bypassing
   approval. If a recipient genuinely needs different unit prices, that is a price revision
   (CEO re-approves) — document this in the workflow notes, do not implement a bypass.
3. **Lifecycle gate.** Generating / accepting / rejecting a quotation requires the deal
   lifecycle ACTIVE (`requireActive`, Phase 1). A paused/terminal deal blocks it.
4. **sales_manager** stays read+comment-only on operational/quotation actions (its only ticket
   writes are stage/lost/reopen/hold/dormant/resume from Phase 1). Never add it here.
5. **Do not change business logic outside this spec.** No payroll/commission/pricing math.
   No payment-ledger work (that's Phase 3). No delivery work (Phase 4).
6. **Mock parity** (`frontend/src/api/contract.test.js`, both directions). Mock authz is not
   authoritative — permission truth is in backend tests. Money is NUMERIC, never float.
7. No skipped/disabled tests, no lint suppressions, no commented-out production logic. Keep
   the `// Mirrors <JavaClass>` headers in mockApi.js accurate.

## Repo orientation — quotation specifics (verified; do not rediscover)

- **`sales.quotation` today** (base + V27 + V28 + V49): `quotation_id, ticket_id, number
  (UNIQUE), issued_by, issued_at, pdf_path, total_amount NUMERIC(14,2), currency,
  validity_period VARCHAR(100), deposit_pct, revision_date DATE, notes TEXT[], doc_status
  (DRAFT/ISSUED/SUPERSEDED default DRAFT), quotation_version INT default 1, + frozen header
  snapshot customer_name/address/tax_id/phone/project_name (V49)`. Unique index
  **`ux_quotation_ticket_version(ticket_id, quotation_version)`** (V49:71).
- **`sales.quotation_item`** (V49): frozen issue-time line snapshot (brand…size, qty, qty_sqm,
  unit_basis, raw_unit, unit_price NUMERIC(14,2), amount NUMERIC(14,2)). Immutable per version.
- **`TicketRepository.createQuotation(ticketId, number, issuedById, total)`** (line ~285):
  TODAY it `UPDATE ... SET doc_status='SUPERSEDED' WHERE ticket_id=:t AND doc_status<>'SUPERSEDED'`
  (supersedes ALL), then `MAX(quotation_version)+1` across the ticket, inserts `doc_status='ISSUED'`.
  **This is the linear model Phase 2 replaces.** `insertQuotationItems` + `updateQuotationHeader`
  freeze the snapshot in the same tx. `findQuotationsByTicketId` (~547) selects ordered by
  `quotation_version DESC` and maps to `QuotationDto`.
- **`QuotationDto`**: `id, ticketId, number, issuedById, issuedByName, issuedAt, pdfPath,
  totalAmount, currency, quotationVersion, docStatus`. Extend it.
- **`TicketService.generateQuotation`** (~line 221): the only issue path — role SALES, owner,
  `QUOTATION_ALLOWED_STATUSES`, `requireActive`, total from `approvedPrice × qty`, freeze,
  QUOTATION_ISSUED event. This is where recipientType + terms enter.
- **Frontend**: `TicketDetailPage.jsx` quotation section (~line 1505, `quotations.map`) — flat
  list of Rev chips; `latestQuotation = quotations[0]` (version-DESC) drives the cockpit's PDF
  button and `docActions`. `docStatusColors()` (~line 72) maps doc_status → tone.
  `format.js` for Thai labels. Mock: `mockApi.js tickets.quotation` (~1496) mirrors the
  supersede-all + version logic; `db.tickets[].quotations[]` array.
- Verification: `cd backend && ./mvnw -B clean verify` (Testcontainers/Docker) ·
  `cd frontend && npm run lint && npm test && npm run build` · frontend-mock (port 5200,
  quick-login personas). Phase 1 baseline on this branch's parent: backend 489, frontend 119.

## Backend spec

### V52__quotation_recipient_and_terms.sql
```sql
ALTER TABLE sales.quotation
    ADD COLUMN recipient_type      VARCHAR(20) NOT NULL DEFAULT 'UNSPECIFIED',
    ADD COLUMN recipient_label     VARCHAR(255),          -- free-text who (e.g. designer firm)
    ADD COLUMN payment_terms       TEXT,
    ADD COLUMN lead_time           TEXT,
    ADD COLUMN delivery_terms      TEXT,
    ADD COLUMN validity_date       DATE,                  -- structured; keep legacy validity_period text
    ADD COLUMN sent_at             TIMESTAMPTZ,
    ADD COLUMN accepted_at         TIMESTAMPTZ,
    ADD COLUMN rejected_at         TIMESTAMPTZ,
    ADD COLUMN parent_quotation_id BIGINT REFERENCES sales.quotation(quotation_id);
-- CHECK recipient_type IN ('DESIGNER','OWNER','BUYER','UNSPECIFIED')
-- Extend doc_status CHECK (drop+re-add) to include the lifecycle states:
--   ('DRAFT','ISSUED','SENT','ACCEPTED','REJECTED','EXPIRED','CANCELLED','SUPERSEDED')
--   (ISSUED stays as the current issue-time value; SENT is its documented alias — see notes)
-- Backfill: existing rows are UNSPECIFIED (conservative & correctable; no production data).

-- Versioning is now PER RECIPIENT CHAIN. Replace the ticket-wide unique index.
DROP INDEX IF EXISTS sales.ux_quotation_ticket_version;
CREATE UNIQUE INDEX ux_quotation_ticket_recipient_version
    ON sales.quotation(ticket_id, recipient_type, quotation_version);
```
Notes to put in the migration header: recipient_type UNSPECIFIED is the legacy bucket; the
unique index moves to (ticket_id, recipient_type, version) so each recipient chain versions
independently. Because there is no production data, the index swap is safe; document that a
real backfill would first assign recipient_type before re-indexing.

### Java
- `QuotationRecipient.java` (ticket pkg): DESIGNER/OWNER/BUYER/UNSPECIFIED constants + VALID +
  isValid (DealLostReason.java style). Thai labels live in format.js, not here.
- `QuotationStatus` constants (or extend an existing holder): DRAFT/ISSUED/SENT/ACCEPTED/
  REJECTED/EXPIRED/CANCELLED/SUPERSEDED. Document ISSUED≈SENT (issue = sent to the customer;
  we keep ISSUED as the value written at generate time to avoid churn, and treat markSent as
  a no-op-or-timestamp when already ISSUED — see below).
- Extend `QuotationDto` += `recipientType, recipientLabel, paymentTerms, leadTime,
  deliveryTerms, validityDate (LocalDate), sentAt, acceptedAt, rejectedAt, parentQuotationId
  (Long)`. Update the mapper + SELECT in `findQuotationsByTicketId` and any other constructor
  (grep `new QuotationDto(`).
- `TicketRepository`:
  - `createQuotation(...)` gains `recipientType` (+ terms). **Supersede only the same
    recipient chain**: `SET doc_status='SUPERSEDED' WHERE ticket_id=:t AND recipient_type=:rt
    AND doc_status NOT IN ('SUPERSEDED','ACCEPTED','REJECTED','CANCELLED')`. Version =
    `MAX(quotation_version) WHERE ticket_id=:t AND recipient_type=:rt` + 1. Set
    `parent_quotation_id` = the previous head of that chain (the row just superseded, if any).
    Persist the terms columns. Keep the insert `doc_status='ISSUED'`.
  - `markQuotationStatus(quotationId, status, timestampColumn)` — sets doc_status + the
    matching timestamp (sent_at/accepted_at/rejected_at) via `now()`. Guard the SQL to the
    ticket for scoping done in the service.
  - A finder for "quotations of ticket grouped/ordered by recipient then version DESC" (or
    keep the flat list and group in the DTO layer — flat + client grouping is fine).
- `TicketService`:
  - `generateQuotation(ticketId, request, actor)` — `request` carries `recipientType`
    (required, validated), `recipientLabel?`, `paymentTerms?`, `leadTime?`, `deliveryTerms?`,
    `validityDate?`, and `amendmentReason?`. Keep ALL existing gates (role/owner/status/
    active). **Amendment-reason rule**: if this recipient chain already has an ACCEPTED
    version, OR `s.paymentStatus() != null` (customer confirmed), then `amendmentReason` is
    REQUIRED (400 without it) and is recorded in the QUOTATION_ISSUED event message. Freeze
    snapshot + header exactly as today. Event message includes recipient type + version.
  - `markQuotationSent/Accepted/Rejected(ticketId, quotationId, note?, actor)` — role SALES +
    owner (or sales_manager/ceo? NO — keep to sales owner + ceo, mirror generate's gate:
    owner-only, ceo allowed; sales_manager NOT). `requireActive`. Validate the quotation
    belongs to the ticket and is in a legal source state (ISSUED/SENT → ACCEPTED or REJECTED;
    ISSUED → SENT). Accepting one version does NOT auto-reject sibling recipients' quotations.
    Write new event kinds (below). Accepting an OWNER quotation is the signal that supports
    Case 2/4 "owner is buyer, S8 skipped" together with Phase 1's entry_channel — no stage
    force; the sales owner still drives confirmCustomer.
  - Quotations never move the stage backward. Sending a DESIGNER/OWNER quotation MAY
    monotonically auto-advance to QUOTE_DESIGN_SIDE and a BUYER quotation to QUOTE_BUYER via
    the existing `autoAdvanceStage` (forward-only; safe if already past). Keep it a suggestion,
    never a regress. (Optional — if it complicates the CEO gate, skip auto-advance and leave
    stage manual; document the choice.)
- New `TicketEventKind`: `QUOTATION_SENT`, `QUOTATION_ACCEPTED`, `QUOTATION_REJECTED`
  (re-declare `chk_event_kind` in V52, full list from V51 + these three).
- **Actions API**: `TicketService.actions` — add, when a quotation exists for the viewer's
  deal and they're the sales owner/ceo: `MARK_QUOTATION_SENT/ACCEPTED/REJECTED` (kind `doc`,
  requiredFields `["quotationId"]`), and keep `GENERATE_QUOTATION` (now with recipientType as
  a required field). The catalog helpers you added in Phase 1 are the place for this.

### Endpoints (TicketController)
- Change `POST /{id}/quotation` to accept a body (recipientType + terms + amendmentReason).
  Keep it working if you must, but the request record is now required — update the mock + UI.
- `POST /{id}/quotations/{quotationId}/sent` · `/accepted` · `/rejected` (optional `{note}`).
- Request records with jakarta validation, following the UpdateStageRequest pattern.

## Frontend spec

- `routes.js`/`hrApi.js`: `quotation(id, payload)` gains a body; add `markQuotationSent`,
  `markQuotationAccepted`, `markQuotationRejected` (id, quotationId, payload).
- `mockApi.js`: mirror per-recipient supersession/versioning, the terms fields, the three
  status actions, the amendment-reason rule, and the actions additions. Extend the seed
  quotations on 2–3 demo tickets to show at least one deal with BOTH a designer and an owner
  quotation (distinct recipient_type, independent versions) so the grouped UI is visible.
- `format.js`: `quotationRecipientLabel(type)` (ผู้ออกแบบ/เจ้าของ/ผู้ซื้อ-ผู้รับเหมา/ไม่ระบุ),
  `quotationStatusLabel(status)` → {label, tone} for the extended states.
- **Quotation section** (`TicketDetailPage.jsx` ~1505): group by recipient_type — a subsection
  per recipient (ผู้ออกแบบ / เจ้าของ / ผู้ซื้อ-ผู้รับเหมา / ไม่ระบุ), each showing its version
  chain (Rev n, status badge, amount, issued/sent/validity/accepted dates, terms). Per active
  (non-superseded) head: actions markSent / markAccepted / markRejected (gated by the actions
  endpoint), plus the existing Excel/PDF download. Revise = generate a new version → open a
  small modal that collects recipientType (defaults to the chain being revised), the terms,
  and the amendment reason when required. Keep the cockpit's stage-gated "ออกใบเสนอราคา"
  entry, but it now opens this modal (choose recipient + terms) instead of firing directly.
- Cockpit `latestQuotation`/docActions: pick the latest ACTIVE quotation per the current
  stage's relevant recipient (QUOTE_DESIGN_SIDE → designer/owner; QUOTE_BUYER → buyer),
  falling back to the most recent overall. Keep the post-issue "ใบเสนอราคา … (PDF)" quick
  download. Don't overbuild — the grouped section is the primary surface.

## Out of scope (do NOT build now)
Payment ledger / amounts / overdue / "payable = accepted quotation total" derivation
(Phase 3) · partial delivery / stock (Phase 4) · dashboards, filters, workflow doc, full
22-case matrix (Phase 5). No stage renames. No editable per-recipient unit prices that bypass
CEO approval (see invariant 2).

## Tests (minimum)
Backend (`TicketServiceTest` / `TicketRepositoryIntegrationTest` style):
- generateQuotation with recipientType DESIGNER then OWNER on the same ticket → two chains,
  each version 1, both ACTIVE (not superseding each other); a second DESIGNER quotation →
  designer v2, designer v1 SUPERSEDED, owner v1 untouched.
- amendment reason: required once a chain has an ACCEPTED version or paymentStatus != null;
  400 without it, OK with it, recorded in the event.
- markQuotationSent/Accepted/Rejected: legal transitions succeed + timestamps set; illegal
  source state 409; non-owner sales 403; sales_manager 403; lifecycle ON_HOLD 409.
- CEO price gate intact: generateQuotation still 409s from a non-approved status; still
  owner-only; totals still from approvedPrice (assert unchanged behavior).
- unique index: two quotations same ticket+recipient+version rejected (integration test
  against the V52 index), different recipients same version allowed.
- actions endpoint surfaces MARK_QUOTATION_* for the owner and not for other roles.
Frontend: contract.test.js green; a component test that the quotation section renders two
recipient groups from a mocked ticket and hides mark-actions absent from `/actions`.

## Definition of done (Codex fills before passing back)
1. All backend + frontend changes on `feat/deal-workflow-p2-quotations`.
2. `cd backend && ./mvnw -B clean verify` → BUILD SUCCESS (report counts; note Docker skips).
3. `cd frontend && npm run lint && npm test && npm run build` → 0 lint errors, all green.
4. frontend-mock manual pass (describe what you drove): generate a designer + an owner
   quotation on one deal (two groups), revise one (amendment reason prompt), mark
   sent→accepted, confirm CEO gate still blocks generation from a non-approved deal.
5. Fill THIS file's sections below. Commit on the branch. Merge NOTHING.

## Files changed
(fill in)

## Commands run
(fill in)

## Tests / build results
(fill in)

## Known risks / questions for Opus review
(fill in)
