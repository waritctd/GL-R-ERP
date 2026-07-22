# Agent Handoff

## Task
Slice B1 (Track B backend — "kill the weekly report"): reps keep standardized deal-tracking
fields current inside the ERP instead of a per-rep weekly Excel report; the system enforces it
via a manual stage-advance gate; managers/CEO track deals live via a computed staleness flag and
win-probability field. Backend only — B2 (frontend rep deal-tracking UI + manager live pipeline)
is a separate follow-up slice.

## Branch
`feat/sales-commission-auto-approval` (shared with commission backend work A1/A2/calc-refine,
already committed on this branch before this task started — see commits `db7a3fb`/`602eabe`).
A separate concurrent agent is editing ONLY `frontend/` commission files and
`docs/agent-handoffs/102...` on this same branch — this task touched neither.

## Base Commit
`db7a3fbd04302dbbbde9f28c4d8dda82881e54f2` (tip of the branch when this task started)

## Current Commit
Not committed — per instructions, this task does not commit/push. All changes are working-tree
only; see Files Changed below.

## Agent / Model Used
Claude (Sonnet)

## Scope

### In Scope
- V83 migration: `sales.ticket` tracking columns (`win_probability`, `designer_name`,
  `owner_name`, `buyer_name`) + new `sales.deal_activity` table.
- Win% stage-default mapping + rep override, exposed on `TicketSummaryDto`.
- Deal activity log: create/list endpoints.
- Tracking-fields update endpoint (win% override, counterparty names, next follow-up date).
- The stage-advance gate in `TicketService.updateStage` (manual forward moves only).
- Computed staleness flag on the ticket summary.
- Real-Postgres integration tests for all of the above, wrong-way-round + mutation-checked.
- Updating pre-existing tests broken by the new gate.

### Out of Scope (per task instructions — not touched)
- `frontend/` (any file) — Track B2, owned by a different session/slice.
- `docs/agent-handoffs/102...` — owned by the concurrent commission-frontend agent.
- Commission code (`th.co.glr.hr.commission.*`, `frontend/.../commissions/*`).
- Stock/incentive/team/takeover commission — explicitly deferred, unrelated to this slice.

## Files Changed
- `backend/src/main/resources/db/migration/V83__deal_tracking_and_activity.sql` (new) — adds
  `sales.ticket.win_probability/designer_name/owner_name/buyer_name` and the `sales.deal_activity`
  table + `chk_deal_activity_kind` CHECK + `idx_deal_activity_ticket_date` index. V80-82 were
  taken (commission migrations already on this branch); V83 was the next free slot.
- `backend/src/main/java/th/co/glr/hr/ticket/WinProbabilityDefaults.java` (new) — stage → default
  win% map + `effective(override, stage)` helper. **Flagged owner-review assumption** — see
  Assumptions below.
- `backend/src/main/java/th/co/glr/hr/ticket/DealActivityKind.java` (new) — CALL/MEETING/
  SITE_VISIT/EMAIL/MESSAGE/QUOTATION_FOLLOWUP/OTHER, backing `chk_deal_activity_kind`. **Flagged
  owner-review assumption.**
- `backend/src/main/java/th/co/glr/hr/ticket/DealActivityDto.java`, `DealActivityRequest.java`,
  `TrackingUpdateRequest.java` (new) — request/response records.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketSummaryDto.java` — added 5 trailing record
  components (`winProbabilityOverride`, `designerName`, `ownerName`, `buyerName`, `stale`) plus an
  `effectiveWinProbability()` instance method. The legacy 33-arg compact constructor was extended
  to default all 5 new fields (`null,null,null,null,false`), so every pre-existing call site using
  that constructor (8 test files) needed **no changes**. `withCustomerAndProject` updated to
  forward the new fields.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` — `SUMMARY_SELECT` reads the 4
  new `sales.ticket` columns; `mapSummary`/`enrichSummary` construct/forward them (`stale` is
  computed in `enrichSummary`, gated on `lifecycle == ACTIVE`); new methods `updateTracking`,
  `insertDealActivity`, `findActivitiesByTicket`, `hasActivitySinceLastStageChange`, `isStale`.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java`:
  - `updateStage`: added the stage-advance gate (see "The Gate Rule" below), inserted after the
    existing backward/skip-forward note checks, before the `updateSalesStage` write.
  - New methods `addActivity`, `listActivities`, `updateTracking` (all gated on
    `requireDealOwnership` — deal owner/sales_manager/ceo, the existing pattern reused verbatim).
  - Private `withCustomerAndProject` helper updated to forward the 5 new `TicketSummaryDto` fields.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketController.java` — 3 new endpoints:
  `POST /api/tickets/{id}/activities`, `GET /api/tickets/{id}/activities`,
  `PUT /api/tickets/{id}/tracking`.
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java` — see "Pre-existing tests
  updated" below.
- `backend/src/test/java/th/co/glr/hr/ticket/DealTrackingAndActivityIntegrationTest.java` (new) —
  the real-DB evidence for this slice (18 tests).

## The Gate Rule (as implemented)

In `TicketService.updateStage`, after the existing `backward`/`skipForward` note checks:

```java
boolean forward = DealStage.indexOf(targetStage) > DealStage.indexOf(s.salesStage());
if (forward) {
    boolean hasFollowUp = s.nextFollowUpAt() != null;
    boolean hasRecentActivity = tickets.hasActivitySinceLastStageChange(ticketId);
    if (!hasFollowUp || !hasRecentActivity) {
        throw new ApiException(HttpStatus.BAD_REQUEST,
            "เลื่อนสถานะไม่ได้: ต้องระบุวันติดตามครั้งถัดไป และบันทึกกิจกรรมอย่างน้อย 1 รายการหลังเปลี่ยนสถานะล่าสุด");
    }
}
```

- **`forward`** is defined as `targetIndex > currentIndex` — strictly greater, matching "real
  forward progress" from the task. This single condition correctly excludes both plain backward
  moves AND the routine `QUOTE_DESIGN_SIDE → SPEC_APPROVED` move (which is index-wise backward,
  so `forward` is false for it) without needing a separate `!backward` check — I considered `!
  backward` but rejected it: `backward` is defined to exclude the routine move (so `!backward`
  would be true for it), which is the wrong answer here — the routine move is not forward
  progress either, so it must stay ungated on its own account, not because it merely isn't
  "backward".
- **`autoAdvanceStage`** (the system-driven path — `confirmCustomer`, `issueImportRequest`,
  `markGoodsReceived`, `generateQuotation`'s auto-advance, `maybeAdvanceClosedPaid`, etc.) is a
  completely separate private method that never calls through `updateStage`, so it is never gated
  by this block. Verified by `autoAdvanceStage_confirmCustomer_isNotGatedByTrackingFields`.
- `hasActivitySinceLastStageChange` compares by `created_at` (the moment a `deal_activity` row was
  logged), not the possibly-backdated `activity_date`, against the most recent `STAGE_CHANGED`
  event's `created_at` — or, if the deal has never had a `STAGE_CHANGED` event yet, the ticket's
  own `created_at`. This means a brand-new deal's very first manual advance is satisfied by any
  activity logged since ticket creation, not exempted.

## Win% Mapping (Owner-Review Assumption)

`WinProbabilityDefaults`:

| Stage | % | Stage | % |
|---|---|---|---|
| LEAD_APPROACH | 10 | ORDER_RECEIVED | 90 |
| PRESENTATION | 20 | DEPOSIT_RECEIVED | 100 |
| SPEC_APPROVED | 30 | PROCUREMENT | 100 |
| QUOTE_DESIGN_SIDE | 40 | DELIVERY_SCHEDULING | 100 |
| OWNER_SIGNOFF | 50 | DELIVERED | 100 |
| AWAITING_BUYER | 50 | CLOSED_PAID | 100 |
| QUOTE_BUYER | 60 | | |
| NEGOTIATION | 70 | | |

**This is NOT sourced from real historical win/loss data or owner sign-off** — it is a first-pass,
monotonically non-decreasing curve chosen by this agent to give every stage of the pipeline a
sensible number. Flagged in the class Javadoc, the V83 migration comment, and here. The owner
should review against real deal outcomes before this is treated as verified business policy — same
caveat as `DealActivityKind`'s 7-value taxonomy (CALL/MEETING/SITE_VISIT/EMAIL/MESSAGE/
QUOTATION_FOLLOWUP/OTHER), which is also a first-pass list, not a confirmed taxonomy of how the
sales team actually follows up.

`effectiveWinProbability()` = the rep's override when set, else the stage default. Never a
blocker on its own (per the owner's decision) — confirmed by
`winProbability_defaultsFromStage_whenNoOverrideSet` /
`winProbability_repOverride_winsOverTheStageDefault`.

## Staleness

Computed in `TicketRepository.enrichSummary` (same pattern as the pre-existing `overdue` field —
never a stored column): `stale = (lifecycle == ACTIVE) && isStale(ticketId)`, where `isStale` is
true when no `deal_activity` row has `created_at >= now() - interval '7 days'` — including a deal
that has never had one at all ("once it's active", per the task). A DRAFT deal that hasn't started,
or a CLOSED_LOST/CANCELLED/COMPLETED deal, is never flagged regardless of activity history.
Exposed to every viewer role unfiltered (same treatment as `overdue`) — it is not sensitive data,
and a sales rep seeing their own deal's staleness is arguably useful, not a leak.

## Endpoints Added
- `POST /api/tickets/{id}/activities` — body `{activityDate, kind, note}`. Owner/sales_manager/
  ceo (`requireDealOwnership`). Deliberately **not** gated on `requireActive` — a rep can still
  record why a deal went quiet on a non-ACTIVE deal; the one place activity state is enforced
  (`updateStage`'s gate) only ever runs on an ACTIVE deal anyway.
- `GET /api/tickets/{id}/activities` — same role gate, returns `{items: [...]}`.
- `PUT /api/tickets/{id}/tracking` — body `{winProbability, designerName, ownerName, buyerName,
  nextFollowUpAt}`, all nullable. **Full-replace semantics, not a merge** — a client omitting a
  field clears it (for `winProbability`, null means "fall back to the stage default", which is the
  intended way to clear an override). Owner/sales_manager/ceo, gated on `requireActive`. Logs a
  `POLICY_CHANGED` ticket event (reused the existing kind rather than adding a new one — no
  `chk_event_kind` migration needed for this slice).

## Commands Run
```bash
cd backend && ./mvnw -q -B compile
cd backend && ./mvnw -q -B test-compile
cd backend && ./mvnw -q -B test -Dtest=DealTrackingAndActivityIntegrationTest
# mutation-check: disabled the gate (if (false && forward)), reran, confirmed exactly the
# 4 gate-specific tests went red, reverted, reran, confirmed all 18 green again.
cd backend && ./mvnw -q -B test -Dtest="th.co.glr.hr.ticket.**"
cd backend && ./mvnw -B clean verify   # full suite — see Test/Build Results
```

## Test / Build Results
- **Backend compile**: pass.
- **Backend test-compile**: pass (after fixing 2 pre-existing full-canonical-constructor call
  sites in `TicketServiceTest.java` that the `TicketSummaryDto` field addition broke — see below).
- **`DealTrackingAndActivityIntegrationTest`**: 18/18 pass, ran against real Postgres via
  Testcontainers (Docker was available; migrated to V83 cleanly).
- **Mutation check**: confirmed. Disabling the gate (`if (false && forward)`) turned exactly these
  4 tests red and nothing else: `manualForwardAdvance_withNoNextFollowUpAtAndNoActivity_...`,
  `manualForwardAdvance_withActivityButNoNextFollowUpAt_...`,
  `manualForwardAdvance_withNextFollowUpAtButNoActivitySinceLastStageChange_...`,
  `activityLoggedBeforeTheLastStageChange_doesNotSatisfyTheNextAdvance`. Reverted; all 18 green
  again (verified with a second full run, exit code 0).
- **Full `th.co.glr.hr.ticket.**` package**: 233 tests, 0 failures, 0 errors.
- **Full `./mvnw -B clean verify`**: ran to completion in the background. **BUILD SUCCESS.**
  `Tests run: 1095, Failures: 0, Errors: 0, Skipped: 0`. Jacoco coverage checks met. Total time
  5:57 min. This is the authoritative number for this handoff.
- **Integration tests ran** (not skipped) — Docker was available locally; Testcontainers
  provisioned Postgres 16 and migrated through V83 successfully every run, including the full
  `clean verify` run above.

## Pre-existing tests updated (and why)

All 5 were `TicketServiceTest` (Mockito-only, per that file's own header) forward-manual-
`updateStage` tests that started 400ing once the gate landed, because their stubbed
`TicketSummaryDto` had `nextFollowUpAt = null` and the mocked `TicketRepository` returns `false`
by default (Mockito's primitive default) for the new `hasActivitySinceLastStageChange` call:

1. `updateStage_ownerCanAdvanceSalesStage`
2. `updateStage_salesManagerAndCeoCanAdvanceSalesStage`
3. `updateStage_rejectsNonOwnerSalesAndWrongRolesPerTarget`
4. `updateStage_multiStepForwardRequiresNote`
5. `reopenedDealIsFullyOperableAndKeepsItsLostReason`

**Fix (not a weakening of the gate)** — two shared test-fixture changes, both additive:
- Added a class-level default stub `when(ticketRepo.hasActivitySinceLastStageChange(anyLong()))
  .thenReturn(true)` next to the existing `cancelOpenForTicket` default-stub pattern already in
  the file's instance initializer block.
- `stubDealWithQuotations` (the shared helper backing `stubDeal`, used by ~20 tests) now builds
  its `TicketSummaryDto` via the full canonical constructor with `nextFollowUpAt = LocalDate.now()`
  instead of the legacy compact constructor (which hardcodes it null). No test in the file asserted
  `nextFollowUpAt` was null, so this is safe; verified by rerunning the full 177-test file (all
  green, including all backward-move/lost/reopen/lifecycle tests untouched by this gate).

This is deliberately a **test-fixture fix, not a gate weakening**: `TicketServiceTest` is
Mockito-only (its own header says so) and was never the place this gate's real enforcement is
proven — that's `DealTrackingAndActivityIntegrationTest`, against real SQL. Also fixed 2 unrelated
compile breaks in the same file from the `TicketSummaryDto` field-count change (`stubCloseDeal`
and `ticketLike`, both using the full canonical constructor directly) — appended the 5 new trailing
args (`null,null,null,null,false` / forwarding `s.winProbabilityOverride()` etc.).

## Authz Evidence
No new role/permission gate was introduced — `addActivity`/`listActivities`/`updateTracking` all
reuse the **existing** `requireDealOwnership` check verbatim (deal owner sales rep, sales_manager,
or ceo), the same gate `markLost`/`reopenDeal`/`placeOnHold`/etc. already use. The stage-advance
gate itself is a **business-rule** gate (data completeness), not an authorization gate — it runs
strictly after `requireStageWriteAccess` (the existing role check) has already passed, and applies
identically regardless of which permitted role is calling. Per CLAUDE.md, "permission changes must
ship evidence" applies to authorization changes; this slice makes none, so no new real-DB
authorization test was required — but the new endpoints and the gate itself ARE covered by
`DealTrackingAndActivityIntegrationTest` against the real service/repository/Postgres regardless
(including `addActivity_deniedForANonOwningRep`, a wrong-way-round check that the existing
ownership gate still holds on the new endpoint).

## Decisions Made
- `stale` and win%/tracking fields are unfiltered by role (visible to any viewer with read access
  to the ticket) — not gated to managers/ceo only, since they are not sensitive and the task's
  "manager-visible" language describes the primary consumer, not an access restriction.
- The tracking-update endpoint reuses `POLICY_CHANGED` for its audit event rather than adding a
  new `TICKET_EVENT` kind, avoiding a `chk_event_kind` migration for this slice.
- `addActivity`/`listActivities` are NOT gated by `requireActive` (see Endpoints Added above) —
  a deliberate choice to keep historical/explanatory logging possible on a non-ACTIVE deal.
- The staleness/gate computations both key off `deal_activity.created_at` (when logged), not the
  possibly-backdated `activity_date` (when the rep says it happened) — chosen for a race-free,
  precise timestamptz comparison rather than a date-only one that could be ambiguous same-day.

## Assumptions (owner review needed)
1. **Win% stage-default mapping** (`WinProbabilityDefaults`) — first-pass numbers, not sourced
   from real deal outcomes. See table above.
2. **`DealActivityKind`'s 7 values** (CALL/MEETING/SITE_VISIT/EMAIL/MESSAGE/QUOTATION_FOLLOWUP/
   OTHER) — a first-pass taxonomy, not confirmed against how the sales team actually works.
3. The 7-day staleness window and the "since last stage change" gate condition are exactly as
   specified in the task (owner-authored), not agent assumptions.

## Known Risks
- `TicketSummaryDto` now has 53 record components — already a long record before this slice;
  adding more fields to it in the future should keep using the same append-at-the-end-with-
  defaulted-legacy-constructor pattern this change used, or the legacy 33-arg constructor (still
  used by 6+ test files) will need touching again.
- The gate compares `created_at` timestamps, which relies on server clock monotonicity within a
  single transaction sequence — not a concern in practice (Postgres `now()` is transaction-start
  time, strictly increasing across separate transactions), but worth knowing if a future change
  batches multiple stage-changes/activities into one transaction.
- B2 (frontend) does not exist yet — reps have no UI to set `nextFollowUpAt`/log activity, so the
  gate as shipped will block real manual forward stage advances in production until B2 lands. Not
  a regression (nothing today lets a rep advance a stage without this UI either, since UI comes
  after backend), but flagging so B2 is not deprioritized.

## Things Not Finished
- ~~B2 (frontend): rep-facing deal-tracking form..., activity log UI, and the manager/CEO live
  pipeline view...~~ **DONE — see the "B2 (frontend)" section appended below.**
- No dashboard/report query aggregating staleness across the whole pipeline yet (e.g. "how many
  ACTIVE deals are stale right now") — only the per-ticket computed field exists. B2's manager
  pipeline panel computes this **client-side** from the already-fetched deal list (see
  `groupDealsForPipeline` in `TicketListPage.jsx`) rather than adding a server aggregation — fine
  at today's deal-count scale, but if the list ever needs to page/paginate, this client-side
  grouping stops being correct and a real repository aggregation will be needed.

---

## B2 (frontend) — 2026-07-22

Implements the frontend half of this slice: the rep-facing deal-tracking panel, the activity log,
the stage-advance gate's UI surfacing, and the manager/CEO live pipeline view. Built directly on
top of B1 above (same branch, not yet committed) — read everything above this line first.

### Files Changed
- `frontend/src/api/routes.js` — added `tickets.activities(id)` (`POST`/`GET
  /api/tickets/{id}/activities`) and `tickets.tracking(id)` (`PUT /api/tickets/{id}/tracking`).
- `frontend/src/api/hrApi.js` — added `tickets.addActivity`, `tickets.listActivities`,
  `tickets.updateTracking`, wired to the new routes.
- `frontend/src/api/queryKeys.js` — added `ticketActivities(id)`.
- `frontend/src/features/tickets/dealTrackingMeta.js` (new) — the shared module both the UI and
  `mockApi.js` import, so the mock's numbers/gate can never drift from the Java source of truth
  (same convention as `stageMeta.js` / `pricingRequestMeta.js`): `WIN_PROBABILITY_DEFAULTS` (mirrors
  `WinProbabilityDefaults.java` exactly) + `effectiveWinProbability()`, `ACTIVITY_KINDS` (mirrors
  `DealActivityKind.java`), `lastStageChangeAt`/`hasActivitySince`/`isReadyToAdvance` (mirror
  `TicketService.updateStage`'s forward-gate condition and `TicketRepository
  .hasActivitySinceLastStageChange`'s SQL), `computeStale` (mirrors `TicketRepository#isStale`),
  and both the reactive `STAGE_ADVANCE_GATE_MESSAGE` (exact backend 400 text) and the pre-emptive
  `STAGE_ADVANCE_GATE_HINT` (same two conditions, forward-phrased). **Important gap called out
  explicitly**: `effectiveWinProbability()` is a Java record *method*, not a record component, so
  neither the real backend nor the mock ever serializes it onto the JSON ticket summary — every
  consumer (UI and mock alike) derives it client-side from `winProbabilityOverride` + `salesStage`
  via this function. Documented in the module's own doc comment so a future reader doesn't go
  looking for a `summary.effectiveWinProbability` field that will never exist.
- `frontend/src/api/mockApi.js`:
  - New store `mockDealActivities` / `mockDealActivitySeq` (own array, not on the ticket — mirrors
    `sales.deal_activity` being its own table, same pattern as `mockAttachments`).
  - `buildTicketDetail`'s summary and `tickets.list`'s row mapping both gained
    `winProbabilityOverride`, `designerName`, `ownerName`, `buyerName`, `stale` (computed via
    `dealComputeStale`, same formula as the backend).
  - `tickets.updateStage`'s forward-move branch gained the stage-advance gate, mirroring
    `TicketService.updateStage` exactly (same `forward = targetIndex > currentIndex` definition,
    not `!backward` — see B1's "The Gate Rule" above for why that distinction matters for the
    routine `QUOTE_DESIGN_SIDE → SPEC_APPROVED` move).
  - Three new methods: `tickets.addActivity`, `tickets.listActivities`, `tickets.updateTracking`,
    all gated on `dealCanMarkLost(user, ticket)` — reused verbatim, since it already mirrors
    `TicketService.requireDealOwnership` (owner/sales_manager/ceo), the exact gate the real
    `addActivity`/`listActivities`/`updateTracking` endpoints use.
  - **Mock-gate parity note (per CLAUDE.md)**: this is authz/business-rule *approximation*, not
    authoritative — every new gate above is commented as mirroring a specific backend method, but
    the real enforcement is B1's `DealTrackingAndActivityIntegrationTest` against real Postgres.
    Verified live against the mock only (see "Live mock verification" below); the permission aspect
    is **unverified against the Java service** by this B2 slice — it was already covered by B1's
    integration test for the ownership gate, and B2 introduces no new role/permission logic, only UI
    plus a mock mirror of an existing gate.
- `frontend/src/features/tickets/DealTrackingPanel.jsx` (new) — the "การติดตามดีล" section: read/edit
  win% (shows the effective value, override editable, clears via empty input), next-follow-up date,
  designer/owner/buyer names; activity log (list + add form: date/kind/note); a
  "พร้อมเลื่อนสถานะ / ยังไม่พร้อม" `StatusBadge` plus an inline warning box showing
  `STAGE_ADVANCE_GATE_HINT` when not ready. Tailwind-only, mobile-first (flex-col cards, `sm:`
  breakpoint for the 2-col edit grid, native `<input>`/`<select>`/`<textarea>` at the browser default
  16px so iOS doesn't zoom on focus).
- `frontend/src/features/tickets/TicketDetailPage.jsx` — imports `DealTrackingPanel`; new
  `canViewDealTracking` gate (`sales`/`sales_manager`/`ceo`), `activitiesQuery`
  (`queryKeys.ticketActivities`), `updateTrackingMutation` (fast-path via `applyTicketUpdate`, same
  as every other action mutation on this page) and `addActivityMutation` (invalidates
  `ticketActivities`/`ticketDetail`/`['tickets','list']` instead, since `addActivity` returns a bare
  `DealActivityDto`, not a full ticket). Renders `<DealTrackingPanel>` right after `<DealStagePanel>`
  when `sections.dealTracking`, else the same one-line `<SectionPeek>` every other role-scoped
  section on this page already uses for import/account.
- `frontend/src/features/tickets/salesViewScope.js` — added the `dealTracking` section id
  (true for sales/sales_manager/ceo via the existing `allTrue()` pass-through, explicitly `false`
  for import and account).
- `frontend/src/features/tickets/TicketListPage.jsx` — the manager live pipeline, gated on
  `isManagerView` (`sales_manager`/`ceo` only, `MANAGER_PIPELINE_ROLES`):
  - `groupDealsForPipeline(deals)` buckets the **full unfiltered** deal list (independent of the
    phase/lifecycle/flag chips, same "counts-from-allDeals" convention the page's existing
    `flagCounts`/`lifecycleCounts` already use) into ยอดที่สั่งซื้อแล้ว (ACTIVE, stage index ≥
    ORDER_RECEIVED), ยอดคาดหวัง (ACTIVE, pre-order — carries the win-weighted forecast, Σ
    `amountPayable × effectiveWinProbability/100`), and ขายไม่ได้ (`CLOSED_LOST`). Paused
    (`ON_HOLD`/`DORMANT`) and terminal-non-lost (`CANCELLED`/`COMPLETED`) deals are deliberately
    excluded from all three buckets — documented in the function's own comment as a judgment call,
    not an oversight.
  - `<TeamPipelineSummary>` renders the three cards (count + total value; the expected card also
    shows the win-weighted forecast) plus a header badge counting stale deals across the won+expected
    buckets, right below the page header.
  - `MANAGER_DEAL_COLUMNS` (`DEAL_COLUMNS` + one `tracking` column rendering `<TrackingBadges>`
    — win% + a "เงียบ" stale chip) swaps in for `isManagerView`; `DealCard`'s mobile variant gained
    an optional `showTracking` prop rendering the same badges for the reflowed card view.
- `frontend/src/features/tickets/TicketDetailPage.test.jsx` — added `listActivities`/`addActivity`/
  `updateTracking` to the mocked `api.tickets` surface (with `beforeEach` defaults), plus a new
  `describe('deal tracking panel', …)` block: renders + shows the stage win% default + the
  pre-emptive gate hint when `nextFollowUpAt` is unset; flips to "พร้อมเลื่อนสถานะ" once
  `nextFollowUpAt` is set and an activity exists since the last stage change; submits a new activity
  via `api.tickets.addActivity`; confirms `account` gets the `SectionPeek`, not the panel, and never
  calls `listActivities`.

### API Methods Added
`hrApi.js` / `mockApi.js` (both, contract-parity enforced by `contract.test.js`):
- `tickets.addActivity(id, payload)` → `POST /api/tickets/{id}/activities`
- `tickets.listActivities(id)` → `GET /api/tickets/{id}/activities`
- `tickets.updateTracking(id, payload)` → `PUT /api/tickets/{id}/tracking`

### Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
```

### Test / Build Results
- **Lint**: pass. 0 errors, 1 pre-existing warning (`PayrollPage.jsx` exhaustive-deps), unrelated to
  this slice.
- **Test**: pass. **48 files / 422 tests, 0 failures** (up from 418 before this slice — 4 new tests
  in `TicketDetailPage.test.jsx`'s new `describe('deal tracking panel', …)` block). `contract.test.js`
  passes (hrApi ↔ mockApi method parity holds for the 3 new methods). `salesViewScope.test.js`
  passes unchanged (its `ceo`/`sales_manager` pass-through and `hr`-defaults-to-nothing tests loop
  over every section id, so the new `dealTracking` id was covered automatically).
- **Build**: pass. `vite build` succeeded in 169ms (esbuild-minify step only counted — full build
  completed without error); no new warnings.
- **Not run**: backend suite (`./mvnw -B clean verify`) — this slice touched no backend files; B1's
  handoff above already has the authoritative backend numbers (1095 tests, BUILD SUCCESS).

### Live Mock Verification
Ran the actual `frontend-mock` app (`VITE_USE_MOCKS=true`) in a real browser and drove it end to end
— **not just automated tests**. Note: the branch's usual `frontend-mock` launch-config port (5200)
was already occupied by a *different* worktree's dev server (`.claude/worktrees/pricing-chain-uat`,
a concurrent session) serving unrelated code — this was caught by inspecting the process's `cwd`
before trusting it (see the repo's `concurrent-session-worktree-collision` lesson), and a fresh
`vite --port 5301` was started from *this* worktree instead, torn down after verification.

Verified, as `sales_manager` (คุณมณี):
1. **Manager pipeline** (`/tickets`): the "ภาพรวมทีม (แทนที่รายงานประจำสัปดาห์)" panel rendered with
   real numbers from the seed data — ยอดที่สั่งซื้อแล้ว 4 ดีล / ฿1,126,500, ยอดคาดหวัง 7 ดีล /
   ฿148,750 with a win-weighted forecast of ฿69,900, ขายไม่ได้ 0 ดีล / ฿0, plus a header badge "11
   ดีลเงียบ (ไม่มีการติดตาม 7 วัน)". The table's new "การติดตาม" column showed each deal's win% (e.g.
   20%/40%/100%/90% matching each deal's stage) and a "เงียบ" chip on stale rows.
2. **Deal tracking panel** (`/tickets/7`, PR-2026-0007): rendered "การติดตามดีล" with a "ยังไม่พร้อม"
   badge and the exact pre-emptive hint text, win% "40% (ค่าเริ่มต้นตามขั้นดีล)", and the activity
   log/add-activity form.
3. **updateTracking**: set next-follow-up to 2026-08-01 via the edit form → saved, reflected
   immediately in both the tracking panel AND the pre-existing payment section's "ติดตามครั้งถัดไป"
   line (confirming the two features correctly share the one `nextFollowUpAt` backend field) →
   event log recorded "เปลี่ยนนโยบายดีล ... อัปเดตข้อมูลติดตามดีล" by the acting user.
4. **addActivity**: logged a CALL activity with a note → appeared in the activity log immediately →
   readiness flipped from "ยังไม่พร้อม" to "พร้อมเลื่อนสถานะ" (both gate conditions now met).
5. **Stage advance succeeds when ready**: clicked "เลื่อนไป: เจ้าของอนุมัติสเปค" → succeeded, stage
   advanced to 5, win% default updated to 50% (`OWNER_SIGNOFF`) — **and readiness correctly flipped
   back to "ยังไม่พร้อม"** immediately after, because the previously-logged activity predates the
   *new* last stage change and no longer satisfies the gate. This confirms the "since last stage
   change" semantics (not just "any activity ever") work end to end, matching B1's
   `hasActivitySinceLastStageChange`.
6. **Stage advance rejected when not ready**: attempted another advance immediately after (5) →
   the mock threw, and the UI surfaced **the exact backend 400 message** as a toast —
   `เลื่อนสถานะไม่ได้: ต้องระบุวันติดตามครั้งถัดไป และบันทึกกิจกรรมอย่างน้อย 1 รายการหลังเปลี่ยนสถานะล่าสุด`
   — not a generic "เกิดข้อผิดพลาด" fallback, confirming `showToast('error', error.message || …)`'s
   existing pattern already satisfies "surface that message" for this new gate with no special-casing
   needed.
7. **Role scoping**: automated test confirms `account` sees the `SectionPeek`, not the panel, and
   never calls `listActivities` (see `TicketDetailPage.test.jsx`). Not re-driven manually in the
   browser for `import`/`account` beyond that — the automated coverage plus the shared
   `salesViewScope.js` gate (already exercised for every other section on this page) was judged
   sufficient given time constraints.

Not checked live: mobile viewport reflow (the Tailwind classes used — `flex-col`/`grid-cols-1
sm:grid-cols-2`/`sm:grid-cols-3` — match the same responsive conventions already verified elsewhere
on this page and `DealStagePanel.jsx`, but no mobile-width screenshot was taken this session).

### Authz Evidence
No new authorization logic was introduced by B2 — every gate the new UI/mock code checks
(`dealCanMarkLost` / `canViewDealTracking` / `salesViewScope.dealTracking`) is a **UI-side mirror**
of the ownership gate B1 already added and already proved with a real-DB integration test
(`DealTrackingAndActivityIntegrationTest`, specifically `addActivity_deniedForANonOwningRep`). Per
CLAUDE.md, this frontend slice does not need its own real-DB test — it ships no new role/scope
decision, only a client-side reflection of one that already has backend evidence. **The mock's gate
behaviour was verified interactively above, which is explicitly NOT authoritative** — if the real
`requireDealOwnership` check on these three endpoints ever needs re-verification, cite B1's
integration test, not this session's mock click-through.

### Known Risks
- `TicketListPage`'s `groupDealsForPipeline` computes the manager pipeline **client-side** off
  `allDeals` (the full unpaginated list). Fine at today's scale; would need to move server-side if
  the ticket list ever gains pagination.
- The win% stage-default table and the activity-kind taxonomy are still the **same owner-review-
  flagged first-pass values** from B1 (`WinProbabilityDefaults.java` / `DealActivityKind.java`,
  mirrored verbatim into `dealTrackingMeta.js`) — B2 surfaces them in the UI but does not resolve
  that open question. If the owner revises either table, both the backend class and
  `dealTrackingMeta.js` need updating together (there is no single source of truth across the
  language boundary — mirrored, not shared).
- `effectiveWinProbability` is computed independently client- and server-side (see the "Files
  Changed" note above) — if `WinProbabilityDefaults.java`'s table ever changes without updating
  `dealTrackingMeta.js`'s copy, the UI's displayed win% would silently diverge from what the server
  would compute. No automated drift check exists between the two files today.

### Things Not Finished (B2)
- No dedicated test for `TicketListPage`'s new manager-pipeline grouping/columns
  (`groupDealsForPipeline`, `MANAGER_DEAL_COLUMNS`) — covered only by the live mock walkthrough
  above, not by `TicketListPage.test.jsx` (which only exercises the `sales` role today). A follow-up
  could add a `sales_manager`-role test asserting the three group totals/counts against a fixed
  fixture.
- No screenshot-verified mobile-width pass on the new panel/pipeline cards.

## Recommended Next Agent
Opus review pass on this B2 slice (per the project's standing Sonnet-implements/Opus-reviews loop),
then merge B1+B2 together once reviewed. After merge, the win%/activity-kind owner-review items
(flagged in B1 and restated above) are the next open thread — not blocking, but should not be
silently treated as final business policy.

## Recommended Next Agent
Claude/Codex implementation agent for B2 (frontend), after an Opus review pass on this backend
slice (per the project's standing Sonnet-implements/Opus-reviews loop).

## Exact Next Prompt
```
Implement Track B Slice B2 (frontend) on a NEW branch off main (after this backend slice,
feat/sales-commission-auto-approval's B1 work, is reviewed and merged — do not build B2 on top of
an unmerged branch). Read docs/agent-handoffs/103_feat-weekly-report-elimination.md in full first —
it has the exact API contract (POST/GET /api/tickets/{id}/activities, PUT /api/tickets/{id}/tracking,
the new TicketSummaryDto fields winProbabilityOverride/effectiveWinProbability()/designerName/
ownerName/buyerName/stale) and the two owner-review-flagged assumptions (win% stage defaults,
activity kind taxonomy) you should surface to the owner rather than treat as final.

Build:
1. A rep-facing "deal tracking" panel on the ticket detail page: win% (showing the effective value,
   editable as an override), designer/owner/buyer name fields, next-follow-up date, and an activity
   log (list + add form) — wired to the new endpoints via hrApi.js AND mirrored in mockApi.js
   (contract.test.js will fail otherwise — CLAUDE.md's mock-contract rule).
2. Surface the stage-advance gate's 400 error message in the UI when a rep tries to advance a
   stage without the required fields, ideally pre-empting it (disable/hint the advance button when
   nextFollowUpAt is unset or no activity has been logged since the last stage change, rather than
   only showing the error after a failed request).
3. A manager/CEO-facing live pipeline view (or an addition to the existing sales pipeline page)
   surfacing the `stale` flag per deal — this is the actual "kill the weekly report" payoff: make
   it obviously visible which of a rep's deals have gone quiet.
4. Follow the project's mockApi-authz-is-not-authoritative rule: any UI permission gate you add
   must be verified against the Java service (already covered by this backend slice's
   DealTrackingAndActivityIntegrationTest for addActivity/listActivities/updateTracking's
   ownership gate — reuse that as your evidence, cite it, don't re-derive from the mock).

Run npm run lint && npm test && npm run build in frontend/ and report the results. Update this
handoff's Things Not Finished section once B2 lands, or create docs/agent-handoffs/104_....md if
you're on a new branch.
```
