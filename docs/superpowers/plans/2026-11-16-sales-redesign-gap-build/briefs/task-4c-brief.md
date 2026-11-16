# Task 4c brief — grouped finance worklist on "งานการเงิน" (slice D2)

Repo: GL&R ERP. Work in your own git worktree cut from `origin/develop` (`git fetch origin develop` first), branch `feat/finance-grouped-worklist`, worktree `.claude/worktrees/finance-worklist`. Frontend only. Test-first. Never push, open a PR or touch a deployed system. You do not dispatch subagents. Do not run Maven.

## Why
The owner approved a before/after review of the sales flow. An audit found the finance landing page ("งานการเงิน", for the account role and the CEO) only got the Express upload card; its worklist is still the old flat table with tabs, two selects and a "ค้างรับทั้งหมด" block. The drawing replaces it with a worklist grouped by the next thing finance must do, with the action button in each row.

## Read first
1. `CLAUDE.md` at the repo root (binding: test-first with a recorded red run, Tailwind-first, mock contract, PR-body checklist; the section on money/commission/payroll math being untouchable). Read and follow `.agents/skills/information-architecture/SKILL.md` and `.agents/skills/frontend-design/SKILL.md`.
2. The drawing: `C:\Users\user\.claude\projects\C--Users-user-Documents-GitHub-GL-R-ERP\21665dad-d5d9-4895-9692-9b2bd2afbd34\tool-results\artifact-9c52c656-1794690962-b51b.html`, `<template id="a-fin-land">` in full, its `ROWS` entry in the script near the bottom (the change bullets), and `<template id="a-fin-deal">` for how a row's action continues on the deal's finance page.
3. The audit row "งานการเงิน (landing)": `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\whats-new\docs\superpowers\plans\2026-11-16-sales-redesign-gap-audit.md` (read there; do not copy or commit).
4. Existing code: `frontend/src/features/finance/AccountFinancePage.jsx` (the page), `FinanceDealPage.jsx`, `FinanceActionModals.jsx`, `FinanceMilestoneSections.jsx`, `ReceiptsImportPage.jsx` (`ExpressUploadCard`), the helpers that decide which finance action a deal needs (search `features/tickets/accountActions.js` and `features/finance/` for them), and the import role's already-built grouped worklist as the pattern to follow for grouping and in-row actions: `frontend/src/features/dashboard/ImportOverview.jsx`. Also `api/hrApi.js` + `mockApi.js` for what the finance worklist endpoint returns.

## What to build
- The worklist on "งานการเงิน" grouped as drawn: "ยืนยันพร้อมปิดงาน", "ติดตามชำระ", "บันทึกใบกำกับ" (use the drawing's exact group labels, hints and order; if the drawing has more or differently named groups than these three, the drawing wins — quote what you used). Each group shows its count; each row shows what the drawing shows and carries the ONE action button for that group, which does exactly what the page offers for that deal today (opens the existing modal, or goes to the existing deal finance page). Empty groups follow the pattern `ImportOverview` uses.
- A deal belongs to exactly one group, decided by a small pure function built on the EXISTING "which finance action does this deal need" logic. Do not write new money rules: no new thresholds, no recomputed balances. If the data the page already receives cannot say which group a deal is in, STOP and report what is missing rather than inventing a rule.
- Deals that need nothing from finance right now are not lost: keep a way to see everything (the existing table behind an "ทั้งหมด" view or link), with its current search, filters and sorting intact. Existing deep links and query params to this page keep working.
- The page subtitle as drawn. The Express upload card stays exactly as it is, including its server-flag condition.
- The "ค้างรับทั้งหมด" figure: keep it if the drawing keeps it; if the drawing drops it, keep it reachable in the "ทั้งหมด" view rather than deleting a number finance uses. Say what you did.
- Wording: user-visible labels on this page say "งาน", not "ดีล" (owner ruling; labels only: identifiers, routes, API fields, enum values, document codes and document names like ใบแจ้งมัดจำ are untouched).
- Phone widths 320–375px: no horizontal page scroll, tap targets at least 44px.

## Constraints
- No backend, schema, permission or API-contract change. No change to who can do what: every button keeps the visibility condition it has today (account vs CEO vs read-only). List each action and its condition before and after in your report.
- Money, payment, commission, payroll and tax calculations are untouchable. You are regrouping and re-presenting rows, not recalculating anything. Any number shown must be a value the server already sent or one the page already derives today, shown unchanged.
- `hrApi.js` / `mockApi.js` stay mirrored; you should not need to change either.
- Do not touch `frontend/src/features/tickets/` or `frontend/src/features/salesBoard/` (other branches own them), except to import existing helpers.
- Existing tests stay green; never weaken an assertion; list each adapted test with its reason.
- On-screen checks run under `VITE_USE_MOCKS=true` on a free port (not 5201, 5202, 5233, 5247; stop it afterwards) and are not permission evidence.

## Test-first, concretely
Tests before code, red run recorded. At minimum: the grouping function for every finance state the existing logic distinguishes (each lands in exactly one group or in none; none are dropped; none appear twice); group order, labels and counts; each row's button, its visibility by role wrong-way-round (a viewer who cannot act today still cannot), and that it triggers the existing action; empty groups; the "ทั้งหมด" view keeping search/filter/sort; the Express card unchanged under both flag states; no "ดีล" in this page's own labels.

## Verify
Targeted `npx vitest run <files>` while iterating; once at the end `cd frontend && npm run lint && npm test && npm run build`. If a full run shows one unrelated failure that passes when re-run alone, report it as a load flake with the evidence.

## Report
Full report to `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\task-4c-report.md`; return at most 300 words: status (DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED), worktree and branch, the grouping rule and the existing logic it reuses (file:line), the before/after action table, files changed, the test-first coverage list with the red run, exact command results, drawn elements not built and why, judgment calls, and what a reviewer should attack first. If your rules forbid committing, leave the work uncommitted and say so.
