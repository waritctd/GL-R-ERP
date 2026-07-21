# Agent Handoff

## Task
Group the sidebar navigation into labeled, collapsible sections per role, fix the collapse interaction (previously broken — see Decisions Made), and polish the visual design of the new group headers to match the app's design system. Follow-up to an earlier, never-merged attempt on `feat/nav-menu-grouping` (discarded — see Assumptions); this branch starts fresh from current `main` and supersedes it.

## Branch
fix/sidebar-menu-polish

## Base Commit
6431df0311afa9a7b725a71b110878f1da477677 (origin/main, "feat(commission): gate submission on deal CLOSED_PAID + amount cross-check (#256)")

## Current Commit
Not yet committed.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- Group `AppShell.jsx`'s flat `navItems` (14 items as of this base commit) into 4 labeled, collapsible sections — `งานขาย`/Sales, `บุคคล`/HR, `การเงิน`/Finance & Payroll, `บุคคลของฉัน`/Self-service — rendered by `Sidebar.jsx`, with Dashboard pinned above all groups.
- Add a real role gate (`canViewCatalog`) to `/catalog`, which previously showed for every role once `SALES_ENABLED` — a frontend-only authz-shaped tightening, explicitly recorded here.
- Add the `/pricing-requests` sidebar link (now buildable: the route/permission exist on `main` as of this base commit, unlike when this was first attempted).
- Fix the group-collapse interaction, which was broken in the discarded first attempt (see Decisions Made).
- Polish pass via the `impeccable` skill: corrected color tokens for the dark sidebar rail, added missing `:focus-visible` states, motion for the chevron, and used the codebase's own `CollapsibleSection` pattern (conditional render, not the `hidden` attribute) to avoid a CSS-specificity bug class entirely.

### Out of Scope
- No backend changes. No changes to `mockApi.js` (nav/permission gating is entirely frontend-side; no new API methods).
- Did not touch `/factory-purchase-orders`, `/employee-requests`, or any other existing item's *behavior* — only added a `group` field to each.

## Files Changed
- `frontend/src/api/routes.js`: added `canViewCatalog` permission key (same role set as `canViewTickets`: sales, import, ceo, account, sales_manager), with a comment noting it's a frontend-only gate (no backend role check on `GET /api/catalog` today).
- `frontend/src/app/permissions.js`: added a `PATH_GUARDS` entry for `/catalog` mirroring the new `canViewCatalog` gate.
- `frontend/src/components/layout/AppShell.jsx`: added a `group` field to every nav item (`sales`/`hr`/`finance`/`self`, omitted for `/`); changed `/catalog`'s `show` to require `canViewCatalog`; renamed `/tickets`'s sidebar label from "งานขาย"/"Sales" to "รายการดีล"/"Deal pipeline" (avoids duplicating its own new group header — sidebar-only label, not a page title, confirmed via grep it's used nowhere else); added a new `/pricing-requests` item (icon `clipboard`, gated on `canViewPricingRequestQueue && SALES_ENABLED`), placed in the Sales group after `/price-import`.
- `frontend/src/components/layout/Sidebar.jsx`: rewritten to render `NAV_GROUPS`-ordered collapsible sections. Collapse state is a `Set` of collapsed group keys (default empty = all expanded). Each collapsed group's panel is **conditionally rendered** (`{!isCollapsed ? <div>… : null}`), not hidden via the `hidden` attribute — this sidesteps the CSS bug described below entirely. A `useEffect` keyed on `pathname` force-expands whichever group contains the active route, so a notification deep-link or back/forward navigation into a collapsed group never hides the active-item highlight. Extracted `NavItemLink` and `isItemActive` so both the pinned and grouped items share identical active-match logic (`isActive` OR path-prefix OR `item.match`).
- `frontend/src/styles.css`: added `.nav-group`, `.nav-group-header`, `.nav-group-header-helper`, `.nav-group-panel`, `.nav-group-chevron` (+ `prefers-reduced-motion` override), and `.nav-item:focus-visible` / `.nav-group-header:focus-visible`. Colors pulled from the existing dark-sidebar-safe tokens (`--color-text-faint` default, `--color-surface` on hover, `--color-teal-focus` for the focus ring — the one existing "bright accent for a dark surface" token in this codebase, previously only used on toast-success icons). Typography matches DESIGN.md's Overline spec (11px/800/uppercase/0.04em tracking) — the system's only sanctioned uppercase-tracking usage.

## Commands Run
```bash
git worktree add .claude/worktrees/nav-menu-polish -b fix/sidebar-menu-polish origin/main
cd frontend && npm ci
npm run lint
npm test
npm run build
# manual dev server (VITE_USE_MOCKS=true) for browser verification — preview_start's
# cwd is pinned to the main repo's checked-out branch, not this worktree
VITE_USE_MOCKS=true npx vite --host 127.0.0.1 --port 5202 --strictPort
```

## Test / Build Results
- Frontend lint: **pass** (0 errors, 3 pre-existing warnings in `CommissionPage.jsx`/`PayrollPage.jsx`, unrelated to this change)
- Frontend tests: **pass** — 400/400 (47 test files)
- Frontend build: **pass**
- Backend: not touched, not run — this branch is frontend-only

## Authz Evidence
**UNVERIFIED — mock-only.** Same as the discarded first attempt: this branch adds a frontend-only role gate, `canViewCatalog`, restricting `/catalog` to `sales, import, ceo, account, sales_manager` — previously any authenticated role could reach it once `SALES_ENABLED` (even `employee`). `GET /api/catalog` has no backend role check today either way (confirmed by reading, not a live request), so this is a client-side tightening ahead of / independent from any server-side fix. No backend/API contract changed. No real-DB integration test applies since nothing server-side changed. Browser-verified under `VITE_USE_MOCKS=true` only — not authoritative for authz per CLAUDE.md.

## Decisions Made
- **Started a fresh branch off current `main` rather than resuming `feat/nav-menu-grouping`.** The user reported the collapse didn't work and the styling wasn't polished; rather than debug an uncommitted, un-pushed worktree from a stale base, restarted clean and re-verified from scratch. `feat/nav-menu-grouping`'s worktree still exists on disk with its uncommitted diff but should be treated as abandoned/superseded by this branch.
- **Root cause of "menu still would not collapse"**: the first attempt's collapse panel used `hidden={isCollapsed}` *together with* a Tailwind `className="grid gap-1"` (later `space-y-1`) on the same element. `[hidden] { display: none }` is a low-specificity UA-stylesheet rule; any author-level class that sets `display` (like Tailwind's `.grid`) wins the cascade at equal specificity and silently defeats it. This branch avoids the whole bug class by not using `hidden` at all — the panel is conditionally rendered in JSX instead, mirroring how the codebase's own `frontend/src/components/common/CollapsibleSection.jsx` already does it (`{open ? (<div>…</div>) : null}`). Verified this time with a **real mouse click via the browser tool** (not a synthetic `element.click()` call), which is what actually caught the visual failure in the first attempt would have been caught by real-click testing from the start.
- **"Not polished" root cause**: the first attempt's group-header Tailwind classes (`text-text-faint hover:text-text`) used light-surface tokens on a dark sidebar. `--color-text-faint` (#94a3b8) happens to read fine on the navy rail — but `hover:text-text` resolved to `--color-text` (#0f172a, near-black), which is nearly invisible against the `#0b1220` sidebar background on hover. This branch instead pulled from the actual DESIGN.md-documented dark-sidebar-safe tokens (see `.nav-item small`'s existing code comment confirming `--color-text-faint` is verified-safe specifically on Sidebar Navy) and used `--color-teal-focus`/`--color-surface` for hover/focus, matching the one other place in the app that needed a "bright on dark" treatment (toast-success icons).
- **Group taxonomy** (same reasoning as the discarded attempt, re-confirmed against current `main`'s larger item set): `/commissions`, `/ceo-settings`, `/price-import`, `/factory-purchase-orders`, `/pricing-requests` all live in Sales — one entry, one group, even though `hr` also sees `/commissions` and `ceo`/`import` see the procurement/pricing-queue items. `/payroll` gets its own small Finance group rather than folding into HR.
- **New for this branch**: `/pricing-requests` sidebar link, placed after `/price-import` in the Sales group (import-ops cluster: price-import → pricing-requests queue → factory-purchase-orders → commissions). This was deferred in the first attempt because the route didn't exist on `main` yet; it does now (`canViewPricingRequestQueue` in `routes.js`, guarded route in `App.jsx`, guarded in `permissions.js`'s `PATH_GUARDS`).
- **Styling approach**: added new rules to the existing legacy `styles.css` (`.nav-group*` classes) rather than Tailwind utility classes on the JSX. CLAUDE.md's Tailwind-first direction is for new code in general, but the immediate neighboring code (`.nav-item`, `.sidebar`, `.collapsible-*`) is 100% legacy-CSS-driven; introducing Tailwind utility soup for only the new sub-part of the same component would itself be the kind of one-off inconsistency `impeccable`'s polish guidance flags as drift. Kept the new rules in the same file, same naming convention, same token usage as their immediate neighbors.
- **Auto-expand active group on route change** (not present in the first attempt): added because conditionally-rendering (rather than CSS-hiding) collapsed panels means a collapsed group's active item is genuinely gone from the DOM, not just visually hidden — a real UX risk if a user follows a deep link (e.g. a notification) into a page whose group they'd previously collapsed. The `eslint-disable-next-line react-hooks/exhaustive-deps` on that effect is intentional: it must only re-run on route changes, not when the user manually re-collapses a group (which would otherwise immediately fight their click if they're standing on that group's active route).

## Assumptions
- `feat/nav-menu-grouping` (the earlier worktree/branch) is abandoned. Its uncommitted changes still exist on disk at `.claude/worktrees/nav-menu-grouping` but nothing there was merged; this branch does not depend on it and a reviewer can disregard or delete that worktree.
- Assumed the mock demo users' `employeeId` presence (or absence) per role — unchanged from the discarded attempt's assumption, re-confirmed in this session's browser testing (employee/hr have one, the CEO/sales demo logins' overtime-leave visibility comes from `canViewAllOvertime`/`canViewAllLeave`, not `employeeId`).

## Known Risks
- Same as the discarded attempt: any role/user currently relying on unrestricted `/catalog` access loses it — intended per this task's scope, not a regression.
- `:focus-visible` behavior was verified by reading the CSS and confirming it matches the established pattern already shipping elsewhere in this file (`.collapsible-header-button:focus-visible`, `.info-tip-trigger:focus-visible`) — could not be visually confirmed via the browser automation tool in this session because the automated tab never held real OS-level window focus (`document.hasFocus()` was `false` throughout), which is a testing-environment limitation, not a claim about the shipped behavior. Worth a manual click-through with a real keyboard before merge if a reviewer wants direct visual confirmation.

## Things Not Finished
- None identified for the stated scope. If further sidebar work is planned, consider whether `/pricing-requests`' Thai label ("คิวใบขอราคา") should be reconciled with `PricingRequestQueuePage`'s own on-page heading for consistency (not checked in this session).

## Verification (browser, VITE_USE_MOCKS=true, real mouse clicks + Tab-key keyboard nav)
- **ceo**: Dashboard · Sales (7 items: รายการดีล, ตั้งค่าราคา, แคตตาล็อกสินค้า, นำเข้าราคา, คิวใบขอราคา, ใบสั่งซื้อโรงงาน, ค่าคอมมิชชัน) · Self-service (3 items) — matches plan. Collapsed the Sales group via a real click: chevron rotated, all 7 items disappeared cleanly, Self-service shifted up with no layout jank. Re-expanded: correct.
- **hr**: Dashboard · Sales (ค่าคอมมิชชัน only) · บุคคล/HR (3 items incl. the pending-request badge) · การเงิน/Finance (เงินเดือน) · Self-service (3 items) — matches plan.
- **employee**: Dashboard · Self-service only (3 items), no Sales/HR/Finance groups — confirms the `/catalog` fix holds. Verified on both desktop rail and the mobile drawer (375×812) — same `Sidebar` component, both surfaces correct.
- Tab-key keyboard navigation reaches the group header button in natural DOM order (confirmed via `document.activeElement` after a real `Tab` keypress, not a synthetic focus() call).
- No console errors observed during any of the above.
- Did not individually browser-test `sales`, `sales_manager`, `import`, `account` in this session (time-boxed, as with the first attempt) — their menus follow the same `hasPermission`/`group` logic already verified for `ceo`/`hr`/`employee`.
- Did not attach before/after screenshots to a PR yet — this handoff's screenshots exist only in this session's transcript; recommend the next step capture them for the PR description per repo convention.

## Recommended Next Agent
Claude Opus review, or the same agent continuing to open the PR.

## Exact Next Prompt
```
Review the sidebar nav-grouping + polish change on fix/sidebar-menu-polish (worktree at
.claude/worktrees/nav-menu-polish) against docs/agent-handoffs/99_fix-sidebar-menu-polish.md.
Specifically verify: (1) the canViewCatalog authz-shaped change is acceptable as frontend-only,
(2) :focus-visible actually renders correctly in a real browser with real keyboard focus (this
session's automation couldn't hold real window focus to confirm visually), (3) the group
taxonomy matches user intent. If clean, attach before/after screenshots (hr, employee, sales,
ceo roles, desktop + mobile, expanded + collapsed) to a PR description and open the PR against
main. Also note the now-abandoned .claude/worktrees/nav-menu-grouping worktree/branch can be
deleted (git worktree remove + branch delete) since this branch supersedes it.
```
