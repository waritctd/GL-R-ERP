# Task 4 brief — "นัดเข้าพบ" page and the phone "วันนี้ของฉัน" screen (slice D1)

Repo: GL&R ERP. Work in your own git worktree cut from `origin/develop` (`git fetch origin develop` first; develop now contains the "งานทั้งหมด" page and the "งาน" wording on the board), branch `feat/visits-page-and-today-phone`, worktree `.claude/worktrees/visits-today`. Frontend only. Test-first. Never push, open a PR or touch a deployed system. You do not dispatch subagents. Do not run Maven.

## Why
The owner approved a before/after review of the sales flow; an audit found two things in it for the sales rep were never built: a "นัดเข้าพบ" menu entry with its own page, and the phone screen "วันนี้ของฉัน".

## Read first
1. `CLAUDE.md` at the repo root (binding: test-first with a recorded red run, Tailwind-first, mock contract, PR-body checklist). It requires the `information-architecture` and `frontend-design` skills: read `.agents/skills/information-architecture/SKILL.md` and `.agents/skills/frontend-design/SKILL.md` and follow them. This task adds a page and a menu entry, so do the IA step properly (route name, where it sits, how it is reached, what links back).
2. The drawing: `C:\Users\user\.claude\projects\C--Users-user-Documents-GitHub-GL-R-ERP\21665dad-d5d9-4895-9692-9b2bd2afbd34\tool-results\artifact-9c52c656-1794690962-b51b.html`
   - `<template id="a-mywork">`: card B "นัดเข้าพบสัปดาห์นี้" and, at the end of the template, the `.phone` block "วันนี้ของฉัน" (this is the phone target);
   - `<template id="a-team">`: "ตารางเข้าพบสัปดาห์นี้" (the managers' week grid);
   - templates `m-visit-plan` and `m-visit-log`;
   - the `NAV` definition in the script: where "นัดเข้าพบ · Visits" sits for sales.
3. The gap audit rows "↳ mobile view วันนี้ของฉัน", "นัดเข้าพบ", "บันทึกเข้าพบ (มือถือ)", "โทรแล้ว", and section 2 (side menu): `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\whats-new\docs\superpowers\plans\2026-11-16-sales-redesign-gap-audit.md` (read there; do not copy or commit).
4. Existing code to REUSE, not duplicate: `frontend/src/features/salesBoard/` — `MyWork.jsx`, `VisitsThisWeek.jsx`, `VisitWeekGrid.jsx`, `VisitDialogs.jsx`, `visitWeek.js`, `FollowUpList.jsx` (the "โทรแล้ว" dialog), `CashCard.jsx`, `TeamOverview.jsx`; `api/hrApi.js` + `mockApi.js` (`salesBoard` namespace: what visit data exists and for which roles); `components/layout/AppShell.jsx` + `Sidebar.jsx`; `app/permissions.js` (path guards, and `SELF_SERVICE_PATHS`); `App.jsx` (routes); `frontend/e2e-real/route-coverage.spec.js` (its `ROUTES` list must include any new route or CI fails).

## What to build
### A. "นัดเข้าพบ" page
- Route `/visits`, menu entry "นัดเข้าพบ · Visits" in the งานขาย group, placed where `NAV` puts it (for sales: after ใบเสนอราคา).
- For a sales rep: their own visits for a week, as planned vs done, with previous/next week navigation and "this week" as the default; buttons "นัดเข้าพบ" (plan) and "บันทึกเข้าพบ" (log) opening the existing dialogs; tapping a planned visit lets them mark it done through the existing log flow. Empty state for a week with no visits.
- For sales_manager and ceo: the team week grid (`VisitWeekGrid`) with the same week navigation. They get the menu entry too.
- Who may open it: exactly the roles that can already read this visit data through the existing endpoints. Check the Java-side gates by reading `backend/src/main/java/th/co/glr/hr/salesboard/` (do not infer from the mock). Do not add or widen a backend gate; mirror the existing rule in the route guard and the menu, and say which lines you based it on. If week navigation needs data the current endpoints cannot return (for example they only serve the current week), STOP and report what exists and what is missing, with your recommended smallest change, instead of faking other weeks.
- Under the production "self-service only" lock this page follows the same visibility as the other sales pages (read how `/tickets` is handled in `permissions.js` and do the same; do not add it to the self-service allowlist).

### B. Phone "วันนี้ของฉัน"
At phone width, the sales rep's home (งานของฉัน) shows the drawn phone screen instead of the stacked desktop page:
- header: the date line and "วันนี้ของฉัน";
- "นัดถัดไป · HH:MM" card: the next planned visit today (or the next upcoming one; say which rule you chose) with who and what; a sensible empty state when there is none;
- one large primary "บันทึกเข้าพบ" button (the existing sticky button may become this; do not show two);
- a quick row of three: "โทรแล้ว", "ส่งตัวอย่าง", "ทำใบเสนอราคา". Each must do something real that the app already supports: "โทรแล้ว" opens the existing call dialog (it needs a deal: let the rep pick from ควรตามต่อ or their deals); "ทำใบเสนอราคา" goes to the existing create-quotation entry point if this user may use it (same permission as today's entry points; hide it otherwise). For "ส่งตัวอย่าง": find whether the app has an existing way to record that activity on a deal; if it does, wire it; if it does not, leave the button OUT and report it. No dead buttons, no fake actions.
- compact "ควรตามต่อ (n)" list with the quiet-days chips;
- compact "เงินเข้าเดือนนี้" card (amount, "จากที่คาด …", next due), shown only when the data exists, as on desktop.
- The full board and the other desktop cards remain reachable on a phone (for example a "ดูงานทั้งหมด" link to `/tickets`); nothing a rep can do on a phone today may become unreachable.
- Desktop and tablet layouts must not change. Choose the breakpoint the project already uses for "mobile" and state it.
- 320–375px: no horizontal page scroll; tap targets at least 44px; bottom controls respect `env(safe-area-inset-bottom)`; `prefers-reduced-motion` respected.

### C. Dialog gaps the audit listed (small, same components)
- "โทรแล้ว": add the "ตามครั้งถัดไป" chips (3 วัน / 1 สัปดาห์ / 2 สัปดาห์ / เลือกวัน) ONLY if the existing call endpoint already accepts a follow-up date; otherwise leave them out and report it.
- "บันทึกเข้าพบ": add the "ตามต่อ" chips (1 สัปดาห์ / 2 สัปดาห์) under the same condition. Do not build the voice-note microphone.

## Constraints
- No backend, schema, permission or API-contract change in this task. Missing data = leave the element out and report it.
- Wording: user-visible labels say "งาน", not "ดีล" (owner ruling; labels only).
- Commission, payroll, tax, pricing math untouchable.
- `hrApi.js` / `mockApi.js` stay mirrored.
- Add the new route to `frontend/e2e-real/route-coverage.spec.js`.
- Do not edit `frontend/src/features/whatsNew/releases/2026-11-16.js` (published).
- Do not edit `frontend/src/features/tickets/` (another branch is rewriting the deal page).
- Existing tests stay green; never weaken an assertion to get there; list each adapted test with its reason.
- On-screen checks run under `VITE_USE_MOCKS=true` on a free port (not 5201, 5202 or 5247; stop it afterwards) and are not permission evidence.

## Test-first, concretely
Tests before code, red run recorded. At minimum: who gets the menu entry and the route, including the wrong-way-round cases (a role that must not, does not; direct navigation is refused); week navigation and default week; the rep view vs the team view by role; empty week; plan and log dialogs open from the page; the phone layout renders the drawn blocks at phone width and the desktop layout at desktop width; each quick action's visibility condition and target; the next-visit rule (today, later this week, none); the cash card absent when the data is absent; no "ดีล" in the new page's own visible text.

## Verify
Targeted `npx vitest run <files>` while iterating; once at the end `cd frontend && npm run lint && npm test && npm run build`.

## Report
Full report to `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\task-4-report.md`; return at most 350 words: status (DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED), worktree and branch, who can open the page and the Java lines that rule is based on, files changed, the test-first coverage list with the red run, exact command results, every drawn element not built and why, every judgment call, and what a reviewer should attack first. If your rules forbid committing, leave the work uncommitted and say so.
