# Brief — second "มีอะไรใหม่" release entry

Repo: GL&R ERP. Work in your own git worktree cut from `origin/develop` (`git fetch origin develop` first), branch `feat/whats-new-2026-11-17`, worktree `.claude/worktrees/whats-new-1117`. Frontend only. Test-first. Never push, open a PR or touch a deployed system. You do not dispatch subagents. Do not run Maven.

## Why
The portal has a "มีอะไรใหม่" page (`/whats-new`, `/whats-new/:releaseId`) that tells each department, in Thai, what changed, with numbered steps and screenshots. One release exists and is ALREADY PUBLISHED to staff: `frontend/src/features/whatsNew/releases/2026-11-16.js`. Do not edit that file or its images. Since then these changes shipped or are about to ship to production, and staff have not been told:

1. **งานทั้งหมด** — `/tickets` is now a 5-column board (table behind "ตาราง"); the menu entry "รายการดีล" is now "งานทั้งหมด"; managers and the CEO see every rep's work with the owner's name.
2. **ใบเสนอราคา list** — opens as a flat list, newest number on top, with a "มุมมอง" switch to "จัดกลุ่มตามดีล"; search also finds a quotation by its total; the last column is "วันที่อนุมัติ".
3. **หน้ารายละเอียดงาน (deal page)** — S1–S9 stepper showing who moves each step, one "ขั้นต่อไป" card, quick buttons "โทรแล้ว" / "นัดเข้าพบ", five tabs ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ · ไฟล์ (history merged into ภาพรวม), and พักงาน / ปิดงาน (เสียงาน) / ยกเลิกงาน as quiet links at the bottom of ภาพรวม.
4. **นัดเข้าพบ** — a new menu entry and page (`/visits`): a rep's week of visits, planned vs done, previous/next week, carried-over visits; managers and the CEO see the team grid.
5. **วันนี้ของฉัน on the phone** — the sales rep's home on a phone: next visit, one large "บันทึกเข้าพบ", quick actions, "ค้าง n นัด", a short ควรตามต่อ list, the month's money; "ดูหน้าเต็ม" for the full page.
6. **งานการเงิน** — the worklist is grouped: ยืนยันพร้อมปิดงาน / ติดตามชำระ / บันทึกใบกำกับ, with "ทั้งหมด" for the full table.
7. **Document dialogs on the deal page** — clearer summaries before confirming ใบแจ้งมัดจำ, ใบแจ้งหนี้ส่วนที่เหลือ, ใบขอซื้อ (IR) and บันทึกการส่งสินค้า; a recorded delivery cannot be undone; an issued IR can only be corrected by a revision.
8. **Wording** — the sales screens now say "งาน" where they said "ดีล".

Read the real components to describe each accurately (labels exactly as rendered, where each control is, who sees it): `frontend/src/features/tickets/AllDealsPage.jsx`, `TicketDetailPage.jsx`, `DealHeader.jsx`, `NextStepCard.jsx`, `DealStepper.jsx`, `DealQuietLinks.jsx`, `ticketDetailTabs.js`, `DealFulfilmentPanel.jsx`, `RemainingInvoiceDialog.jsx`, `DealPaymentSection.jsx`; `features/quotations/QuotationListPage.jsx`; `features/salesBoard/VisitsPage.jsx`, `TodayOnPhone.jsx`, `MyWork.jsx`; `features/finance/AccountFinancePage.jsx`, `financeWorkGroups.js`; `components/layout/AppShell.jsx`; `app/permissions.js`. Do not describe anything from memory or from this brief's summary where the code says otherwise; the code wins.

## Read first
- `CLAUDE.md` at the repo root (test-first with a recorded red run; Tailwind-first; PR-body checklist).
- The whole `frontend/src/features/whatsNew/` folder: `releases/2026-11-16.js` (the shape of a release: entries, per-role sections, step tuples, image paths), `releases/index.js`, `audiences.js` (roles; `entryVisibleTo` hides an entry whose page the viewer cannot open under the production lock; `publishPayload`), `WhatsNewPage.jsx`, `ChangeCard.jsx`, and every test in the folder (they pin rules a release must satisfy; a new release must satisfy the same rules).
- `frontend/public/whats-new/2026-11-16/` for how screenshots are named.

## What to build
- A new release module `releases/2026-11-17.js` (id `2026-11-17`, date 17 Nov 2026 in the same form the first release uses), registered in `releases/index.js`. It becomes the latest release, so the header pill, the menu item and `/whats-new` show it; the 16 Nov release stays reachable.
- Entries for items 1–7 above (fold item 8 into the entries where the label changed; add one short entry for it only if a role would otherwise not be told). Each entry: a title that names the change in the reader's words, one sentence of why it helps, numbered steps a person can follow (where to click, what they will see), and a help line where a step can go wrong. Plain, polite Thai of the kind the first release uses; no English jargon; exact on-screen labels in quotes.
- **Per-role sections, strictly by what each role can open.** Derive it from `permissions.js` / `AppShell.jsx` and the pages' own role branches, not from guesswork: for example, a sales rep is told about their own board, a sales manager and the CEO about the team board with owner names; "วันนี้ของฉัน" is for sales reps only; งานการเงิน is for account and the CEO, and the CEO's rows carry "เปิด" rather than action-named buttons; roles with no sales pages get nothing new from this release, in which case follow whatever the first release and its tests do for a role with no entries (do not invent filler). A role must never be told about a page it cannot open.
- Images: every step that shows a screen references an image at `/whats-new/2026-11-17/<entry-id>-<n>.png`. Do NOT create image files and do not reuse the 16 Nov images. The controller will capture real screenshots from the mock app afterwards. If an existing test requires image files to exist on disk, do not weaken it: report it and leave that single test failing for the right reason, naming it.
- The release must not claim anything that is not live: the lifecycle dialogs (create, move to S2, pause, lost, cancel) are NOT part of this release; do not describe them.

## Constraints
- Do not touch the backend, the release-notice endpoints, `audiences.js` role lists, or the published 16 Nov release and its images.
- Existing whatsNew tests stay green apart from the image-existence case above; never weaken an assertion.
- If you find that a described feature is not actually on `origin/develop` (check before writing about it), leave it out and report it.

## Test-first, concretely
Extend the existing release tests so they run against every release in `RELEASES`, then add what this release needs: it is the latest; every role's section lists only entries whose page that role can open (use the real permission helpers, both with the production lock on and off); every entry id is unique and every referenced image path is under `/whats-new/2026-11-17/`; no entry mentions the retired "รายการดีล" menu name as the current name; each step has text and, where it names an on-screen label, that label exists in the source of the component it describes (pin a handful of the most important labels by importing or grepping the constants, so the notice breaks when the UI wording changes). Record the red run.

## Verify
`cd frontend && npx vitest run src/features/whatsNew/` while iterating; once at the end `npm run lint && npm test && npm run build`.

## Report
Full report to `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\task-wn2-report.md`; return at most 350 words: status, worktree and branch, the entries and which roles get each, the test-first coverage with the red run, command results, and — most important — a **screenshot shot list**: for every image path, the exact role to sign in as in the mock app, the URL or the clicks to reach the state, the viewport (desktop 1440×900, or phone 375×812 for the phone entry), and what must be visible in the frame. If your rules forbid committing, leave the work uncommitted and say so.
