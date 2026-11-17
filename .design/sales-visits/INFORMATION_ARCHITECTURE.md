# Information Architecture: นัดเข้าพบ page and the phone "วันนี้ของฉัน" screen

Slice D1 of the sales redesign gap build. Sources: the approved before/after drawing (`a-mywork` card B and its
`.phone` block, `a-team` card D, `m-visit-plan`, `m-visit-log`, the drawing's `NAV`), and the 2026-11-16 gap audit
rows "↳ mobile view วันนี้ของฉัน", "นัดเข้าพบ", "บันทึกเข้าพบ (มือถือ)", "โทรแล้ว" and section 2 (side menu).

## Site map (sales roles, only what this slice touches)

- แดชบอร์ด `/` — for `sales` this is งานของฉัน (SalesOverview → MyWork)
  - at ≤720px (the app's `mobile` breakpoint, `useIsMobile`): **วันนี้ของฉัน** (new), with a switch to the full page
- งานทั้งหมด `/tickets`
- ใบเสนอราคา `/quotations` (create: `/quotations/new`)
- **นัดเข้าพบ `/visits`** (new)
  - `sales`: my visits for one ISO week, planned vs done; `?` no query params (week is page state)
  - `sales_manager`, `ceo`: the team week grid
- แคตตาล็อกสินค้า `/catalog`, ค่าคอมมิชชัน `/commissions`, …

## Navigation model

- **Primary (sidebar, งานขาย group):** "นัดเข้าพบ · Visits" sits directly after "ใบเสนอราคา", where the drawing's
  `NAV.sales` puts it (`mywork, board, quotes, visits, catalog, comm`). The drawing gives managers no visits item;
  the brief gives them one, in the same slot, because the page serves them the team grid.
- **Audience** = exactly who the visit endpoints serve: VisitService `VISIT_ROLES` {sales, sales_manager, ceo}
  for the own list; `TEAM_ROLES` {sales_manager, ceo} for the team list. Every other role gets neither the item nor
  the route (direct navigation → the access-denied page).
- **Self-service lock:** same handling as `/tickets` — a PATH_GUARDS entry, the item in the `sales` group, and NOT
  on the self-service allowlist. Sales, sales_manager and ceo are lock-exempt, so they keep it; everyone else is
  refused twice (lock, then guard).
- **Mobile:** the existing nav drawer (≤1040px). The phone home links to `/visits` directly.

## Content hierarchy

### นัดเข้าพบ (`/visits`), sales
1. Header: title, the two actions "นัดเข้าพบ" (plan) and "บันทึกเข้าพบ" (log) — the jobs done on this page.
2. Week bar: ‹ · สัปดาห์นี้ · › and the week's range, plus "x จาก y ไปแล้ว".
3. นัดไว้ (planned, overdue first by date) — each row can be ticked "เข้าพบแล้ว" from its day on, or cancelled.
4. ไปแล้ว (done).
5. ยกเลิก (cancelled), only when there are any.
Empty week: one message and the plan action.

### นัดเข้าพบ (`/visits`), sales_manager / ceo
1. Header (title only — managers read here; their own planning stays on the rep flows).
2. ตารางเข้าพบ (VisitWeekGrid, reused as-is: its own ‹ สัปดาห์นี้ › bar, legend, desktop grid / phone per-rep list).

### วันนี้ของฉัน (phone home, sales)
1. Date line + "วันนี้ของฉัน".
2. นัดถัดไป card — the next unticked planned visit today; else the next one in the 7 days ahead (across the week
   boundary: GET /api/sales/visits today..today+7); else an empty state that stays true while visits are missed.
3. One large primary "บันทึกเข้าพบ" (replaces the sticky button; never two). Shown even when /my-work fails, beside
   the error and a retry.
4. Quick row: "โทรแล้ว" (pick a งาน, then the existing call dialog), "ทำใบเสนอราคา" (only for who may create one).
   "ส่งตัวอย่าง" is not built: there is no way to record a sample sent on a งาน.
4a. ค้าง n นัด — this week's unticked past visits and those carried over from the 31 days before Monday, tickable.
   `/visits` shows the same carried-over list on the current week.
5. ควรตามต่อ (n), compact, each row → its งาน page.
6. เงินเข้าเดือนนี้, compact, only when the payload carries `cash`.
7. Way out: ดูงานทั้งหมด (`/tickets`), นัดเข้าพบทั้งสัปดาห์ (`/visits`), งานใหม่, and "ดูหน้าเต็ม" — the full stacked
   page (board, worklist, money band, carried-over visits, ปิดงาน) so nothing reachable today becomes unreachable. The
   choice is remembered for the browser session; the full page opens with an equally large "กลับไปหน้าวันนี้".

## User flows

### Log a visit on the phone
1. Rep opens the app → `/` → วันนี้ของฉัน.
2. Taps บันทึกเข้าพบ → the existing m-visit-log bottom sheet, today's planned visits preselected.
3. Saves → toast; the next-visit card moves on.

### Plan and tick on the visits page
1. Sidebar → นัดเข้าพบ → this week.
2. "นัดเข้าพบ" → m-visit-plan → saved; if it landed in another week the page offers the jump.
3. On the day: tap "เข้าพบแล้ว" on the row → the existing tick dialog → moves to ไปแล้ว.

## Naming

| Concept | Label in UI | Notes |
|---|---|---|
| a deal | งาน | owner ruling; existing shared dialogs keep their current wording (not this slice) |
| plan a visit | นัดเข้าพบ | m-visit-plan |
| log a visit that happened | บันทึกเข้าพบ | m-visit-log |
| tick a planned visit | เข้าพบแล้ว | existing VisitRow / VisitDoneModal |

## Component reuse map

| Component | Used on | Differences |
|---|---|---|
| VisitRow, WeekNav (VisitsThisWeek.jsx) | MyWork card B, `/visits` | none |
| PlanVisitModal, LogVisitModal, VisitDoneModal | MyWork, `/visits` | none |
| VisitWeekGrid | TeamOverview, `/visits` (managers) | none |
| CallModal (FollowUpList.jsx) | ควรตามต่อ rows, phone quick row | the phone picks the งาน first |

## URL strategy

`/visits`, no dynamic segment and no query parameters: the shown week is page state (like the MyWork card), so a
shared link always opens this week.
