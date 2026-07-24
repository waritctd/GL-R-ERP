# Phase 3 — Design Foundation

Phase 3 locks a coherent visual system **before** any shared component is built or
any page is redesigned. It is **not** a page-redesign phase — no production screen
changes here (that is Phase 4+). The work is analysis, reconciliation, and
proposals only.

The sanctioned design vocabulary is not invented here; it already exists and is the
source of truth:

- [`../../../DESIGN.md`](../../../DESIGN.md) — tokens, type ramp, component specs.
- [`../../../frontend/src/index.css`](../../../frontend/src/index.css) — the
  `@theme` tokens the app actually consumes.
- [`../../../PRODUCT.md`](../../../PRODUCT.md) — strategic product context (who /
  what / why).

Phase 3's job is to (a) make sure that vocabulary is **current and honest** against
the Phase 1 audit and Phase 2 architecture, and (b) write down the token/primitive
consolidation the later repair will follow — without building it yet.

## Steps

| Step | Name | Output |
|------|------|--------|
| **3.1** | Product & design context refresh (`/impeccable init`) | Refreshed `PRODUCT.md` + `DESIGN.md` (root); [`STEP_3.1_PRODUCT_DESIGN_RECONCILIATION.md`](STEP_3.1_PRODUCT_DESIGN_RECONCILIATION.md) |
| **3.2** | Design language + semantic tokens | Revised `PRODUCT.md` + 21-section `DESIGN.md` (root); the four docs below |

## Documents (Step 3.2)

- [`../../../PRODUCT.md`](../../../PRODUCT.md) — strategic product context (purpose, roles, jobs, operational risks, device contexts, Thai-first, sensitive-data, personality, anti-references, success criteria, what it is not).
- [`../../../DESIGN.md`](../../../DESIGN.md) — the 21-section design language (north star → screenshot checklist). Frontmatter carries the machine-readable tokens.
- [`TOKENS.md`](TOKENS.md) — the semantic token architecture: colour/text/border/action/status, typography (incl. Thai), spacing/density, shape, elevation, motion, layout/breakpoints — purpose, prohibited uses, values, contrast, gaps.
- [`STATUS_PRESENTATION.md`](STATUS_PRESENTATION.md) — every status & the 9 work-states: Thai label, tone, badge-vs-text, actionability, responsible role, defects, misuse.
- [`COMPONENT_CONTRACTS.md`](COMPONENT_CONTRACTS.md) — styling/behaviour contracts for 21 Phase-4 components (which exist vs. proposed), states, a11y, tokens, anti-patterns, build order.
- [`LEGACY_STYLE_MIGRATION.md`](LEGACY_STYLE_MIGRATION.md) — how the 2,213-line `styles.css` is retired safely (boundaries, token swaps, regression guards, evidence gate, no bulk conversion).
- [`DECISION_LOG.md`](DECISION_LOG.md) — foundation decisions, the Impeccable-output review (accept/reject/retain/deprecate), and open risks before token implementation.

## Guardrails (from [`../00-governance/UI_REPAIR_RULES.md`](../00-governance/UI_REPAIR_RULES.md))

- **No new one-off colors, shadows, radii, spacing, buttons, dialogs, table
  styles, or breakpoints.** Reuse the existing token/primitive; if none fits,
  *propose* a shared one here rather than inventing a local value.
- **No production UI change** before Phase 4. Editing `DESIGN.md` **prose** and
  `PRODUCT.md` is in scope; changing a token **value** (which the app renders) is
  not, unless it is stated plainly as its own change and follows Change Control.
- **Do not fork the sources of truth.** These notes reference and extend
  `DESIGN.md` / `index.css`; they never restate or duplicate the token values.
