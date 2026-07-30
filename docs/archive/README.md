# Archived docs

These are **historical / superseded** pre-implementation planning documents for the sales/CRM stack,
kept for reference only. They should **not** be treated as active guidance — see
[`../../CLAUDE.md`](../../CLAUDE.md) for the current rules and scope.

Two things these files say that are no longer true:
- They describe the sales/CRM stack as **frozen for v0.1.0**. It has been **unfrozen since
  2026-07-16** and is part of the current release line, including an approved deal/pricing-request
  redesign. v0.1.0 was the HR-core-only release and is historical.
- They predate the shipped implementations they plan for, so where they disagree with the code, the
  code wins.

- `M0_SURVEY.md` — early repo audit & plan adjustments. Superseded by the 2026-07-07 stabilization
  audit, which lived at `docs/agent-handoffs/01_STABILIZATION_AUDIT.md` until that corpus was retired
  in 2026-07; read it from git history if needed (`git log --diff-filter=D --oneline --
  docs/agent-handoffs`, then `git show <sha>^:docs/agent-handoffs/01_STABILIZATION_AUDIT.md`).
- `QUOTATION_AND_REVISION_PLAN.md` — pre-implementation plan for the quotation revision flow +
  deposit-notice auto-generation. Since shipped; see [`../sales-workflow.md`](../sales-workflow.md)
  for the current-state workflow.
- `TICKET_DASHBOARD_PLAN.md` — pre-implementation plan for the ticket + dashboard sales module.
  Since shipped; see [`../sales-workflow.md`](../sales-workflow.md).
- `quotation_template_source.xlsx` — source spreadsheet template used while designing the quotation
  documents (binary reference asset).
