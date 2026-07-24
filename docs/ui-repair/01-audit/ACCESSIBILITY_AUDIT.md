# Accessibility Audit (WCAG 2.2 AA target)

Source + rendered review against the `frontend-ui.md` accessibility bar. Grounded
in `frontend/src/components/common/*` and captured evidence. Cross-refs to
[UI_AUDIT](UI_AUDIT.md). This is a heuristic + source audit, **not** a full
assistive-technology test (see AUDIT_GAPS).

## Issues

| ID | WCAG | Sev | Finding | Location |
|----|------|-----|---------|----------|
| **A-01** | 4.1.1 / 4.1.2 | P1 | **`<button>` nested inside `<button>`** — clickable `DataTable` rows are buttons whose cells contain more buttons → invalid DOM, ambiguous hit-testing, inner actions unreachable/mis-announced (= UI_AUDIT **F-02**). | `DataTable.jsx:255,360-378`; `Button.jsx:61` |
| **A-02** | 1.3.1 / 4.1.2 | P2 | **Malformed hand-rolled ARIA grid** — `role=table` on `<section>`, `role=row` on a `<button>`, `role=cell` on `<span>`, **no `rowgroup`**, interactive rows in a static `table` (= **F-07**). | `DataTable.jsx:284,286,309,311,369` |
| **A-03** | 2.4.7 | P2 | **No global visible focus ring** on buttons/links — `outline:none` stripped, ring only on inputs + a few named classes; `Button` sets none (= **F-08**). | `styles.css:240,249-254`; `Button.jsx` |
| **A-04** | 3.3.1 / 4.1.3 | P2 | **Form errors not programmatically linked** — red border + colored text only; no `aria-invalid`/`aria-describedby` (= **F-09**). Also colour-dependent. | `FormField.jsx`; `styles.css:1586-1596` |
| **A-05** | 1.3.1 / 4.1.2 | P3 | **Modal labelled by `aria-label` only** (no `aria-labelledby` to the visible `<h2>`); background not `inert`/`aria-hidden` (= **F-18**). | `Modal.jsx:53-58,65` |
| **A-06** | 4.1.2 | P3 (latent) | **Icon-only button** accessible name relies on the caller passing `aria-label` (not enforced by the `icon` variant); most callers do (= **F-20**). | `Button.jsx` |

## Verified GOOD (not violations)
- **Status not colour-only** — `StatusBadge.jsx` always renders text (+ optional
  icon); CSS pairs tinted bg with matching dark text. No WCAG 1.4.1 failure.
- **Contrast “Muted Floor” already remediated** — `--color-text-muted #64748b`
  (~4.6:1 on white, passes) is the floor for body/data text; `--color-text-faint
  #94a3b8` (~2.6:1, fails) is deliberately reserved for **icons/placeholders** and
  `.nav-item` text on the **navy sidebar** (~7.3:1). Comments at `styles.css:303-306,
  326-333,1240-1241` document the prior audit. No confirmed body-text contrast
  failure remains — **watch-only** for regressions.
- **Reduced motion handled** — three `@media (prefers-reduced-motion: reduce)`
  blocks (`styles.css:473,1700,1805,2207`).
- **Dialog focus management** — `Modal.jsx` traps focus, handles Escape, restores
  focus, sets `role="dialog"` + `aria-modal` (solid; only A-05 gaps remain).
- **Mobile drawer** is focus-trapped with Escape/backdrop close (`AppShell.jsx`).
- **Icon-only pagination/close buttons** carry `aria-label` + `title`
  (`DataTable.jsx:401,417`, `Modal.jsx:68`).

## Priority order for remediation (Phase 4+, not now)
1. **A-01 + A-02 together** — the shared `DataTable` a11y contract is the single
   highest-leverage fix (touches every list in the app). Resolve the row-as-button
   nesting and rebuild the grid/table semantics as one change.
2. **A-03** — one global `:focus-visible` token across interactive elements.
3. **A-04** — wire `aria-invalid`/`aria-describedby` into the shared `FormField`.
4. **A-05 / A-06** — modal labelling + icon-button name enforcement.

## Not covered this pass (→ AUDIT_GAPS)
Real screen-reader/keyboard walkthroughs, `axe`/automated scans against the running
DOM, colour-contrast measurement on every rendered surface (only tokens reasoned
about), and 200%-zoom reflow (WCAG 1.4.10) were not run.
