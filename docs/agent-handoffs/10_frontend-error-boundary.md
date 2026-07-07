# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
P1-5 (the last P1): add a global React **`ErrorBoundary`** so a thrown component shows a friendly fallback instead of white-screening the whole app. Placed top-level (catastrophic crash → full-screen fallback) AND route-level around `<Outlet/>` (a page crash keeps the nav + auto-recovers on navigation). Frontend-only, **no new deps** (hand-rolled class boundary). Full plan: `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md` ("Branch 9" section is the exact spec).

## Branch
`feat/frontend-error-boundary` (off `main`; ALREADY created and checked out for you)

## Base Commit
`4d1c1c2` (main tip)

## Current Commit
`4d1c1c2` (main tip; all changes below are uncommitted in the working tree, left for review per instructions)

## Agent / Model Used
Implementer: Claude Sonnet · Reviewer: Claude Opus

## Scope

### 1. New `frontend/src/components/common/ErrorBoundary.jsx`
Class component: state `{error:null}`; `static getDerivedStateFromError(error)` → `{error}`; `componentDidCatch(error, info)` → `console.error('UI ErrorBoundary caught:', error, info)` (comment: future Sentry hook attaches here, no dep now); `reset = () => this.setState({error:null})`. `render()`: on error → `props.fallback?.({error, reset})` if given else the default fallback; else `props.children`.
Default fallback (reuse `.panel`/`.empty-state`/`.primary-button`/`.secondary-button`): Thai+English "เกิดข้อผิดพลาด / Something went wrong" heading + short line, a "ลองใหม่ / Try again" button (`reset`) and a "โหลดหน้าใหม่ / Reload" button (`window.location.reload()`). Show `error.message` ONLY in `import.meta.env.DEV`. Fallback must NOT depend on Query/router context.

### 2. Placement (same component, two spots)
- `frontend/src/main.jsx`: wrap `<App/>` in `<ErrorBoundary>` (inside `<BrowserRouter>`).
- `frontend/src/components/layout/AppShell.jsx`: wrap the `<Outlet/>` (line ~147, inside `.content-scroll`) — `<ErrorBoundary key={location.pathname}><Outlet/></ErrorBoundary>`. `location` is already in scope (`useLocation()`), so keying on `location.pathname` auto-recovers on navigation.

### 3. Test `frontend/src/components/common/ErrorBoundary.test.jsx`
Vitest + testing-library (mirror `DataTable.test.jsx`): a throwing child → fallback heading renders + error doesn't propagate (spy/silence `console.error` for the throwing cases, restore after); a healthy child → renders normally; (optional) `reset` recovers.

### Out of Scope
- Frontend-only, NO new dependencies (no `react-error-boundary`). No routing/data/business-logic changes. Don't touch components beyond the two wrap points. Prod fallback must NOT show stack traces/internals (dev-only `error.message`). Keep it small.

## Files Changed
- New: `frontend/src/components/common/ErrorBoundary.jsx` — class component (`state = { error: null }`, `getDerivedStateFromError`, `componentDidCatch` logging via `console.error` with a comment marking the future Sentry/report hook attach point, `reset` arrow method). `render()` returns `props.children` when healthy; on error, calls `props.fallback({ error, reset })` if `fallback` is a function, else renders the co-located `DefaultErrorFallback` (reuses `.panel` + `.empty-state` classes, Thai+English heading, dev-only `error.message`, "ลองใหม่ / Try again" `.secondary-button` calling `reset`, "โหลดหน้าใหม่ / Reload" `.primary-button` calling `window.location.reload()`).
- New: `frontend/src/components/common/ErrorBoundary.test.jsx` — Vitest + testing-library, mirrors `DataTable.test.jsx` pattern (`globalThis.React = React`). 3 tests: (1) throwing child renders the fallback heading and doesn't propagate (console.error spied/restored via `beforeEach`/`afterEach`), (2) clicking a custom `fallback` render-prop's reset button recovers a now-healthy child, (3) a healthy child renders normally with no fallback text present.
- Modified: `frontend/src/main.jsx` — imported `ErrorBoundary` and wrapped `<App/>` with it, inside `<BrowserRouter>` (so provider order is `QueryClientProvider > BrowserRouter > ErrorBoundary > App`).
- Modified: `frontend/src/components/layout/AppShell.jsx` — imported `ErrorBoundary` and wrapped the routed `<Outlet/>` at the `.content-scroll` div: `<ErrorBoundary key={location.pathname}><Outlet /></ErrorBoundary>`, using the already-in-scope `location` from `useLocation()`. No other lines in this file changed.
- No changes to `styles.css` — `.panel` + `.empty-state` + `.primary-button`/`.secondary-button` were sufficient as-is.

## Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
# Manual preview verification (see below), including a temporary throw in LeavePage.jsx that was reverted before finishing.
```

## Test / Build Results
- `npm run lint`: PASS — 0 errors, 9 warnings, all pre-existing (`react-hooks/exhaustive-deps` in unrelated files: AttendancePage, CeoSettingsPage, CommissionPage, TicketDashboard, DepositNoticePage, PayrollPage, TicketDetailPage, TicketListPage). No warnings in any new/changed file.
- `npm test`: PASS — 8 test files, 49 tests, all green (incl. the 3 new `ErrorBoundary.test.jsx` tests).
- `npm run build`: PASS — Vite build succeeded in ~145ms, no errors (`dist/index.html`, `dist/assets/index-*.css` 30.5kB, `dist/assets/index-*.js` 491.6kB).
- Manual (preview, `frontend-mock` port 5200):
  - Logged in via HR quick-login button ("HR" + "พนักงานทั้งหมด · อนุมัติคำขอ") → dashboard ("สวัสดี, คุณชาย") loaded normally, no console errors, boundary invisible.
  - Added a temporary `throw new Error('boom');` at the top of `LeavePage`'s body (right after the function signature, before other hooks/logic) → reloaded → navigated to Leave (`/leave`): the inline fallback rendered ("เกิดข้อผิดพลาด / Something went wrong", dev-only "boom" message, both buttons) **with the sidebar and topbar still fully present and functional** (confirmed via accessibility snapshot + screenshot).
  - Navigated to Dashboard (`/`) → the dashboard rendered fully and normally, confirming the `key={location.pathname}` remount recovers automatically on navigation without needing "Try again"/"Reload".
  - Reverted the temporary throw in `LeavePage.jsx`, re-navigated to Leave → full normal Leave page rendered ("จัดการการลา" with quotas/filters/request form); verified via `document.body.textContent` containing no "เกิดข้อผิดพลาด" fallback text.
  - `git status` after revert shows only the intended files (no `LeavePage.jsx` in the diff).

## Decisions Made
- Used `Component` from `react` (named import) rather than `React.Component`, matching this codebase's convention of named imports over the `React` namespace default in most files (though `DataTable.test.jsx`'s `globalThis.React = React` pattern was still followed in the test file for consistency/safety with the classic JSX runtime assumption it seems to guard against).
- `DefaultErrorFallback` is a plain function component defined in the same file (not exported) rather than inlined JSX in `render()`, for readability — this is an internal implementation detail, not a new public API.
- Combined `.panel` and `.empty-state` classes on the same wrapping `<div>` for the default fallback: `.panel` gives the card background/border/shadow/padding, `.empty-state` gives the centered-content grid layout — no new CSS was needed.
- The optional "reset recovers" test was implemented against a custom `fallback` render-prop (rather than the default fallback) so the test can deterministically flip the throwing condition via component state at the same time as calling `reset`, avoiding flakiness from remounting a component that would immediately throw again if reset alone were tested against the always-throwing `Bomb` component.
- `componentDidCatch`'s comment about the future Sentry/report hook is a plain code comment, not a TODO/FIXME marker, since no ticket exists for it yet.

## Assumptions
- Error boundaries only catch render/lifecycle errors (not event handlers / async) — expected React behavior; the fallback + reload covers the white-screen case the audit calls out.

## Known Risks
- The route-level boundary must key on `location.pathname` so navigation recovers (otherwise a crashed page stays crashed).
- Don't leak internals in prod (dev-only `error.message`).
- StrictMode double-invokes render in dev — the boundary must still behave (it will; just be aware when eyeballing console).

## Things Not Finished
- Nothing outstanding against the Branch 9 spec. All three deliverables (component, two wrap points, test) are implemented and verified; lint/test/build are green; the temporary manual-verification throw was reverted.
- Not done (intentionally out of scope per the plan): no Sentry/error-reporting integration (comment left as a marker only), no new CSS, no changes to any other page/component.

## Recommended Next Agent
Claude Opus review — live-verify the fallback shows on a thrown page WITHOUT killing nav, auto-recovers on navigate, prod hides internals; lint/test/build. **This closes out all P1 items** — next is P2 (OpenAPI, form/table architecture, audit-log coverage, docs cleanup) or a v0.1.0 release/DoD pass.

## Exact Next Prompt

```
You are the reviewer agent (Claude Opus) for branch `feat/frontend-error-boundary` in
/Users/ploy_warit/Desktop/GL-R-ERP. This closes out all P1 roadmap items (P1-5, the last one).

Read first: docs/agent-handoffs/00_MASTER_CONTEXT.md, docs/agent-handoffs/10_frontend-error-boundary.md
(this handoff, now filled in), and /Users/ploy_warit/.claude/plans/atomic-marinating-otter.md
("Branch 9" section — the exact spec this branch was implemented against).

Do NOT re-implement — this is a review pass. Per CLAUDE.md, reviewer agents only make tiny,
safe fixes (typos, obvious one-liners); anything larger goes back to an implementation branch.

Verify:
1. Read the diff: `git status` and `git diff -- frontend/src/main.jsx frontend/src/components/layout/AppShell.jsx`,
   plus the new files `frontend/src/components/common/ErrorBoundary.jsx` and
   `frontend/src/components/common/ErrorBoundary.test.jsx`. Confirm it matches the Branch 9 spec exactly
   (class component, getDerivedStateFromError/componentDidCatch/reset, default fallback reusing
   .panel/.empty-state/.primary-button/.secondary-button, dev-only error.message, wrap points in
   main.jsx inside BrowserRouter and AppShell.jsx keyed on location.pathname).
2. Re-run `cd frontend && npm run lint && npm test && npm run build` and confirm green (the handoff
   file records lint PASS with 9 pre-existing unrelated warnings, test PASS 49/49, build PASS).
3. Live-verify in the preview (frontend-mock, port 5200): log in via the HR quick-login button,
   confirm the app renders with no console errors and the boundary is invisible. Then temporarily
   add `throw new Error('boom');` at the top of a route page component's render (e.g. LeavePage),
   reload, navigate to that page, confirm the inline fallback renders WITH the sidebar/topbar
   still present and functional, confirm navigating to another route (e.g. Dashboard) auto-recovers,
   then REVERT the temporary throw and confirm `git status` shows no stray changes.
4. Confirm the prod fallback never reveals error.message/stack outside `import.meta.env.DEV`
   (read the component; don't just trust the dev-mode screenshot).
5. Check scope discipline: no new dependencies were added (`git diff -- frontend/package.json
   frontend/package-lock.json` should be empty), no business-logic/routing/data changes beyond the
   two wrap points, no unrelated files touched.

If everything checks out, this branch is ready to merge (PR to main, after which all P1 items are
closed). If you find issues, note them precisely (file + line) and either fix trivially yourself
(per reviewer-agent rules) or send it back with exact repro steps for a fresh Claude Sonnet
implementation-agent turn on this same branch — do not silently patch anything beyond a one-liner.

After review, if merging, follow the standard PR flow (branch off main is already `feat/frontend-error-boundary`;
open a PR, do not force-push, do not merge without human approval per repo conventions). Update this
handoff file's "Recommended Next Agent" section to point to whatever P2 work or v0.1.0 release/DoD
pass comes next, per 00_MASTER_CONTEXT.md.
```

---

## Review Verdict (Claude Opus 4.8, reviewer) — APPROVED

Reviewed the diff and independently re-verified live. Approved; committed + PR'd. **Closes all P1 items.**

- **Scope:** clean — only `main.jsx` + `AppShell.jsx` modified, `ErrorBoundary.jsx`/`.test.jsx` new. The temporary throw the implementer used for its manual check was reverted (LeavePage not in the diff); I confirmed the same after my own throw-injection.
- **Component:** correct — class boundary with `getDerivedStateFromError`/`componentDidCatch` (+ a marked Sentry-hook attach point), `reset`, render-prop-or-default fallback; the default fallback reuses `.panel .empty-state` + buttons and shows `error.message` **only** under `import.meta.env.DEV` (prod hides internals). Top-level wrap in `main.jsx`; route-level `<ErrorBoundary key={location.pathname}>` around `<Outlet/>`.
- **Gates (re-run by me):** `npm run lint` 0 errors, `npm test` **49/49** (incl. 3 new), `npm run build` ok.
- **Live-verified independently (beyond the implementer's pass):** normal HR login/dashboard renders clean, no console errors (boundary invisible). Injected `throw new Error('review-boom')` into `LeavePage` → at `/leave` the inline fallback rendered **with the full sidebar + topbar intact** (screenshot) and the dev-only message shown; navigating to Dashboard **auto-recovered** (path `/`, greeting back, no fallback) via the `pathname`-keyed remount. Reverted the throw; diff clean.

**Next:** all P0 + P1 are done. Remaining is P2 (OpenAPI, form/table architecture, audit-log coverage, docs cleanup) or a v0.1.0 release/DoD pass.
