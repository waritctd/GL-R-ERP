# 85 — feat/sales-pricing-request-foundation

**Branch:** `feat/sales-pricing-request-foundation` (branched off `feat/sales-flow-remediation-part1`, not off `main`) · **Migrations:** V59 pricing foundation + V60 product-description/idempotency after syncing main's V55 attendance migration
**Date:** 2026-07-20 · **Status:** Synced with `origin/main`, pricing migrations renumbered after main's V55, and pricing-request SIT/full validation green. Backend **741 tests green**, frontend **298 tests green**.

## Review remediation (2026-07-20)

An external review returned *Request changes* with ~12 findings. It audited the stale range
`1415774..fe44ae4`, which predated four pushed commits, so four findings were already fixed or
wrong: the Import request-information UI (fixed in `b229922`), the missing multi-request acceptance
test (`PricingRequestFlowIntegrationTest`), the untracked/stale handoff, and the breadcrumb, which
already read ดีล. Every other finding was verified against the code and fixed:

| Commit | Fixes |
|---|---|
| `2701a83` | **Draft privacy** — a DRAFT was readable by import/account/ceo/sales_manager, contradicting this service's own javadoc, with a test locking it in. Now owner + ceo + sales_manager only, enforced across all three read paths, returning **404 not 403** so a non-owner cannot probe ids. Also added the missing `requireActive` guard to updateDraft/requestInformation/respondInformation. |
| `20eefa4` | **Product identity** — an item with every identifying field null was creatable and submittable. Now requires a source item, catalog product, model, or description (brand alone is insufficient), re-checked on submit against persisted items. **Contact ownership** — `recipientContactId` was never checked against the deal's customer. |
| `e29bd5f` | **Duplicate drafts** on create-then-submit retry; **draft editing**, which had no UI at all despite the endpoint existing; **updateDraft full replacement**, since COALESCE meant null could never clear a field; **deal summary** no longer reduces every request to the highest-id one. |
| `2aa6103` | **Mock parity** (`revisionNo: 0` violated a DB CHECK; currency normalisation; queue leaked drafts after `2701a83`); **legacy copy**; **cascade retry**; dashboard tile; `PRICING_REQUEST_SUBMITTED` notification type. |
| `8289097` | **CLAUDE.md** — recorded that this release intentionally changes business logic, permissions, contracts and schema within sales/CRM, with the guardrails that do not relax. |
| `042f9cb` | Coverage for the Sales respond branch (previously **zero**), the full pickup→info→respond sequence, and updateDraft's validation, which had 3 of ~9 branches tested. |

**Permission changes made deliberately** (per the new CLAUDE.md section): DRAFT visibility is owner +
ceo + sales_manager; CEO may cancel a pricing request although `TicketService.cancel` is owner-only.
Both are verified in `PricingRequestServiceTest`, not the mock.

### Browser pass — what was and was not confirmed live

Confirmed by driving `VITE_USE_MOCKS=true`: the ดีล breadcrumb; the item unit seeding from the deal;
per-row product-identity validation (the error attaches to "รายการที่ 2", not a form banner); draft
creation; and the แก้ไขร่าง action appearing on a sales-owned draft.

**Not driven live:** role switching. The SPA logout control was unreachable in the harness's viewport
state, so the import/ceo draft-privacy views and the Sales respond click were not clicked through.
This is a smaller gap than it sounds — **CLAUDE.md forbids treating mock behaviour as evidence for a
permission rule**, so the browser could not have been the authority for draft privacy anyway. Those
rules are covered by the Java service tests and the `PricingRequestFlowIntegrationTest` sequence.
Worth a few minutes of manual click-through before merge regardless.

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
   dissolves the old pricing-request migration-order concern. Two boundaries: "safe to discard" scopes to `sales.*`
   only — the main Supabase project still holds real payroll/HR records, and V59/V60 are purely
   additive so they delete nothing; and when remigrating later, never edit an applied seed migration
   in place — add a forward-only `Vnnn` with env-distinct identifiers.
8. **Handoffs 82/83/84's claim that V56-V58 were verified against the full V1..V55+V900..V909 uat
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

## Review-remediation Commits A + B (2026-07-19/20)

After the recommended-next-agent review pass above, two more fix commits landed on this branch
addressing findings from that review:

- **Commit A** `2701a83` `fix(pricing): make drafts private and enforce deal lifecycle` — DRAFT
  visibility narrowed to owning sales rep + ceo/sales_manager (import/account see it only once
  SUBMITTED, across get/list/listForTicket); unauthorised DRAFT read now 404s (not 403, to avoid id
  enumeration); `requireActive` gap closed on `updateDraft`/`requestInformation`/`respondInformation`
  (cancel/cancelOpenForTicket deliberately still work on a dead deal). Already landed before this
  handoff update; see the commit message for full detail.
- **Commit B** (this update) — two more review findings, implemented in full:

### Part 1 — an unidentified product could be submitted

`validateItems` previously only checked `quantityType` membership; every product-identifying field
(`sourceTicketItemId`, `productId`, `brand`, `model`, `color`, `texture`, `size`, `factory`,
`specialRequirement`) was nullable and unchecked, so Sales could submit a line that said nothing
more than "1 แผ่น".

- `PricingRequestService.validateItems` now requires each item to satisfy
  `sourceTicketItemId != null || productId != null || hasText(model) || hasText(specialRequirement)`
  — **brand alone is not sufficient**. 400 names the offending 1-based line
  (`"รายการที่ N: ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)"`).
- `submit()` re-checks the same predicate against the **persisted** items
  (`requests.findItems(id)`, `PricingRequestItemDto`), not the create/update payload
  (`PricingRequestItemRequest`) — a draft created before this rule existed must not be submittable
  after it. The two record types have no shared interface, so the shared logic is a private static
  predicate, `isProductIdentified(Long sourceTicketItemId, Long productId, String model, String
  specialRequirement)`, that both `validateItems` and `submit`'s item loop call after each extracts
  its own type's fields.
- Mirrored in `PricingRequestCreateModal.jsx` (`itemIdentityValid`, called from a new
  `validateItemIdentities()`) as a **per-row** error — unlike the modal's other validations (a single
  form-level banner), this one is attached to the specific line via a new `itemErrors` state so the
  user knows which row is incomplete.
- Mirrored in `mockApi.js`'s `requirePricingRequestItemFieldsValid` (now indexed, with the identity
  check added inline, tagged `// Mirrors PricingRequestService.validateItems`); the mock's `submit()`
  now also re-runs this helper against `pr.items` before transitioning, mirroring the backend's
  persisted-item recheck.

**Existing-fixture impact: zero.** Every existing backend test fixture (`sampleItem`,
`sampleItemRequest` in `PricingRequestServiceTest`; the `item()` helpers in
`PricingRequestRepositoryIntegrationTest` and `PricingRequestFlowIntegrationTest`) already sets both
`brand` and `model`, so all 68 pre-existing tests in `PricingRequestServiceTest` passed unmodified —
no fixture needed fixing, no assertion was loosened. Same on the frontend: the create modal's test
fixture (`ticketItem()`) seeds `sourceTicketItemId` + `model`, so the three pre-existing modal tests
were untouched.

### Part 2 — `recipientContactId` was never checked against the deal's customer

`validateRecipientIdentifiable` only asserted that *one of* `recipientContactId`/`recipientLabel` was
present — nothing verified the contact actually belongs to the ticket's customer, so a pricing
request on Customer A's deal could name Customer B's contact.

- `ContactRepository` gained a public `findById(long) -> Optional<ContactDto>`, extracted from the
  inline SQL that used to live only inside `create()` (`create` now calls `findById` instead of a
  second query).
- New `PricingRequestService.validateRecipientContactBelongsToCustomer(Long recipientContactId,
  TicketSummaryDto ticket)`: when `recipientContactId != null`, loads the contact and requires
  `contact.customerId() == ticket.customerId()`; a non-existent contact id is treated the same as a
  mismatch (both 400 `"ผู้รับที่เลือกไม่ได้อยู่ในลูกค้าของดีลนี้"`). **Skips the check when
  `ticket.customerId()` is null** (an older deal with no customer link) rather than throwing — no
  customer to compare against, not "any contact is fine". Applied on `createDraft`, `updateDraft`
  (only when the request actually touches `recipientContactId`/`recipientLabel`, same joint-invariant
  pattern as the existing `validateRecipientIdentifiable` recheck), and `submit()` (against the
  persisted `summary.recipientContactId()`, for the same before-the-rule-existed reason as Part 1).
- **Backend-only.** The task scope for Part 2 explicitly did not include a `mockApi.js` mirror, and
  there is currently no reachable path to it anyway: the create modal has no contact picker yet
  (`recipientContactId` is always null from the UI — see "Known risks" above), so this is a real but
  currently-dead gap in the mock's permissiveness. Flagging per CLAUDE.md's mock-parity rule: **if a
  contact picker ships before the mock gets this check, add it then** — a mock more permissive than
  production is the dangerous direction (issue #199).
- Tests added to `PricingRequestServiceTest`: reject/accept/unknown-id/null-customerId-skip on
  `createDraft`, plus the "another customer" rejection on `updateDraft` and `submit`. All existing
  tests were unaffected because `stubTicket`'s original 3-arg overload always set `customerId = null`
  (a new 4-arg overload was added for the tests that need a non-null customer), so the new check was a
  no-op for every pre-existing scenario.

### Files changed (Commit B)

- `backend/src/main/java/th/co/glr/hr/customer/ContactRepository.java` — added `findById`, `create`
  reuses it.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestService.java` — constructor now
  takes `ContactRepository`; `isProductIdentified`/`hasText`/`identityErrorMessage` helpers;
  `validateItems` and `submit()` both call the identity predicate; new
  `validateRecipientContactBelongsToCustomer`, called from `createDraft`/`updateDraft`/`submit`.
- `backend/src/test/java/th/co/glr/hr/pricingrequest/PricingRequestServiceTest.java` — `ContactRepository`
  mock added to the constructor call; `stubTicket` 4-arg overload (customerId); `itemRequestWithIdentity`/
  `itemDtoWithIdentity` helpers; 13 new tests (6 item-identity, 1 submit-recheck, 6 contact-ownership).
- `backend/src/test/java/th/co/glr/hr/pricingrequest/PricingRequestFlowIntegrationTest.java` — constructor
  call updated to pass a real `ContactRepository(jdbc)` (no behavioural change — both create requests in
  this file use `recipientLabel`, not `recipientContactId`, so Part 2's check never fires here).
- `frontend/src/features/pricingRequests/PricingRequestCreateModal.jsx` — `itemIdentityValid`,
  `itemErrors` state, `validateItemIdentities()`, per-row error rendering, `updateItem` clears a row's
  error when its identity fields change.
- `frontend/src/features/pricingRequests/PricingRequestCreateModal.test.jsx` — 2 new tests (blocks +
  shows per-row error; clears on fixing the model and allows submission).
- `frontend/src/api/mockApi.js` — `requirePricingRequestItemFieldsValid` gained the identity check
  (indexed loop) and is now also called from `submit()`.
- `frontend/src/api/mockApi.pricingRequests.test.js` — 2 new tests (rejects brand-only item, accepts
  specialRequirement-only item) + a comment explaining why a submit()-recheck mock test isn't
  reachable (`delay()` uses `structuredClone`, so a returned item can't be mutated back into the
  mock's own store — that scenario is exercised on the backend instead).

### Commands run

```bash
cd backend  && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build
```

### Test / build results

- **Backend:** 658 tests, 0 failures, 0 errors, 0 skipped (baseline before Commit B: 645 — the +13
  delta is exactly the new tests listed above; no existing test was modified or weakened). BUILD
  SUCCESS, Jacoco gate met.
- **Frontend:** lint 0 errors / 4 pre-existing warnings (unrelated files, untouched by this commit);
  246 tests passed (baseline before Commit B: 242 — the +4 delta is exactly the new tests listed
  above); build clean.

### Known risks / uncertainties (Commit B)

- Part 2 is backend-only; see "Backend-only" note above — the mock will silently accept a
  cross-customer `recipientContactId` until either a contact picker ships or the mock gets the same
  check added defensively ahead of that.
- Did not re-verify Commit A's claims independently in this pass — it was already committed and
  green when this session started; only Commit B is newly authored here.
- Not committed or pushed (per the task instructions for this session) — working tree has the Commit
  B changes uncommitted on top of `2701a83`.

## Main sync + SIT gate (2026-07-20)

### Merge outcome

- Fetched `origin` and merged `origin/main` into `feat/sales-pricing-request-foundation`.
- Git reported exactly one textual conflict: `CLAUDE.md`.
- Resolved `CLAUDE.md` by keeping both appended guidance sections: the existing backend
  Testcontainers validation note and main's new authorization-evidence requirement. No business
  logic was hand-resolved.
- Confirmed no unmerged files remained after resolving `CLAUDE.md`.

### Migration renumber

Main added `backend/src/main/resources/db/migration/V55__attendance_daily_activation.sql`; that file
was kept as-is. The pricing branch's unapplied migrations were moved forward with `git mv`:

- `V55__close_verification.sql` -> `V56__close_verification.sql`
- `V56__cancel_reason.sql` -> `V57__cancel_reason.sql`
- `V57__audit_trail_integrity.sql` -> `V58__audit_trail_integrity.sql`
- `V58__pricing_request_foundation.sql` -> `V59__pricing_request_foundation.sql`
- `V59__pricing_request_product_description_idempotency.sql` -> `V60__pricing_request_product_description_idempotency.sql`

Only prose/comment references were updated after:

```bash
rg -n "V55|V56|V57|V58|V59" docs/ backend/src/test/ backend/src/main/java/
```

### Pricing-request SIT coverage

`PricingRequestFlowIntegrationTest` now proves the requested flows through the real Java service and
real Postgres, never via `mockApi.js` authz:

- Deal creation excludes Import notification recipients:
  `createDeal_withThreeItems_startsAsDraftWithOneCreatedEventAndNoNotifications`
- DRAFT visibility is private to owner/CEO/Sales Manager and hidden from Sales B + Import across list
  and direct-read boundaries:
  `draftPricingRequest_isPrivateToOwnerAndOversightUntilSubmitted`
- Submit hands off to Import, Import can see/pick up, and the owner sees the state change:
  `submittedPricingRequest_handsOffToImportAndReflectsPickupToOwner`
- Information loop records Import request + Sales response and blocks no-stake principals:
  `informationLoop_recordsBothTurnsAndRejectsPrincipalsWithNoStake`
- Accounting can open deal/payment information but cannot read pricing requests:
  `accountingCanReadDealPaymentInfoButCannotReadPricingRequests`
- Idempotency is proven sequentially and under a two-thread race:
  `sequentialCreateDraft_sameClientRequestId_returnsOneRequestWithOneItemSetAndOneCreatedEvent`
  and `concurrentCreateDraft_sameClientRequestId_returnsOneRequestWithOneItemSetAndOneCreatedEvent`

Full SIT report from the backend verify XML: 13 methods in
`PricingRequestFlowIntegrationTest`, all passed, 0 skipped.

### Commands run

```bash
git status --short --branch
git fetch origin
git merge origin/main
git diff --name-only --diff-filter=U
git mv backend/src/main/resources/db/migration/V59__pricing_request_product_description_idempotency.sql backend/src/main/resources/db/migration/V60__pricing_request_product_description_idempotency.sql
git mv backend/src/main/resources/db/migration/V58__pricing_request_foundation.sql backend/src/main/resources/db/migration/V59__pricing_request_foundation.sql
git mv backend/src/main/resources/db/migration/V57__audit_trail_integrity.sql backend/src/main/resources/db/migration/V58__audit_trail_integrity.sql
git mv backend/src/main/resources/db/migration/V56__cancel_reason.sql backend/src/main/resources/db/migration/V57__cancel_reason.sql
git mv backend/src/main/resources/db/migration/V55__close_verification.sql backend/src/main/resources/db/migration/V56__close_verification.sql
rg -n "V55|V56|V57|V58|V59" docs/ backend/src/test/ backend/src/main/java/
git diff --check
docker info >/dev/null 2>&1 && echo "DOCKER: up — Testcontainers WILL run" || echo "DOCKER: DOWN — SIT/Flyway will SKIP, do not merge"
cd backend && ./mvnw -Dtest=PricingRequestFlowIntegrationTest test
cd backend && ./mvnw -B clean verify
rg '<testcase ' backend/target/surefire-reports/TEST-th.co.glr.hr.pricingrequest.PricingRequestFlowIntegrationTest.xml
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
```

### Test / build results

- Docker/Testcontainers preflight: `DOCKER: up — Testcontainers WILL run`.
- Focused backend SIT: `PricingRequestFlowIntegrationTest` ran on Testcontainers Postgres with
  Flyway V1..V60; 13 tests, 0 failures, 0 errors, 0 skipped.
- Backend full verify: `./mvnw -B clean verify` ran Testcontainers Postgres and Flyway through
  `V60__pricing_request_product_description_idempotency.sql`; 741 tests, 0 failures, 0 errors,
  0 skipped; BUILD SUCCESS; Jacoco gate met.
- Frontend lint: 0 errors, 3 warnings in existing commission/payroll hook-dependency lines.
- Frontend tests: 42 files, 298 tests passed, including `src/api/contract.test.js`.
- Frontend build: `vite build` succeeded.

### Files changed in this sync pass

- `CLAUDE.md` merge resolution: kept both validation/authz guidance sections.
- Migration filenames V56-V60 as listed above; main's V55 attendance migration kept unchanged.
- Prose/comment references in handoffs 82/83/84/85 and Java comments updated to the new migration
  numbering.
- `PricingRequestFlowIntegrationTest.java` extended with the real-service permission, visibility,
  notification, handoff, bidirectional-info-loop, accounting, and idempotency/race coverage.
- `origin/main` attendance/employees/profile changes were carried in by merge without hand editing.

### Known risks / uncertainties

- No pricing-request flow was left unverified by the Java SIT gate in this pass.
- The historical product-scope risks below still apply: the pricing-request foundation does not yet
  implement the later factory quote -> CEO approval -> quotation rebuild chain.
- User requested a PR for human review and explicitly said not to merge to `main` in this session.

### Next prompt

Review the PR for `feat/sales-pricing-request-foundation`. If approved, merge it deliberately; if
not, address review feedback on the same branch. After this lands, continue the next pricing-chain
slice: factory quote -> CEO approval -> quotation rebuild.

### Recommended next agent

Whoever picks up Step 2-4 of the pricing-request separation (factory quote → CEO approval →
quotation rebuild) per "Known risks" #1 above — this branch is still not independently deployable.
If a real contact picker is wired into `PricingRequestCreateModal` before that, add the
`recipientContactId`-ownership mirror to `mockApi.js` at the same time (see Part 2's "Backend-only"
note).
