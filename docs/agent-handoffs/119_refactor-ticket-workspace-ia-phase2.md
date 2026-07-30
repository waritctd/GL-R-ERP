# Agent Handoff

## Task
Apply an Opus review's "PASS WITH FIXES" verdict on the 7-tab ticket-detail IA rebuild
(`ticketDetailTabs.js`, `Tabs.jsx`, `DealHistoryPanel.jsx`, `TicketDetailPage.jsx`, `DealQuotationPanel.jsx`,
`DealStateHeader.jsx`) plus e2e/housekeeping fixes, per an owner-decided fix list (FIX 1–FIX 6). No
implementation choices were left open — the fix list was prescriptive; this handoff records what was
built and what was found while verifying it.

## Branch
`refactor/ticket-workspace-ia-phase2`

## Base Commit
`2aba97f0` (merge of PR #346, `fix/sales-quotation-sticky-cta-dead-click` — see handoff 118)

## Current Commit
Not committed — working tree only, per instruction ("Do NOT commit, push, or switch branches").

## Agent / Model Used
Claude (Sonnet) — implementation pass applying a prior Opus review's fix list.

## Scope

### In Scope
- FIX 1: restore commenting + audit trail for import/account inside the "กิจกรรม" tab, while keeping
  the follow-up activity feed gated on deal ownership.
- FIX 2: gate the "เอกสาร" tab on the real `AttachmentController.requireTicketAccess` identity model
  (participant or manager), not an unconditional `() => true`.
- FIX 3: fix the cross-tab `issue_quotation` first-click race (queued action fired before the
  destination panel's own query settled).
- FIX 4: comparator transitivity bug in `DealHistoryPanel`'s sort; a stale/wrong width claim in
  `DealStateHeader.jsx`'s comment; dead `muted` prop + stale `dealWorkspaceTabs.js` references in
  `Tabs.jsx`/`ticketDetailTabs.js`.
- FIX 5: e2e locators that reference a tab-scoped panel without opening its tab first; actually run
  the suite.
- FIX 6: write this handoff; drop the `.claude/launch.json` reformat/worktree-specific entries;
  `.gitignore` the untracked `.impeccable/` tooling output.

### Out of Scope (and not touched)
- Any backend file.
- `salesViewScope.js` (role/section logic).
- The `can = {...}` block's contents (verified byte-identical to `origin/main` — see below).
- Business logic / API contracts / schema.
- A newly-surfaced gap found while running e2e (see "Known Risks" — deliberately NOT patched).

## Files Changed
- `frontend/src/features/tickets/ticketDetailTabs.js` (untracked, pre-existing on this branch from
  the rebuild) — "กิจกรรม" tab's `isVisible` changed from `sections.dealTracking`-gated to
  role-unconditional (`() => true`, same as ภาพรวม/เอกสาร); "เอกสาร" tab's doc comment rewritten to
  explain the real per-instance gate now lives in `TicketDetailPage.jsx` (`canViewDocumentsTab`), not
  here (role+sections alone can't express it); file header rewritten to drop the unverifiable
  `dealWorkspaceTabs.js` reference.
- `frontend/src/features/tickets/ticketDetailTabs.test.js` (untracked) — updated the 4 tests whose
  expectations changed because "activity" is now role-unconditional (account/import/hr/employee all
  gain it); `resolveTicketDetailTab` test updated the same way.
- `frontend/src/components/common/Tabs.jsx` (untracked) — removed the dead `muted` prop (no caller
  ever passed it — confirmed via repo-wide grep) and its `opacity-60` styling branch; removed the 3
  stale comments naming `dealWorkspaceTabs.js`, a file that never existed on `origin/main`; rewrote
  the header/inline comments to describe current behaviour.
- `frontend/src/features/tickets/DealHistoryPanel.jsx` (untracked) — sort comparator rewritten: the
  old event-id tiebreak only applied to event-vs-event pairs, making the "tie" relation non-transitive
  (empirically verified: the old comparator produced 4 different orderings across 6 input
  permutations of the same 3-row set; see "Tests / Build Results"). Fixed with a universal secondary
  key (kind rank, then id) applied to every row pair. Added `canViewActivityFeed` prop and a
  distinguishing empty-state (doesn't claim "nothing has happened" when the follow-up feed was simply
  never fetched for this viewer).
- `frontend/src/features/tickets/DealHistoryPanel.test.jsx` (untracked, pre-existing) — unchanged;
  still green against the new comparator.
- `frontend/src/features/tickets/DealQuotationPanel.jsx` — `openIssueQuotation`'s decision tree
  factored into `attemptIssueQuotation`, called once immediately (via the ref) and once more,
  automatically, from a new `useEffect` keyed on `quotationsQuery.isSuccess` — closes the cross-tab
  race where a queued `runOnTab` action fired before the panel's own query had a chance to settle.
- `frontend/src/features/tickets/DealStateHeader.jsx` — corrected the stage-label-width comment (real
  widest label measured 190px, not 167px) and explained that wrapping — not column width — is what
  prevents overflow. No CSS/logic changed.
- `frontend/src/features/tickets/DealTrackingPanel.jsx` — pre-existing rebuild diff on this branch,
  untouched by this session beyond how `TicketDetailPage.jsx` now calls it (see below).
- `frontend/src/features/tickets/TicketDetailPage.jsx`:
  - `canComment={can.comment}` (dropped `&& canViewDealTracking`).
  - `DealTrackingPanel` render now explicitly wrapped in `{canViewDealTracking ? (...) : null}`
    (previously implicit via the whole tab being dealTracking-gated) — its `canEdit` prop simplified
    from `canViewDealTracking && (isOwner...)` to `isOwner || role === 'sales_manager' || role ===
    'ceo'` since the surrounding conditional now carries the `canViewDealTracking` gate.
  - `DealHistoryPanel` gets a new `canViewActivityFeed={canViewDealTracking}` prop; `canAddActivity`
    unchanged.
  - New `canViewDocumentsTab` (role===ceo||sales_manager||isOwner||user.id===summary.assignedToId),
    computed right after `ticket` loads (before the loading/no-ticket early returns) so
    `attachmentsQuery`'s `enabled` can depend on it.
  - `attachmentsQuery.enabled` now `!!ticketId && canViewDocumentsTab` (previously fired for anyone).
  - New `visibleTabItems`/`visibleActiveTab` derived values: filter "documents" out of the rendered
    tab list and fall back `?tab=documents` to ภาพรวม when `canViewDocumentsTab` is false — mirrors
    `resolveTicketDetailTab`'s existing role-level fallback for the one tab that predicate can't see
    (it needs per-ticket `createdById`/`assignedToId`, not just role). All 7 `TabPanel active={...}`
    checks and the `<Tabs value={...}>` prop switched from `activeTab` to `visibleActiveTab`.
  - Import list: added `DEFAULT_TICKET_DETAIL_TAB_ID`.
- `frontend/src/features/tickets/TicketDetailPage.test.jsx`:
  - New regression test: "creates and issues the quotation when clicked directly from ภาพรวม" — drives
    the sticky `issue_quotation` click WITHOUT pre-opening the ใบเสนอราคา tab (unlike every existing
    test in that describe block, which the review pointed out makes `runOnTab` a no-op). Verified to
    FAIL without the FIX 3 change (see below).
  - 5 pre-existing tests updated for FIX 1/FIX 2's changed tab-visibility rules (account/import now
    keep "กิจกรรม"; "เอกสาร" visibility rewritten from "every role" to the real identity gate, with
    cases for ceo/owner/account/non-assignee-import/assignee-import; the `?tab=` fallback test moved
    off "activity" — no longer role-hidden — onto "pricing", plus a new test proving the FIX-2
    per-instance fallback for a stale `?tab=documents` deep link).
- `frontend/e2e/pcr-chain.spec.js` — opens "ใบเสนอราคา" before referencing `deal-quotation-panel`
  (line ~173 in the pre-fix file).
- `frontend/e2e/deposit-fulfilment-close.spec.js` — opens "ใบเสนอราคา" before `deal-quotation-panel`
  (~127); opens "การเงิน" before both `deal-deposit-panel` references (~168, ~186); opens
  "จัดซื้อ-ส่งมอบ" before `deal-fulfilment-panel` (~194); **plus one more found only by actually
  running the suite** (not in the review's line list): re-opens "การเงิน" again after the
  `DepositNoticePage` "กลับ" round trip, because `DepositNoticeRoute`'s `onBack` (`App.jsx`) is a fixed
  `navigate(/tickets/:id)`, not `navigate(-1)`, so it drops the `?tab=` query string and the page
  resets to ภาพรวม.
- `frontend/e2e/deposit-fulfilment-close.spec.js` — **second pass, this session, per an explicit
  owner decision on the "PRODUCTION GAP: three-party close's INVOICE step" finding below.** The spec
  no longer asserts the invoice-attach → CONFIRM_CLOSE → CEO VERIFY_CLOSE tail, which has never
  worked against the real Java service (see "Production Gap" section under Known Risks). Renamed the
  test to `'deposit paid -> fulfilment -> final payment confirmed (invoice-gated close tracked
  separately)'`; added a file-header comment block explaining the contradiction and the owner
  decision; the test body now runs for real through deposit issue/confirm, the full IR→shipping→
  goods-received→partial-delivery(1/2)→complete(2/2) fulfilment chain, and account's FINAL_PAYMENT
  confirmation (none of which depend on the broken invoice control), then calls
  `test.fixme(true, '...')` immediately before the `#ticket-invoice-file` upload step — the invoice
  attach, `ticket-detail-confirm-close`, and `ticket-detail-verify-close` assertions were removed
  rather than left dead. `test.fixme()` reports this test as **fixme/skipped**, never passed; the
  in-body comment names the exact contradiction and points at
  `TicketIaAuthzMatrixIntegrationTest.attachments_accountIsNeitherParticipantNorManagerAndIsRefused`
  so the next reader cannot mistake this for a flaky or obsolete test.
- `.claude/launch.json` — reverted to `origin/main` via `git checkout origin/main -- .claude/launch.json`
  (dropped a whole-file reformat + session-specific worktree entries that were in the working tree
  before this session started).
- `.gitignore` — added `.impeccable/` (untracked design-review tooling output at both repo root and
  `frontend/`, which `git add -A` would otherwise sweep in).

## Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
cd frontend && npx playwright install chromium
cd frontend && npx playwright test
cd frontend && npx playwright test --list
git stash push -- frontend/src/features/tickets/DealQuotationPanel.jsx   # mutation-check FIX 3
cd frontend && npx vitest run src/features/tickets/TicketDetailPage.test.jsx -t "cross-tab race"
git stash pop
git checkout origin/main -- .claude/launch.json
```

### Second pass (this session) — scoping down the invoice-gated spec
```bash
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
cd frontend && npx playwright test
cd frontend && npx playwright test --reporter=list   # to confirm exactly which test reports skipped
```

## Test / Build Results
- `npm run lint` — **0 errors**, 1 pre-existing unrelated warning (`PayrollPage.jsx:336`, missing
  `useEffect` dep — untouched by this branch).
- `npm test -- --run` — **74 files / 791 tests, all pass** (785 baseline + the FIX-3 regression test,
  plus 5 pre-existing tests rewritten for FIX 1/2's new tab-visibility rules — none skipped, none
  newly xfail).
- `npm run build` — **pass**, no new warnings.
- **Mutation-check, FIX 3** (repo rule: "a green test that cannot fail is not evidence"): stashed only
  `DealQuotationPanel.jsx` back to the committed (pre-fix) version and re-ran the new "cross-tab race"
  test in isolation — it **failed** (timed out waiting for `createCustomerQuotation`, reproducing the
  exact dead-end the fix closes). Restored the fix; the same test then passed. Diff confirmed
  identical afterwards (`git stash pop` restored the working-tree edit exactly).
- **Empirical proof, FIX 4 comparator**: reproduced the non-transitivity directly in Node — sorting the
  same 3-row set (`E1` id 1, an activity, `E2` id 2, all one timestamp) across all 6 input
  permutations, the OLD comparator produced **4 different final orderings**; the NEW comparator
  produced **exactly 1**, regardless of input order. (Script inline in this session; not committed.)
- **Playwright e2e — actually run, not skipped.** `npx playwright test` (8 spec files, 64 tests,
  chromium, mock frontend on port 5250):
  - **63 passed.**
  - **1 failed: `deposit-fulfilment-close.spec.js:31` "deposit paid -> fulfilment -> three-party close
    -> CLOSED_PAID"** — see "Known Risks" below; this is a genuine, newly-surfaced consequence of FIX
    2 correctly enforcing the real identity gate, not an unfixed locator bug. `pcr-chain.spec.js`
    (also named in FIX 5) passes cleanly, including the ISSUE_QUOTATION step this session's FIX 3
    touches.
  - 0 skipped. No flake observed across 2 full runs of the whole suite.

### Second pass (this session) — after scoping the spec down per owner decision
- `npm run lint` — **0 errors**, same 1 pre-existing unrelated warning (`PayrollPage.jsx:336`).
- `npm test -- --run` — **74 files / 791 tests, all pass** (unchanged from the first pass; this
  session touched no Vitest-covered source, only the e2e spec + this handoff).
- `npm run build` — **pass**, no new warnings.
- `npx playwright test` — **64 tests total: 63 passed, 1 skipped (via `test.fixme()`), 0 failed.**
  The 1 skipped is `deposit-fulfilment-close.spec.js:59` — confirmed by name with
  `npx playwright test --reporter=list`, which prints it with a `-` (not run) marker, distinct from
  the 63 `✓` passes. This is the exact test the "Production Gap" section below documents: it now runs
  for real through deposit + the full fulfilment chain + FINAL_PAYMENT confirmation, then calls
  `test.fixme(true, '...')` immediately before the invoice-attach step and stops — it is reported as
  **skipped**, not passed, and must be reported to the owner as skipped.

## Authz Evidence
**No authorization change.** Specifically:
- The `can = {...}` block in `TicketDetailPage.jsx` is **byte-identical to `origin/main`** — diffed
  programmatically (extracted both blocks by regex, compared character-for-character: identical, 2441
  bytes each).
- `salesViewScope.js` is untouched (`git diff origin/main -- frontend/src/features/tickets/salesViewScope.js`
  is empty).
- No backend file changed (`git status --porcelain | grep backend` is empty).
- FIX 1 and FIX 2 are **deliberate, owner-approved capability decisions** recorded here per CLAUDE.md,
  not accidental scope creep:
  - **FIX 1** restores two capabilities the backend already grants but the frontend had accidentally
    withdrawn: commenting (`TicketService.comment`'s `requireViewAccess`, not deal ownership) and the
    plain audit trail (`ticket.events`, passed through unchanged by `projectForRole`/`events()` for
    every viewer). It deliberately does NOT restore `DealTrackingPanel`'s win%/designer/owner/buyer
    fields for import/account — the review named only the two capabilities above as lost, so this
    session did not newly expose a third one nobody asked to restore; that panel keeps its
    pre-existing `canViewDealTracking` gate. The one genuinely narrower capability
    (`TicketService.listActivities`, the follow-up feed) stays gated exactly as before, just moved
    inside the tab rather than gating the whole tab.
  - **FIX 2** narrows the "เอกสาร" tab's frontend visibility to mirror
    `AttachmentController.requireTicketAccess` (ticket participant OR {ceo, sales_manager} — `hr`
    omitted since it never reaches this page). This is a presentation projection tightening to match
    an *already-enforced* backend gate, not a new rule — the tab was previously shown unconditionally,
    producing a swallowed 403 and a lying "ยังไม่มีไฟล์แนบ" empty state for account (always) and
    non-assignee import. It is the "mock more permissive than production" direction CLAUDE.md warns
    about, being closed, not opened.
- Tab visibility (all 7 tabs) mirrors gates already pinned against the real Java service in
  `backend/src/test/java/th/co/glr/hr/ticket/TicketIaAuthzMatrixIntegrationTest.java` — this session
  did not add or re-verify any backend test; it only aligned the frontend projection with what that
  suite already proves.
- **All runtime verification this session was `VITE_USE_MOCKS=true`** (Vitest/RTL unit tests + the
  mock-frontend Playwright e2e suite). Per CLAUDE.md, the permission aspect of anything demonstrated
  here is therefore **UNVERIFIED by execution against the real Java service** — the frontend gates now
  match what `TicketIaAuthzMatrixIntegrationTest` already proves on paper, but this session ran no new
  backend test and did not re-run that suite.

## Decisions Made
- Kept `DealTrackingPanel` gated on `canViewDealTracking` inside the now-always-visible "กิจกรรม" tab
  (FIX 1 scope call — see Authz Evidence).
- Applied FIX 2's per-instance gate at the `TicketDetailPage.jsx` level (`canViewDocumentsTab`) rather
  than widening `ticketDetailTabs.js`'s `(role, sections)` `isVisible` signature to accept ticket
  instance data — keeps that module's contract (pure function of role) intact and matches the existing
  pattern where instance-specific filters live on the page (e.g. `canViewPricingRequests`).
- For FIX 3, fixed the race by having `DealQuotationPanel` retry its own queued intent once its query
  settles (an internal `useEffect`), rather than teaching the parent's generic `runOnTab` queue about
  child readiness — keeps the fix local to the one panel with the actual race, per the review's own
  framing ("this is exactly what is uncovered"), and avoids touching `PricingRequestPanel`'s
  `openCreate` (not reported as broken, not touched).

## Assumptions
- The owner's fix list was taken as final and prescriptive; no fix was second-guessed or partially
  applied.
- "Every viewer of the deal" (FIX 1) means the same role set that can reach this page at all — the
  ticket-read gate (`VIEWER_ROLES`), not a broader set.

## Known Risks
- **New finding from actually running e2e (FIX 5): the sales three-party-close workflow's INVOICE
  attachment step has no working path once FIX 2 lands.** `deposit-fulfilment-close.spec.js` fails at
  `#ticket-invoice-file` because: (1) that specific upload control only renders for `isAccount`
  (pre-existing, untouched by this session — `TicketDetailPage.jsx`'s "R5: Attachments" block); (2) it
  is the ONLY control in the UI that sets `attachType: 'INVOICE'` (the generic "แนบไฟล์" button never
  does, even for ceo/sales owner); (3) `account` is now correctly excluded from the "เอกสาร" tab
  entirely by FIX 2, since `attachments_accountIsNeitherParticipantNorManagerAndIsRefused` proves the
  real backend already refuses it. Net effect: **no role that can pass FIX 2's real identity gate has
  a UI control that produces an INVOICE-type attachment**, so `hasInvoiceAttachment`
  (`requireClosePrerequisites`) can never become true through this UI as it stands today. This is a
  genuine, pre-existing product gap (the account-only INVOICE button was already backend-incompatible
  before this branch) that FIX 2 has now surfaced rather than caused. **Deliberately NOT patched** —
  widening who may click that control is a workflow/ownership decision (which role attaches the
  closing tax invoice), not a UI-repair fix, and is out of this branch's authority per CLAUDE.md's
  business-logic-freeze rule. Flagged as a follow-up task (see below) rather than worked around.

### Production Gap — owner decision recorded (this session, second pass)
- **The contradiction, pinned to exact lines:**
  - `frontend/src/features/tickets/TicketDetailPage.jsx:1893` — the `#ticket-invoice-file` upload
    control (the ONLY UI control that produces an `attachType: 'INVOICE'` attachment) is gated
    `!TERMINAL.includes(st) && isAccount` — i.e. only the `account` role can ever click it.
  - `backend/src/main/java/th/co/glr/hr/attachment/AttachmentController.java:37` (`MANAGER_ROLES =
    Set.of("hr", "sales_manager", "ceo")`) and `:116-125` (`requireTicketAccess`) — `account` is
    neither a ticket participant (creator/assignee) nor in `MANAGER_ROLES`, so every attachment
    upload/list/delete call it makes 403s for real. This exact refusal is pinned by
    `TicketIaAuthzMatrixIntegrationTest.attachments_accountIsNeitherParticipantNorManagerAndIsRefused`
    (`backend/src/test/java/th/co/glr/hr/ticket/TicketIaAuthzMatrixIntegrationTest.java:481-483`).
  - Net effect: the UI offers `account` the only button that can attach an invoice, and the backend
    refuses `account` on that exact endpoint. The three-party close's INVOICE precondition has never
    been reachable through this UI against the real Java service — not a regression introduced by
    FIX 2 or by this branch, a pre-existing product-level dead end that FIX 2's correct tightening of
    the "เอกสาร" tab merely surfaced (previously masked by the tab being shown unconditionally).
  - **The mock hid it.** `frontend/src/api/mockApi.js`'s `attachments` namespace has no role gate at
    all, so `deposit-fulfilment-close.spec.js` passed under `VITE_USE_MOCKS=true` for as long as it
    existed. This is the same shape CLAUDE.md calls out by name as the issue-#199 pattern ("mock let
    HR approve OT; the real `OvertimeService` returns 403" / "a mock more permissive than production
    is the dangerous direction") — here it's `account` + attachments instead of HR + OT approval, but
    the mechanism is identical: an agent (or a green CI run) could report the three-party close as
    "working" because the mock never enforced the gate the real service does.
- **Owner decision (this session):** out of scope for `refactor/ticket-workspace-ia-phase2`. Fixing
  who may attach the closing invoice is a backend authorization change (widening `MANAGER_ROLES`,
  adding a narrower account exception, or reassigning the UI control to a role that already has
  attachment access) and, per CLAUDE.md's "Permission changes must ship evidence" rule, must ship its
  own real-DB integration test through the real service — not ride along inside a UI-IA branch that
  has run no backend tests this session.
- **What was done instead of patching the defect:** `frontend/e2e/deposit-fulfilment-close.spec.js`
  was adjusted, not the production code. The spec no longer asserts the unreachable invoice-attach →
  CONFIRM_CLOSE → VERIFY_CLOSE tail; it stops (via `test.fixme(true, '...')`) right after account's
  real, working FINAL_PAYMENT confirmation, with a comment block naming this exact contradiction and
  pointing at `TicketIaAuthzMatrixIntegrationTest.attachments_accountIsNeitherParticipantNorManagerAndIsRefused`
  so the next reader cannot mistake the gap for a flaky or obsolete test. This preserves real coverage
  for everything that does work (deal creation through the full PCR chain, quotation issue/accept,
  order confirmation, deposit notice issue + confirm, the full IR→shipping→goods-received→partial→
  complete fulfilment chain, and FINAL_PAYMENT confirmation) while reporting the untested tail as
  **skipped**, never as passed.
- **A separate follow-up task has been raised** to make the real authorization decision (who should
  attach the closing tax invoice) and implement it as its own branch with a real-DB integration test,
  then restore full coverage in this spec. See "Exact Next Prompt" below.
- `docs/agent-handoffs/backups/` is untracked and was present before this session started (not
  created by this session, not investigated further — out of scope).
- No integration/backend test ran this session (frontend-only branch) — see Authz Evidence.

## Things Not Finished
- The INVOICE-attachment gap now has an **owner decision recorded** (see "Production Gap" under Known
  Risks): out of scope for this branch, to be fixed on its own branch with a real-DB integration test.
  `deposit-fulfilment-close.spec.js` was adjusted (scoped down + `test.fixme()`) rather than the
  defect being patched — it is intentionally **not** fully green end-to-end; the invoice-gated close
  tail remains untested and reports as skipped. A separate follow-up task has been raised to make the
  authorization decision and restore full coverage.
- No other outstanding items from the fix list — FIX 1 through FIX 6 are all applied and verified per
  the "Test / Build Results" section above.

## Recommended Next Agent
This handoff's remaining item is the follow-up task below (separate branch, backend authz decision +
real-DB integration test). For `refactor/ticket-workspace-ia-phase2` itself: a Claude Opus review to
re-verify this session's changes (including the spec adjustment) against the fix list before merge.

## Exact Next Prompt
```
Separate follow-up task (own branch, NOT refactor/ticket-workspace-ia-phase2): close the production
gap recorded in docs/agent-handoffs/119_refactor-ticket-workspace-ia-phase2.md under Known Risks →
"Production Gap — owner decision recorded". Summary: the three-party close's INVOICE-attachment
precondition has never been reachable in production. The only UI control that produces an
attachType: 'INVOICE' attachment is gated `isAccount` (frontend/src/features/tickets/TicketDetailPage.jsx:1893,
`!TERMINAL.includes(st) && isAccount`), but AttachmentController.requireTicketAccess
(backend/src/main/java/th/co/glr/hr/attachment/AttachmentController.java:37 MANAGER_ROLES={hr,
sales_manager, ceo}, :116-125 requireTicketAccess) refuses `account` outright — pinned by
TicketIaAuthzMatrixIntegrationTest.attachments_accountIsNeitherParticipantNorManagerAndIsRefused
(backend/src/test/java/th/co/glr/hr/ticket/TicketIaAuthzMatrixIntegrationTest.java:481). Get an
explicit decision from the repo owner on the real-world workflow (who should attach the closing tax
invoice — sales owner? ceo? does account need a narrower, backend-supported exception widening
MANAGER_ROLES or an ownership check?) before touching anything — this is a business-logic/authz
question, not a UI bug, per CLAUDE.md. Once decided: implement it as its own branch (backend
authorization change requires a real-DB integration test through the real service, per CLAUDE.md's
"Permission changes must ship evidence" — write it wrong-way-round, mutation-check it), restore full
coverage in frontend/e2e/deposit-fulfilment-close.spec.js (currently scoped down with a `test.fixme()`
immediately before the invoice-attach step — remove that fixme and the file-header "PRODUCTION GAP"
comment once the path genuinely works end to end), and re-run the full frontend test/build/e2e trio
plus the backend integration test. Do not weaken TicketDetailPage.jsx's FIX-2 tab-visibility gate
(canViewDocumentsTab) to route around this — it correctly mirrors
AttachmentController.requireTicketAccess and must stay that way.
```
