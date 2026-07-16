# Agent Handoff

## Task
Phase-2 Branch 3b (user-requested): minimize `frontend/src/styles.css` (~2k lines) as far as
safely possible via (1) a grep-proven dead-rule sweep and (2) a bounded migration of simple
single-use rules to inline Tailwind utilities. No business-logic, API, auth, route, or DB-schema
changes; no visual regressions.

## Branch
`refactor/styles-css-minimization`

## Base Commit
`6725539` (`refactor(ui): tokenize sales-stack accent colors into the design system`) — this is
`origin/refactor/ticket-design-tokens`'s tip. At branch-start time `origin/main` was still at the
tokens branch's parent (`1a6ce1d`, PR #220), i.e. the tokenize commit had not yet merged to main,
so per the task's own instruction ("base on the tokens branch — it's merging to main right now; if
`git log origin/main -1` already shows the tokenize commit, base on origin/main instead") this
branch is based on `origin/refactor/ticket-design-tokens`, not `origin/main`.

## Current Commit
See `git log -1` on this branch after this session's commit lands (single commit,
`refactor(css): dead-rule sweep + single-use migration of styles.css`).

## Agent / Model Used
Claude Sonnet (implementation agent, standing Sonnet-implements/Opus-reviews loop)

## Scope

### In Scope
- `frontend/src/styles.css` — deletion of 11 confirmed-dead rules/selectors (Step 1) plus removal
  of 4 more rules whose sole call site was migrated to Tailwind utilities (Step 2).
- `frontend/src/components/common/DesktopOnlyNotice.jsx` — `.desktop-only-notice` → Tailwind
  utilities at its one call site.
- `frontend/src/components/common/FormField.jsx` — `.field-hint` and `.field-error-text` → Tailwind
  utilities at their one call site each (both inside this same component).
- `frontend/src/features/employees/EmployeeDetailPage.jsx` — `.salary-value` → Tailwind utilities
  at its one call site.

### Out of Scope / Not Touched
- Tailwind imports/layers, token/CSS-var definitions in `frontend/src/index.css`, font setup, base
  reset, third-party overrides, `@keyframes skeleton-shimmer`, and every multi-use semantic
  component class (`.status-badge` family, `.event-dot` family, buttons still used by ≥2 files,
  table/card frames) — all left exactly as-is.
- One pre-existing design-hook flag (`side-tab` accent border) fired repeatedly on
  `.timeline-list > div.current` throughout this session as line numbers shifted under it — this
  rule is untouched pre-existing code, not introduced by this branch; not in scope for a CSS-sweep
  task and left alone.
- `frontend/src/features/employees/EmployeeDetailPage.jsx`'s `canSeeSensitive` gate
  (`hasPermission(user.role, 'canViewSensitiveEmployeeData')`) — discovered while trying to
  browser-verify `.salary-value` that this permission key does not exist anywhere in
  `frontend/src/app/permissions.js`, so the salary/compensation panel renders "แสดงเฉพาะบทบาท HR
  และ ADMIN" (visible only to HR/ADMIN) even when logged in as the HR demo account. This looks like
  a pre-existing latent bug unrelated to this branch — flagging for a separate task, not fixing it
  here (out of scope for a CSS-only sweep, and CLAUDE.md's mock-authz-is-not-authoritative rule
  means this needs checking against the real permission model, not the mock, anyway).

## Files Changed
- `frontend/src/styles.css` — 2221 → 2106 lines (115 lines / ~5.2% removed net).
- `frontend/src/components/common/DesktopOnlyNotice.jsx` — 1 line changed (className swap).
- `frontend/src/components/common/FormField.jsx` — 2 lines changed (className swaps).
- `frontend/src/features/employees/EmployeeDetailPage.jsx` — 1 line changed (className swap).
- Total diff: 4 files, 6 insertions(+), 121 deletions(-) (`git diff --stat`), well inside the
  ~400-line risk bound the task set for Step 2.

## Commands Run
```bash
git fetch origin
git switch -c refactor/styles-css-minimization origin/refactor/ticket-design-tokens
cd frontend && npm ci

# Step 1 — dead-rule sweep
grep -oE '\.[a-zA-Z][a-zA-Z0-9_-]*' src/styles.css | sed 's/^\.//' | sort -u   # 172 raw tokens
# (172 → 170 after dropping 2 comment-text false positives: "css", "md", from
#  "styles.css" / "DESIGN.md" mentions inside CSS comments)
grep -ohE "\b(<170-name-alternation>)\b" $(find src -name '*.jsx' -o -name '*.js' | grep -v styles.css) \
  | sort | uniq -c | sort -rn                     # single combined pass, all 170 names at once
# diffed the "found" set against the full 170 to get the 27 zero-hit candidates, then per-name:
grep -rn "<class>" src --include='*.jsx' --include='*.js'   # targeted verification per candidate
# dynamic-family checks (task's own warned-about families):
grep -rn '`avatar-\${size}`\|`status-\${tone}`\|`stat-\${tone}`\|`toast toast-\${' src --include='*.jsx'
grep -rn "tone: '" src --include='*.js' --include='*.jsx'   # enumerate every live tone value
grep -rn '<Avatar' src --include='*.jsx'                     # enumerate every live size value

# Step 2 — single-use migration (4 rules, see Decisions Made for the Tailwind-mapping rationale)
# manual coordinated edits: JSX className swap + CSS rule deletion per rule

cd frontend
npm run lint
npm test -- --run
npm run build
python3 -c "content=open('src/styles.css').read(); print(content.count('{'), content.count('}'))"
wc -l src/styles.css

# Visual verification (worktree-local dev server, since preview_start's frontend-mock config
# resolves against a DIFFERENT worktree — documented gotcha, confirmed again this session)
VITE_USE_MOCKS=true npx vite --host 127.0.0.1 --port 5220 --strictPort &
# browser-tool navigate to http://127.0.0.1:5220, confirmed via fetch('/src/styles.css') that the
# served stylesheet was THIS worktree's (all 11 deleted classes absent) before trusting screenshots
```

## Test / Build Results
- `npm run lint`: **pass** — 0 errors, 7 pre-existing warnings (all `react-hooks/exhaustive-deps`,
  identical set to baseline, none introduced by this change).
- `npm test -- --run`: **pass** — 19 test files, 94 tests, 0 failures (identical to baseline).
- `npm run build`: **pass** — Vite build completed in ~150ms, no errors.
- Brace balance: 300 `{` / 300 `}` after all edits (verified after every edit round, not just once).
- Backend: not touched, not run (frontend-only change).
- Live browser verification (worktree-local `vite` dev server on port 5220, mock API, confirmed
  serving this worktree's `styles.css` via a content fetch before trusting any screenshot):
  - **Login screen**: `.login-panel`/`.login-form`/`.brand-mark` render correctly.
  - **HR Dashboard**: stat cards render with every tone class correctly (indigo/teal/amber/rose/
    blue), `.profile-strip`, `.page-heading` all intact.
  - **Employee list**: `.data-row`/`.table-head` grid renders correctly.
  - **Employee detail page**: personal-info tab renders correctly; the compensation
    (`.salary-value`) tab could not be reached due to the pre-existing `canSeeSensitive` permission
    gap noted above (unrelated to this change) — not visually confirmed live, but the Tailwind
    utility mapping was cross-checked token-by-token against `frontend/src/index.css`'s `@theme
    static` block (`mt-4`→`--space-4`≈native Tailwind 16px, `text-surface`→`--color-surface`,
    `text-4xl`→`--text-4xl: 34px`, `font-extrabold`→800) and the component's DOM structure is
    unchanged (same `<div>`, only the `className` string changed).
  - **Payroll page (desktop)**: stat cards, empty state render correctly.
  - **Payroll page (mobile, 375px)**: `.desktop-only-notice` (now Tailwind) renders pixel-equivalent
    — blue border, light-blue background, dark-blue text, rounded corners, correct gap between the
    Thai/English lines.
  - **Overtime page**: submitted a request with an empty reason field; `.field-error-text` (now
    Tailwind: `m-0 text-danger text-xs font-bold`) rendered correctly — small, bold, red text under
    the invalid (red-bordered) textarea, confirming the FormField error path end-to-end.
    `.field-hint` was not independently triggered in a live screenshot (no reachable field with a
    `hint` prop in this session's flows) but shares the identical verification method (CSS-var →
    Tailwind-token cross-check) and is a strict subset of the already-confirmed `.field-error-text`
    pattern (same component, same rule shape, one property value different).
  - **Tickets list + detail** (logged in as CEO demo — sales/CRM stack is unfrozen per CLAUDE.md
    and reachable without a flag flip): list renders with all status-tab/status-badge tones intact;
    detail page's icon-button (refresh, top-right), event-dot (green "created" dot), and the
    still-shared `primary-button`/`secondary-button`/`danger-button`/`icon-button` selector-list
    rule (with `success-button`/`text-button`/`back-button` now removed from that shared list) all
    render with unchanged styling.
  - **Leave page**: stat cards, filter row, request form all render correctly.
  - Console: clean throughout (checked via `read_console_messages` after each navigation).
  - One environment quirk hit and worked around: the Browser tool's `computer` click action did not
    register as a real React synthetic event for the login/demo-account buttons on this dev server
    (page stayed on the login screen after several `left_click` attempts at correct coordinates and
    refs) — worked around with `element.click()` via `javascript_tool`, which did register. Once
    logged in, `computer left_click` worked normally for regular navigation. Flagging in case this
    recurs for the next agent; not a code regression (confirmed via `read_console_messages`, no
    errors, and the click handler itself is untouched by this branch).

## Dead-Rule Sweep Audit (Step 1)

Extracted 172 raw `.classname` tokens from `styles.css` via `grep -oE '\.[a-zA-Z][a-zA-Z0-9_-]*'`;
2 were comment-text false positives (`css`, `md`, from "styles.css"/"DESIGN.md" mentions inside
comments), leaving **170 real class names**. A single combined grep pass across every `.jsx`/`.js`
file under `frontend/src` (excluding `styles.css` itself) found 143 with ≥1 hit and 27 with zero
hits. Each of the 27 was individually re-verified (broad substring grep + targeted dynamic-
construction search), since several belong to the task-warned dynamic families (`status-*`,
`stat-*`, `avatar-*`, `toast-*`).

### Deleted (11 rules/selectors — grep-proven zero usage, Step 1)
| Class / selector | Verdict basis |
|---|---|
| `address-line` | 0 hits anywhere (broad + targeted grep). |
| `back-button` | 0 hits; `Button.jsx`'s `back`-shaped affordance now uses `variant="text"`/plain `<button>`, never this literal class. |
| `hero-meta` (+ `hero-meta span`) | 0 hits. |
| `icon-only` | 0 hits. |
| `icon-button.mobile-nav-toggle` (compound) | 0 hits for `mobile-nav-toggle`; the mobile menu-toggle button (`AppShell.jsx:126-137`) now renders via `<Button variant="icon">` with Tailwind responsive utilities (`!hidden max-[720px]:!inline-flex …`), not this legacy class. **`.icon-button` alone and the sibling `.mobile-drawer-backdrop` in the same selector list are both still live and were kept.** |
| `payroll-adjustment-group` (+ `h3`) | 0 hits. |
| `success-button` | 0 hits as a literal class (appeared in 4 CSS locations: the shared button base-rule list, its own rule, the 720px mobile-floor list, and a `.attendance-import-panel .success-button` descendant rule — all 4 removed/trimmed). |
| `text-button` | 0 hits (shared a rule with `back-button`; also removed a now-stale explanatory comment that referenced it by name). |
| `user-table` | 0 hits — no `gridClassName="user-table"` callsite exists (confirmed against every `gridClassName=` usage in the codebase); appeared in its own rule, the 1040px `min-width:900px` selector list, and a documentation comment (all updated). |
| `form-grid.single` (compound) | 0 hits for the raw class combo — the Tailwind `<FormGrid single>` component (`Layout.jsx`) reproduces the same effect via `grid-cols-1` directly, never applying the literal `"form-grid single"` string. **Bare `.form-grid` (used raw by `CommissionPage.jsx`/`TicketCreateModal.jsx`) is still live and was kept**, including its appearance in the 720px media query. |
| `icon-button.dark` (compound) | 0 hits for `dark` as an actual class anywhere — the word "dark" itself has nonzero raw grep hits, but 100% of them are substrings of CSS-variable names (`var(--color-success-dark)`, `--color-danger-dark`, `--color-warning-dark`, and this branch's own new `text-info-dark` Tailwind class), never the literal class token. Confirmed via full-codebase search for `"dark"` in any `className`/prop-value position. |

### Kept-used (155 classes — grep-confirmed real usage)
`action-list`, `active`, `app-main`, `app-shell`, `attendance-import-device`,
`attendance-import-panel`, `attendance-import-result`, `avatar`, `avatar-lg`, `avatar-sm`,
`avatar-xl`, `bar-list`, `bar-row`, `bar-track`, `brand`, `brand-mark`, `breadcrumbs`,
`collapsible-body`, `collapsible-chevron`, `collapsible-header`, `collapsible-header-button`,
`collapsible-header-right`, `collapsible-section`, `collapsible-subtitle`, `collapsible-title`,
`collapsible-title-group`, `comment`, `commission-payroll-table`, `commission-row-wrap`,
`commission-table`, `compact`, `confirm-dialog-message`, `confirm-dialog-reason`, `content-scroll`,
`created`, `currency-input`, `currency-input-symbol`, `current`, `danger-button`, `data-row`,
`data-table`, `data-table-search`, `data-table-toolbar`, `detail-hero`, `editable-list`,
`employee-cell`, `empty-state`, `event-dot`, `field-required`, `form-error`, `form-field`,
`form-grid`, `highlight-panel`, `icon-button`, `info-tip`, `info-tip-bubble`, `info-tip-trigger`,
`input-with-icon`, `is-active`, `is-invalid`, `is-mobile-drawer-open`, `is-open`, `is-sticky`,
`leave-balance-card`, `leave-calendar-item`, `leave-calendar-list`, `loading-veil`, `login-brand`,
`login-form`, `login-panel`, `login-screen`, `mini-metric`, `mobile-drawer-backdrop`,
`modal-backdrop`, `modal-body`, `modal-footer`, `modal-header`, `modal-panel`, `nav-item`,
`nav-list`, `old-value`, `page-actions`, `page-heading`, `page-stack`, `pagination`, `panel`,
`panel-header`, `payroll-actions`, `payroll-breakdown`, `payroll-detail-grid`,
`payroll-detail-panel`, `payroll-row`, `payroll-special-grid`, `payroll-table`,
`payroll-workspace`, `primary-button`, `profile-strip`, `reflow-cards`, `request-feed`,
`request-feed-item`, `request-table`, `row-actions`, `salary-history`, `search-field`,
`secondary-button`, `sensitive-panel`, `sidebar`, `sidebar-account`, `single` (as `FormGrid`'s
Tailwind-only prop name — NOT the same as the deleted `.form-grid.single` raw CSS rule, see above),
`skeleton`, `skeleton-card`, `skeleton-card-header`, `skeleton-text`, `sort-header`, `span-2`,
`stat-amber`, `stat-blue`, `stat-card`, `stat-grid`, `stat-helper`, `stat-icon`, `stat-indigo`,
`stat-label`, `stat-rose`, `stat-teal`, `stat-value`, `status-badge`, `status-danger`,
`status-indigo`, `status-info`, `status-neutral`, `status-success`, `status-tab`, `status-tabs`,
`status-teal`, `status-warning`, `success`, `table-head`, `table-panel`, `tabs`,
`ticket-detail-grid`, `ticket-event`, `ticket-events`, `ticket-items-table`, `ticket-table`,
`timeline-list`, `toast`, `toast-error`, `toast-success`, `toolbar-actions`, `topbar`,
`topbar-title`, `topbar-user`, `topbar-user-text`, `transition`.

Notable non-obvious ones, individually verified (not just word-boundary-matched — these are the
task-warned dynamic families and compound-selector modifiers):
- **`status-*` / `stat-*` tone families** (`stat-indigo`/`status-indigo`, `stat-teal`/`status-teal`/
  `status-success`, `stat-amber`/`status-warning`, `stat-blue`, `stat-rose`/`status-danger`,
  `status-neutral`, `status-info`): all constructed dynamically (`` `status-badge status-${tone}` ``
  in `StatusBadge.jsx`, `` `stat-icon stat-${tone}` `` in `StatCard.jsx`). Enumerated every literal
  `tone="…"` / `tone: '…'` value across the codebase (`format.js`'s status maps, `TicketListPage.jsx`,
  every `<StatCard>`/`<StatusBadge>` call site) — every one of `indigo`, `teal`, `amber`, `blue`,
  `rose`, `neutral`, `warning`, `success`, `danger`, `info` is a real, reachable value. All kept.
- **`avatar-lg`/`avatar-sm`/`avatar-xl`**: constructed via `` `avatar avatar-${size}` `` in
  `Avatar.jsx`; all 3 sizes have live `<Avatar size="…">` call sites. Kept. (`avatar-md` is the
  fourth family member — see Kept-unsure below, it's the one genuinely borderline case.)
- **`toast-error`/`toast-success`**: constructed via `` `toast toast-${toast.kind}` `` in
  `Toast.jsx`; `showToast('error', …)` and `showToast('success', …)` are both called dozens of
  times across the app. Kept.
- **`event-dot` modifiers** (`created`, `comment`, `success`, `transition`): all 4 confirmed as
  literal return values in `TicketDetailPage.jsx`'s event-kind-to-class function. Kept.
- **`request-feed-item.compact`, `status-tab.active`, `nav-item.active`, `timeline-list > div.current`,
  `table-head.is-sticky`, `sort-header.is-active`, `form-field *.is-invalid`,
  `collapsible-chevron.is-open`, `sidebar.is-mobile-drawer-open`, `mobile-drawer-backdrop.is-open`**:
  every compound-selector modifier individually confirmed applied together with its base class at a
  real call site (not just a coincidental word match — this mattered because words like `active`,
  `current`, `success`, `single` are common enough to appear in unrelated identifiers/comments too).

### Kept-unsure (1 class)
- **`avatar-md`** — part of the same dynamic `avatar-${size}` family as `avatar-lg`/`avatar-sm`/
  `avatar-xl` (all three confirmed live above), but it is also the component's declared default
  (`Avatar.jsx`: `size = 'md'`), and **none of the 8 current `<Avatar>` call sites omit the `size`
  prop** — so by today's call graph alone it has zero live reachability. Kept rather than deleted:
  deleting only the default value of a 4-way size family while keeping its 3 siblings is exactly
  the kind of "could plausibly be constructed dynamically" case the task said to err conservative
  on — a future `<Avatar employee={x} />` call without an explicit `size` would silently lose its
  sizing the moment this rule is gone, and the win (3 lines of CSS) doesn't justify that footgun.

## Single-Use Migration (Step 2)

4 rules migrated — each was used at **exactly one call site**, was pure layout/spacing/typography
(no pseudo-elements, no media queries, no keyframes, no descendant combinators), and had no hover/
focus state:

| CSS rule (deleted) | Call site | New Tailwind className |
|---|---|---|
| `.salary-value` (`margin-top:16px; color:var(--color-surface); font-size:var(--text-4xl); font-weight:800;`) | `EmployeeDetailPage.jsx:166` | `mt-4 text-surface text-4xl font-extrabold` |
| `.field-hint` (`margin:0; color:var(--color-text-muted); font-size:var(--text-xs); font-weight:500;`) | `FormField.jsx:43` | `m-0 text-text-muted text-xs font-medium` |
| `.field-error-text` (`margin:0; color:var(--color-danger); font-size:var(--text-xs); font-weight:700;`) | `FormField.jsx:48` | `m-0 text-danger text-xs font-bold` |
| `.desktop-only-notice` (flex column, gap/padding via `--space-*`, border+bg+text via `--color-info*`, `font-size:var(--text-sm)`) | `DesktopOnlyNotice.jsx:10` | `flex flex-col gap-1 py-3 px-4 border border-info rounded-md bg-info-bg text-info-dark text-sm` |

Every custom property used maps 1:1 to a Tailwind utility already defined via `@theme static` in
`frontend/src/index.css` (confirmed by reading that file directly, not assumed) — this matches the
convention already established in `Button.jsx` (`bg-primary`, `text-danger`, etc., per
`60_refactor-ticket-design-tokens.md`'s prior confirmation that `@theme static` auto-derives
utility classes from any `--color-*` custom property).

**Skipped as too risky / not simple enough for Step 2** (left as CSS classes, untouched):
`.field-required` (has a descendant-combinator parent rule, `.form-field .field-required`),
`.highlight-panel` (has `.highlight-panel h2, .highlight-panel p` descendant rules),
`.commission-row-wrap` (has a `.commission-row-wrap .data-row` descendant override),
`.search-field`/`.currency-input`/`.input-with-icon` (absolutely-positioned icon overlays — judged
too easy to get pixel-wrong without a dedicated visual diff pass), `.breadcrumbs` (multi-rule family
with its own descendant selectors), `.page-heading` (shared selector list with `.login-form h1`/`p`).
These are all legitimate single-use candidates for a **future**, more generously time-boxed Step-2
pass but were not attempted here to keep this branch's diff small and its risk low.

Diff size for Step 2: 4 files changed, well inside the task's ~400-line bound (total branch diff:
6 insertions / 121 deletions across all 4 files).

## Decisions Made
1. **Compound-selector modifiers were split, not deleted wholesale.** `.icon-button.mobile-nav-toggle`,
   `.icon-button.dark`, and `.form-grid.single` all shared their base class (`.icon-button`/
   `.form-grid`) with other, definitely-live selectors — only the dead modifier fragment was
   removed from each selector list/rule, never the shared base class.
2. **Stale comments referencing deleted classes were updated, not just left dangling.** Two
   comments explicitly named a class being deleted in this pass (`.text-button is excluded: …` in
   the 720px mobile-floor comment, and `.user-table / .commission-table / .commission-payroll-table`
   in the HR-core card-fallback comment) — both were edited to drop the now-nonexistent reference
   while preserving the surrounding design rationale, since a comment naming a deleted class would
   confuse the next reader.
3. **Chose 4 conservative Step-2 candidates over a broader sweep of all ~40 single-use-count
   classes.** Given the ~400-line risk bound and that each migration requires a coordinated JSX+CSS
   edit plus visual verification, this session picked the 4 simplest (2-4 flat properties, no
   combinators/pseudo/media-queries) rather than attempting the full single-use population. The
   skipped list above is a ready-made candidate set for a follow-up.
4. **Did not attempt to fix the `canSeeSensitive`/`canViewSensitiveEmployeeData` permission gap**
   found while trying to browser-verify `.salary-value` — it's a pre-existing, unrelated latent
   bug (the permission key doesn't exist in `permissions.js` at all, so the salary tab is
   inaccessible to every role including HR), out of scope for a CSS-sweep task, and per CLAUDE.md
   mock authz is never authoritative anyway.

## Assumptions
- Interpreted "grep the codebase" as requiring the full battery the task itself prescribed: a
  broad word-boundary pass across all 170 class names in one combined grep (for speed), then
  individual re-verification of every zero-hit candidate and every task-named dynamic family
  (`status-*`, `stat-*`, `avatar-*`, `toast-*`) via targeted, non-word-boundary searches (to catch
  template-literal construction that a naive grep would miss) — plus the reverse check (word-
  boundary hits that turn out to be false positives from unrelated substrings, e.g. `dark`/`single`
  matching inside CSS-variable names or unrelated prop names).
- Treated the running `preview_start` `frontend-mock` server (port 5200) as unusable for this
  session's verification, per the previously-documented gotcha (it resolves `launch.json` against
  a different worktree's checkout) — confirmed again by fetching `/src/styles.css` from it and
  finding it did not match this worktree's file. Started an independent worktree-local `vite` dev
  server on port 5220 instead and content-verified it before trusting any screenshot.

## Known Risks
- `.field-hint`'s Tailwind migration was cross-checked token-by-token but not independently
  triggered in a live screenshot in this session (no reachable field with a `hint` prop was hit
  during the walkthrough) — low risk since it's the same component, same rule shape, and one
  property value different from the already-confirmed `.field-error-text` sibling, but flagging
  for the Opus reviewer to spot-check if a `hint`-bearing field is easy to reach (e.g. leave-request
  attachment field, per `FormField.jsx`'s own docstring example).
- `.salary-value`'s Tailwind migration was not visually confirmed live (pre-existing permission gap
  blocks the tab for every demo role) — verified instead by direct token-mapping cross-check against
  `index.css` and by confirming the DOM structure is unchanged (only the `className` string changed,
  same `<div>{formatMoney(...)}</div>`).
- The Step-2 "skipped as too risky" list (`.field-required`, `.highlight-panel`,
  `.commission-row-wrap`, icon-overlay inputs, `.breadcrumbs`, `.page-heading`) remains as CSS —
  legitimate future work but each needs its descendant-combinator or shared-selector complexity
  unwound carefully, one at a time, with visual verification.

## Things Not Finished
- Steps 2 candidates beyond the 4 migrated (see the "skipped" list above and the full kept-used
  single-use classes visible in `styles.css` — e.g. `login-panel`/`login-form`/`login-brand`,
  `collapsible-*`, `info-tip-*`, `currency-input*` — are all legitimate future Step-2 targets, not
  attempted here to keep the diff small per the task's own risk bound).
- The pre-existing `canViewSensitiveEmployeeData`/`canSeeSensitive` permission gap on
  `EmployeeDetailPage.jsx` (flagged above, out of scope) — worth its own bug-fix branch.

## Recommended Next Agent
Claude Opus review (standing Sonnet-implements/Opus-reviews loop). Specifically should:
1. Independently re-run (or spot-check) the dead-rule sweep's grep audit, especially the 11
   deletions and the dynamic-family reasoning for the tone/avatar/toast families that were kept.
2. Visually confirm `.field-hint` (reachable via a `hint`-bearing `FormField`, e.g. an attachment
   field) and `.salary-value` (needs the `canSeeSensitive` permission gap worked around or fixed
   first, or accept the token-mapping cross-check as sufficient given it's a pre-existing,
   unrelated gap).
3. Decide whether to spin off the `canViewSensitiveEmployeeData` permission gap as its own follow-up
   task (recommended — it looks like a real, if minor, latent bug: the salary/compensation tab is
   currently unreachable for every role in the mock, including HR).
4. Decide whether a follow-up Step-2 pass on the "skipped as too risky" and other single-use
   classes is worth doing, given this branch intentionally left ~40 single-use classes untouched.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch refactor/styles-css-minimization (pushed, based on
origin/refactor/ticket-design-tokens @ 6725539). Read CLAUDE.md and
docs/agent-handoffs/61_refactor-styles-css-minimization.md — it documents a dead-rule sweep (11
rules deleted, grep-proven zero usage, including careful handling of dynamic class-construction
families like status-${tone}/stat-${tone}/avatar-${size}/toast-${kind}) plus a bounded Step-2
migration of 4 single-use rules to Tailwind utilities (styles.css: 2221 → 2106 lines). Lint/test/
build all pass (94 tests, 0 errors, same baseline as before this branch).

Please: (1) spot-check the dead-rule-sweep audit tables (deleted / kept-used / kept-unsure lists)
against the actual styles.css and frontend/src, (2) visually verify .field-hint (find a FormField
call site with a `hint` prop — check its own docstring example) and .salary-value (the
EmployeeDetailPage.jsx compensation tab — you'll likely hit the same
canViewSensitiveEmployeeData/canSeeSensitive permission gap noted in the handoff, where the
permission key doesn't exist in frontend/src/app/permissions.js at all; decide whether to fix that
as a quick aside or spin it off as its own task), (3) decide on merge readiness.
```
