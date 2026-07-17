# Agent Handoff — Phase 4 of the branching-workflow program (FOR CODEX)

> **Program context.** Phases 1–3 (lifecycle/policies/actions #228, per-recipient quotations
> #229, payment ledger #230) are done and Opus-reviewed, all DRAFT do-not-merge. This is Phase
> 4 of 5. Phase 5 (reports/filters, workflow doc, 22-case matrix) follows AFTER this passes
> Opus review. Implement ONLY what this document specifies. There is NO production data.

## Task
On a new branch `feat/deal-workflow-p4-fulfilment` **branched from
`feat/deal-workflow-p3-payments`** (NOT from base — Phase 4 stacks on Phase 3):

Give the deal **per-line delivery tracking, partial delivery, and manual stock declaration** so
Cases 7 (product in stock, skip S12–S16) and 8 (partial stock / partial delivery, `40/100`)
work — with delivery progress independent of payment (already true after Phase 3).

1. Extend the fulfilment states; add per-line `qty_delivered`.
2. `sales.delivery_record` (+ items) — an auditable delivery log.
3. `reserveStock` (manual per-line availability declaration — Case 7), `recordPartialDelivery`,
   `completeDelivery` (Case 8). FULLY_DELIVERED auto when Σdelivered = Σordered.
4. Delivery-complete drives the DELIVERED stage + the `close()` gate.

## NO real inventory subsystem (scope guard — user decision)
There is no warehouse/stock/reservation module anywhere and Phase 4 does NOT build one. Stock
availability is a **manual declaration** by staff (import/account/ceo), recorded with actor +
reason + quantity as an audited statement — trusted, not validated against a real ledger. A
genuine inventory system is a separate future project. Do not add stock/on-hand/reservation
tables beyond what this spec lists.

## Non-negotiable invariants (carried from Phases 1–3)

1. **Owner scoping.** Reads through `requireViewAccess`; plain sales see only their own deals.
2. **Role gates.** Fulfilment/delivery actions belong to **import + ceo** (goods handling),
   matching today's `IMPORT_ROLES` for the IR/shipping marks. `reserveStock`,
   `recordPartialDelivery`, `completeDelivery` = import/ceo. Delivery *scheduling* (the
   DELIVERY_SCHEDULING stage, S18) stays a sales manual stage as today. sales_manager gets
   NOTHING operational. account/ceo keep money; no cross-wiring.
3. **Lifecycle gate.** Every delivery mutation requires lifecycle ACTIVE (`requireActive`).
4. **CEO price gate & payment ledger untouched.** Phase 4 does not touch pricing, quotations,
   or the payment ledger. Delivery progress is INDEPENDENT of payment (Phase 3 already made
   pay-before-delivery work; Phase 4 makes deliver-before-pay coherent too).
5. **Quantities are NUMERIC(12,2)**, never float. Guard: `0 ≤ qty_delivered ≤ qty` at the DB
   (CHECK) and service; a delivery may not push a line's cumulative delivered past its ordered
   qty (unless an explicit authorized adjustment reduced the order — out of scope here, so just
   block over-delivery with a clear error).
6. **Mock parity** (`contract.test.js`). Mock authz non-authoritative. No skipped tests / lint
   suppressions / commented-out logic. Keep `// Mirrors` headers.
7. **Do not change business logic outside this spec.** No stage renames.

## Repo orientation — fulfilment specifics (verified; do not rediscover)

- **`sales.ticket.fulfillment_status`** VARCHAR(40), **NO CHECK** (Java-only, like
  payment_status). Current linear path: `null → IR_ISSUED → IR_SENT → SHIPPING →
  GOODS_RECEIVED`. Set by `TicketService`:
  - `issueImportRequest` → IR_ISSUED (import/ceo; requires quotation_issued + deposit-ready or
    a waived policy; auto-advances stage to PROCUREMENT). Refuses if fulfillmentStatus != null.
  - `markIrSent` (IR_ISSUED→IR_SENT), `markShipping` (IR_SENT→SHIPPING), `markGoodsReceived`
    (SHIPPING→GOODS_RECEIVED; also carries payment DEPOSIT_PAID→AWAITING_FINAL_PAYMENT). All
    `IMPORT_ROLES`, `requireActive`.
  - GOODS_RECEIVED = "arrived at OUR warehouse" (end of PROCUREMENT stage S17), NOT delivered
    to the customer. Stages DELIVERY_SCHEDULING (S18) and DELIVERED (S19) are MANUAL sales
    stages today (`SALES_TARGET_STAGES`); only PROCUREMENT auto-advances (from IR).
- **`close()`** (~line 425): requires `FULLY_PAID` + `GOODS_RECEIVED` (dual-track) and (Phase 3)
  no outstanding balance. Phase 4 broadens the delivery half — see below.
- **`sales.ticket_item`** (V6 + …): `qty NUMERIC(12,2)` (pieces, the primary unit), `qty_sqm
  NUMERIC(12,4)`, brand/model/color/texture/size, pricing fields. **No delivered/allocated
  columns.** `TicketItemDto` has `qty, qtySqm, …` — add `qtyDelivered`.
- **Frontend**: `stageMeta.js` `PROCUREMENT_SUBSTEPS` (IR_ISSUED/IR_SENT/SHIPPING/
  GOODS_RECEIVED chips) rendered by `DealStagePanel.jsx` (การนำเข้า chips). Cockpit
  operational buttons (ส่ง IR / shipping / รับสินค้า) gated by the Phase-1 actions endpoint.
  Item list rendered in `TicketDetailPage.jsx`.
- Verification: `cd backend && ./mvnw -B clean verify` (Testcontainers) ·
  `cd frontend && npm run lint && npm test && npm run build` · frontend-mock port 5200.
  Phase 3 baseline: backend 495, frontend 121.

## Backend spec

### V54__fulfilment_and_delivery.sql
```sql
ALTER TABLE sales.ticket_item
    ADD COLUMN qty_delivered NUMERIC(12,2) NOT NULL DEFAULT 0
        CONSTRAINT chk_ticket_item_qty_delivered CHECK (qty_delivered >= 0 AND qty_delivered <= qty);

CREATE TABLE sales.delivery_record (
    delivery_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id     BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    source        VARCHAR(20) NOT NULL
                  CONSTRAINT chk_delivery_source CHECK (source IN ('WAREHOUSE','STOCK')),
    delivered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_by  BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    note          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_delivery_record_ticket ON sales.delivery_record(ticket_id, delivered_at);

CREATE TABLE sales.delivery_record_item (
    delivery_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    delivery_id      BIGINT NOT NULL REFERENCES sales.delivery_record(delivery_id) ON DELETE CASCADE,
    item_id          BIGINT NOT NULL REFERENCES sales.ticket_item(item_id),
    qty              NUMERIC(12,2) NOT NULL CHECK (qty > 0)
);

-- Optional manual stock declaration per line (Case 7/8): what staff DECLARE is available
-- from stock for this deal. Trusted statement, not a real reservation.
ALTER TABLE sales.ticket_item
    ADD COLUMN qty_from_stock NUMERIC(12,2) NOT NULL DEFAULT 0
        CONSTRAINT chk_ticket_item_qty_from_stock CHECK (qty_from_stock >= 0 AND qty_from_stock <= qty),
    ADD COLUMN stock_note     TEXT;
-- chk_event_kind: re-declare (full list from V53) + 'STOCK_RESERVED','DELIVERY_RECORDED','DELIVERY_COMPLETED'
```
`fulfillment_status` stays uncontrolled VARCHAR — no CHECK to widen.

### Java
- `FulfilmentStatus.java` constants (ticket pkg): the existing IR_ISSUED/IR_SENT/SHIPPING/
  GOODS_RECEIVED **plus** `PICKED_UP` (S14 — picked up from manufacturer), `CUSTOMS_CLEARANCE`
  (S16 — arrived in TH, awaiting clearance), `FROM_STOCK` (Case 7 — fulfilled from stock, no
  import), `PARTIALLY_DELIVERED`, `FULLY_DELIVERED`. Provide `isDeliveryComplete(status)` =
  status ∈ {GOODS_RECEIVED, FULLY_DELIVERED} (legacy coarse deals reach GOODS_RECEIVED; deals
  using delivery records reach FULLY_DELIVERED). Keep the coarse path working: the new
  PICKED_UP / CUSTOMS_CLEARANCE are OPTIONAL fine-grained steps that import MAY set between
  IR_SENT→SHIPPING and SHIPPING→GOODS_RECEIVED — do not force them into the required chain.
- Extend `TicketItemDto` += `qtyDelivered`, `qtyFromStock` (+ mapper/SELECT). Add a derived
  `qtyRemaining = qty − qtyDelivered` if convenient (or compute in the DTO/frontend).
- `DeliveryRecordDto` + `DeliveryRecordItemDto` records.
- `TicketRepository`:
  - `reserveStock(ticketId, List<{itemId, qtyFromStock, note}>)` — set per-line qty_from_stock
    (guarded ≤ qty).
  - `insertDeliveryRecord(ticketId, source, deliveredBy, note, List<{itemId, qty}>)` — inserts
    the record + items AND increments each line's `qty_delivered` (in one tx; guard cumulative
    ≤ qty — do the check in SQL or service and 409 on over-delivery).
  - `findDeliveriesByTicket(ticketId)`, `sumDeliveredByTicket(ticketId)` / `sumOrdered(...)`
    (or compute from items), `allLinesFullyDelivered(ticketId)`.
  - `updateFulfillmentStatus` already exists (reuse).
- `TicketService`:
  - **`reserveStock(ticketId, request, actor)`** — import/ceo, `requireActive`. Records the
    per-line stock declaration + a `STOCK_RESERVED` event (message: total qty_from_stock +
    note). **Case 7 (fully in stock)**: if every ordered line is fully covered by qty_from_stock
    and there's no import in flight (fulfillmentStatus null or FROM_STOCK), the deal may
    SKIP the IR — set fulfillmentStatus = FROM_STOCK and auto-advance stage to PROCUREMENT
    (goods are effectively "in hand"). Do NOT force IR issuance. **Case 8 (partial stock)**:
    lines partially from stock, remainder still needs the import path — stock lines are
    deliverable immediately (recordPartialDelivery from source STOCK) while the IR covers the
    rest. Keep it simple: reserveStock only declares; delivery is a separate action.
  - **`recordPartialDelivery(ticketId, request, actor)`** — import/ceo, `requireActive`.
    request `{source (WAREHOUSE|STOCK), note?, lines:[{itemId, qty>0}]}`. Insert a delivery
    record; block if any line's cumulative delivered would exceed ordered (409). After insert,
    recompute: if Σdelivered == Σordered → fulfillmentStatus = FULLY_DELIVERED + auto-advance
    stage to DELIVERED; else fulfillmentStatus = PARTIALLY_DELIVERED. Emit `DELIVERY_RECORDED`
    event (per-line summary, e.g. `40/100`). A STOCK-source delivery is allowed even without an
    IR (Case 7/8) provided the line has qty_from_stock ≥ the delivered qty (guard, with a clear
    message) — a WAREHOUSE-source delivery requires goods received (fulfillmentStatus
    GOODS_RECEIVED or the line otherwise sourced). Document the guard.
  - **`completeDelivery(ticketId, note?, actor)`** — import/ceo. Convenience: records the
    remaining undelivered qty of every line as one WAREHOUSE (or mixed) delivery and sets
    FULLY_DELIVERED + DELIVERED stage. Refuse if nothing remains. (Or: mark FULLY_DELIVERED
    only when all lines already delivered — pick the cleaner semantics and document it. Prefer
    "delivers the remainder" so one click closes out a fully-received deal.)
  - **`close()` change**: replace the delivery half of the gate — require
    `FulfilmentStatus.isDeliveryComplete(fulfillmentStatus)` (GOODS_RECEIVED **or**
    FULLY_DELIVERED) instead of hardcoded GOODS_RECEIVED, keeping the FULLY_PAID +
    no-outstanding requirement. A deal that used delivery records must be FULLY_DELIVERED; a
    coarse legacy deal at GOODS_RECEIVED still closes. Document this in the workflow notes and
    the review questions (business may want close to require FULLY_DELIVERED strictly).
  - `markGoodsReceived` unchanged except: it no longer needs to be the delivery-complete
    signal — it's warehouse arrival. Do NOT auto-set FULLY_DELIVERED there.
- **Actions API** (`TicketService.actions`, Phase-1 catalog): add for import/ceo when
  applicable: `RESERVE_STOCK` (when items exist and not yet fully delivered),
  `RECORD_PARTIAL_DELIVERY` (when goods available — GOODS_RECEIVED or FROM_STOCK or stock
  declared — and Σdelivered < Σordered, requiredFields `["source","lines"]`),
  `COMPLETE_DELIVERY` (when remainder > 0 and goods available). Keep the existing IR/shipping
  marks.

### Endpoints (TicketController)
`POST /{id}/reserve-stock` `{lines:[{itemId, qtyFromStock, note?}]}` ·
`POST /{id}/deliveries` `{source, note?, lines:[{itemId, qty}]}` (partial delivery) ·
`POST /{id}/deliveries/complete` `{note?}` · `GET /{id}/deliveries` (list). Optionally
`POST /{id}/picked-up`, `POST /{id}/customs-clearance` for the two new fine-grained fulfilment
marks (import/ceo) — only if trivial; otherwise skip and leave them for a later pass (document
the choice). Request records with jakarta validation.

## Frontend spec

- `routes.js`/`hrApi.js`: `reserveStock`, `recordDelivery`, `completeDelivery`,
  `listDeliveries` (+ pickedUp/customsClearance if added). Mock mirrors all + qty_delivered/
  qty_from_stock on items + the delivery records + the FULLY_DELIVERED/PARTIALLY_DELIVERED
  recompute + actions additions. Seed a demo deal mid-delivery (e.g. 40/100 on a line) so the
  progress UI is visible, and one FROM_STOCK deal.
- `format.js`: `fulfilmentStatusLabel` for the new states (สินค้าจากสต็อก / ส่งมอบบางส่วน /
  ส่งมอบครบแล้ว / รับจากผู้ผลิตแล้ว / รอออกของ). Extend `PROCUREMENT_SUBSTEPS` in `stageMeta.js`
  (or add a separate delivery substep list) to include the new states without breaking the
  coarse chain.
- **Delivery section** in `TicketDetailPage.jsx` (near payment/procurement): per-line
  ordered / delivered / remaining with a `40 / 100` progress indicator, the delivery-records
  history (date/source/who/note + per-line qty), and — for import/ceo, gated by the actions
  endpoint — a **บันทึกการส่งสินค้า** modal (source + per-line qty, defaulting to remaining),
  a **ส่งมอบครบ** button (completeDelivery), and a **จองสินค้าจากสต็อก** control (per-line
  qty_from_stock + reason). Cockpit การนำเข้า chips gain FROM_STOCK / PARTIALLY_DELIVERED /
  FULLY_DELIVERED states; show the delivery progress in the stage hero when relevant.
- Keep the existing IR/shipping/goods-received cockpit buttons working.

## Out of scope (do NOT build now)
Real inventory / on-hand / reservation ledger (never — separate project). Dashboards, filters,
workflow doc, 22-case matrix (Phase 5). No pricing/quotation/payment changes. No stage renames.

## Tests (minimum)
Backend:
- recordPartialDelivery: 40 of 100 → line qty_delivered 40, fulfillmentStatus
  PARTIALLY_DELIVERED, Σdelivered 40; deliver remaining 60 → FULLY_DELIVERED + stage DELIVERED;
  over-delivery (deliver 70 when 60 remain) → 409; qty ≤ 0 → 400.
- reserveStock: per-line qty_from_stock set; qty_from_stock > qty → 400; Case 7 fully-in-stock
  → FROM_STOCK + PROCUREMENT stage without an IR; STOCK delivery allowed up to declared stock.
- role/lifecycle gates: import/ceo only (sales/account/sales_manager 403); ON_HOLD → 409.
- close(): FULLY_DELIVERED + FULLY_PAID (+ no outstanding) → closes; GOODS_RECEIVED coarse +
  FULLY_PAID → still closes; PARTIALLY_DELIVERED → 409 even if FULLY_PAID.
- delivery independent of payment: a deal can be FULLY_DELIVERED while BALANCE_PENDING (Case
  10) and FULLY_PAID while PARTIALLY_DELIVERED (Case 9) — assert both states coexist.
Frontend: contract.test.js green; a component test that the delivery section shows `40/100`
and hides record-delivery absent from `/actions`.

## Definition of done (Codex fills before passing back)
1. All backend + frontend changes on `feat/deal-workflow-p4-fulfilment`.
2. `cd backend && ./mvnw -B clean verify` → BUILD SUCCESS (counts; note Docker skips).
3. `cd frontend && npm run lint && npm test && npm run build` → 0 lint errors, all green.
4. frontend-mock manual pass (describe): record a partial delivery (progress updates),
   complete it (→ FULLY_DELIVERED + DELIVERED stage), a stock deal skips the IR, a
   fully-paid-but-partially-delivered deal stays open.
5. Fill THIS file's sections below. Commit on the branch. Merge NOTHING.

## Files changed
(fill in)

## Commands run
(fill in)

## Tests / build results
(fill in)

## Known risks / questions for Opus review
(fill in)
