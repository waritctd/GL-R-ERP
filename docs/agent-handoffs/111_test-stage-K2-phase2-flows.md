# Agent Handoff

## Task
Implement PHASE 2 of Stage K2 — Playwright browser e2e against the MOCK
frontend (`VITE_USE_MOCKS=true`): the drivable FLOW specs + their testids,
on top of the foundation merged in
`docs/agent-handoffs/110_test-stage-K2-phase1-foundation.md` (branch
`test/stage-K2-phase1-foundation`, merged to main via PR #295, `7e2ffd1`).
Full spec: `/private/tmp/claude-501/-Users-ploy-warit-Desktop-GL-R-ERP/b702f473-d9ac-46f0-b2e2-acb2c2128a03/scratchpad/stage-K2-e2e-spec.md`
(a session-scratchpad path — likely gone by the time a future agent reads
this; the spec's content is fully reproduced/superseded by this handoff).

## Branch
`test/stage-K2-phase2-flows`

## Base Commit
`eff039e` (origin/main, PR #296 `fix/catalog-route-guard` merged — this
already includes Phase 1)

## Current Commit
`38cb4e6` (7 commits on top of base — see `git log` for the incremental
sequence: deal-panel testids → deal-creation.spec → PCR-detail testids →
mock bug fixes + pcr-chain.spec → commission testid + commission.spec →
hr.spec → deposit-fulfilment-close.spec)

## Agent / Model Used
Claude Sonnet (implementation), per the repo's Sonnet-implements/Opus-reviews loop.

## Scope

### In Scope
- testids (attribute-only) on `TicketCreateModal.jsx` + `Modal.jsx` (new
  optional `testId` prop), `Deal{Stage,Quotation,Deposit,Fulfilment}Panel.jsx`,
  `TicketDetailPage.jsx` (primaryAction buttons only — see Decisions Made),
  `PricingRequestDetailPage.jsx`, `PricingRequestQueuePage.jsx` (pickup —
  see Decisions Made), `CommissionPage.jsx`.
- 5 flow specs: `deal-creation.spec.js`, `pcr-chain.spec.js`,
  `deposit-fulfilment-close.spec.js`, `commission.spec.js`, `hr.spec.js`.
- Two real, 100%-reproducible bugs found and fixed in `src/api/mockApi.js`
  while building `pcr-chain.spec.js` — see "Real bugs found + fixed" below.

### Out of Scope / Not Touched
- Phase 1's `auth.spec.js`, `rbac.spec.js`, `playwright.config.js`,
  `e2e/helpers/auth.js` — reused as-is, not modified.
- `backend/`, `hrApi.js` — not touched.
- `e2e-ci.yml` — not modified (Phase 1's version already runs `npm run
  test:e2e`, so all 5 new specs auto-join it; nothing to change).

## Files Changed

### testids (attribute-only)
- `frontend/src/components/common/Modal.jsx` — new optional `testId` prop,
  passed through as `data-testid` on the dialog `<section>`. Generic,
  reusable by any `<Modal>` caller.
- `frontend/src/features/tickets/TicketCreateModal.jsx` — `testId=
  "ticket-create-modal"` on its `<Modal>`; `data-testid="ticket-create-submit"`
  on the submit button. Reuses all existing `id`s (`#customer-select`,
  `#project-field`, `#item-N-brand/model/color/texture/size/qty/...`) —
  none added.
- `frontend/src/features/tickets/DealStagePanel.jsx` — `deal-stage-panel`
  root + `deal-stage-advance` (the one action our specs actually drive;
  hold/dormant/lost/edit/reopen were left untouched — see Decisions Made).
- `frontend/src/features/tickets/DealQuotationPanel.jsx` —
  `deal-quotation-panel` root + `deal-quotation-create`/`-issue`/`-accept`/
  `-reject`/`-revision`/`-confirm-order`.
- `frontend/src/features/tickets/DealDepositPanel.jsx` —
  `deal-deposit-panel` root + `deal-deposit-create-notice`/
  `-issue-notice`/`-confirm`.
- `frontend/src/features/tickets/DealFulfilmentPanel.jsx` —
  `deal-fulfilment-panel` root + `deal-fulfilment-issue-ir`/
  `-mark-ir-sent`/`-mark-shipping`/`-mark-goods-received`/
  `-reserve-stock`/`-record-delivery`/`-record-delivery-submit`
  (the delivery modal's own submit button)/`-complete`.
- `frontend/src/features/tickets/TicketDetailPage.jsx` —
  `ticket-detail-confirm-customer`/`-confirm-final`/`-confirm-close`/
  `-verify-close` on the four mutually-exclusive `primaryAction` buttons.
  **No `submit`/`confirm-deposit` testids added here** — see Decisions Made.
- `frontend/src/features/pricingRequests/PricingRequestDetailPage.jsx` —
  `pcr-generate-drafts`, `pcr-quote-ready`, `pcr-quote-save-response`,
  `pcr-costing-create`/`-recalculate`/`-submit`, `pcr-ceo-start-review`/
  `-save-decision-items`/`-approve`.
- `frontend/src/features/pricingRequests/PricingRequestQueuePage.jsx` —
  `pcr-queue-pickup` on the desktop table's pickup button — see Decisions
  Made for why this lives here, not on `PricingRequestDetailPage.jsx`.
- `frontend/src/features/commissions/CommissionPage.jsx` — `commission-approve`
  on the approve icon button. `commission-reject` was **not** added — see
  Decisions Made (not needed by any spec; can be added trivially later,
  same pattern).

### Real bugs found + fixed (both in `frontend/src/api/mockApi.js`)
1. **`generateFactoryEmailDrafts` (~line 5639)**: a new factory-quote
   item's `quotedUnit`/`unitBasis` defaulted to `item.requestedUnit` (a
   display LABEL, e.g. `"ตร.ม."`) instead of `item.requestedUnitBasis`
   (the canonical code, e.g. `'PER_SQM'`, that the response form's
   `<select>` and `recalculateCosting`'s cost math actually key on). Any
   import officer who filled in only the raw price without re-touching
   the (seemingly-already-selected) dropdown would submit an unrecognised
   unit-basis code, and `recalculateCosting` would 422 with `"Unsupported
   factory quote unit basis '<label>'"` — silently blocking "Submit to
   CEO" for that quote, no matter what the officer did right. Fix: seed
   both fields from `item.requestedUnitBasis`.
2. **`startPricingDecision` (~line 6028)**: the `decision` object
   literal's own `items: costing.items.map(...)` callback referenced
   `decision?.id` — but that `.map()` runs synchronously while `decision`
   itself is still being constructed, a temporal-dead-zone violation.
   It threw `"Cannot access 'decision' before initialization"` on
   **every single call**, unconditionally breaking the CEO's
   "เริ่มพิจารณาราคาขาย" (start review) button for **every** PCR, for
   any user — not a corner case, not test-specific. Fix: drop the
   premature self-reference (leave it `null`); the very next line
   (`decision.items.forEach((item) => { item.pricingDecisionId =
   decision.id; })`, unmodified) already exists specifically to fix this
   up once `decision.id` is known — its own comment ("Fix up the
   self-reference now that decision.id is known") shows this was the
   original intent, just broken by JS evaluation order.

   Both fixes are one-line, behavior-neutral data-wiring/evaluation-order
   corrections — not business-logic changes, and neither touches
   authorization.

### New specs
- `frontend/e2e/deal-creation.spec.js` — sales walks the full 6-section
  `TicketCreateModal` (customer → project → contact/entry-channel → items
  → deal details → review) end to end, lands on a fresh DRAFT deal,
  verified both on the deal page and back in the deal list via the
  `DataTable` search box.
- `frontend/e2e/pcr-chain.spec.js` — one session, role-switched
  (`switchRole`, never a hard reload): sales creates a deal + pricing
  request (submitted straight to Import) → import picks up, gets a
  factory quote, costs it → CEO reviews and approves the selling price →
  sales issues the customer quotation and records the ACCEPTED outcome.
  Exercises the real `PricingRequest` status machine end to end (DRAFT →
  SUBMITTED → IMPORT_REVIEWING → COSTING_IN_PROGRESS →
  READY_FOR_CEO_REVIEW → CEO_REVIEWING → APPROVED_FOR_QUOTATION →
  QUOTATION_ISSUED → QUOTATION_ACCEPTED). This spec is what surfaced both
  mock bugs above — see its own file-header comments for the full story.
- `frontend/e2e/deposit-fulfilment-close.spec.js` — builds its own deal
  through the full PCR chain (no seeded ticket sits at a PCR-driven
  deposit/fulfilment status — see Decisions Made), then drives
  deposit-paid (create + issue notice, account confirms) → fulfilment (IR
  chain, a genuine partial delivery of 1-of-2 units, then complete) → the
  real three-party close (account confirms readiness with an uploaded
  invoice attachment, CEO verifies) through to a `closed` ticket.
- `frontend/e2e/commission.spec.js` — `sales_manager` creates a manual
  commission (lands `MANAGER_APPROVED`, since a `sales_manager`-created
  entry skips the `SUBMITTED`→manager-review step per
  `CommissionService#createManualCommission`) → CEO approves it via the
  confirm dialog.
- `frontend/e2e/hr.spec.js` — OT create (employee) → manager approve
  (`warehouse.manager@glr.co.th`, credential login — no quick-login button
  exists for this persona) → CEO approve; leave create with its real
  auto-decision outcome (see Decisions Made — the task brief's assumption
  about leave approve/reject was wrong); attendance render smoke check
  for hr + employee.

## Commands Run
```bash
git fetch origin
git checkout -b test/stage-K2-phase2-flows origin/main
cd frontend
npm ci
npx playwright install --with-deps chromium
npm run test:e2e         # iteratively, per spec, many times while debugging
npm test                 # Vitest, after each testid/mockApi.js change
npm run lint
unset VITE_USE_MOCKS && npm run build
```

## Test / Build Results
- **Vitest (`npm test`)**: 545/545 passing (63 files) — unchanged count
  before/after every testid + mockApi.js change in this branch.
- **Playwright (`npm run test:e2e`, full suite)**: **25/25 passing**
  locally against the mock dev server on port 5250 (10 `auth.spec.js` +
  8 `rbac.spec.js` from Phase 1, unmodified, still green; + 1
  `deal-creation.spec.js` + 1 `pcr-chain.spec.js` + 1
  `deposit-fulfilment-close.spec.js` + 1 `commission.spec.js` + 3
  `hr.spec.js` = 7 new). Total runtime ~60s. The `[WebServer] ...
  ECONNREFUSED /api/auth/login` lines are the same pre-existing benign
  noise Phase 1's handoff already documented (a fire-and-forget real-
  backend call with nowhere to land under pure mocks).
- **Lint (`npm run lint`)**: 0 errors, the same 1 pre-existing warning in
  `PayrollPage.jsx` (`react-hooks/exhaustive-deps`) Phase 1 already noted
  — untouched file, unrelated to this branch. Note: `npm run lint` scopes
  `eslint src` only — it does **not** lint `frontend/e2e/`. Running
  `npx eslint e2e/` directly surfaces `no-undef` for browser globals
  (`URL`, `Buffer`, `window`, `console`, `PopStateEvent`) across **both**
  Phase 1's files (`rbac.spec.js`, `helpers/auth.js`) and this branch's
  new specs — a pre-existing gap in the e2e ESLint env config, not a
  regression introduced here. Not fixed (out of scope; would need an
  `env: { browser: true, node: true }` override for `e2e/**` in the
  eslint config, which isn't part of the actual `npm run lint` gate).
- **Build (`npm run build`, `VITE_USE_MOCKS` unset)**: succeeds — confirms
  every testid addition and the mockApi.js bug fixes don't affect the
  non-mock production build path (mockApi.js is never imported when
  `VITE_USE_MOCKS` is unset — see `src/api/index.js`).
- Backend: not touched, not run (no backend changes in this branch).

## Authz Evidence
No authorization change in this task. Every testid is attribute-only; the
two mockApi.js fixes are data-wiring/evaluation-order corrections with no
role/scope/permission logic touched. `commission.spec.js`'s two-step
approve and `hr.spec.js`'s two-step OT approve both drive real role-gated
mutations, but — per CLAUDE.md and Phase 1's own precedent — this is
**frontend-gating/UI-driving only, not a backend authz proof**. Say so
explicitly: none of this verifies anything against the real Java services.

## Decisions Made

- **`demoData.js` employeeId fix: NOT needed, verified rather than
  applied.** The task flagged the `sales`/`import`/`account` personas'
  `employeeId: null` as a likely blocker and asked me to seed a real one
  if a flow broke because of it. I traced every ownership/scoping check
  these specs exercise (`isOwner = user.id === ticket.createdById` on
  `TicketDetailPage.jsx` and `DealDepositPanel.jsx`;
  `canCreatePricingRequest`/`canCreateManualCommission`/etc. in
  `pricingRequestMeta.js`/`routes.js`) and every one of them compares
  against `user.id` (the login/session user id), never `user.employeeId`.
  Every seeded ticket's `createdById` is `6` — the same id as the `sales`
  quick-login persona — so ownership already resolves correctly for every
  flow these specs drive. The 3 routes actually gated on
  `!!user.employeeId` (`/profile`, `/employee-requests`, `/leave`) are
  not reachable by `sales`/`import`/`account` anyway (rbac.spec.js
  already covers that denial). No flow broke because of the null
  `employeeId`, so no demoData.js change was made — flagging this
  explicitly since the task asked me to report either way.
- **`PricingRequestDetailPage.jsx` has no "pickup" control — moved that
  testid to `PricingRequestQueuePage.jsx`, where pickup actually lives.**
  The task brief asked for "pickup / propose-price / decision" testids on
  `PricingRequestDetailPage.jsx`, but pickup (`canPickupPricingRequest`,
  the "รับเรื่อง" button) is only ever rendered on the queue/list page
  (`PricingRequestQueuePage.jsx`) — the detail page has no pickup action
  of its own (confirmed by reading both files and grepping for "pickup").
  Added `pcr-queue-pickup` where the code actually is; documented the
  divergence rather than fabricating a control that doesn't exist.
- **`TicketDetailPage.jsx` has no "submit" or "confirm-deposit" action
  anymore — not instrumented, per the task's own "attribute-only, no
  behavior change" rule (nothing to attach a testid to).** The task brief
  named `submit`/`confirm-deposit`/`confirm-final`/`close` as the four
  testids to add. Tracing the actual code: ticket-level `submit()` was
  retired in Phase 2 Slice S1 ("engine collapse" — see
  `docs/agent-handoffs/104`) and has no UI control left on this page;
  pricing now starts via `PricingRequestPanel`'s "สร้างใบขอราคา" +
  "ส่งให้ Import" instead (already covered — no new testid needed, its
  button text is unique enough). `confirm-deposit` (`confirmDepositPaid`)
  moved entirely into `DealDepositPanel.jsx` in an earlier slice — that's
  where `deal-deposit-confirm` lives, not on this page. Added testids only
  to what's actually there: the four real `primaryAction` buttons
  (confirm-customer / confirm-final / confirm-close / verify-close).
- **Catalog-backed PCR items, not free-text ones, in `pcr-chain.spec.js`
  and `deposit-fulfilment-close.spec.js`.** `PricingRequest.submit()`
  422s any item with no resolved Price Catalog snapshot
  (`submitPricingRequestCatalogGate`, mirroring a real financial-integrity
  gate from an earlier review) — a real, intentional, documented rule,
  not a bug. Both specs drive `PricingRequestCreateModal`'s own catalog
  search/pick UI (product "Corner", priceId 5, factory REFIN — chosen
  specifically because its `priceUnit` is `PER_PIECE`, needing no extra
  sqm/box/linear-m conversion factor to cost out cleanly) instead of
  typing a custom item, matching what a real user must do.
- **`deposit-fulfilment-close.spec.js` builds its own deal from scratch —
  no seeded shortcut exists.** The task suggested starting from a seeded
  deal id to shorten the flow. Checked: `mockPricingRequests` is always
  empty at boot (`const mockPricingRequests = [];`), and while 5 seeded
  tickets (ids 6/11/12/13/14) do carry non-null `paymentStatus`/
  `fulfillmentStatus`, those are legacy pre-PCR-redesign rows — none has
  a corresponding `PricingRequest` at `QUOTATION_ACCEPTED`, which
  `canCreateDepositNoticeFromQuotation`/`canConfirmOrder` both require.
  So there is no PCR-chain-reachable shortcut; the spec runs the full
  chain (reusing `pcr-chain.spec.js`'s proven steps) before it can even
  reach the deposit panel.
- **`confirmOrder`'s "one deliberate bridge write" is a hard dependency
  for the three-party close, and is called explicitly before any
  close-gated action.** A new deal's legacy `ticket.status` starts and
  stays `'draft'` forever under the current PCR-driven flow — but
  `TicketDetailPage.jsx`'s `confirmFinalPayment`/`confirmClose`/
  `verifyClose` gates all require `ticket.status === 'quotation_issued'`.
  Traced this down to `confirmOrder`'s own comment ("The one deliberate
  bridge write... guarded FROM 'draft' only", mirroring
  `TicketRepository.markQuotationIssuedForOrderConfirmation") — it
  deliberately flips `'draft'` → `'quotation_issued'` the first time it
  runs. Confirmed empirically (test passed) that this bridge is real and
  suffices; documented the dependency in the spec's own header so it
  doesn't look accidental to the next reader.
- **`DealDepositPanel`'s create-notice button navigates away
  (`navigate('/tickets/:id/deposit')`) — `deposit-fulfilment-close.spec.js`
  follows that page's own "กลับ" (`onBack`) button to return, not a raw
  `spaGoto`.** A first attempt used `spaGoto(page, ticketPath)` right
  after clicking create-notice; it silently failed to leave
  `DepositNoticePage` (still showed that page's own heading afterward) —
  likely some router-level state that a bare `pushState`+`popstate` pair
  doesn't unwind cleanly. Using the page's own in-app "กลับ" button (a
  real `onClick` navigation, not history manipulation) works reliably.
- **`DealStagePanel.jsx`/`CommissionPage.jsx` testid coverage is scoped to
  what these specs actually drive, not exhaustive.** `DealStagePanel`
  only got `deal-stage-panel` + `deal-stage-advance` (hold/dormant/lost/
  edit/reopen untouched); `CommissionPage` only got `commission-approve`
  (no `commission-reject` — nothing in these 5 specs rejects a
  commission). Both are trivial, same-pattern additions for a future spec
  that needs them — not added speculatively per the "attribute-only,
  behavior-neutral, driven by an actual test" discipline.

## Assumptions
- Same as Phase 1: `VITE_ENABLE_SALES` is not `'false'` anywhere in this
  environment, so `SALES_ENABLED` is `true` under the Playwright dev
  server.
- Each `npx playwright test` invocation spins its own fresh mock dev
  server (`reuseExistingServer: false`) — verified no leftover process
  held port 5250 between runs (checked via `lsof`/`ps` mid-session while
  debugging an apparent state-carryover symptom that turned out to be
  deterministic re-seeding, not real leakage — see the mock-bug
  investigation trail in this session if it matters to a future debugger).

## Known Risks
- **`e2e-ci.yml` (Phase 1's, unmodified) has still only run locally, not
  in real GitHub Actions, for these 5 new specs.** Phase 1's handoff
  flagged the same risk for its own 2 specs; this branch adds runtime
  (~60s total locally for the full suite, up from ~15-20s) — worth
  confirming the GitHub Actions runner doesn't hit a materially different
  timeout profile before promoting `e2e-ci.yml` to a required check.
- **Timing/date-math traps in `hr.spec.js`'s leave sub-test**: the
  original `nextWeekdayAtLeast` helper checked weekday via local
  `Date.getDay()` but formatted the returned string via UTC
  `toISOString()` — a real bug in my own test code (not the app) that
  silently returned a *different* calendar date than the one whose
  weekday was checked, depending on the runner's local timezone offset.
  Fixed to format from the same local `getFullYear`/`getMonth`/`getDate`
  used for the weekday check. Flagging this pattern explicitly in case a
  future spec reaches for date-math again — it's an easy trap (see also
  the existing memory note `bangkok-timezone-test-flake.md` for the same
  class of bug on the backend side).
- **The 2 mockApi.js bug fixes are scoped to what `pcr-chain.spec.js`
  exercises** — I did not audit the rest of `mockApi.js` for similar
  patterns (e.g., other object literals with a premature self-reference,
  or other `requestedUnit`-vs-`requestedUnitBasis` mix-ups). Both bugs
  were 100% reproducible and would affect any real user, so they were
  worth fixing on sight, but a broader audit was out of scope for this
  branch.
- **`versionNo` cosmetic bug spotted, not fixed**: `createCosting`
  (mockApi.js) computes `id: mockPricingCostingSeq++, ..., versionNo:
  mockPricingCostingSeq` in the same object literal — the post-increment
  on `id` already advances the counter before `versionNo` reads it, so a
  brand-new PCR's first costing displays "Version 2" instead of "Version
  1". Purely cosmetic (doesn't affect any gate or the specs), so left
  alone rather than bundled into an unrelated fix — worth a follow-up.

## Things Not Finished
- `e2e-ci.yml` promotion to a required check — still explicitly deferred
  (per both Phase 1's and this branch's task briefs) until it's proven
  stable in real GitHub Actions across a few PR runs.
- The documented mock gaps from the task brief (payroll
  processing/exports throwing `ไม่รองรับในโหมดทดลองใช้งาน`,
  forced-password-change unreachable, attachment-upload not asserting
  real bytes) were **not turned into their own assert-the-gap specs** —
  none of the 5 flow specs in this branch happened to touch payroll
  processing or forced-password-change, and the one file upload this
  branch does drive (`deposit-fulfilment-close.spec.js`'s invoice
  attachment) only asserts the upload UI's own success state, never
  downloaded bytes, so no gap assertion was needed inline. If a future
  spec drives payroll processing directly, assert the Thai
  not-supported message per the task brief's original guidance.
- No PR merge (explicitly out of scope — "do NOT merge").

## Recommended Next Agent
Opus review of this PR (verify the two mockApi.js bug-fix claims against
the diff directly, and spot-check a couple of the testid/spec pairings),
then merge once approved. After merge, `e2e-ci.yml`'s first few real
GitHub Actions runs are worth watching before promoting it to a required
check (see Known Risks).

## Exact Next Prompt
```
Review PR for branch test/stage-K2-phase2-flows (Stage K2 Phase 2 — Playwright
e2e flow specs) against docs/agent-handoffs/111_test-stage-K2-phase2-flows.md.

Specifically verify:
1. The two mockApi.js bug-fix claims (generateFactoryEmailDrafts's
   quotedUnit/unitBasis defaulting, startPricingDecision's temporal-dead-zone
   self-reference) are real and the fixes are correct/minimal — read the diff
   directly, don't take the handoff's word for it.
2. Every testid added is attribute-only (no onClick/logic changes riding along).
3. The 5 new specs actually assert meaningful state transitions, not just
   "a button existed and was clickable" — spot-check pcr-chain.spec.js and
   deposit-fulfilment-close.spec.js in particular (the longest, most
   multi-role chains).
4. Confirm `cd frontend && npm run test:e2e` is still 25/25 green and
   `npm run lint && npm test && npm run build` (VITE_USE_MOCKS unset) are
   still green before approving.

If approved, merge to main (do not force-merge past a red check). After
merge, promote e2e-ci.yml to a required check only once it has run clean
on a few real PRs against main — it hasn't been observed in real GitHub
Actions yet for either Phase 1 or Phase 2's specs.
```
