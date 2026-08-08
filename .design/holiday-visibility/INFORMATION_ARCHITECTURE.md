# Information Architecture: Holiday Visibility (OT + Leave)

No `DESIGN_BRIEF.md` preceded this doc — this feature's brief lives in the approved multi-PR plan
itself (PR 1 backend: derive `day_type` from `hr.holiday` and validate a contradicted HOLIDAY
claim; PR 2, this one: surface that same calendar to an employee filing OT, *before* they claim a
day type; PR 3, planned: reuse the same panel in the leave-request composer). This document covers
PR 2's structural decisions and records the reuse contract PR 3 inherits.

## Site Map

No new routes. Both surfaces this feature touches already exist; PR 2 changes what renders inside
one of them.

- `/employee-requests` — "คำขอ" (combined requests page, `RequestsPage.jsx`)
  - `?tab=ot` — "ล่วงเวลา" (Overtime) — **`OvertimePanel.jsx`, changed in this PR**
  - `?tab=welfare` — "สวัสดิการ / เงินพิเศษ" (unrelated to this PR)
  - `?tab=attendance-correction` — "ขอแก้ไขเวลาเข้า-ออกงาน" (unrelated to this PR)
- `/leave/new` — leave-request composer (`LeaveRequestPage.jsx`) — **not touched in PR 2**; its
  own calendar-context note (step 2) already reads the same backend endpoint this PR reuses as a
  shared component. PR 3 is expected to replace or sit alongside that note with `UpcomingHolidays`.
- `/overtime` — legacy path, already redirects to `/employee-requests?tab=ot` (`App.jsx`); unchanged.

## Navigation Model

- **Primary navigation**: unchanged. An employee reaches this screen via the existing "คำขอ" nav
  item, same as before this PR.
- **Secondary navigation**: the `ล่วงเวลา / สวัสดิการ / แก้ไขเวลาเข้า-ออกงาน` tab bar inside
  `RequestsPage.jsx` — unchanged, this PR only changes content *within* the `ล่วงเวลา` tab.
  There is no dedicated "holiday" nav destination — visibility is contextual (inline, at the
  point of the decision it informs), never a separate page. `/api/holidays` (the full admin
  calendar CRUD) already has its own destination for HR/CEO (`HolidaysTab.jsx`, attendance-calendar
  admin) and stays out of scope here; this PR is about the *employee-facing read*, not calendar
  management.
- **Utility navigation**: none added.
- **Mobile navigation**: unchanged (hamburger/drawer shell). `UpcomingHolidays` and the verdict
  badge use the same `max-[720px]:` responsive idiom as the rest of `OvertimePanel.jsx` and stack
  naturally in the existing single-column mobile layout — no mobile-specific nav pattern needed
  since nothing here is navigation, only content.

## Content Hierarchy

### OvertimePanel.jsx ("ล่วงเวลา" tab)

The ordering rule this PR exists to satisfy: **the holiday answer must arrive before the claim is
made.** Everything below the intro/stat-row/filter-bar (unchanged, out of scope) now reads:

1. **`UpcomingHolidays` panel** ("วันหยุดที่จะถึง", next ~90 days) — highest priority addition.
   Sits *above the entire submit form*, not just above the day-type field, because an employee
   deciding *which date* to request OT for needs this before they even open the date picker —
   answering "which upcoming days are holidays" is a distinct, earlier question than "is the date
   I already picked one."
2. **วันที่ทำ OT** (work-date field, existing, unchanged position) — the input that turns the
   general holiday list above into a specific question.
3. **Verdict badge** (new) — resolves the calendar answer for the *exact* date just picked,
   immediately below the date field and before the day-type control. This is the second-highest
   priority addition: it is the direct answer to the question the date field just posed, and it
   must land before the employee is asked to pick a day type themselves — otherwise they are
   guessing, which is the exact failure mode PR 1's backend validation exists to catch.
4. **ประเภท OT** (day-type select, existing, unchanged options) — now paired with an inline
   pre-flight mismatch note (new) directly beneath it, only rendered when the employee's own
   selection contradicts the verdict above it. Field-level, not a page-level banner, so it reads
   as "about this specific choice," matching this codebase's existing convention
   (`LeaveRulePanel`/`PreviewErrorNotice` placement in `LeaveRequestPage.jsx`).
5. **เริ่ม / สิ้นสุด / เหตุผลความจำเป็น / submit** (existing, unchanged) — the mechanical rest of
   the form, unaffected by holiday context.
6. **Existing OT requests table** (existing, unchanged) — below the form; this PR does not touch
   the review/approval history section.

### UpcomingHolidays.jsx (new, shared)

1. **Panel title** ("วันหยุดที่จะถึง" by default, overridable via the `title` prop for a future
   caller with a different framing need).
2. **Holiday rows** (date + name + a `วันหยุดบริษัท` badge) — sorted ascending, capped at `limit`.
3. **Terminal state** (loading / error / genuinely empty) — always one honest, visible message.
   Never a blank panel: the whole point of this component existing is that "no answer yet" and
   "the answer is 'no holidays'" must never look identical to the person reading it.

## User Flows

### Submit OT with holiday awareness (primary flow this PR changes)

1. Employee opens "คำขอ" → "ล่วงเวลา" tab (`/employee-requests?tab=ot`).
2. **New**: sees `UpcomingHolidays` — the next ~90 days' company holidays, if any, before touching
   the form at all.
3. Employee opens `วันที่ทำ OT` and picks a date (today, a near date, or a backdated one within
   the retroactive window).
4. **New**: the verdict badge resolves for that exact date —
   - `วันหยุดบริษัท: {nameTh} · 3x` — the date is a confirmed company holiday.
   - `วันทำงานปกติ · 1.5x` — the calendar loaded and confirms this is not a holiday.
   - `ปฏิทินยังไม่ได้โหลด` — the calendar hasn't resolved yet (in flight, or errored); no claim
     can be verified against it right now.
5. Employee opens `ประเภท OT` and picks WORKDAY or HOLIDAY.
   - If [selection contradicts the verdict, and the verdict is resolved] → **New**: an inline
     pre-flight note appears under the field, before submit:
     - Selected HOLIDAY on a confirmed-WORKDAY date → warns the submission **will be refused**
       (predicts `OvertimeService#validateDayTypeClaim`'s 400).
     - Selected WORKDAY on a confirmed-HOLIDAY date → informs the request will still be **paid
       3x automatically** (the backend corrects this upward; not an error, just information).
   - If [verdict unresolved, or selection matches the verdict] → no note.
6. Employee fills times/reason and submits.
   - If the backend still returns the day-type-claim 400 (calendar changed between page-load and
     submit, or the pre-flight prediction was itself stale) → **New**: the error now also renders
     inline on the `ประเภท OT` field (`FormField error`), in addition to the existing toast — the
     toast alone previously left the employee to guess which field was at fault.
7. On success: unchanged (toast, form reset, list invalidated).

### Browse upcoming holidays without filing OT (secondary, incidental)

An employee can read `UpcomingHolidays` purely as information — no interaction required, no
following flow. This is intentional: not every visit to this tab is to file a request.

## Naming Conventions

| Concept | Label in UI | Notes |
|---|---|---|
| A day present in `hr.holiday` (BANK or COMPANY source) | **วันหยุดบริษัท** | Used both as `UpcomingHolidays`' per-row badge text and in the verdict badge's HOLIDAY state. Deliberately covers both BANK and COMPANY sources — the employee-facing surface does not need that admin-CRUD distinction (`LeaveCalendarHolidayDto` omits `source` for the same reason). **Not** "วันหยุดนักขัตฤกษ์" — that term undersells/mislabels a COMPANY-sourced row that isn't a public holiday. |
| The shared panel listing upcoming holiday dates | **วันหยุดที่จะถึง** | `UpcomingHolidays`' default `title` prop. |
| A day NOT in `hr.holiday` | **วันทำงานปกติ** | Verdict badge's confirmed-normal state; matches the existing `ประเภท OT` `<option>` label word-for-word (a deliberate reuse, not a coincidence — same concept, same word). |
| The calendar hasn't resolved for the selected date | **ปฏิทินยังไม่ได้โหลด** | Verdict badge's third state — covers both "still loading" and "errored," since `calendar-context`'s response shape gives the frontend no way to distinguish "not yet loaded" from a genuinely-empty result; the frontend does not pretend to know the difference. |
| The OT pay multiplier for a holiday work date | **3x** | Matches the existing `ประเภท OT` `<option>` label and `OvertimeDayType.HOLIDAY`'s multiplier. |
| The OT pay multiplier for a normal work date | **1.5x** | Matches the existing `ประเภท OT` `<option>` label and `OvertimeDayType.WORKDAY`'s multiplier. |

## Component Reuse Map

| Component | Used on | Behavior differences |
|---|---|---|
| `UpcomingHolidays` (new, `components/common/`) | `OvertimePanel.jsx` (this PR, ~90-day-forward window from today) | Feature-agnostic by construction — takes `{ from, to, title, limit }`, no OT-specific content. **Planned reuse**: the leave-request composer (PR 3) is expected to pass its own `{ from, to }` (likely the selected leave range, or its own lookahead window) and possibly its own `title`. No behavior differences are anticipated between callers; if PR 3 needs one, it should be added as a new prop, not a caller-sniffing branch inside the component. |
| `Panel`, `StatusBadge`, `EmptyState` (existing, `components/common/`) | Composed inside `UpcomingHolidays` | Used exactly as documented on their own components — no local overrides or duplicated styling. |
| Verdict badge (new, inline in `OvertimePanel.jsx`, **not** extracted as a shared component) | `OvertimePanel.jsx` only | Deliberately NOT generalized into `components/common/` in this PR: its three-state copy ("วันหยุดบริษัท: {name} · 3x" / "วันทำงานปกติ · 1.5x" / "ปฏิทินยังไม่ได้โหลด") is written around OT's pay-multiplier framing specifically. If PR 3's leave composer needs an equivalent "is this date special" badge, extract a shared primitive *then*, informed by what leave actually needs to say — extracting speculatively now risks guessing wrong and refactoring twice. |

## Content Growth Plan

- **Holiday rows** (`UpcomingHolidays`, verdict badge): grow/shrink with `hr.holiday` itself, which
  HR maintains via the existing admin CRUD (`HolidaysTab.jsx`, outside this PR's scope). No
  pagination needed — `UpcomingHolidays`' `limit` prop (default 8) caps the panel's own row count
  regardless of how many holidays a wide `[from, to]` window contains; a caller wanting more can
  raise `limit`, not this component's job to decide for every caller.
- **OT requests table**: unchanged by this PR, already has its own date-range filter for growth.
- **Mock fixture growth**: `MOCK_HOLIDAY_DATES` (`mockApi.js`) is a small, hand-maintained calendar
  (2026 only, 7 dates) for demo/test purposes — extending it (new years, more dates) is expected
  over time and does not require touching `UpcomingHolidays`, the verdict badge, or
  `calendarContext`'s own logic, only the fixture data itself.

## URL Strategy

No changes. This PR adds no routes, no query parameters, and no deep-linkable state — the verdict
badge and `UpcomingHolidays` are both purely derived from existing form/page state
(`วันที่ทำ OT`'s current value, and "today" at render time), not from the URL.
