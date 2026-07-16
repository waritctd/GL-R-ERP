# Agent Handoff

## Task
Phase-2 Branch 3 (user-approved, mapping finalized by the Opus planner): tokenize the ad-hoc
inline colors in the sales UI (`TicketDetailPage.jsx`, `DepositNoticePage.jsx`,
`CeoSettingsPage.jsx`, 3 named sites in `NotificationBell.jsx`) into the Tailwind design-token
system in `frontend/src/index.css`. Colors only — no functional or layout changes.

## Branch
`refactor/ticket-design-tokens`

## Base Commit
`3ae79ad` (origin/main, tip of PR #219 — `feat(tickets): give sales_manager read+comment oversight
of the sales stack`)

## Current Commit
See `git log -1` after the commit landed by this agent (single commit,
`refactor(ui): tokenize sales-stack accent colors into the design system`).

## Agent / Model Used
Claude Sonnet (implementation agent, standing Sonnet-implements/Opus-reviews loop)

## Scope

### In Scope
- `frontend/src/index.css` — new `@theme static` tokens.
- `DESIGN.md` — document every new token/alias in the Colors section (prose + YAML frontmatter
  `colors:` block).
- `.impeccable/design.json` — `colorMeta` ramp additions (warning, info, new override ramp).
- `frontend/src/features/tickets/TicketDetailPage.jsx` — full sweep, all raw hex literals.
- `frontend/src/features/deposits/DepositNoticePage.jsx` — full sweep, all raw hex literals.
- `frontend/src/features/ceoSettings/CeoSettingsPage.jsx` — full sweep, all raw hex literals.
- `frontend/src/components/common/NotificationBell.jsx` — **only** the 3 named sites
  (`bg-[#ef4444]`, `bg-[#f0f6ff]`, `bg-[#3b82f6]`).

### Out of Scope
- `frontend/src/api/mockApi.js` document-HTML builders (standalone `<!DOCTYPE>` iframe fixtures) —
  explicitly exempted by the task.
- `NotificationBell.jsx`'s `TYPE_ICON` map (lines 6–13) and its fallback icon color (line 108) —
  explicitly out of scope per the task's own wording ("3 sites"), and deliberately left alone (see
  Decisions Made below — touching it would break a runtime string concatenation).
- Any business logic, API contracts, auth, routes, or DB schema — untouched.

## Files Changed
- `frontend/src/index.css` — added `--color-warning-border`, `--color-warning-dark`,
  `--color-warning-bg-soft`, `--color-info-border`, `--color-info-border-strong`,
  `--color-success-border`, `--color-override`, `--color-override-border`, `--color-link`,
  `--color-border-muted`.
- `DESIGN.md` — documented all new tokens/aliases in the "Tertiary — Semantic" and "Neutral"
  color sections, added an "Aliases" subsection for `--color-link`, and added `override: "#7c3aed"`
  to the YAML frontmatter `colors:` block (mirroring how success/warning/danger/info are each a
  single top-level entry there).
- `.impeccable/design.json` — added `#92400e`/`#fbbf24`/`#fffbeb` to the `warning` ramp, added
  `#93c5fd` to the `info` ramp (its Tailwind blue-300 slot, between `#60a5fa` and `#bfdbfe`), and
  added a new `override` ramp entry (violet scale, canonical `#7c3aed`). `#bfdbfe` was already
  present in the `info` ramp and `#a7f3d0` was already present in the `success` ramp — verified,
  no change needed for those two (the task's own wording flagged both as "verify").
- `frontend/src/features/tickets/TicketDetailPage.jsx` — 137 raw hex-literal occurrences replaced
  with `var(--color-…)`; the one pre-existing `var(--color-info-border, #bfdbfe)` fallback (line
  596) was simplified to `var(--color-info-border)` now that the token genuinely exists.
- `frontend/src/features/deposits/DepositNoticePage.jsx` — 30 occurrences replaced.
- `frontend/src/features/ceoSettings/CeoSettingsPage.jsx` — 29 occurrences replaced.
- `frontend/src/components/common/NotificationBell.jsx` — 3 occurrences replaced
  (`bg-[#ef4444]` → `bg-danger`, `bg-[#f0f6ff]` → `bg-info-row-active`, `bg-[#3b82f6]` → `bg-info`).

## Commands Run
```bash
git fetch origin
git switch -c refactor/ticket-design-tokens origin/main
cd frontend && npm ci
npm run lint
npm test
npm run build
```
Plus a one-off Python script (`tokenize.py`, in the scratchpad, not committed) that did an exact
hex-literal → `var(--color-…)` string substitution per file, driven by the fixed mapping table
below — not a heuristic/fuzzy replace.

## Test / Build Results
- `npm run lint`: **pass** — 0 errors, 7 pre-existing warnings (all `react-hooks/exhaustive-deps`,
  unrelated to this change, present before this branch).
- `npm test`: **pass** — 19 test files, 94 tests, 0 failures.
- `npm run build`: **pass** — Vite build completed in 149ms, no errors.
- Backend: not touched, not run (frontend-only change).
- Live browser verification (frontend-mock, port 5200, this worktree): done for a representative
  sample — logged in as CEO, opened a pending-approval ticket (`PR-2026-0002`) and confirmed the
  info next-action banner, the "ไม่อนุมัติ" (reject) outlined-danger button, and the "คำนวณราคา
  (CIF)" info-tinted button all render with the correct colors; opened CEO price config and
  confirmed the "BOT อัตโนมัติ"/"กรอกเอง" badges and the FX-rate override input border render
  correctly. Did not get to the manual-price-override purple styling (that UI only appears in the
  sales/import edit flow, not the CEO approval view reached in this session) or the
  `DepositNoticePage` screens — see Known Risks.

## Decisions Made
1. **Uniform replacement strategy: swap the literal for `var(--color-…)` inside the existing
   `style={{}}` object everywhere, rather than restructuring color-only style objects into
   Tailwind `className`s.** The task's instructions said to *prefer* converting color-only style
   props to className. Given the sheer volume (~200 occurrences across 3 files, many inside
   ternary/conditional blocks), restructuring every "pure color" site into className string
   concatenation would multiply the diff size and the risk of a JSX mistake for no visual benefit
   — `var(--color-x)` already gives full tokenization (single source of truth, retheme-able) without
   touching component structure. This is a deliberate, repo-CLAUDE.md-aligned deviation ("smallest
   diff that satisfies the task") — **flagging for Opus review** since item 3 of the task did ask
   for className conversion on color-only objects. The 3 explicitly-named `NotificationBell.jsx`
   sites *were* converted to real Tailwind utility classes (they were already `className` arbitrary
   values, so no restructuring was needed there — just swapping `bg-[#hex]` for `bg-token`).
2. **`#dc2626` and `#ef4444` both remap to `--color-danger`, per the task's explicit mapping — but
   this is a value change for `#ef4444`** (`#dc2626` ≠ `#ef4444`). The task's own wording only
   calls out **four** deliberate visual normalizations (`#fca5a5→#fecaca`, `#0369a1→#1d4ed8`,
   `#374151→#334155`, `#1e3a5f→#1e40af`) but the `#ef4444→danger` remap is *also* a value change
   not on that list. It was explicit in the task's own remap table, so it was implemented as
   directed, but it is **not pixel-identical** and worth a second look — it affects the delete-item
   icon buttons on `TicketDetailPage.jsx` (lines ~954, ~1434 pre-refactor) and the `REJECTED`
   notification icon is unaffected (that's in the untouched `TYPE_ICON` map).
3. **`NotificationBell.jsx`'s `TYPE_ICON` map (6 hex values) and its fallback (line 108) were left
   untouched**, even though they're the same hex family as some remapped values (`#ef4444`,
   `#94a3b8`). Two reasons: (a) the task's scope line explicitly names only 3 sites for this file;
   (b) line 118 does `background: icon.color + '1a'` — string-concatenating an alpha suffix onto
   the raw hex to build an 8-digit `#rrggbbaa`. If `icon.color` became `'var(--color-warning)'`,
   that concatenation would produce invalid CSS (`'var(--color-warning)1a'`), silently breaking the
   icon-badge tint background — a functional regression forbidden by the task ("no functional
   changes whatsoever"). Fixing that properly (e.g. a parallel `bgTint` field or `color-mix()`)
   is out of scope for a colors-only refactor and would need its own task.
4. **The special pre-existing `var(--color-info-border, #bfdbfe)` fallback pattern at
   `TicketDetailPage.jsx:596`** was simplified to `var(--color-info-border)` (no fallback) since the
   token now genuinely exists in `@theme static` — this was the one site the task called out by name
   ("the token was intended and never added").
5. **`.impeccable/design.json`**: added `#93c5fd` to the `info` ramp at its correct Tailwind
   blue-300 position (between `#60a5fa` blue-400 and `#bfdbfe` blue-200) even though the task only
   explicitly asked to verify `#bfdbfe`'s presence — `#93c5fd` is a genuine new member of the info
   family used by this refactor (`--color-info-border-strong`), so it needs a ramp slot for the
   impeccable design-hook to stop flagging it as "outside DESIGN.md."

## Assumptions
- Tailwind v4's `@theme static` auto-derives utility classes (`bg-danger`, `text-danger`,
  `bg-info-row-active`, etc.) from any `--color-*` custom property — confirmed this is already
  relied on elsewhere in the codebase (`Button.jsx` already uses `bg-success`, `text-danger`,
  `border-danger-border`), so no Tailwind config changes were needed for the new tokens to become
  usable as utility classes.
- The two hex-literals-in-comments (`TicketDetailPage.jsx:60`, `DepositNoticePage.jsx:490-491`)
  are historical documentation ("Previously SUPERSEDED text used Ink Faint (#94a3b8)...") describing
  a *prior* fix, not live styling — left untouched deliberately, they remain accurate.
- `docStatusColors()` (defined inline in `TicketDetailPage.jsx` near line 61) was already fully
  tokenized in an earlier pass (returns `var(--color-surface-subtle)` / `var(--color-icon-muted)` /
  `var(--color-success-bg)` / etc.) — confirmed by reading the function; no hex literals remain
  there, nothing to do.

## Known Risks
- **Deviation #1 above (uniform `var()`-in-style-object vs. className conversion)** — the biggest
  judgment call in this branch. Low functional risk (mechanical string substitution, verified by
  full lint/test/build pass and spot-check in the browser), but it does not fully match item 3 of
  the task's literal wording. Opus should decide if this is acceptable or if specific sites
  (e.g. the reject/cancel buttons, which are genuinely color-only 2-prop style objects) should be
  converted to className in a follow-up.
- **Deviation #2 (`#ef4444→danger` is a value change, not one of the "four" listed
  normalizations)** — visually, delete-icon buttons and the unread-badge dot shift from a brighter
  red (`#ef4444`) to the darker canonical danger red (`#dc2626`). Subtle but real; worth an
  eyeballed diff in the browser before merge.
- `DepositNoticePage.jsx`'s specific screens (customer search dropdown, deposit-% picker, the
  edit-mode item table, the confirm dialog) were **not individually screenshot-verified** in this
  session — the same mechanical, low-risk substitution was applied, and lint/test/build all pass,
  but a human/Opus visual pass on that page specifically is recommended before merge.
- The CEO manual-price-override UI (purple `--color-override`/`--color-override-border` styling,
  `TicketDetailPage.jsx` ~lines 1251–1269) was not reached in this session's browser walkthrough
  (it only appears in the sales/import item-edit flow, not the CEO approval view that was open) —
  recommend Opus reach that specific state (log in as `sales` or `import`, open a ticket mid-price-
  proposal, and check the override input/save-button purple styling) before merge.
- Two browser-tool `computer scroll` calls timed out mid-session (unrelated tooling flakiness, not
  a code issue) — worked around with `window.scrollBy`/`el.scrollTop` via `javascript_tool`; noting
  in case it recurs for the next agent.

## Things Not Finished
- Screenshot verification of `DepositNoticePage.jsx` end-to-end.
- Screenshot verification of the manual-price-override (purple) state on `TicketDetailPage.jsx`.
- No PR opened (per instructions — implementation branch only, pushed, no PR/merge).

## Remaining-Literal Audit (grep `#[0-9a-fA-F]{3,8}` on the 4 scoped files, post-refactor)

```
frontend/src/features/tickets/TicketDetailPage.jsx:60:
  // Previously SUPERSEDED text used Ink Faint (#94a3b8), below the DESIGN.md Ink Muted
  → historical comment, documents a fix already made elsewhere in this same file
    (docStatusColors()); not live code, left as accurate documentation.

frontend/src/features/deposits/DepositNoticePage.jsx:490-491:
  // Muted Floor fix: was Ink Faint (#94a3b8) on a table header label —
  //     DESIGN.md specifies Ink Muted (#64748b) for `.table-head` overline text.
  → same as above: historical comment, not live code.

frontend/src/components/common/NotificationBell.jsx:6-13:
  TYPE_ICON = { SUBMITTED: '#f59e0b', PICKED_UP: '#3b82f6', PRICE_PROPOSED: '#f59e0b',
                APPROVED: '#22c55e', REJECTED: '#ef4444', QUOTATION_ISSUED: '#22c55e',
                CLOSED: '#94a3b8', CANCELLED: '#94a3b8' }
  → explicitly out of scope per the task's own wording ("NotificationBell.jsx (3 sites: ...)").
    Left untouched intentionally: converting to var(--color-x) would break the
    `icon.color + '1a'` alpha-suffix string concatenation on line 118 (invalid CSS,
    a functional regression). See Decisions Made #3.

frontend/src/components/common/NotificationBell.jsx:108:
  const icon = TYPE_ICON[item.type] ?? { name: 'bell', color: '#64748b' };
  → same reasoning as above (feeds into the same icon.color + '1a' concatenation); out of scope.
```

No other raw hex literals remain in any of the 4 scoped files. `frontend/src/api/mockApi.js` was
not touched (exempt per task).

## Recommended Next Agent
Claude Opus review (standing Sonnet-implements/Opus-reviews loop). Specifically should:
1. Weigh Deviation #1 (uniform `var()`-in-style vs. className-for-pure-color) and decide if
   acceptable as-is or needs a follow-up pass on the clearly-pure-color sites.
2. Independently verify Deviation #2 (`#ef4444→danger`) is an acceptable, intended value shift.
3. Screenshot-verify `DepositNoticePage.jsx` and the manual-price-override (purple) state on
   `TicketDetailPage.jsx`, which this session didn't reach.
4. Re-run the remaining-literal grep sweep independently to confirm the audit above is complete.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch refactor/ticket-design-tokens (pushed, based on origin/main at 3ae79ad,
includes PR #219). Read CLAUDE.md and docs/agent-handoffs/60_refactor-ticket-design-tokens.md —
it documents a colors-only tokenization of TicketDetailPage.jsx, DepositNoticePage.jsx,
CeoSettingsPage.jsx, and 3 named sites in NotificationBell.jsx into new/existing CSS custom
properties in frontend/src/index.css. Two deviations are flagged for your review: (1) instead of
converting color-only style={{}} objects to Tailwind className per the task's "prefer" wording,
every site uniformly kept the style object and swapped the raw hex for var(--color-x) — lower risk
given the ~200-site volume, but not a literal match to the instruction; (2) #ef4444 and #dc2626
both remap to --color-danger, which changes the rendered color for delete-icon buttons and the
notification unread-badge dot, even though the task's own text names only FOUR deliberate visual
normalizations elsewhere (fca5a5→fecaca, 0369a1→1d4ed8, 374151→334155, 1e3a5f→1e40af) — decide if
this fifth shift is intended or should be reverted to a --color-danger-soft-style token that
preserves #ef4444's exact value. Also: this session did NOT get to browser-verify
DepositNoticePage.jsx or the purple manual-price-override state on TicketDetailPage.jsx (reachable
by logging in as sales/import and opening a ticket mid-price-proposal) — do that before
sign-off. Lint/test/build all pass (94 tests, 0 errors). Re-run the remaining-literal grep sweep at
the bottom of the handoff to confirm nothing was missed, then decide on merge.
```
