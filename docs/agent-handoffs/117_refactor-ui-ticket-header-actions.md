# Agent Handoff

## Task
Phase 1 of the ticket-detail IA rebuild (see
`docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md`): fix the
measured clutter on `/tickets/:id` — 4,324px, 12 panels, 15 buttons for sales at stage 4 of 14,
with the actual next action ("สร้างใบขอราคา") 59% down the page and the same "whose turn"
sentence duplicated across panels. Build a sticky action bar (one primary CTA, hidden when
unavailable), a "not your turn" wrapper around the existing `nextSalesAction`/`nextImportAction`/
`nextAccountAction` resolvers, a single work-state banner, an overflow menu for secondary
actions, a bottom "จัดการดีล" danger zone, and drop the redundant page-level "กลับ" bar (F-14).

## Branch
`refactor/ui-ticket-header-actions`

## Base Commit
`a104244d` (origin/main, PR #339 merge)

## Current Commit
Not committed — worktree has uncommitted changes only, per instructions (do not commit/push/merge
without being asked).

## Agent / Model Used
Claude Sonnet 5 (implementation) + Claude Opus (independent review, `aea4cffbf0b6d1408` — 8
findings, all addressed before this handoff was written).

## Scope

### In Scope
- `frontend/src/features/tickets/`: `TicketDetailPage.jsx`, `DealStagePanel.jsx`,
  `DealStateHeader.jsx`, `DealTrackingPanel.jsx`, plus their test files.
- New: `frontend/src/features/tickets/workState.js` (+ test), `frontend/src/components/common/OverflowMenu.jsx` (+ test).
- `frontend/src/components/common/Icon.jsx`: added `moreHorizontal` icon.

### Out of Scope
- No change to any server-side gate, `can.*` computation, `hasAction()` call, `canSetStage`/
  `canMarkLost`/`allowedTargetStages` logic, or `availableActions` shape — this is presentation-
  only, per the task's hard constraint. No authz change.
- No change to `PricingRequestPanel.jsx`, `DealQuotationPanel.jsx`, `DealDepositPanel.jsx`,
  `DealFulfilmentPanel.jsx` internals (only wrapped in `id`+`scroll-mt` anchor `div`s from the
  outside, for the sticky bar's "jump to control" behaviour).
- Not a tabs restructure (Phase 2).
- Mobile bottom-pinned action bar (the IA doc's stated mobile pattern) was simplified to the same
  `sticky top-0` treatment used at every width, for Phase-1 scope — noted under Known Risks.

## Files Changed
- `frontend/src/features/tickets/workState.js` (new): `resolveWorkState(user, deal,
  pricingRequests)` — wraps the three existing next-action resolvers with an "is this deal's
  current stage gated to my role" check (reusing `stageMeta.js`'s `gate` field), so the sticky
  bar can say "not your turn" instead of always surfacing a nudge.
- `frontend/src/features/tickets/workState.test.js` (new): 11 tests covering the gate mapping,
  ceo/sales_manager passthrough, non-ACTIVE lifecycle, and each role's resolver dispatch.
- `frontend/src/components/common/OverflowMenu.jsx` (new): generic "⋯" menu —
  `role="menu"`/`menuitem`, ArrowUp/ArrowDown (wrapping) + Home/End + Escape (returns focus to
  the trigger) + click-outside-closes.
- `frontend/src/components/common/OverflowMenu.test.jsx` (new): 9 tests.
- `frontend/src/components/common/Icon.jsx`: added `moreHorizontal` (lucide `Ellipsis`).
- `frontend/src/features/tickets/DealStagePanel.jsx`: converted to `forwardRef` +
  `useImperativeHandle` exposing `openEditStage`/`openMarkLost`/`openHold`/`openDormant` (each
  defensively re-checks its own real gate before acting). Removed the inline
  แก้ไขสถานะ…/เสียงาน/พักดีลไว้/พัก dormant buttons and the `guidance` prop/line (the duplicate
  "ถึงคิวคุณ" text). Added `advanceReady` prop: disables "เลื่อนไป" + shows
  `STAGE_ADVANCE_GATE_HINT` beside it when the stage-advance readiness gate isn't met (moved from
  DealTrackingPanel, which lived in a different panel from the button it blocked).
- `frontend/src/features/tickets/DealStagePanel.test.jsx`: +5 tests for the ref's defensive gate
  re-checks (no-op vs. opens, for all four openers).
- `frontend/src/features/tickets/DealTrackingPanel.jsx`: removed the standalone
  `STAGE_ADVANCE_GATE_HINT` callout (kept the compact "พร้อมเลื่อนสถานะ/ยังไม่พร้อม" badge).
- `frontend/src/features/tickets/DealStateHeader.jsx`: now `sticky top-0 z-10`; renders one
  `bannerText` line + `primaryAction` + `OverflowMenu` instead of the old
  `nextAction`/`waitingHint`/inline-prefixed-text.
- `frontend/src/features/tickets/TicketDetailPage.jsx` (largest diff): dropped the page-level
  "กลับ" button (Breadcrumbs stays); computes `workState` via `resolveWorkState` and falls back to
  it (as an in-page scroll-to or real route navigation) only when the existing authoritative
  `primaryAction` (confirmCustomer/finalPayment/close/verify) is absent; composes the one banner
  line; recomputes `canEditStage`/`canLostDeal`/`canHoldDeal`/`canDormantDeal` (mirroring
  DealStagePanel's own gates **including** the "only while lifecycle is plain ACTIVE" condition
  that used to be implicit in which JSX branch rendered them) to decide the header overflow
  menu's items; adds the "จัดการดีล" danger zone (เสียงาน + ยกเลิก) at the very bottom of the
  page; narrows `hasActions`/"การดำเนินการอื่น ๆ" (revise/cancel moved out); the revise form is
  now its own block (trigger moved to the overflow menu) with a `useEffect` that scrolls/focuses
  it on open; `IN_PAGE_JUMP_TARGET` maps resolver action keys to `id` anchors added to
  PricingRequestPanel/DealQuotationPanel/DealDepositPanel/DealFulfilmentPanel/DealTrackingPanel's
  wrapper `div`s, each `scroll-mt-[300px] max-[720px]:scroll-mt-[420px]` (verified empirically
  against the actual sticky-header height at 1440/390 — see Test Results).
- `frontend/src/features/tickets/TicketDetailPage.test.jsx`: updated 2 existing tests (gate hint
  moved), reworked 1 (revise now opens via the overflow menu), added 2 new describe blocks
  (advance-readiness-next-to-button, ON_HOLD duplicate-dormant regression).

## Commands Run
```bash
cd frontend
npm run lint
npx vitest run
npm run build
# Manual (non-e2e-suite) Playwright verification against a standalone dev server on :5299
# (VITE_USE_MOCKS=true), NOT the shared :5210 — see Known Risks.
node <scratchpad>/ticket-ia-check.mjs
# axe-core wcag2aa scan (color-contrast rule)
```

## Test / Build Results
- Lint: **pass** — 0 errors, 1 pre-existing warning (`PayrollPage.jsx:336`, unrelated to this
  branch, explicitly listed as expected-and-do-not-fix in the task).
- Tests: **pass** — 72 files, **743 tests** at the time this session ended, 0 failures (was 717 on
  `origin/main`; +26 from the two new test files + the additions above). Correction (P3, review
  round 2): this number was left stale after the two follow-up sessions below added more tests —
  the tree's actual current count is reported in each of those sections; do not read this line as
  the branch's current total.
- Build: **pass**.
- Mutation-check on the ON_HOLD duplicate-dormant fix: reverting the `isActiveLifecycle` guard in
  `TicketDetailPage.jsx` turns exactly the new regression test red (`header overflow menu /
  danger zone (ON_HOLD lifecycle regression) > does not duplicate "พัก dormant"...`) and nothing
  else — confirmed the test is real evidence, not a vacuous pass.
- `scroll-mt` fix verified empirically (not just by class name): measured the sticky header at
  275px (desktop, 1440) / 398px (mobile, 390), then measured a real in-page jump
  (`สร้างใบขอราคา` → `#pricing-request-panel`) landing at ~300px on desktop (header bottom
  ~303px, effectively flush) and ~421px on mobile (header bottom ~414px, clears with margin).
- axe-core (`wcag2aa`, `color-contrast` rule) on `/tickets/1` as sales at 1440/820/390, including
  with the overflow menu open: **0 new violations**. 2 pre-existing violations
  (`.nav-group-header-helper` in the sidebar nav, desktop only) confirmed present on
  `origin/main` too (`Sidebar.jsx`/layout untouched by this diff — `git diff a104244d --
  frontend/src/components/layout/` is empty).

## Authz Evidence
No authorization change in this task. Verified two ways:
1. Every `canEditStage`/`canLostDeal`/`canHoldDeal`/`canDormantDeal` expression added to
   `TicketDetailPage.jsx` is character-identical to the corresponding expression already in
   `DealStagePanel.jsx` (same `hasAction`/`canSetStage`/`canMarkLost`/`allowedTargetStages`
   calls on the same data) — confirmed again in review, including the one place they'd drifted
   (the implicit "ACTIVE lifecycle only" branch condition — fixed, see below).
2. `DealStagePanel`'s ref-exposed `openEditStage`/`openMarkLost`/`openHold`/`openDormant` each
   re-check their own gate before acting (`if (canEditStage) setEditOpen(true)`, etc.) — a stale
   or over-eager caller cannot force an action past the real gate; this is defensive, not the
   authority (that stays `GET /{id}/actions`, unchanged).
This is presentation-only (same convention as `salesViewScope.js`/`accountActions.js`/
`importActions.js` — see `workState.js`'s own doc comment). `VITE_USE_MOCKS=true` was the only
verification surface used; per CLAUDE.md this is correctly reported as N/A for authz (there is no
authz change to verify).

## Decisions Made
- **Sticky bar priority**: the existing, server-gated `primaryAction`
  (confirmCustomer/finalPayment/close/verify) always wins over the new resolver-based action when
  both could apply — it's a real button with a real mutation, the resolver's is a presentation-only
  fallback ("jump to the real control further down the page").
- **"Jump to control" over duplicating the mutation**: rather than reimplement CREATE_PCR/ISSUE_
  QUOTATION/etc. inline in the sticky bar (which would risk drifting from the real gates those
  panels already enforce), the sticky primary either navigates to a different route (import's
  pickup queue, account's commission page) or smooth-scrolls + focuses the existing control
  further down this same page. This means the measured "15 → 3 buttons" target in the task is
  **not** exactly hit — see Test Results for actual counts; the honest accounting is in the
  report given to the user for this session.
- **CEO and sales_manager get no `workState` resolver** (only sales/import/account do, per the
  task's explicit resolver list) — they fall back to the existing `primaryAction` only, or no
  sticky primary/banner at all if that's also empty. Not a bug: their own two-signature-close
  button is unaffected, and no false info is ever shown.
- **`isActiveLifecycle` gate added** to the header's overflow-menu-visibility recomputation
  (`TicketDetailPage.jsx`) that DealStagePanel's original buttons got "for free" from which JSX
  branch rendered them (only the plain-ACTIVE branch, never ON_HOLD/DORMANT/lost) — an Opus
  review catch; without it, an ON_HOLD deal's "พัก dormant" (which the backend legitimately
  advertises for ON_HOLD→DORMANT) would duplicate DealStagePanel's own dedicated button for that
  exact transition. Confirmed live against the real seed data: ticket 15 is genuinely ON_HOLD and
  reproduced the bug pre-fix.

## Assumptions
- The IA doc's mobile "sticky bottom action bar" is read as directional, not literal for Phase 1;
  implemented as `sticky top-0` uniformly. Flagged under Known Risks for Phase 2/a follow-up to
  revisit if the owner wants a true bottom-pinned mobile bar.
- `scroll-mt-[300px] max-[720px]:scroll-mt-[420px]` is a measured, not theoretical, value (see
  Test Results) but will drift if the sticky header's content grows (e.g. a future region added
  to `DealStateHeader`). No test pins this to a specific pixel value.

## Known Risks
- **Console 502 at login** (`/api/auth/login`), reproduced identically for all 4 roles: this is
  `vite.config.js`'s dev-server `/api` proxy to `http://localhost:8080` (no real backend running)
  firing before/alongside the mock intercepts the app-level call. Confirmed pre-existing and
  unrelated to this diff — reproduces on a bare `page.goto('/')` + quick-login with **zero**
  ticket-detail code involved, and no file this branch touches is in the auth/bootstrap path.
  Not investigated further (out of scope); noting it here so it isn't mistaken for a regression.
- **Mobile bottom-bar deviation** — see Assumptions above.
- **`workState.js` deliberately does not consider deal ownership**, only the current stage's
  role-gate (per the task's explicit instruction to derive "whose turn" from `stageMeta`'s
  existing gate data, nothing more). A `sales` viewer looking at a peer's deal (not their own)
  would still see "ถึงคิวคุณ" if the stage gate matches — this is a **label only**, no permission
  implication (the real buttons underneath still gate on ownership independently, unchanged) — an
  Opus review side-note, not a filed finding. Worth a quick look if peer-deal visibility for
  sales turns out to be reachable in practice; not verified either way in this session.
- This entire session ran in an isolated `.claude/worktrees/ui-ticket-header-actions` worktree
  with its own standalone dev server on port 5299, **not** the shared port-5210 server named in
  the task — the main checkout (`/Users/ploy_warit/Desktop/GL-R-ERP`) had ~256 uncommitted files
  from a concurrent session mid-flight (unrelated doc cleanup) when this task started, so
  switching branches there risked disrupting that session. See the final chat report for the
  full reasoning.

## Things Not Finished
- Full mobile bottom-pinned sticky action bar (see Known Risks).
- ~~The "15 → 3 visible buttons" target was not hit exactly~~ — superseded, see the follow-up
  section below: the real, measured number for sales on `/tickets/1` is **11** after the follow-up
  fixes (was 13 as measured by the owner, 15 as measured by the original Phase-1 session on a
  different ticket/state). Still short of "3" — Phase 2's tabs are what remove the rest.
- e2e suite (`frontend/e2e/`) was **not run** (no dev server matching that suite's expected setup
  in this worktree) — statically confirmed no spec references any button/testid moved by this
  branch (`grep` for the Thai labels, `deal-stage-advance`, and "กลับ" scoping — all clear).

## Follow-up: clutter fixes (separate session, same branch/worktree)

The owner measured the delivered Phase-1 result directly (`/tickets/1`, sales, port 5211) and found
**13 visible controls**, with two specific misses the original session's own honest accounting had
NOT surfaced this way: "สร้างใบขอราคา" rendered **twice** (sticky bar's CREATE_PCR "jump" button +
PricingRequestPanel's own create button — both visible at once since the sticky bar never scrolls
away), and the pipeline panel's own filled-indigo "เลื่อนไป: เจ้าของอนุมัติสเปค" button competed with
the sticky bar's resolver-derived primary for "the one thing to click."

**FIX 1** (`frontend/src/features/pricingRequests/PricingRequestPanel.jsx`): converted to
`forwardRef` exposing `openCreate()` (defensive re-check on `canCreate`, same convention as
`DealStagePanel`'s `openEditStage`/etc.). Removed the panel's own "สร้างใบขอราคา" button entirely —
`TicketDetailPage.jsx`'s sticky CREATE_PCR action now calls `pricingRequestPanelRef.current
?.openCreate()` directly instead of `scrollToSection`, so the sticky bar is the ONE
"สร้างใบขอราคา" on the page and actually performs the action (not just a scroll toward it). The
empty state still explains the section's purpose (`ใบขอราคาส่งรายละเอียดสินค้าให้ฝ่ายนำเข้าเสนอราคา
— สร้างได้จากปุ่ม "สร้างใบขอราคา" บนแถบด้านบนของหน้า` for the owning rep, unchanged plainer text for
everyone else) without a duplicate CTA of its own.

**FIX 2** (`DealStagePanel.jsx` + `TicketDetailPage.jsx` + `OverflowMenu.jsx`): removed the inline
"เลื่อนไป: {next stage}" button (and its always-visible gate-hint paragraph) from
`DealStagePanel.jsx`; exposed `openAdvance()` on its forwardRef (re-checks `canAdvance` AND
`advanceReady` before calling `onUpdateStage` — the same two conditions the old `disabled`
attribute enforced). `TicketDetailPage.jsx` mirrors `canAdvance` at the page level (next
stage/ADVANCE_STAGE/canSetStage — checked directly against `availableActions`, NOT this file's own
single-arg `hasAction`, which silently drops the `targetStage` match DealStagePanel's version
requires — caught and fixed before shipping) with the same explicit `isActiveLifecycle` guard the
other overflow gates already needed, and adds an overflow item (`testId: 'deal-stage-advance'`)
that is shown only when `canAdvance`, and `disabled: !readyToAdvance` with
`disabledReason: STAGE_ADVANCE_GATE_HINT` when the activity/follow-up precondition isn't met.
`OverflowMenu.jsx` gained `disabled`/`disabledReason`/`testId` item fields: a disabled item uses
`aria-disabled` (not the native attribute, so it stays in the roving keyboard-focus set), renders
its reason inline, and its click handler no-ops instead of calling `onSelect`. The pipeline panel's
compact "ถัดไป: {next stage}" line (previously suppressed whenever `canAdvance` was true, to avoid
duplicating the button that stood right below it) is now unconditional — the button is gone, so
this line is the only next-stage context left in that panel.

**FIX 3 decisions** — both stay inline, no change:
- `ดูขั้นตอนทั้งหมด (14 ขั้น)`: a disclosure toggle for the SAME panel's own stepper content, not a
  navigation/mutation action. Moving it to a global overflow would separate the toggle from what it
  discloses (open menu → click → close menu → look back at the panel).
- `แก้ไขข้อมูลติดตาม`: already a quiet `secondary-button` that does not visually compete with the
  sticky primary the way the old `เลื่อนไป` did; it is also the direct route to satisfying
  `เลื่อนไป`'s own precondition (sets `nextFollowUpAt`), sits right next to `DealTrackingPanel`'s own
  ready/not-ready badge, and is used more often than the rare administrative actions that ARE in the
  overflow (`แก้ไขสถานะ…`/`พักดีลไว้`/`พัก dormant`/`ขอแก้ไข`). Context locality + non-competing
  styling won out over raw count-reduction.

**Measured before/after (sales, `/tickets/1`, real dev server on :5211, VITE_USE_MOCKS=true)**:
before = 13 (owner's count); after = **11** — `ดีล, รีเฟรช, สร้างใบขอราคา, การดำเนินการเพิ่มเติม,
ดูขั้นตอนทั้งหมด (14 ขั้น), แก้ไขข้อมูลติดตาม, บันทึกกิจกรรม, ไปที่ใบแจ้งยอดเงินรับมัดจำ →,
ส่งความคิดเห็น, เสียงาน, ยกเลิก` — exactly the 13 minus the duplicate "สร้างใบขอราคา" and minus
"เลื่อนไป" (both moved/removed, nothing else changed). Overflow now carries 5 items for sales
(`เลื่อนไป: เจ้าของอนุมัติสเปค` [disabled here — gate unmet], `แก้ไขสถานะ…`, `พักดีลไว้`,
`พัก dormant`, `ขอแก้ไข (Revise)`); ceo gets 4 (no "ขอแก้ไข", not the deal owner); import/account
each get 1 (`แก้ไขสถานะ…` only — `เลื่อนไป` correctly ABSENT for both, since `canSetStage` gates that
stage to `sales`/`sales_manager`/ceo — proves the gate was not widened to unauthorized roles). No
horizontal overflow at 1440/820/390 for any of the 4 roles. Only console/network error observed:
`502 http://localhost:5211/api/auth/login` — reproduced on a bare login with zero ticket-detail code
involved, confirmed pre-existing per the original Phase-1 handoff's own "Known Risks" note, not a
regression.

**End-to-end proof "เลื่อนไป" still fully works once ready** (not just gated): satisfied both
preconditions live in the browser (set a follow-up date via "แก้ไขข้อมูลติดตาม", logged an activity
via "บันทึกกิจกรรม") → `DealTrackingPanel`'s badge flipped to "พร้อมเลื่อนสถานะ" → reopened the
overflow → the item's `aria-disabled` was gone and its label was the plain
`เลื่อนไป: เจ้าของอนุมัติสเปค` (no hint text) → clicked it → the real `api.tickets.updateStage`
mutation fired (`อัปเดตสถานะดีลแล้ว` toast appeared) → the header's ขั้นตอนดีล stat chip changed from
`เสนอราคาผู้ออกแบบ/เจ้าของ` to `เจ้าของอนุมัติสเปค`. Nothing became unreachable; the gate travelled
with the action.

**Bonus finding**: `e2e/pcr-chain.spec.js:108` and `e2e/deposit-fulfilment-close.spec.js:69` both do
`page.getByRole('button', { name: 'สร้างใบขอราคา' }).click()` with no `.first()` — on a freshly
created deal (no PR yet, stage gated to sales) BOTH the old duplicate buttons would have been on
screen simultaneously, which is a Playwright strict-mode violation. These specs were not run before
(or now — still no dev server matching their setup in this worktree), so this was never caught, but
FIX 1 incidentally fixes it: there is only one such button now.

**Commands run this session** (from `frontend/`): `npm run lint` (0 errors, 1 pre-existing
`PayrollPage.jsx` warning), `npx vitest run` (72 files / 750 tests, all green — was 743 before this
session's +7 net new tests), `npm run build` (pass). Browser verification: two standalone Playwright
scripts (`playwright-core` from the MAIN checkout's `frontend/node_modules`, per the task's
pointer) against the already-running worktree dev server on `:5211` — a control-count/overflow-
content/width sweep across sales/ceo/import/account, and a behavioral script driving the actual
FIX 1/FIX 2 interactions (button counts before/after modal open, keyboard Home-to-first-item, click-
while-disabled no-op, and the full ready-and-advance flow above). Not committed anywhere as test
files (scratchpad only, per the no-report-files convention) — the assertions that matter were
ported into `PricingRequestPanel.test.jsx`, `DealStagePanel.test.jsx`, and `TicketDetailPage.test.jsx`
instead.

**Authz**: no authorization change. `canAdvance`'s gate (`ADVANCE_STAGE`/`canSetStage`) is
byte-identical to `DealStagePanel`'s pre-existing one, just evaluated in a second place (mirrored,
same pattern as `canEditStage`/`canHoldDeal`/`canDormantDeal` already were) and re-checked again
defensively inside `openAdvance()` before it acts. `VITE_USE_MOCKS=true` only — per CLAUDE.md this
is unverified against the real Java service, but there is no authz change to verify here (same
`GET /{id}/actions` decision, same `stageMeta.js` gate table, presentation-only reshuffle of WHERE
each already-gated action renders).

## Follow-up: review round 2 — three P2s + P3s (separate session, same branch/worktree)

An independent review found three more P2s in the delivered clutter-follow-up, all now fixed. The
owner decided all three up front — this session implemented rather than re-litigated.

**FIX 1** (`frontend/src/features/tickets/workState.js`): `isViewersStage` gated on the deal's
CURRENT stage BEFORE ever calling the matching role's resolver. That is wrong for every auto stage
(`SALES_STAGES`' `auto: true` entries) — an auto stage's `gate` names the role whose action CAUSED
entry into it, not the role whose action is pending NOW, so the role about to act is always looking
at the stage BEFORE the one their own action would produce. Verified against the backend two ways:
`TicketService.java:1029` only advances to `DEPOSIT_RECEIVED` once account confirms the deposit, so
while `paymentStatus === 'DEPOSIT_NOTICE_ISSUED'` the deal still sits at `ORDER_RECEIVED` (gate:
`sales`) — account saw "รอฝ่ายขาย" and no primary, when `nextAccountAction` correctly had
`confirmDeposit` ready; `TicketService.java:702` only advances to `PROCUREMENT` once import issues
the IR, so while `fulfillmentStatus == null` the deal still sits at `DEPOSIT_RECEIVED` (gate:
`account`) — import saw "รอฝ่ายบัญชี" and no primary, when `nextImportAction` correctly had
`issueImportRequest` ready. Fix: `resolveWorkState` now calls the matching resolver FIRST,
unconditionally, and only falls back to reading the current stage's gate (for the "รอ<role>" banner)
when the resolver comes back empty. `ceo`/`sales_manager` still never get that fallback banner
(neither has a worklist resolver at all) — verified live this session (sales_manager on ticket 1
shows an empty banner, not a stray "รอ...").

`workState.test.js`: rewrote the two tests the reviewer flagged by line number (`:50` "import viewer
on a sales-gated stage", `:67` "account viewer on a sales-gated stage") — same assertions, updated
comments to describe the new resolver-first mechanism rather than the old stage-gate-first one. Also
rewrote the "sales viewer on an import-gated stage" test (not explicitly flagged, but its own
`pricingRequests: []` setup meant nextSalesAction's bucket-1 CREATE_PCR would now fire — a false
positive purely from unrealistic test data, not the fix — so the setup was corrected to a realistic
live PR at `IMPORT_REVIEWING` with `stale: false`, which genuinely has nothing pending, preserving
the test's original "not their turn" intent under the new contract). Added 3 new cases: the two
backend-verified scenarios above (each asserting the real action now wins over the banner), plus
account's `chaseOverdue` on a sales-gated stage (the bug report's third example — an overdue balance
is account's own pending action regardless of current stage). 11 → 14 tests, all passing.

**FIX 2** (`frontend/src/features/tickets/DealQuotationPanel.jsx` +
`frontend/src/features/tickets/TicketDetailPage.jsx`): `ออกใบเสนอราคา` and `ยืนยันคำสั่งซื้อ` still
rendered twice each — the sticky bar's own scroll-to-`DealQuotationPanel` copy (labels defined in
`salesActions.js:32`/`:33`) plus the panel's own buttons (`DealQuotationPanel.jsx:194`/`:244` in the
pre-fix file), both visible at once since the sticky bar never scrolls away. Same ref-opener
treatment as `PricingRequestPanel`'s `openCreate` / `DealStagePanel`'s `openAdvance`:
`DealQuotationPanel` is now `forwardRef` + `useImperativeHandle`, exposing `openIssueQuotation()`
(re-checks `current && isCustomerQuotationEditable(current) && canManageCustomerQuotation(user, pr)`)
and `openConfirmOrder()` (re-checks `pr && canConfirmOrder(user, pr)`) — both null-safe against a
stale/absent `pr`/`current`. The panel's own buttons are removed; the sections that used to hold them
now render an explanatory sentence pointing at the sticky bar instead (mirrors `PricingRequestPanel`'s
empty-state convention). `TicketDetailPage.jsx` gained a `dealQuotationPanelRef` and two new branches
in the sticky-primary composition (`actionKey === 'issue_quotation'` / `'confirm_order'`) that call
the ref openers directly instead of scrolling; `issue_quotation`/`confirm_order` were removed from
`IN_PAGE_JUMP_TARGET` since they're no longer scroll targets.

New tests in `TicketDetailPage.test.jsx` (describe block "sticky header primary CTA —
ISSUE_QUOTATION/CONFIRM_ORDER own their labels alone"): for each action, seed a PR at the matching
status (`APPROVED_FOR_QUOTATION` / `QUOTATION_ACCEPTED`), assert exactly one button with that label
exists both before AND after clicking it, and assert the click actually calls the real mutation
(`api.pricingRequests.issueCustomerQuotation` / `confirmOrder`) with the right id — proving the ref
opener performs the action, not just opens something.

**FIX 3** (`frontend/src/features/tickets/DealStagePanel.jsx` +
`frontend/src/features/tickets/TicketDetailPage.jsx`): the old inline "เลื่อนไป" button (and
แก้ไขสถานะ…/พักดีลไว้/พัก dormant) were each `disabled={actionLoading}` — a mutation already in
flight blocked a second click. `DealStagePanel`'s `openAdvance`/`openEditStage`/`openHold`/
`openDormant` re-check `canAdvance`/`canEditStage`/`canHold`/`canDormant` but never `actionLoading`,
so reopening "⋯" mid-mutation and clicking again fired a second request — the second `updateStage`
landing as a 409 "Deal is already in stage X" (`TicketService.java:1143`). `openMarkLost` was
deliberately left unchanged: เสียงาน lives in the bottom "จัดการดีล" danger zone as a real `<button>`
with a native `disabled={actionLoading}` attribute, which already blocks the click at the DOM level —
no ref-level re-check was ever missing there. Fixed: `!actionLoading` added to all four remaining
openers' re-checks, and to the corresponding `overflowItems` entries' own `disabled` (advanceStage:
`!readyToAdvance || actionLoading`; editStage/hold/dormant: `actionLoading`) so the item visibly
reads as unavailable, not just silently no-ops.

New tests: `DealStagePanel.test.jsx` gained 4 cases (`openAdvance`/`openEditStage`/`openHold`/
`openDormant`, each proving a no-op while `actionLoading: true` even though every other gate
passes — `openAdvance`'s uses a fresh local `vi.fn()` rather than the file's shared
`noopHandlers.onUpdateStage`, since that mock already has a call recorded from an earlier test in
the same file and this file never resets mocks between tests). `TicketDetailPage.test.jsx` gained an
end-to-end case: click เลื่อนไป (via a `updateStage` mock returning a never-resolving promise to hold
`actionLoading` true), reopen the menu while it's still pending, assert the item shows
`aria-disabled="true"`, click it again, and assert `updateStage` was still only called once total.

**P3s** (also fixed, reviewer-flagged, cheap):
- `TicketDetailPage.jsx`'s `blocker` line (`รอชำระมัดจำ`/`รอชำระส่วนที่เหลือ`) lost its `!isAccount`
  guard — account is the role whose OWN action clears both waits, so seeing them as a blocker
  doubled up with FIX 1's new resolver-derived primary. Restored `&& !isAccount` on both branches.
  New tests use a legacy (`status: 'document_issued'`) ticket so `nextAccountAction` itself resolves
  to `null` (isolating the blocker guard from FIX 1's resolver-first primary, which would otherwise
  mask the same bug by giving account a real primary instead) — sales_manager sees the blocker,
  account does not. (Uses sales_manager, not ceo, as the "someone who isn't account" control: `ROLE_
  PERMISSIONS.canConfirmPayments` in `src/api/routes.js` is `['account', 'ceo']`, so `isAccount` is
  ALSO true for ceo — using ceo would have exercised the same branch as the account case.)
- `OverflowMenu.jsx`: items gained `tabIndex={-1}` (out of the normal Tab order — reachable only via
  Arrow/Home/End, same as before; `.focus()` still works on a tabIndex=-1 native `<button>`), and a
  `Tab`/`Shift+Tab` keydown handler that closes the menu so Tab naturally continues into whatever
  follows the component in the DOM instead of leaving an open, still-interactive popover behind.
  Also stopped applying `hover:bg-surface-hover`/`focus-visible:bg-surface-hover` to disabled items
  at all (previously fought a same-specificity `hover:bg-transparent` override that wasn't
  guaranteed to win) — simpler and correct: enabled/disabled items now get mutually exclusive class
  sets instead of one overriding the other. New tests: roving-tabindex + Tab-closes-menu,
  Shift+Tab-closes-menu, and a disabled item no-opping on click (the keyboard-activation stand-in —
  jsdom doesn't synthesize the browser's native Enter/Space→click translation for buttons, so a
  direct `fireEvent.click` on the focused disabled item is the correct way to exercise it under
  `fireEvent`, per testing-library convention).
- Corrected the stale "743 tests" figure in this handoff's main Test/Build Results section (see the
  note added there) — it was accurate when written, went stale after the two follow-up sessions.

**Measured control counts, sales/ceo/import/account on `/tickets/1`, live dev server on :5211,
VITE_USE_MOCKS=true (before → after this session; "before" = the FIX1/FIX2/FIX3 review round 2
starting point, i.e. the prior "Follow-up: clutter fixes" section's own "after" numbers)**:
- **sales**: 11 → 11 (unchanged — none of the three fixes touch sales' own control count on this
  specific ticket, since ticket 1 is sales-gated and sales already had its real action). Overflow:
  5 items (`เลื่อนไป: เจ้าของอนุมัติสเปค` [disabled — gate unmet], `แก้ไขสถานะ…`, `พักดีลไว้`,
  `พัก dormant`, `ขอแก้ไข (Revise)`) — unchanged.
- **ceo**: 13 (unchanged; ceo has no `workState` resolver, so FIX 1 cannot add or remove a sticky
  primary for ceo). Overflow: 4 items (`เลื่อนไป: เจ้าของอนุมัติสเปค` [disabled], `แก้ไขสถานะ…`,
  `พักดีลไว้`, `พัก dormant`) — unchanged.
- **import**: 6 controls (`ดีล, รีเฟรช, การดำเนินการเพิ่มเติม, ดูขั้นตอนทั้งหมด (14 ขั้น),
  จองสินค้าจากสต็อก, ส่งความคิดเห็น`), banner reads `รอฝ่ายขาย` — **unchanged from before FIX 1**, and
  correctly so: ticket 1 is genuinely nothing-pending for import (no unpicked PR, fulfilment hasn't
  started), so FIX 1's resolver-first order still correctly falls through to the stage-gate banner
  here. This ticket does NOT reproduce the FIX 1 bug — see "Not independently verified live" below.
  Overflow: 1 item (`แก้ไขสถานะ…`) — proves the gate was never widened to import (`เลื่อนไป` correctly
  absent, `canSetStage` still gates that stage to sales/sales_manager/ceo only).
- **account**: 9 controls (`ดีล, รีเฟรช, การดำเนินการเพิ่มเติม, ดูขั้นตอนทั้งหมด (14 ขั้น),
  บันทึกรับชำระเงิน, ตั้งค่าการวางบิล, เปลี่ยนนโยบายมัดจำ…, ไปที่ใบแจ้งยอดเงินรับมัดจำ →,
  ส่งความคิดเห็น`), banner reads `รอฝ่ายขาย` — same reasoning as import: genuinely nothing pending on
  THIS ticket, correct fallback, not a reproduction of the bug. Overflow: 1 item (`แก้ไขสถานะ…`).
- **sales_manager** (bonus, not in the requested list): banner is empty (no `รอ...` text at all) —
  live confirmation that the `ceo`/`sales_manager` exemption in the FIX 1 rewrite holds in the
  browser, not just in the unit tests.

No horizontal overflow at 1440/820/390 for sales or account (`document.documentElement.scrollWidth
=== window.innerWidth` at all three widths, checked live, still authenticated at each width —
verified this specific point twice after an earlier viewport-resize attempt incidentally logged the
session out and would have silently measured the LOGIN page's width instead of the ticket page's).
No console errors, no failed network requests observed this session (the previously-noted 502-at-login
issue did not reproduce). Confirmed via `read_network_requests` that the dev server actually served
the edited files with fresh HMR timestamps (`workState.js?t=…`, `DealQuotationPanel.jsx?t=…`,
`DealStagePanel.jsx?t=…`, `OverflowMenu.jsx?t=…`) — the live checks above exercised this session's
actual code, not a stale bundle.

**Not independently verified live** (honestly incomplete, per the task's own instruction to say so
rather than claim it works): the two FIX 1 backend-verified scenarios (account waiting-with-a-real-
primary at `ORDER_RECEIVED` + `paymentStatus: DEPOSIT_NOTICE_ISSUED`; import waiting-with-a-real-
primary at `DEPOSIT_RECEIVED` + `fulfillmentStatus: null`) and FIX 2's exact-once-rendering
(`ออกใบเสนอราคา`/`ยืนยันคำสั่งซื้อ` on a deal with a PR at `APPROVED_FOR_QUOTATION`/
`QUOTATION_ACCEPTED`) were **not** reproduced in the live browser. Checked all 15 seed tickets
directly via the mock module (`api.tickets.list`, `api.pricingRequests.listForTicket` for every
ticket id 1–15) — none currently sits in either exact state (no ticket has `paymentStatus ===
'DEPOSIT_NOTICE_ISSUED'` at all; no PR anywhere in the seed reaches `APPROVED_FOR_QUOTATION` or
later). Attempted to construct one live by walking ticket 1 through the real PCR-creation flow as
sales, but the create-PCR modal's catalog-linkage validation (`ต้องเลือกสินค้าจาก Price Catalog ที่
active ก่อนส่งคำขอราคา`) blocked submission before reaching a state useful for this check, and
completing that chain (PCR → import pickup/quote → CEO price approval → customer quotation →
accept → confirm order → deposit notice, switching roles at each step) was not finished in this
session. These two specific scenarios are instead proven by `workState.test.js`'s 3 new cases (which
cite the exact `TicketService.java` line numbers this session verified them against) and
`TicketDetailPage.test.jsx`'s 2 new FIX 2 cases (which seed the exact PR status combinations and
assert both the single-render count and the real mutation firing) — solid evidence, but unit/
component-level, not a live end-to-end browser walk. Flagging this plainly rather than claiming the
live check covered it.

**Commands run this session** (from `frontend/`): `npx vitest run src/features/tickets/workState.test.js`
(14/14, iteratively while writing FIX 1), `npx vitest run src/components/common/OverflowMenu.test.jsx
src/features/tickets/DealStagePanel.test.jsx` (27/27), `npx vitest run src/features/tickets/TicketDetailPage.test.jsx`
(targeted `-t` runs while writing each new test, then the full file), `npx vitest run` (full suite:
**72 files, 765 tests, 0 failures** — was 750 before this session's +15 net new), `npm run lint` (0
errors, 1 pre-existing `PayrollPage.jsx` warning, unchanged/expected), `npm run build` (pass, no new
warnings). Browser verification via the Claude Browser pane against the already-running worktree dev
server on `:5211` (quick-login per role, `history.pushState`+`popstate` for in-SPA navigation between
roles' tickets, per this worktree's own established convention) — control-count/overflow-content
sweep across sales/ceo/import/account/sales_manager, the sticky-bar FIX 2 click-through (create-PCR
modal opened and cancelled cleanly, no state left behind — confirmed via
`api.pricingRequests.listForTicket(1)` returning only the original seed DRAFT PR afterward), and the
three-width responsive sweep.

**Authz**: no authorization change in any of the three fixes. FIX 1 reorders WHEN the existing
resolvers run (never changes what they're allowed to return) and only changes fallback banner text.
FIX 2's ref openers re-check the exact same `pricingRequestMeta.js` predicates
(`isCustomerQuotationEditable`, `canManageCustomerQuotation`, `canConfirmOrder`) the removed buttons
used — same gates, same source of truth, just invoked from a different call site. FIX 3 adds a purely
additive `!actionLoading` condition (can only make an action MORE restrictive, never less) to guards
that were already present. `VITE_USE_MOCKS=true` only — per CLAUDE.md this is unverified against the
real Java service, but there is no authz change here to verify (same `GET /{id}/actions` decision,
same `pricingRequestMeta.js`/`stageMeta.js` predicates, presentation-only).

## Recommended Next Agent
Owner sign-off on the "not independently verified live" scenarios above (or have the next session
finish the real PCR→quotation→order-confirm→deposit-notice walk-through to close that gap), then
rebase onto latest `origin/main`, run the full backend+frontend gate one more time, and open the PR.
The mobile bottom-pinned bar deviation (Known Risks, above) and the original FIX 3 "leave both
inline" judgment calls (ดูขั้นตอนทั้งหมด/แก้ไขข้อมูลติดตาม, unrelated to this round's FIX 3 despite
the same fix number) are both still open decisions for the owner, not blockers for this PR.

## Exact Next Prompt
```
This branch (refactor/ui-ticket-header-actions) has now had Phase 1 implemented + independently
reviewed, a clutter follow-up (round 1: duplicate "สร้างใบขอราคา" removed; "เลื่อนไป" moved into the
header overflow with its gate intact), AND a second review round fixing three more P2s (FIX 1:
workState.js now asks the role resolver BEFORE checking the current stage's gate, so account/import
see their real pending action instead of a stale "รอ<role>" banner on auto-advanced stages; FIX 2:
"ออกใบเสนอราคา"/"ยืนยันคำสั่งซื้อ" de-duplicated the same way FIX 1 round 1 de-duplicated
"สร้างใบขอราคา"; FIX 3: the header overflow's เลื่อนไป/แก้ไขสถานะ…/พักดีลไว้/พัก dormant items now
respect actionLoading, closing a double-submit-under-409 gap) plus 3 P3s (blocker line's !isAccount
guard restored; OverflowMenu roving-tabindex + Tab-closes-menu + disabled-item focus-highlight fix;
a stale test-count line corrected) — see this handoff's "Follow-up: review round 2" section for the
full diff, the exact TicketService.java line numbers each fix was verified against, and what was
and wasn't confirmed live in the browser (the two FIX 1 backend-verified scenarios and FIX 2's
exact-once-render were proven at the unit/component level with realistic seeded data, NOT live —
the mock seed has no ticket in either exact state, and constructing one live was blocked partway by
the create-PCR modal's catalog-linkage validation). lint/test/build are green (72 files / 765
tests). Next: either close that live-verification gap (finish walking a ticket through the real
PCR→quotation→order-confirm→deposit-notice chain across sales/import/CEO/account role switches), or
get owner sign-off to proceed without it — then rebase onto the latest origin/main, re-run
`npm run lint && npm test && npm run build`, and open the PR.
```
