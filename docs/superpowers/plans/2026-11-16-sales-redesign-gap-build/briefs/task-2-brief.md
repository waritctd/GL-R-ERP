# Task 2 brief — deal detail page redesign (slice B)

Repo: GL&R ERP (`waritctd/GL-R-ERP`). Work in your own git worktree cut from `origin/develop` (`git fetch origin develop` first), branch `feat/deal-page-redesign`. Frontend only. Test-first. Leave your work uncommitted or commit locally as your own rules allow; never push, open a PR, or touch a deployed system. You do not dispatch subagents.

## Why
The owner approved a before/after review of the sales flow. An audit on 2026-11-16 found the deal detail page (`/tickets/:id`) was never rebuilt: it is still the pre-redesign page. This task builds the drawn page for the roles that see it (sales, sales_manager, ceo; import and account are redirected to their own pages and are out of scope).

## Read first
1. `CLAUDE.md` at the repo root: binding rules (test-first with a recorded red run, Tailwind-first, mock contract, what a PR body must list). It requires the `information-architecture` and `frontend-design` skills for this work: read `.agents/skills/information-architecture/SKILL.md` and `.agents/skills/frontend-design/SKILL.md` and follow them.
2. The approved drawings, in the saved review page `C:\Users\user\.claude\projects\C--Users-user-Documents-GitHub-GL-R-ERP\21665dad-d5d9-4895-9692-9b2bd2afbd34\tool-results\artifact-9c52c656-1794690962-b51b.html`:
   - `<template id="a-deal">` (แท็บ ภาพรวม), `a-deal-quote`, `a-deal-money`, `a-deal-ship`;
   - the script near the bottom: the helper that renders `data-part="dealhead"` (the shared header, S stepper, "ขั้นต่อไป" card and tab bar for deals `a` and `b`), and the `ROWS` entries for these templates (their change bullets say what each tab must contain).
3. The gap audit rows for this page: `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\whats-new\docs\superpowers\plans\2026-11-16-sales-redesign-gap-audit.md` (rows "หน้ารายละเอียดดีล · แท็บ ดีล", "แท็บ ประวัติ", "แท็บ สินค้าและราคา", "แท็บ การเงิน", "แท็บ จัดซื้อ-ส่งมอบ", and the note on "แท็บ เอกสาร"). Read it there; do not copy or commit it.
4. The spec for stage meaning: `docs/superpowers/specs/2026-11-15-sales-flow-redesign-design.md` §4–§7 (display stages, S-codes, which moves are manual and which follow documents).
5. Current code: `frontend/src/features/tickets/TicketDetailPage.jsx`, `ticketDetailTabs.js`, `DealStagePanel.jsx`, `DealStageStepper.jsx`, `DealStateHeader.jsx`, `DealHistoryPanel.jsx`, `DealPaymentSection.jsx`, `DealFulfilmentPanel.jsx`, `DealQuotationPanel.jsx`, `DealDocumentRegister.jsx`, `DealAttachmentsPanel.jsx`, `stageMeta.js`; and the already-built pieces to reuse: `frontend/src/features/salesBoard/FollowUpList.jsx` (the "โทรแล้ว" dialog), `VisitDialogs.jsx` (นัดเข้าพบ / บันทึกเข้าพบ), `ExpectedPaymentsPanel.jsx`, `boardFormat.js`, `quietRule.js`.

## What to build
1. **Header + S stepper.** Deal identity as drawn, then an 8-step stepper S1, S2, S3, S5, S6, S7, S8, S9 with the drawn Thai labels, the current step highlighted, and under each step whether it is "เซลส์ย้ายเอง" or "อัตโนมัติ". It replaces the "สถานะดีล (Pipeline)" panel with numbered pips. Take the stage→S-code mapping from existing code/spec; do not invent one.
2. **"ขั้นต่อไป" card.** One card naming the single next step for the deal's current stage with ONE primary button, as drawn. The button performs the action the page already offers for that stage today (it opens the existing dialog or navigates to the existing screen). Derive the stage→next-action table from the actions the current `DealStagePanel` and tabs offer plus the spec; put the table in one small pure module with unit tests for every stage, including closed/paused/lost/cancelled deals (which show no next step, or the resume action if one exists today).
3. **Header quick actions** "โทรแล้ว" and "นัดเข้าพบ", reusing the existing dialogs from `salesBoard` (extract the dialog if it is currently private to `FollowUpList`; do not duplicate it). The deal page's old instant "บันทึกกิจกรรม" logging is replaced by these.
4. **Five tabs** in this order: "ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ · ไฟล์".
   - ภาพรวม: the "ข้อมูลงาน" card and ONE merged timeline (the content of today's "ประวัติ" tab) with "+ บันทึก" and filters "ทั้งหมด / เข้าพบ / เอกสาร". The separate "ประวัติ" tab goes away.
   - ใบเสนอราคา: "ใบเสนอราคาของงานนี้" table with "+ สร้างใบเสนอราคา" (the quotation list that lives in today's "เอกสาร" tab), then "สินค้าในใบล่าสุด", then everything today's "สินค้าและราคา" tab holds that still has a function (item entry, "คำขอราคา" panel). Nothing a user can do today may become unreachable.
   - เงิน: three KPI tiles "มูลค่างาน / รับแล้ว / คงค้าง", then รับแล้ว (receipts, with the existing Express / บันทึกเอง chip) beside เอกสาร, and the existing "เงินที่คาดว่าจะเข้า" panel. Same data and actions as today's "การเงิน" tab.
   - ส่งของ: today's "จัดซื้อ-ส่งมอบ" content, with a "ดูอย่างเดียว" chip and hint on the read-only tracker, and the delivery list presented as the drawn table (สั่ง / ส่งแล้ว / เหลือ / ที่มา) if the data already on the page supports it. If a column has no data source, leave it out and report it.
   - ไฟล์: today's "เอกสาร" tab minus the quotation table that moved (document register, attachments).
   - Old tab keys in URLs (`?tab=` or whatever the page uses today) must keep working: map each old key to its new tab. Pin this with tests.
5. **Quiet links instead of the red danger zone.** At the bottom of ภาพรวม, plain text links "พักงาน", "ปิดงาน (เสียงาน)", "ยกเลิกงาน" that open the EXISTING dialogs unchanged (a later task redesigns those dialogs). The red "จัดการดีล" danger-zone block is removed. Every action it offered must still be reachable, to exactly the roles that could use it before.
6. **Wording.** Owner ruling: user-visible labels on this page say "งาน", not "ดีล" (breadcrumb "งานทั้งหมด › …", "ข้อมูลงาน", "มูลค่างาน", toasts). Labels only: identifiers, routes, API fields, enum values, document codes, and the text of printed documents or emails are untouched. Dialogs opened from this page keep their current wording (they are the later task).

## Hard constraints
- No backend, schema, permission or API-contract change. If a drawn element needs data the page's current endpoints do not return, do NOT invent an endpoint or fake the value: leave it out, and list it in your report.
- Do not change who can do what. Every existing control keeps its existing permission condition; you are moving controls, not re-gating them. Before you start, list every action the current page offers and its visibility condition; after you finish, show the same list with where each now lives. That table goes in your report. An action that became unreachable, or reachable to a role that could not use it before, is a defect.
- Commission, payroll and tax code is untouchable. Pricing/quotation math is untouchable.
- `hrApi.js` and `mockApi.js` stay mirrored; you should not need to change either.
- Keep `TicketDetailPage.jsx` from growing: extract the new header, stepper, next-step card and each tab body into focused components under `features/tickets/`. Do not rewrite panels whose content is unchanged; re-home them.
- Phone widths 320–375px: no horizontal page scroll; the stepper and tab bar scroll inside their own wrapper or collapse; tap targets at least 44px; respect `prefers-reduced-motion`.
- Existing tests for this page and its panels must stay green; adapt only where structure legitimately changed, never weaken an assertion to get green, and list each adapted test with the reason.
- Any on-screen check you do runs under `VITE_USE_MOCKS=true` (free port, not 5201 or 5202; stop the server afterwards) and is not evidence about permissions: say so.

## Test-first, concretely
Write tests before code and record the red run. At minimum: the stage→S-step mapping for every stage; the next-step table for every stage and lifecycle; the five tabs and their order; each old tab key landing on the right new tab; the merged timeline filters; each relocated action still rendered under its original permission condition (one test per action, including the wrong-way-round case: a role that could not see it before still cannot); the quiet links opening the existing dialogs; no red danger zone; no "ดีล" left in this page's own visible text (allow deal data and the dialogs).

## Verify
Targeted `npx vitest run <files>` while iterating; once at the end `cd frontend && npm run lint && npm test && npm run build`. Do not run Maven.

## Report
Write the full report to `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\task-2-report.md` and return a summary of at most 350 words: status (DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED), worktree and branch, the before/after action-location table, files changed, the test-first coverage list with the red run, exact command results, every drawn element you did not build and why, every judgment call, and anything a reviewer should attack first.
