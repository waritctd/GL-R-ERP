# 85 — feat/sales-pricing-request-foundation

**Branch:** `feat/sales-pricing-request-foundation` (branched off `feat/sales-flow-remediation-part1`, not off `main`) · **Migration:** V58 only
**Date:** 2026-07-19 · **Status:** all 7 planned commits done, plus a fix commit for three defects the browser pass surfaced. Backend **630 tests green**, frontend **242 tests green**.

⚠️ **This branch is not independently deployable.** See "Known risks" #1. Do not merge to `uat` or
`main` for deploy until the pricing chain is rebuilt on the PricingRequest aggregate (Steps 2-4).

⚠️ **Branch stacking:** this branch sits on top of `feat/sales-flow-remediation-part1`, which had
never been pushed. Pushing this branch therefore also published part1's four commits (`ab922ac`,
`04a1bce`, `cce5e7d`, `1415774`). A PR from here to `main` contains **ten** commits, not six —
either merge part1 first, or expect a combined review.

This is the **first handoff file for this branch**. It covers the whole plan: a summary of backend
commits 1-5, full detail on commit 6 (frontend), and what remains.

## Why

Commit `03b5ba9` (backend, part of this branch) retired ticket-level `submit()`/auto-submit-on-create:
a newly created deal is now always a lightweight DRAFT, whether or not products were attached, and
creation notifies nobody. That is *deliberately* a regression until this branch finishes — from that
point until the frontend catches up, a new deal cannot be priced at all. The `PricingRequest`
aggregate (commits 1-5, backend) is the replacement: a deal may now have several pricing requests
(one per recipient / re-quote round), each walking its own
`DRAFT -> SUBMITTED -> IMPORT_REVIEWING <-> MORE_INFO_REQUIRED -> CANCELLED` lifecycle, independent of
`sales.ticket.status`. Commit 6 is the frontend slice that makes that aggregate usable end to end.

## Backend commits 1-5 (summary only — see their own messages for full detail)

| Commit | What |
|---|---|
| `d829324` feat(pricing): add pricing request schema | New `sales.pricing_request` / `_item` / `_event` tables (migration), `PricingRequestStatus/Recipient/EventKind/QuantityType` constants. |
| `baebd99` feat(pricing): add pricing request repository | `PricingRequestRepository` — CRUD + compare-and-set `transition()`, mirrors `TicketRepository`'s conventions but never writes `sales.ticket`/`sales.ticket_event`. |
| `46e9e39` feat(pricing): add draft and submit workflow | `PricingRequestService.createDraft/get/listForTicket/list/updateDraft/submit` + `PricingRequestController`, all validation-before-persist. |
| `7cd0891` feat(pricing): add import pickup and information loop | `pickup/requestInformation/respondInformation/cancel` + the internal `cancelOpenForTicket` cascade (no controller endpoint — invoked by `TicketService.markLost/cancel`). |
| `03b5ba9` refactor(ticket): stop auto-submitting deals with products | `TicketService.create` always starts DRAFT, no notification; ticket-level `submit()` now 409s unconditionally (deprecated); `SUBMIT` removed from `actions()`. |

## Commit 6 (this handoff) — frontend

### Scope
- API layer: `pricingRequests` namespace in `routes.js` / `hrApi.js` / `mockApi.js` (10 methods:
  `listForTicket, create, queue, get, update, submit, pickup, requestInformation,
  respondInformation, cancel`), plus `canViewPricingRequestQueue` permission.
- New feature folder `frontend/src/features/pricingRequests/`: `pricingRequestMeta.js`,
  `PricingRequestPanel.jsx` (per-deal section on `TicketDetailPage`), `PricingRequestCreateModal.jsx`,
  `PricingRequestQueuePage.jsx` (Import's cross-deal queue, new route `/pricing-requests`).
- Fixed the stale ticket mock that commit 5 (backend) made inaccurate: `tickets.submit` now 409s
  unconditionally, `tickets.create` always produces `draft`/`CREATED`/no-notify, `SUBMIT` removed
  from the mock's `actions()` builder.
- `TicketDetailPage`: mounted `PricingRequestPanel` after the items table; removed the dead
  ticket-level submit button + its next-action help text.
- `TicketListPage.handleCreate` now navigates to the new deal instead of just closing the modal.
- `DealStagePanel`'s "การขอราคา" substep strip re-keyed off the deal's latest `PricingRequest`
  status instead of the permanently-`draft` `ticket.status`; renders nothing with no requests.
- `TicketDashboard`'s "รอรับเรื่อง (Import)" queue tile re-pointed at the PricingRequest queue.
- `SalesTabs` now accepts `role` and conditionally shows a "คิวขอราคา" tab.

### Out of scope
- No attachments on a pricing request (explicitly cut).
- No real contact picker for the recipient — `recipientLabel` (free text) only; `recipientContactId`
  wiring is a follow-up.
- No dedicated "edit draft" UI (PUT `/pricing-requests/{id}` is implemented in all three API layers
  for contract completeness, but nothing in the UI calls it yet — a saved draft can only be
  re-submitted, not edited, from `PricingRequestPanel`). **Note this is the same class of gap as
  the `requestInformation` defect above, which shipped unnoticed because the API layer existed and
  the tests passed. Treat "implemented in the API layer" as NOT meaning "reachable by a user" —
  check for a rendered control.**
- (Resolved during review — the `TicketDashboard` StatCard "รอรับเรื่อง" tile that still read the
  stale `summary.submitted` was repointed at the pricing-request queue, and the then-orphaned
  `canPickup` binding removed.)

## Files changed

**API layer**
- `frontend/src/api/routes.js` — `pricingRequests` route namespace + `canViewPricingRequestQueue` in `ROLE_PERMISSIONS`.
- `frontend/src/api/hrApi.js` — `pricingRequests` namespace (10 methods).
- `frontend/src/api/mockApi.js` — `pricingRequests` namespace (module-level store + 10 methods, ~340 new lines); fixed `tickets.create`/`tickets.submit`/`tickets.actions`' SUBMIT line; removed now-dead `doTransition` helper (its only caller was the old `tickets.submit`).
- `frontend/src/api/queryKeys.js` — `pricingRequestsByTicket`, `pricingRequestQueue`, `pricingRequestDetail`.
- `frontend/src/app/permissions.js` — `PATH_GUARDS` entry for `/pricing-requests`.
- `frontend/src/utils/format.js` — `pricingRequestStatusLabel`.

**New feature folder**
- `frontend/src/features/pricingRequests/pricingRequestMeta.js` — statuses, transition table, recipient/quantity-type options, gate predicates (`canCreatePricingRequest`, `canSubmitPricingRequest`, `canPickupPricingRequest`, `canRequestInformation`, `canRespondInformation`, `canCancelPricingRequest`), `PRICING_REQUEST_SUBSTEPS`, `latestPricingRequest`.
- `frontend/src/features/pricingRequests/PricingRequestPanel.jsx` — per-deal table/list, create button, per-row expand (items + own event log), submit/respond/cancel actions.
- `frontend/src/features/pricingRequests/PricingRequestCreateModal.jsx` — recipient, required date, target price/currency, note, item table seeded from the deal's ticket items; บันทึกร่าง / ส่งให้ Import.
- `frontend/src/features/pricingRequests/PricingRequestQueuePage.jsx` — Import's queue (status filter, `DataTable` desktop/mobile-card, pickup action). Uses a `<Link>` per row instead of `DataTable`'s `onRowClick` — a whole-row `<button>` would have nested the pickup `<button>` inside it (invalid HTML; caught via a jsdom `validateDOMNesting` warning during testing).
- Tests: `pricingRequestMeta.test.js` (24), `PricingRequestPanel.test.jsx` (5), `PricingRequestQueuePage.test.jsx` (4).

**Existing files edited**
- `frontend/src/features/tickets/TicketDetailPage.jsx` — mounted `PricingRequestPanel`; removed the submit button/help text/`can.submit`; added a `pricingRequestsByTicket` query, passed down to `DealStagePanel` as `pricingRequests`.
- `frontend/src/features/tickets/TicketListPage.jsx` — `handleCreate` navigates to `/tickets/{newId}`; `<SalesTabs role={user.role} />`.
- `frontend/src/features/tickets/TicketCreateModal.jsx` — helper copy near the items section (no behavioural change).
- `frontend/src/features/tickets/DealStagePanel.jsx` — `pricingRequests` prop; substep strip re-keyed; new `frontend/src/features/tickets/DealStagePanel.test.jsx` (4 tests — did not exist before).
- `frontend/src/features/tickets/stageMeta.js` — removed the now-dead `PRICING_SUBSTEPS` export (moved to `pricingRequestMeta.js`).
- `frontend/src/features/sales/SalesTabs.jsx` — accepts `role`, conditional "คิวขอราคา" tab.
- `frontend/src/features/dashboard/TicketDashboard.jsx` + `.test.jsx` — "รอรับเรื่อง (Import)" now reads the PricingRequest queue count; test file's `api` mock gained `pricingRequests.queue`.
- `frontend/src/App.jsx` — lazy import + `<Route path="/pricing-requests">`.
- `frontend/src/styles.css` — `.pricing-request-queue-table` grid-template-columns (matches the existing per-page-table convention, e.g. `.ticket-table`).
- `frontend/src/features/tickets/TicketDetailPage.test.jsx` — mock gained `pricingRequests.listForTicket/get`.
- `frontend/src/features/tickets/TicketListPage.test.jsx` — fixed the `api.tickets.create` fixture shape (`{ticket:{summary:{id,...}}}`, was flattened `{ticket:{id,...}}` — never noticed before because nothing read the id); added a navigation assertion via a `useLocation`-based `LocationDisplay` helper (`MemoryRouter` doesn't touch `window.location`).

## Response wrapper shapes (verified against the controller)

- **Detail-shaped** (`createDraft`, `get`, `updateDraft`, `submit`, `pickup`, `requestInformation`,
  `respondInformation`, `cancel`) → `PricingRequestController` returns
  `new PricingRequestDetailResponse(dto)`, a record `{ pricingRequest: PricingRequestDetailDto }`
  where `PricingRequestDetailDto = { summary, items, events }`. Both `hrApi.js` and `mockApi.js`
  return `{ pricingRequest: { summary, items, events } }` from every one of those methods.
- **List-shaped** (`listForTicket`, `list`/`queue`) → `Map.of("items", ...)`, i.e. `{ items: [...] }`
  of `PricingRequestSummaryDto`. Both clients match.

## Commands run

```bash
cd backend  && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build
```

## Test / build results

All re-run independently by the reviewing agent, not just reported by the implementer.

- **Backend:** `./mvnw -B clean verify` → **624 tests, 0 failures, 0 errors, 0 skipped**, BUILD
  SUCCESS, Jacoco coverage gate met. Integration tests **did run** (Docker was available, so
  `PostgresTestSupport#isAvailable` took the Testcontainers path — they were not skipped). Note the
  count moved 626 → 624: commit 5 replaced five now-obsolete `submit` tests with one, split one
  `create` test into two, and added one `actions` test.
- **Frontend:** lint **0 errors** (4 warnings, all pre-existing in `CommissionPage.jsx` /
  `PayrollPage.jsx`, untouched by this branch); **38 files / 229 tests all pass**, including
  `api/contract.test.js` (hrApi ↔ mockApi method-name parity in both directions); build clean,
  `PricingRequestQueuePage` code-splits into its own ~5 kB lazy chunk.
- **No `typecheck` was run — there is no such script in this repo.** Validation is
  `lint && test && build` only.

## Permission verification

Per CLAUDE.md, mock authz is **not** authoritative. Every role rule below was verified against the
Java service (`PricingRequestServiceTest`, 54 tests / `PricingRequestControllerTest`, 23 tests), not
against `mockApi.js`:

- sales (deal owner) may create / update-draft / submit / respond-information / cancel.
- import may pickup; **only the *assigned* import** may request-information (an unassigned import
  gets 403 and must pick up first).
- sales sees only pricing requests on deals it created — enforced on every read path via a single
  `requireViewable` helper mirroring `TicketService.requireViewAccess`.
- `sales_manager` stays read-only oversight; it is deliberately absent from both action role sets,
  per handoff 84.
- **One deliberate divergence:** `cancel` permits the deal owner **or the CEO**, whereas
  `TicketService.cancel` is owner-only. Without the override, a request whose originating rep has
  left the company would be uncancellable. Flagged here because it is a new permission, not a
  mirror of an existing one.

## Commit 7 + browser verification

`0065544 test(pricing)` adds `PricingRequestFlowIntegrationTest` (the full acceptance walk against
real Postgres — deal-with-3-products stays draft with zero notifications, two requests coexist,
submitting one leaves the other DRAFT and the deal unmoved, pickup keeps `sales.ticket.assigned_to`
NULL and the deal timeline at one event, info loop returns to IMPORT_REVIEWING with `picked_up_at`
byte-identical) and the `App.test.jsx` route guard for `/pricing-requests`.

### The browser pass found three defects the test suites did not

Driving the mock (`VITE_USE_MOCKS=true`) caught what unit tests structurally could not — they
covered the gate predicates and the API surface, not whether a control actually renders. Fixed in
`b229922`:

1. **Import could never request more information.** `api.pricingRequests.requestInformation`
   existed in all three API layers, `canRequestInformation` existed in `pricingRequestMeta.js`, and
   the backend implemented it — but **no component called it**. A request could therefore never
   reach `MORE_INFO_REQUIRED`, which also made the already-wired Sales respond path unreachable.
   Two Definition-of-Done boxes were silently failing.
2. **The mock was more permissive than production.** The create modal left `requestedUnit` blank
   (it did not seed from the deal item) and the mock happily accepted the submission, where the
   real backend rejects it — `@NotBlank String requestedUnit`. This is the issue-#199 failure mode:
   you only find out in prod. The modal now seeds the unit from `unitBasis` and the mock validates
   the required item fields like the Java request record does.
3. **Naming collision:** the deal breadcrumb still labelled a ticket "ใบขอราคา", the same words the
   new pricing-request panel uses. Now "ดีล".

### Verified live in the browser

Deal detail shows the panel after the items table with the correct empty state; the old ส่งขอราคา
button is gone; creating a request yields `PCR-2026-0002` in SUBMITTED; the Import queue lists it
and pickup assigns it; the `ขอข้อมูลเพิ่มเติม` action moves it to `MORE_INFO_REQUIRED`;
`DealStagePanel`'s การขอราคา strip appears only once a request exists and tracks its status; and
the row expansion renders **"ประวัติ (เฉพาะใบขอราคานี้)"** with that request's own events while the
deal timeline still shows only สร้างดีล — decision #4 confirmed end to end.

### What was NOT verified live

- **The Sales respond-information step.** It is wired (`PricingRequestPanel.jsx`) and unit-tested,
  and the Import half of the loop was driven live, but the final Sales→respond click was not
  performed in the browser (the SPA logout did not take, and reloading resets the mock store).
  Worth 60 seconds of manual confirmation before merge.
- **Nothing was verified against the real Java backend through the UI** — only against the mock.
  Permission behaviour is covered by the Java service/controller tests, not by this browser pass.

### Known UX observation (not a defect)

After Import picks up a request it disappears from the default queue view, because the default
filter is the SUBMITTED ("รอ Import รับเรื่อง") work queue and the request has left that state.
The data is correct and it is still reachable under the other filters, but there is no confirmation
feedback on pickup, so it reads as though something failed. Consider a toast, or defaulting the
queue to "everything assigned to me plus everything unclaimed".

## Known risks / uncertainties

- **No real contact picker.** `recipientContactId` exists in every DTO/schema but the create modal
  only ever sends `recipientLabel` (free text). `validateRecipientIdentifiable` accepts either, so
  this is spec-compliant, just less structured than a real customer-contact lookup would be.
- **No edit-draft UI.** A saved draft (บันทึกร่าง) can be submitted later from `PricingRequestPanel`,
  but its fields cannot be edited without going through `update()` directly — there's no modal for
  it. `PUT /pricing-requests/{id}` is implemented and contract-tested in all three API layers, just
  unused by any component yet.
- **Deep link on submit-notification is the ticket, not the request** (backend commit 3's own
  known limitation — `NotificationRepository.notifyByRole` hardcodes `/tickets/{ticketId}`).
  Preserved as-is in the mock.
- **Mock authz is not authoritative** (per CLAUDE.md) — `mockApi.js`'s `pricingRequests` namespace
  approximates `PricingRequestService`'s gates by inspection; verify permission behaviour against
  the Java service before relying on it.
### Carried forward from the plan — deliberately not fixed in this branch

1. **NOT INDEPENDENTLY DEPLOYABLE.** Commit `03b5ba9` 409s ticket-level submit, and the
   PricingRequest aggregate cannot yet produce a price (no factory quote, no CEO approval, no
   quotation). So a **newly created deal cannot be priced, quoted, or completed.** Worse than it
   first looks: `autoAdvanceStage(QUOTE_DESIGN_SIDE)` / `(QUOTE_BUYER)` fire only inside
   `generateQuotation()` (`TicketService.java:313/315`), and every downstream auto-advance chains
   off them — `ORDER_RECEIVED`, `DEPOSIT_RECEIVED`, `PROCUREMENT`, `DELIVERY_SCHEDULING`,
   `DELIVERED`, `CLOSED_PAID`. A new deal therefore **cannot legitimately reach the end of the
   14-stage pipeline at all**. Deals already at `submitted`/`in_review` are unaffected —
   `pickup()` still finds them. Gate any merge to `main`/`uat` on Steps 2-4 landing.
2. **The Render demo breaks on merge to `main`.** It deploys from `main` on the prod profile and is
   shown to customers; its pricing and close flows will dead-end for any deal created live.
3. **`source_ticket_item_id` silently dangles.** `TicketRepository.replaceItems()` does DELETE +
   re-INSERT, regenerating every `item_id`, so any pricing request created before a `proposePrice`
   loses its link. `ON DELETE SET NULL` keeps integrity; the reference is just lost. Not fixed here
   — `replaceItems` is business-logic-adjacent and belongs in its own change.
4. **Import has no "we cannot price this" exit** — only the more-info loop or `CANCELLED`, and
   cancelling is semantically Sales' call about a dead opportunity. Expect Import to misuse
   `CANCELLED`/`MORE_INFO_REQUIRED` as a parking state until a `DECLINED` status arrives with the
   factory-quote step.
5. **`GET /api/pricing-requests` has no pagination.** Default-excluding `CANCELLED` buys headroom;
   revisit when the queue is the primary surface.
6. **Notification deep-links are ticket-scoped.** `NotificationRepository` hardcodes
   `link = "/tickets/{id}"` and unmapped types fall back to the generic title
   "อัปเดตสถานะใบขอราคา". Fine while the panel lives on that page.
7. **UAT/main DB rebuild is the agreed path** (existing ticket data on both is throwaway), which
   dissolves the V58 out-of-order concern. Two boundaries: "safe to discard" scopes to `sales.*`
   only — the main Supabase project still holds real payroll/HR records, and V58 is purely
   additive so it deletes nothing; and when remigrating later, never edit an applied seed migration
   in place — add a forward-only `Vnnn` with env-distinct identifiers.
8. **Handoffs 82/83/84's claim that V55-V57 were verified against the full V1..V54+V900..V909 uat
   chain is not verifiable from this branch** — `db/migration-uat/` exists only on `uat`. Treat as
   unproven; the rebuild settles it.
9. **`sales_manager`** keeps read/oversight only — no new write permission was granted.

## Recommended next agent

Claude Opus review (verify the mock/hrApi/backend shape parity claims above against the actual
controller/service source, and browser-drive the mock: create a deal with no items → create a
pricing request → submit → switch to the import persona → pick up from `/pricing-requests` →
request more info → switch back to sales → respond → confirm `DealStagePanel`'s substep strip
tracks the request, not the ticket).

## Exact next prompt

```
Finish Step 1 of the sales pricing-request separation on branch
feat/sales-pricing-request-foundation (commits 1-6 are pushed; read
docs/agent-handoffs/85_feat-sales-pricing-request-foundation.md first). Two jobs:

A. Complete the plan's commit 7, which was never done:
   1. Add PricingRequestFlowIntegrationTest (extends AbstractPostgresIntegrationTest) walking the
      acceptance scenario end to end: create a deal with 3 items -> assert ticket.status='draft',
      one CREATED event and ZERO rows in hr.notification; create two pricing requests -> both DRAFT
      with distinct codes; submit only the first -> it is SUBMITTED with submitted_at set while the
      second is still DRAFT with null submitted_at, and ticket.status/sales_stage are unchanged;
      import picks up the first -> IMPORT_REVIEWING with assigned_import_id set while
      sales.ticket.assigned_to IS STILL NULL and sales.ticket_event still has exactly one row;
      request-info then respond -> picked_up_at byte-identical, status back to IMPORT_REVIEWING
      (not SUBMITTED).
   2. Add the App.test.jsx route-guard case for /pricing-requests.
B. Browser-drive the mock (VITE_USE_MOCKS=true, frontend-mock launch config) — this has NEVER been
   done for this slice. As `sales`: create a deal with zero items, confirm PricingRequestPanel
   prompts and the old ส่งขอราคา button is gone; create + submit a pricing request. Switch to
   `import`: pick it up from /pricing-requests, request more information. Back to `sales`: respond.
   Confirm DealStagePanel's "การขอราคา" strip tracks the pricing request's status throughout and is
   not stuck on the ticket's draft. Note: the mock session dies on reload, so navigate via pushState.

Also verify, per CLAUDE.md's "mock authz is not authoritative" rule, that mockApi.js's
pricingRequests gates match PricingRequestService's actual gates, and flag any divergence however
small — a mock that is MORE permissive than production is the dangerous direction (issue #199).

Do NOT merge to main or uat: this branch is not independently deployable (handoff risk #1).
```
