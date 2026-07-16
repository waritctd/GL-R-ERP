# Agent Handoff

## Task
Phase-2 Branch 4 slice B (user-approved, the big one): migrate **TicketDetailPage.jsx**
server state from imperative `useState`/`useEffect` loaders (26 `useState` hooks, a
`doAction(fn, successMsg)` helper) to TanStack Query, following the house conventions slice A
established (`queryKeys.js`, `LeavePage.jsx`). ~1570-line file.

## Branch
`refactor/tickets-query-slice-b`

## Base Commit
`10515ff` (tip of `origin/refactor/tickets-query-slice-a` at fetch time — includes slice A's
list/dashboard migration AND the Opus-review flattening of `queryKeys.tickets.*` into flat
`ticketList`/`ticketDetail`/`ticketAttachments`).

## Current Commit
See `git log -1` after this agent's commit lands (single commit,
`refactor(tickets): migrate TicketDetailPage to TanStack Query (slice B)`).

## Agent / Model Used
Claude Sonnet (implementation agent, standing Sonnet-implements/Opus-reviews loop).

## Scope

### In Scope
- `frontend/src/features/tickets/TicketDetailPage.jsx` — full `useQuery`/`useMutation`
  conversion of server state; UI-only `useState` (propose/edit/reject/revise/comment drafts)
  left untouched.
- New test: `frontend/src/features/tickets/TicketDetailPage.test.jsx`.

### Out of Scope (not touched)
- `frontend/src/api/queryKeys.js` — no new keys needed; `ticketDetail`/`ticketAttachments`
  already existed from slice A.
- `mockApi.js`, `hrApi.js`, any backend code, routes, auth, DB schema.
- `TicketListPage.jsx` / `TicketDashboard.jsx` (slice A, already merged into this branch's base).
- `DepositNoticePage.jsx`, `CeoSettingsPage.jsx`, `NotificationBell.jsx` (slice C's territory —
  discovered mid-task that a concurrent reviewer session is rebasing `review/query-c`
  (`refactor/tickets-query-slice-c`) in a shared worktree; no file overlap with this branch).
- Download busy-state (`downloadingQuotationKey`, `downloadingInvoice`) and `sendFactoryEmail`/
  `emailSending` — not server-state mutations with anything to invalidate, left as local state
  exactly as instructed.

## Files Changed
- `frontend/src/features/tickets/TicketDetailPage.jsx`:
  - **Queries**: `ticketQuery` (`queryKeys.ticketDetail(ticketId)` →
    `api.tickets.get(ticketId).then(r => r.ticket)`), `attachmentsQuery`
    (`queryKeys.ticketAttachments(ticketId)`), and a new page-independent
    `factoryConfigsQuery` (`['factoryConfigs']`, `staleTime: Infinity`) replacing the old
    lazy-fetch-inside-`initPropose` pattern.
  - **Generic action mutation** (`actionMutation`): a drop-in replacement for the old
    `doAction(fn, successMsg)` helper. `mutationFn: ({ fn }) => fn()`; `onSuccess(response,
    { successMsg })` does the 3 things specified: `setQueryData(ticketDetail, response.ticket)`
    (fast path, no refetch), invalidate `['tickets','list']` + `dashboardSummary()` +
    `notifications()` (the staleness fix), then the same UI-draft reset the old `doAction` did.
    All ~17 action call sites (`approve`/`pickup`/`quotation`/`close`/`cancel`/
    `confirmCustomer`/`confirmDepositPaid`/`issueImportRequest`/`markIrSent`/`markShipping`/
    `markGoodsReceived`/`confirmFinalPayment`/`revision`/`editItems`/`reject`/`comment`/
    `proposePrice`) are unchanged at the call site — they still call `doAction(fn, msg)`, which
    now wraps `actionMutation.mutateAsync` in a try/catch that swallows the rejection (mutateAsync
    always rejects even after `onError` runs; the old imperative version never threw past its own
    try/catch either).
  - **`calculatePricesMutation`** (separate instance, NOT routed through `doAction`): its own
    `onSuccess` does `setQueryData` + the 3 invalidations, then its own side effects
    (`setPriceBreakdown`, `setShowBreakdown(true)`) — it never reset the propose/edit/reject/
    revise drafts before this migration, so it still doesn't. `calcLoading` →
    `calculatePricesMutation.isPending`.
  - **`overrideMutation`** (one shared instance for all items, not a mutation-per-item): per-item
    pending state is derived from `overrideMutation.isPending && overrideMutation.variables?.itemId
    === itemId` instead of the old `overrideLoading` map — preserves the original per-item
    granularity (each row's button/label is independent) without juggling a dynamic set of
    mutation hooks.
  - **`uploadAttachmentMutation`** / **`deleteAttachmentMutation`**: invalidate
    `queryKeys.ticketAttachments(ticketId)` on success instead of manually calling
    `loadAttachments()` / filtering local array state.
  - **`refreshTicket()`**: the manual refresh icon-button now invalidates BOTH
    `ticketDetail` and `ticketAttachments` (previously only reloaded the ticket) — one of the two
    documented staleness fixes.
  - `initPropose()` no longer awaits a lazy factory-config fetch; it reads straight from
    `factoryConfigsQuery`'s cached data (falls back to `{}` if not yet resolved, same as before).
  - Removed `useState`: `ticket`, `loading`, `actionLoading`, `factoryConfigs`, `attachments`,
    `attachLoading`, `calcLoading`, `overrideLoading`, `uploadingFile`, `deletingAttachment`.
  - Kept `useState` exactly as instructed (pure UI): `proposeMode`, `draftRaw`,
    `draftFactoryCurr`, `proposeNote`, `emailDraft`, `emailSending`, `editMode`, `editDraft`,
    `editNote`, `showRejectForm`, `rejectReason`, `showBreakdown`, `overrideDraft`,
    `showReviseForm`, `reviseScope`, `reviseReason`, `commentText`, `confirm`,
    `downloadingQuotationKey`, `downloadingInvoice`, plus `priceBreakdown` (see Decisions Made).
- `frontend/src/features/tickets/TicketDetailPage.test.jsx` (new) — 3 tests:
  1. Renders a ticket from a mocked `api.tickets.get`.
  2. Approve (as `ceo`) calls `api.tickets.approve` and the displayed status updates via
     `setQueryData` — asserts `api.tickets.get` is called exactly once total (proving the
     single-round-trip fast path, no extra ticket refetch after the mutation).
  3. Comment posts (`api.tickets.comment`) and marks seeded `['tickets','list',...]`/
     `dashboardSummary()`/`notifications()` query-cache entries as `isInvalidated`.

## Commands Run
```bash
git fetch origin
git switch -c refactor/tickets-query-slice-b origin/refactor/tickets-query-slice-a
cd frontend && npm ci
npx eslint src/features/tickets/TicketDetailPage.jsx
npx vitest run src/features/tickets/TicketDetailPage.test.jsx
npm run lint
npm test
npm run build
```

## Test / Build Results
- `npm run lint`: **pass** — 0 errors, 5 pre-existing warnings, none in files this branch
  touched (`AttendancePage.jsx`, `CeoSettingsPage.jsx`, `CommissionPage.jsx`, `PayrollPage.jsx` —
  same list as slice A documented, minus `TicketDetailPage.jsx` itself which is now clean).
- `npm test`: **pass** — 22 test files, 101 tests, 0 failures (98 pre-existing + 3 new).
- `npm run build`: **pass** — Vite build completed in ~150ms, no errors. `TicketDetailPage`
  chunk: 62.52 kB / gzip 13.56 kB.
- Backend: not touched, not run (frontend-only change).
- **Live browser verification: partially done, with an important finding.** Same environment
  constraint slice A hit — `preview_start`/browser tools resolve to a shared worktree
  (`.claude/worktrees/charming-gauss-f118ad`), not this implementation worktree. Since that
  worktree was clean and on `review/query-a` (matching this branch's exact base commit
  `10515ff`), I overlaid my two changed files there (not committed, just copied) and drove the
  running `frontend-mock` dev server against them:
  - **Verified live**: logged in as `import`, opened ticket `PR-2026-0002` (`price_proposed`),
    posted a comment — event history updated in place, success toast fired, textarea cleared,
    no full-page reload. Logged in as `ceo`, opened the same ticket, clicked "คำนวณราคา (CIF)"
    — success toast fired, "ซ่อนรายละเอียดสูตร" breakdown toggle appeared, `อัปเดตล่าสุด` date
    updated to the current mock-server date, confirming `calculatePricesMutation`'s
    `setQueryData` + `priceBreakdown`/`showBreakdown` side effects all work end-to-end. The
    item row also gained an "override" button (`showCalcBreakdown` now true), confirming the
    fresh `calcedCost` data flowed through correctly.
  - **Interrupted before finishing**: while scrolling to inspect the CIF breakdown table, the
    page hit a Vite HMR parse error — **a live git conflict had appeared in that shared
    worktree's `frontend/src/api/queryKeys.js`**. `git status` there showed `review/query-c`
    (this repo's slice-C branch) mid interactive-rebase (`rebase -i ... onto 71b9332`), conflicted
    on `queryKeys.js`/`TicketDashboard.jsx`/`TicketListPage.jsx` — **a different agent session was
    actively rebasing in that exact shared worktree while I was borrowing it for browser
    preview.** I stopped immediately, did not touch the conflicted files, and re-checked: by the
    time I looked, the other session had already resolved and moved on (`nothing to commit,
    working tree clean` on `review/query-c`). My overlay copies of `TicketDetailPage.jsx`/
    `TicketDetailPage.test.jsx` were gone (wiped by their rebase checkout, since they were
    uncommitted local edits with no relation to the conflict) — no residue left behind, verified
    via `git status`/`git diff`.
  - Given that worktree is confirmed to be **actively in use by a concurrent session**, I stopped
    trying to use it further rather than risk colliding with real in-progress work — same judgment
    call slice A made when it accidentally found itself in a wrong/shared worktree.
  - **Not manually verified**: `approve`/`reject`/`pickup`/the dual-track post-quotation actions,
    attachment upload/delete, per-item override, and the cross-page live-update (a second
    concurrently-mounted observer, e.g. dashboard open in another tab, refreshing from this
    page's action without a manual reload) — the automated tests cover the approve case's
    `setQueryData` fast-path and the comment case's 3-way invalidation directly, but a real
    click-through of the remaining actions and the multi-tab cache-sharing behavior is still
    outstanding. **Recommend the next agent/Opus do this from a worktree that isn't concurrently
    driving another branch's rebase/review.**

## Decisions Made
1. **`priceBreakdown` stays local `useState`, fed by `calculatePricesMutation`'s `onSuccess`** —
   not `setQueryData` under a synthetic breakdown key. Rationale: there is no GET endpoint for
   it (it only ever exists as a `calculatePrices` mutation response), no other page/component
   reads it, and nothing needs to invalidate it independently of the mutation that produces it.
   `setQueryData` would add a second cache entry with no query ever "owning" it (no `useQuery`
   would read that key), which is more machinery than the data's actual lifecycle needs. This
   was explicitly one of the two options the task offered ("local state is simplest and
   acceptable") — chose it over the cache-key alternative.
2. **`factoryConfigsQuery` is unconditionally enabled** (not gated to only import/ceo roles or
   only when propose mode is entered). Rationale: keyed by `['factoryConfigs']` with no
   `ticketId`, `staleTime: Infinity` — the whole app now shares ONE fetch per session instead of
   one lazy fetch per ticket-detail-page visit (the old behavior re-fetched every time
   `factoryConfigs` state was empty, i.e. on first propose-click per page mount). This is a
   genuine improvement consistent with the migration's spirit, at the cost of one extra network
   call for roles that never enter propose mode (sales/account viewing a ticket). Judged
   worthwhile given the shared-cache payoff; flagging for the reviewer in case a role-gated
   `enabled:` is preferred instead.
3. **`loading` (ticket) and `attachLoading` are `isLoading`-only, not `isLoading || isFetching`**
   — same rationale slice A used for `TicketDashboard` (handoff 62, Decision #1): both gate a
   full skeleton replacement (whole page / whole attachment list), and using `isFetching` too
   would flash that skeleton on every quiet background refetch (e.g. after the manual refresh
   button's invalidate, or after an upload/delete invalidates `ticketAttachments`). Extending an
   already-approved precedent from slice A rather than introducing a new inconsistent gate.
4. **`overrideMutation` is one shared `useMutation` instance for all item rows**, with per-item
   pending state derived from `overrideMutation.variables?.itemId` while `isPending` — not a
   `useMutation` per item (impossible with a static hook call) and not a `useState` map (would
   have reintroduced the exact imperative-state pattern this migration is removing). This
   preserves the original UI's per-item independent spinner/disabled state.
5. **`calculatePricesMutation` does NOT run the generic action mutation's UI-draft reset**
   (`resetActionDrafts`) — matches original behavior exactly (the old `handleCalculatePrices`
   never touched `proposeMode`/`editMode`/etc.), even though routing it through the same reset
   would have been harmless in practice (calculatePrices and propose/edit are used by disjoint
   roles — ceo vs import/sales — so in any single session at most one of those UI states could
   ever be active). Preserved the distinction anyway for behavioral fidelity to the original.

## Assumptions
- `queryKeys.ticketDetail`/`queryKeys.ticketAttachments` (flat, from the Opus-review commit
  `10515ff`) are the correct/final key shape to build on — confirmed by reading that commit's
  diff directly rather than trusting the (now superseded) nested-object shape described in
  handoff 62's prose.
- All ~17 mutation endpoints in `mockApi.js`'s `tickets` namespace return `{ ticket:
  buildTicketDetail(ticket) }` (confirmed by reading the relevant handlers directly) — this is
  what makes the `setQueryData(ticketDetail, response.ticket)` single-round-trip fast path valid
  for every one of them, not just `approve`.
- No other currently-mounted component reads `['factoryConfigs']` under a different shape that
  would collide with this page's query (grep-verified: no prior usage existed since it was purely
  local state before).

## Known Risks
- **Live browser verification is incomplete** (see Test/Build Results) — comment and
  calculatePrices were driven end-to-end in a real running mock app; approve/reject/pickup/the
  dual-track chain, attachment upload/delete, and per-item override were NOT clicked through
  manually (only covered by the automated approve/comment tests). The interrupting discovery
  (a concurrent agent actively rebasing in the only worktree the browser-preview tool resolves
  to) means this environment's shared-worktree constraint is now confirmed to be an active
  collision risk, not just a stale/wrong-directory mixup like slice A hit — worth raising to the
  user as a standing tooling gap across all three ticket-query slices (A/B/C all hit variations
  of this).
- `factoryConfigsQuery`'s unconditional `enabled` (Decision #2) adds one extra network call for
  roles that never enter propose mode — low cost, flagged for reviewer judgment.
- The per-item override's shared-mutation-instance pattern (Decision #4) means only ONE item can
  ever be "overriding" at a time app-wide for this ticket (correct — the UI already only allowed
  one row's override form open at a time via `overrideDraft`), but if a future change allowed
  multiple simultaneous override submissions, this pattern would need revisiting.

## Things Not Finished
- Full manual click-through of every action (see Known Risks) — recommend doing this from a
  worktree not concurrently in use by another agent session.
- No PR opened (per instructions — implementation branch only, pushed, no PR/merge).

## Recommended Next Agent
Claude Opus review (standing Sonnet-implements/Opus-reviews loop). Specifically should:
1. Do the remaining manual click-through (approve/reject/pickup/dual-track actions/attachments/
   override) from a worktree that is confirmed NOT mid-rebase or otherwise in concurrent use —
   check `git status` there FIRST before assuming it's free, given what happened this session.
2. Weigh Decision #2 (`factoryConfigsQuery` unconditionally enabled vs. role-gated) — confirm the
   extra network call for non-propose roles is an acceptable trade for the shared-cache benefit.
3. Confirm Decision #1 (priceBreakdown as local state) matches the intent of the task's
   originally-offered options.
4. Sanity-check that `doAction`'s try/catch-swallow (needed because `mutateAsync` still rejects
   after `onError` runs) doesn't mask any error path a caller further up ever depended on
   noticing — grep confirms no call site currently awaits `doAction`'s return value for anything
   but sequencing (e.g. the cancel-confirm dialog's `await doAction(...); setConfirm(null);`),
   so this should be safe, but worth a second look.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch refactor/tickets-query-slice-b (pushed, based on
origin/refactor/tickets-query-slice-a at 10515ff). Read CLAUDE.md and
docs/agent-handoffs/63_refactor-tickets-query-slice-b.md — it documents migrating
TicketDetailPage.jsx's 26 useState hooks + doAction imperative helper to TanStack Query
(Phase-2 Branch 4 slice B). Lint/test/build all pass (101 tests, 0 errors, 0 new warnings vs.
slice A's baseline). This session verified two flows live in a running mock app (comment-post,
CEO calculate-prices) before a concurrent agent's in-progress git rebase (on review/query-c, in
the ONLY worktree this environment's browser-preview tool resolves to) forced it to stop rather
than risk collision — check `git status` in that shared worktree FIRST before using it again.
Please do the remaining manual click-through: log in as ceo on a price_proposed ticket, approve
it, confirm the status badge updates to "อนุมัติแล้ว" without a page reload and that
api.tickets.get is not re-called (setQueryData fast path); log in as import, pick up a submitted
ticket and propose prices; log in as ceo/account and walk a ticket through the post-quotation
dual-track (confirmCustomer → generateDocument → confirmDepositPaid → issueImportRequest →
markIrSent → markShipping → markGoodsReceived → confirmFinalPayment); upload and delete an
attachment; do a per-item CEO price override. Also confirm the manual refresh button now
refetches both the ticket AND its attachments (previously only the ticket). Also decide on the
handoff's two flagged decisions: (1) factoryConfigsQuery is unconditionally enabled rather than
role-gated — confirm the extra network call for non-propose roles (sales/account) is an
acceptable trade for the shared ['factoryConfigs'] cache; (2) priceBreakdown stays local
useState fed by the calculatePrices mutation's onSuccess rather than a synthetic query-cache key
— confirm this matches intent. Do not merge without doing the click-through above.
```
