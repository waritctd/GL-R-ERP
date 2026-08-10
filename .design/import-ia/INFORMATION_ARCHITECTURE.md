# Information Architecture: ฝ่ายนำเข้า (Import role)

**Status:** approved and implemented — branch `refactor/import-ia-remove-procurement`
**Date:** 2026-08-11
**Scope:** Import's navigation and page structure, only.
**Explicitly unaffected:** the Sales pipeline workspace — see §6, which is an audit proving
containment, not a proposal.
**Out of scope:** payroll/HR, commission math, any authorization change, any backend or DB change.

---

## 1. The problem with today's IA

Import's surfaces are organised by **system object** — one page per aggregate:

| Surface | Aggregate it exposes | Owner ruling |
|---|---|---|
| `/` `ImportOverview` | tickets + pricing requests, bucketed by stage | keep, revise |
| `/pricing-requests` `คิวขอราคา` | PricingRequest | keep |
| `/procurement` §1 `งานรับเข้าคลัง / ส่งมอบ` | Ticket.fulfillmentStatus | **delete the page** |
| `/procurement` §2 `ใบสั่งซื้อโรงงาน` | FactoryPurchaseOrder | **delete — not a current requirement** |
| `/factory-purchase-orders/:id` | FactoryPurchaseOrder | **delete** |

Three problems fall out of that:

**(a) `ใบสั่งซื้อโรงงาน` models work the business does not do yet.** It is a full per-factory
purchase-order ledger — proforma refs, container refs, ETD/ETA, landed cost. The code itself
records that it has never been used: *"unused in production today (0 POs)"*
([DealFulfilmentPanel.jsx:386](frontend/src/features/tickets/DealFulfilmentPanel.jsx:386)). It is
the single largest thing on Import's screen and it is furniture.

**(b) `/procurement` §1 is a duplicate of the dashboard worklist.** Both are built from the same
helper. `ProcurementFulfilmentPage` calls `nextFulfilmentActionCode(ticket)`; `ImportOverview`
calls `nextImportAction(ticket, prs)`, which *wraps* that same function
([importActions.js:73](frontend/src/features/tickets/importActions.js:73)). Same rows, same
`STAGE_ORDER` sort, same `IMPORT_ACTION_LABELS` button text, both linking to `/tickets/:id`. The
dashboard's row set is a strict superset — it also includes the pricing-request pickup step.
**Deleting `/procurement` therefore removes no capability at all.**

**(c) Import's real obligation is invisible in the IA.** Import owes Sales a status readout on
**two** streams for the same deal — the price request, and the fulfilment chain. Today neither is
expressed as an obligation; they are expressed as two unrelated menu items whose names
(`คิวคำขอราคา`, `จัดซื้อ & นำเข้า`) describe storage locations rather than what Sales is waiting for.

**The reorganising principle: Import's IA is keyed to the deal it owes Sales an answer on, not to
the aggregate the answer happens to be stored in.**

---

## 2. Site Map

Bold = changed. `~~strike~~` = deleted.

- **`/` — งานของฉัน** *(ImportOverview, revised — §4)*
- `/pricing-requests` — คิวขอราคา
  - `/pricing-requests/:id` — คำขอราคา detail *(factory quotes, costing — unchanged)*
- `/tickets/:id` — deal detail *(read + the `จัดซื้อ-ส่งมอบ` tab where fulfilment is actually recorded — unchanged)*
- `/catalog` — แคตตาล็อกสินค้า
- `/price-import` — นำเข้าราคา
- `/attendance` — เวลาทำงาน *(self-service)*
- `/profile`, `/employee-requests` — self-service *(unchanged)*
- ~~`/procurement` — จัดซื้อ & นำเข้า~~ **deleted**
- ~~`/factory-purchase-orders` — ใบสั่งซื้อโรงงาน~~ **deleted**
- ~~`/factory-purchase-orders/:id`~~ **deleted**

Net: Import goes from **6 work surfaces to 4**, and from two pages that answer "what do I do next"
down to one.

### Deleted routes — redirect policy

`/procurement` and `/factory-purchase-orders` are removed from `PATH_GUARDS`, so an existing
bookmark falls through to the app's normal unknown-route handling rather than `AccessDeniedPage`
(which currently names `/procurement` explicitly —
[AccessDeniedPage.jsx:40](frontend/src/components/common/AccessDeniedPage.jsx:40) — and must lose
that entry). No redirect alias: the destination is the dashboard, which is already `/`.

---

## 3. Navigation Model

**Primary navigation** — sidebar, `งานขาย` group. Import sees 4 items (was 5):

| Order | Label | Path | Why here |
|---|---|---|---|
| 1 | *(dashboard)* | `/` | The worklist. Where the day starts and ends. |
| 2 | คิวขอราคา | `/pricing-requests` | Stream 1 depth. **Renamed** from `คิวคำขอราคา` — see §7. |
| 3 | แคตตาล็อกสินค้า | `/catalog` | Reference, not work. |
| 4 | นำเข้าราคา | `/price-import` | Reference maintenance, not work. |

`จัดซื้อ & นำเข้า` is removed. The nav item, its `match: ['/procurement', '/factory-purchase-orders']`
entry, and `canManageProcurement`'s two `PATH_GUARDS` all go with it.

**Why the fulfilment chain gets no nav item.** It is not being demoted — it is being put where it
already lives. Fulfilment is *per-deal* work: every mutation in the chain
(`ออกคำขอนำเข้า` → `ส่งแล้ว` → `ขนส่ง` → `รับเข้าคลัง` → `บันทึกส่งมอบ`) happens inside
`DealFulfilmentPanel` on `/tickets/:id`, never on the list page. `/procurement` §1 was only ever a
launcher into those deals — and the dashboard worklist is the same launcher, already listing the
same rows plus the pricing ones. A second nav item would be a second door into one room.

**Secondary navigation.** `SalesTabs` renders nothing for Import: it only ever offered `คิวขอราคา`,
and its own `tabs.length < 2` rule already suppresses a one-tab bar
([SalesTabs.jsx:49](frontend/src/features/sales/SalesTabs.jsx:49)). Unchanged by this proposal.

**Utility navigation.** Topbar user menu (`ข้อมูลของฉัน`, `คำขอของฉัน`, logout) — unchanged.

**Mobile.** Unchanged: sidebar collapses to the existing drawer. One fewer item to scroll.

---

## 4. Content Hierarchy

### 4.1 `/` — the ImportOverview dashboard

**Scope ruling (owner, mid-implementation): "don't overdo it."** An earlier draft of this section
proposed re-cutting the six stage tiles into four obligation tiles and tagging each worklist row
with its stream (`ราคา` / `ส่งมอบ`). **That was dropped.** Import and Account exist only to keep
Sales informed; a dashboard redesign is not what this change is for.

**What actually ships here is one removal:**

**Removed — the `คิวของฉัน` panel.** A two-row launcher into `/pricing-requests` and
`/procurement`, described in its own comment as "the two workspaces the worklist above feeds into".
One of those two destinations no longer exists, and the survivor duplicated a permanent sidebar
item. A one-row shortcut list pointing at the sidebar is the same redundancy as (b) above, so the
whole panel went and `กำลังขนส่ง` takes the full width.

**Deliberately unchanged:** the six `สถานะงานทั้งหมด` tiles, the `สิ่งที่ต้องทำ` worklist, and the
`กำลังขนส่ง` list. Import keeps the fulfilment chain (owner ruling — see §5), so every tile still
names real work.

**Known wart, left alone.** The `ตั้งราคา` tile's count and its filter disagree by design: the
count is every in-flight pricing request (`PRICING_IN_FLIGHT_STATUSES`, 8 statuses) while clicking
it filters the worklist to `pickupPricingRequest` rows only (`SUBMITTED`). The tile can therefore
read "10" and show 2 rows. That is pre-existing and documented in the source as an intentional
"awareness gauge"; fixing it is a dashboard change, which this branch is not.

### 4.2 `/pricing-requests` — คิวขอราคา

Unchanged in structure. It is already the correct shape for stream 1: filter chips over statuses,
one row per request, `รับเรื่อง` inline, deep link to detail.

### 4.3 `/tickets/:id` — deal detail, `จัดซื้อ-ส่งมอบ` tab

Structurally unchanged, minus one block: **step 3 `ใบสั่งซื้อโรงงาน`**
([DealFulfilmentPanel.jsx:385–437](frontend/src/features/tickets/DealFulfilmentPanel.jsx:385)) is
deleted along with its `api.procurement.listForPricingRequest` query. Steps 1 (`นำเข้าสินค้า`) and
2 (`ส่งมอบสินค้า`) — the entire chain Import actually performs — are untouched.

---

## 5. User Flows

### Flow A — Sales asks for a price, Import answers *(stream 1)*

1. Sales submits a pricing request → status `SUBMITTED`.
2. Import lands on `/`. The `รอตั้งราคา` tile carries a count; the worklist row shows
   `[ราคา] ลูกค้า · PCR-xxxx · รับงาน · ขอราคา`.
3. Import clicks through to `/pricing-requests` and picks it up → `IMPORT_REVIEWING`.
4. Import works factory quotes and costing on `/pricing-requests/:id`.
   - Needs something from Sales → `ขอข้อมูลเพิ่มเติม` → `MORE_INFO_REQUIRED`; Sales is now the
     blocked party.
   - Factory quote in hand → costing → `ส่งให้ CEO ตรวจ` → `READY_FOR_CEO_REVIEW`.
5. **Sales sees each of those transitions on the deal's `สินค้าและราคา` tab** (`PricingRequestPanel`,
   already built, already role-scoped). Nothing new is required for stream 1 at the *detail* level.

### Flow B — Goods move, Import tells Sales *(stream 2)*

1. Customer accepts the quotation; the deal reaches `quotation_issued`.
2. Import lands on `/`. `รอส่งมอบ` carries a count; the row shows
   `[ส่งมอบ] ลูกค้า · TKT-xxxx · ออกคำขอนำเข้า`.
3. Import clicks the row → `/tickets/:id` → `จัดซื้อ-ส่งมอบ` tab → records the step.
4. `fulfillmentStatus` advances; the deal reappears in the worklist at its next step, or drops out
   when `FULLY_DELIVERED`.
5. **Sales sees the new status on the deal's own `จัดซื้อ-ส่งมอบ` tab**, exactly as it does today —
   see §6. Unchanged.

### Flow C — Sales asks "where is my order?"

Sales opens the deal → `จัดซื้อ-ส่งมอบ` tab → reads the chain. **Unchanged by this IA**, before and
after. §6 records why it is left alone and what it would take to improve it later.

---

## 6. Impact on the Sales pipeline workspace — **none, by design**

**Owner ruling: the Sales pipeline must not be affected.** This IA is contained entirely within
Import's own surfaces. `/tickets`, `/tickets/:id` as Sales sees it, the deal list columns, the
filters, the phase cards, the inbox toggle — all untouched. What follows is the audit that shows
containment actually holds, not a proposal.

### 6.1 Why Sales cannot notice the deletion

Every deleted surface is already gated to `import`/`ceo`, so no Sales user has a path into any of
it today:

| Deleted thing | Gate today | Sales impact |
|---|---|---|
| `/procurement` | `PATH_GUARDS` → `canManageProcurement` = `['import','ceo']` | none — Sales 403s there now |
| `/factory-purchase-orders(/:id)` | same guard | none |
| Nav item `จัดซื้อ & นำเข้า` | `show:` same permission | never rendered for Sales |
| `api.procurement.*` | `ProcurementService.RAW_PO_ROLES` | Sales cannot call it |
| `DealFulfilmentPanel` step 3 | `['import','ceo'].includes(role)` — **and** the query is `enabled: canViewProcurement` ([DealFulfilmentPanel.jsx:145–151](frontend/src/features/tickets/DealFulfilmentPanel.jsx:145)) | Sales never renders the block and never fires the request |

The last row is the only file this IA touches that Sales also renders, and it is the one that
matters most. `DealFulfilmentPanel` **is** on Sales' `จัดซื้อ-ส่งมอบ` tab
(`visibleSections('sales').delivery === true`), so the file is shared — but step 3 is
role-gated at the JSX level *and* its query is `enabled`-gated, so for a Sales viewer the deleted
block renders nothing and fetches nothing today. **Steps 1 and 2 — the entire chain Sales actually
reads — are not touched.**

### 6.2 Shared modules kept deliberately

| Module | Also used by Sales | Ruling |
|---|---|---|
| `importActions.js` | no (Import surfaces only) | **keep** — `DealFulfilmentPanel` is documented against it as the single source of truth for stage |
| `stageMeta.js` `PROCUREMENT_SUBSTEPS` | yes — `SubstepChips` on the fulfilment panel | **keep, untouched** |
| `salesViewScope.js` | yes — `visibleSections` / `dealInScope` | **keep, untouched.** Import keeps both streams, so its branch stays correct. Authorization-adjacent; no reason to open it. |
| `fulfilmentStatusLabel()` | yes | **keep, untouched** |

### 6.3 The gap this IA deliberately does not close

For the record, since it is the obvious next question: `/tickets` shows Sales almost nothing about
Import's work. `ขั้นตอน / เหตุผลงาน` and `ความคืบหน้า` both render `salesStage` — Sales' *own*
pipeline stage, which Sales moves. The only fulfilment signal on the whole page is one
`ส่งมอบบางส่วน` filter chip covering **one** of nine `fulfillmentStatus` values, behind a collapsed
filter sheet. Import can complete four of five fulfilment steps with no visible change on Sales'
list screen.

`fulfillmentStatus` is already on the list DTO
([TicketResponses.java:15](backend/src/main/java/th/co/glr/hr/ticket/TicketResponses.java:15)) and
already in the browser — the page reads it for its flag count
([TicketListPage.jsx:642](frontend/src/features/tickets/TicketListPage.jsx:642)) and discards it
otherwise. A read-only `สถานะฝ่ายนำเข้า` column would therefore be frontend-only and cheap.

**It is out of scope here** under the ruling above, and recorded in §10 as follow-up 1. Pricing
request status could not be added to that column at all without a backend change — the list DTO
does not carry it ([salesViewScope.js:130](frontend/src/features/tickets/salesViewScope.js:130)) —
which is follow-up 2.

### 6.4 Verification — done

Containment was a claim, so it was checked rather than asserted:

- **`git diff` on both TicketDetailPage files is 46 deletions, 0 insertions.** Not one Sales-facing
  assertion was rewritten — only factory-PO-specific lines removed. This is the strongest available
  proof of containment: had a Sales assertion needed editing, it would show as an insertion.
  The `account` test that asserted the four fulfilment buttons stay hidden kept all four
  assertions; only its two factory-PO lines went.
- **Browser, mock stack, `/tickets/7` as `sales`:** all six tabs present, `จัดซื้อ-ส่งมอบ` renders
  `นำเข้าสินค้า` + `ส่งมอบสินค้า` with delivery progress `0 / 400` intact, no `ใบสั่งซื้อโรงงาน`.
- **Browser as `import`:** sidebar is 5 items with no `จัดซื้อ & นำเข้า`; `/procurement` and
  `/factory-purchase-orders/1` both redirect to `/` via the existing `path="*"` catch-all;
  no horizontal overflow on `.content-scroll`.
- `npm run lint` clean · `npm test` **1579 passed / 130 files** · `npm run build` clean.
- New wrong-way-round tests: `AppShell.test.jsx` asserts the nav item is *absent*;
  `permissions.test.js` asserts no guard is left behind for the three removed paths.

⚠️ **Not verified:** `frontend/e2e-real` (needs the real Spring stack + Postgres — not run here),
and anything permission-shaped, since this ran on `VITE_USE_MOCKS=true`. Neither matters much for
this change: **no authorization was altered.** Two `PATH_GUARDS` entries were deleted along with
the routes they guarded, and `canManageProcurement` was removed once its last reader was gone. The
Java `ProcurementController` still enforces `RAW_PO_ROLES` on endpoints that now have no caller.

**No authorization change anywhere in this IA** — no role gate, scope filter, or `PATH_GUARDS`
predicate is altered; two guard *entries* are deleted along with the routes they guarded.

---

## 7. Naming Conventions

| Concept | Label in UI | Notes |
|---|---|---|
| A request from Sales for a price | **คำขอราคา** | Unchanged. Code prefix `PCR-`. |
| The cross-deal list of those | **คิวขอราคา** | **Renamed** from `คิวคำขอราคา`. The page header already says `คิวขอราคา` while the sidebar says `คิวคำขอราคา` — one of them is wrong and the shorter one wins. A nav label that disagrees with the page title it lands on is the "navigation shows current location" check failing. |
| Import's two obligations | **ราคา** / **ส่งมอบ** | New worklist row tags. Two words, mutually exclusive, matching the two tile names. |
| Goods moving toward the customer | **ส่งมอบ** | Consistently, everywhere. Not `จัดส่ง`, not `นำเข้า`. |
| Goods currently in transit | **กำลังขนส่ง** | Reserved for the awareness tile only — a state, not a task. |
| Per-factory purchase order | *(retired)* | `ใบสั่งซื้อโรงงาน` leaves the vocabulary entirely. |
| Import's whole job | *(no collective noun)* | `จัดซื้อ & นำเข้า` is retired. It named a page, and the page named a business process that does not exist yet. |

---

## 8. Component Reuse Map

| Component | Used on | Change |
|---|---|---|
| `ImportOverview` | `/` | Revised: 6 tiles → 4, worklist rows gain a stream tag, `คิวของฉัน` panel removed |
| `importActions.js` | `ImportOverview` | **Now single-consumer** — `ProcurementFulfilmentPage` was its second caller. Keep the module; it is the one source of truth for "which stage is this deal at" and `DealFulfilmentPanel` is documented against it. |
| `PricingRequestQueuePage` | `/pricing-requests` | Unchanged |
| `DealFulfilmentPanel` | `/tickets/:id` | Step 3 (factory POs) deleted; steps 1–2 unchanged |
| `DataTable` | `/tickets`, `/pricing-requests` | Unchanged; `/tickets` gains one column |
| ~~`ProcurementFulfilmentPage`~~ | — | **Deleted** |
| ~~`ProcurementListPage`~~ | — | **Deleted** |
| ~~`ProcurementDetailPage`~~ | — | **Deleted** |

**Deletion inventory as shipped** — 28 files, +91 / **−1509**:

```
frontend/src/features/procurement/          6 files, 926 lines  (deleted)
frontend/src/App.jsx                        3 lazy imports, 3 <Route>
frontend/src/app/permissions.js             2 PATH_GUARDS entries
frontend/src/components/layout/AppShell.jsx 1 nav item + the คิวขอราคา rename
frontend/src/components/common/AccessDeniedPage.jsx  1 label entry
frontend/src/api/routes.js                  procurement.* (8 routes) + canManageProcurement
frontend/src/api/hrApi.js                   api.procurement (8 methods)
frontend/src/api/mockApi.js                 api.procurement (191 lines) + 4 dead internals
frontend/src/api/queryKeys.js               3 factoryPurchaseOrder* keys
frontend/src/data/demoSales.js              makeFpo builder, 4 call sites, 2 seq counters
frontend/src/utils/format.js                factoryPurchaseOrderStatusLabel (last caller gone)
frontend/src/features/tickets/DealFulfilmentPanel.jsx  step-3 block, its query,
                                            pickAcceptedPricingRequest, 4 imports, 1 prop
frontend/src/features/tickets/TicketDetailPage.jsx     that prop's call site
frontend/e2e-real/route-coverage.spec.js    3 route entries
frontend/src/styles.css                     .procurement-table
frontend/src/index.css                      comment now marked as history
```

Tests: `permissions.test.js` and `AppShell.test.jsx` gain wrong-way-round assertions (§6.4);
`TicketDetailPage.test.jsx` loses 2 factory-PO tests and 3 lines from a third;
`PricingRequestDetailPage.test.jsx` follows the label rename.

**Kept, dormant, unreferenced:** `backend/.../procurement/*.java` (8 endpoints),
`sales.factory_purchase_order` (V77/V78). No migration is written; nothing is dropped. When the
requirement lands, the backend is still there.

⚠️ `api.procurement` must be removed from **`hrApi.js` and `mockApi.js` together** —
`frontend/src/api/contract.test.js` asserts the two method surfaces match in both directions and
will fail on a one-sided deletion.

---

## 9. Content Growth Plan

| Surface | Grows with | Handling |
|---|---|---|
| `/` worklist | open obligations — bounded by live deals, realistically tens | Tile filters; no pagination needed. Revisit past ~50 rows. |
| `/pricing-requests` | every request ever | Already handled: status chips default to `SUBMITTED`, `activeOnly: true`, `DataTable` search + sort |
| `/tickets` | every deal | Already handled: phase/lifecycle/flag filters, inbox toggle, search, pagination |

Nothing in this IA introduces an unbounded list.

---

## 10. URL Strategy

- Pattern: `/<resource>` for a cross-deal list, `/<resource>/:id` for one record. Unchanged.
- Dynamic segments: `:id` only, always the numeric PK.
- Query parameters: filter state where it is worth sharing — `/tickets?status=`,
  `/pricing-requests` (component state today; leave as is).
- **Removed:** `/procurement`, `/factory-purchase-orders`, `/factory-purchase-orders/:id`.
- **Added:** none. Every destination in the new IA is a URL that already exists.

### Follow-ups deliberately excluded from this IA

All three touch the Sales pipeline and are therefore out of scope under the owner ruling in §6.
Recorded so the reasoning is not lost.

1. **`สถานะฝ่ายนำเข้า` column on `/tickets`** — read-only `fulfillmentStatus`, frontend-only, closes
   the §6.3 gap for stream 2.
2. **`pricingRequestStatus` on the ticket list DTO** — needed before stream 1 could join that
   column. A backend change; also excluded by the "surface existing transitions only" ruling.
3. **Explicit Import→Sales status note** (stage + free text + expected-by date) — considered and
   set aside by the same ruling.

And one non-change, stated so nobody "fixes" it later by accident:

4. **`salesViewScope.js`'s `import` branch** still names `delivery`/`pricingRequest` sections and a
   `PROCUREMENT_IDX` worklist rule. Both stay correct after this change — Import keeps both streams —
   so the file is untouched. It is authorization-adjacent; there is no reason to open it.
