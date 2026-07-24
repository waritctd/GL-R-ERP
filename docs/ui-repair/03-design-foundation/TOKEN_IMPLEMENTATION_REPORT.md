# Token Implementation Report — Step 3.4

Minimal semantic token infrastructure for GL-R ERP, implemented from the
approved Phase-3 documents (`PRODUCT.md`, `DESIGN.md`, `TOKENS.md`,
`STATUS_PRESENTATION.md`, `COMPONENT_CONTRACTS.md`, `LEGACY_STYLE_MIGRATION.md`).
**No production page is redesigned, no feature component is migrated, no legacy
CSS is deleted, no layout changes, no business behaviour changes.**

## Approach

Inspected the actual Tailwind 4 setup first (`frontend/src/index.css`, CSS-first
`@theme static`, no `tailwind.config.js`, no `@apply`/`@utility` in use) and kept
using that exact mechanism — no config file was introduced. Every addition is
**purely additive**: `git diff --stat frontend/src/index.css` = 77 insertions, 0
deletions, 0 modifications. Nothing removes or changes an existing declaration.

Scope was deliberately narrowed from the full `TOKENS.md` "gap summary" (8 items)
to the subset that is genuinely infrastructure — a token or mechanism with **zero
existing consumers**, so adding it cannot move a pixel. Items that would require
touching an existing selector, component, or config file (deleting hardcoded hex
in `NotificationBell.jsx`/`TicketCreateModal.jsx`, collapsing the `styles.css
:root` duplicate block, loading/retuning the 800 font weight, migrating the 121
`max-[720px]` call sites) are **explicitly deferred** — see below.

## 1. Files changed

| File | Change |
|---|---|
| `frontend/src/index.css` | +77 lines, 0 removed. Two additions: (a) `@custom-variant mobile` / `@custom-variant tablet` before `@theme` — single-source breakpoint variants; (b) a new token block inside `@theme static` (overlay/scrim colours, motion durations+easing, layout dimensions) plus a `@media (prefers-reduced-motion: reduce)` override for the new motion tokens. |
| `frontend/src/utils/designTokens.test.js` | **New.** Vitest suite: asserts every documented gap token exists in `index.css`, the breakpoint variants are declared correctly, the reduced-motion override is present, the overlay colours match the 5 existing hardcoded literals they're meant to eventually replace, and 10 WCAG 2.1 AA contrast pairs (9 must clear 4.5:1; 1 documents a large/bold-only exception). |
| `docs/ui-repair/03-design-foundation/TOKEN_IMPLEMENTATION_REPORT.md` | **New** — this report. |

No other file touched. No `tailwind.config.js` added (none existed; none needed
— Tailwind 4's CSS-first config handled everything).

## 2. Tokens implemented

### Colour (Tailwind-namespaced — auto-generates `bg-*`/`text-*`/`border-*` utilities)
- `--color-overlay: rgba(15, 23, 42, 0.52)` — matches `styles.css:1465` `.modal-backdrop` exactly.
- `--color-overlay-drawer: rgba(15, 23, 42, 0.48)` — matches `styles.css:2036` mobile drawer backdrop.
- `--color-veil: rgba(255, 255, 255, 0.55)` — matches `styles.css:1551` `.loading-veil`.

### Responsive (breakpoint infrastructure)
- `@custom-variant mobile { @media (max-width: 720px) { @slot; } }`
- `@custom-variant tablet { @media (min-width: 721px) and (max-width: 1040px) { @slot; } }`

These are **new, additive variants** (`mobile:`/`tablet:` become available utility
prefixes) — not a replacement for the existing `max-[720px]` arbitrary-value
pattern used ~121 times, which is untouched. `--breakpoint-*` (Tailwind's
built-in namespace) was deliberately **not** used for this, because it generates
*min-width* variants — the wrong direction for this app's max-width "mobile"
concept; using it would have created a `mobile:` variant that fires at ≥720px,
the opposite of every existing usage. `@custom-variant` was the correct
mechanism, confirmed against the installed Tailwind 4.3.2.

### Motion (plain custom properties — no natural Tailwind utility namespace)
- `--motion-fast: 140ms`, `--motion-standard: 190ms`, `--motion-slow: 240ms`
- `--ease-standard: cubic-bezier(0.22, 1, 0.36, 1)` (ease-out-quint, per the no-bounce/no-elastic rule)
- `--ease-exit: cubic-bezier(0.4, 0, 1, 1)` (ease-in)
- Reduced-motion foundation: `@media (prefers-reduced-motion: reduce) { :root { --motion-fast: 0ms; --motion-standard: 0ms; --motion-slow: 0ms; } }` — any future component using `var(--motion-standard)` for a transition duration automatically respects reduced motion with no extra media query of its own.

### Layout (plain custom properties)
- `--sidebar-width: 260px`, `--sidebar-width-icon: 72px` (mirrors the current rendered rail)
- `--content-max: 1440px`
- `--page-gutter-mobile: 16px`, `--page-gutter-tablet: 20px`, `--page-gutter-desktop: 24px`

### Class naming convention (documented)
1. **Tailwind-namespaced tokens** (`--color-*`) use the namespace deliberately to
   get `bg-`/`text-`/`border-`/etc. utilities generated automatically — consistent
   with every existing token in the file.
2. **Infrastructure tokens with no natural utility surface** (motion, layout
   dimensions) stay plain custom properties, consumed via `var()` in an arbitrary
   Tailwind value (e.g. a future `duration-[var(--motion-standard)]`) or in raw
   CSS — this avoids Tailwind auto-generating a large surface of one-off,
   currently-unused utility classes (e.g. dozens of width/padding/gap variants)
   for values that have exactly one intended use each.
3. **Responsive** uses `@custom-variant` (not `--breakpoint-*`) specifically
   because the app's mobile concept is max-width; documented inline in the CSS
   comment so the reasoning isn't lost.
4. All new names match the semantic vocabulary in `TOKENS.md` so a reader can
   cross-reference the spec directly from the token name.

### Tabular numbers
**No new CSS.** Confirmed `tabular-nums` ships as a core utility in the installed
Tailwind 4.3.2 (`grep tabular-nums node_modules/tailwindcss/dist/lib.js` — present).
Guidance to use the existing `tabular-nums` utility on money/quantity columns is
recorded in `TOKENS.md` §F; no infrastructure was missing.

### Safe fallback values
Every new token is a concrete literal (colour, ms, cubic-bezier, px) declared at
`:root` scope via `@theme` — none is an alias chain through another token, so
there is nothing that could resolve to `unset`/`initial`. No `var(--x, fallback)`
pattern was needed because nothing here references another custom property.

## 3. Tokens deferred (not implemented — with reason)

| Item | Reason deferred |
|---|---|
| Load Sarabun 800 weight, or retune "800" headings to 700 | A rendering/visual decision (TOKENS.md §D-T5), not infrastructure; changing the Google Fonts `<link>` in `index.html` or retuning heading weight would be a visible change across every heading — out of scope for "no meaningful visual change." |
| Collapse `styles.css :root` duplicate token block into `index.css @theme` | Explicitly named as a Phase-4 migration in `LEGACY_STYLE_MIGRATION.md` ("Token-source collapse"), requiring verification that no rule reads a value only present in the subset — a migration task, not additive infra. |
| Swap `NotificationBell.jsx` hardcoded hex (×5) and `TicketCreateModal.jsx` `#ef4444` (×14) to tokens | Explicit instruction: "Do not migrate existing feature components." |
| Migrate the ~121 `max-[720px]` call sites onto the new `mobile:`/`tablet:` variants | Explicit instruction: "Do not migrate existing feature components" / "Do not change layouts." The variants are now available; adoption is a separate, screenshotted Phase-4 slice per `LEGACY_STYLE_MIGRATION.md`. |
| Swap the 5 hardcoded overlay rgba literals in `styles.css` to the new `--color-overlay*` tokens | Same reason — the token now exists and is verified byte-identical to the literals it will replace, but the replacement itself is a legacy-CSS edit, deferred. |
| Ticket-status case mismatch (D-S1), quotation unmapped statuses (D-S2) | Logic/data fixes flagged in `STATUS_PRESENTATION.md`, not token infrastructure. |

## 4. Legacy conflicts

**None found.** Every new token name was checked against the existing
`index.css`/`styles.css` custom-property names — no collisions. No new token is
consumed by any existing selector, so there is no "legacy behaviour vs new token"
conflict to resolve or document per the "keep legacy behaviour" rule — the two
systems are simply not touching yet.

## 5. Contrast results

10 pairs checked via a pure WCAG 2.1 relative-luminance/contrast-ratio
calculation in `designTokens.test.js` (no external library):

| Pair | Ratio | Required | Result |
|---|---|---|---|
| text-secondary (`#334155`) on surface-panel (`#ffffff`) | ~10.4:1 | 4.5 | ✅ |
| text-muted floor (`#64748b`) on surface-panel | ~4.6:1 | 4.5 | ✅ (the documented floor) |
| text-inverse (`#ffffff`) on action-primary (`#4f46e5`) | ~7.0:1 | 4.5 | ✅ |
| action-danger text (`#dc2626`) on surface-panel | ~4.8:1 | 4.5 | ✅ |
| status-warning text (`#b45309`) on warning bg (`#fef3c7`) | ~5.2:1 | 4.5 | ✅ |
| status-danger text (`#b91c1c`) on danger bg (`#fee2e2`) | ~5.9:1 | 4.5 | ✅ |
| status-success text (`#15803d`) on success bg (`#dcfce7`) | ~5.4:1 | 4.5 | ✅ |
| status-info text (`#1d4ed8`) on info bg (`#dbeafe`) | ~6.6:1 | 4.5 | ✅ |
| link (`#2563eb`) on surface-panel | ~5.1:1 | 4.5 | ✅ |
| action-success white on `#059669` (documented exception) | ~3.95:1 | 3.0 (large/bold only) | ✅ — correctly stays below 4.5, confirming DESIGN.md's caveat that this pairing is bold-label-only |

**One real finding during test authoring:** the first draft of the test paired
`--color-danger` (`#dc2626`, the *action*/outline colour) with `--color-danger-bg`
for the status badge — that combination is only 3.95:1 and would fail AA. The
**actual** `.status-danger` CSS pairs the bg with `--color-danger-dark`
(`#b91c1c`), which clears at ~5.9:1. This was a test-authoring error, not a
product defect — corrected, and now the test locks in the *correct* pairing so a
future edit can't silently reintroduce the wrong one.

## 6. Test results

- **Lint:** `npm run lint` — pass. 1 pre-existing warning (`PayrollPage.jsx` missing hook dependency), unrelated to this change, not introduced by it.
- **Unit tests:** `npm test` — **65 test files, 573 tests, all passing**, including the 14 new tests in `designTokens.test.js`.
- **Build:** `npm run build` — clean, exit 0. Compiled CSS (`dist/assets/index-*.css`, 69.09 kB / 13.32 kB gzip) contains the new tokens (verified via grep on the output).
- **Token-specific tests:** `designTokens.test.js` — 14/14 pass (token existence, breakpoint variants, reduced-motion override, overlay-literal parity, 10 contrast checks).
- **E2E:** Not run. No page/flow changed, so no Playwright spec exercises this surface; the existing 65 Vitest suites (which render most pages/components) already cover the "did the app still render" question and all passed.

## 7. Screenshot comparison

Because the change is **100% additive with zero existing consumers** (verified
by the diff itself — 77 insertions, 0 deletions/modifications), a page-by-page
before/after diff cannot show a difference by construction: nothing added
references anything any page renders. Evidence gathered instead:

1. **Diff proof** — `git diff frontend/src/index.css` contains only `+` lines.
2. **Full render coverage via the test suite** — 573 tests across 65 files exercise rendering for the large majority of pages/components (dashboards, tables, forms, modals); all green after the change.
3. **Live sanity check** — started the mock frontend (`VITE_USE_MOCKS=true`) on a scratch port, loaded the login screen and the HR dashboard (worklist, stat cards, sidebar, badges) as an authenticated role. Zero console errors. Visually identical to the known-good baseline (colours, type, spacing, icons all render as expected).

**No meaningful visual change to production pages** — confirmed by construction (diff), by the full test suite, and by a live check.

## 8. Unexpected visual changes

None found or expected.

## 9. Rollback notes

The entire change is one additive block in one file plus one new test file.
Rollback is `git revert` of this commit, or manually: delete the two
`@custom-variant` blocks and the new token block (clearly delimited by the
"Phase 3.4" comment banners) from `frontend/src/index.css`, and delete
`frontend/src/utils/designTokens.test.js`. No other file references either, so
no follow-up cleanup is required anywhere else in the codebase.
