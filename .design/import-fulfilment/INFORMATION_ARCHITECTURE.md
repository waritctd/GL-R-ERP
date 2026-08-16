# Information Architecture: งานนำเข้า (Import fulfilment workspace)

**Status:** implemented — branch `feat/import-fulfilment-workspace`
**Date:** 2026-08-17
**Scope:** one new Import work surface (`/fulfilment`), its nav item, and the CTA repoint on
Import's dashboard. Stage 12 (`DealStage.PROCUREMENT`) only.
**Out of scope:** delivery (`PARTIALLY_DELIVERED` / `FULLY_DELIVERED`, stages 13–14) — being
reassigned to Sales in later work; payroll/HR; commission math; any backend or DB change.

---

## 0. Why this exists, given `.design/import-ia/` said the opposite

`.design/import-ia/INFORMATION_ARCHITECTURE.md` §3 explicitly refused a fulfilment nav item:

> "Why the fulfilment chain gets no nav item. It is not being demoted — it is being put where it
> already lives. […] `/procurement` §1 was only ever a launcher into those deals — and the dashboard
> worklist is the same launcher. A second nav item would be a second door into one room."

**That reasoning was correct and is not being reversed.** It rests on a premise — *every fulfilment
mutation happens inside `DealFulfilmentPanel` on `/tickets/:id`, and a list page can only launch you
there* — and this branch changes that premise. `/procurement` §1 was deleted because it duplicated
the dashboard worklist: same helper, same rows, same sort, same labels, same destination. It was a
second door into one room.

`/fulfilment` is not a door. It is the room. It performs the four transitions itself.

The test that separates the two: **delete this page and Import loses the ability to advance N deals
without opening N deal pages.** Deleting `/procurement` lost nothing, which is exactly why it went.

What that means concretely for today's Import:

| | `/procurement` §1 (deleted) | `/fulfilment` (this branch) |
|---|---|---|
| Rows | `nextFulfilmentActionCode` | `nextFulfilmentActionCode` (same helper, delivery filtered out) |
| Row control | `navigate('/tickets/' + id)` | `useMutation` → the four `api.tickets.*` endpoints |
| Mutations on the page | **0** | **4** |
| Cost of advancing 6 deals | 6 page loads, 6 tab switches, 6 clicks, 6 back-navigations | 6 arm+confirm pairs, no navigation |

The duplication rule still binds, and §4.1 below records what was removed from the dashboard to
honour it.

---

## 1. The problem

Import has a real workspace for **stream 1** and none for **stream 2**.

| | Stream 1 — price | Stream 2 — fulfilment |
|---|---|---|
| Cross-deal list | `/pricing-requests` `คิวขอราคา` | *(none)* |
| Filter by state | status chips | *(none)* |
| Act from a row | `pickupMutation.mutate(row.id)` | *(none — all four live on one deal's tab)* |
| Browse/search deals | `DataTable searchable` | *(none — `canViewDealPipeline` excludes import, so `/tickets` 403s)* |

The only stream-2 worklist is the `สิ่งที่ต้องทำ` panel on `ImportOverview`, where every row's button
is `navigate('/tickets/' + id)`. Import's most repetitive job — walking a batch of deals one
milestone forward — costs a full page load, a tab switch and a back-navigation *per deal*, and
Import has no way to browse or search the deals it owns because the pipeline browser is gated to
sales/sales_manager/ceo.

**The reorganising principle: stream 2 gets the same shape stream 1 already has — a filterable
cross-deal list whose rows act in place.**

---

## 2. Site Map

Bold = new.

- `/` — งานของฉัน *(ImportOverview — tiles + worklist + กำลังขนส่ง; §4.1)*
- `/pricing-requests` — คิวขอราคา *(stream 1, unchanged)*
  - `/pricing-requests/:id` — คำขอราคา detail *(unchanged)*
- **`/fulfilment` — งานนำเข้า** *(stream 2, NEW — §4.2)*
- `/tickets/:id` — deal detail *(unchanged; still the only place for stock reservation, delivery,
  item weights, documents, and the full event history)*
- `/catalog` — แคตตาล็อกสินค้า
- `/price-import` — นำเข้าราคา

Net: Import goes from 4 work surfaces to 5, and from **one** page that can advance a deal
(`/tickets/:id`) to **two**.

### Why `/fulfilment` and not `/procurement` or `/import`

| Candidate | Rejected because |
|---|---|
| `/procurement` | Burned URL. Deleted in `ebaf6888` with a documented redirect policy (stale bookmarks fall through `path="*"` to `/`). Resurrecting it would make a *different* page answer an old bookmark — worse than a redirect to the dashboard. |
| `/import` | Collides with `/price-import` (`นำเข้าราคา`), where "import" already means *upload a spreadsheet*. Two sibling nav items whose paths differ by a prefix and whose meanings do not overlap at all. |
| `/fulfilment` | ✅ Matches the axis the page filters on (`fulfillmentStatus`) and every frontend identifier for it — `DealFulfilmentPanel`, `nextFulfilmentActionCode`, `fulfilmentStatusLabel`. Single-l, per frontend convention; the double-l `fulfillmentStatus` spelling is the DB column and DTO field, never a route. |

---

## 3. Navigation Model

**Primary navigation** — sidebar, `งานขาย` group. Import sees 5 items (was 4):

| Order | Label | Path | Audience |
|---|---|---|---|
| 1 | *(dashboard)* | `/` | all |
| 2 | แคตตาล็อกสินค้า | `/catalog` | `canViewCatalog` |
| 3 | นำเข้าราคา | `/price-import` | `canManagePriceImport` |
| 4 | คิวขอราคา | `/pricing-requests` | `canViewPricingRequestQueue` — import/ceo/**sales_manager** |
| 5 | **งานนำเข้า** | **`/fulfilment`** | **`canActOnFulfilment` — import/ceo** |

**Placement: immediately after `คิวขอราคา`.** Import's two obligations to Sales become two adjacent
nav items — stream 1 then stream 2, in the order a deal travels. Nothing else moves.

**Audience is narrower than its neighbour, deliberately.** `คิวขอราคา` includes `sales_manager`;
this does not, because `TicketService.FULFILMENT_ROLES` is `{import, ceo}` and a sales_manager who
reached this page would see rows whose every button 403s. A nav item that leads only to refusals is
worse than no nav item.

**Why this is not the second door §3 of the previous IA refused.** That door and this one do not
open on the same room: the dashboard worklist *launches*, this *acts*. The previous IA's own
deletion test — "deleting `/procurement` removes no capability at all" — fails here in the other
direction.

**Label `งานนำเข้า`.** Parallels `งานการเงิน` (`/finance`), the existing name for Account's
acting worklist — same `งาน<domain>` construction for the same kind of object, so the two role
worklists read as siblings. Checked against the vocabulary the previous IA fixed (§7 there):

- not `ส่งมอบ` — reserved for goods moving to the customer, which is **out of scope** and moving to Sales;
- not `กำลังขนส่ง` — reserved for the dashboard's awareness tile, a state not a task;
- not `จัดซื้อ & นำเข้า` — retired; it named a business process the company does not run;
- `นำเข้า` alone is what the four milestones actually are: issue the import request, send it, goods
  travel, goods arrive. `ImportOverview` already calls this งานนำเข้า in its own subtitle.

**Secondary navigation.** `SalesTabs` is unchanged and renders nothing here (its `tabs.length < 2`
rule already suppresses a one-tab bar for Import). This page deliberately does **not** render
`SalesTabs`: unlike `/pricing-requests` it is not part of a tabbed sales workspace.

**Utility / mobile.** Unchanged — one more item in the same drawer.

---

## 4. Content Hierarchy

### 4.1 `/` — ImportOverview (revised: CTA destinations only)

**One change: the four fulfilment-chain CTAs now point at `/fulfilment` instead of `/tickets/:id`.**
Made in `nextImportAction` (the shared helper), not in the page, so the dashboard and any future
consumer follow automatically.

The dashboard stays the at-a-glance summary — tiles, worklist, `กำลังขนส่ง` — and does **not** gain
buttons that mutate. That is the anti-duplication rule from `ebaf6888` applied in the one direction
that keeps both surfaces worth having:

- **dashboard = what is happening** (six counts, every stream, including ones this page excludes)
- **`/fulfilment` = do the next thing** (four stages, one stream, acts in place)

Two CTA codes keep their old destinations, because `/fulfilment` genuinely cannot serve them:

| Code | Destination | Why |
|---|---|---|
| `pickupPricingRequest` | `/pricing-requests` | stream 1; unchanged, already correct |
| `recordDelivery` | `/tickets/:id` | **out of scope** — delivery moves to Sales; this workspace has no delivery control at all |

Deliberately unchanged: the six tiles (including `ส่งมอบ`, which still counts delivery rows the
workspace excludes — the dashboard is the whole picture by design), the sort, and the known
`ตั้งราคา` count/filter mismatch documented in the previous IA §4.1.

### 4.2 `/fulfilment` — งานนำเข้า

1. **Page header** — name and the four-stage chain in one line, so the scope ("this page does IR →
   goods received, and stops") is stated before any row is read.
2. **Filter chips + counts** — the four stages plus ทั้งหมด. Counts make the chips a status readout,
   not just a control: "3 deals waiting for an IR" is answered without clicking.
3. **The rows** — one per deal, each carrying its own next action as a button.
4. **Escape hatch per row** — the deal code links to `/tickets/:id` for everything this page
   deliberately cannot do (stock reservation, delivery, item weights, documents, event history).

**Row content priority:** customer → deal code + current stage → due date / overdue → the action.
Customer name first because Import identifies a deal by who it is for; the code is the
lookup key and the link, so it is second and monospaced.

**Exit behaviour is load-bearing and must be visible.** `ยืนยันรับเข้าคลัง` is the last action this
page owns: `nextFulfilmentActionCode` returns `recordDelivery` for `GOODS_RECEIVED`, which this page
filters out. **Confirming it therefore removes the row.** Unexplained, that reads as a bug ("I
clicked and it vanished"), so the success toast names the handoff rather than just the write.

---

## 5. User Flows

### Flow A — advance a batch (the flow this page exists for)

1. Import opens `งานนำเข้า`. Chips read `ทั้งหมด 9 · ออกคำขอนำเข้า 3 · ส่งคำขอนำเข้าแล้ว 2 · บันทึกออกเดินทาง 3 · ยืนยันรับเข้าคลัง 1`.
2. Clicks `ออกคำขอนำเข้า` → 3 rows.
3. Clicks a row's button → **the row arms**: it expands into a confirmation strip naming the deal
   and the exact transition.
   - `ยืนยัน` → mutation fires → toast → list and every deal-detail cache invalidate → row
     re-renders at its next stage (or leaves, per §4.2).
   - `ยกเลิก`, `Esc`, or arming a different row → disarmed, nothing sent.
4. Repeats without leaving the page.

### Flow B — the server refuses

1. Import confirms `ออกคำขอนำเข้า` on a deal whose deposit is not ready.
2. `TicketService.issueImportRequest` 409s with its own Thai message.
3. That message is shown verbatim in an error toast; the row disarms and stays put.

**Why the frontend does not pre-empt this.** `nextFulfilmentActionCode` matches on
`status`/`fulfillmentStatus` only. The backend additionally requires deposit readiness
(`DEPOSIT_NOTICE_ISSUED` / `DEPOSIT_PAID` / bypass-policy + `CUSTOMER_CONFIRMED`) and refuses
`markShipping` / `markGoodsReceived` on a PO-tracked deal. The server's own `availableActions` is
the one authority on "may I act right now", and **`api.tickets.list` rows do not carry it** —
`importActions.js` says so in its header, and `DealFulfilmentPanel` keeps `hasAction(...)` local for
exactly this reason.

Re-deriving the deposit rule client-side would be a *second copy of a backend rule that can drift* —
the failure this repo has been bitten by repeatedly. So: the button is an **affordance**, the server
is the **authority**, and the confirmation strip shows the deal's payment status so the operator has
the same fact the server will judge on without the frontend judging it.

### Flow C — Sales asks "where is my order?"

Unchanged. Sales reads the deal's `จัดซื้อ-ส่งมอบ` tab. This page writes the same column through the
same endpoints, so the two can never disagree — provided the cache invalidation matches, which is
why the page reuses `DealFulfilmentPanel.invalidateAfterFulfilmentChange`'s key set exactly.

---

## 6. Naming Conventions

| Concept | Label in UI | Notes |
|---|---|---|
| This workspace | **งานนำเข้า** | Sibling of `งานการเงิน`. Import's stream-2 obligation. |
| The four milestones | `ออกคำขอนำเข้า` · `ส่งคำขอนำเข้าแล้ว` · `บันทึกออกเดินทาง` · `ยืนยันรับเข้าคลัง` | Read from `IMPORT_ACTION_LABELS` — never retyped, so a chip cannot drift from the button it filters to. |
| Current state of a deal | `fulfilmentStatusLabel()` | Same badge wording as the deal page and the dashboard. |
| Goods moving to the customer | **ส่งมอบ** | Named here only to say it is *not on this page*. |
| Goods in transit | **กำลังขนส่ง** | Still reserved for the dashboard tile. |

---

## 7. Component Reuse Map

| Component | Used on | Change |
|---|---|---|
| `importActions.js` | dashboard, this page, `DealFulfilmentPanel` | `nextImportAction`'s `to` for the four fulfilment codes → `/fulfilment`. `nextFulfilmentActionCode` **untouched** — it is the shared decision and a second copy is forbidden. |
| `PageHeader` / `PageStack` / `Panel` / `FilterBar` / `EmptyState` / `StatusBadge` / `Button` | this page | reused as-is; no new page CSS |
| `DealFulfilmentPanel` | `/tickets/:id` | unchanged. Still the only surface for stock, delivery, weights. |
| `ImportOverview` | `/` | CTA destinations only (§4.1) |

**Not built, deliberately:** no shared "acting worklist" abstraction extracted from
`AccountFinancePage`. That page navigates and this one mutates; they share a silhouette, not
behaviour, and factoring on a silhouette is how a second hidden design system starts.

---

## 8. Content Growth Plan

Bounded by live deals at stages 12 — realistically tens. Filter chips + search cover it; no
pagination. `api.tickets.list` is already role-scoped server-side, so growth in *other* roles' deals
never reaches this page. Revisit past ~50 rows.

---

## 9. URL Strategy

- `/fulfilment` — cross-deal list. No detail sub-path: a single deal's detail is `/tickets/:id`,
  which already exists and which import may read (`canViewTickets`).
- **Query parameters: none.** The chip is component state, matching `PricingRequestQueuePage`'s
  `filterKey`/`setFilterKey`, which this page was asked to mirror. (`AccountFinancePage` puts its
  filter in `?stage=`; the two conventions already coexist and unifying them is not this branch's
  job.)
- **A `PATH_GUARDS` entry is mandatory, not optional.** `canAccessPath` returns `true` for any path
  no guard claims — an unguarded `/fulfilment` would be reachable by every authenticated role.

---

## 10. Authorization — presentation only

The route guard and nav gate are **presentation**. The real gate is
`TicketService.FULFILMENT_ROLES = Set.of("import", "ceo")`, which this branch does not touch, on
four endpoints it does not touch.

`ROLE_PERMISSIONS.canActOnFulfilment = ['import', 'ceo']` is a **new frontend constant mirroring**
that set. It grants nothing: a role added to it would see the page and get 403s.

⚠️ **Verification for this branch ran under `VITE_USE_MOCKS=true` and in jsdom. Nothing
permission-shaped is verified by it.** Per CLAUDE.md a real-DB integration test through the Java
service is what proves a role gate, and none was written because no role gate changed.

⚠️ **Known mock divergence, pre-existing:** `mockApi.issueImportRequest` and its three siblings call
`hasRole('import')` — CEO only. Production `FULFILMENT_ROLES` is `{import, ceo}`. The mock is
*stricter* than production here (the safe direction, but still a divergence), so mock-mode clicking
as `ceo` will fail on all four actions while production would allow them. Not fixed on this branch:
it is a mockApi authz change, outside a frontend-UI task's scope.
