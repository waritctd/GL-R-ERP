# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
Add a mobile navigation shell and app-wide mobile-safety fixes for HR-core pages: a hamburger + off-canvas nav drawer to replace the sidebar that is hidden below 720px, plus the global iOS-zoom, `100dvh`, and touch-target fixes. HR-core only; the sales/CRM stack stays frozen.

## Branch
`fix/mobile-app-shell`

## Base Commit
`a6fa3ee` (main @ start; run `git rev-parse HEAD` to confirm before branching)

## Current Commit
`a6fa3eec6919d65426ec53689af525037577f565` (no commit made; branch has uncommitted implementation changes)

## Agent / Model Used
Implementer: Codex GPT-5 (isolated worktree; see note below) · Reviewer: Claude Sonnet 5

**Note on isolation:** the implementation agent was launched with `isolation: "worktree"`, but no separate worktree was actually created — `git worktree list` after the run showed only this repo's own directory, already checked out on `fix/mobile-app-shell` with uncommitted edits. The implementer's own transcript shows it noticed this ("I can't switch into the main working tree... I'm confined to my own worktree") and was mid-improvising a workaround when the task was killed by the user. The work product itself (reviewed below) was sound despite the isolation failure, but this should be treated as an environment limitation to watch for on future `isolation: "worktree"` agent runs, not assumed to be reliable.

## Scope

### In Scope
- **Nav drawer:** hamburger button in the topbar (`frontend/src/components/layout/AppShell.jsx`) that opens the existing `Sidebar` (`frontend/src/components/layout/Sidebar.jsx`) as an off-canvas drawer with a backdrop below 720px; close on route change and on backdrop tap. Add drawer + backdrop rules in a **new** `@media (max-width: 720px)` block in `frontend/src/styles.css`.
- **iOS-zoom fix:** `font-size: 16px` on the `input, select, textarea` rule (`frontend/src/styles.css:226-237`).
- **`100vh` → `100dvh`:** `frontend/src/styles.css` ~104, ~129, ~313, and modal `max-height` ~1446.
- **Touch targets:** `.icon-button` → 44×44 (`frontend/src/styles.css:545-546`); larger tap area for interactive `.status-badge` (`~1313`).

### Out of Scope
- Routing changes (that is branch 5, `refactor/frontend-routing`).
- Data fetching / server-state (branches 3–4).
- Table→card layout (branch 2, `fix/mobile-core-list-cards`).
- Any sales/CRM page (tickets, quotation, deposit, commission, pricing, ceo-settings).
- Any business logic.

## Files Changed
- `frontend/src/components/layout/AppShell.jsx`: added mobile drawer state, hamburger button with `aria-expanded`/`aria-controls`, route-change/backdrop/Esc close behavior, and focus trap while the drawer is open.
- `frontend/src/components/layout/Sidebar.jsx`: accepts drawer `id`, ref, and open-state class so the existing sidebar can be reused as the mobile drawer.
- `frontend/src/components/common/Icon.jsx`: added lucide `Menu` to the shared icon map for the hamburger.
- `frontend/src/styles.css`: added 16px form-control font size, `100dvh` replacements, 44px `.icon-button`, interactive `.status-badge` tap-area rule, hidden/shown hamburger rules, and a new `@media (max-width: 720px)` drawer/backdrop block. Did not edit `.table-head`/`.table-row`.
- `docs/agent-handoffs/02_fix-mobile-app-shell.md`: completed this handoff.

### Reviewer amendments (Claude Sonnet 5, same file, small CSS-only fixes)
- `frontend/src/styles.css`, inside the new `@media (max-width: 720px)` block:
  1. Added `.brand span:not(.brand-mark), .nav-item span, .sidebar-account span { display: revert; }`. **Reason:** the pre-existing `@media (max-width: 1040px)` rule that hides these spans (for the collapsed desktop icon-rail) also matches at 375px, since `max-width` queries are not mutually exclusive. Before this fix, the drawer rendered as 10 unlabeled icon buttons with no accessible name at all (confirmed via live DOM inspection — `getComputedStyle` showed `display: none` and the a11y snapshot showed nameless `button` nodes) — the drawer was open but functionally useless as navigation.
  2. Added `overflow-y: auto;` to `.sidebar` in the same block. **Reason:** restoring the labels above made each nav item ~2–3x taller, so the drawer's total content height (1056px) now exceeds a typical phone viewport (812px) with no way to reach the rest — confirmed the logout button's bounding rect fell below `window.innerHeight` (unreachable) before this fix, and fully on-screen/scrollable after.

## Commands Run
```bash
git status --short --branch
git rev-parse --abbrev-ref HEAD
git rev-parse HEAD
git stash push --include-untracked -m clean-before-fix-mobile-app-shell
git switch -c fix/mobile-app-shell
git checkout stash@{0}^3 -- docs/agent-handoffs
cd frontend && npm run lint && npm test && npm run build
cd frontend && VITE_USE_MOCKS=true npm run dev
/Users/ploy_warit/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node /Users/ploy_warit/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/cli.js install chromium
/Users/ploy_warit/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node /private/tmp/glr-mobile-shell-verify.cjs
curl -I http://localhost:8090/api/auth/me
```

## Test / Build Results
- Frontend build (`npm run build`): PASS (`vite build`, final run) — re-confirmed after reviewer fixes.
- Frontend tests (`npm test`): PASS (7 files, 37 tests) — re-confirmed after reviewer fixes.
- Lint (`npm run lint`): PASS with existing warnings only (13 `react-hooks/exhaustive-deps` warnings in pre-existing files; no errors) — re-confirmed after reviewer fixes.
- Manual mobile check at 375px & 768px (Codex, pre-fix): reported PASS for drawer open/close, focus trap, Esc, backdrop, nav-click-closes, 16px inputs, 44px hamburger, 768px unaffected. Screenshots saved to `/private/tmp/glr-mobile-shell-375-drawer.png` / `-768-desktop.png` (not committed).
- **Reviewer live-browser verification (Claude Sonnet 5, `frontend-mock` @ 5200, 375×812 / 800×1024 / 1440×900):** re-verified the above and went further — inspected actual computed styles and bounding rects via `preview_inspect`/`preview_eval`, not just visual screenshots. This is what surfaced the two bugs above: nav-item/brand/account `span`s computed to `display:none` inside the open drawer (confirmed via `getComputedStyle`), and the logout button's bounding rect (`top:997, bottom:1041`) fell entirely below `window.innerHeight` (812) — both invisible in a quick screenshot glance but real usability blockers. After the two fixes: drawer shows full labels, scrolls, all items and the account/logout footer are reachable and confirmed on-screen via bounding-rect check; 800px tablet width still shows the unmodified collapsed icon rail (no hamburger, no drawer); 1440px desktop shows the full unmodified sidebar. Lint/test/build re-ran clean after the fixes (see above).
- Manual caveat (unchanged from Codex's pass): Attendance route could not be completed in mock-mode browser verification because the existing mock API lacks `api.attendance.devices/list`; navigating there white-screens `AttendancePage` with `Cannot read properties of undefined (reading 'devices')`. This is a pre-existing bug outside this shell branch and was not patched here — worth flagging as its own fix later.

## Decisions Made
- Reused the single existing `Sidebar` component for both desktop and mobile; CSS keeps desktop/tablet behavior unchanged and turns it into an off-canvas drawer only below 720px.
- Closed the drawer before delegating nav clicks to the parent route handler so heavy route renders cannot leave the menu open.
- Used a previous-route ref so the "close on route change" effect does not race against a user opening the drawer immediately after login.
- Kept backdrop focus out of the tab order (`tabIndex={-1}`) and traps keyboard focus inside the drawer while open.
- Added `Menu` to the shared icon registry rather than rendering a text hamburger.
- Added a specific base hide selector for `.icon-button.mobile-nav-toggle`, then matched that specificity in the 720px media rule so the hamburger is hidden at 768px but visible at 375px.

## Assumptions
- Confirmed: the `Sidebar` component can be reused for the drawer with only id/ref/open-state props and mobile-only CSS positioning.
- The current 768px layout is expected to use the existing collapsed static sidebar from the pre-existing `@media (max-width: 1040px)` rule; this branch leaves that behavior intact.
- Interactive status badges are currently not rendered by `StatusBadge.jsx` (it returns a `span`), so the new `button.status-badge, a.status-badge` rule safely covers interactive badge instances without inflating all read-only badges.

## Known Risks
- `frontend/src/styles.css` is a single unscoped global sheet — changed only the requested base selectors (`input/select/textarea`, `100dvh`, `.icon-button`, interactive `.status-badge`) plus additive mobile drawer rules, plus the reviewer's two additive fixes inside the same new `@media (max-width: 720px)` block.
- Do **not** edit the shared `.table-head`/`.table-row` grid selectors (owned by branch 2).
- Do **not** set `user-scalable=no` / `maximum-scale` in `index.html` (accessibility; would also mask the zoom bug instead of fixing it).
- Attendance route browser verification is blocked in mock mode by the missing `api.attendance` mock module, causing `AttendancePage` to white-screen before the shell can be inspected. Backend was running, but the attempted seeded HR login (`hr@glr.co.th` / `demo1234`) returned 401, so real-backend browser verification was not completed.
- Browser screenshots were saved in `/private/tmp`, not committed to the branch, to keep the diff small and avoid binary proof artifacts in Git.
- **Resolved by reviewer, but worth flagging as a general pattern:** the `@media (max-width: 1040px)` icon-rail rules and the new `@media (max-width: 720px)` drawer rules both match at phone widths (≤720px implies ≤1040px). Any future edit to the 1040px block should be checked against the 720px drawer behavior too, since CSS `max-width` queries stack rather than replace each other.
- The isolation gap noted above (agent ended up on the main working directory instead of a separate worktree) means this branch's history includes a `git stash` step by the implementer; `stash@{0}` ("clean-before-fix-mobile-app-shell") still exists in this repo and should be dropped once this branch's changes are confirmed safe and no longer needed as a fallback.

## Things Not Finished
- No commit or push was made, per instruction — branch `fix/mobile-app-shell` still has uncommitted working-tree changes only.
- Full Attendance route reachability remains unverified in browser tooling due to the pre-existing mock/credential blocker above (pre-existing bug, not introduced by this branch).
- `CLAUDE.md` was stashed by the implementer and not restored to the working tree before it stopped; the reviewer restored it from `stash@{0}^3` (the untracked-files parent of the stash commit) without popping/dropping the stash, so it is back in the working tree unstaged. The stash itself (`stash@{0}`) is still present as a safety net and has not been dropped.

## Review Verdict (Claude Sonnet 5, standing in as reviewer)

**APPROVED with two tiny reviewer-applied CSS fixes** (documented above in Files Changed / Decisions Made). Findings:

- **Scope:** clean. Only `AppShell.jsx`, `Sidebar.jsx`, `Icon.jsx`, `styles.css`, and this handoff were touched. No routing, data-fetching, table/card, or sales-stack changes leaked in.
- **CSS cascade:** the implementer's own changes did not touch shared base selectors beyond the requested ones. However, live-browser verification (not just a visual screenshot glance) surfaced an *interaction* between the pre-existing `@media (max-width: 1040px)` icon-rail rule and the new `@media (max-width: 720px)` drawer — both fire simultaneously at phone widths, which silently hid all drawer labels and caused unreachable overflow content. Both are fixed (see above) and reverified at 375px/800px/1440px.
- **Drawer a11y:** hamburger has `aria-expanded`/`aria-controls`; focus moves into the drawer on open (confirmed visually — focus ring on first item) and is trapped (Tab wraps within the drawer); Esc and backdrop-click close it; route-change closes it. After the label fix, nav items are also properly named for assistive tech again (previously they had no accessible name at all — confirmed via the a11y tree).
- **Mobile correctness:** inputs compute to 16px (no iOS zoom risk); `100dvh` applied at all four call sites; `.icon-button` is 44×44; interactive `.status-badge` has a larger tap area. All confirmed via `preview_inspect`/computed styles, not just visual inspection.
- **Regression check:** re-ran `npm run lint` (0 errors, 13 pre-existing warnings, unrelated files), `npm test` (37/37 pass), `npm run build` (succeeds) after the reviewer fixes. Visually confirmed 800px tablet width still shows the original collapsed icon rail (no hamburger, no drawer) and 1440px desktop shows the full original sidebar — neither regressed.
- **Process note:** the `isolation: "worktree"` request did not produce an actual isolated worktree (see "Note on isolation" above) — this is an environment gap, not a defect in the delivered code, but should be watched on future runs.

## Recommended Next Agent
Human (or any agent, at the user's direction) to commit this branch's changes and open a PR for review — the code is verified and ready. After that, the next branch in the stabilization sequence is `fix/mobile-core-list-cards` (branch 2), per `01_STABILIZATION_AUDIT.md` §8.

## Exact Next Prompt
```
Branch `fix/mobile-app-shell` has been implemented and reviewed (see this handoff file) — lint/test/build all pass, and mobile drawer behavior was verified live in-browser at 375px/800px/1440px, including two reviewer-applied CSS fixes for a label-visibility and drawer-overflow bug. Nothing has been committed yet.

Next step: commit these changes on branch `fix/mobile-app-shell` with a conventional-commit message, push, and open a PR against `main` for human review. Do not merge.

After that, move to branch 2, `fix/mobile-core-list-cards`, per docs/agent-handoffs/01_STABILIZATION_AUDIT.md §8: add card-fallback rendering for the six HR-core tables currently forced to `min-width: 900px` (employee/attendance/leave/overtime/payroll/profile-requests tables) so they don't force horizontal scroll on phones. Read docs/agent-handoffs/00_MASTER_CONTEXT.md and 01_STABILIZATION_AUDIT.md first, create a new docs/agent-handoffs/03_fix-mobile-core-list-cards.md from the README.md template, and follow the same one-branch/one-agent/update-the-handoff discipline.
```
