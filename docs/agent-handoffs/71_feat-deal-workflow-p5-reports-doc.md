# Agent Handoff — Phase 5 of the branching-workflow program (FOR CODEX)

> **Program context.** Phases 1–4 are done and Opus-reviewed, all DRAFT do-not-merge:
> lifecycle/policies/actions (#228), per-recipient quotations (#229), payment ledger (#230),
> fulfilment + partial delivery (#231). **This is Phase 5 — the FINAL phase.** After it passes
> Opus review the whole program gets user sign-off and only THEN does anything merge. Implement
> ONLY what this document specifies. There is NO production data.

## Task
On a new branch `feat/deal-workflow-p5-reports` **branched from
`feat/deal-workflow-p4-fulfilment`** (NOT from base — Phase 5 stacks on Phase 4):

Deliver the three things that turn the branching workflow from "built" into "operable and
documented":

1. **Reports / filters** — surface the new dimensions the earlier phases added (lifecycle
   buckets, payment-overdue, partial-delivery) on the deal list and the sales dashboard, so
   staff can actually *find* on-hold / overdue / partially-delivered deals.
2. **`docs/sales-workflow.md`** — the canonical current-state workflow doc: transition matrix,
   the 12 branching cases mapped to concrete action sequences, derivation rules for the
   auto-stages, and the S1–S20 ↔ new-model cross-reference.
3. **Close the 22-scenario test matrix** — map each scenario to an existing test or add the
   missing one, so the whole program has a named, auditable coverage set.

**This is mostly reporting UI + docs + tests. It is NOT a new domain feature.** No new
lifecycle/payment/fulfilment *mutations*, no schema migration is required for deliverables 1 & 3
(all needed fields already exist on the DTOs). The ONLY backend code change is additive
read-only aggregation counts on the dashboard (deliverable 1, see §Backend). Do not touch
pricing, the CEO price gate, the payment ledger, or delivery mutations.

## NO new business logic (scope guard)
Every dimension you are exposing already exists as data:
- `lifecycle` (ACTIVE/ON_HOLD/DORMANT/CLOSED_LOST/CANCELLED/COMPLETED) — Phase 1, on the summary DTO.
- `overdue` boolean (due_date past ∧ outstanding>0) — Phase 3, already computed per row.
- `fulfillmentStatus` incl. PARTIALLY_DELIVERED / FULLY_DELIVERED — Phase 4, on the summary DTO.

Phase 5 **reads and groups** these. It does not create new states, does not add filters that
change what a query *means*, and does not silently redefine an existing count (see the
`overdueOver3Days` warning in §Backend). No stage renames, no new event kinds.

## Non-negotiable invariants (carried from Phases 1–4)
1. **Owner scoping.** The deal list already scopes plain `sales` to their own deals
   (`TicketService.list/listPage`: `createdByFilter = "sales".equals(role) ? actor.id() : null`).
   Every new filter/count MUST respect that scope — a sales user's buckets count ONLY their own
   deals. Do NOT add a filter or dashboard count that leaks other owners' deals to a sales user.
   `sales_manager` / import / account / ceo see all, as today.
2. **No meaning changes to existing counts/queries.** The dashboard's existing
   `overdueOver3Days` is **ticket-age** based (`status NOT IN (...) AND created_at < :overdueBefore`),
   NOT payment-overdue. Leave it exactly as-is. Add a SEPARATE `paymentOverdue` count for the
   real due-date/outstanding overdue. Any queue-count change is additive and documented.
3. **Mock parity** (`frontend/src/api/contract.test.js`). Mock authz non-authoritative. No
   skipped tests, no lint suppressions, no commented-out logic. Keep `// Mirrors <JavaClass>`
   headers. If you extend the dashboard summary shape, extend BOTH `hrApi` and `mockApi`.
4. **Tailwind-first.** Filters/cards use Tailwind utilities + the existing shared components
   (StatCard/DataTable/StatusBadge pattern already on these pages). No new page-specific CSS file.
5. **Do not change business logic outside this spec.** Docs describe the system AS BUILT — if
   while writing `sales-workflow.md` you find the code disagrees with an earlier handoff, document
   what the code actually does and FLAG the discrepancy for Opus review; do not "fix" behaviour.

## Repo orientation — reports/list/dashboard (verified; do not rediscover)

### Deal list page — `frontend/src/features/tickets/TicketListPage.jsx`
- Fetches ALL deals via `api.tickets.list({})` (empty params) under query key
  `queryKeys.ticketList('')`, then filters **client-side**. This is the established pattern —
  keep Phase 5 filters client-side too (no backend query-param plumbing needed).
- Existing filters: `?phase=` (1–5, or `lost`) via the phase summary cards (lines ~188–228),
  and `?q=` free-text search (DataTable `searchable`). URL-backed via `updateParam`.
- `phaseCounts` (lines ~122–133) already treats **lost as a lifecycle bucket**:
  `deal.lifecycle === 'CLOSED_LOST' || deal.lostReason`. Paused deals (ON_HOLD/DORMANT) keep
  their phase and already render a lifecycle badge (lines ~54–62). So lifecycle is *partly*
  consumed already — you are extending it, not introducing it.
- Row status column already renders `dealLifecycleLabel` / `dealLostReasonLabel` badges.

### Dashboard — `frontend/src/features/dashboard/TicketDashboard.jsx` + `ActionQueue.jsx`
- Data from `api.dashboard.summary()` (a SEPARATE endpoint from the ticket list).
- `StatCard`s render status counts (totalOpen/submitted/inReview/priceProposed/approved/
  quotationIssued/closedThisMonth/cancelledThisMonth). `ActionQueue` shows work-queue counts.
- **No lifecycle-bucket / payment-overdue / partial-delivery card exists yet.**

### Backend dashboard aggregation
- `dashboard/DashboardController.java` → `DashboardService.java:47`
  `dashboardRepository.tickets(ticketScope, monthStart, overdueBefore)` →
  `DashboardRepository.java:113 tickets(...)` — a single `SELECT COUNT(*) FILTER (WHERE …)`
  over `sales.ticket t` with `whereTicketScope(scope, params)` applying the SAME visibility
  scope as the list (own-deals for sales). Returns `dashboard.TicketSummaryDto`.
- `dashboard.TicketSummaryDto` (record) and `dashboard.DashboardSummaryDto` (flattens the counts
  onto the top-level summary via `DashboardSummaryDto.of(...)`).
- Verification: `cd backend && ./mvnw -B clean verify` (Testcontainers) ·
  `cd frontend && npm run lint && npm test && npm run build` · frontend-mock port 5200.
  Phase 4 baseline: **backend 505, frontend 122**.

### State model reference (all already implemented — for the doc + filters)
- 14 stages in order (code · phase · S-map): LEAD_APPROACH·1·S1, PRESENTATION·1·S2,
  SPEC_APPROVED·2·S3, QUOTE_DESIGN_SIDE·2·S4+S5, OWNER_SIGNOFF·2·S6, AWAITING_BUYER·3·S7,
  QUOTE_BUYER·3·S8, NEGOTIATION·3·S9, ORDER_RECEIVED·4·S10(auto), DEPOSIT_RECEIVED·4·S11(auto),
  PROCUREMENT·4·S12–S17(auto), DELIVERY_SCHEDULING·5·S18, DELIVERED·5·S19, CLOSED_PAID·5·S20(auto).
  Source: `backend/.../ticket/DealStage.java`, mirror `frontend/.../tickets/stageMeta.js`.
- lifecycle: ACTIVE, ON_HOLD, DORMANT, CLOSED_LOST, CANCELLED, COMPLETED (`DealLifecycle.java`,
  DB CHECK in `V51`).
- fulfillment_status: IR_ISSUED, IR_SENT, PICKED_UP, SHIPPING, CUSTOMS_CLEARANCE, GOODS_RECEIVED,
  FROM_STOCK, PARTIALLY_DELIVERED, FULLY_DELIVERED (`FulfilmentStatus.java`, Java-only, no CHECK).
- payment_status: CUSTOMER_CONFIRMED, DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID, AWAITING_FINAL_PAYMENT,
  FULLY_PAID (Java-only).
- paymentStage (derived): NOT_REQUIRED, DEPOSIT_PENDING, DEPOSIT_RECEIVED, PARTIALLY_PAID,
  BALANCE_PENDING, FULLY_PAID (`PaymentStage.java`).
- policies: tender_requirement (REQUIRED/NOT_REQUIRED/UNKNOWN), deposit_policy
  (REQUIRED/NOT_REQUIRED/WAIVED/CREDIT_CUSTOMER), entry_channel
  (DESIGNER_LED/OWNER_DIRECT/BUYER_DIRECT). lost reasons: 8 values (`DealLostReason.java`).

---

## Deliverable 1 — Reports / filters

### 1a. Deal-list filters (frontend only, client-side)
Add a filter row on `TicketListPage.jsx` that layers ON TOP of the existing phase + search
filters (they compose — an active lifecycle/overdue/delivery filter narrows within the current
phase view). Reuse the URL-param + card/segmented pattern already there. Add three toggle
dimensions, each URL-backed (e.g. `?life=`, `?flag=`):

1. **Lifecycle bucket** beyond the existing lost card. Add visible filters for **ON_HOLD**
   (พักไว้), **DORMANT** (ดีลเงียบ), and optionally **CANCELLED** (ยกเลิก) / **COMPLETED** (จบสมบูรณ์).
   Reconcile with the existing "เสียงาน"/lost card so a deal is counted in exactly one lifecycle
   place. Recommended: keep the 5 phase cards for ACTIVE deals; add a compact lifecycle chip row
   (all / on-hold / dormant / lost / cancelled / completed) that filters the table. Counts shown
   per chip, computed the same client-side way as `phaseCounts`.
2. **Overdue** (เกินกำหนดชำระ) — filter `deal.overdue === true`. Show a count badge.
3. **Partial delivery** (ส่งมอบบางส่วน) — filter `deal.fulfillmentStatus === 'PARTIALLY_DELIVERED'`.
   Show a count badge.

Rules:
- Filters compose with phase + search; clearing returns to the default active-phase view.
- All counts respect owner scope automatically (the list is already scoped server-side; you are
  only grouping what came back).
- Add the Thai labels via the existing `format.js` / label helpers
  (`dealLifecycleLabel` already exists; add `overdue` / partial-delivery labels consistent with
  stageMeta conventions). No new state names — these are views over existing data.
- Empty-state copy when a filter yields zero deals (e.g. "ไม่มีดีลที่เกินกำหนดชำระ").

### 1b. Dashboard bucket cards (backend additive + frontend)
Extend the dashboard summary **additively** with real counts for the new dimensions, then render
cards. Do NOT touch the existing `overdueOver3Days` (ticket-age) count — add new fields:

Backend (`DashboardRepository.tickets` SQL + `dashboard.TicketSummaryDto` +
`DashboardSummaryDto`):
- `onHold` = `COUNT(*) FILTER (WHERE t.lifecycle = 'ON_HOLD')`
- `dormant` = `COUNT(*) FILTER (WHERE t.lifecycle = 'DORMANT')`
- `paymentOverdue` = count of deals with `due_date < CURRENT_DATE` AND positive outstanding.
  **Outstanding is derived** (payable − paid) — mirror exactly how
  `TicketRepository.enrichSummary`/`derivePaymentStage` computes `overdue` so the dashboard count
  and the list `overdue` flag agree. If expressing that in the single aggregate SQL is
  impractical, compute it with the same subquery shape the summary uses (payable from latest
  accepted/issued quotation total, paid from `sales.payment_receipt`). Keep it in ONE query if
  you can; correctness (agreeing with the list) matters more than one extra join.
- `partiallyDelivered` = `COUNT(*) FILTER (WHERE t.fulfillment_status = 'PARTIALLY_DELIVERED')`
- All within the SAME `whereTicketScope(scope, params)` so counts stay scope-correct.
- Update `TicketSummaryDto.empty(...)`, the row-mapper, and `DashboardSummaryDto.of(...)` for the
  new fields. Update any existing dashboard test that asserts the summary shape.

Frontend (`TicketDashboard.jsx`): add StatCards for On-hold, Dormant, Overdue (payment),
Partially-delivered. Wire `mockApi` dashboard summary to return the same new fields (compute from
the mock's seeded deals; keep the `// Mirrors` header) so `contract.test.js` passes and the
mock-browser dashboard shows real numbers.

> If wiring `paymentOverdue` into the single dashboard SQL proves genuinely messy, it is
> acceptable to scope 1b to `onHold`/`dormant`/`partiallyDelivered` (pure column filters) and
> leave payment-overdue as a **list-page filter only** (1a already delivers it) — but note the
> decision in the handoff results section and the workflow doc so Opus can rule on it. Prefer
> shipping all four if the query stays readable.

---

## Deliverable 2 — `docs/sales-workflow.md`

Create the canonical current-state doc (does not exist yet). It documents the system AS BUILT
across Phases 1–4. Sections:

1. **Overview** — one deal = one ticket = one `sales.ticket` row; the four independent tracks
   (lifecycle, sales stage, payment, fulfilment) and how the display stage is derived.
2. **The 14 stages** — table (no · code · Thai label · phase · S-map · manual-vs-auto). Mark
   ORDER_RECEIVED/DEPOSIT_RECEIVED/PROCUREMENT/CLOSED_PAID as auto-derived; the rest manual.
3. **Derivation rules for auto-stages ≥ ORDER_RECEIVED** — exactly what payment/fulfilment/
   lifecycle conditions advance each (read the `autoAdvance*` logic in `TicketService`; describe
   it, don't restate code line-by-line).
4. **State vocabularies** — lifecycle, payment_status, paymentStage (derived), fulfillment_status,
   policies (tender/deposit/entry_channel), lost reasons. One line each on meaning + who sets it.
5. **Transition / actions matrix** — for each action (the Phase-1 actions catalogue: stage
   updates, lifecycle hold/dormant/resume/lost/cancel/close, deposit-notice, quotation
   generate/send/accept/reject, payment record, reserveStock/recordPartialDelivery/
   completeDelivery, policy setters): **required source state(s) · allowed roles · required
   fields · resulting effect**. Source of truth is the service gates + the `/actions` endpoint —
   the doc must match them. A compact table is fine.
6. **The 12 branching cases** — map each to a concrete action sequence (actor → action → state).
   Use THIS canonical list (they correspond 1:1 to what the phases built):

   | # | Case | Key mechanism |
   |---|------|---------------|
   | 1 | Quote issued before spec formally approved (S4 before S3) | quotation records are stage-independent; monotonic auto-advance never forces stage backward |
   | 2 | Owner is also the buyer — skip separate buyer quotation (S8) | entry_channel OWNER_DIRECT + OWNER-recipient quotation satisfies commercial completeness |
   | 3 | Buyer-direct entry — designer not involved | entry_channel BUYER_DIRECT; designer-side quotation skipped with reason |
   | 4 | Multiple quotation recipients / re-quote coexisting | per-recipient (DESIGNER/OWNER/BUYER) version chains + supersession (Phase 2) |
   | 5 | Deposit waived | deposit_policy WAIVED (account/ceo) → issueImportRequest without deposit |
   | 6 | Deposit not required | deposit_policy NOT_REQUIRED → same IR-without-deposit path (S10→S12) |
   | 7 | Product fully in stock | reserveStock all lines → GOODS_RECEIVED without an IR, skip S12–S16 |
   | 8 | Partial stock / partial delivery (40/100) | mixed stock+import lines; multi-drop delivery; PARTIALLY_DELIVERED → FULLY_DELIVERED (incl. the stock-first-then-warehouse ordering, see Phase-4 review) |
   | 9 | Pay in full before delivery | recordPayment(BALANCE) allowed pre-delivery; close() still needs delivery+payment |
   | 10 | Credit customer | deposit_policy CREDIT_CUSTOMER + due_date + overdue tracking; close blocked while outstanding>0 |
   | 11 | Quotation amendment after acceptance | new version requires reason; immutable V49 snapshots + unique version index |
   | 12 | Deal on hold / dormant then resumed | lifecycle ON_HOLD/DORMANT gates mutations; resume restores ACTIVE |

7. **S1–S20 ↔ new-model cross-reference** — table mapping each legacy S-code to the new
   stage/track state(s), including the spec's alias names (SENT≈ISSUED, etc.) as aliases only.
8. **Close & lost/cancel semantics** — exact gate for `close()` (payment complete + delivery
   complete, incl. the hybrid GOODS_RECEIVED legacy allowance flagged in Phase 4) and the
   lifecycle terminal states.

Keep it accurate over exhaustive. Link it from `docs/agent-handoffs/README.md` or the master
context if there's an obvious index. This doc is a deliverable Opus will read against the code.

---

## Deliverable 3 — Close the 22-scenario test matrix

Produce a named coverage set. For EACH of the 22 scenarios below, either (a) point to the
existing test that already covers it, or (b) ADD the test. Put the mapping in the handoff results
section as a table (scenario # → test class#method → EXISTS/ADDED). Backend tests are the
authority for authz/gates (mock authz is non-authoritative). Add frontend tests for the new
filters/cards.

Baseline: `TicketServiceTest` (152), `TicketRepositoryIntegrationTest` (9), `QuotationRendererTest`
(5); frontend `TicketListPage.test.jsx` (2), `TicketDashboard.test.jsx` (2), `contract.test.js`,
`stageMeta.test.js`, `permissions.test.js` (22), etc.

| # | Scenario | Layer | Likely status |
|---|----------|-------|---------------|
| 1 | Plain sales list shows only own deals | backend | EXISTS (`list_*` scoping) — confirm |
| 2 | Non-owner sales cannot open/update another's deal (403) | backend | EXISTS — confirm |
| 3 | Quotation generated before SPEC_APPROVED (Case 1) | backend | confirm/ADD |
| 4 | Owner-is-buyer skips buyer quotation (Case 2) | backend | confirm/ADD |
| 5 | Buyer-direct entry skips designer quotation (Case 3) | backend | confirm/ADD |
| 6 | Per-recipient quotation versions coexist + supersession (Case 4) | backend | EXISTS (Phase 2) — confirm |
| 7 | CEO price gate: cannot issue quotation on unapproved price | backend | EXISTS (`generateQuotation_*`) — confirm |
| 8 | Deposit WAIVED → IR without deposit (Case 5) | backend | EXISTS (Phase 1) — confirm |
| 9 | Deposit NOT_REQUIRED → IR path (Case 6) | backend | EXISTS (Phase 1) — confirm |
| 10 | Full stock → GOODS_RECEIVED without IR, skip procurement (Case 7) | backend | EXISTS (`reserveStock_*`) — confirm |
| 11 | Partial stock + partial delivery 40/100 → PARTIALLY_DELIVERED (Case 8) | backend | EXISTS (`recordPartialDelivery_*`) — confirm |
| 12 | Stock-first then warehouse remainder allowed (Phase-4 review fix) | backend | EXISTS (`recordPartialDelivery_stockFirstThenWarehouseRemainder_isAllowed`) |
| 13 | Over-delivery blocked (409) | backend | EXISTS — confirm |
| 14 | Pay-in-full before delivery allowed; close blocked until delivered (Case 9) | backend | EXISTS (Phase 3) — confirm |
| 15 | Credit customer overdue flagged; close blocked while outstanding (Case 10) | backend | EXISTS (Phase 3) — confirm |
| 16 | Overpayment blocked without override reason | backend | EXISTS (`recordPayment_*`) — confirm |
| 17 | Double-submit receipt idempotency (receipt_ref unique) | backend | confirm/ADD |
| 18 | Quotation amendment after acceptance requires reason (Case 11) | backend | EXISTS (Phase 2) — confirm |
| 19 | On-hold/dormant gate mutations; resume restores (Case 12) | backend | EXISTS (`lifecycle_holdDormantResume_*`) — confirm |
| 20 | Skip-with-reason enforced on multi-step stage jump; single-step note-optional | backend | EXISTS (Phase 1) — confirm |
| 21 | Stale / same-stage update → 409 (no-op / optimistic guard) | backend | confirm/ADD |
| 22 | Unauthorized role per new action (sales_manager operational; sales on money/fulfilment) → 403 | backend | EXISTS (`*_rejectsSalesManagerRole` sweep) — confirm; ADD any gap |
| + | Deal-list lifecycle/overdue/partial filters render + filter correctly | frontend | ADD (`TicketListPage.test.jsx`) |
| + | Dashboard new bucket cards show correct counts | frontend | ADD (`TicketDashboard.test.jsx`) |
| + | mockApi dashboard summary shape matches hrApi (new fields) | frontend | EXISTS (`contract.test.js`) — extend |

For every "confirm" that turns out to be a real GAP, ADD the test. The deliverable is that all 22
rows resolve to a concrete passing test, listed by name in the results section.

## Out of scope (do NOT build now)
- No new lifecycle/payment/fulfilment mutations or states. No schema migration for deliverables
  1 & 3. No real inventory subsystem. No pricing / CEO-gate / ledger changes.
- No backend list query-param plumbing (list filters stay client-side, matching today).
- No merge — PR stays draft/do-not-merge.

## Definition of done (Codex fills before passing back)
- Deal list has working lifecycle / overdue / partial-delivery filters composing with phase +
  search, owner-scope-correct, with Thai labels + empty states.
- Dashboard shows the new bucket cards backed by additive, scope-correct aggregation counts (or
  the documented reduced scope), with mock parity.
- `docs/sales-workflow.md` exists and matches the code (all 8 sections, 12 cases mapped, S-map
  table), with any code-vs-doc discrepancies flagged for Opus.
- The 22-scenario matrix is fully resolved (table of scenario → test name → EXISTS/ADDED), all
  green.
- `cd backend && ./mvnw -B clean verify` green (note count vs 505 baseline).
- `cd frontend && npm run lint && npm test && npm run build` green (note count vs 122 baseline).
- frontend-mock walkthrough: list filters + dashboard cards show sensible numbers on seeded data.

## Files changed
_(Codex fills: path + what changed.)_

## Commands run
_(Codex fills.)_

## Tests / build results
_(Codex fills: backend Tests run, frontend Test Files/Tests, build.)_

## Known risks / questions for Opus review
_(Codex fills: e.g. paymentOverdue-in-aggregate-SQL decision, any doc-vs-code discrepancy found,
 lifecycle-bucket reconciliation choice.)_
