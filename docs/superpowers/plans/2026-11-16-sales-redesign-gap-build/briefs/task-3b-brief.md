# Task 3b brief — document dialogs (slice C2)

Repo: GL&R ERP. Work in your own git worktree, branch `feat/deal-document-dialogs`, worktree `.claude/worktrees/deal-doc-dialogs`, cut from `origin/feat/deal-page-redesign` (`git fetch origin` first; that branch carries the rebuilt deal page these dialogs open from and is not merged to develop yet, so this is a genuine stack). Frontend only. Test-first. Never push, open a PR or touch a deployed system. You do not dispatch subagents. Do NOT run Maven (another agent has the backend test database).

## Why
The owner approved a before/after review of the sales flow. An audit found four document dialogs on the deal page were never rebuilt: confirming the deposit notice, the remaining-balance invoice, confirming the purchase request (IR), and recording a delivery. They still show the old bodies.

## Read first
1. `CLAUDE.md` at the repo root (binding: test-first with a recorded red run; Tailwind-first; mock contract; PR-body checklist; pricing, commission, payroll and tax math untouchable). Read and follow `.agents/skills/frontend-design/SKILL.md`.
2. The drawings, in `C:\Users\user\.claude\projects\C--Users-user-Documents-GitHub-GL-R-ERP\21665dad-d5d9-4895-9692-9b2bd2afbd34\tool-results\artifact-9c52c656-1794690962-b51b.html`: templates `m-deposit-issue`, `m-remaining`, `m-ir`, `m-delivery` in full, and their `ROWS` entries in the script near the bottom.
3. The audit rows for these four (section "Modals"): `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\whats-new\docs\superpowers\plans\2026-11-16-sales-redesign-gap-audit.md` (read there; do not copy or commit).
4. Code: `frontend/src/features/deposits/DepositNoticePage.jsx` (the issue confirm), `frontend/src/features/tickets/DealPaymentSection.jsx`, `RemainingInvoiceDialog.jsx`, `DealFulfilmentPanel.jsx` (IR confirm and the delivery dialog), `ProcurementSourceDialog.jsx`, and the API methods they call in `api/hrApi.js` / `mockApi.js`. For what each request accepts and each response returns, read the Java DTOs behind those endpoints (deposit notice, remaining invoice, purchase request / IR, delivery) under `backend/src/main/java/th/co/glr/hr/`: the Java source is the truth, not the mock.

## What to build
For each dialog, the drawn body in place of the old one. In every case the dialog must submit EXACTLY the same request, to the same endpoint, with the same values the old dialog would have sent for the same user choices. You are changing what the user sees before confirming, not what is sent.

1. **ยืนยันการออกเอกสาร — ใบแจ้งมัดจำ (`m-deposit-issue`).** The drawn one-line summary, the "มูลค่าตามใบเสนอราคา QT-…" row, the deposit / VAT / total lines, and the hint about "เงินที่คาดว่าจะเข้า". Keep the irreversibility warning the old confirm carried.
2. **ใบแจ้งหนี้ส่วนที่เหลือ (`m-remaining`).** The drawn summary line "ส่วนที่เหลือ … = มูลค่า … − มัดจำที่ได้รับ …" and the drawn footer. The drawing also shows "วันครบกำหนด" and "เงื่อนไข" fields: include a field ONLY if the existing request already accepts that value; otherwise leave it out and report it.
3. **ยืนยันการออกใบขอซื้อ (IR) (`m-ir`).** The drawn "N โรงงาน · N รายการ" summary and the table โรงงาน / รายการ / ระยะเวลานำเข้า, built from data the panel already has. Keep the irreversibility warning. If lead time is not available to the panel for some rows, show what is available and report the gap.
4. **บันทึกการส่งสินค้า (`m-delivery`).** The drawn summary ("งวดที่ N · นัดส่ง …" where that data exists), the table of items with ordered / delivered / remaining / this delivery, "วันที่ส่ง" only if the request accepts a delivery date, the info line about the deal moving on when everything is delivered ONLY if that is what the backend actually does (check the Java service; quote the lines; if it is not automatic, do not say it is), and the button "บันทึกการส่ง". Keep "แหล่งสินค้า" and "หมายเหตุ" if the request needs them.

## Rules for every number on these dialogs (this is money and stock)
- Every amount, quantity, percentage and date shown must be a value the page already received from the server or already derives today, displayed with the same formatter. Do not introduce a new calculation. Where the drawing shows an arithmetic line (for example remaining = value − deposit received), show it only if all three numbers are values the server already sends for this document and they are consistent by construction; if the page would have to compute one of them, show the server's figures without the equation and report it.
- If the summary a dialog shows could ever disagree with what the server will actually issue (for example a preview computed client-side), that is a defect: prefer the server's preview/draft values the old dialog used.
- No change to who can open or confirm each dialog. Every control keeps its existing visibility and disabled conditions. List each dialog's opener and confirm conditions before and after in your report.

## Other constraints
- Wording: user-visible labels say "งาน", not "ดีล" (owner ruling; labels only). Document names (ใบแจ้งมัดจำ, ใบแจ้งหนี้, ใบขอซื้อ), document codes, and the text of printed documents are untouched.
- No backend, schema, permission or API-contract change. `hrApi.js` / `mockApi.js` unchanged unless a contract test forces a mirror; you should not need to.
- Do not touch: `TicketCreateModal.jsx`, `UpdateStageModal.jsx`, `DealStagePanel.jsx`, `MarkLostModal.jsx`, `CancelDealModal.jsx`, `ReasonModal.jsx`, `stageMeta.js`, `nextStep.js`, `DealQuietLinks.jsx` (another agent is rebuilding those dialogs on a sibling branch), nor `frontend/src/features/finance/`, `frontend/src/features/quotations/`, `frontend/src/features/salesBoard/`. Keep edits to `TicketDetailPage.jsx` to the minimum; ideally none.
- Existing tests stay green; never weaken an assertion; list each adapted test with its reason. Tests that pinned the old dialog bodies will change: say which and why. Make targeted edits; do not rewrite a test file whole.
- Phone: each dialog usable at 320–375px (tables scroll inside their own wrapper or stack; no horizontal page scroll; 44px targets).
- On-screen checks run under `VITE_USE_MOCKS=true` on a free port (not 5201, 5202, 5233, 5247; stop it afterwards) and are not permission evidence.

## Test-first, concretely
Tests before code, red run recorded. For each dialog at minimum: the drawn summary and rows render from server values, with fixtures where every figure differs (so swapping one field for another fails); the request sent on confirm is byte-identical to what the old dialog sent for the same input (capture the old payload in a test FIRST, against the unchanged code, then keep that test green through the rebuild); opener and confirm visibility/disabled conditions by role and state, wrong-way-round; the irreversibility warnings still present; cancel/close sends nothing; no "ดีล" in the dialog's own labels.

## Verify
Targeted `npx vitest run <files>` while iterating; once at the end `cd frontend && npm run lint && npm test && npm run build`. If a full run shows one unrelated failure that passes when re-run alone, report it as a load flake with the evidence.

## Report
Full report to `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\task-3b-report.md`; return at most 350 words: status (DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED), worktree and branch, per dialog: what is shown and where each figure comes from (field name), the payload-identity evidence, opener/confirm conditions before and after; files changed; the test-first coverage list with red runs; exact command results; every drawn element not built and why (with the Java lines that show the request does not accept it); judgment calls; what a reviewer should attack first. If your rules forbid committing, leave the work uncommitted and say so.
