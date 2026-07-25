# Phase 5A Plan - Ticket Workspace Repair

Date: 2026-07-25
Target branch: `refactor/ui-phase-5-ticket-workspace` (to be created from `origin/main`)
Surface: `/tickets/:id` — `frontend/src/features/tickets/TicketDetailPage.jsx`

This plan covers the **ticket workspace** (deal detail). Phase 4A covered the ticket
**worklist** (`/tickets`) and is recorded in `../04-production-repair/`. Phase 5 docs live in
this `05-ticket-workspace/` folder by explicit instruction; the folder split is deliberate, not
a drift from the 4A convention.

**No production code may be modified until this plan is complete and approved.** This document
is the gate.

## Preflight Record

- Working tree at plan time: clean, on `refactor/ui-phase-4-ticket-worklist`.
- `git rev-list --left-right --count origin/main...HEAD` → `1 7`. `git diff --stat origin/main
  HEAD -- frontend/src` is **empty**: Phase 4A is fully merged into `origin/main` as the squashed
  commit `c345e1c refactor(ui): repair ticket worklist and shared table interactions (#319)`.
  **Phase 5 therefore branches from `origin/main`, not from the 4A branch.** Per the standing
  rule, re-fetch and rebase onto the latest `origin/main` before opening the PR.
- Phase 0–3 docs are tracked under `docs/ui-repair/` on `origin/main` (commit `c198380`,
  PR #318); Phase 3.4's token infrastructure is `596ab66`.
- Package scripts (`frontend/package.json`): `lint`, `test`, `build`, `test:e2e`, `audit`.
  There is **no `typecheck` script** — this is a plain JS project.
- Playwright: `frontend/playwright.config.js` — `testDir: './e2e'`, chromium only,
  `workers: 1`, `fullyParallel: false`, mock Vite server at `127.0.0.1:5250` with
  `VITE_USE_MOCKS=true`, `reuseExistingServer: false`.
- Baseline to re-record at implementation start (Phase 4A's closing figures, for comparison):
  `npm run lint` pass with 1 pre-existing warning at
  `frontend/src/features/payroll/PayrollPage.jsx:312:6` (`react-hooks/exhaustive-deps`);
  `npm test` 66 files / 612 tests; `npm run build` clean; `npm run test:e2e` 64 specs.
  **Re-run and re-record these before touching anything** — do not inherit them from 4A.
- Dead cross-reference to record, not fix: `00-governance/UI_REPAIR_RULES.md` and root
  `AGENTS.md` both point at `frontend/.claude/rules/frontend-ui.md` as the frontend design
  charter. **That file does not exist in this tree.**

## Current Ticket-Detail Structure

Route chain: `frontend/src/App.jsx:358-361` (inside the `SALES_ENABLED` block, wrapped in
`RequireAccess`) → `TicketDetailRoute` (`App.jsx:59-73`) → `TicketDetailPage`.

Props: `{ user, ticketId, onBack, showToast }`. `ticketId` comes from `useParams().id`;
`onBack` is `() => navigate(-1)` — deliberately *not* a fixed `/tickets`, so the list's URL
filters (`q`, `phase`, `life`, `flag`, `inbox`) survive the round trip. **Preserve this.**

Route guard: `RequireAccess` → `canAccessPath` (`frontend/src/app/permissions.js`), rule
`p.startsWith('/tickets/')` → `hasPermission(role, 'canViewTickets')`.

### Module scope (`TicketDetailPage.jsx:1-143`)

| Lines | What |
|---|---|
| 1-32 | Imports — react-query, `api`/`ROLE_PERMISSIONS`, `queryKeys`, 8 shared components, format utils, 7 ticket children, `salesViewScope` |
| 34-79 | `EVENT_KIND_LABEL` — Thai labels for ~40 event kinds |
| 84-91 | `PAYMENT_TRACK_KINDS` / `FULFILLMENT_TRACK_KINDS` — timeline dot tone sets |
| 93 | `TERMINAL = ['closed','cancelled']` |
| 99-107 | `docStatusColors(docStatus)` |
| 109-115 | `eventDotClass(kind)` |
| 117-124 | `InfoRow` — label/value row, inline styles |
| 133-143 | `SectionPeek` — the one-line stand-in for a role-hidden section |

### Component logic (`:145-785`)

| Lines | What |
|---|---|
| 149-240 | Local state: edit-items (`editMode`/`editDraft`/`editNote`), revise form, `fieldErrors` + `fieldRefs` (+ `clearFieldError`, `setFieldError`, `setFieldErrorsForPrefix`, `focusFirstInvalid`), `paymentModal`/`paymentDraft`, `billingModal`/`billingDraft`, `commentText`, `confirm` discriminator, `downloadingQuotationKey`, `downloadingInvoice` |
| 242-310 | 6 `useQuery` calls (see Query Inventory) |
| 323-331 | `applyTicketUpdate()` — the shared post-mutation cache write |
| 334-348 | `resetActionDrafts()` |
| 353-371 | `actionMutation` + the `doAction(fn, msg)` wrapper |
| 375-401 | attachment upload / delete mutations |
| 405-427 | `updateTrackingMutation`, `addActivityMutation` |
| 431-434 | `refreshTicket()` |
| 436-468 | **Loading state** — full-page skeleton (header + 2 panel skeletons), gated on `isLoading` only (not `isFetching`) so a background refetch never flashes it |
| 470-480 | **Not-found state** — `EmptyState` "ไม่พบดีล" + back button |
| 482-532 | Derived values: destructure `{ summary, items, events, quotations }`, `sections = visibleSections(role)`, `isOwner`, `showProposed`/`showApproved`/`showCalcBreakdown`, `itemsGridCols`, delivery totals, quotation sorting → `latestQuotation` / `quotationGroups` |
| 537-569 | The `can` map — 12 flags |
| 575-577 | `hasActions` — whether "การดำเนินการอื่น ๆ" renders at all |
| 586-599 | `NEXT_ACTION_STEPS` → `nextAction` text |
| 607-614 | `waitingHint` text |
| 626-649 | `primaryAction` — the single CTA node, 4 mutually exclusive branches |
| 651-784 | 12 handlers (upload, delete, final payment, 2 downloads, comment, record payment, set billing, open/close modals) |

### Render tree (`:786-1843`), in DOM order

| Lines | Region |
|---|---|
| 788 | `<Breadcrumbs>` — ดีล → deal code |
| 789-792 | Full-width back button (**F-14**) |
| 799-806 | `<DealStateHeader>` — code/customer/lifecycle + existing equal-weight stat-chip/card treatment to retire + "ถึงคิวคุณ" callout hosting `primaryAction` + refresh |
| 807-814 | Status meta row — `StatusBadge`, "มีการแก้ไข", creator/date, assignee |
| 820-865 | `<DealStagePanel>` — 14-stage cockpit; `docActions` slot built inline at 838-864 |
| 871-885 | `<DealTrackingPanel>` \* |
| 887-957 | **"การชำระเงิน"** — inline, ~70 lines: 3 money tiles, billing dates, 2 buttons, receipt history \* |
| 971-1081 | **"การดำเนินการอื่น ๆ"** — inline, ~110 lines: draft hint, ขอแก้ไข, แก้ไขรายการสินค้า, revoke-close, ยกเลิก + the inline revise form (1019-1079) |
| 1083-1665 | `.ticket-detail-grid` — CSS grid `2fr 1fr`, collapses to 1 column at 900px (`styles.css:1707-1724`) |
| ├ 1085-1098 | "ข้อมูลทั่วไป" — 7 × `InfoRow` |
| ├ 1100-1383 | **"รายการสินค้า"** — ~283 lines: edit mode (1105-1312) / read mode (1314-1382), 3 column variants |
| ├ 1385-1391 | `<PricingRequestPanel>` \* |
| ├ 1399-1401 | `<DealQuotationPanel>` |
| ├ 1409-1420 | `<DealDepositPanel>` \* |
| ├ 1430-1442 | `<DealFulfilmentPanel>` \* |
| ├ 1445-1532 | "ไฟล์แนบ" — 2 upload controls, skeleton, empty state, file rows |
| ├ 1534-1599 | "ใบเสนอราคา (เอกสารเดิม)" — read-only legacy rows, grouped by recipient \* |
| └ 1602-1664 | "ประวัติการดำเนินการ" — reversed event list + comment composer |
| 1667-1732 | Payment `Modal` |
| 1734-1779 | Billing `Modal` |
| 1784-1792 | `ConfirmDialog` — delete attachment |
| 1796-1805 | `CancelDealModal` |
| 1807-1840 | `ConfirmDialog` — final payment |

`\*` = wrapped in `sections.<id>` from `salesViewScope.js`, falling back to `SectionPeek`.

Three styling systems are mixed on this page: legacy `.panel` / `.table-panel` /
`.ticket-detail-grid` classes from `styles.css` (now 3,090 lines), Tailwind utilities in the
newer children (`DealStateHeader`, `DealStagePanel`), and heavy inline `style={{…}}` objects in
the payment panel, items table, attachments and event list — roughly **600 of the 1,843 lines**.

## Query Inventory

Every key comes from `frontend/src/api/queryKeys.js`. **All keys are preserved verbatim.**

| # | Line | Key | queryFn | enabled |
|---|---|---|---|---|
| 1 | 242-246 | `['tickets','detail',id]` | `api.tickets.get(id).then(r => r.ticket)` | `!!ticketId` |
| 2 | 248-256 | `['tickets','actions',id]` | `api.tickets.actions(id)` | `!!ticketId && !!ticket`; **`placeholderData: (prev) => prev`** |
| 3 | 267-271 | `['tickets','payments',id]` | `api.tickets.listPayments(id).then(r => r.items ?? [])` | `!!ticketId && !!ticket` |
| 4 | 280-284 | `['pricingRequests','byTicket',id]` | `api.pricingRequests.listForTicket(id).then(r => r.items ?? [])` | `canViewPricingRequests && !!ticketId && !!ticket` |
| 5 | 291-295 | `['tickets','activities',id]` | `api.tickets.listActivities(id).then(r => r.items ?? [])` | `canViewDealTracking && !!ticketId && !!ticket` |
| 6 | 302-306 | `['tickets','attachments',id]` | `api.attachments.list(id).then(r => r.attachments ?? [])` | `!!ticketId` |

The `placeholderData` on #2 is load-bearing: every action invalidates `ticketActions`, and
without it the whole action surface blanks for a beat on each mutation. **Keep it.**

Child-owned queries (untouched by this phase):

| Component:line | Key |
|---|---|
| `DealDepositPanel.jsx:106` | `['depositNotices', ticketId]` |
| `DealFulfilmentPanel.jsx:131` | `['tickets','deliveries',id]` |
| `DealFulfilmentPanel.jsx:144` | `['pricingRequests','factoryPurchaseOrders',prId]` |
| `DealQuotationPanel.jsx:67` | `['pricingRequests','customerQuotations',prId]` |
| `PricingRequestPanel.jsx:47` | `['pricingRequests','byTicket',ticketId]` (shares #4's cache entry) |
| `PricingRequestPanel.jsx:54,63` | `['pricingRequests','detail',id]` ×2 |

A CEO opening a deal runs ~11-13 live queries. Moving a panel behind a tab **changes when its
query mounts**. That is the single biggest behavioural risk in this phase — see Risks.

## Mutation Inventory

| Line | Mutation | Endpoint | onSuccess |
|---|---|---|---|
| 353-361 | `actionMutation` (generic, 18 call sites) | caller-supplied `api.tickets.*` | `applyTicketUpdate(res.ticket)` + toast + `resetActionDrafts()` |
| 375-387 | `uploadAttachmentMutation` | `POST /api/tickets/:id/attachments` | invalidate `ticketAttachments`, `ticketActions`, **`queryKeys.ticket(id)` ← broken** + toast |
| 390-400 | `deleteAttachmentMutation` | `DELETE /api/attachments/:id` | same three + toast |
| 405-412 | `updateTrackingMutation` | `PUT /api/tickets/:id/tracking` | `applyTicketUpdate(res.ticket)` |
| 418-427 | `addActivityMutation` | `POST /api/tickets/:id/activities` | invalidate `ticketActivities`, `ticketDetail`, `['tickets','list']` |

`applyTicketUpdate()` (`:323-331`) — **preserve exactly**:
`setQueryData(ticketDetail(id), updatedTicket)`, then invalidate `ticketActions(id)`,
`ticketPayments(id)`, `ticketDeliveries(id)`, `['tickets','list']`, `dashboardSummary()`,
`notifications()`.

Non-mutation downloads keep their own local busy flags (no cache): `handleDownloadQuotation`
(`:689-702`), `handleDownloadRemainingInvoice` (`:704-714`).

Child-owned mutations, each with its own invalidation set — **all untouched**:
`DealDepositPanel` ×5, `DealFulfilmentPanel` ×7, `DealQuotationPanel` ×4,
`PricingRequestPanel` ×4.

## Action Inventory

Source: `GET /api/tickets/:id/actions` → `TicketService.actions()`
(`backend/src/main/java/th/co/glr/hr/ticket/TicketService.java:1646-1676`). Shape:
`{ currentState: {lifecycle, salesStage, paymentStatus, fulfillmentStatus, status},
availableActions: [{ action, category, label, targetStage|fields }] }`.

Consumption (`:257-259`):

```js
const availableActions = actionsQuery.data?.availableActions ?? [];
const actionNames = new Set(availableActions.map(a => a.action));
const hasAction = (action) => actionNames.has(action);
```

The raw array is also passed to `DealStagePanel` (`:824`), `DealDepositPanel` (`:1414`) and
`DealFulfilmentPanel` (`:1436`), each of which re-implements a local `hasAction`.

Server vocabulary — 28 actions across 7 categories (`payment`, `doc`, `fulfillment`,
`operational`, `stage`, `policy`, `lifecycle`): `CONFIRM_CUSTOMER`, `ISSUE_DEPOSIT_NOTICE`,
`DEPOSIT_PAID`, `RECORD_PAYMENT`, `SET_BILLING`, `ISSUE_IMPORT_REQUEST`, `IR_SENT`, `SHIPPING`,
`GOODS_RECEIVED`, `RESERVE_STOCK`, `RECORD_PARTIAL_DELIVERY`, `COMPLETE_DELIVERY`,
`FINAL_PAYMENT`, `CONFIRM_CLOSE`, `REVOKE_CLOSE_CONFIRM`, `VERIFY_CLOSE`, `CANCEL`,
`EDIT_ITEMS`, `ADVANCE_STAGE` (carries `targetStage`), `UPDATE_STAGE`,
`SET_TENDER_REQUIREMENT`, `SET_ENTRY_CHANNEL`, `WAIVE_DEPOSIT`, `MARK_LOST`, `PLACE_ON_HOLD`,
`MARK_DORMANT`, `RESUME`, `REOPEN`.

`SET_ENTRY_CHANNEL` is offered by the server and consumed **nowhere** in the frontend. Record
it; do not build a control for it in this phase.

**Non-negotiable rule: server `availableActions` stays authoritative.** No frontend
approximation may widen it, and the new work-state classifier may never report
*Needs-my-action* for an action the server did not offer.

### The 12 `can` flags (`:537-569`)

| `can.*` | Gate | Rendered at | Effect |
|---|---|---|---|
| `revise` | status ∈ approved/quotation_issued/document_issued + `canCreateTickets` + `isOwner` (**no `hasAction`**) | 982-988 → form 1019-1079 | `POST /revision` |
| `confirmClose` | `hasAction('CONFIRM_CLOSE')` | `primaryAction` 636-642 | `close/confirm` |
| `revokeCloseConfirm` | `hasAction('REVOKE_CLOSE_CONFIRM')` | 1003-1009 | `close/revoke` |
| `verifyClose` | `hasAction('VERIFY_CLOSE')` | `primaryAction` 643-648 | `close/verify` |
| `cancel` | `hasAction('CANCEL')` + not terminal + `isOwner` | 1010-1016 | `CancelDealModal` → `POST /cancel` |
| `comment` | not terminal (**no `hasAction`**) | 1649-1663 | `POST /comments` |
| `editItems` | `hasAction('EDIT_ITEMS')` + `EDITABLE_STATUSES` + `canCreateTickets` + `isOwner` | 990-1001 | `PATCH /items` |
| `confirmCustomer` | `hasAction('CONFIRM_CUSTOMER')` + status + paymentStatus + `isSales` | `primaryAction` 626-630 | `POST /confirm-customer` |
| `confirmFinalPayment` | `hasAction('FINAL_PAYMENT')` + status + `isAccount` | `primaryAction` 631-635 | `ConfirmDialog` → `POST /final-payment` |
| `recordPayment` | `hasAction('RECORD_PAYMENT')` + `isAccount` | 919-923 | payment `Modal` |
| `setBilling` | `hasAction('SET_BILLING')` + `isAccount` | 924-928 | billing `Modal` |
| `downloadRemainingInvoice` | status + fulfillmentStatus + `isSales` (**no `hasAction`**) | 857-862 | blob download |

Three flags (`revise`, `comment`, `downloadRemainingInvoice`) are status/role-derived with no
`hasAction` check. That is **existing behaviour and is preserved as-is** — this phase does not
tighten or loosen it.

## Step 2 - Role / Action Inventory

Roles that can reach `/tickets/:id` at all: `canViewTickets = ['sales','import','ceo','account','sales_manager']`
(`frontend/src/api/routes.js`, mirrored by `TicketService.VIEWER_ROLES`). This inventory is
verified against the frontend route guard, `visibleSections`, server `availableActions`, Java
service gates, and existing authz tests. It is **not** inferred from mock data.

Evidence anchors:

- Frontend route guard: `frontend/src/app/permissions.js` gates `/tickets/:id` with
  `canViewTickets`; `permissions.test.js` proves import/account keep detail read while losing the
  pipeline browser.
- Presentation projection: `frontend/src/features/tickets/salesViewScope.js` and
  `salesViewScope.test.js`.
- Server action vocabulary and role gates: `TicketService.actions()` plus `SALES_ROLES`,
  `IMPORT_ROLES`, `ACCOUNT_ROLES`, `CLOSE_CONFIRM_ROLES`, `CEO_ROLES`, `FULFILMENT_ROLES`,
  `VIEWER_ROLES`, `requireViewAccess`, `requireStageWriteAccess`, and `requireDealOwnership`.
- Supporting Java gates: `PricingRequestService.requireViewable`, `PricingDecisionService`
  (`RAW_DECISION_ROLES`, `SALES_VIEW_ROLES`), `CustomerQuotationService.VIEW_ROLES`,
  `ProcurementService.RAW_PO_ROLES`, `DepositNoticeService`, `AttachmentController`, and
  `TicketService.listPayments` / `projectForRole`.
- Existing authz tests: `TicketServiceTest`, `TicketScopeIntegrationTest`,
  `PricingRequestServiceTest`, `PricingDecisionIntegrationTest`, `AttachmentControllerTest`,
  `DealTrackingAndActivityIntegrationTest`, `stageMeta.test.js`, and
  `salesViewScope.test.js`.

**Projection and tab inventory** — target tabs are
`ภาพรวม · ราคา · ใบเสนอราคา · การเงิน · จัดซื้อและส่งมอบ · เอกสาร · กิจกรรม`.

| Role | Ticket visible | Ownership / scope limits | Visible tabs | Hidden tabs | Read-only tabs / partial read-only zones |
|---|---|---|---|---|---|
| `sales` | Yes | Yes. `requireViewAccess` limits sales to tickets where `createdById == actor.id`; pricing request and customer quotation reads are owner-scoped too. | All 7 | None | `การเงิน` is read-only for ledger/billing. In `จัดซื้อและส่งมอบ`, delivery/procurement progress is read-only, while deposit-notice sales steps can be writable. |
| `sales_manager` | Yes | No ticket owner limit; oversight can read every deal. Deliberately excluded from sales/import/account/CEO operation role sets, with one explicit exception: pipeline stage/lost/reopen/tracking oversight. | All 7 | None | `ราคา`, `ใบเสนอราคา`, `การเงิน`, and `จัดซื้อและส่งมอบ` are business read-only. `กิจกรรม`/tracking and stage/lifecycle oversight remain writable where the server offers them. |
| `import` | Yes | No ticket owner limit on detail read, but list/worklist rows are role-scoped. Pricing-request drafts stay hidden until submitted unless oversight. | `ภาพรวม`, `ราคา`, `จัดซื้อและส่งมอบ`, `เอกสาร`, `กิจกรรม` | `ใบเสนอราคา`, `การเงิน` | `ภาพรวม` item table is read-only. `กิจกรรม` has timeline/comment only; deal-tracking panel is hidden. `เอกสาร` has a backend participant/uploader caveat (see data table). |
| `account` | Yes | No ticket owner limit on detail read, but list/worklist rows are money-scoped. Account is denied pricing-request/factory quote/costing reads. | `ภาพรวม`, `ใบเสนอราคา`, `การเงิน`, `จัดซื้อและส่งมอบ`, `เอกสาร`, `กิจกรรม` | `ราคา` | `ภาพรวม` item table is read-only. `ใบเสนอราคา` is legacy/customer-facing read-only; `CustomerQuotationService` gives no account read grant. Fulfilment progress is read-only, but deposit policy/payment can be writable. Deal tracking is hidden. |
| `ceo` | Yes | No owner limit; CEO sees every deal. | All 7 | None | No whole tab is read-only. Customer-quotation editing itself remains sales-owner-only; CEO acts on pricing decisions and close verification, not by editing customer quotation drafts. |

**Action inventory** — server `availableActions` remains authoritative. The sticky action bar may
choose a smaller visual primary, but it must never invent an action the server did not offer.

| Role | Primary action responsibility | Secondary actions | Destructive / consequential actions | Close-sequence responsibility |
|---|---|---|---|---|
| `sales` | Own-deal commercial flow: create/submit/respond to pricing requests; create/issue/customer-outcome customer quotations; confirm order/customer; create/issue deposit notice; update sales stages when server offers stage actions. | Edit/revise own items, comment, log activities/update tracking, upload documents, download customer documents, set tender requirement when the current control is visible, request commercial revisions. `SET_ENTRY_CHANNEL` is server-offered but unconsumed. | Cancel own deal, mark lost/place on hold/dormant/reopen when owner gate passes, cancel own pricing request where allowed, delete attachments when attachment gate passes, record rejected/revision quotation outcomes. | None in the three-party close. Sales can prepare prerequisites, but close is Account -> CEO. |
| `sales_manager` | Oversight primary is pipeline follow-up: server may offer `ADVANCE_STAGE`/`UPDATE_STAGE`, `MARK_LOST`, `PLACE_ON_HOLD`, `MARK_DORMANT`, `RESUME`, `REOPEN`, plus tracking/activity. It must not receive sales/import/account/CEO operational mutations. | Comment, inspect pricing/quotation/payment/fulfilment, update tracking/activity, set tender requirement when the current control is visible, upload/delete ticket attachments through the manager attachment gate. `SET_ENTRY_CHANNEL` is server-offered but unconsumed. | Mark lost / hold / dormant / reopen and attachment delete when authorized. No deal cancel, payment, procurement, quotation-edit, or close mutations. | None. `TicketServiceTest` asserts sales_manager is rejected by both close steps. |
| `import` | Procurement/pricing execution: pick up submitted pricing requests, request more information, drive factory quote/costing in the pricing-request detail, issue IR, mark IR sent/shipping/goods received, reserve stock, record/complete delivery. | Comment, view raw pricing/costing/factory quote data, manage factory-email inclusion for PR attachments, update import stage fallback where server offers it. | Factory quote attachment tombstoning/deletion under its guards, factory PO cancellation where `RAW_PO_ROLES` allows it, attachment delete only if participant/uploader gate passes. No deal cancel. | None. Import supplies delivery state that can make the close ready, but does not sign close. |
| `account` | Money lifecycle: record payment, set billing, confirm deposit paid, confirm final payment, set/waive deposit policy, confirm ready-to-close after paid + delivered + invoice prerequisites pass. | Comment, upload invoice/general attachments when attachment gate passes, read fulfilment progress for final-payment timing, use account stage fallback for money stages. | Revoke close confirmation, delete attachments when authorized. No deal cancel, no pricing/procurement mutation. | Step 1 only: `CONFIRM_CLOSE` is `CLOSE_CONFIRM_ROLES = {'account'}`. Account may revoke that confirmation while active. |
| `ceo` | Final oversight: pricing decision start/update/approve/return in the PR detail, payment fallback actions, fulfilment fallback actions, broad stage fallback, and final `VERIFY_CLOSE` after account has confirmed. | Comment, view/act on raw cost/margin, record payment/set billing/waive deposit as fallback, inspect all tabs, upload/delete attachments through manager gate, update tracking/activity, stage/lifecycle oversight, set tender requirement when the current control is visible. `SET_ENTRY_CHANNEL` is server-offered but unconsumed. | Mark lost/hold/dormant/reopen, return pricing to import, reject pricing decisions, revoke close confirmation, cancel factory POs/attachments where raw-procurement gates allow. | Step 2 only: CEO verifies close with `VERIFY_CLOSE`. CEO is intentionally **not** in `CLOSE_CONFIRM_ROLES`, so cannot perform Account's first signature. |

**Sensitive-data / visibility inventory** — "visible" here means the current ticket workspace and
the Java service gate agree, unless a caveat is explicitly called out.

| Role | Sensitive information | Cost visibility | Margin visibility | Payment visibility | Quotation visibility | Procurement visibility | Tracking visibility | Attachments visibility |
|---|---|---|---|---|---|---|---|---|
| `sales` | Own customer/deal data, customer-facing prices and quotes; no raw supplier data. | No raw factory/costing cost. Sees approved selling/sales-view values only. | No raw margin. | Yes, read-only payment tab and customer confirmation/deposit prep steps. | Yes, legacy quotation and customer quotation chain for own deal; sales-owner can manage drafts/outcomes. | Delivery progress read-only; no raw Factory PO detail. | Full for own deals: tracking fields + activity log/comment. | Documents tab visible. Backend allows creator/assignee/uploader access; own tickets normally pass as creator. |
| `sales_manager` | Cross-deal oversight, including payment/quotation summaries and approved sales-view prices; no raw supplier data. | No raw cost/costing/factory quote endpoints; covered by wrong-way-round raw pricing tests. | No raw margin. | Yes, read-only. | Yes, read-only customer quotation/legacy quotation. | Fulfilment progress read-only; no raw Factory PO endpoint. | Full oversight: tracking fields + activity log/comment. | Documents tab visible. Backend manager role (`sales_manager`) bypasses attachment ownership. |
| `import` | Raw supplier/factory/pricing execution data; no payment ledger/deposit document surface in ticket workspace. | Yes on pricing/factory quote/costing/raw decision paths (`import`/`ceo`). | Yes where raw pricing decision exposes it; import cannot mutate CEO decision. | Hidden in UI and `listPayments` is denied for import. | Hidden in ticket workspace; legacy ticket quotation is projected out and file download denied. CustomerQuotationService separately permits import read, but Phase 5 tab projection hides that tab for import. | Yes: fulfilment chain and raw Factory PO detail (`RAW_PO_ROLES = import/ceo`). | DealTrackingPanel hidden; activity/comment timeline remains available. | Documents tab visible in UI, but Java attachment access is participant/uploader/manager, and import is not a manager role. Treat import attachment access as conditional, not guaranteed. |
| `account` | Payment ledger, billing dates, invoices, close readiness; no raw pricing/cost/procurement detail. | No. `PricingDecisionIntegrationTest` / `TicketScopeIntegrationTest` deny account from decision/costing/factory quote/PR reads. | No. | Yes, read/write: payment receipts, billing, final payment, deposit policy/payment. | Ticket workspace shows legacy/customer-facing quotation totals read-only; `CustomerQuotationService` itself deliberately excludes account. | Fulfilment progress read-only; no raw Factory PO endpoint. | DealTrackingPanel hidden; activity/comment timeline remains available. | Documents tab and invoice upload are visible in UI, but Java attachment access is participant/uploader/manager, and account is not a manager role. Treat account attachment access as conditional, not guaranteed. |
| `ceo` | Broadest sales visibility, including raw pricing, cost/margin, payment, procurement, quotations and all deal tracking. | Yes. Raw pricing decision/costing/factory quote reads are positive controls for CEO. | Yes. CEO owns pricing decision margin/approval. | Yes, read/write fallback. | Yes: legacy/customer quotation read; pricing decision/quotation approval path via PR detail. Customer quotation draft editing remains sales-owner-only. | Yes: fulfilment plus raw Factory PO detail. | Full: tracking fields + activity log/comment. | Documents tab visible. Backend manager role (`ceo`) bypasses attachment ownership. |

`sales_manager` nuance: the role is still excluded from `canCreateTickets`, `canPickupTickets`,
`canProposePrices`, `canApproveReject`, `canGenerateQuotation`, and `canConfirmPayments`. The
current Java service nevertheless treats pipeline stage/lost/reopen and deal-tracking as an
intentional oversight exception (`requireStageWriteAccess` / `requireDealOwnership`). Phase 5A
must preserve that exact current behaviour rather than simplifying the role to read-only.

Attachment caveat: `TicketDetailPage` mounts `ticketAttachments(ticketId)` for every viewer and
the Phase 5 `documents` tab is projected for every role, but `AttachmentController` does **not**
mirror `TicketService.VIEWER_ROLES`; it allows uploader, ticket creator/assignee, or manager
roles (`hr`, `sales_manager`, `ceo`). Existing tests cover creator/assignee/manager/stranger
paths, but not import/account as ticket-detail viewers. Do not claim import/account attachment
access as fully verified without adding a real Java authz test or changing the gate in a separate,
approved authz task.

**This inventory is a record of existing behaviour. No cell changes in Phase 5A.**

## Section Visibility Matrix

From `visibleSections(role)` in `frontend/src/features/tickets/salesViewScope.js` — presentation
projection only, explicitly **not** a security boundary.

| Section id | sales | sales_manager | ceo | import | account |
|---|---|---|---|---|---|
| `pricingRequest` | ✓ | ✓ | ✓ | ✓ | ✗ |
| `payment` | ✓ | ✓ | ✓ | ✗ | ✓ |
| `delivery` | ✓ | ✓ | ✓ | ✓ | ✓ (read-only) |
| `quotation` (legacy) | ✓ | ✓ | ✓ | ✗ | ✓ |
| `depositNotice` | ✓ | ✓ | ✓ | ✗ | ✓ |
| `dealQuotation` | ✓ | ✓ | ✓ | ✗ | ✓ |
| `dealTracking` | ✓ | ✓ | ✓ | ✗ | ✗ |

Two further page-local role arrays gate content independently of `visibleSections`:
`canViewPricingRequests = ['sales','import','ceo','sales_manager']` (`:265`) and
`canViewDealTracking = ['sales','sales_manager','ceo']` (`:290`).

## Existing Mobile Problems

Grounded in `../01-audit/RESPONSIVE_AUDIT.md` and a source read of this page.

- **F-01 (P1)** — no deliberate tablet treatment existed before Phase 4A; 4A added a 721-1040px
  icon rail to the shell but **did not touch this page's content**, which still collapses via a
  one-off `@media (max-width: 900px)` on `.ticket-detail-grid` (`styles.css:1720`) — a fourth
  breakpoint that matches neither 720 nor 1040 (violates **D-T4**, two breakpoints only).
- The whole page is a single scroll. On 390×844 a sales rep must scroll past the stage cockpit,
  tracking panel, payment panel and other-actions panel before reaching the items table, and
  the primary CTA scrolls out of view entirely — the opposite of the IA's "identity + work-state
  + primary action always visible on mobile".
- The items table (`:1314-1382`) is a CSS-grid table with 4-6 columns driven by `itemsGridCols`
  and **no mobile card reflow** — it squeezes rather than reflows.
- The legacy quotation rows (`:1534-1599`) and payment receipt rows (`:934-950`) use fixed
  `gridTemplateColumns` (`110px 90px 1fr`) that do not reflow.
- Modals get no mobile sheet treatment: `.modal-panel` is `width: min(720px, 100%)` at every
  width (`styles.css:1975`) with no `max-width: 720px` override block.
- **122 `max-[720px]` literals across 36 files** remain (the `mobile:` / `tablet:` variants from
  Phase 3.4 exist at `index.css:16-25` but **zero call sites have been migrated**).

## Existing Accessibility Problems

- **A-05 / F-18** — `Modal.jsx` labels the dialog with `aria-label={title}` while also rendering
  the visible `<h2>{title}</h2>`; the `subtitle` `<p>` is not wired to `aria-describedby`; the
  background is not `inert`; there is no portal, no scroll lock, no `useId`. The focus trap,
  Escape handling and focus restore are solid.
- **F-19** — `frontend/src/features/auth/ChangePasswordModal.jsx:142-146` hand-rolls its own
  backdrop/panel and its own `FOCUSABLE` constant. Out of this phase's scope, recorded.
- **F-10** — Thai-first violated on this page: `TicketDetailPage.jsx:40` (`'พัก dormant'`) and
  `DealStagePanel.jsx:209,374`.
- **F-14** — redundant back navigation: `Breadcrumbs` at `:788` *and* a full-width "กลับ" button
  at `:789-792`.
- **F-17** — colour and spacing literals in inline styles across the payment panel, items table,
  attachments, event list and legacy quotation rows.
- **F-13** — every button on this page is still a legacy `.primary-button` /
  `.secondary-button` / `.icon-button` CSS class, not the shared `<Button>`.
- The event list (`:1602-1664`) is a `<div>` stack with decorative `.event-dot` spans — no
  ordered-list semantics, no `<time>` elements (Phase 3 §17).
- "ข้อมูลทั่วไป" uses `InfoRow` `<div>`s rather than `<dl>/<dt>/<dd>` (Phase 3 §21).
- `EmptyState.jsx` has **no CTA slot**, so the not-found state at `:470-480` bolts a bare button
  underneath it — Phase 3 §18 requires empty states to route onward.
- Attachment load failures are silently swallowed (`:311-312`, deliberate, documented) — no
  error surface at all for that query.

## Container Audit Rule

Before restyling the ticket workspace, audit every visible container. A bordered or elevated card
is allowed only when it represents a genuinely independent object, decision or temporary layer.
Replace decorative and nested cards with sections, dividers, description lists, table rows, tonal
insets or plain grouped content. Enforce the one-panel-deep rule: no card inside another card.
Metric cards must not appear unless the metric directly supports the current task; otherwise use a
compact stat strip or inline summary.

This applies to extracted tab panels, the desktop context panel, the sticky action surface,
migrated legacy `.panel` / `.table-panel` regions and any replacement for the current stat-card
layout.

## First-Viewport Contract

The first viewport is governed by
[`PHASE_5A_FIRST_VIEWPORT_CONTRACT.md`](PHASE_5A_FIRST_VIEWPORT_CONTRACT.md). It is an
acceptance gate for Phase 5A, not a later polish pass.

Within the first viewport, every role must be able to answer: which deal this is, which stage it
is in, what the current work state is, whether something is blocked or returned, whose action is
required, what the current user should do next, and where supporting details live.

The persistent command header may show deal code, customer, project, current stage, work-state
label, waiting-on role, blocker/returned reason, sales owner, important freshness/deadline, one
primary next action, and refresh as an icon action. It must not show five equal-weight summary
cards for stage, pricing request, payment, import, and deal value.

Replace separate summary cards with one compact metadata strip using inline label/value pairs,
for example: `ใบขอราคา: รอ Import รับเรื่อง`, `การชำระเงิน: ยังไม่เริ่ม`, `การนำเข้า:
ยังไม่เริ่ม`, `มูลค่าดีล: ฿0`. Do not put each pair in its own rounded or elevated card.

The stage display is compact by default: current phase, current stage, immediate previous/next
context where useful, and progress summary. The complete fourteen-step detail may remain behind
`ดูขั้นตอนทั้งหมด`, but it must not dominate the first viewport or render at full prominence on
every tab.

## Tab Simplification Contract

The tab bodies are governed by
[`PHASE_5A_TAB_SIMPLIFICATION_CONTRACT.md`](PHASE_5A_TAB_SIMPLIFICATION_CONTRACT.md). This is
an acceptance gate for Phase 5A.

Overview is the concise operational summary only: meaningful current-work alert, deal
`DescriptionList`, role-sensitive item summary, compact cross-track workflow summary, latest
three meaningful activity events, and role-appropriate secondary detail. It must not render every
workflow panel.

Pricing answers request count, active request, owner, blocker and permitted role action. Pricing
request rows may be bordered or disclosed because each request is an independent record, but the
list must not sit inside another oversized card and non-active requests stay collapsed.

Quotations use dense document rows and keep quotation-document status distinct from customer
accepted/rejected outcome. Money uses compact aligned financial rows with tabular numbers instead
of three metric cards.

Fulfilment is reframed into two connected tracks: `การจัดหา / นำเข้า` and `การส่งมอบลูกค้า`.
`สินค้าถึงโกดัง GL&R` and `ส่งมอบถึงลูกค้า` remain separate states. Documents is the canonical
dense file list, with compact empty/upload states. Activity is one chronological structure for
events, comments and follow-up.

## Badge Reduction Contract

Badge usage is governed by [`PHASE_5A_BADGE_AUDIT.md`](PHASE_5A_BADGE_AUDIT.md). This is an
acceptance gate for Phase 5A.

A badge represents a short state. Do not use badges for long explanations, ordinary metadata,
labels already present in a heading, every step in a sequence, decorative emphasis, counts or raw
facts.

Ticket workspace badge budget:

- One primary stage badge in the persistent header.
- One work-state badge or alert in the persistent header.
- Local record badges only when the sub-record has its own state.
- Long waiting, blocker or returned messages use `InlineAlert` or plain text.
- Role labels, quantity types, revision numbers, progress counts and event types use text/table
  cells, not pills.

Do not repeat `กำลังดำเนินการ` as a high-emphasis badge at multiple levels. Do not repeat the
same payment, pricing, fulfilment or lifecycle state in the header, tab heading, record row and
record body.

## Action Hierarchy Contract

Action hierarchy is governed by
[`PHASE_5A_ACTION_HIERARCHY.md`](PHASE_5A_ACTION_HIERARCHY.md). This is an acceptance gate for
Phase 5A.

Every visible ticket-workspace action must be classified as `PRIMARY NEXT ACTION`,
`CONTEXTUAL ACTION`, `SECONDARY ACTION`, `DESTRUCTIVE ACTION`, `DOCUMENT ACTION` or
`NAVIGATION ACTION`.

The sticky action bar may contain one primary action, up to two common secondary actions, one
overflow/other-actions control and a separated destructive group. It must not show five
equal-weight workflow buttons together.

For the stage-control shape, use:

```text
Primary: เลื่อนไปขั้นถัดไป
Secondary: แก้ไขสถานะ
More actions: พักดีลไว้ · พักเป็นดีลไม่เคลื่อนไหว · ทำเครื่องหมายเสียงาน · ยกเลิกดีล
```

Replace user-facing internal vocabulary such as `พัก dormant`, `Shipping`, `Goods Received`,
`WAREHOUSE` and `STOCK` with Thai-first operational wording unless the business explicitly
requires the English term. Destructive actions are separated and confirmed.

## Mobile Workspace Contract

Mobile workspace behaviour is governed by
[`PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md`](PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md). This is an
acceptance gate for Phase 5A.

At `390 x 844`, the ticket workspace must not reproduce the desktop page as one vertical stack.
The compact header, reachable tabs, the start of the active tab, and one primary action path must
be available without scrolling through historical content.

Mobile rules:

- Persistent header is compact; long Thai customer and project names wrap intentionally.
- Tabs remain reachable and the active tab content begins close to the top.
- Sticky action bar includes safe-area padding and never covers content.
- Empty states remain compact.
- Tables reflow to compact records unless true column comparison requires a bounded horizontal
  scroll region.
- Expandable forms, modals and drawers keep submit/cancel controls reachable and preserve focus,
  inert background and focus restore behaviour.
- Page-level horizontal scrolling is forbidden.

The mobile acceptance data set must include long Thai customer/project names, multiple pricing
requests, five quotations, partial delivery, multiple documents, long activity history, returned
pricing request, overdue payment and completed deal states.

## Spacing Consistency Rule

Spacing consistency is a Phase 5 acceptance requirement, not a visual-polish pass. The ticket
workspace must use spacing to communicate information architecture: small gaps for content that
belongs together, larger gaps for true group boundaries, and no large empty area used as a
substitute for structure.

Use the Phase 3 spacing tokens from `TOKENS.md` / `index.css` as the source of truth. For this
workspace, the active rhythm is:

| Value | Token | Use |
|---|---|---|
| 4px | `--space-1` | Icon/text micro-gap, label/value micro-gap, helper text proximity |
| 8px | `--space-2` | Badges, related controls, compact row content, action button gaps |
| 12px | `--space-3` | Compact internal padding, inline alert padding, mobile record internals |
| 16px | `--space-4` | Standard control groups, field groups, mobile section spacing, tabs to active content |
| 20px | `--space-5` | Default desktop working-section padding, form body to actions |
| 24px | `--space-6` | Major section separation, tab-local primary content to historical content |
| 32px | `--space-8` | Distinct page-region separation only when genuinely required |

`--space-7` / 28px exists in the Phase 3 token file, but it is not a default Phase 5 workspace
choice. Use it only when an approved component contract already requires it, or when a technical
constraint is documented in the implementation notes.

Do not add new arbitrary spacing values while touching the workspace. Avoid nearby substitutes
such as 5, 6, 7, 9, 10, 11, 13, 14, 15, 17, 18, 22, 26 or 30px unless the value already belongs
to an approved component contract or is technically necessary and documented.

Default vertical rhythm:

| Relationship | Default |
|---|---|
| Page region to page region | 24px |
| Persistent header to tabs | 16px |
| Tabs to active panel/content | 16px |
| Major section to major section | 24px |
| Section heading to supporting text | 4-8px |
| Heading group to content | 16px |
| Related content rows | 8-12px |
| Distinct groups inside one section | 20px |
| Label to form control | 6-8px, only where inherited from the shared form contract |
| Control to helper/error text | 4-8px |
| Field group to field group | 16px |
| Form body to action row | 20px |
| Buttons within one action row | 8px |
| Destructive action separation | At least 16px or a separate overflow group |
| Mobile record-card internal gap | 8-12px |
| Mobile record to next record | 12px |

Equivalent workspace surfaces must use equivalent padding. Preferred defaults:

- Desktop working section: 20px.
- Mobile working section: 16px.
- Dense table/list container: 0-16px depending on whether row padding owns the rhythm.
- Compact inline alert: 12px.
- Consequential decision area: 20-24px.

Avoid double padding caused by `panel padding -> inner card padding -> child content margin`.
When a card is converted to a section, row, description list, table/list or inline alert, remove
the obsolete inner padding at the same time.

## Component Boundaries

Component extraction is governed by
[`PHASE_5A_COMPONENT_BOUNDARIES.md`](PHASE_5A_COMPONENT_BOUNDARIES.md). This is an acceptance
gate for Phase 5A.

Extract components only when they encode semantic workspace roles or reduce real complexity. Do
not create a generic `Card` component, and do not add components that exist only to apply border,
background, padding or elevation. New Phase 5A ticket workspace component names must not contain
`Card`.

### Extracted from `TicketDetailPage.jsx` (moved verbatim first, restyled second)

| New file | Moved from |
|---|---|
| `features/tickets/tabs/OverviewTab.jsx` | ข้อมูลทั่วไป `:1085-1098` + รายการสินค้า `:1100-1383` + การดำเนินการอื่น ๆ `:971-1081` |
| `features/tickets/tabs/PricingTab.jsx` | `PricingRequestPanel` mount `:1385-1391` |
| `features/tickets/tabs/QuotationsTab.jsx` | `DealQuotationPanel` mount `:1399-1401` + legacy quotations `:1534-1599` |
| `features/tickets/tabs/MoneyTab.jsx` | การชำระเงิน `:887-957` + payment/billing modals `:1667-1779` |
| `features/tickets/tabs/FulfilmentTab.jsx` | `DealDepositPanel` + `DealFulfilmentPanel` mounts `:1409-1442` |
| `features/tickets/tabs/DocumentsTab.jsx` | ไฟล์แนบ `:1445-1532` + the `docActions` downloads `:838-864` |
| `features/tickets/tabs/ActivityTab.jsx` | ประวัติการดำเนินการ + comment composer `:1602-1664` + `DealTrackingPanel` mount `:871-885` |
| `features/tickets/dealWorkState.js` | new — pure classifier |
| `features/tickets/workspace/DealWorkStateBanner.jsx` | new — replaces the `queueText` block inside `DealStateHeader` |

The items-table edit mode (`:1105-1312`) moves **with** the items table into `OverviewTab`,
including its `editItems.qty.<row>` field-error keys. The shared `fieldErrors` / `fieldRefs`
machinery stays on `TicketDetailPage` and is passed down, so the per-form prefix contract
(`payment.` / `revise.` / `editItems.qty.`) is unchanged.

### Semantic workspace components

Approved Phase 5A extraction targets, when they meet the threshold in the component-boundaries
contract:

- `DealWorkspaceHeader`
- `DealMetadataStrip`
- `DealWorkStateBanner`
- `DealWorkflowSummary`
- `DealWorkspaceTabs`
- `DealOverviewPanel`
- `DealItemSummary`
- `DealActivityTimeline`
- `DealDocumentList`
- `DealFinancialSummary`
- `DealActionBar`
- `CompactEmptySection`
- `WorkflowStepList`

These components encode operational meaning. Avoid `InfoCard`, `SmallCard`, `StatusCard`,
`DetailCard`, `GenericCard2` and any new generic `Card` wrapper.

### New shared primitives — each has a consumer in this slice

| File | Contract | Notes |
|---|---|---|
| `components/common/Tabs.jsx` | **new contract, authored here** | `role="tablist"` + `role="tab"` + `role="tabpanel"`, roving tabindex, ←/→/Home/End, `aria-controls` + `aria-labelledby`, `useId`, focus moves to the panel on change, horizontal scroll below 720px. `[role="tab"]` is already covered by the global focus ring (`styles.css:257-305`). The dead `.tabs` / `.status-tabs` CSS (`styles.css:1906-1929`, `:1650-1673`, **no consumers today**) is either adopted or deleted — not left dangling. Closest existing precedent to improve on: `features/requests/RequestsPage.jsx:42-60` (has `role`/`aria-selected`, missing `aria-controls`, panel roles, ids and arrow keys). |
| `components/common/InlineAlert.jsx` | §7 | `info`/`success`/`warning`/`danger`; `role="status"` or `role="alert"` by severity; optional dismiss. **Full border + tinted bg — the `border-left` side-stripe is an absolute ban.** |
| `components/common/DescriptionList.jsx` | §21 | `<dl>/<dt>/<dd>`; consolidates `FieldList`/`InfoGrid` from `components/common/FieldList.jsx`; replaces `InfoRow`. |
| `components/common/StickyActionBar.jsx` | §15 | One primary + secondary/overflow; **disabled actions explain why**; does not trap scroll; thumb-reachable below 720px. |
| `components/common/Timeline.jsx` | §17 | `<ol>` + `<time>`; loading skeleton + "ยังไม่มีกิจกรรม" empty; replaces `.ticket-events`/`.ticket-event`/`.event-dot`. |
| `features/tickets/workspace/DealWorkStateBanner.jsx` | **new contract, authored here** | Deal-specific; driven by `dealWorkState.js`; renders the 9 work-states with the Phase 2 Thai labels. |

**Not built** (no consumer in this slice, despite being in the Phase 3 build order): Drawer,
FilterBar, ApprovalTask, WorklistRow. Do not build abstract components with no current consumer.

### Modified shared primitive

`components/common/Modal.jsx` — the A-05 fix, applied **app-wide to all 23 consumers**:
`useId` + `aria-labelledby` on the visible `<h2>` + `aria-describedby` on the subtitle; portal to
`document.body`; `inert` + `aria-hidden="true"` on the page root while open, cleared on close.
The proven in-repo pattern to mirror is the Phase 4A mobile filter sheet at
`features/tickets/TicketListPage.jsx:588-760` (portal at `:749`, inert at `:759`, focus recapture,
single `close*` path with `requestAnimationFrame` restore). The existing focus trap, Escape
handling and focus restore in `Modal.jsx` are already correct — **do not rewrite them.**

### Contract addendum

Phase 3 has **no `Tabs` contract and no `DealWorkStateBanner` contract**. Both are authored as an
addendum appended to `../03-design-foundation/COMPONENT_CONTRACTS.md` (new §22 Tabs, §23
Work-state banner) so a later phase can cite them rather than re-deriving them.

## CSS And Visual Cleanup Contract

CSS and visual cleanup is governed by
[`PHASE_5A_CSS_VISUAL_CLEANUP.md`](PHASE_5A_CSS_VISUAL_CLEANUP.md). This is an acceptance gate
for Phase 5A.

Within ticket detail and directly related panels, remove unnecessary border layers, rounded
containers, duplicate backgrounds, excessive vertical padding, one-off shadows and repeated
inline layout styles where maintainable components, utilities or tokenized classes exist.

The cleanup is not a full application CSS rewrite. It must preserve readable density, Thai font
sizing and line-height, visible focus, 44px touch targets, and meaningful distinctions between
current work, waiting work, blocked/returned/overdue work, completed work, history and reference
information.

The target is clearer grouping with fewer boxes, not a borderless page.

## State Feedback Contract

Loading, error, empty, permission-limited, not-applicable and completed states are governed by
[`PHASE_5A_STATE_FEEDBACK_CONTRACT.md`](PHASE_5A_STATE_FEEDBACK_CONTRACT.md). This is an
acceptance gate for Phase 5A.

The workspace must distinguish ticket loading, ticket load error, ticket not found, tab content
loading, tab content error, tab content empty, permission-limited content, not-applicable content
for the current stage, and completed content.

Do not reuse the same empty state for conditions that mean different things. `ยังไม่มีใบขอราคา`,
`คุณไม่มีสิทธิ์ดูข้อมูลราคา`, and `ใบขอราคากำลังโหลด` are three different states with three
different treatments.

Use compact inline alerts, retry actions, tab-local failure boundaries and previously loaded data
during background refresh. Do not display raw server exceptions.

## Proposed Tab Mapping

Target order, exactly as the scope specifies:

```
ภาพรวม · ราคา · ใบเสนอราคา · การเงิน · จัดซื้อและส่งมอบ · เอกสาร · กิจกรรม
```

| Tab | id | Content | Visibility gate (existing constants — none changed) |
|---|---|---|---|
| ภาพรวม | `overview` | ข้อมูลทั่วไป (→ `DescriptionList`) · รายการสินค้า + edit mode · การดำเนินการอื่น ๆ | always |
| ราคา | `pricing` | `PricingRequestPanel` | `sections.pricingRequest && canViewPricingRequests` |
| ใบเสนอราคา | `quotations` | `DealQuotationPanel` + legacy quotation rows | `(sections.dealQuotation && canViewPricingRequests) \|\| sections.quotation` |
| การเงิน | `money` | การชำระเงิน + payment/billing modals | `sections.payment` |
| จัดซื้อและส่งมอบ | `fulfilment` | `DealFulfilmentPanel` + `DealDepositPanel` | `sections.delivery \|\| sections.depositNotice` |
| เอกสาร | `documents` | ไฟล์แนบ + generated-doc downloads (quotation PDF/XLSX, remaining invoice) | attachments always; per-document gates unchanged |
| กิจกรรม | `activity` | ประวัติการดำเนินการ (→ `Timeline`) + comment composer + `DealTrackingPanel` | events + comment always; tracking `sections.dealTracking` |

Resulting projection:

| Role | Tabs |
|---|---|
| sales / sales_manager / ceo | all 7 |
| import | ภาพรวม · ราคา · จัดซื้อและส่งมอบ · เอกสาร · กิจกรรม (5) |
| account | ภาพรวม · ใบเสนอราคา · การเงิน · จัดซื้อและส่งมอบ · เอกสาร · กิจกรรม (6) |

Rules:

- **A role with no data access gets no tab.** Phase 2: "The UI must not render a tab whose data
  the backend would 403." `SectionPeek` retires for tabbed sections — the deal-level context it
  carried (customer · current stage) already lives in the persistent header, which every viewer
  sees.
- **Tab visibility is presentation projection, not the security boundary.** It mirrors
  `salesViewScope.js`, which itself mirrors the Java projection. Hiding a tab grants nothing and
  removes nothing server-side.
- **URL state:** `?tab=<id>`, written with `setSearchParams(next, { replace: true })`. This
  matches the existing `?tab=` convention in `features/requests/RequestsPage.jsx` and keeps deals
  deep-linkable, while `replace` means browser-back still returns to `/tickets` with its filters
  through the existing `navigate(-1)` — the preserved behaviour.
- **Fallback:** an absent, unknown, or not-permitted `?tab=` falls back to `overview`. Never a
  blank panel, never a redirect.
- **Query mounting:** tab panels are conditionally rendered, so a hidden tab's child queries do
  not mount. This is the intended benefit (a CEO no longer fires 13 queries on open) but it is a
  real behaviour change — see Risks and the Test Matrix.

## Proposed Work-State Derivation

New pure module `frontend/src/features/tickets/dealWorkState.js`.

```
workState({ summary, availableActions, can }, viewer) -> {
  state: 'needs_my_action' | 'waiting' | 'blocked' | 'overdue' | 'draft'
       | 'completed' | 'cancelled' | 'returned' | 'informational',
  label: <Thai string>,
  waitingOn: <role id | null>,
  reason: <string | null>,        // the blocker / return reason
}
```

Rules, straight from `../02-information-architecture/WORK_STATE_MODEL.md`:

1. **Computed from the axes, never from `ticket.status`.** Inputs are `summary.lifecycle`,
   `summary.salesStage`, `summary.paymentStatus`, `summary.fulfillmentStatus`,
   `summary.closeConfirmedAt`, `summary.overdue`, the latest pricing-request status, and
   `availableActions`. Correction MS1/CS2 is explicit: `status=quotation_issued` persists across
   ~6 work-states and 3 roles, so keying off it would be wrong.
2. **Per-viewer.** The classifier takes `(record, viewer)` and never `record` alone. The same
   deal is *Needs-my-action* for the CEO and *Waiting* for the import user who submitted it.
3. **Never wider than the server.** *Needs-my-action* requires the corresponding `can.*` flag,
   which is itself gated on `availableActions`. The classifier can only ever narrow.
4. **Overdue is a modifier**, not a slot — it escalates an underlying Needs-my-action or Waiting.
5. **Returned only where the backend models it** — the pricing chain
   (`COSTING_REVISION_REQUIRED`), deposit revision, quotation revision. Never invented.
6. **D-18 consistency advisory:** when the computed work-state and the persisted `salesStage`
   disagree in a missed-auto-advance shape (e.g. `FULLY_PAID` + `FULLY_DELIVERED` but stage ≠
   `CLOSED_PAID`), render a **non-blocking** "สถานะไม่สอดคล้อง" advisory on CEO/account views.
   Informational only — it never gates an action.

The existing `nextAction` (`:586-599`) and `waitingHint` (`:607-614`) derivations are the seed
for the labels and are folded into this module rather than left as a second, divergent source.
`salesActions.js` / `importActions.js` / `accountActions.js` are **list-row** CTA resolvers with
no `availableActions` access — they are not reused here and are not modified.

`DealWorkStateBanner` renders the result. `DealStateHeader`'s current "ถึงคิวคุณ" block is
replaced by it so the two can never disagree; `DealWorkspaceHeader` keeps identity, compact
current-stage context and one metadata strip. It must not keep or introduce a five-card summary
row.

## Proposed Sticky-Action Ownership

`TicketDetailPage` owns the single `StickyActionBar`.

- **Primary** = the existing `primaryAction` cascade, unchanged in logic:
  `confirmCustomer` → `confirmFinalPayment` → `confirmClose` → `verifyClose`.
- **Secondary / overflow** = `revise`, `editItems`, `revokeCloseConfirm`, `cancel` — the
  contents of today's "การดำเนินการอื่น ๆ" panel, which stops being a mid-page section.
- **Two-signature close (D-08)** renders as two distinct states and never one combined "close"
  button: account sees "ยืนยันพร้อมปิดงาน", CEO sees "ตรวจสอบและปิดงาน".
- **Child panels keep their own in-panel primaries.** `DealQuotationPanel`,
  `DealDepositPanel`, `DealFulfilmentPanel` and `PricingRequestPanel` each own their action
  plumbing and are not rewired. When the viewer's next move lives in one of them, the bar shows a
  **"ไปที่แท็บ …"** navigation affordance that switches tabs — not a duplicated mutation button.
  This is a deliberate narrowing of the Phase 2 IA ideal, taken to keep the four preserved
  workflows untouched.
- **Disabled actions explain why** (§15, DA1/DA2/DA3): quotation until the CEO approves; issue-IR
  until the deposit is confirmed; confirm-close until fully-paid + delivered + invoice uploaded.
  The reason text comes from data the page already has — never invented.
- On a terminal deal (closed / cancelled / lost) the bar renders the terminal state and no
  actions except any the server still offers (e.g. `REOPEN`).

## Test Matrix

Conventions (from `../04-production-repair/` and the existing suite): tests are colocated
(`Foo.jsx` → `Foo.test.jsx`), `globalThis.React = React` at the top, `vi.mock('../../api/index.js')`
per endpoint, a local `renderX()` wrapping `QueryClientProvider` (retry off) + `MemoryRouter`,
queries by Thai role/label, **no jest-dom matchers** (raw `expect(...).toBe(...)`), and
`stubMobile()` overriding `window.matchMedia` for `(max-width: 720px)` cases
(`TicketListPage.test.jsx:11-24`).

| Suite | File | Must cover |
|---|---|---|
| Tabs primitive | `components/common/Tabs.test.jsx` | tablist/tab/tabpanel roles + ids; `aria-selected`; `aria-controls` ↔ panel `id`; roving tabindex; ←/→/Home/End; focus lands on the panel after change; only the active panel is rendered |
| Inline alert | `components/common/InlineAlert.test.jsx` | `role="alert"` for danger/warning vs `role="status"` for info/success; text present (not colour-only); optional retry/action is a real button; dismiss is a real button; **no `border-left` accent** |
| Description list | `components/common/DescriptionList.test.jsx` | `<dl>/<dt>/<dd>` structure; single-column below 720px; empty value renders "-" |
| Sticky action bar | `components/common/StickyActionBar.test.jsx` | one primary; up to two visible secondary actions; overflow reachable by keyboard; destructive actions separated from ordinary overflow actions; disabled primary exposes its reason via accessible text |
| Timeline | `components/common/Timeline.test.jsx` | `<ol>`/`<li>`; `<time dateTime>`; loading skeleton `aria-hidden`; empty state text |
| Modal (A-05) | `components/common/Modal.test.jsx` | `aria-labelledby` points at the visible `<h2>`; `aria-describedby` at the subtitle when present; page root gets `inert` + `aria-hidden` on open and is cleared on close; existing focus trap / Escape / restore still pass |
| Work state | `features/tickets/dealWorkState.test.js` | all 9 states; per-viewer divergence on the same record (CEO vs import at `READY_FOR_CEO_REVIEW`); **never `needs_my_action` without the backing `availableActions` entry**; overdue escalation; returned only where modelled; the D-18 advisory shape; **written wrong-way-round** (assert a viewer is *not* told to act) |
| Workspace semantic components | `features/tickets/workspace/*.test.jsx`, `features/tickets/tabs/*.test.jsx` | extracted components satisfy `PHASE_5A_COMPONENT_BOUNDARIES.md`: semantic names, no new generic `Card`, no component that only adds border/padding, no five-card summary recreated behind component boundaries; focused tests for header, metadata strip, work-state banner, workflow summary, financial summary, document list, item summary, activity timeline, action bar, compact empty state and workflow step list where extracted |
| Ticket detail | `features/tickets/TicketDetailPage.test.jsx` | all **34** existing tests keep passing, re-scoped to open the owning tab first; **plus** tab projection per role (7/5/6); first-viewport contract basics (identity, current stage, work-state, waiting/blocker, owner/deadline, one next-action slot, tab map); no five-card summary row; badge contract basics (one stage badge, one work-state badge/alert, no repeated high-emphasis `กำลังดำเนินการ`); action hierarchy basics (single primary slot, no row of equal stage actions, destructive actions in overflow/confirmation, `พัก dormant` renamed Thai-first); mobile contract basics at `390 x 844` (compact header, reachable tabs, no page-level horizontal overflow, sticky action not covering content); CSS cleanup basics (no nested decorative card stack, no duplicate metric-card surfaces, focus still visible); state feedback basics (ticket loading, load error and not-found are distinct; raw server exception is not shown); `?tab=` deep link; unknown/not-permitted `?tab=` falls back to `overview`; tab change uses `replace` (history length unchanged); `onBack` still `navigate(-1)`; sticky-bar primary matches `availableActions`; **the attachment-toast regression** (upload/delete fires the success toast and invalidates `ticketDetail`) |
| Tab panels | `features/tickets/tabs/*.test.jsx` | one focused test per extracted tab covering the behaviour that moved with it (items edit-mode per-row `editItems.qty.<row>` errors; payment amount inline validation; final-payment confirm dialog amount), plus the tab simplification contract: Overview `DescriptionList` + three-event activity cap, Pricing active row/collapsed rows/compact empty state, Quotations dense document list + separate customer outcome, Money compact financial rows/no metric cards, Fulfilment two ordered tracks + item fulfilment table, Documents grouped file rows + compact empty row, Activity one chronological structure + collapsed composer; plus badge reduction: row-local badges only for independent sub-record states, role labels/quantity types/revision numbers/event types render as text; plus action hierarchy: tab-local actions do not duplicate sticky primary, document actions stay row-local, destructive row actions confirm; plus mobile contract: tabs remain concise and do not expand into the whole desktop stack; plus CSS cleanup: fewer border/background/radius layers without losing current/waiting/completed/history distinctions; plus state feedback: tab loading, error, empty, permission-limited, not-applicable and completed states use distinct messages/treatments |
| Unchanged | `salesViewScope.test.js`, `stageMeta.test.js`, `DealStagePanel.test.jsx`, `designTokens.test.js` | must stay green untouched; `designTokens.test.js` reads `index.css` off disk, so update it **only** if a token is added |

E2E: new `frontend/e2e/phase5a-acceptance.spec.js` on the existing mock server (port 5250),
reusing `e2e/helpers/auth.js` (`loginAs`, `spaGoto`, `SEEDED_ROLES`) and the local helpers
`phase4a-acceptance.spec.js` established — `loginAtViewport` (**always log in at desktop before
resizing**, because the logout control is inside the collapsed drawer below 720px),
`watchForProblems` (console + pageerror with the `KNOWN_NOISE` filter), and
`assertNoHorizontalOverflow` (1px slack). **Never call `page.goto()` after the initial load** —
the mock `db` is module state; use `spaGoto`.

Full baseline re-run at the end: `npm run lint && npm test && npm run build && npm run test:e2e`,
plus `git diff --check`.

## Screenshot Matrix

Captured by the acceptance spec itself, gated on `CAPTURE_EVIDENCE=1` (the Phase 4A pattern), so
an ordinary `npm run test:e2e` asserts without rewriting tracked PNGs:

```
CAPTURE_EVIDENCE=1 npm run test:e2e
```

Evidence root: `docs/ui-repair/evidence/proposed/phase-5a-ticket-workspace/`
Path shape: `<role-or-shared>/<label>-<WxH>.png` (roles hyphenated: `sales-manager`).
PNGs under `docs/ui-repair/evidence/**` are LFS-tracked (`.gitattributes`).

Viewports — all five the scope names:
`mobile-390x844` · `tablet-768x1024` · `tablet-1024x768` · `desktop-1366x768` · `laptop-1440x900`.

Roles: `sales` (owner), `sales-manager`, `import`, `account`, `ceo` — 25 role × viewport runs.

Per role, capture at minimum:

1. Default landing (`overview` tab).
2. Each tab that role can see, on desktop.
3. The work-state banner in a *Needs-my-action* state and in a *Waiting* state.
4. The sticky action bar with a primary action, and with a disabled primary showing its reason.
5. Mobile: compact header + banner + sticky bar path visible without historical scrolling; the
   tab strip scrolled; no page-level horizontal overflow.
6. Keyboard focus visible on: a tab, the sticky primary, a modal's first control.

First-viewport assertions in the acceptance spec:

- Header answers the seven questions in `PHASE_5A_FIRST_VIEWPORT_CONTRACT.md` for every role
  projection.
- Header contains no five equal-sized summary-card row.
- Stage display is compact by default and the fourteen-step detail is behind
  `ดูขั้นตอนทั้งหมด`.
- First body content does not duplicate the header's primary workflow action.
- The tab row is visible as the map to supporting detail.

Tab simplification assertions in the acceptance spec:

- Overview does not render Pricing, Money, Fulfilment, Documents and Activity as full workflow
  panels at once.
- Overview workflow status is one compact cross-track summary and recent activity is capped at
  three meaningful events.
- Empty Price and Documents states are compact inline rows, not large blank panels.
- Money has aligned financial rows and no three metric-card layout.
- Fulfilment shows two connected tracks and keeps warehouse receipt distinct from customer
  delivery.
- Activity has one chronological event structure and no separate permanent comment/history panel.

Badge reduction assertions in the acceptance spec:

- Header has one primary stage badge and one work-state badge or alert at most.
- Active lifecycle `กำลังดำเนินการ` is not repeated as a prominent badge in the header and body.
- Long waiting/blocker/returned text appears as `InlineAlert` or plain text, not a pill.
- Role labels, sequence steps, quantity types, revision numbers, progress counts and event types
  are not rendered as status badges.
- Independent sub-records still keep one local status badge: pricing request, quotation document,
  deposit document/payment row and Factory PO.

Action hierarchy assertions in the acceptance spec:

- Every role/state render has at most one visible `PRIMARY NEXT ACTION`.
- Sticky action bar shows no more than two visible secondary actions before overflow.
- The old flat row `เลื่อนไป... / แก้ไขสถานะ... / เสียงาน / พักดีลไว้ / พัก dormant` does not
  appear.
- `พัก dormant` and English import/delivery labels are replaced with Thai-first terms.
- Destructive actions are separated from ordinary actions and require confirmation.
- Document and navigation actions are visually distinct from workflow submit/approval actions.

Mobile workspace assertions in the acceptance spec:

- At `390 x 844`, the page does not reproduce the desktop structure as one vertical stack.
- Compact header, tabs, active tab opening and one primary action path are reachable before long
  historical content.
- Long Thai customer and project names wrap intentionally without causing page-level horizontal
  overflow.
- Sticky action bar includes safe-area padding and does not cover the last row, form action,
  empty state or timeline entry.
- Empty Price/Documents states are compact.
- Item, pricing, quotation, payment, document, delivery and activity rows reflow to compact
  records unless a bounded table scroller is explicitly justified.
- Expandable forms, modals and drawers keep submit/cancel controls reachable and preserve focus
  trap, inert background and focus restore behaviour.
- Mobile stress scenarios cover: long Thai customer name, long project name, multiple pricing
  requests, five quotations, partial delivery, multiple documents, long activity history,
  returned pricing request, overdue payment and completed deal.

CSS and visual cleanup assertions in the acceptance spec:

- Touched ticket-workspace surfaces do not contain nested decorative card stacks.
- Header and Money tab do not recreate metric cards as rounded bordered containers.
- Resting content uses no one-off shadows.
- Documents and Pricing empty states are compact.
- Activity history renders as timeline/list rows, not bordered cards inside a panel.
- Readable density, Thai text sizing, visible focus and 44px mobile touch targets are preserved.
- Borders/backgrounds/radius remain only where they communicate structure, state, row separation,
  controls, temporary layers or independent external records.

State feedback assertions in the acceptance spec:

- Ticket initial loading renders a layout-matched workspace skeleton with `aria-busy`.
- Ticket load error, ticket not found and permission denial render distinct Thai-first messages.
- Tab content loading, tab content error and tab content empty are distinct.
- Permission-limited tab content does not render as ordinary empty content.
- Not-applicable-in-current-stage content does not render as error or completed content.
- Completed content does not render as empty content.
- Tab-local query failures show tab-local retry actions and do not blank the workspace.
- Background refresh preserves previously loaded header, action and tab data.
- Raw server exceptions, stack traces, SQL/Java class names and endpoint paths are never shown.

Shared states (`shared/`): loading skeleton · ticket load error + retry · not-found · tab query
error + retry · tab empty · permission-limited content · not-applicable content · completed
content · a modal with the A-05 labelling · a terminal (cancelled/lost) deal · the D-18
consistency advisory.

Evidence must use mock personas only and must expose no real PII, salary or customer data.

## Migration Boundaries

- **Decompose, don't rewrite.** Each region moves into its tab file **verbatim first** (same
  JSX, same handlers, same props), then is restyled in a second pass — the discipline handoff
  105 used when it moved deposit and fulfilment out of this page. A reviewer must be able to see
  "moved" and "changed" as separate steps.
- **The child panels move first, simplify second.** `PricingRequestPanel`,
  `DealQuotationPanel`, `DealDepositPanel`, `DealFulfilmentPanel` are initially relocated with
  props, queries, mutations, invalidation sets and internal gates unchanged. Their presentation
  may then be simplified to meet `PHASE_5A_TAB_SIMPLIFICATION_CONTRACT.md`, but business logic,
  permissions, endpoint behaviour, cache keys and mutation side effects stay unchanged.
- **`applyTicketUpdate`, `doAction`, `resetActionDrafts`, `fieldErrors`/`fieldRefs` stay on
  `TicketDetailPage`** and are passed down. The per-form prefix contract (`payment.`, `revise.`,
  `editItems.qty.<row>`) is unchanged.
- **State feedback follows `PHASE_5A_STATE_FEEDBACK_CONTRACT.md`.** Preserve previously loaded
  ticket and tab data during background refresh; do not collapse query errors into empty states;
  sanitize user-facing errors.
- **No new page-specific CSS file.** Touched regions go Tailwind-first using existing tokens and
  the `mobile:` / `tablet:` variants. `.ticket-detail-grid`, `.ticket-events`, `.ticket-event`,
  `.event-dot` are removed from `styles.css` only once nothing references them, with before/after
  screenshots. The one-off `@media (max-width: 900px)` at `styles.css:1720` goes away with
  `.ticket-detail-grid` — it is not replaced by a new breakpoint.
- **CSS cleanup follows `PHASE_5A_CSS_VISUAL_CLEANUP.md`.** Remove ticket-detail visual layers
  only when the remaining structure still communicates current work, waiting, blockers,
  completion, history and temporary decisions. Do not flatten the page into an undifferentiated
  white sheet.
- **Legacy button migration is scoped to touched surfaces only.** `.primary-button` /
  `.secondary-button` / `.icon-button` definitions stay in `styles.css` for the ~20 pages that
  still use them.
- **Page-scoped class names must not be shared** — Phase 4A's `.ticket-table` →
  `.ticket-worklist-table` incident. Any new class is prefixed `ticket-workspace-`.

## Rollback Boundaries

| Unit | Rolls back with |
|---|---|
| `Tabs.jsx` / `InlineAlert.jsx` / `DescriptionList.jsx` / `StickyActionBar.jsx` / `Timeline.jsx` | each with its own test; no other consumer this slice, so each reverts independently |
| `Modal.jsx` A-05 | `Modal.jsx` + `Modal.test.jsx` + any consumer that opted into `describedBy` — **one unit**, because it touches all 23 consumers |
| The tab shell | restoring `TicketDetailPage.jsx` alone returns the stacked layout; the extracted `tabs/*.jsx` files are additive and the child panels are unchanged |
| `dealWorkState.js` + `DealWorkStateBanner.jsx` | pure and side-effect-free; reverting restores the inline `nextAction`/`waitingHint` block in `DealStateHeader` |
| `queryKeys` defect fix | one line + one test; independently revertible |
| CSS | keep every edit in labelled `/* Phase 5A */` blocks; do not delete broad `styles.css` sections in this slice |

No backend, API, database, permission, status-machine or route change is made, so rollback
requires no server or migration work.

## Explicit Exclusions

Not built, not touched, not "while we're here":

- `/tickets/new`, draft persistence, create-ticket redesign, `TicketCreateModal` — **that is
  Phase 6.**
- Any new backend work-state field, endpoint, permission, status value, or database migration.
- Pricing, quotation, payment, deposit, fulfilment or commission **calculation** changes.
- Fulfilment business-rule changes; status-machine transitions.
- Navigation architecture migration (D-01/D-06 remain proposals); `SalesTabs` route pills.
- Full `styles.css` cleanup; whole-application Tailwind migration; the 122 `max-[720px]`
  literals outside touched files.
- Dark mode; any new component framework, animation library, font or icon library.
- Drawer / FilterBar / ApprovalTask / WorklistRow primitives — no consumer in this slice.
- `ChangePasswordModal` consolidation (F-19); `EmptyState` CTA slot (F-18 §18); `SET_ENTRY_CHANNEL`.
- Unrelated pre-existing baseline failures — record them, do not silently fix them.

## Risks

1. **Tab-gated query mounting is a real behaviour change.** Panels behind an inactive tab do not
   mount, so their queries do not fire. Intended (fewer requests on open) but it changes when
   `depositNotices` / `ticketDeliveries` / `customerQuotations` load and therefore when their
   `availableActions`-dependent buttons appear. Test explicitly, and never let a hidden tab's
   absence suppress an action the sticky bar should show.
2. **34 existing `TicketDetailPage.test.jsx` tests will break on structure, not behaviour.**
   Re-scope them to open the owning tab first — **do not delete or weaken an assertion** to make
   it pass.
3. **`Modal.jsx` touches 23 consumers.** The portal changes where the DOM lands, which can break
   any test querying within a container. Budget for a suite-wide sweep.
4. **The work-state classifier can invite a 403.** Mis-classifying *Waiting* as
   *Needs-my-action* would offer a user an action the service rejects. Every
   `needs_my_action` branch must be backed by an `availableActions` entry, and the tests must be
   written wrong-way-round.
5. **`styles.css` is `layer(legacy)`** and loses to Tailwind utilities on the same property.
   Measure computed styles before believing a CSS finding; do not reach for `!important`.
6. **Mock-only verification.** Everything here runs under `VITE_USE_MOCKS=true`. The mock's
   authz approximates the Java services and is known to diverge. Nothing in this phase may be
   reported as verifying a permission rule.
7. **Evidence PNGs are LFS-tracked.** Do not commit multi-MB blobs outside LFS.

## Implementation Checkpoints

1. Branch from the latest `origin/main`; re-record the lint / test / build / e2e baseline.
2. Build and unit-test the five shared primitives (`Tabs`, `InlineAlert`, `DescriptionList`,
   `StickyActionBar`, `Timeline`) with no consumer wired up yet.
3. Fix `Modal.jsx` A-05 (labelling, describedby, portal, inert); run the full suite to flush out
   consumer breakage before anything else lands on top of it.
4. Fix `queryKeys.ticket(ticketId)` → `queryKeys.ticketDetail(ticketId)` at
   `TicketDetailPage.jsx:383,396`; add the attachment-toast regression test.
5. Write `dealWorkState.js` + its tests; wire `DealWorkStateBanner` into the header, replacing
   the inline `queueText` block. No layout change yet.
6. Extract the seven tab files **verbatim** — no restyling. Suite must stay green with the page
   still rendering them stacked.
7. Apply the component-boundaries contract while introducing `DealWorkspaceHeader`,
   `DealMetadataStrip`, `DealWorkspaceTabs` and the `Tabs` shell + `?tab=` (replace) routing +
   the overview fallback. Re-scope the 34 existing tests. Enforce the first-viewport contract:
   compact command header, no five-card summary row, no full pipeline by default, and tab
   navigation visible as the detail map.
8. Add the `StickyActionBar` and retire the "การดำเนินการอื่น ๆ" mid-page panel.
9. Restyle the touched regions and complete the component-boundary, container, spacing, CSS
   visual-cleanup, state-feedback, badge, action-hierarchy, mobile-workspace and
   tab-simplification audits:
   `DescriptionList` for
   ข้อมูลทั่วไป, `Timeline` for the event list, `<Button>` for legacy classes, tokens for inline
   styles, Thai-first label fixes, remove the F-14 back bar. Enforce the one-panel-deep rule and
   replace decorative/nested cards or unsupported metric cards with sections, dividers,
   description lists, table rows, tonal insets, compact stat strips or inline summaries. Consolidate
   touched spacing onto the Phase 5 rhythm above instead of adding one-off margins. Apply the
   CSS cleanup contract, state-feedback contract, badge contract, action hierarchy, mobile
   contract, component-boundary contract and tab-specific contracts for Overview, Pricing,
   Quotations, Money, Fulfilment, Documents and Activity.
10. Responsive pass across all five viewports; enforce the `390 x 844` mobile workspace
    contract and its stress scenarios; add the desktop context panel above 1040px; remove
    `.ticket-detail-grid` and its 900px media block.
11. Author the `COMPONENT_CONTRACTS.md` addendum (§22 Tabs, §23 Deal work-state banner).
12. Run the focused tests, then the full baseline; write `phase5a-acceptance.spec.js`; capture
    evidence with `CAPTURE_EVIDENCE=1`.
13. Fill in `PHASE_5A_IMPLEMENTATION.md`, the QA inventory/results/matrix docs, and the
    Change Control checklist. Report the permission aspect as **unverified — mock only**.
14. **Stop and report instead of silently fixing any unrelated failure.**

## Authorization Statement

This phase changes **no** permission, role gate, scope or filter. Tab visibility is presentation
projection mirroring `frontend/src/features/tickets/salesViewScope.js`, which is explicitly not a
security boundary; the backend enforces per endpoint and is untouched.

All verification runs under `VITE_USE_MOCKS=true`. Per `CLAUDE.md`, mock authorization is **not
authoritative**. Nothing in this phase's reporting may describe role scoping as tested — the
permission aspect is **unverified (mock only)**, and that is acceptable here precisely because no
permission is being changed.
