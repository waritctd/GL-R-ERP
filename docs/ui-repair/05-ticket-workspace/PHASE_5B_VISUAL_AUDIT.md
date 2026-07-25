# Phase 5B Visual Audit

Date: 2026-07-25

Scope: ticket workspace visual stabilization in the mock Vite app. Primary dense
state: `/tickets/13`. Roles: `sales`, `sales_manager`, `import`, `account`,
`ceo`. Viewports: `390x844`, `768x1024`, `1024x768`, `1366x768`, `1440x900`.
Tabs were inspected where role-projected: Overview, Pricing, Quotations, Money,
Fulfilment, Documents, Activity.

Evidence root:
`docs/ui-repair/evidence/proposed/phase-5b-visual-stabilization/`

## Screenshot Matrix

| Pass | Screenshots captured | Role-hidden tab cases | Page overflow cases |
| --- | ---: | ---: | ---: |
| Before | 160 | 15 | 0 |
| After | 160 | 15 | 0 |

Hidden tab cases are expected role projections: `import` cannot see
Quotations/Money and `account` cannot see Pricing.

## Issues And Fixes

| Screenshot | Tab | Role | Viewport | Issue | Fix | Before value | Final value |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `before/sales-1366x768-pricing.png` | Pricing | sales | `1366x768` | Active tab content inherited the legacy two-column detail shell, so the pricing panel sat in a constrained left column with an empty right lane. | Replaced `ticket-detail-grid` with a full-width vertical tab body. | `grid-template-columns: 2fr 1fr`; tab panel top `475px`. | `flex` column with `gap-4`; tab panel top `431px`; first panel width `1042px`. |
| `before/sales-1366x768-documents.png` | Documents | sales | `1366x768` | Documents repeated the same blank right-lane layout, making an empty state look like a missing column rather than a compact list state. | Removed the obsolete grid selector from `styles.css`; Documents now owns the full tab width. | `ticket-detail-grid` active; tab panel top `475px`. | No `ticket-detail-grid` selector remains; tab panel top `431px`; first panel width `1042px`. |
| `before/import-1024x768-fulfilment.png` | Fulfilment | import | `1024x768` | Fulfilment was visually compressed by the same grid wrapper and the process content did not use the available tablet-landscape width. | Same full-width tab body replacement across all non-Money tabs. | Tab panel top `475px`; wrapper split by `2fr 1fr`. | Tab panel top `431px`; first panel width `882px`. |
| `before/sales-1366x768-overview.png` | Overview | sales | `1366x768` | Header repeated the normal `ACTIVE` lifecycle as a badge, separated stage/status into extra visual rows, and placed refresh near the next-action line. | Hide normal active lifecycle badge, keep terminal/non-active lifecycle badges, combine stage/status into one compact row, move refresh to that utility row. | Header height `277px`; tab panel top `475px`; repeated active badge visible. | Header height `233px`; tab panel top `431px`; active lifecycle is implicit. |
| `before/sales-390x844-overview.png` | Overview | sales | `390x844` | Mobile first viewport carried the same extra active badge and refresh/action clustering, increasing cognitive load before active tab content. | Applied the same header compaction to mobile while preserving the primary action and work-state copy. | Header height `438px`; tab panel top `624px`. | Header height `422px`; tab panel top `608px`; no page overflow. |
| `before/import-1024x768-fulfilment.png` | Fulfilment | import | `1024x768` | The procurement substep row rendered nine rounded chips plus an owner pill, creating a badge wall inside one workflow row. | Replaced substep chips with one progress summary row, current/next labels, and a small progress bar. Converted owner pill to plain owner text. | Nine substep chips plus rounded role pill. | One progress row: `8 / 9`, current `ส่งมอบบางส่วน`, next `ส่งมอบครบแล้ว`; plain owner text. |
| `before/import-390x844-fulfilment.png` | Fulfilment | import | `390x844` | The same procurement chip wall wrapped heavily on mobile, pushing the active process content down. | Same compact progress summary and plain owner text on mobile. | Multiple wrapped chips. | One wrapped text row plus stable progress bar; no page overflow. |

## Normalized Patterns

1. Removed the stale tab-body grid pattern instead of patching individual tab
   widths.
2. Reduced header duplication by treating `ACTIVE` lifecycle as the default
   state and reserving badges for meaningful non-active or terminal states.
3. Kept refresh as a secondary utility action rather than part of the next
   workflow action line.
4. Replaced fulfilment sequence badges with one compact progress representation.
5. Preserved all role gates, routes, tabs, status values, workflow transitions,
   API calls, invalidation behavior, and backend code.

## Verdict

PASS for visual stabilization. The remaining visual limitations are outside this
small pass: several legacy panels still use older bordered surfaces and inline
styles, but no additional screenshot-observed inconsistency was severe enough to
justify speculative CSS rewriting in Phase 5B.
