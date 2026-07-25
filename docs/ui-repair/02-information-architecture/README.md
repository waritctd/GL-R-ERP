# Phase 2 — Workflow & Information Architecture

Analysis-only. **No production JSX, CSS, routes, APIs, backend services, permission
logic, statuses, or schema were modified in this phase.** These documents define how
GL-R ERP *should* organise work before Phase 3 (design foundation) and Phase 4+
(implementation) decide how it should look.

Everything here is reconciled against the **actual implementation** (Java services are
the authority for permissions per `CLAUDE.md`; the frontend mirror and mock are not).
Where the product hypothesis and the code disagree, the disagreement is documented,
not smoothed over — see [`IA_DECISION_LOG.md`](IA_DECISION_LOG.md) §"Business-rule
discrepancies" and the reconciliation banner in [`ROLE_HANDOFF_MAP.md`](ROLE_HANDOFF_MAP.md).

## Documents

| File | What it defines |
|------|-----------------|
| [`ROLE_JOB_MAP.md`](ROLE_JOB_MAP.md) | Every role's jobs, owned/visible/waiting records, docs, transitions, notifications, sensitive-data limits, mobile likelihood, nav now→proposed |
| [`ROLE_HANDOFF_MAP.md`](ROLE_HANDOFF_MAP.md) | Every cross-role handoff: trigger, pre/post status, manual/auto, cancel/return, notification, what each side sees while waiting, audit |
| [`WORK_STATE_MODEL.md`](WORK_STATE_MODEL.md) | The 9 UX work-states and the mapping from backend status + actor context → work-state (no schema change) |
| [`INFORMATION_ARCHITECTURE.md`](INFORMATION_ARCHITECTURE.md) | Work-oriented top-level nav concepts, per role, with badges and mobile priority |
| [`NAVIGATION_MIGRATION_MAP.md`](NAVIGATION_MIGRATION_MAP.md) | Item-by-item map from today's nav to the proposal; routes preserved vs. later migration; deep-links that must stay compatible |
| [`ROLE_LANDING_STRATEGY.md`](ROLE_LANDING_STRATEGY.md) | Per-role landing archetype (queue / pipeline / inbox / overview / self-service) and full spec |
| [`TICKET_INFORMATION_ARCHITECTURE.md`](TICKET_INFORMATION_ARCHITECTURE.md) | The canonical deal record: 18 information regions placed into header / tabs / context / action-bar / mobile |
| [`SALES_LIFECYCLE_REVIEW.md`](SALES_LIFECYCLE_REVIEW.md) | Step 2.2 deep review of the sales vertical: 18-stage lifecycle table (16 attributes each), 11 problem classes, Mermaid diagram with backend names, out-of-scope backend recs, verdict |
| [`CREATE_TICKET_FLOW.md`](CREATE_TICKET_FLOW.md) | The create-deal workflow and a reasoned container decision |
| [`PAGE_PATTERN_CATALOG.md`](PAGE_PATTERN_CATALOG.md) | Structural (not visual) patterns and which existing pages migrate to each |
| [`IA_DECISION_LOG.md`](IA_DECISION_LOG.md) | Every material decision with evidence, alternatives, risk, reversibility, and the Phase-2 completion response |
| [`IA_REDTEAM_REVIEW.md`](IA_REDTEAM_REVIEW.md) | Step 2.3 independent red-team: 27 stress scenarios, code-ground-truth spot-checks, 5 newly-surfaced gaps, risk register, exit-gate check, verdict |

## Source of truth

- Audit evidence: [`../01-audit/`](../01-audit/) and [`../evidence/current/`](../evidence/current/).
- Design law: [`../../../frontend/.claude/rules/frontend-ui.md`](../../../frontend/.claude/rules/frontend-ui.md) ("Operations Control Desk") and [`../../../DESIGN.md`](../../../DESIGN.md).
- Governance: [`../00-governance/`](../00-governance/).
- Code ground-truth was extracted this phase from the Java services and the frontend
  route/permission/nav model; `file:line` citations appear throughout.
