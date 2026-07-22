# Agent Handoff

## Task
Phase 2 Slice S1 (backend "engine collapse"): retire the legacy ticket-native pricing/quotation
HTTP routes that `TicketService.submit()` already permanently severed for new deals (always 409s
from `draft`), without breaking read access to the 3 legacy tickets stranded in
submitted/in_review/price_proposed status or the 3 legacy `sales.quotation` rows
(`pricing_request_id IS NULL`) that predate the PricingRequest (PCR) redesign. Owner-authorized
sales-workflow reconciliation, backend-only (frontend rebuild is Slice S2).

## Branch
`feat/deal-workspace-unification` (stacked on Phase-1 commission + Track-B `deal_activity`
tracking work already committed on this branch — untouched by this slice).

## Base Commit
`1b189bf9f5c9a96c997882d4440ccf46b235f9f6` (tip of the branch when this session started; working
tree was clean).

## Current Commit
Not committed — per instructions, this session left the changes on disk uncommitted.

## Agent / Model Used
Claude Sonnet 5 (Claude Code)

## Scope

### In Scope
- Remove the 11 legacy `@PostMapping` routes from `TicketController` listed below.
- Mark their 10 backing `TicketService` methods `@Deprecated` with Javadoc pointing at the PCR /
  `PricingDecisionService` / `CustomerQuotationService` / `OrderConfirmationService` replacement
  (deprecate-in-place, not delete — `submit()` was already deprecated in an earlier step and was
  left as-is).
- Strip the now-dead `TicketActionsResponse` action verbs that pointed at the removed routes
  (`PICKUP`, `PROPOSE_PRICE`, `APPROVE`, `REJECT`, `CALCULATE_PRICES`, `OVERRIDE_ITEM_PRICE`,
  `GENERATE_QUOTATION`, `MARK_QUOTATION_SENT`, `MARK_QUOTATION_ACCEPTED`,
  `MARK_QUOTATION_REJECTED`) so `GET /{id}/actions` never advertises a verb that would now 404 —
  see "Decisions Made" below for why this was in-scope.
- Confirm the 3 stranded legacy tickets and legacy quotations still load/serialize.
- Update backend unit tests for the removed action verbs; run the full backend suite.
- Write this handoff.

### Out of Scope
- Frontend (`TicketDetailPage`, `hrApi.js`, `mockApi.js`) — that is Slice S2, explicitly deferred.
- Commission (`commission/*`) and Track-B `deal_activity`/tracking code — untouched, per the
  branch's existing stacked work.
- Any business-logic change to the PCR/CustomerQuotation/OrderConfirmation chain itself — this
  slice only removes dead surface area in front of it.
- Deleting the deprecated `TicketService` methods, `TicketRequests`/`TicketResponses` DTOs (e.g.
  `CalculatePricesResponse`), or the `TicketStatus` enum values — deletion cascades widely
  (event-kind history, DTOs, repository columns still populated for legacy rows) and was not
  asked for.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/ticket/TicketController.java`:
  - Removed 11 `@PostMapping`/`@PutMapping` route methods (see "Exact Routes Removed" below) and
    replaced each block with an explanatory comment pointing at the retained
    `TicketService`/`CustomerQuotationService` Javadoc.
  - Removed the now-unused `import th.co.glr.hr.ticket.TicketResponses.CalculatePricesResponse;`.
  - Left every other route (reads, quotation file download, close/verify, deal-pipeline,
    tracking/activities, the whole dual-track post-quotation/fulfilment chain) unchanged.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java`:
  - Added `@Deprecated` + Javadoc to `pickup`, `proposePrice`, `approve`, `reject`,
    `generateQuotation`, `markQuotationSent`, `markQuotationAccepted`, `markQuotationRejected`,
    `calculatePrices`, `overrideItemPrice` (10 methods; `submit` was already deprecated).
  - Removed the `addQuotationActions` private method and its only call site in `actions()`, and
    removed the `canGenerateQuotation` helper and the `PICKUP`/`PROPOSE_PRICE`/`APPROVE`/
    `REJECT`/`CALCULATE_PRICES`/`OVERRIDE_ITEM_PRICE`/`GENERATE_QUOTATION` blocks from
    `addOperationalActions`, each replaced with an explanatory comment. No other method body
    changed — `markQuotationLifecycle`, `legalQuotationTransition`, `canManageQuotation`,
    `QUOTATION_ALLOWED_STATUSES`, `PROPOSE_ALLOWED_STATUSES` all stay (still referenced by the
    retained-but-deprecated methods).
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java`:
  - `actions_requireViewAccessAndReflectLifecycle`: flipped the `quotationOwner`/
    `quotationManager` assertions from `.contains("MARK_QUOTATION_SENT", ...)` to
    `.doesNotContain(...)` (now also asserting `GENERATE_QUOTATION` is absent), since those
    verbs no longer exist in `actions()`.
  - Added a new test `actions_neverOffersRetiredLegacyPricingVerbs`, mirroring the existing
    `actions_neverOffersSubmit` precedent, proving `PICKUP`/`PROPOSE_PRICE`/`APPROVE`/`REJECT`/
    `CALCULATE_PRICES`/`OVERRIDE_ITEM_PRICE`/`GENERATE_QUOTATION` are never offered — exercised
    against the real stranded statuses (`SUBMITTED`, `IN_REVIEW`, `PRICE_PROPOSED`, `APPROVED`)
    for the roles that used to see them (import/ceo/sales owner).
  - Every other test that calls `ticketService.pickup()/proposePrice()/approve()/reject()/
    calculatePrices()/overrideItemPrice()/generateQuotation()/markQuotationSent/Accepted/
    Rejected()` directly (bypassing the controller) was left as-is — those methods still exist
    with identical behaviour (deprecated, not deleted), so those unit tests remain valid
    regression coverage for code that is still present in the codebase, just unrouted.

## Exact Routes Removed (from `TicketController`)
1. `POST /{id}/submit`
2. `POST /{id}/pickup`
3. `POST /{id}/propose-price`
4. `POST /{id}/approve`
5. `POST /{id}/reject`
6. `POST /{id}/quotation` (legacy ticket-native quotation generate)
7. `POST /{id}/quotations/{quotationId}/sent`
8. `POST /{id}/quotations/{quotationId}/accepted`
9. `POST /{id}/quotations/{quotationId}/rejected`
10. `POST /{id}/calculate-prices`
11. `PUT /{id}/items/{itemId}/price-override`

## Methods Deprecated (in place, on `TicketService`)
`pickup`, `proposePrice`, `approve`, `reject`, `generateQuotation`, `markQuotationSent`,
`markQuotationAccepted`, `markQuotationRejected`, `calculatePrices`, `overrideItemPrice`
(`submit` was already `@Deprecated` from an earlier step in this redesign).

## What Was Kept, and Why
- `GET /{id}/quotations/{quotationId}/file` (→ `getQuotationXlsx`/`getQuotationPdf`) — untouched.
  Both legacy (`pricing_request_id IS NULL`) and PCR-issued quotations render from this one path;
  removing it would make the 3 legacy `sales.quotation` rows unreadable, which the task
  explicitly forbids.
- Every downstream operational endpoint (confirm-customer, deposit, import-request/ir-sent/
  shipping/goods-received, reserve-stock, deliveries, payments/billing/final-payment,
  close/confirm+verify, stage/lost/reopen/hold/dormant/resume/cancel, tracking/activities) —
  untouched; none of it depends on the removed legacy pricing loop.
- `GET /{id}` and `GET /{id}/actions` — untouched at the route level. `actions()`'s *output* for
  legacy statuses did change (see below), but the method itself, its role/ownership gating, and
  its ability to load a ticket in any `TicketStatus.VALUES` status are unchanged.
- The 10 deprecated `TicketService` methods themselves (bodies unchanged) — kept rather than
  deleted so the historical `ticket_event` rows and DTOs they touch stay coherent, and so a
  future admin/migration tool could still invoke them directly if ever needed. Only the HTTP
  surface is gone.

## Decision Made Beyond the Literal Endpoint List: Pruning `actions()`
The task listed exactly which routes to remove and which `TicketService` methods to deprecate, but
did not explicitly mention `TicketService.actions()`. I pruned it anyway, for a concrete reason
found while reading the code, not as unrequested scope creep:

`GET /{id}/actions` is the contract a client (today: `TicketDetailPage`, out of scope for this
slice) uses to decide which buttons to show. Before this change, `addOperationalActions` still
offered `PICKUP`/`PROPOSE_PRICE`/`APPROVE`/`REJECT`/`CALCULATE_PRICES`/`OVERRIDE_ITEM_PRICE` for
tickets in `submitted`/`in_review`/`price_proposed` (i.e. **exactly** the 3 stranded legacy
tickets this task requires to stay readable), and `addQuotationActions` offered
`MARK_QUOTATION_SENT`/`ACCEPTED`/`REJECTED` for **any** ticket with an `ISSUED`/`SENT` quotation —
including a brand-new PCR-driven deal whose `CustomerQuotationService`-issued quotation reaches
`ISSUED` status via the *new* flow. `addOperationalActions` also offered `GENERATE_QUOTATION` for
any ticket at `APPROVED`/`QUOTATION_ISSUED`, which likewise includes new-flow deals (the
`OrderConfirmationService` bridge writes `ticket.status = quotation_issued` directly). With the
routes now removed, all of these would have advertised a verb that 404s on click — for both the
stranded legacy tickets *and*, for `GENERATE_QUOTATION`/`MARK_QUOTATION_*`, ordinary new deals.
This is exactly the failure mode the pre-existing `actions_neverOffersSubmit` test (from an
earlier step in this same redesign) was written to prevent for `SUBMIT` — I applied the same
reasoning to the newly-retired verbs and extended the test suite the same way
(`actions_neverOffersRetiredLegacyPricingVerbs`). This is confined to `TicketService`, does not
touch the frontend, and does not change what data a ticket returns — only which action verbs are
advertised as currently performable.

## Stranded-Data Visibility — How Verified
No production/live DB access in this session (Supabase MCP requires interactive OAuth, unavailable
here) — verification was via the backend test suite, which already stubs and exercises tickets in
exactly the three legacy statuses through the untouched read/action paths:
- `TicketServiceTest`: 38 references to `TicketStatus.SUBMITTED`/`IN_REVIEW`/`PRICE_PROPOSED`
  across dozens of tests calling `service.get()`/`service.actions()`/`service.comment()` etc.
  against tickets stubbed in those statuses — all pass unchanged (the read/serialize path was not
  touched by this slice; only route mappings and `actions()`'s advertised verb list changed).
- `TicketRepositoryIntegrationTest` (real Postgres via Testcontainers): exercises a
  `PRICE_PROPOSED` → event-logging path against the real repository/DB.
- New test `actions_neverOffersRetiredLegacyPricingVerbs` explicitly calls `service.actions()`
  against tickets in `SUBMITTED`, `IN_REVIEW`, and `PRICE_PROPOSED` — proving both that the call
  succeeds (ticket loads/serializes) and that no dead verb is advertised.
- Quotation read: `getQuotationXlsx`/`getQuotationPdf`/the `/file` route were not modified at all;
  `CustomerQuotationService`'s own Javadoc (read before coding, per instructions) confirms legacy
  and PCR quotations share the same `sales.quotation`/`sales.quotation_item` table and renderer,
  so no code path distinguishes `pricing_request_id IS NULL` rows for read access.

This is read-path/contract verification, not a live-DB query against the 3 real stranded rows —
flagging that distinction explicitly rather than overclaiming.

## Commands Run
```bash
git status
git log -1 --format=%H
cd backend && ./mvnw -q -B compile
cd backend && ./mvnw -q -B test-compile
cd backend && ./mvnw -q -B test -Dtest=TicketServiceTest
cd backend && ./mvnw -B clean verify
```

## Test / Build Results
- Backend unit + integration tests (`./mvnw -B clean verify`): **PASS** —
  `Tests run: 1096, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (~4m13s total).
  Integration tests **ran** (Docker available locally → Testcontainers, `TEST_DB_URL` unset):
  confirmed via `TicketScopeIntegrationTest` (18), `TicketRepositoryIntegrationTest` (10),
  `TicketEventStatusIntegrationTest` (3), `DealTrackingAndActivityIntegrationTest`, and
  `OrderConfirmationIntegrationTest` (6/6 — this is the test proving the order-confirm bridge is
  the sole path to `quotation_issued`; untouched by this slice, re-verified green).
  Jacoco coverage check: "All coverage checks have been met."
- `TicketServiceTest` alone: `Tests run: 178, Failures: 0, Errors: 0, Skipped: 0`.
- Frontend: **not run** — out of scope for this backend-only slice (S2 owns the frontend).

## Authz Evidence
**No authorization/role-gate change in this task.** Every `requireRole`/`requireOwner`/
`requireQuotationWriteAccess`/`canManageQuotation` check on the deprecated methods is byte-for-byte
unchanged — only the HTTP route in front of them was removed, and the `actions()` verb list was
pruned (which verbs are *suggested*, not who is *allowed* to call the underlying method). No new
role, scope, or filter was introduced or altered. Real-DB integration-test evidence is therefore
not required by CLAUDE.md's "Permission changes must ship evidence" section, and none was claimed.

## Decisions Made
- Deprecate-in-place for all 10 methods rather than delete, per the task's explicit preference
  ("prefer deprecation-in-place over deletion where deletion cascades widely").
- Pruned `TicketActionsResponse`'s dead verbs from `actions()` — see the dedicated section above
  for the reasoning and evidence this was necessary, not optional polish.
- Left `TicketResponses.CalculatePricesResponse` (now-unused DTO) and `TicketRequests`' now
  controller-unreferenced-but-service-referenced request records (`ProposePriceRequest`,
  `RejectRequest`, `GenerateQuotationRequest`, `OverridePriceRequest`) untouched — all are still
  used as parameter types by the retained (deprecated) `TicketService` methods, so nothing is
  actually dead there.
- Left the 178 existing `TicketServiceTest` tests that call the deprecated methods directly
  (`service.pickup(...)`, `service.approve(...)`, etc.) unchanged — they still test real,
  present, reachable-by-direct-call business logic and remain valid regression coverage.

## Assumptions
- "The backing TicketService methods" in the task instructions refers only to the 10 methods
  whose sole HTTP entry point was one of the 11 removed routes — not `markQuotationLifecycle`,
  `legalQuotationTransition`, or `canManageQuotation`, which remain live private helpers for the
  retained-but-deprecated public methods.
- The 3 stranded legacy tickets and 3 legacy quotations mentioned in the task are the same ones
  referenced in `docs/agent-handoffs/85_feat-sales-pricing-request-foundation.md`'s DB
  reconciliation and prior memory notes — no new live-DB query was run to re-confirm the count in
  this session (no DB credentials/MCP access available here).

## Known Risks
- `GET /{id}/actions` for one of the 3 stranded legacy tickets will now offer **fewer** actions
  than before (no `PICKUP`/`PROPOSE_PRICE`/etc.) — by design, since those buttons would 404. If
  anyone genuinely needs to push one of those 3 tickets through the legacy loop (e.g. via direct
  DB/service-layer intervention), the `TicketService` methods still exist and still work; there is
  simply no UI/HTTP path to trigger them anymore. This is the intended "engine collapse" outcome,
  flagging it so it isn't mistaken for a regression.
- Frontend `TicketDetailPage`/`hrApi.js`/`mockApi.js` still reference the retired endpoints (they
  were explicitly out of scope here) — until S2 lands, any frontend code path that calls
  `POST /submit`, `/pickup`, `/propose-price`, `/approve`, `/reject`, `/quotation`,
  `/quotations/{id}/{sent,accepted,rejected}`, `/calculate-prices`, or
  `/items/{id}/price-override` will now get a 404 instead of whatever it got before (409 for
  `submit`, or a real response for the others). Since `actions()` no longer advertises the verbs
  for these buttons, well-behaved frontend code driven off `GET /{id}/actions` won't reach them —
  but any hardcoded button not gated by `actions()` would surface a 404. Flagging for S2 to audit.
- `mockApi.js`'s contract test (`frontend/src/api/contract.test.js`) was **not run** in this
  session (frontend explicitly out of scope) — if it asserts `hrApi.js` method presence 1:1
  against `mockApi.js` and either still calls the now-dead backend routes, that test's frontend-CI
  status is unaffected by this backend-only change (no backend route removal breaks a frontend
  method-surface test), but S2 should verify.

## Things Not Finished
- Slice S2: rebuild `TicketDetailPage` into the unified deal workspace, and remove/redirect the
  now-dead `hrApi.js`/`mockApi.js` methods (`submit`, `pickup`, `proposePrice`, `approve`,
  `reject`, `generateQuotation`, `markQuotationSent/Accepted/Rejected`, `calculatePrices`,
  `overrideItemPrice`) so the frontend stops calling routes that now 404.

## Recommended Next Agent
Claude Sonnet (implementation) for Slice S2, frontend-focused.

## Exact Next Prompt
```
Phase 2 Slice S2 of the deal-workspace-unification branch (feat/deal-workspace-unification):
rebuild the frontend to match the backend "engine collapse" done in Slice S1
(docs/agent-handoffs/104_feat-deal-workspace-unification.md).

Backend context: TicketController no longer exposes POST /{id}/submit, /pickup,
/propose-price, /approve, /reject, /quotation (legacy generate),
/quotations/{quotationId}/{sent,accepted,rejected}, /calculate-prices, or PUT
/{id}/items/{itemId}/price-override. TicketService.actions() no longer advertises PICKUP/
PROPOSE_PRICE/APPROVE/REJECT/CALCULATE_PRICES/OVERRIDE_ITEM_PRICE/GENERATE_QUOTATION/
MARK_QUOTATION_SENT/ACCEPTED/REJECTED. Quotation download (GET .../file) and the entire
downstream operational chain (confirm-customer, deposit, import-request, deliveries, payments,
close, deal-pipeline, tracking) are untouched. Pricing now runs through
POST /api/tickets/{ticketId}/pricing-requests + the PricingRequest/FactoryQuote/
PricingDecision/CustomerQuotation/OrderConfirmation chain (see those services' own classes
under backend/src/main/java/th/co/glr/hr/{pricingrequest,factoryquote,pricingdecision,
customerquotation,orderconfirmation}).

Read CLAUDE.md first, then this handoff (104) and 85_feat-sales-pricing-request-foundation.md
for the chain's design. Task:

1. Audit frontend/src for every call site touching the retired routes (grep TicketDetailPage,
   hrApi.js, mockApi.js for submit/pickup/proposePrice/approve/reject/generateQuotation/
   markQuotationSent/Accepted/Rejected/calculatePrices/overrideItemPrice) and confirm whether
   any are still reachable by a real user click today (not just gated behind actions()).
2. Rebuild TicketDetailPage (or design a new unified "deal workspace" view per the task name)
   to drive the deal through the PCR/CustomerQuotation/OrderConfirmation chain end-to-end,
   replacing whatever UI currently assumes the legacy submit->pickup->propose->approve->
   generate-quotation loop.
3. Remove or clearly mark-dead the now-unreachable hrApi.js/mockApi.js methods for the 11
   retired routes — check frontend/src/api/contract.test.js (mockApi <-> hrApi method-surface
   parity) before deleting anything, and use its KNOWN_GAPS mechanism if a method must stay for
   contract-test reasons.
4. The 3 legacy tickets stranded in submitted/in_review/price_proposed and their quotations
   must remain READABLE in the new UI (read-only historical view is fine — no requirement to
   support acting on them).
5. Run cd frontend && npm run lint && npm test && npm run build; report exact results.
6. Update this handoff or create the next NN_ handoff with what changed, and do not touch
   backend/commission/deal_activity code.
```

---

## Slice S2 (frontend "deal workspace unification") — completed

Implemented the frontend side of the engine collapse: removed the dead ticket-native pricing/
quotation surface end to end (routes.js → hrApi.js → mockApi.js → TicketDetailPage), added a new
`DealStateHeader` state-header component, and pulled the PricingRequest chain's customer-facing
quotation tail (issue/outcome) + order-confirm/deposit-notice bridge onto the deal page via a new
`DealQuotationPanel`, so a rep no longer has to leave the deal to move a priced PCR to a signed
order. Session left uncommitted per instructions.

### Files Changed
- `frontend/src/api/routes.js` — removed `tickets.quotationStatus` (only used by the 3 retired
  markQuotation* methods); kept `tickets.quotationFile` (download, untouched) and the generic
  `tickets.action` helper (still backs every kept operational method).
- `frontend/src/api/hrApi.js` — removed `tickets.submit/pickup/proposePrice/calculatePrices/
  approve/reject/quotation/markQuotationSent/markQuotationAccepted/markQuotationRejected/
  overrideItemPrice` (11 methods). Every other `tickets.*` method (including
  `downloadQuotationXlsx/Pdf`) is untouched.
- `frontend/src/api/mockApi.js` — removed the matching 11 mock handlers plus the now-dead private
  helpers they alone used (`markQuotationStatus`, `verifyStatus`). Pruned
  `tickets.actions()`'s advertised-verb list to drop `PICKUP`/`PROPOSE_PRICE`/`CALCULATE_PRICES`/
  `OVERRIDE_ITEM_PRICE`/`APPROVE`/`REJECT`/`GENERATE_QUOTATION`/`MARK_QUOTATION_SENT`/
  `MARK_QUOTATION_ACCEPTED`/`MARK_QUOTATION_REJECTED` — mirrors the backend's own `actions()`
  pruning from Slice S1, so the mock never advertises a button that would now 404. Quotation
  read/download (`downloadQuotationXlsx/Pdf`, `buildMockQuotationXlsx/Html`, `normalizeQuotation`,
  the seeded legacy quotations on ticket id 6) are untouched — the 3 legacy quotations still
  render.
- `frontend/src/features/tickets/DealStateHeader.jsx` (new) — the state-header: deal code/title/
  customer + lifecycle badge, a stat strip (sales stage / PCR status / payment status /
  fulfilment status / deal value), and a "ถึงคิวคุณ: …" line with the one primary CTA that mirrors
  it. Reads straight off `summary`, the already-fetched `pricingRequests` list, and the parent's
  already-computed `nextAction`/`waitingHint`/`primaryAction` — invents nothing new.
- `frontend/src/features/tickets/DealQuotationPanel.jsx` (new) — "ราคาและใบเสนอราคา": for the
  deal's PCR that has reached `APPROVED_FOR_QUOTATION`/`QUOTATION_ISSUED`/`QUOTATION_ACCEPTED`
  (picks the most recently created one if several qualify; renders nothing if none do), surfaces
  create/issue/download/outcome for the customer quotation plus confirm-order +
  create-deposit-notice-from-quotation, reusing `pricingRequestMeta.js`'s exact predicates and
  `hrApi.pricingRequests.*` methods `PricingRequestDetailPage.jsx` already uses. Links out to
  `/pricing-requests/{id}` for line-item/discount editing and the factory/costing/CEO-price steps
  (deliberately NOT duplicated here — see file's doc comment for why).
- `frontend/src/features/tickets/TicketDetailPage.jsx` — the big diff:
  - Removed: `proposeMode`/`draftRaw`/`draftFactoryCurr`/`proposeNote`/`emailDraft`/
    `emailSending` state + `initPropose`/`handleProposePrice`/`groupByFactory`/`buildEmailDraft`/
    `sendFactoryEmail` handlers + the whole propose-mode items-table render branch (factory
    grouping, currency/unit bar, email-draft panel); `factoryConfigsQuery` (nothing left on this
    page needs it once propose-mode is gone).
  - Removed: `showRejectForm`/`rejectReason` state + `handleReject` + the "การอนุมัติราคา" CEO
    decision panel (approve/reject buttons + reject-reason form).
  - Removed: `priceBreakdown`/`showBreakdown` state + `calculatePricesMutation`/
    `handleCalculatePrices` + the "รายละเอียดสูตรคำนวณราคา" breakdown section + the "คำนวณราคา
    (CIF)"/"ดูรายละเอียดสูตร" buttons.
  - Removed: `overrideDraft` state + `overrideMutation`/`handleOverridePrice`/
    `isOverridingItem` + the per-item CEO manual-override input UI (kept a read-only "override"
    label on `item.manualPrice`, since the 3 stranded legacy tickets may already carry one).
  - Removed: `quotationModal`/`quotationDraft` state + `openQuotationModal`/
    `closeQuotationModal`/`submitQuotation`/`markQuotation` + the whole "ออกใบเสนอราคา" Modal
    JSX block.
  - `can.{pickup,propose,calculatePrices,overridePrice,approve,reject,generateQuotation}` all
    removed from the `can` object; `NEXT_ACTION_STEPS` and `primaryAction` trimmed to match
    (dual-track/close steps only — quotation issue/outcome/confirm-order now live in
    `DealQuotationPanel`, which has its own primary buttons).
  - "ใบเสนอราคา" (legacy) section retitled "ใบเสนอราคา (เอกสารเดิม)": kept read-only (StatusBadge +
    Excel/PDF download), removed the "Revise"/"ส่งแล้ว"/"รับแล้ว"/"ปฏิเสธ" buttons — per task,
    stranded pre-redesign quotations stay visible, not actionable.
  - `docActions` (in `DealStagePanel`'s doc row): removed the `can.generateQuotation` "ออกใบเสนอ
    ราคา(ใหม่)" button; kept the legacy-quotation PDF download, deposit-notice, and IR buttons.
  - Mounted `DealStateHeader` in place of the old bare `<header>` (title/code/status/refresh) —
    the old ticket-status badge + "สร้างโดย/เจ้าหน้าที่นำเข้า" byline moved to a small row directly
    below the header, unchanged in content.
  - Mounted `DealQuotationPanel` right after `PricingRequestPanel`, gated on
    `sections.dealQuotation && canViewPricingRequests`.
  - **`DealStagePanel` no longer receives `primaryAction`** (see "Decisions Made" below) — it
    still receives `guidance` and renders its own advance-stage/hold/dormant/lost/docActions
    controls unchanged.
  - Cleaned up now-stale comments (`fieldErrors` key list, `sections.priceApproval` doc comment)
    and the now-unused `InfoTip` import.
- `frontend/src/features/tickets/salesViewScope.js` — removed the `priceApproval` section id
  (the panel it gated is gone); added `dealQuotation` (visibility mirrors the existing
  `quotation` section: hidden from `import`, visible to `account`/`sales`/`sales_manager`/`ceo`).
- `frontend/src/features/tickets/salesViewScope.test.js` — updated the 4 assertions that
  referenced `priceApproval` to instead assert `dealQuotation`'s visibility per role.
- `frontend/src/features/tickets/TicketDetailPage.test.jsx` — removed tests for the deleted
  approve/reject/quotation-modal/CEO-override flows (7 tests); added `MemoryRouter` around the
  render helper (`DealQuotationPanel` uses `useNavigate`/`Link`); added tests proving the retired
  controls are gone even under a stale `actions()` payload, the `DealStateHeader` stat strip
  renders, the legacy quotation section is read-only, and a `describe('deal quotation panel')`
  block (renders nothing pre-`APPROVED_FOR_QUOTATION`, the owning sales rep can create a
  customer-quotation draft once eligible, import never sees the section). Net: 20 tests (was 24;
  removed 7, added ~7 net different), all passing.

### Commands Run
```bash
cd frontend
npx vitest run src/api/contract.test.js
npx eslint src/api/mockApi.js src/api/hrApi.js src/api/routes.js
npx vitest run src/features/tickets/salesViewScope.test.js
npx eslint src/features/tickets/TicketDetailPage.jsx src/features/tickets/DealStateHeader.jsx \
  src/features/tickets/DealQuotationPanel.jsx src/features/tickets/salesViewScope.js
npx vitest run src/features/tickets/TicketDetailPage.test.jsx
npm run lint
npm test -- --run
npm run build
```

### Test / Build Results
- `npm run lint`: **PASS** — 0 errors, 1 pre-existing warning unrelated to this slice
  (`PayrollPage.jsx` exhaustive-deps).
- `npm test -- --run`: **PASS** — 48 test files, 420 tests, all green (includes the updated
  `TicketDetailPage.test.jsx` at 20/20 and `salesViewScope.test.js` at 18/18).
- `npm run build`: **PASS** — `vite build` succeeds, no errors.

### Live Mock Self-Check (VITE_USE_MOCKS=true)
Ran a fresh `npm run dev -- --port 5201 --strictPort` (own port — port 5200 from
`.claude/launch.json`'s `frontend-mock` config was already occupied by a **different worktree's**
stale vite process, `.claude/worktrees/pricing-chain-uat/frontend`; see the
`concurrent-session-worktree-collision` gotcha — did not touch or kill that process, used a fresh
port instead) and drove it through the Browser pane:
- **Sales**, deal `PR-2026-0007` (no PCR yet): `DealStateHeader` renders (code, lifecycle badge,
  5-chip stat strip, no queue line since nothing is currently actionable for this viewer); items
  table, `PricingRequestPanel` ("ยังไม่มีใบขอราคา"), payment/delivery sections, "การดำเนินการอื่น ๆ"
  (only แก้ไขรายการสินค้า/ยกเลิก — **no dead price-approval/propose/quotation-generate controls**)
  all render; no console errors.
- **CEO**, deal `PR-2026-0006` (one of the 3 stranded legacy tickets — status `quotation_issued`,
  2 legacy quotations seeded, no PCR): `DealStateHeader` shows "ถึงคิวคุณ:
  ยืนยันว่าลูกค้าชำระส่วนที่เหลือครบแล้ว" + the single "ยืนยันชำระครบ (Final Payment)" primary CTA
  (confirmed via `read_page` there is exactly **one** button with that name on the page — proves
  `DealStagePanel` no longer duplicates it); clicking it opens the real
  `ยืนยันการรับชำระครบถ้วน` confirm dialog with the correct ฿240,000 outstanding amount (cancelled
  without confirming, to leave state untouched); "ใบเสนอราคา (เอกสารเดิม)" shows both legacy
  quotation revisions (QT-2026-0001 ผู้ออกแบบ, QT-2026-0101 เจ้าของ) with only Excel/PDF download
  buttons — **no Revise/ส่งแล้ว/รับแล้ว/ปฏิเสธ buttons** — proving the stranded ticket and its
  legacy quotations stay readable and inert; event history still shows historical
  `PICKED_UP`/`PRICE_PROPOSED`/`APPROVED`/`QUOTATION_ISSUED` event labels (read-only, unchanged);
  `DealQuotationPanel` correctly does not render (no PCR on this legacy ticket); no console
  errors.
- **Not verified live**: the full PCR → factory-quote → costing → CEO-decision →
  `APPROVED_FOR_QUOTATION` → `DealQuotationPanel` create/issue/outcome/confirm-order path click-
  by-click in the browser (no seed data reaches that PCR status; driving it through the UI from
  scratch is a long multi-role sequence). This path **is** covered by automated tests instead:
  `DealQuotationPanel.jsx` reuses `PricingRequestDetailPage.jsx`'s exact predicates/mutations
  (already covered by `PricingRequestDetailPage.test.jsx`'s 40 tests), and the new
  `describe('deal quotation panel')` block in `TicketDetailPage.test.jsx` exercises the
  create-customer-quotation-draft call from the deal page directly. Flagging this as the one gap
  in live verification, not claiming more than was checked.

### Authz Evidence
**No authorization/role-gate change in this slice.** Every predicate `DealQuotationPanel` uses
(`canCreateCustomerQuotation`, `canManageCustomerQuotation`, `canViewCustomerQuotation`,
`canRecordCustomerQuotationOutcome`, `canConfirmOrder`, `canCreateDepositNoticeFromQuotation`) is
imported verbatim from `pricingRequestMeta.js` — none were added, edited, or newly composed here.
`salesViewScope.js`'s `dealQuotation` section id is presentation-only (per that file's own doc
comment, "never a security boundary") and mirrors the existing `quotation` section's role
visibility exactly. mockApi's `actions()` pruning removes *advertised UI verbs*, not permission
checks — the underlying (now-unrouted) `TicketService` methods' role/ownership gates are
untouched (see Slice S1's own Authz Evidence). Verification here ran under `VITE_USE_MOCKS=true`
only, as it does for every other frontend-only slice on this branch — no authz claim beyond that.

### Decisions Made
- **`primaryAction` lives in `DealStateHeader` only, not `DealStagePanel` too.** First pass passed
  the same `primaryAction` node to both (header for "one primary CTA", `DealStagePanel` unchanged
  from before this slice) — this rendered the identical button twice on one page and broke 2
  existing tests (`TestingLibraryElementError: Found multiple elements with role "button" and
  name "ยืนยันชำระครบ (Final Payment)"`). Fixed by no longer passing `primaryAction` into
  `DealStagePanel` (confirmed via grep that `TicketDetailPage.jsx` is `DealStagePanel`'s only
  caller, so this is safe/local). `DealStagePanel.test.jsx` doesn't test `primaryAction`
  directly, so this didn't need a `DealStagePanel` test update.
- **`DealQuotationPanel` picks "the most recently created PCR at/after
  `APPROVED_FOR_QUOTATION`"**, not "the deal's only active PCR" — a deal can have several
  pricing-request rounds (revisions/re-quotes); this mirrors `activePricingRequestsSummary`'s own
  "don't reduce to a single highest-id winner across ALL statuses" lesson, but scoped to just the
  statuses where a customer quotation can exist.
- **Full line-item/discount editing and the factory/costing/CEO-price steps stay on
  `PricingRequestDetailPage`**, linked from `DealQuotationPanel` — duplicating
  `PricingRequestDetailPage`'s ~300-line item-draft/discount-editing UI onto the deal page would
  have doubled the diff size and created two divergent implementations of the same mutation
  (`updateCustomerQuotation`) to keep in sync. Matches the task's own instruction.
- **The legacy "ใบเสนอราคา" section stays as its own section** (retitled "(เอกสารเดิม)"), separate
  from the new `dealQuotation`/`DealQuotationPanel` section, rather than merging them — they read
  from genuinely different data sources (`ticket.quotations`, `pricing_request_id IS NULL`, vs.
  `api.pricingRequests.listCustomerQuotations`) and conflating "here's your old read-only
  paperwork" with "here's your live actionable quotation" would have been confusing.
- Kept `hrApi.factoryConfigs.list`/`.sendEmail` and their `mockApi`/`routes.js` definitions
  untouched (not in the task's explicit 11-method removal list) even though
  `TicketDetailPage.jsx` no longer calls them anywhere — removing them was out of scope for this
  slice; flagging as a known follow-up below rather than silently expanding the diff.

### Known Risks
- `frontend/src/api/hrApi.js`'s `factoryConfigs.list`/`.sendEmail` (and their `mockApi.js`/
  `routes.js` counterparts) are now **unreachable from any frontend call site** —
  `TicketDetailPage.jsx` was their only caller, and it no longer calls them once propose-mode is
  gone. Left in place deliberately (see Decisions Made) since removing them wasn't in this
  slice's explicit scope; a follow-up cleanup could remove them or find them a new home if the
  factory-RFQ-email idea is still wanted somewhere.
- `TicketResponses.CalculatePricesResponse` / `TicketRequests.ProposePriceRequest,
  RejectRequest, GenerateQuotationRequest, OverridePriceRequest` (backend DTOs, noted as
  intentionally kept in Slice S1) are now doubly orphaned — no backend route AND no frontend
  caller. Still out of scope here (backend-only cleanup).
- The 3 stranded legacy tickets/quotations were spot-checked via one of them (`PR-2026-0006`,
  ticket id 6) in the live mock, not all 3 individually — the other 2 share the exact same render
  path (same `sections.quotation`/`quotationGroups` code, no per-ticket special-casing), so this
  is considered representative, not exhaustive.

### Things Not Finished
None for this slice's stated scope. Possible future follow-ups (not requested, not started):
removing the now-fully-orphaned `factoryConfigs` email-draft aggregate; surfacing a direct link
from `PricingRequestPanel`'s row list to `PricingRequestDetailPage` (today only
`DealQuotationPanel` links out, and only once a PCR reaches `APPROVED_FOR_QUOTATION`).

## Recommended Next Agent (updated)
Slice S2 is done. Next: none currently planned — this was the last slice named in the branch's
task list. If further work lands on this branch, read this handoff plus Slice S1's section above
first.
