# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
Migrate the two HR-core request-approval pages — **LeavePage** and **OvertimePage** — off imperative `useEffect`+`loadX` fetching onto TanStack Query (`useQuery`/`useMutation` + invalidation), reusing the branch-3 infra. Also **add the missing `overtime` mock** to `mockApi.js` so overtime is verifiable/demoable. Roadmap "branch 4". Full plan: `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md`.

## Branch
`refactor/query-leave-overtime` (off `main`; ALREADY created and checked out for you)

## Base Commit
`29c7e26` (main tip — includes branches 1–3)

## Current Commit
Working tree is **uncommitted** (per instructions — left for review). Base commit `29c7e26`. All four source files modified in the working tree; nothing staged or committed.

## Agent / Model Used
Implementer: Codex GPT-5.3-Codex · Reviewer: Claude Opus _(per audit §9 + owner directive: Opus plans, Codex executes)_

## Scope

### In Scope
- Extend `frontend/src/api/queryKeys.js`: `leaveRequests(filters)`, `leaveBalances(employeeId, year)`, `leaveEmployees()`, `leaveTypes()`, `overtimeRequests(filters)`, `overtimeEmployees()` (follow the existing factory style).
- `frontend/src/features/leave/LeavePage.jsx`: reads → `useQuery` (reference data, list keyed on **appliedFilters**, dependent balances); mutations (create/approve/reject/cancel) → `useMutation` + invalidate `leaveRequests` **and** `leaveBalances`. Preserve manual-submit UX (list refetches only on submit, not per keystroke — use a separate `appliedFilters` state), form-default seeding from reference data, all `useMemo`s, and the `{ user, currentEmployee, showToast }` props contract.
- `frontend/src/features/overtime/OvertimePage.jsx`: same pattern, simpler (no balances). Preserve the `workDate` datetime-sync form logic.
- `frontend/src/api/mockApi.js`: add an `overtime:` block mirroring the existing `leave:` block (~lines 1089–1229) + a small seed fixture. Match the response envelope/fields `OvertimePage` + `hrApi.js` expect (cross-check `hrApi.js` overtime wrapper + `routes.js` ~lines 23–30).

### Out of Scope
- **PayrollPage.jsx / AttendancePage.jsx** and their mocks — deferred to later branches. Do NOT touch.
- The shared `useHrData` layer (branch 3), routing, styles, and any leave/overtime business logic (server computes payable minutes/quotas; the page only displays). Data-layer refactor only.
- No optimistic updates (invalidate-on-success only). No Devtools in the bundle. Do not modify `queryClient.js`, `main.jsx`, or the `api/` transport (`client.js`/`index.js`/`hrApi.js`).

## Implementation notes
- **appliedFilters pattern:** keep the live form `filters` state for the inputs; add `appliedFilters` set by `submitFilters()`; key the list `useQuery` on `appliedFilters` so typing in the date/select fields does NOT refetch — only the submit button does. Mutations invalidate `...Requests(appliedFilters)`.
- **Leave balances** is a dependent query: `enabled: !!form.employeeId`, keyed on `(form.employeeId, yearFrom(form.startDate))`.
- Move mutation error handling into `onError` (`showToast('error', ...)`), keep the existing success wording in `onSuccess`.
- Use the list query's `isLoading`/`isFetching` for the existing `loading` UI.

## Files Changed
- `frontend/src/api/queryKeys.js` — added `leaveRequests(filters)`, `leaveBalances(employeeId, year)`, `leaveEmployees()`, `leaveTypes()`, `overtimeRequests(filters)`, `overtimeEmployees()` following the existing factory style (`+6` lines).
- `frontend/src/features/leave/LeavePage.jsx` — migrated all reads (`leaveEmployees`, `leaveTypes`, `leaveRequests(appliedFilters)`, dependent `leaveBalances`) to `useQuery`; create/approve/reject/cancel to `useMutation` invalidating BOTH `leaveRequests(appliedFilters)` and `leaveBalances(...)`. Added `appliedFilters` state + `yearFrom()` helper.
- `frontend/src/features/overtime/OvertimePage.jsx` — same pattern (no balances): `overtimeEmployees`, `overtimeRequests(appliedFilters)` reads; create/approve/reject/cancel mutations invalidating `overtimeRequests(appliedFilters)`. `workDate` datetime-sync form logic left intact.
- `frontend/src/api/mockApi.js` — added `overtime:` block (`employees`/`list`/`create`/`approve`/`reject`/`cancel`) + a 2-row seed fixture (`db.overtimeRequests`) + `buildOvertimeRecord()` / `overtimeMinutesBetween()` helpers, mirroring the `leave:` block (`+194` lines).

## Commands Run
```bash
cd frontend && npm run lint    # 0 errors, 9 warnings (all pre-existing, other files)
cd frontend && npm test        # 37 passed (7 files)
cd frontend && npm run build   # built in ~105ms, 447.88 kB bundle
# preview: frontend-mock on port 5200, HR quick-login, manual verification (see below)
```

## Test / Build Results
- Frontend build (`npm run build`): **PASS** — 144 modules, `dist/assets/index-*.js` 447.88 kB (gzip 117 kB).
- Frontend tests (`npm test`): **PASS** — 37/37 across 7 files (unchanged; these pages have no unit tests, none added).
- Lint (`npm run lint`): **PASS** — 0 errors, 9 warnings, all pre-existing `exhaustive-deps` in other files (attendance/ceoSettings/commissions/dashboard/deposits/payroll/tickets). LeavePage/OvertimePage contribute **zero** warnings (the new `?? []` fallbacks were wrapped in `useMemo` so no new logical-expression warnings were introduced).
- Manual (preview, HR login, mock mode):
  - **Leave:** page loads; balances (3 cards) load from the dependent query once the acting employee seeds; typing new from/to dates does **NOT** refetch the list (stayed 0 rows); clicking ค้นหา (submit) refetched → 2 seed rows appeared → **manual-submit UX confirmed**; approve on the SUBMITTED row flipped it to อนุมัติแล้ว and updated stats + balances (cascade); submitting a new leave request grew the list 2→3, added a SUBMITTED row, and dropped remaining-quota 39→38 (both `leaveRequests` + `leaveBalances` invalidations fired). No console errors.
  - **Overtime:** page now **LOADS** (previously white-screened in mock) — header/stats/filter/form/table all render; list populated 1 seed row for the current month; submitting an OT request grew the list 1→2 with a new SUBMITTED row; approve/reject page buttons are gated by the page's pre-existing `managesRequest()` (an HR user who is not a direct/division manager sees no approve button — unchanged behavior). The `overtime.approve` mock was exercised directly: returns `{ request }` with status→APPROVED and payableMinutes computed (120min × 1.5 = 180), full envelope shape-compatible with the page. Screenshot captured (stats show 2 total / 1 pending / 1 approved / 3 ชม. payable). No console errors anywhere in the session.

## Decisions Made
- **`appliedFilters` initialized to the same object as `filters`** so the list query fires on mount with the default month range, matching the old `loadRequests()` on-mount behavior. `submitFilters()` only calls `setAppliedFilters(filters)`; the list `useQuery` is keyed on `appliedFilters`, so filter-input keystrokes never refetch.
- **Loading UI** uses `requestsQuery.isLoading || requestsQuery.isFetching` so the "กำลังโหลด" empty-state still shows during submit-triggered refetches (preserves the old `loading` flag semantics).
- **`saving`** is derived from the four mutations' `isPending` (OR'd) so every button's `disabled={saving}` keeps its original meaning without a manual `useState`.
- **Error toasts** for reads moved into small `useEffect(() => { if (query.error) showToast(...) }, [query.error, showToast])` blocks, preserving the exact original messages of the imperative `loadX` catch blocks; mutation errors moved into `onError`.
- **Form-default seeding** preserved via an effect keyed on `[employeesQuery.data, leaveTypesQuery.data]` (overtime: `[employeesQuery.data]`) that only patches the form when the value actually changes (returns `current` otherwise) to avoid render loops.
- **Wrapped `query.data ?? []` in `useMemo`** on both pages so the arrays are referentially stable — this avoids the new `exhaustive-deps` warnings the `?? []` fallbacks would otherwise add to the existing `useMemo`s, keeping the lint delta at zero.
- **Overtime mock authorization** reuses `canReviewLeave(user, employeeId)` (HR/admin or the employee's ฝ่าย manager) for approve/reject/cancel — the same helper the leave mock uses — since there is no separate overtime-review permission and the two pages share the manager model. Approve computes `payableMinutes = round(actualMinutes × multiplier)` where multiplier is 3 for HOLIDAY else 1.5, defaulting `actualMinutes` to `plannedMinutes` (server computes this for real; mock just needs a plausible non-null value the page can render).
- **Refresh buttons** now call `requestsQuery.refetch()` instead of the removed `loadRequests`.

## Assumptions
- The `overtime` mock only needs to satisfy what `OvertimePage`/`hrApi.js` call; its data shape mirrors the leave mock + the fields listed in the plan. **Confirmed against `hrApi.js` (`api.overtime`: list/employees/create/approve/reject/cancel) and `routes.js` (lines 23–30).** Method names + `{ requests }` / `{ employees }` / `{ request }` envelopes match; the page renders `id, employeeId, employeeName, employeeCode, workDate, plannedStartAt, plannedEndAt, plannedMinutes, dayType, reason, status, actualMinutes, payableMinutes, reviewerNote, calculationNote, requestedAt, managerEmployeeId` — all present in the mock record.
- `canReviewLeave` is reused as the overtime review gate in the mock (no dedicated overtime permission exists); both pages share the ฝ่าย-manager model.

## Known Risks
- Manual-submit UX must be preserved — verify the list does NOT refetch on each filter keystroke (only on submit).
- Leave's post-mutation cascade must invalidate BOTH `leaveRequests` and `leaveBalances` (today's `Promise.all([loadRequests, loadBalances])`).
- Overtime page currently white-screens in mock mode; after adding the mock it must load cleanly.
- Keep mock/real response shapes identical so the page behaves the same on the real backend.

## Things Not Finished
- Nothing in scope is incomplete. All four files migrated + the overtime mock added; lint/test/build green; both pages preview-verified.
- **Not done (out of scope, by design):** PayrollPage/AttendancePage query migration + their mocks (deferred to later branches); no optimistic updates; no Devtools; no changes to `useHrData`, transport, routing, or styles.
- **Left uncommitted** per instructions — reviewer to verify then commit/merge.
- The reviewer could not exercise the OT approve/reject **page buttons** live because the HR demo user is not a direct/division manager of the seeded requesters, so `managesRequest()` (unchanged page logic) hides those buttons for HR. The approve **mock** was verified directly and the Leave page's identical approve/reject flow was verified through the UI. To exercise the OT buttons through the UI, log in as a manager whose division owns a seeded OT request, or temporarily seed an OT request whose `managerEmployeeId` matches the logged-in user.

## Recommended Next Agent
Claude Opus review — live-verify (like branches 1–3): both pages load and behave; manual-submit preserved; leave balances cascade; overtime mock works; lint/test/build. Then branch 5 (routing) or the deferred attendance/payroll query branches.

## Exact Next Prompt

```
You are the Claude Opus reviewer for branch `refactor/query-leave-overtime` (GL-R-ERP, branch 4 of the stabilization roadmap). The implementer left the work UNCOMMITTED in the working tree off `main` @ 29c7e26. Do NOT re-implement; review + live-verify only, then decide commit/merge.

Read first: docs/agent-handoffs/00_MASTER_CONTEXT.md, 01_STABILIZATION_AUDIT.md, 05_refactor-query-leave-overtime.md (this file), and the plan /Users/ploy_warit/.claude/plans/atomic-marinating-otter.md.

Changed files (git diff): frontend/src/api/queryKeys.js, frontend/src/features/leave/LeavePage.jsx, frontend/src/features/overtime/OvertimePage.jsx, frontend/src/api/mockApi.js.

Verify:
1. `cd frontend && npm run lint && npm test && npm run build` — expect 0 lint errors (9 pre-existing warnings in OTHER files), 37 tests pass, build ok.
2. Preview (frontend-mock, port 5200), HR quick-login (button text contains "HR" + "พนักงานทั้งหมด"):
   - Leave: list loads; change the date range and confirm the list does NOT refetch until you click ค้นหา (submit); select an employee → balances load; submit a leave request → list grows + remaining-quota drops (both leaveRequests + leaveBalances invalidated); approve/reject a SUBMITTED row via the ConfirmDialog → status + stats update. No console errors.
   - Overtime: page LOADS (was white-screen); list populates from the new mock; submit an OT request → appears. NOTE the OT approve/reject page buttons are gated by pre-existing managesRequest() and are hidden for an HR user who is not a manager of the requester — to exercise them, log in as a manager of a seeded requester OR seed an OT request with managerEmployeeId == the logged-in user. The approve mock itself returns { request } with status APPROVED + payableMinutes.
3. Focus review points: (a) list query keyed on appliedFilters NOT filters (no refetch-on-keystroke); (b) leave mutations invalidate BOTH leaveRequests(appliedFilters) AND leaveBalances(form.employeeId, yearFrom(form.startDate)); (c) overtime workDate datetime-sync form logic untouched; (d) mock response envelopes match hrApi.js/routes.js and stay shape-compatible with the real backend; (e) props contract { user, currentEmployee, showToast } and all useMemos unchanged; (f) no optimistic updates, no Devtools, no changes to queryClient.js/main.jsx/transport/useHrData.

If green: commit (one focused commit) and hand to merge. Then the next roadmap item is branch 5 (`refactor/frontend-routing`).
```

---

## Review Verdict (Claude Opus 4.8, reviewer) — APPROVED

Reviewed the full diff and independently re-verified. **Approved, no changes needed.** Committed and opened as a PR to `main`.

- **Scope:** clean — exactly the 4 planned files. `queryClient.js`, `main.jsx`, the `api/` transport, `useHrData`, routing, styles, PayrollPage/AttendancePage all untouched. No optimistic updates, no Devtools.
- **Correctness (read):**
  - Leave list `useQuery` is keyed on `appliedFilters` (line 121), and `updateFilter` only mutates `filters` while `submitFilters` sets `appliedFilters` (207–225) — the manual-submit UX is structurally guaranteed, not incidental.
  - `invalidateLeave()` invalidates BOTH `leaveRequests(appliedFilters)` and `leaveBalances(form.employeeId, balancesYear)` (200–205); every Leave mutation calls it — faithfully reproduces the old `Promise.all([loadRequests, loadBalances])` cascade.
  - Dependent balances query `enabled: !!form.employeeId` (139); form-default seeding from reference data preserved via a landing effect (186–198); AUTO_REJECTED quota path preserved (232–234); `saving` derived from the four mutations' `isPending` (271).
  - Overtime mirrors the pattern (no balances); `workDate` datetime-sync form logic untouched.
  - New overtime mock: `list/employees/create/approve/reject/cancel` return `{requests}`/`{request}`/`{employees}` via `buildOvertimeRecord` (enriches employeeCode/Name + managerEmployeeId/Name — the fields `managesRequest()` needs), shape-compatible with `hrApi.js`/`routes.js`.
- **Gates (re-run by me):** lint 0 errors / 9 warnings (all pre-existing, other files — the migration actually removed a few); `npm test` 37/37; build ok (bundle 447.88 kB, +3 KB for the mock).
- **Live-verified (frontend-mock, HR login), beyond the implementer's pass:**
  - **Overtime now LOADS** (was a white-screen in mock) — full header/stats/filter/form/table render, 1 seed row, zero console errors. Screenshot captured.
  - **Leave manual-submit proven behaviorally:** typed a wide 2026 date range into the filter WITHOUT submitting → list stayed at 0 rows (no keystroke refetch); clicked ค้นหา → **2 rows appeared** (submit-only refetch). Matches the code.
  - No console errors on either page.
- **Noted (not a defect):** OT approve/reject page buttons are hidden for the HR demo user by the pre-existing `managesRequest()` manager gate (unchanged behavior) — the approve mock and the identical Leave approve/reject UI flow were verified instead.

**Next:** branch 5 (`refactor/frontend-routing`), or the deferred attendance/payroll Query branches.
