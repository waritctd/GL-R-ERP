# Agent Handoff

## Task
Phase-2 Branch 4 slice C (user-approved, final slice): migrate **DepositNoticePage.jsx**,
**CeoSettingsPage.jsx**, and **NotificationBell.jsx** server state from imperative
`useState`/`useEffect` loaders to TanStack Query, following the house conventions established by
`LeavePage.jsx` and slice A (`62_refactor-tickets-query-slice-a.md`). Slice B
(`refactor/tickets-query-slice-b`, live in a shared worktree at the time this branch was worked)
owns `TicketDetailPage.jsx` — not touched here, per the task's explicit instruction.

## Branch
`refactor/tickets-query-slice-c`

## Base Commit
`10515ff` (origin/refactor/tickets-query-slice-a tip at fetch time — "review(tickets): flatten
queryKeys.tickets to match the file convention"). Branch created via
`git switch -c refactor/tickets-query-slice-c origin/refactor/tickets-query-slice-a`.

## Current Commit
See `git log -1` after this agent's commit lands (single commit,
`refactor(sales): migrate deposit/ceo-settings/notification-bell to TanStack Query (slice C)`).

## Agent / Model Used
Claude Sonnet (implementation agent, standing Sonnet-implements/Opus-reviews loop).

## Scope

### In Scope
- `frontend/src/api/queryKeys.js` — append `depositNotices`, `depositNoteTemplates`,
  `customersSearch`, `fxRates`, `priceCalcConfigs` key factories (append-only, no reordering of
  slice A's `ticketList`/`ticketDetail`/`ticketAttachments`).
- `frontend/src/features/deposits/DepositNoticePage.jsx` — `useQuery`/`useMutation` conversion.
- `frontend/src/features/ceoSettings/CeoSettingsPage.jsx` — `useQuery`/`useMutation` conversion.
- `frontend/src/components/common/NotificationBell.jsx` — `useQuery` (with `refetchInterval`) +
  `useMutation` conversion.
- New tests: `DepositNoticePage.test.jsx`, `CeoSettingsPage.test.jsx`, `NotificationBell.test.jsx`.

### Out of Scope (explicitly not touched)
- `TicketDetailPage.jsx`, `TicketListPage.jsx`, `TicketDashboard.jsx` — slice A/B own these. Verified
  via `grep` that none of this branch's edits reference or modify them.
- `frontend/src/api/mockApi.js` / `hrApi.js` — no shape or authz changes; `contract.test.js` still
  passes unmodified (mockApi already implements `depositNotices.createDraft`/`.listByTicket` —
  confirmed both hrApi and mockApi expose them under the `depositNotices` namespace, not a gap).
- Any business logic, API contract, auth, routes, DB schema, or styling/design tokens (the few
  pre-existing literal colors the design-system lint hook flagged — box-shadow/backdrop rgba values
  in DepositNoticePage's customer dropdown and CeoSettingsPage's config modal, plus
  NotificationBell's `TYPE_ICON` colors and one font-size — all predate this branch and were not
  touched; confirmed by re-checking line content after each edit).

## Files Changed
- `frontend/src/api/queryKeys.js` — appended (flat named-function style, matching the rest of the
  file, not slice A's now-flattened `ticketList`/`ticketDetail`/`ticketAttachments` precedent):
  ```js
  depositNotices: (ticketId) => ['depositNotices', ticketId],
  depositNoteTemplates: () => ['depositNotices', 'templates'],
  customersSearch: (q) => ['customers', 'search', q ?? ''],
  fxRates: () => ['fxRates'],
  priceCalcConfigs: () => ['priceCalcConfigs'],
  ```

- `frontend/src/features/deposits/DepositNoticePage.jsx`:
  - Three reads: `noteTemplatesQuery` (`depositNoteTemplates()`), `customersQuery` (keyed by the
    live `customerSearch` state — `customersSearch(customerSearch)`, so `customerSearch === ''` at
    mount is exactly the "whole master list" load the old `loadDocs()` did up front), and
    `depositNoticesQuery` (`depositNotices(ticketId)`, returning the raw doc array).
  - `doc` is now a `useMemo` derived from `depositNoticesQuery.data`, computing DRAFT-or-latest-ISSUED
    exactly as the old `loadDocs()` did (byte-identical `find`/`filter`/`sort` logic moved as-is).
  - **Mount is still LOAD-ONLY** (PR #218's fix preserved) — nothing here ever calls `createDraft`
    except the user's own click on `handleCreateDraft`.
  - `form` is a `useState` re-seeded via a `useEffect` keyed on a `docSeedKey` string
    (`` `${doc.id}:${doc.version}:${doc.status}` ``), not on `doc`'s object identity — see Decision
    #2 below for why.
  - `handleCreateDraft`/`handleSave`/`handlePreview`/`handleIssue` are now
    `createDraftMutation`/`saveMutation`/`previewMutation`/`issueMutation`. All four invalidate
    `depositNotices(ticketId)` on success; `issueMutation` additionally invalidates `['tickets',
    'list']` and `queryKeys.ticketDetail(ticketId)` (that key already exists in `queryKeys.js` post
    slice A's flattening — used directly, no literal-array fallback needed).
  - The customer-search typeahead's `onChange` no longer fires its own imperative
    `api.customers.search(...)` call — it just updates `customerSearch` state, and the query's key
    change drives the refetch (same call, same per-keystroke cadence, one less hand-rolled fetch
    path).
  - `handleDownloadXlsx`/`handleDownloadPdf`/`download()` are untouched (not in the task's mutation
    list; they're one-shot blob fetches with no cache to invalidate).
  - `previewHtml`/`confirmIssue`/`downloading`/`customerSearch` remain plain `useState` (ephemeral
    UI state, as specified).

- `frontend/src/features/ceoSettings/CeoSettingsPage.jsx`:
  - `fxRatesQuery` (`fxRates()`) and `configsQuery` (`priceCalcConfigs()`) replace the `load()`
    imperative `Promise.all`. `loading = fxRatesQuery.isLoading || configsQuery.isLoading`. Errors
    surface via a `useEffect` per query (matches `LeavePage`'s per-query error-toast convention).
  - `saveFxRate`/`saveConfig` are now `saveFxRateMutation`/`saveConfigMutation`, invalidating
    `fxRates()`/`priceCalcConfigs()` respectively — the direct mutation analog of the old
    hand-rolled re-list-after-save.
  - The old per-currency `savingFx` state object is gone; a shared mutation only has one in-flight
    call at a time, so "is this row's save in flight" is now
    `saveFxRateMutation.isPending && saveFxRateMutation.variables?.currency === currency`
    (`isSavingFx(currency)` helper) — reading the mutation's own in-flight variables back out
    instead of tracking a parallel per-key boolean map.
  - `editFx`/`editingConfig`/`configDraft` remain plain `useState` (UI/edit-draft state, as
    specified).

- `frontend/src/components/common/NotificationBell.jsx`:
  - `notificationsQuery` (`queryKeys.notifications()`, already defined pre-slice-C) replaces the
    manual `load()` + `setInterval(load, 30000)`; `refetchInterval: 30_000` reproduces the exact
    cadence. `items = notificationsQuery.data ?? []` reproduces the old catch-and-clear-to-`[]`
    fallback on error (this component has no `showToast` prop, so errors stay silent as before).
  - `markRead`/`markAllRead` are now `markReadMutation`/`markAllReadMutation`, invalidating
    `notifications()` on success — the optimistic local-array patch was dropped in favor of
    invalidate-and-refetch (see Decision #3), per the task's "your call" on that trade-off.
  - Click-outside-closes-dropdown effect and all dropdown/badge JSX are untouched.

- `frontend/src/features/deposits/DepositNoticePage.test.jsx` (new) — empty-state render + create
  draft mutation (asserts the exact payload built from note templates, and that the post-mutation
  invalidation triggers a `listByTicket` refetch); a second test renders an existing DRAFT doc and
  asserts the form is seeded from it (`findByDisplayValue`).
- `frontend/src/features/ceoSettings/CeoSettingsPage.test.jsx` (new) — renders fx rates from a
  mocked `api.fxRates.list`; a second test drives the Override → edit → save flow and asserts
  `api.fxRates.upsert` is called with the right payload and `api.fxRates.list` refetches
  (invalidation working end to end).
- `frontend/src/components/common/NotificationBell.test.jsx` (new, the "nice-to-have" requested) —
  renders items + unread badge from a mocked `api.notifications.list`; a second test clicks an
  unread item and asserts `markRead` fires and the shared `notifications()` cache refetches
  afterward (the mechanism slice B/other pages rely on to live-update the bell).

## Commands Run
```bash
git fetch origin
git switch -c refactor/tickets-query-slice-c origin/refactor/tickets-query-slice-a
cd frontend && npm ci
npx vitest run src/features/deposits/DepositNoticePage.test.jsx src/features/ceoSettings/CeoSettingsPage.test.jsx src/components/common/NotificationBell.test.jsx
npm run lint
npm test
npm run build
```

## Test / Build Results
- `npm run lint`: **pass** — 0 errors, 5 pre-existing warnings, all `react-hooks/exhaustive-deps`
  in files this branch didn't touch (`AttendancePage.jsx`, `CommissionPage.jsx` x2,
  `PayrollPage.jsx`, `TicketDetailPage.jsx`). None of the three files this slice edited produce any
  warning.
  - Note: an intermediate version of `DepositNoticePage.jsx` hit a real lint **error** —
    `populateForm` was declared after the `useEffect` that referenced it, and
    `react-hooks`'s newer rule flags forward-referenced function declarations inside hook bodies
    (even though JS function-declaration hoisting makes it runtime-safe). Fixed by moving
    `populateForm`'s declaration above the seeding effect. Flagging this because it's a rule
    slice A/B may not have hit yet (their functions weren't referenced from inside a `useEffect`
    the same way) — worth knowing if a future slice hits the same error.
- `npm test`: **pass** — 24 test files, 104 tests, 0 failures (98 pre-existing + 6 new).
  `contract.test.js` (mockApi/hrApi method-surface parity) passes unmodified — no API shape drift.
- `npm run build`: **pass** — Vite build completed in 140ms, no errors.
  `DepositNoticePage` chunk: 21.34 kB / gzip 5.51 kB. `CeoSettingsPage` chunk: 11.27 kB / gzip
  3.18 kB. (NotificationBell has no own chunk — it's part of the shared app-shell bundle.)
- Backend: not touched, not run (frontend-only change).
- **Live browser verification: NOT done — same gap slice A hit, confirmed reproduced.** This
  session's `preview_start`/browser tools are bound to a shared worktree
  (`.claude/worktrees/charming-gauss-f118ad`), not this implementation worktree
  (`agent-ad56e31dd202c8f8c`) — confirmed via `preview_list`'s reported `cwd`. Unlike slice A's
  session, I did **not** attempt any `git switch` there: `git status` in that worktree showed it is
  currently **mid-task** — on branch `review/query-a` (tracking
  `origin/refactor/tickets-query-slice-a`) with **uncommitted changes to `TicketDetailPage.jsx`**
  (a file this task explicitly forbids me from touching, and which slice B/a reviewer owns) plus an
  untracked `TicketDetailPage.test.jsx`. Switching that worktree's branch to drive a preview of my
  own branch would risk clobbering that in-progress work — exactly the collision CLAUDE.md's branch
  discipline rule ("do not let two agents edit the same branch/worktree at the same time") exists to
  prevent. I left it untouched and relied on the automated test suite instead, which exercises the
  exact query/mutation/invalidation logic (create-draft payload + refetch, fx-rate save + refetch,
  notification mark-read + shared-cache refetch) a manual click-through would verify.
  **Recommend the next agent/Opus do a manual click-through** from wherever the browser preview
  tool resolves to `refactor/tickets-query-slice-c` (a fresh worktree, or once `review/query-a`'s
  work has landed and that shared worktree is free): as `sales`, open a ticket's deposit notice with
  no existing document, confirm the empty state + "สร้างเอกสารฉบับร่าง" button work, save/preview/issue
  a draft and confirm each still round-trips correctly (issue requires the ticket to be
  `quotation_issued` + `paymentStatus=CUSTOMER_CONFIRMED`, per `mockApi.depositNotices.issue`); as
  `ceo`, open CEO Settings, confirm fx rates + price configs render, override an fx rate and confirm
  it saves + the row updates; confirm the notification bell renders, polls, and marks items read.

## Decisions Made
1. **`customersQuery` is keyed by the live `customerSearch` text**
   (`queryKeys.customersSearch(customerSearch)`), turning the old onChange's imperative
   `api.customers.search(text).then(setCustomers)` into a reactive query whose key changes on every
   keystroke. This is a deliberate simplification (one fewer hand-rolled fetch path) that reproduces
   the exact same call pattern (one `search()` call per keystroke, no debounce, same as before).
2. **The full-page loading/error gates deliberately exclude `customersQuery`.** Including
   `customersQuery.isLoading`/`.error` in the top-level `loading`/`loadError` (which control the
   whole-page skeleton and the whole-page error+retry state) would mean every keystroke in the
   customer-search box — which mints a "new" query key never fetched before — flashes the *entire
   page* to a skeleton, or (on a flaky search request) hijacks the whole page into an error state.
   The original imperative version never had this problem (the onChange fetch had no loading UI and
   a silent `.catch(() => {})`). `loading`/`loadError` are scoped to `depositNoticesQuery` +
   `noteTemplatesQuery` only; a failed/slow customer search stays invisible outside its own dropdown,
   matching original behavior. Trade-off: an *initial* mount-time customer-list fetch failure no
   longer blocks the page into the error+retry state (the original did block on this, since it was
   inside the same `Promise.all` as the mount-time `noteTemplates` fetch) — a minor regression in
   strictness, but the alternative (folding search-time errors into the blocking state) is a worse,
   more visible regression. Flagging for the reviewer to confirm this trade-off is acceptable.
3. **`form` is re-seeded via a `useEffect` keyed on `` `${doc.id}:${doc.version}:${doc.status}` ``,
   not on `doc`'s object identity or on every `depositNoticesQuery` refetch.** Each fetch produces a
   brand-new array/object graph (via mockApi's `structuredClone`), so keying the reseed on object
   identity would refire on *any* background refetch — including ones triggered by a `handleSave`'s
   own invalidation, where the just-saved data should already match the form (no reseed needed) but
   would otherwise still fire harmlessly, and more importantly by any *future* invalidation this
   query might pick up from elsewhere. The `id:version:status` signature only changes on a real
   state transition (new draft created, or DRAFT → ISSUED) — `update()` doesn't bump version/status,
   so a save's own refetch is a no-op reseed (correct: the saved data already matches the form that
   produced it), while `issue()` does flip status, correctly re-triggering the reseed so the form
   picks up read-only/issued rendering. This also means an in-flight edit is never silently
   clobbered by a same-content background refetch.
4. **NotificationBell's mark-read/mark-all-read dropped the optimistic local-array patch in favor of
   invalidate-and-refetch**, per the task's explicit "your call" on this trade-off — chosen for
   consistency with every other mutation in this codebase (Leave/Ticket/Deposit/CeoSettings all
   invalidate-and-refetch rather than hand-patching the cache via `setQueryData`). The visible
   effect is a one-refetch-round-trip delay before the read/unread badge updates, instead of
   instant — acceptable given the mock's near-zero latency, but worth a second look if the real
   backend adds meaningful latency to `PATCH /notifications/{id}/read`.
5. **`isSavingFx(currency)` reads `saveFxRateMutation.variables` back out** instead of keeping a
   parallel `savingFx` state map, since only one fx-rate save can be in flight at a time through a
   single shared mutation. This is the standard TanStack pattern for "which row is a shared mutation
   acting on" and avoids a redundant piece of state that could drift from the mutation's actual
   pending status.

## Assumptions
- `queryKeys.notifications()` (defined before this slice, for a different/future consumer) is safe
  to adopt as-is for the bell — confirmed via `grep` that no other file referenced it yet, so this
  is genuinely greenfield, not a collision.
- `queryKeys.ticketDetail(ticketId)` and the `['tickets','list']` prefix used in `issueMutation`'s
  cross-invalidation are stable by the time this branch merges — confirmed both already exist in
  `queryKeys.js` post slice A's review-commit (`10515ff`, which flattened `tickets.detail` to
  `ticketDetail`), so no placeholder/literal-array fallback was needed for `ticketDetail`.
- `api.depositNotices.createDraft`/`.listByTicket` living under the `depositNotices` namespace (not
  `tickets`) in both `hrApi.js` and `mockApi.js` is intentional, current, and covered by
  `contract.test.js` — confirmed by reading both files directly rather than assuming from the
  call-site naming.
- The shared `charming-gauss-f118ad` worktree being mid-task on `review/query-a` is transient (some
  other agent's active review/implementation session) — not a permanent fixture of this repo's
  setup. If it's still mid-task when the next agent picks this up, the same worktree-collision
  caution applies.

## Known Risks
- **No live browser verification** (see Test/Build Results) — same category of gap as slice A,
  reproduced for the same structural reason (shared preview worktree bound to a different branch),
  compounded this time by that worktree being actively in use by another agent. The
  query/mutation/invalidation logic is unit-tested end to end, but the actual click-through
  (deposit-notice create/save/preview/issue round trip, fx-rate override, notification bell live
  update) has not been visually confirmed on this branch.
- Decision #2's trade-off (an initial customer-list load failure no longer blocks the page into the
  error+retry state) is a minor, deliberate strictness regression — low risk (customers data is
  supplementary auto-fill, not core to the deposit-notice flow) but worth the reviewer's explicit
  sign-off.
- Decision #4 (bell mutations invalidate-and-refetch rather than optimistic-patch) trades instant
  visual feedback for consistency with the rest of the codebase — low risk against the mock, worth
  re-confirming once real backend latency is in the loop.

## Things Not Finished
- Live browser click-through (see Known Risks) — deferred to whichever agent/reviewer can get the
  preview tool pointed at this actual branch.
- No PR opened, no merge (per instructions — implementation branch only, pushed).
- Merging slice B's `queryKeys.js` additions (it's meant to append there too) is a trivial
  three-way merge left for whoever integrates all three slices — this branch only appended slice
  C's five keys after slice A's existing ones, untouched otherwise.

## Recommended Next Agent
Claude Opus review (standing Sonnet-implements/Opus-reviews loop). Specifically should:
1. Do the manual click-through described in Test/Build Results, once a worktree/browser-preview
   setup actually resolves to `refactor/tickets-query-slice-c` (and `review/query-a` in the shared
   worktree is no longer mid-task).
2. Weigh Decision #2 (customers query excluded from the blocking loading/error gate) — confirm the
   trade-off (no more mount-time customer-list-failure blocking) is acceptable.
3. Weigh Decision #3 (form reseed keyed on `id:version:status`, not object identity/every refetch) —
   confirm this is the right signal for "the doc materially changed" vs. potentially too narrow if a
   future backend change makes `update()` bump version.
4. Confirm Decision #4 (bell drops the optimistic patch) is the desired trade-off now that this is
   documented, not just an oversight.
5. Once slices A/B/C are all reviewed, plan the merge order and the three-way `queryKeys.js`
   reconciliation (each slice only appended, so it should be a clean merge, but worth double-checking
   no two slices picked overlapping key names).

## Exact Next Prompt
```text
Repo GL-R-ERP, branch refactor/tickets-query-slice-c (pushed, based on
origin/refactor/tickets-query-slice-a at 10515ff). Read CLAUDE.md and
docs/agent-handoffs/64_refactor-tickets-query-slice-c.md — it documents migrating
DepositNoticePage.jsx, CeoSettingsPage.jsx, and NotificationBell.jsx to TanStack Query
(Phase-2 Branch 4 slice C, the final slice). Lint/test/build all pass (104 tests, 0 errors, 0 new
warnings), but this session could NOT do a live browser click-through: the browser-preview tool
resolved to a different, shared worktree (.claude/worktrees/charming-gauss-f118ad) which was
ALSO mid-task on a different branch (review/query-a, uncommitted changes to TicketDetailPage.jsx)
at the time — so touching it was doubly unsafe, not just inconvenient. Please do the manual
click-through from wherever the preview tool resolves to this branch (a fresh worktree, or once
review/query-a's work has landed): as `sales`, open a ticket's deposit notice with no existing
document and confirm the empty state + "สร้างเอกสารฉบับร่าง" button create a draft correctly; save,
preview, and issue it (issue requires the ticket to already be quotation_issued with
paymentStatus=CUSTOMER_CONFIRMED — mockApi.depositNotices.issue enforces this) and confirm each
action still round-trips; as `ceo`, open CEO Settings, confirm fx rates + price-calc configs
render, override an fx rate and confirm the row updates after save; confirm the notification bell
renders unread items, the badge count is correct, marking an item read updates it, and the 30s
poll still runs. Also decide on the handoff's flagged decisions: (1) the customers-search query is
deliberately excluded from the page's blocking loading/error gate, since including it would flash
the whole page to a skeleton/error on every keystroke — but this also means an initial
customer-list load failure no longer blocks the page like it used to, a minor strictness
regression; confirm acceptable. (2) the form-reseed effect is keyed on a
`${doc.id}:${doc.version}:${doc.status}` signature rather than doc's object identity, so a save's
own background refetch (same version/status) doesn't reseed the form and clobber in-progress
edits — confirm this is the right signal, and that it wouldn't miss a real content change encoded
some other way. (3) NotificationBell's mark-read/mark-all-read now invalidate-and-refetch instead
of optimistically patching the local array — confirm this consistency choice over instant-feedback
is desired. Once this and slices A/B are both reviewed, plan the merge order and reconcile
queryKeys.js across all three (each slice only appended new keys, so it should merge cleanly, but
double check no two slices picked the same key name). Do not merge without the browser
click-through above.
```
