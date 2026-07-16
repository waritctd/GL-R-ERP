# Agent Handoff

## Task
Phase-2 Branch 4 slice A (user-approved): migrate **TicketListPage** and **TicketDashboard**
server state from imperative `useState`/`useEffect` loaders to TanStack Query, following the
house conventions already established by `LeavePage.jsx` / `useHrData.js`. Query keys for
`tickets.detail`/`tickets.attachments` are defined now (unused here) so slice B
(`TicketDetailPage.jsx`) can build on a stable key module without a second edit to
`queryKeys.js`.

## Branch
`refactor/tickets-query-slice-a`

## Base Commit
`9666d4f` (origin/main at fetch time). Verified still an ancestor of the current `origin/main`
tip (`45b32e4`, PR #222 "dead-rule sweep + single-use migration of styles.css") — no rebase
needed, no file overlap with that CSS commit.

## Current Commit
See `git log -1` after this agent's commit lands (single commit,
`refactor(tickets): migrate list + dashboard to TanStack Query (slice A)`).

## Agent / Model Used
Claude Sonnet (implementation agent, standing Sonnet-implements/Opus-reviews loop).

## Scope

### In Scope
- `frontend/src/api/queryKeys.js` — add the `tickets` key namespace.
- `frontend/src/features/tickets/TicketListPage.jsx` — `useQuery`/`useMutation` conversion.
- `frontend/src/features/dashboard/TicketDashboard.jsx` — `useQuery` conversion (two queries,
  one shared with `TicketListPage`'s "ทั้งหมด" tab).
- New tests: `TicketListPage.test.jsx`, `TicketDashboard.test.jsx`.

### Out of Scope (explicitly not touched)
- `TicketDetailPage.jsx`, `DepositNoticePage.jsx`, `CeoSettingsPage.jsx`, `NotificationBell.jsx`
  — slices B/C own these.
- `frontend/src/api/mockApi.js` — no shape or authz changes; `contract.test.js` still passes
  unmodified.
- `TicketCreateModal.jsx` — untouched; its own try/catch around `onSubmit` is relied on as-is
  (see Decisions Made #2).
- Any business logic, API contract, auth, routes, or DB schema.

## Files Changed
- `frontend/src/api/queryKeys.js` — added:
  ```js
  tickets: {
    list: (status) => ['tickets', 'list', status ?? ''],
    detail: (id) => ['tickets', 'detail', id],
    attachments: (id) => ['tickets', 'attachments', id],
  },
  ```
  This is a nested-object shape (unlike the flat `leaveRequests`/`leaveEmployees` style elsewhere
  in the file) — implemented exactly as specified in the task prompt. `detail`/`attachments` are
  unused by this slice; they exist so the key module doesn't need a second edit for slice B.
- `frontend/src/features/tickets/TicketListPage.jsx`:
  - Replaced the `tickets` `useState` + `loadTickets`/mount `useEffect` with
    `useQuery({ queryKey: queryKeys.tickets.list(statusFilter), queryFn: ... })`; `loading =
    isLoading || isFetching` (matches `LeavePage` convention).
  - Error toast preserved via a `useEffect` on `ticketsQuery.error` (same message/behavior as the
    old catch block).
  - Create flow is now `useMutation` (`api.tickets.create`), invalidating the `['tickets',
    'list']` prefix on success — same success toast, same `setCreating(false)`.
  - Manual refresh icon-button now calls the same prefix-invalidate function instead of
    `loadTickets(statusFilter)`.
  - `statusFilter`/`searchText` unchanged — still driven by `useSearchParams` exactly as before.
- `frontend/src/features/dashboard/TicketDashboard.jsx`:
  - Replaced the mount-only `Promise.all([api.dashboard.summary(), api.tickets.list({})])` with
    two independent queries: `queryKeys.dashboardSummary()` and `queryKeys.tickets.list('')`.
  - `queryKeys.tickets.list('')` is the **same cache entry** `TicketListPage` uses for its "ทั้งหมด"
    (all) tab — a mutation anywhere that invalidates `['tickets','list']` now refreshes this
    dashboard's recent-tickets strip too, not just on next mount. This is the deliberate
    improvement named in the task.
  - `summary`/`notifications`/`recent` are now derived with `useMemo` from query data using the
    same defensive unwrapping (`summaryRes?.summary ?? summaryRes ?? null`, array-or-`.tickets`
    guard, `.slice(0, 6)`) instead of being set imperatively.
  - `loading` is deliberately `isLoading` only (not `isFetching`) — see Decisions Made #1.
  - Error toast preserved via a `useEffect` combining both queries' `.error`.
- `frontend/src/features/tickets/TicketListPage.test.jsx` (new) — renders rows from a mocked
  `api.tickets.list`; a create-mutation smoke test (stubs `TicketCreateModal` to a single button
  so the test drives `TicketListPage`'s mutation wiring, not the modal's own multi-field form)
  asserting `api.tickets.create` is called and `api.tickets.list` refetches afterward.
- `frontend/src/features/dashboard/TicketDashboard.test.jsx` (new) — renders stat counts from a
  mocked `api.dashboard.summary`, and renders the recent-tickets strip from a mocked
  `api.tickets.list`, asserting the exact shared-cache call shape (`api.tickets.list({})`).

## Commands Run
```bash
git fetch origin
git switch -c refactor/tickets-query-slice-a origin/main
cd frontend && npm ci
npx vitest run src/features/tickets/TicketListPage.test.jsx src/features/dashboard/TicketDashboard.test.jsx
npm run lint
npm test
npm run build
```

## Test / Build Results
- `npm run lint`: **pass** — 0 errors, 6 pre-existing warnings (all `react-hooks/exhaustive-deps`
  in files this branch didn't touch: `AttendancePage.jsx`, `CeoSettingsPage.jsx`,
  `CommissionPage.jsx`, `PayrollPage.jsx`, `TicketDetailPage.jsx`).
- `npm test`: **pass** — 21 test files, 98 tests, 0 failures (94 pre-existing + 4 new).
- `npm run build`: **pass** — Vite build completed in 141ms, no errors.
  `TicketListPage` chunk: 29.91 kB / gzip 7.50 kB. `TicketDashboard` chunk: 7.10 kB / gzip 2.45 kB.
- Backend: not touched, not run (frontend-only change).
- **Live browser verification: NOT done, and this is a real gap — flagging clearly.** This
  session's `preview_start`/browser tools are bound to a *different*, shared worktree
  (`.claude/worktrees/charming-gauss-f118ad`), not this implementation worktree
  (`agent-a3a4cc5def8677097`). Starting the mock dev server via the browser tool reused (and
  then, after a restart, still pointed at) that other worktree's checkout — confirmed via
  `preview_list`'s reported `cwd`. That worktree was mid-task on an unrelated branch
  (`review/css-min`, clean, PR-ready) when this session started; I had accidentally run this
  task's initial `git switch -c` setup there by mistake (wrong directory), caught it before any
  edits were made, and reverted it back to `review/css-min` cleanly (`git status` was clean both
  before and after — no work was lost). Given the browser preview tool cannot be pointed at my
  actual worktree, and I did not want to risk further disruption to that shared checkout by
  copying files into it, I relied on the automated test suite instead: the 4 new tests exercise
  the exact query/mutation/invalidation logic (initial render from a mocked list/summary call,
  create → invalidate → refetch, shared-cache key shape) that a manual click-through would have
  verified. **Recommend the next agent (or Opus) do a manual click-through** on a worktree where
  the browser preview tool actually resolves to this branch — log in as `sales`, confirm the list
  loads, filter tabs update the URL and refetch, the refresh icon-button refetches, creating a
  ticket shows the success toast and the new row appears without a full-page reload; log in as
  `import`/`ceo`, confirm the ticket-overview dashboard cards/queue render and a status-filtered
  navigation from a stat card still works.

## Decisions Made
1. **`TicketDashboard`'s `loading` gate uses `isLoading` only, not `isLoading || isFetching`**
   (which is what `LeavePage` uses for its inline table-loading state). Rationale: `loading` here
   controls whether the *entire* dashboard is replaced by a full skeleton screen. If it included
   `isFetching`, then every background refetch triggered by another page's invalidation (the
   deliberate live-update improvement this task asked for) would flash the whole dashboard back to
   a skeleton, which is a visible regression relative to "keep rendering identical" — a quiet
   background update reusing the last-good data is what "live-update" should mean here. `loading`
   in `TicketListPage` *does* keep `isLoading || isFetching` (matching `LeavePage`) because there
   it only gates a small inline `EmptyState`/table-loading swap, not the whole page.
2. **`TicketListPage`'s create mutation has no `onError` toast.** The original imperative
   `handleCreate` had no try/catch — a thrown error from `api.tickets.create` propagated
   uncaught up into `TicketCreateModal`'s own `submit()` try/catch, which sets its own inline
   `error` state and re-enables the form (no toast). Adding an `onError` toast in the mutation
   would have been an *additional* user-visible behavior beyond what existed before, so it was
   deliberately omitted — `mutateAsync` still rejects on failure, so `TicketCreateModal`'s
   existing error handling is unchanged.
3. **`queryKeys.tickets` uses a nested-object shape** (`{ list, detail, attachments }`) rather
   than the flat top-level-function style the rest of the file uses (`leaveRequests`,
   `leaveEmployees`, etc.). This was the shape explicitly given in the task prompt; flagging the
   inconsistency for whoever reviews the key-module style going forward, in case a follow-up wants
   to flatten it to `ticketsList`/`ticketDetail`/`ticketAttachments` for consistency.
4. **Manual refresh button now calls `invalidateTicketsList` directly** (a same-file helper that
   calls `queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] })`) instead of
   `refetch()` on the query object. This was chosen over `ticketsQuery.refetch()` because it's the
   exact same prefix-invalidate the create-mutation's `onSuccess` uses, keeping "refresh" and
   "post-create refresh" as one code path instead of two slightly different ones.

## Assumptions
- `queryKeys.tickets.list('')` (dashboard) and `queryKeys.tickets.list(statusFilter)` with
  `statusFilter === ''` (list page's "ทั้งหมด" tab) produce an identical key array
  (`['tickets','list','']`) and therefore share one cache entry — confirmed by reading both call
  sites; this is the mechanism the shared-cache "deliberate improvement" depends on.
- `api.tickets.list({})` (dashboard, unconditional) and `api.tickets.list(statusFilter ? {status:
  statusFilter} : {})` with `statusFilter === ''` (list page) are the same call shape (`{}`) —
  confirmed against `hrApi.js`'s `tickets.list: (params) => apiRequest(withQuery(...))` and
  `mockApi.js`'s `tickets.list(params = {})`, both treating an empty/absent `status` identically.
- No other page currently reads `queryKeys.dashboardSummary()` or the `['tickets','list', ...]`
  prefix in a way that would be broken by this change — `grep`-verified no other call sites exist
  yet (`TicketDetailPage.jsx` uses its own imperative loader, untouched by this slice).

## Known Risks
- **No live browser verification in this worktree** (see Test/Build Results above) — the
  query/mutation logic is unit-tested, but the actual click-through (filter tabs, refresh button,
  create-ticket toast + row appearing) has not been visually confirmed since this branch's edits
  landed. This is the main thing the next agent/reviewer should close out.
- The nested `tickets: {...}` key shape (Decision #3) is a minor style inconsistency with the rest
  of `queryKeys.js` — low risk (keys are still correct and typo-proof), but worth a style decision
  before slice B extends it further.
- `TicketDashboard`'s `loading = isLoading` (not `isFetching`) means a *slow* background refetch
  after an invalidation won't show any loading indicator at all — data will just update in place
  once it resolves. This matches the "quiet live update" intent, but if a reviewer expects some
  visible feedback during a background refetch, that's a intentional trade-off to revisit (Decision #1).

## Things Not Finished
- Live browser click-through (see Known Risks).
- No PR opened (per instructions — implementation branch only, pushed, no PR/merge).

## Recommended Next Agent
Claude Opus review (standing Sonnet-implements/Opus-reviews loop). Specifically should:
1. Do the manual click-through described in Test/Build Results, from a worktree where the browser
   preview tool actually resolves to `refactor/tickets-query-slice-a`.
2. Weigh Decision #1 (`isLoading`-only dashboard loading gate) — confirm the "no skeleton flash on
   background refetch" trade-off is the intended UX, not an oversight.
3. Decide whether Decision #3's nested `tickets` key shape should be flattened for consistency
   with the rest of `queryKeys.js` before slice B (`TicketDetailPage.jsx`) builds on
   `queryKeys.tickets.detail`/`.attachments`.
4. Confirm Decision #2 (no error toast on ticket-create failure) matches the intended UX — it's a
   faithful behavior-preservation call, not a new design decision, but worth a second look since
   every other mutation in this codebase (leave, overtime, etc.) does toast on error.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch refactor/tickets-query-slice-a (pushed, based on origin/main at 9666d4f,
ancestor-verified against current origin/main tip 45b32e4). Read CLAUDE.md and
docs/agent-handoffs/62_refactor-tickets-query-slice-a.md — it documents migrating TicketListPage
and TicketDashboard server state to TanStack Query (Phase-2 Branch 4 slice A). Lint/test/build all
pass (98 tests, 0 errors, 0 new warnings), but this session could NOT do a live browser
click-through: the browser-preview tool in this environment resolved to a different, shared
worktree (.claude/worktrees/charming-gauss-f118ad) rather than the implementation worktree, and I
chose not to risk disrupting that shared checkout further to force it. Please do the manual
click-through from wherever the preview tool resolves to this branch: log in as `sales`, confirm
the ticket list loads, filter tabs update the URL and refetch, the refresh icon-button refetches,
and creating a ticket shows the success toast with the new row appearing without a full reload;
log in as `import` or `ceo`, confirm the ticket-overview dashboard renders and that a stat-card
navigation with ?status=... still filters correctly, and that a create/action elsewhere (e.g.
approving a ticket) live-updates the dashboard's recent-tickets strip without a manual refresh
(the deliberate cache-sharing improvement documented in the handoff). Also decide on the handoff's
three flagged decisions: (1) TicketDashboard's loading gate is isLoading-only, not
isLoading||isFetching, to avoid a skeleton flash on background refetches — confirm this is the
intended UX; (2) queryKeys.tickets uses a nested-object shape unlike the rest of queryKeys.js's
flat style — decide if it should be flattened before slice B (TicketDetailPage.jsx) extends it;
(3) the ticket-create mutation has no onError toast, matching the original uncaught-propagation
behavior into TicketCreateModal's own inline error state — confirm this is still desired now that
every other mutation in the codebase does toast on error. Do not merge without doing the browser
click-through above.
```
