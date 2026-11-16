# Task 4 report — นัดเข้าพบ page + phone วันนี้ของฉัน (slice D1)

**Status: DONE_WITH_CONCERNS.** All of the work is uncommitted, because my rules forbid committing. Nothing was pushed.

- Worktree: `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\visits-today`
- Branch: `feat/visits-page-and-today-phone`, cut from `origin/develop` at 5b07639b.
- `npm ci` was run once in `frontend`.

## Who can open `/visits`, and which Java lines the rule comes from
- **Who can open the page:** sales, sales_manager and ceo.
  - It mirrors `VisitService.VISIT_ROLES` (`backend/.../salesboard/VisitService.java:43`). `listOwn` checks it at line 70.
- **Which view each role gets:** sales_manager and ceo get the team grid; sales gets their own week.
  - It mirrors `VisitService.TEAM_ROLES` (line 44). `listTeam` checks it at line 76.
  - The grid's rows come from `GET /api/sales/team-overview`, which uses the same role set: `SalesBoardService.TEAM_ROLES`, lines 33 and 177.
  - The rep view also calls `my-work` to fill the deal picker in the dialogs. `MY_WORK_ROLES` (lines 32 and 168) is the same set as VISIT_ROLES.
- **Self-service lock:** handled like `/tickets`. There is a PATH_GUARDS entry and the page is NOT on the self-service allowlist. sales, sales_manager, ceo and hr are exempt from the lock, but the guard still refuses hr.
- **No backend, API, schema or contract change.** No backend gate was added or widened.
- **Week navigation was not blocked.** `GET /api/sales/visits?from&to` and `/team?from&to` already serve any range of up to 31 days, so I did not need to stop.

## Files
New:
- `frontend/src/features/salesBoard/VisitsPage.jsx`: the `/visits` page (`RepWeek`, `TeamWeek`).
- `frontend/src/features/salesBoard/TodayOnPhone.jsx`: the phone screen, with the call picker, compact follow-up list and compact cash card.
- `VisitsPage.test.jsx` and `MyWork.phone.test.jsx`.
- `.design/sales-visits/INFORMATION_ARCHITECTURE.md`: the IA step.

Changed:
- `MyWork.jsx`: the phone branch, using `useIsMobile`, plus the "ดูหน้าเต็ม" and "กลับไปหน้าวันนี้" toggle. To keep the diff small, I did not re-indent the inner stacked block.
- `visitWeek.js`: added `dayLineLabel` and `nextVisit`. Tests are in `visitWeek.test.js`.
- `VisitsThisWeek.jsx`: `export WeekNav`.
- `FollowUpList.jsx`: `export CallModal`.
- `api/routes.js`: `canViewVisits` and `canViewTeamVisits`. These are frontend presentation keys only.
- `app/permissions.js`: the `/visits` guard.
- `components/layout/AppShell.jsx`: nav item "นัดเข้าพบ · Visits", placed after ใบเสนอราคา.
- `App.jsx`: lazy route inside the SALES_ENABLED/RequireAccess block.
- `AccessDeniedPage.jsx`: added a label for `/visits`.
- `e2e-real/route-coverage.spec.js`: added `/visits`.
- Tests: `permissions.test.js`, `RequireAccess.test.jsx`, `AppShell.test.jsx`.

## Test-first coverage, with the red run
Red run: 38 failed / 135 passed across 6 files. Every failure was a missing-behaviour assertion; stubs existed, so none was an import error. The log is at `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\t4-red.log`.

- **permissions.test.js**
  - hr, employee, import, account, warehouse, qc and a division manager cannot open `/visits` and lack `canViewVisits`.
  - sales, sales_manager and ceo can open it.
  - `canViewTeamVisits` is held only by sales_manager and ceo.
  - Under the lock, sales, sales_manager and ceo keep it; import, account, employee, division manager and hr are refused. `/visits` was added to the HIDDEN list.
- **RequireAccess.test.jsx:** typing `/visits` as hr, account, import or employee shows the access-denied page with the label "นัดเข้าพบ" and the path, and never renders the page. sales, sales_manager and ceo get through.
- **AppShell.test.jsx**
  - There is no "นัดเข้าพบ (Visits)" link for 7 roles that must not have it.
  - sales, sales_manager and ceo have the link to `/visits`, directly after `/quotations`.
  - Two existing lock tests gained assertions: the locked sales rep sees it; locked import does not.
- **visitWeek.test.js**
  - `dayLineLabel` gives "พฤหัสบดี 12 พ.ย.".
  - `nextVisit` picks today first (timed before untimed, never an overdue visit), falls back to later this week, and returns null when nothing is left.
- **VisitsPage.test.jsx**
  - Defaults to this week, and goes to the previous week, back to this week, and to the next week, with the exact `from`/`to` sent.
  - Splits the week into planned and done, with cancelled shown apart; counts read "1 จาก 4 ไปแล้ว".
  - Empty week.
  - The plan dialog opens and creates a visit.
  - The log dialog opens with today's planned visits even while another week is on screen.
  - Ticking a visit marks it done; a future visit has no tick.
  - The "moved" toast uses งาน wording.
  - Cancel works after confirmation.
  - A rep never calls the team endpoints, and there is no "ดีล" in the page's own text.
  - sales_manager and ceo get the grid, week navigation calls `/team` with the previous week, the rep list is never called, and there is no log button.
- **MyWork.phone.test.jsx (phone width mocked)**
  - The drawn blocks render instead of the stacked page (no board, no `before`), with no "ดีล".
  - Exactly one "บันทึกเข้าพบ"; it opens the bottom sheet with today's visit preselected.
  - Next-visit rule: today, later this week, none (the none case offers a plan button).
  - Quick actions:
    - ทำใบเสนอราคา links to `/quotations/new` for sales and for a holder of the create-quotation grant; it is hidden for ceo.
    - "ส่งตัวอย่าง" is absent.
    - โทรแล้ว is hidden when the rep has no deals. Otherwise the picker lists ควรตามต่อ first, then the existing call dialog runs, then `addActivity(31, CALL…)`.
  - The follow-up list shows chips and links to `/tickets/:id`.
  - Cash card: the next due week skips a week with nothing due, the due part is left out when nothing more is due, and the card is absent when `cash` is null.
  - Ways out: links to `/tickets` and `/visits`, the งานใหม่ button, ดูหน้าเต็म, and back.
  - The safe-area class is present.
  - At desktop width the page is unchanged.

Mutation checks were each reverted afterwards. In each case only the matching tests went red:
- guard always true: 3 tests red;
- nav item shown to everyone: 1 red;
- team view tested on the wrong key: 10 red;
- nextVisit including overdue visits: 2 red;
- `canQuote` always true: 1 red;
- phone branch switched off: 17 red.

Deliberately not covered:
- Real 44px heights, 320px overflow, and reduced motion, because jsdom does not lay pages out. I checked them in a browser instead (below).
- Any authz evidence. This is frontend gating that mirrors the Java rule; the backend is unchanged.

## Command results
- `npm run lint`: exit 0.
- `npm test`: 303 files and 6580 tests passed, exit 0. There were no flakes.
- `npm run build`: exit 0; it emitted a `VisitsPage-*.js` chunk.
- Maven was not run, as the brief says. No backend was changed.

On-screen check:
- Run on `VITE_USE_MOCKS=true`, port 5263; the server was stopped and the port is free.
- At 320 and 375px: no horizontal scroll (scrollWidth 320), and no tappable element on the phone screen is under 44px. The picker sheet and call dialog work.
- `/visits` works at phone and desktop width. The desktop and 800px home are unchanged.
- The manager grid works.
- The console showed two existing 502s from the dev proxy.
- **Permission behaviour is unverified on the real stack** (mock only).

## Drawn elements not built
- **"ส่งตัวอย่าง" quick action.** `DealActivityKind` has no sample kind and no endpoint records a sample, so I left it out. The only existing trace is the visit-outcome chip inside บันทึกเข้าพบ.
- **"ตามครั้งถัดไป" chips on โทรแล้ว.** `DealActivityRequest` is `activityDate, kind, note` and has no follow-up date, so I left them out. `PUT /api/tickets/{id}/tracking` does carry `nextFollowUpAt`, but it replaces the whole record, so calling it from a dialog would wipe the designer, owner and buyer fields. **Smallest fix:** an optional `nextFollowUpAt` on the activity request.
- **"ตามต่อ" chips on บันทึกเข้าพบ.** `CreateVisitRequest` and `VisitDoneRequest` have no follow-up date, so I left them out.
- **Voice note:** excluded by the brief.
- **Managers' own plan/log on `/visits`:** not offered. The team grid only, as the brief describes.

## Judgment calls
- **Mobile breakpoint:** ≤720px, using `useIsMobile`, which is the `mobile` variant. Detection is done in JS rather than CSS, so the board is not rendered twice.
- **Next-visit rule:** the first PLANNED visit today in server order (date, then time with unset last, then id). A visit whose time has passed but is not ticked yet still counts as next. If none, the next PLANNED visit later this week. Overdue visits never count. Only this week's data is used.
- **Next due date on the cash card:** the first week from this Monday on with an expected total above 0, labelled with `weekEndLabel` exactly as CashCard labels it. The month's last week therefore reads "ถึง 30 พ.ย.".
- **Primary button placement:** "บันทึกเข้าพบ" sits inline where the drawing puts it, not sticky. In "ดูหน้าเต็ม" mode the old sticky button returns, so there is never more than one.
- **Nothing becomes unreachable on a phone:** "ดูหน้าเต็ม" gives back the whole stacked page: money band, worklist, board, carried-over visits, and ปิดดีล.
- **Wording:** the toasts on the new page say งาน. The shared dialogs and MyWork's own toast still say ดีล; that copy is shared, so it was not this slice's to change.
- **ทำใบเสนอราคา visibility:** uses `canCreateDealQuotationStandalone`, the same gate as QuotationListPage's create button.

## What a reviewer should attack first
1. The `MyWork.jsx` restructure: the stacked page is now wrapped in a phone/desktop branch, deliberately not re-indented.
2. `nextVisit` treats a past-time, unticked visit today as "next".
3. Permissions are mock-verified only.
