# Legacy Style Migration

How the ~2,213-line global `frontend/src/styles.css` is retired onto Tailwind 4 +
semantic tokens **without visual regressions**. **Proposal/process doc — no code
change in Phase 3.** Migration happens in Phase 4+, one verified slice at a time.

This doc governs *how*; it does not authorise any specific migration now.

## Current global CSS — the risks

- **Size & reach.** `styles.css` is 2,213 lines imported globally into
  `@layer legacy` (`index.css:4`). It styles both **generic primitives** (`.primary/
  .secondary/.danger/.icon-button`, `input`, `.status-badge`, `.modal-*`,
  `.table-head`/`.data-row`, `.stat-card`, `.toast`, `.skeleton`) **and
  page-specific selectors** (`.payroll-*`, `.commission-*`, `.ticket-items-table`,
  `.pricing-request-queue-table`, `.leave-calendar-*`, `.login-*`). The two are
  interleaved, so a careless edit to a "primitive" rule can hit a page.
- **Dual button system.** The legacy `.*-button` classes (`styles.css:583-664`)
  duplicate `Button.jsx`; 16–22 files still use the CSS classes, which don't all
  carry the 44px mobile floor / focus handling (F-13). This is the one systemic
  duplication.
- **Two token sources.** `styles.css:5-95` `:root` duplicates a **subset** of the
  `index.css:7-124` `@theme` superset. Drift risk (TOKENS §Two-sources).
- **The 720 literal ×121.** No breakpoint token; `max-[720px]` is copy-pasted
  ~121× plus 2 `@media` + 1 `matchMedia`. A change of breakpoint today means 124
  edits.
- **Hardcoded literals.** 5 rgba scrim/overlay literals (`styles.css:394,481,1465,
  1551,2036`); component hex bypasses (`NotificationBell` ×5, `TicketCreateModal`
  `#ef4444` ×14); raw px paddings (`gap:7px`, `0 16px`, `3px 10px`) bypass the space
  scale.
- **Layer-order `!important`.** Legacy is imported *before* utilities
  (`@layer theme, legacy, utilities`), so a few mobile card rules need `!important`
  to beat legacy (`styles.css:2080-2081`). Migrating a rule out of legacy can change
  which declaration wins — a silent regression source.
- **`outline:none` in 4 places** strips focus rings (A-03).

## Identifying a safe migration boundary

A slice is safe to migrate only when it is a **self-contained unit** you can prove
unchanged. In priority of safety:

1. **A shared React primitive already isolates the surface** — e.g. `Button`,
   `StatusBadge`, `Modal`. Migrating "buttons" means migrating the ~20 call sites
   that use the legacy `.*-button` class onto `<Button>`, then deleting the class.
   The primitive is the boundary.
2. **One page/feature owns its selectors** — e.g. `.payroll-*` is only used by
   `PayrollPage`. That page + its classes migrate together, then the classes go.
3. **A single generic rule with a known caller set** — grep proves exactly who uses
   it; migrate all callers, then delete.

**Never** treat "all buttons" or "all tables" as one slice across the whole app.
The boundary is *one component or one page*, verified by grep, not a global sweep.

**Boundary checklist before starting a slice:**
- `grep` every selector in the slice → a closed, known caller list.
- No caller outside the slice depends on a rule you'll change.
- The slice has before-screenshots (desktop + mobile, both scripts).
- The slice maps to existing tokens/primitives (or a proposed token is approved).

## Component-by-component process

For each slice:

1. **Inventory** — grep the legacy selectors and every call site. Record the exact
   current computed values (padding, height, colour, radius, focus) — the
   regression baseline.
2. **Map to tokens/primitives** — every arbitrary value → a semantic token
   ([`TOKENS.md`](TOKENS.md)) or the shared primitive ([`COMPONENT_CONTRACTS.md`](COMPONENT_CONTRACTS.md)).
   If no token fits, **propose** one (Change Control) — do not invent a local value.
3. **Migrate call sites** — move markup to the primitive / Tailwind utilities.
   Keep the rendered result pixel-identical unless the slice *is* an approved fix
   (e.g. adding the focus ring); state that explicitly.
4. **Verify** — lint + unit + relevant e2e; render the surface; console + failed-
   network check; **after-screenshots** desktop + mobile, both scripts; diff
   against baseline.
5. **Remove the old CSS** — only after step 3 proves no remaining caller (grep
   returns zero). See "Removing old CSS" below.
6. **Change Control** — complete the checklist; PR with before/after evidence;
   human review.

## How semantic tokens replace arbitrary values

- A literal that a token covers → the token: `#4f46e5` → `action-primary`;
  `rgba(15,23,42,0.52)` → `--color-overlay` (propose); `max-[720px]` →
  `--breakpoint-mobile` (propose); `gap:7px`/`0 16px` → the nearest `--space-*`
  **only if it matches the rendered value** — otherwise keep the exact px and log a
  one-off (a token must not silently reflow a control).
- **A migration never changes a rendered value as a side effect.** If the nearest
  token differs from the current literal, either (a) the literal was a bug and the
  change is the *point* of the slice (state it), or (b) keep the literal as a
  documented one-off until a token is agreed. Do not "round to the scale" silently.
- Collapse the `styles.css :root` block into `index.css @theme` only after
  verifying no rule reads a value present *only* in the subset.

## Avoiding visual regressions

- **Pixel-baseline every slice** (before/after, desktop 1366 + mobile 390, tablet
  768 where the shell is touched, **both Thai and English**).
- **Watch the layer order.** Moving a rule out of `@layer legacy` changes cascade
  priority vs utilities; re-check any `!important` the slice touched and confirm the
  intended declaration still wins.
- **Watch focus and touch targets** — the legacy `.*-button` mobile 44px floor and
  focus come from separate `@media`/global rules; the `<Button>` primitive must
  carry them so the migrated control keeps them.
- **Watch Thai** — line-height, truncation, and label width can shift when a rule
  moves; verify with real Thai strings.

## Removing old CSS — only after usage verification

- Delete a legacy selector **only** when `grep -rn "class-or-selector" frontend/src`
  returns zero live callers (excluding the deletion itself and tests that assert
  its absence).
- Delete in the **same PR** as the last caller migration, so the tree never carries
  dead-but-referenced CSS or referenced-but-deleted CSS.
- Re-run the full frontend build after deletion — an orphaned `@apply`/var
  reference would surface.

## Temporary compatibility styles

- Sometimes a slice must ship before every caller is migrated. A **temporary
  compatibility shim** (a legacy class kept alive, or a bridging rule) is allowed
  **only** as a documented exception with an **owner** and a **removal condition**
  (per `../00-governance/CHANGE_CONTROL.md` Exceptions).
- A shim is a countdown, not a resting state. Log it; the removal condition is
  "last caller migrated." An undocumented shim is a defect.

## Preventing duplicate Tailwind + legacy rules

- After migrating a slice, the **same visual property must not be set in both**
  the Tailwind utility and a surviving legacy rule — one will silently win by
  cascade/layer. Delete the legacy rule in the same PR (above), don't leave both.
- Prefer the primitive/utility as the single home; do not add a new
  page-specific CSS file (governance rule). If native CSS is genuinely unavoidable,
  document why (Change Control).
- No `@apply` to rebuild a second hidden design system — reuse the cva primitive
  or Tailwind utilities directly (the codebase currently uses **no** `@apply`, keep
  it that way).

## Required evidence per slice (gate to merge)

1. `npm --prefix frontend run lint` — pass.
2. `npm --prefix frontend test` (Vitest) — pass.
3. Relevant `npm --prefix frontend run test:e2e` (Playwright) — pass.
4. Before/after screenshots — desktop + mobile (+ tablet if shell), **both scripts**.
5. `grep` proof the removed selector has zero remaining callers.
6. A completed [Change Control](../00-governance/CHANGE_CONTROL.md) checklist.
7. Human review. **Authorization is never verified against the mock** — if a slice
   somehow touches a gate/scope, it needs a real-DB IT or the permission aspect is
   reported unverified (it shouldn't — a UI-repair slice must not touch authz).

## Prohibition on bulk automatic conversion

**No automated, global, or blind CSS→Tailwind rewrite.** Not a codemod over the
whole file, not "convert all `.*-button` at once," not an AI pass that rewrites
`styles.css` wholesale. The legacy stylesheet is retired **one verified component or
page at a time, with screenshots** (governance rule, restated). A bulk conversion
cannot prove per-surface visual parity and will regress Thai layout, focus, and
touch targets — it is explicitly banned.

## Suggested slice order (safest, highest-leverage first)

1. **Buttons** — migrate the ~20 legacy `.*-button` call sites onto `<Button>`;
   add the loading state + global focus ring; delete the classes. (F-13, A-03)
2. **Modal outlier** — move `ChangePasswordModal` onto `Modal.jsx`; migrate
   `ConfirmDialog` footer buttons to `<Button>`. (F-19)
3. **Colour literals** — `NotificationBell` ×5, `TicketCreateModal` `#ef4444` ×14,
   the 5 scrim rgba → tokens. (F-17, D-S3)
4. **Breakpoint token** — introduce `--breakpoint-mobile`/`-tablet`; reconcile the
   720/1040 literals; add the deliberate tablet-band behaviour. (F-01 — the highest-
   value responsive slice)
5. **DataTable** a11y/table contract; then per-page table CSS (`.payroll-*`,
   `.commission-*`, `.ticket-items-table`, …) page by page.
6. **Token-source collapse** — fold `styles.css :root` into `index.css @theme`.
7. Remaining page-specific selectors (`.leave-calendar-*`, `.login-*`, …) as their
   pages migrate.
