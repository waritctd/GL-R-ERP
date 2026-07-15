# Agent Handoff

## Task
Update the two governing docs (`CLAUDE.md` and `docs/agent-handoffs/00_MASTER_CONTEXT.md`) to
reflect that stabilization is complete and the repo has exited the "freeze new features" phase.
Triggered by a gap-analysis review of `main` against the original HR proposal, the
`ERP_Gantt_UserFlow.xlsx`, and the Sales System Proposal, which produced a prioritized 9-item
HR-core gap-closure roadmap (see below). `v0.1.0`, `v0.2.0`, and `v0.3.0` are already tagged on
`main`, and a shipped (flag-hidden) sales feature (#163) meant the "freeze" language in both docs
was already stale before this change.

## Branch
`docs/exit-stabilization-freeze`

## Base Commit
`46311ec` (`Merge pull request #173 from waritctd/test/nft-non-functional-testing`), tip of `main`
when this branch was cut. `v0.3.0` was tagged on this same commit and pushed to origin just before
this branch started.

## Current Commit
_(fill in after commit)_

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Flip `CLAUDE.md`'s "stabilization phase / freeze new features" framing to a post-`v0.3.0`
  feature phase, scoped to the HR-core gap-closure roadmap only.
- Flip the equivalent section in `00_MASTER_CONTEXT.md`, mark the v0.1.0 DoD as historically
  complete (tag now exists), and record the `v0.2.0`/`v0.3.0` releases.
- Add the 9-item HR-core gap-closure roadmap (priority order) to `00_MASTER_CONTEXT.md` so any
  agent picking up a branch has the sequence without re-deriving it.
- Refresh stale "repo quick facts" (SecurityConfig default-deny not permitAll, react-router v7
  exists, migrations now V1–V41) while already touching these files — small, factual corrections,
  not a rewrite.
- Keep the sales/CRM freeze intact — only HR-core gap work is unfrozen.

### Out of Scope
- Any code change (backend/frontend). Docs-only branch.
- Implementing any of the 9 roadmap items — those are separate branches (see next-agent prompts
  logged as this roadmap gets worked through).
- Touching `01_STABILIZATION_AUDIT.md` (historical record of the P0–P2 stabilization plan; left as
  a snapshot in time, not rewritten).
- Modifying the untracked `tools/` directory (pre-existing untracked state, not part of this task).

## Files Changed
- `CLAUDE.md`: replaced the "stabilization phase" framing and the "During stabilization —
  non-negotiable" block with a "Feature-phase rules — non-negotiable" block (features allowed per
  roadmap, sales stays frozen, no rewrite, no changing shipped business logic). Refreshed "Repo
  quick facts" (default-deny SecurityConfig, react-router v7, TanStack Query, V1–V41 migrations,
  Actuator/OpenAPI present) dated 2026-07-14 / tag `v0.3.0`.
- `docs/agent-handoffs/00_MASTER_CONTEXT.md`: replaced "Current Priority" (freeze → gap-closure),
  added the "HR-Core Gap-Closure Roadmap" section (9 items, priority order, with dependency/blocker
  notes for items 1–3 and blockers for items 4 and 9), relaxed the first "Non-Negotiable Rule" to
  permit roadmap-scoped features, marked the v0.1.0 DoD checklist's last item done (tag exists),
  added a "Releases since v0.1.0" section (`v0.2.0`, `v0.3.0`), and refreshed the "Repository
  Snapshot" section to match current `main` (migrations V1–V41, tag `v0.3.0`, scope-split note
  updated to say HR-core is open for the roadmap).

## Commands Run
```bash
git checkout main && git pull origin main
git tag -a v0.3.0 46311ec -m "..." && git push origin v0.3.0
git checkout -b docs/exit-stabilization-freeze
# edits via Edit/Write tool, no shell mutation of file contents
```

## Test / Build Results
- Frontend build: not run (docs-only change, no source touched)
- Backend tests: not run (docs-only change, no source touched)
- Lint: not run (docs-only change; no lintable files changed)

## Decisions Made
- Cut the release as `v0.3.0` (not `v0.1.0`/`v0.2.1`) after discovering `v0.1.0`/`v0.2.0` already
  existed on `main` — confirmed with the user before tagging (annotated tag, pushed to origin).
- Kept `01_STABILIZATION_AUDIT.md` untouched — it's a dated snapshot of the pre-stabilization
  audit and branch sequence; rewriting it would destroy the historical record the handoff system
  relies on.
- Left the sales/CRM freeze in place; only lifted the freeze for the 9 HR-core items in this
  review's gap analysis.

## Assumptions
- The user's approved plan (saved at
  `~/.claude/plans/users-ploy-warit-desktop-gl-r-gl-r-hr-m-toasty-sutherland.md`, not part of this
  repo) is the source of truth for the roadmap order; it was transcribed into
  `00_MASTER_CONTEXT.md` verbatim so it survives outside that local plan file.

## Known Risks
- None functional (docs-only). Risk is purely "roadmap goes stale if not kept in sync" — future
  agents completing a roadmap item should check it off in `00_MASTER_CONTEXT.md`.

## Things Not Finished
- PR not yet opened/merged (see next prompt — user asked to pass Phase 1 to Codex next; this PR
  should still be opened and merged independently since Phase 1 depends on the freeze being lifted
  in the docs agents read).

## Recommended Next Agent
Codex (implementation) for `feat/payslip-pdf` (roadmap item 1), once this PR is merged to `main`.

## Exact Next Prompt
```
Read CLAUDE.md and docs/agent-handoffs/00_MASTER_CONTEXT.md first (the freeze is lifted for the
HR-core gap-closure roadmap; sales/CRM stays frozen). Implement roadmap item 1: feat/payslip-pdf.

Branch off main: git checkout -b feat/payslip-pdf

Task: add a per-employee payroll payslip PDF, reusing existing infrastructure — do not introduce a
new PDF library or change any payroll calculation.

Reuse:
- backend/src/main/java/th/co/glr/hr/common/PdfDocumentWriter.java — shared Thai-font (Sarabun)
  PDF builder already used by deposit notices and quotations. Use it as-is.
- backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeRenderer.java — copy its toPdf() pattern
  (loadFont, text(), gap(), toBytes()) for the new PayslipRenderer.
- backend/src/main/java/th/co/glr/hr/payroll/PayrollLineDto.java — already has every field a
  payslip needs (employeeCode, employeeName, departmentName, bankName/bankAccount, baseSalary,
  dailyRate, hourlyRate, specialPayTotal, overtimePay, commissionPay, grossEarnings,
  nonTaxableIncome, unpaidLeaveDays/Deduction, grossTaxableIncome, ssoWageBase, socialSecurity,
  withholdingTax, studentLoanDeduction, legalExecutionDeduction, otherPostTaxDeductions,
  totalDeductions, netPay, calculationNote). No new query/entity should be needed.
- backend/src/main/java/th/co/glr/hr/audit/AuditService.java — audit payslip access the same way
  PayrollService.bankExport() audits "EXPORT_PAYROLL_BANK_FILE".

Build:
1. New backend/src/main/java/th/co/glr/hr/payroll/PayslipRenderer.java — byte[] toPdf(PayrollLineDto
   line, PayrollPeriodDto period) using PdfDocumentWriter, Thai-labeled sections (earnings,
   deductions, net pay), mirroring DepositNoticeRenderer's structure/pagination handling.
2. Two new endpoints on PayrollController:
   - GET /api/payroll/{periodId}/lines/{lineId}/payslip.pdf — HR/admin only (existing
     PAYROLL_VIEW_ROLES-style role check), any line in that period.
   - GET /api/payroll/{periodId}/payslip/me — any authenticated employee, but must resolve to
     *their own* payroll line only (look up by session user's employeeId; 403/404 if the period has
     no line for them). Do not let an employee pass an arbitrary lineId.
3. Audit both endpoints via AuditService (new action names, e.g. "VIEW_PAYSLIP_PDF",
   "VIEW_OWN_PAYSLIP_PDF").
4. Frontend: add a "Download payslip" button per row on frontend/src/features/payroll/PayrollPage.jsx
   (HR view) and a "My payslip" download on the employee dashboard, wired through the existing
   frontend/src/api/hrApi.js + frontend/src/api/routes.js pattern (see bankExport for the existing
   analogous wiring).

Constraints:
- No new Maven/npm dependency — PDFBox is already a transitive dep via PdfDocumentWriter.
- No change to any payroll calculation, tax, or SSO logic — this is read-only rendering of already
  -computed PayrollLineDto values.
- Do not touch the frozen sales/CRM stack.
- Keep the diff scoped to payslip rendering + the two endpoints + the two frontend buttons.

Before finishing:
- Run cd frontend && npm run lint && npm test && npm run build
- Run cd backend && ./mvnw -B clean verify (note if TEST_DB_URL-gated tests were skipped)
- Verify the rendered PDF: Thai text renders correctly (Sarabun font), figures match the payroll
  line exactly, an employee cannot fetch another employee's payslip via /payslip/me.
- Fill in docs/agent-handoffs/43_feat-payslip-pdf.md (create from the template in
  docs/agent-handoffs/README.md) with files changed, commands run, test/build results, known
  risks, and the next prompt (recommend: Claude review, then roadmap item 2 feat/payslip-email).
- Do not commit/push/open a PR unless the user asks.
```
