# Agent Handoff

## Task
Phase 4 of the Tailwind style migration: replace **static** hardcoded inline
`style={{…}}` usages with Tailwind utility classes (using `@theme` tokens from
`frontend/src/index.css`) in four non-table shared/auth files:
`LoginPage.jsx`, `NotificationBell.jsx`, `Skeleton.jsx`, `Avatar.jsx`.
Genuinely dynamic inline styles (computed per-render from props/state) were
kept inline. Parity-first — pixel-identical before/after.

## Branch
`feat/tw-kill-inline-styles`

## Base Commit
`07da4dc` (main, includes PR1 `feat/tw-primitives-profile` #141 — the
`Panel`/`PageStack`/`DetailHero`/`InfoGrid` primitives and `frontend/src/utils/cn.js`)

## Current Commit
Not committed yet — committed at the end of this session per the task's
"commit + push, do not open a PR" instruction (see final commit hash in
`git log -1` on this branch after the commit step below).

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/features/auth/LoginPage.jsx` — converted all 6 inline
  `style={{…}}` usages in the DEMO quick-login block to Tailwind utilities.
- `frontend/src/components/common/NotificationBell.jsx` — converted all
  static inline styles (positioning, dropdown container, header, empty
  state, message/meta text, unread badge/dot) to Tailwind utilities. Kept 2
  inline styles that are genuinely dynamic (see below).
- `frontend/src/components/common/Skeleton.jsx` — reviewed; the file's one
  `style={{ width, height, borderRadius: radius }}` is fully dynamic
  (per-call props with varying call sites) — **no change made**.
- `frontend/src/components/common/Avatar.jsx` — reviewed; the file's one
  `style={{ background, color }}` is fully dynamic (per-employee computed
  values) — **no change made**.

### Out of Scope
- `styles.css` — confirmed byte-for-byte unchanged (`git diff --stat` empty).
- Frozen sales/CRM pages (tickets, quotation, deposit, commission,
  pricing/FX, catalog, customer, factory, ceo-settings) — not touched.
- Table-related files — explicitly excluded per the task's "non-table"
  scoping; not touched.
- Any other shared component not in the 4-file target list.

## Files Changed
- `frontend/src/features/auth/LoginPage.jsx` — 6 static inline styles → 6
  Tailwind class conversions (see token mapping below). 0 kept as dynamic.
- `frontend/src/components/common/NotificationBell.jsx` — 15 static inline
  style objects → Tailwind classes (counting the task's "17" as the raw
  `style={{` occurrence count including the two intentionally-kept dynamic
  ones on the icon-wrapper background and `<Icon style={{ color }}>`). 2 kept
  as dynamic (see below).
- `frontend/src/components/common/Skeleton.jsx` — no changes (all dynamic).
- `frontend/src/components/common/Avatar.jsx` — no changes (all dynamic).

## Token Mappings Used
- `#e6eaf0` → `border-border` (`--color-border`)
- `#94a3b8` → `text-text-faint` (`--color-text-faint`)
- `#e2e8f0` → `border-border-subtle` (`--color-border-subtle`)
- `#fff` → `bg-surface` / `text-surface` (`--color-surface`)
- `#0f172a` → `text-text` (`--color-text`)
- `#1d4ed8` → `text-info` (`--color-info`)
- `#f1f5f9` → `border-surface-subtle` (`--color-surface-subtle`)
- `12px`/`13px`/`11px`/`14px` → `text-xs`/`text-sm`/`text-2xs`/`text-md`
  (theme defines `--text-2xs:11px`, `--text-xs:12px`, `--text-sm:13px`,
  `--text-md:14px` — Tailwind v4 auto-generates `text-*` utilities from
  these `@theme` vars).
- `borderRadius: 8` → `rounded-md` (theme overrides `--radius-md: 8px`;
  **not** `rounded-lg`, since this theme's `--radius-lg` is customized to
  `20px`, not Tailwind's default `0.5rem`/8px — caught and fixed during
  verification, see Known Risks).
- `borderRadius: 12` (dropdown) → arbitrary `rounded-[12px]` (no exact
  token: 3/8/20/999 don't match 12).
- `#ef4444` (unread badge, unread dot's sibling color set) → **no matching
  token** (`--color-danger-soft` is `#f87171`, `--color-danger` is
  `#dc2626` — neither equals `#ef4444`) → arbitrary `bg-[#ef4444]`. Initially
  mis-mapped to `bg-danger-soft`; corrected after live-browser verification
  caught the color mismatch (see Known Risks / Decisions Made).
- `#3b82f6`, `#f0f6ff` → no matching token → arbitrary `bg-[#3b82f6]` /
  `bg-[#f0f6ff]`.
- Spacing: theme's `--spacing: 4px` base matches Tailwind's default scale
  (`w-2`=8px, `w-8`=32px, `py-3`=12px, `px-4`=16px, `gap-3`=12px, `mt-px`=1px,
  etc.); values off that scale used arbitrary `[Npx]` (e.g. `gap-[18px]`,
  `top-[2px]`, `mt-[6px]`, `mt-[3px]`, `mr-[6px]`).

## Dynamic Inline Styles Kept (with reasons)
- `Avatar.jsx` — `style={{ background: employee?.avatarBg || '#e2e8f0',
  color: employee?.avatarFg || '#475569' }}` — computed per-employee at
  render time from props; no static value to extract. Whole file unchanged.
- `Skeleton.jsx` — `style={{ width, height, borderRadius: radius }}` — all
  three are per-call props with varying call sites (`SkeletonText`,
  `SkeletonCard` pass different `width`/`height`/`radius` values); the
  defaults are static but the entire point of the component is runtime
  overrides. Whole file unchanged.
- `NotificationBell.jsx` icon-wrapper span — `style={{ background:
  icon.color + '1a' }}` — `icon.color` comes from the `TYPE_ICON` lookup
  table keyed by `item.type` (8 different notification types, each a
  different color), computed per-notification at render time. Kept inline.
- `NotificationBell.jsx` `<Icon style={{ color: icon.color }} />` — same
  `icon.color`, kept inline for the same reason.

## Commands Run
```bash
git fetch origin main --quiet
git checkout -b feat/tw-kill-inline-styles origin/main
cd frontend && npm install
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
git diff --stat frontend/src/styles.css   # confirm empty
git diff --stat -- frontend/src/features/tickets frontend/src/features/deposits \
  frontend/src/features/commissions frontend/src/features/ceoSettings \
  frontend/src/features/dashboard/TicketDashboard.jsx  # confirm empty
```
Plus a `frontend-mock` browser preview session for parity verification (see
Known Risks for a tooling gotcha encountered during this step).

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all in
  frozen/other pages: `AttendancePage`, `CeoSettingsPage`, `CommissionPage`,
  `TicketDashboard`, `DepositNoticePage`, `PayrollPage`, `TicketDetailPage`,
  `TicketListPage` — matches the baseline from PR1's handoff, none
  introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green (no
  new test files added in this PR; no existing test broken).
- Build: **pass** — `vite build` → built in ~135ms, no errors. Bundle sizes
  effectively unchanged from PR1 baseline.

## Decisions Made
- **`!important` (`!`) prefix on several utilities** in both files, to win
  against pre-existing **unlayered** global CSS in `styles.css` that would
  otherwise silently override the Tailwind utilities (Tailwind's
  `@layer utilities` always loses to unlayered CSS regardless of selector
  specificity, per the CSS cascade-layers spec). Specifically:
  - `NotificationBell.jsx`: `.topbar-user span` (styles.css) sets
    `display: block; color: var(--color-text-faint); font-size:
    var(--text-xs); font-weight: 500;` and matches **every** `<span>`
    inside `NotificationBell` (the whole component renders inside
    `<div className="topbar-user">` in `AppShell.jsx`). Fixed with `!flex`
    (badge + icon-wrapper, both need `display:flex` not the forced
    `block`), `!text-surface`/`!text-[10px]`/`!font-extrabold` (badge),
    `!text-sm`/`!text-text` (message span), `!text-2xs`/`!text-text-faint`
    (meta span — the color happened to already match by coincidence, but
    `!` was added for consistency/safety).
  - `NotificationBell.jsx` "mark all read" button: `button, input, select,
    textarea { font: inherit }` (styles.css, unlayered) forces
    `font-size` to inherit from ancestor context (computed to browser
    default `16px`) instead of the intended `12px`. Fixed with `!text-xs`.
  - `LoginPage.jsx` DEMO label `<p>`: `.login-form p { margin: 4px 0 0;
    color: var(--color-text-muted); }` (styles.css, unlayered) overrode
    both `margin` and `color`. Fixed with `!m-0 !mb-[10px] !text-text-faint`.
  - This precedent already existed in the codebase (`Button.jsx` uses
    `!font-bold` for the same class of problem), so `!`-prefixed utilities
    are an established pattern here, not a new one.
- Grouped LoginPage's 6 inline styles into one edit since they're all in
  the same JSX block (DEMO quick-login section) with no dynamic values.
- Used `rounded-md` (not `rounded-lg`) for the unread badge's `border-radius:
  8` — this theme customizes `--radius-lg` to `20px` (not Tailwind's
  default `0.5rem`/8px), so `rounded-lg` would have been a silent 8px→20px
  regression if not cross-checked against `index.css`'s `@theme static`
  block first.

## Assumptions
- The task's inline-style counts ("LoginPage.jsx (6)", "NotificationBell.jsx
  (17)", "Skeleton.jsx (1)", "Avatar.jsx (1)") count raw `style={{` JSX
  occurrences, not unique CSS declarations — matches what was found in each
  file.
- "Non-table shared/auth files" scoping means exactly the 4 named files;
  no other shared component was touched even where it also has inline
  styles (out of scope for this PR).

## Known Risks
- **Preview tooling gotcha (environment-specific, not a code issue):** the
  `mcp__Claude_Preview__preview_start` tool's `frontend-mock` launch config
  resolved against the **outer repo's** `.claude/launch.json`
  (`/Users/ploy_warit/Desktop/GL-R-ERP/.claude/launch.json`, cwd reported as
  the outer repo root) rather than this worktree's copy, so the dev server
  it started served the **outer repo's stale, unconverted** source — early
  parity checks were silently validating the wrong (original inline-style)
  code, not this branch's changes. Discovered by noticing `document
  .querySelector('button[aria-label="..."]').className` didn't reflect a
  source edit that had just been made. Worked around by running `vite`
  manually from this worktree on a spare port and using
  `preview_eval`'s `location.href = '...'` to navigate the existing browser
  tab there, then continuing to use `preview_screenshot`/`preview_inspect`/
  `preview_eval` against that same tab/serverId. **A real regression was
  caught this way** (the `!important` cascade collisions above, and the
  `#ef4444`→`bg-danger-soft` mis-mapping) — if the next agent's preview
  server silently serves stale content again, re-verify with the same
  manual-vite + `location.href` navigation trick, and specifically compare
  a known-just-edited class name via `preview_eval` before trusting any
  screenshot.
- **This `!important` fix is scoped to the 2 files touched.** Any other
  shared component that renders inside `.topbar-user` (or any other
  unlayered `styles.css` selector with descendant-element rules) and gets
  converted to Tailwind in a later PR should be checked for the same
  collision class — it will not be visually obvious from reading the
  component in isolation; it only shows up via computed-style inspection
  in the browser.
- Pre-existing (not introduced by this PR): the `NotificationBell` dropdown
  uses a fixed `width: 340px` with `right: 0`, which overflows off the left
  edge of the viewport at narrow mobile widths (~375px) since the bell sits
  near the right edge of a narrow topbar. Verified this is identical
  behavior in the original inline-style code (same `right: 0`/`width: 340`
  values) — not a regression, but flagged in case a future responsive-polish
  PR wants to address it.

## Things Not Finished
- No other shared/auth files converted — only the 4 named targets.
- The mobile dropdown-overflow issue noted above is unaddressed (pre-existing,
  out of scope for a parity-only PR).

## Recommended Next Agent
Claude Sonnet (implementation) — continue the Tailwind migration Phase 4
page/component sequence per whatever plan doc governs it next (check for an
updated plan reference or ask the user for the next target list).

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP repo.
Continue the Tailwind inline-style migration (Phase 4): pick the next
non-table, non-frozen file(s) with static hardcoded inline style={{...}}
usages and convert them to Tailwind utilities using the @theme tokens in
frontend/src/index.css, following the same parity-first approach as
docs/agent-handoffs/27_tw-kill-inline-styles.md (this file).

First read: CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md, and this
handoff file in full — especially "Decisions Made" (the !important-prefix
pattern needed to beat unlayered styles.css selectors like `.topbar-user
span` and `button, input, select, textarea { font: inherit }`) and "Known
Risks" (the preview-tooling cwd gotcha: mcp__Claude_Preview__preview_start
may serve the OUTER repo's stale code instead of this worktree's — verify
by checking a just-edited className via preview_eval before trusting any
screenshot; work around with a manually-started `vite` process on a spare
port + `preview_eval`'s `location.href = '...'` navigation if it recurs).

Grep the frontend/src tree for remaining `style={{` occurrences outside
already-converted files, frozen sales/CRM pages, and table-related files,
and pick the next reasonably-scoped batch (roughly 1-4 files). For each
inline style: convert if static, keep inline (with a one-line comment or
handoff note on why) if genuinely dynamic per-render. Cross-check every
hex color and radius/spacing value against frontend/src/index.css's
@theme static block BEFORE picking a utility class name — do not assume
Tailwind's default scale matches this theme's overridden tokens (e.g.
--radius-lg is 20px here, not Tailwind's default 8px).

Hard constraints: parity-first (pixel-identical desktop + mobile ≤720px),
do not change behavior/props, do not touch frozen pages, do not edit
styles.css, do not commit until told to.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint
errors, 9 pre-existing warnings, tests+build green), then a frontend-mock
browser preview parity check with actual computed-style inspection
(getComputedStyle via preview_eval) on every converted element — not just
a visual screenshot — since silent cascade-layer collisions with
styles.css are easy to miss visually but showed up clearly in computed
styles in this PR. Update/create docs/agent-handoffs/28_<branch-name>.md
before ending.
```
