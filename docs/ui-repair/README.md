# UI Repair

A controlled, multi-phase repair of the GL-R ERP frontend's UI/UX. The goal is a
calmer, more consistent, more task-usable interface **without** changing business
logic, routes, permissions, status transitions, or schema. This directory is the
shared workspace and paper trail for that effort.

> **Read the governance docs before doing any UI-repair work.** They are the rules
> of engagement, the recorded baseline, and the per-change checklist.

## Phase map (this effort covers Phases 0–3 only)

| Phase | Name | Output | Touches production UI? |
|------|------|--------|------------------------|
| **0** | Guardrails & baseline | This tree; the three governance docs; a verified baseline | No |
| **1** | Audit | Findings in `01-audit/` (evidence-backed, current-state) | No |
| **2** | Information architecture | Nav / route / role IA proposals in `02-information-architecture/` | No |
| **3** | Design foundation | Token/primitive consolidation notes in `03-design-foundation/` | No — analysis & proposals only |
| 4+ | Production repair | *(out of scope here)* | Yes — not before Phase 4 |

**No production screen is redesigned before Phase 4.** Phases 0–3 are audit,
architecture, and foundation work only.

## Contents

- [`00-governance/UI_REPAIR_RULES.md`](00-governance/UI_REPAIR_RULES.md) — scope,
  what must not change, and the rules every phase follows.
- [`00-governance/BASELINE.md`](00-governance/BASELINE.md) — the recorded,
  evidence-based baseline (stack, structure, roles, routes, test/build results).
- [`00-governance/CHANGE_CONTROL.md`](00-governance/CHANGE_CONTROL.md) — the
  checklist every UI change must satisfy during the repair.
- `01-audit/`, `02-information-architecture/`, `03-design-foundation/` — filled in
  during Phases 1–3.
- `evidence/current/`, `evidence/proposed/` — screenshot evidence (desktop +
  mobile). Large binaries follow the repo's existing gitignore/LFS conventions.

## Existing assets this effort builds on (do not duplicate)

These already exist and are the sources of truth — reference and extend them; do
not restate or fork them:

- [`../../frontend/.claude/rules/frontend-ui.md`](../../frontend/.claude/rules/frontend-ui.md)
  — the frontend design charter ("Operations Control Desk"), path-scoped to
  frontend source/test files. The design law for UI work.
- [`../../DESIGN.md`](../../DESIGN.md) — design tokens and design language (palette,
  type ramp, radii, spacing, per-component specs). The token vocabulary.
- [`../ux-ui-audit/`](../ux-ui-audit/) — the prior UX/UI audit (report, route
  inventory, machine-readable findings). Prior art for Phase 1.
- [`../../CLAUDE.md`](../../CLAUDE.md) and
  [`../agent-handoffs/00_MASTER_CONTEXT.md`](../agent-handoffs/00_MASTER_CONTEXT.md)
  — repo-wide agent operating rules and product identity.
