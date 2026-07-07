# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
Introduce **TanStack Query v5** and migrate the entire `useHrData` shared server-state layer onto it: the four app-wide reads (`currentEmployee`, `employees`, `profileRequests`, `dashboardSummary`) + the four mutations, replacing the imperative `loadData(user)` `Promise.all` and hand-patched `useState`. Roadmap "branch 3" (`01_STABILIZATION_AUDIT.md` §7 P1-1, §8). Full plan: `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md`.

## Branch
`refactor/tanstack-query-core` (off `main`; ALREADY created and checked out for you)

## Base Commit
`20177b8` (main tip)

## Current Commit
`20177b8` — work left **uncommitted** on `refactor/tanstack-query-core` for review (per task rules: do NOT commit/push). Working tree carries the change set below.

## Agent / Model Used
Implementer: Claude Opus (claude-opus-4-8) · Reviewer: Claude Opus _(per audit §9)_

## Scope

### In Scope
- Add `@tanstack/react-query` (v5) to `frontend/package.json` (commit `package-lock.json`).
- New `frontend/src/api/queryClient.js` (`QueryClient`: `staleTime: 30_000`, `retry: 1`, `refetchOnWindowFocus: false`) and `frontend/src/api/queryKeys.js` (key factory).
- `frontend/src/main.jsx`: wrap `<App/>` in `<QueryClientProvider>` (inside `<React.StrictMode>`).
- Rewrite `frontend/src/hooks/useHrData.js`: reads → `useQuery` (all `enabled`-gated on `user`), mutations → `useMutation` + `invalidateQueries`. **Keep the return shape/field names stable** so `App.jsx` barely changes. Remove `loadData`. `resetData` clears local UI state + purges the query cache.
- `frontend/src/App.jsx`: drop the two `await loadData(...)` calls (session-restore + login); queries fire via `enabled`. Keep `resetData()` on logout.
- Rewrite `frontend/src/hooks/useHrData.test.js` for the Query-based API (wrap in `QueryClientProvider`, assert via `waitFor`).

### Out of Scope
- Routing (`route`/`selectedEmployee`/the `App.jsx` ternary) — branch 5. Keep them as local `useState`; `openEmployee` stays imperative.
- HR-core feature-page fetching (leave/overtime/attendance/payroll) — branch 4.
- The `api/` transport layer (`client.js`, `index.js`, `hrApi.js`, `mockApi.js`) — reused unchanged; `queryFn`s call the existing `api.*` methods.
- Sales/CRM stack, styles, business logic. No optimistic updates, no Devtools in the prod bundle.

## Implementation notes (from the approved plan)
- **currentEmployee**: `enabled: !!user?.employeeId`; `queryFn: () => api.employees.get(user.employeeId).then(r => r.employee)`.
- **employees**: `enabled: !!user && hasPermission(user.role, 'canViewEmployees')`; else derive via `useMemo` from `currentEmployee` (`currentEmployee ? [currentEmployee] : []`) — do NOT run the list query for non-viewers.
- **profileRequests**: `enabled: !!user`; `queryFn` keeps `.catch(() => [])`.
- **dashboardSummary**: `enabled: !!user && !!api.dashboard?.summary`; `queryFn` mirrors `.then(r => r?.summary ?? r ?? null).catch(() => null)`.
- **Mutations** exposed as same-signature async wrappers (`(payload) => m.mutateAsync(payload)`): `createEmployee` → invalidate `employees` + route to employees + toast; `updateEmployee` → invalidate `employees` + `currentEmployee(id)`, set `selectedEmployee` from response, toast; `createProfileRequest` / `reviewProfileRequest` → invalidate `profileRequests` + toast.

## Files Changed
- **New:** `frontend/src/api/queryClient.js` — exports the shared `QueryClient` (`staleTime: 30_000`, `retry: 1`, `refetchOnWindowFocus: false`).
- **New:** `frontend/src/api/queryKeys.js` — key factory: `currentEmployee(id)`, `employees()`, `profileRequests()`, `dashboardSummary()`.
- **Modified:** `frontend/package.json` + `frontend/package-lock.json` — added `@tanstack/react-query ^5.101.2`.
- **Modified:** `frontend/src/main.jsx` — wrapped `<App/>` in `<QueryClientProvider client={queryClient}>` inside `<React.StrictMode>`.
- **Modified:** `frontend/src/hooks/useHrData.js` — core rewrite: `useState`+`loadData` → four `useQuery` reads (enabled-gated) + four `useMutation` writes (invalidate-on-success); `loadData` removed; `resetData` now also purges the query cache; `route`/`selectedEmployee`/`routeTo`/`openEmployee` kept as local state/imperative.
- **Modified:** `frontend/src/App.jsx` — removed both `await loadData(...)` calls (restoreSession effect + handleLogin) and the `loadData` destructure; everything else unchanged.
- **Modified:** `frontend/src/hooks/useHrData.test.js` — rewritten for the Query API (`QueryClientProvider` wrapper, fresh `QueryClient` per test with `retry: false`, `waitFor` assertions).
- **Untouched (confirmed):** the `api/` transport layer (`client.js`, `index.js`, `hrApi.js`, `mockApi.js`), routing, feature pages, styles, business logic.

## Commands Run
```bash
npm --prefix frontend install @tanstack/react-query   # -> ^5.101.2, 0 vulnerabilities
cd frontend && npm run lint     # 0 errors, 12 exhaustive-deps warnings (all pre-existing)
cd frontend && npm test         # 7 files, 37 tests passed (incl. rewritten useHrData.test.js: 5)
cd frontend && npm run build     # built in ~135ms
```

## Test / Build Results
- **Frontend build (`npm run build`):** PASS — 144 modules, built in ~135ms; bundle 444.48 kB (gzip 116.43 kB).
- **Frontend tests (`npm test`):** PASS — 37/37 across 7 files; the rewritten `useHrData.test.js` runs 5 tests (HR full list; non-viewer derives from currentEmployee with no `employees.list` call; failed profileRequests → `[]`; createEmployee → mutation + refetch + toast; reviewProfileRequest → mutation + refetch + toast).
- **Lint (`npm run lint`):** PASS — 0 errors, 12 warnings, all pre-existing `react-hooks/exhaustive-deps` in feature pages (leave/overtime/payroll/tickets/etc.). None originate from `useHrData.js`, `App.jsx`, or the new files. (Audit expected ~13; the count is 12 because the imperative `loadData` removed from App carried no dep warning — no new warnings introduced.)
- **Manual (preview `frontend-mock`, port 5200, desktop):**
  - HR quick-login → dashboard summary (28 headcount, 5 approvals, Leave/OT tiles), current-employee card (นายสมชาย ศรีสุข / GLR-1021), and recent profile-requests all populate from Query. Screenshot captured (populated HR dashboard).
  - Employees list → 12 rows (GLR-1001…1012, paginated view of 28) populate from the `employees()` query.
  - Approve a pending profile request → the row flips to "อนุมัติแล้ว", the approve-button count drops 4→3, and the sidebar "Profile requests" pending badge drops 4→3 (App-level `pendingCount` is derived from the invalidated+refetched Query data). No ConfirmDialog in this flow; mutation fired directly.
  - **Logout → login as Employee (different role):** returns to login, then Employee dashboard greets "คุณภูมิ" with NO trace of the previous HR data (no "28 Headcount", no "สมชาย") — confirms `resetData` cache purge. Employee (non-viewer) sees no Employees-list nav; `employees` derives from `currentEmployee` (the "no `employees.list`" path is asserted by the unit test).
  - Zero console errors throughout; no visible double-fetch flicker under StrictMode.

## Decisions Made
- **`resetData` purges by prefix key `['currentEmployee']`** (not `currentEmployee(user?.employeeId)`) so it removes the cached record regardless of which id it was keyed under — robust against the logout ordering in `App.handleLogout` (`setUser(null)` runs before `resetData()`), guaranteeing no stale currentEmployee bleed for the next role. The other three keys are removed by their exact factory keys. (Plan permits `removeQueries` for the four keys or `queryClient.clear()`; prefix-removal of these four is the minimal, targeted choice.)
- **`updateEmployee` invalidates both `employees()` and `currentEmployee(id)`** (per plan) rather than hand-patching state — invalidating `currentEmployee(id)` covers the "edited self" case the old imperative code patched manually. `selectedEmployee` (local UI state) is still set from the mutation response so the detail page updates immediately.
- **Mutation signatures preserved** via thin wrappers: `createEmployee(payload)`, `updateEmployee(id, payload)`, `createProfileRequest(payload)`, `reviewProfileRequest(id, status)` — internally these map to `mutateAsync` with a single arg object, so no call site in `App.jsx`/feature pages changes.
- **`employees` derivation:** non-viewers never mount the list query (`enabled: canViewEmployees === false`); their `employees` comes from a `useMemo` over `currentEmployee`. Viewers read `employeesQuery.data ?? []`. This preserves the exact pre-Query behavior and the "no list call for non-viewers" guarantee.
- **Test uses `createElement` for the `QueryClientProvider` wrapper** (the test file is `.js`, not `.jsx`, so no JSX) — keeps the existing filename/extension and avoids touching the vitest config.
- **No Devtools, no optimistic updates** — invalidate-on-success only, per plan (simpler to review).

## Assumptions
- `@tanstack/react-query` v5 is React 18 compatible and passes `npm audit --audit-level=moderate` (CI gate). **Confirmed:** install reported `found 0 vulnerabilities`; resolved version `^5.101.2`.
- The mock API is in-memory (no HTTP), so the "no `employees.list` for a non-viewer" guarantee is verified by the unit test rather than by inspecting network traffic in the preview.

## Known Risks
- `useHrData` return shape MUST stay stable (minus `loadData`) or `App.jsx` breaks — verify every field App destructures still exists.
- Login/session-restore flow change: `loadData` awaited data before; now the dashboard renders while queries load. Dashboards already tolerate `null`/`[]` — confirm no empty-flash/veil regression.
- StrictMode double-mount: Query dedups, but confirm no visible double-fetch flicker.
- Logout must purge the cache (`resetData`/`queryClient`) so a different role logging in sees no stale data.

## Things Not Finished
- **Nothing in scope is outstanding.** All plan items done, all three gates green, all manual scenarios verified.
- **Deliberately not done (out of scope, per plan):** HR-core feature-page fetching (leave/overtime/attendance/payroll still use their own `useEffect` loads) → branch 4. URL routing / retiring the `App.jsx` ternary → branch 5. No React Query Devtools, no optimistic updates.
- **Prerequisite note:** the handoff's "Step 0 — re-land #117" was NOT performed here. This task's charter said the branch was already created off `main` and to work the current tree; I did not branch/cherry-pick. If `main` still lacks the #117 card fallback, that re-land is a separate, non-conflicting piece of work (different files) to sequence before/around merge — flag for the reviewer/owner.
- **Bundle size:** JS bundle is 444 kB (gzip 116 kB); TanStack Query adds ~2 packages. No code-splitting done (not in scope).

## Recommended Next Agent
Claude Opus review — live-verify (like branches 1–2): scope, stable return shape, cache-purge-on-logout, no stale bleed, StrictMode behavior, lint/test/build. Then branch 4 (HR-core feature pages) per the audit.

## Exact Next Prompt

```
You are the Claude Opus REVIEWER for branch `refactor/tanstack-query-core` on GL-R-ERP
(/Users/ploy_warit/Desktop/GL-R-ERP). The implementation is UNCOMMITTED in the working tree.

Read first:
- docs/agent-handoffs/00_MASTER_CONTEXT.md, 01_STABILIZATION_AUDIT.md (§7 P1-1, §8),
  and 04_refactor-tanstack-query-core.md (this file, filled in).
- The plan at /Users/ploy_warit/.claude/plans/atomic-marinating-otter.md — "Branch 3 — implementation".

Run `git status` (expect the change set in the handoff, uncommitted) and `git diff` to review.

Verify the implementation (do NOT re-implement except tiny safe fixes):
1. Scope: only the 8 files in the handoff changed. The api/ transport layer, routing, feature
   pages, styles, and business logic are untouched. No Devtools/optimistic updates added.
2. Return-shape stability: every field App.jsx destructures from useHrData still exists except
   `loadData` (removed). `createEmployee/updateEmployee/createProfileRequest/reviewProfileRequest`
   keep their old call signatures.
3. Correctness of the four reads' `enabled` gating and queryFns (esp. the non-viewer `employees`
   useMemo derivation and the tolerant `.catch` fallbacks for profileRequests/dashboardSummary).
4. Mutations invalidate the right keys on success (employees; employees+currentEmployee(id);
   profileRequests x2) and toast with the preserved wording (approved=success else info).
5. `resetData` purges the cache so a different role logging in sees no stale bleed.
6. Re-run the gates: `cd frontend && npm run lint && npm test && npm run build`
   (expect 0 lint errors, 12 pre-existing warnings; 37 tests pass; clean build).
7. Live-verify with the preview tooling (frontend-mock, port 5200, desktop ~1280), like branches 1–2:
   HR login → dashboard/employees/requests populate; approve a request → row + sidebar pending
   badge update; logout → login as Employee → NO stale HR data, non-viewer sees no employees list.
   (Attendance page white-screens in mock mode — pre-existing missing api.attendance mock, unrelated.)

If it passes, approve for merge. Then the next roadmap step is branch 4
(`refactor/query-employees-requests`) per the audit §8. Do not merge branches out of order;
also confirm with the owner whether the #117 card-fallback re-land (handoff "Step 0") has
landed on `main` — it was NOT done on this branch.
```

---

## Review Verdict (Claude Opus 4.8, reviewer) — APPROVED

Reviewed the full diff and independently re-verified. **Approved, no changes needed.** Committed and opened as a PR to `main`.

- **Scope:** exactly the 8 expected files. `api/` transport layer, routing, feature pages, styles, business logic untouched. No Devtools/optimistic updates.
- **Return-shape stability:** confirmed — `App.jsx` diff is only the removal of the two `await loadData(...)` calls + the `loadData` destructure. Every other field is unchanged; mutation call signatures preserved.
- **Correctness (read):** `routeTo` is a hoisted function declaration, so its use inside `createEmployee.onSuccess` before its textual definition is safe. `updateEmployee` invalidating `currentEmployee(id)` correctly refetches only when editing self (id === user.employeeId); a no-op otherwise — matches the old hand-patch intent. Non-viewer `employees` derivation and the tolerant `.catch` fallbacks are intact.
- **Gates (re-run by me):** lint 0 errors / 12 pre-existing warnings; `npm test` 37/37 (rewritten `useHrData.test.js` is stronger than the original — it asserts the invalidate→refetch cycle, not just local patching); build clean (bundle +36 KB gzip for TanStack Query, expected).
- **Live-verified (frontend-mock, desktop), beyond the implementer's pass:**
  - HR login → dashboard summary + current-employee + employees + profile-requests all populate from Query; console clean.
  - Approve a profile request → approved rows 1→2 **and** sidebar pending badge **4→3** (App-level `pendingCount` derived from the invalidated+refetched query) — the mutation→invalidate→refetch→derived-state chain works end to end.
  - **Cache-purge (highest risk):** logout → login as Employee (คุณภูมิ / GLR-1009) showed **zero** stale HR bleed — no `สมชาย`, no headcount `28`, correct employee-only nav (no Employees / no Profile-requests-review), own data present. `resetData`'s prefix `removeQueries` is correct.
  - No console errors; no StrictMode double-fetch flicker.
- **Decision on `resetData` prefix-removal of `['currentEmployee']`:** good call — it purges the record regardless of which id it was keyed under, robust to the `setUser(null)`-before-`resetData()` ordering in `handleLogout`.

**Follow-ups (not blockers):** #117 re-land (Step 0) is handled separately in PR #118 (independent files). Branch 4 (`refactor/query-employees-requests`) is next per audit §8 — which, given this branch already migrated the shared employees/requests layer, becomes the HR-core **feature pages** (leave/overtime/attendance/payroll).
