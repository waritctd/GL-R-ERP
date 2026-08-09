# Information Architecture: Leave Surface (all roles)

No `DESIGN_BRIEF.md` preceded this doc. The brief is the owner's instruction of 2026-08-10:
*"restructure the whole leave page IA, for both employee, manager, ceo, hr view — it's really messy
right now."* That instruction arrived mid-task, after three narrower decisions had already been
made and remain binding (recorded in **Decisions already locked** below).

This document covers `/leave` (`LeaveSurfacePage.jsx` and its four tabs) plus the four dashboard
entry points that read the same endpoint. It supersedes nothing — the Phase A1 tab split it builds
on was sound and is kept.

## Decisions already locked (owner, 2026-08-10)

1. The `me` tab's forward-looking list panel is named **วันลาที่กำลังจะถึง**; the request table is
   named **ประวัติการลา**.
2. The default date window widens to **±12 months from today**, applied in
   `LeaveService.list`. This is an authorised **API behaviour change**, not a side effect — it is
   stated as such here and must be stated in the PR body.
3. The `.data-row > td > small` clipping is fixed **globally** in the shared table CSS, and
   re-verified across desktop / tablet / phone and on pages other than leave.

## The four defects this restructure exists to fix

These were found by reading the code, not inferred from the screenshot. Each is load-bearing for a
structural decision below.

### D1 — "ปฏิทินวันลา" structurally cannot show upcoming leave

`MyLeaveTab.jsx:383` and `TeamLeaveTab.jsx:246` both default the filter to
`{ from: monthStartIso(), to: todayIso() }`. `LeaveRepository#findRequests` filters
`lr.start_date <= :toDate`, so **any leave starting after today is excluded before the panel ever
sees it.** `activeCalendarItems` then filters that already-past-bounded set to SUBMITTED/APPROVED
and sorts ascending.

So the panel called "leave *calendar*" has only ever listed leave that had already started. The
owner's instinct to rename it toward "history" was reading a real property of the data. The fix is
not only the rename: a panel that is supposed to look forward needs its own forward window, decoupled
from the history filter (see **N2**).

### D2 — pending approvals silently age out of every review surface

Five call sites pass no dates at all and inherit `LeaveService.list`'s null-default of
`month-start → today+1month`:

| Call site | Role served |
|---|---|
| `ReviewQueueTab.jsx:61` | any approver — **the review queue itself** |
| `LeaveSurfacePage.jsx:54` | the `รอพิจารณา` tab-visibility signal |
| `CeoOverview.jsx:134` | ceo |
| `DivisionManagerOverview.jsx:109` | division manager |
| `EmployeeSelfService.jsx:144` | employee |

A request for 26 มิ.ย. is invisible on 10 ส.ค. (`end_date >= :fromDate` fails). A vacation booked
for December is invisible today (`start_date <= :toDate` fails). Both directions drop rows that are
still `SUBMITTED` — i.e. **a pending approval can disappear from its approver's queue simply because
the month rolled over**, and the tab-visibility signal can hide the `รอพิจารณา` tab entirely.

Locked decision 2 (±12 months) fixes both directions at the one place all five inherit from.

### D3 — one name, two meanings

`Panel title="ปฏิทินวันลา"` appears in **both** `MyLeaveTab.jsx:671` (self-scoped) and
`TeamLeaveTab.jsx:391` (team-scoped). A manager switching tabs meets the same heading twice over
different data. Nothing in either panel distinguishes them.

### D4 — the filter's position contradicts what it filters

| Tab | Filter position | Governs |
|---|---|---|
| `me` | 3rd of 6, above `วันหยุดที่จะถึง` | the two panels *below* the holidays panel — not the holidays panel it sits on top of |
| `team` | 1st | the table, two panels down |
| `review` | none at all | — |

In both tabs the control is separated from its target by an intervening panel it has no effect on.

## Site Map

No new routes, no new query parameters. Structure changes inside existing tabs only.

- `/leave` — "การลา" (`LeaveSurfacePage.jsx`)
  - **`LeavePolicyBar`** — reference bar above the tabs, on every tab — **new**
  - `?tab=me` — **ของฉัน** — `MyLeaveTab.jsx` — **restructured**; hidden for hr/ceo
  - `?tab=team` — **ลูกทีม** / **พนักงานทั้งหมด** (hr, ceo) — `TeamLeaveTab.jsx` — **restructured to match `me`**
  - `?tab=review` — **รอพิจารณา** — `ReviewQueueTab.jsx` — **gains a stat row**
  - ~~`?tab=rules` — กฎการลา~~ — **REMOVED** (owner ruling, mid-implementation)
- `/leave/new` — leave composer (`LeaveRequestPage.jsx`) — **unchanged**

Tab order and the hr/ceo `review`-by-default landing (`defaultLeaveSurfaceTabId`) are unchanged.

### The `กฎการลา` tab was removed (owner ruling, added mid-implementation)

The §5 announcement is reference material for every tab, not a destination, so it became a slim
permanent bar above the tab strip — deliberately the same shape as the welfare page's own
`ระเบียบสวัสดิการ (PDF)` bar, which the owner named as the pattern. The in-app §5 clause breakdown
(`RulesTab.jsx`, `leavePolicySections.js`, ~8 CollapsibleSections) was **deleted** on the owner's
explicit call; the PDF is the authoritative text and is one click away. Recoverable from git.

⚠️ **`rules` was the only unconditionally-visible tab, and two things silently depended on that.**
Removing it — combined with hiding `me` for hr/ceo — created a real blank-page path:

1. **`resolveLeaveSurfaceTab`'s final fallback** returned the literal `'me'`, justified by a comment
   asserting `me` was always visible. Now resolves against `visibleIds`, first-visible as last resort.
2. **A ceo mid-first-load had ZERO visible tabs.** `ceo` is not in `ROLE_PERMISSIONS.canReviewLeave`,
   so `review` needs loaded rows; `team` was gated on a not-yet-loaded `employeeOptions`; `me` is now
   hidden; `rules` is gone. `team.isVisible` now short-circuits on `canViewAllLeave` — hr/ceo always
   *can* see every employee, so asserting it from the role rather than waiting for data to prove it
   removes both the flicker and the empty state. Pinned by a test.

### `เหตุผล` was unreachable in full (found while verifying)

The collapsed row's reason cell is width-constrained, and the expanded row **did not repeat `reason`
at all** — it showed paid-days, quota, emergency flag and reviewedAt. So the complete text of a leave
reason could not be read anywhere in the UI. Expanding a row, the one affordance that should reveal
it, did not. `เหตุผล` is now the first, full-width field of the expanded detail.

## Navigation Model

- **Primary navigation**: unchanged — the existing "การลา" nav item.
- **Secondary navigation**: the existing four-tab bar. Unchanged in membership, order, labels, and
  visibility rules.
- **Utility navigation**: unchanged — the header "ยื่นคำขอลา" CTA (hidden for hr/ceo by
  `canSubmitOwnLeave`, presentation only) and the overflow "รีเฟรช".
- **Mobile navigation**: unchanged. Every panel below reflows through the existing `reflow-cards` /
  `mobileCard` idiom already in `DataTable`.

## Content Hierarchy

### The organising rule

Within every tab, sections run in one direction: **entitlement → what is coming → what happened.**
A filter appears immediately above the first section it governs and never above one it does not.
This single rule resolves D1, D3 and D4 at once, and it is the only structural idea in this document.

### `me` — ของฉัน

1. **`CompactStatRow`** (existing, unchanged) — four numbers, the at-a-glance answer.
2. **`โควตาวันลา`** (existing, unchanged) — entitlement: *how much do I have?* Includes the
   `ดูโควตา` type select and the `โควตาการลาทั้งหมด` disclosure.
3. **`วันหยุดที่จะถึง`** (existing `UpcomingHolidays`, **moved up** above the filter) — forward:
   company holidays, today → +90d. It never read the filter; it must stop appearing to.
4. **`วันลาที่กำลังจะถึง`** (renamed from `ปฏิทินวันลา`, **re-scoped**) — forward: *my own* approved
   and pending leave, today → +90d, **on its own window, not the history filter** (fixes D1). Pairs
   with (3): the two "what's coming up" panels sit together, one company-wide, one personal.
5. **Filter bar** (`จากวันที่` / `ถึงวันที่` / `สถานะ` / `ค้นหา`) — **moved down**, now directly
   above the one section it governs (fixes D4). Date inputs render **empty**; the backend's ±12-month
   default supplies "recent" (locked decision 2).
6. **`ประวัติการลา`** (renamed from `คำขอลาของฉัน`) — the full `DataTable`: paging, expand, cancel,
   retry.

### `team` — ลูกทีม / พนักงานทั้งหมด

Same skeleton, team-scoped, so a manager moving between tabs meets the same shape twice:

1. **`วันลาที่กำลังจะถึงของทีม`** / **`…ของพนักงานทั้งหมด`** (renamed from the duplicate
   `ปฏิทินวันลา`, re-scoped to a forward window — fixes D1 and D3).
2. **Filter bar** (`จากวันที่` / `ถึงวันที่` / `สถานะ` / employee select) — already first in this
   tab; it stays directly above the table once the panel above moves off the filter.
3. **`ประวัติการลาของทีม`** / **`ประวัติการลาพนักงานทั้งหมด`** (renamed from `คำขอลาของทีม` /
   `คำขอลาพนักงานทั้งหมด`) — the `DataTable`.

Role-aware copy continues to come from the existing `labelFor`/`helperFor` mechanism in
`leaveSurfaceTabs.js` — extended to these panel titles rather than a second, parallel role check.

### `review` — รอพิจารณา

1. **`CompactStatRow`** — **new**. Four numbers chosen for a QUEUE, not a history: `รอคุณพิจารณา`,
   `เริ่มใน 7 วัน` (urgency — a request starting Monday outranks one three months out, and nothing
   else on this tab surfaced that), `วันลารวม`, `พนักงาน`. Counted over `actionableRequests`, not
   the wider list, so the headline matches the rows beneath it.
2. **The queue `DataTable`** (existing, unchanged) — approve / reject / cancel.

**No filter bar here — a deliberate departure from this document's first draft.** The draft proposed
one for consistency. Implementation rejected it: this tab's whole contract is "everything you must
act on", and its own source comment says a reviewer "should never lose sight of one". A control that
can hide actionable rows works against that, and the D2 fix already removed the invisible window
that made a filter feel necessary. Consistency is not worth a queue that can be accidentally
emptied. Flagged to the owner rather than silently dropped.

## User Flows

### Employee checks their own leave (primary)

1. Lands on `/leave` → `ของฉัน` (`defaultLeaveSurfaceTabId` → `me`).
2. Reads the stat row and `โควตาวันลา` — how much is left.
3. Reads `วันหยุดที่จะถึง` then `วันลาที่กำลังจะถึง` — what is coming, company-wide then personal.
   - **Changed**: leave starting after today now appears here. Under the current build it cannot (D1).
4. Scrolls to `ประวัติการลา` — a full ±12 months of their own requests, newest first, **without
   touching a control** (locked decision 2).
   - If they want a narrower slice → set `จากวันที่` / `ถึงวันที่` / `สถานะ` → `ค้นหา`.
5. Expands a row for detail, cancels a `SUBMITTED` one, or retries a rejected one — all unchanged.

### Manager reviews their reports' leave

1. Lands on `/leave` → `ของฉัน`; `รอพิจารณา` is visible because they have actionable rows.
2. Opens `รอพิจารณา` — **changed**: the queue now includes pending requests from up to 12 months
   back and 12 months forward, so nothing has aged out (D2).
3. Approves / rejects inline.
4. Opens `ลูกทีม` for the wider picture — same shape as their own tab: what is coming, then history.

### HR / CEO oversee leave

1. Land on `/leave` → `รอพิจารณา` directly (`defaultLeaveSurfaceTabId`, unchanged).
2. `ลูกทีม` reads `พนักงานทั้งหมด` and covers every active employee (unchanged mechanism).
3. `ของฉัน` remains visible with no submit CTA — see **Open question O1**.

## Naming Conventions

| Concept | Label in UI | Notes |
|---|---|---|
| The actor's own leave falling after today | **วันลาที่กำลังจะถึง** | Replaces `ปฏิทินวันลา` in `me`. Parallel in construction to `วันหยุดที่จะถึง` directly above it — same `…ที่จะถึง` frame, deliberately, because they answer the same shape of question one after the other. Not "ปฏิทิน": the panel is a list, and calling a list a calendar is what made D1 invisible for so long. |
| The team's leave falling after today | **วันลาที่กำลังจะถึงของทีม** | `team` tab, manager view. |
| Every employee's leave falling after today | **วันลาที่กำลังจะถึงของพนักงานทั้งหมด** | `team` tab, hr/ceo view, via the existing `labelFor` mechanism. |
| The actor's own past + filtered requests | **ประวัติการลา** | Replaces `คำขอลาของฉัน`. "ประวัติ" states the direction; the tab is already called `ของฉัน`, so repeating "ของฉัน" in the panel title inside it was redundant. |
| The team's requests | **ประวัติการลาของทีม** | Replaces `คำขอลาของทีม`. |
| Every employee's requests | **ประวัติการลาพนักงานทั้งหมด** | Replaces `คำขอลาพนักงานทั้งหมด`. |
| Company non-working days ahead | **วันหยุดที่จะถึง** | Unchanged — `UpcomingHolidays`' existing default title, already established by the holiday-visibility IA. |
| Requests awaiting this actor's decision | **รอพิจารณา** | Unchanged tab label. |
| The §5 announcement PDF | **ประกาศวันลา (PDF)** | `LeavePolicyBar`'s trigger. Mirrors the welfare bar's `ระเบียบสวัสดิการ (PDF)` construction — "<document> (PDF)" — so the two reference bars read as one pattern. |

**Retired terms:** `ปฏิทินวันลา` (both instances — never a calendar), `คำขอลาของฉัน`,
`คำขอลาของทีม`, `คำขอลาพนักงานทั้งหมด`, `กฎการลา` (tab).

## Postscript: the clipping fix, and why measurement replaced reasoning

The owner's third locked decision (fix `.data-row` text clipping globally) took **three attempts**,
and the first two were wrong in ways that read as correct:

1. *"Wrap `<small>`, keep `<strong>` on one line"* — reasoning that the primary line is short
   scannable identity. It is not: the leave table's `<strong>` is a date range, and
   `10 ส.ค. 2569 - 12 ส.ค. 2569` was rendering as `10 ส.ค. 2569 - 12 ส.ค. …`, ellipsing the end date.
2. *Targeting `.data-row > td > strong` / `> small`* — **those selectors match nothing.** Every
   caller emits `<td><span><strong/><small/></span></td>`, so the text nodes are grandchildren and
   the child combinator never reaches them. The rule was inert; the bug survived it untouched.

What actually clips is the wrapper `<span>` (`white-space: nowrap` + `text-overflow: ellipsis`,
inherited by both text lines). Established by measuring `scrollWidth`/`clientWidth` in a real
browser — `194 > 172` on that span, while the `<strong>` inside reported no overflow of its own.

Verified after the fix: **0 clipped nodes** at 1440 / 900 / 390px on the leave surface, and 0 on
`/employees`, `/payroll` and `/attendance` at desktop and mobile — the cross-page check the owner
asked for. Mobile was never affected: below 720px these tables render as `record-card`s, not
`.data-row`s.

## Component Reuse Map

| Component | Used on | Behavior differences |
|---|---|---|
| `UpcomingHolidays` (existing, `components/common/`) | `me`; `OvertimePanel` (existing caller) | None. Position within `me` changes; the component does not. |
| Upcoming-leave list panel | `me`, `team` | **To be extracted** from the two current inline copies of the `ปฏิทินวันลา` markup, which are near-identical today and already drifting. Takes `{ title, requests, emptyTitle }`. Extraction is the point: D3 exists because the markup was duplicated, and a rename applied to two copies is a rename that will diverge again. See the filter-bar precedent in **O2**. |
| Filter bar | `me`, `team`, `review` (new) | `FILTER_BAR_CLASS` is **already duplicated verbatim** in `MyLeaveTab.jsx:43` and `TeamLeaveTab.jsx`, each with its own copy of the `items-end` comment. Adding a third copy for `review` is not acceptable — extract once. |
| `DataTable`, `Panel`, `StatePanel`, `CompactStatRow`, `QuotaBar` | throughout | Unchanged, used as documented on each. |
| `leaveRequestTable.jsx` column builders | `me`, `team`, `review` | Unchanged — already shared, and correctly so. |

## Content Growth Plan

- **`ประวัติการลา`** grows without bound per employee. Already handled: `DataTable` paging
  (20/page) plus the date/status filter directly above it. The ±12-month default bounds the initial
  read; a wider range is an explicit user action.
- **`วันลาที่กำลังจะถึง`** is bounded by construction — a 90-day forward window, capped at 8 rows,
  same convention as `UpcomingHolidays` beside it.
- **`รอพิจารณา`** grows with team size, not time (only `SUBMITTED` rows). ±12 months makes it
  complete rather than large; it shrinks as decisions are made.
- **`team` for hr/ceo** is the widest read on the surface — every active employee, ±12 months.
  Worth measuring once against prod-scale row counts before merge; noted as a risk, not a blocker.

## URL Strategy

Unchanged. `?tab=` remains the only leave-surface query parameter and the single source of truth for
tab selection. Filter state stays component-local and is deliberately **not** promoted to the URL in
this pass — doing so would be a genuine feature addition (shareable filtered views), not a
restructure, and CLAUDE.md forbids adding features without an explicit request.

## Resolved by the owner (2026-08-10)

**O1 — `ของฉัน` is hidden for hr/ceo.** Resolved: **hide it.** They land on `รอพิจารณา` and see
only oversight surfaces. Consequence that must be handled in the same change:
`resolveLeaveSurfaceTab`'s final fallback is the literal `DEFAULT_LEAVE_SURFACE_TAB_ID` (`'me'`),
justified by a comment asserting `me` "is unconditionally visible to everyone … so this final
fallback can never itself resolve to a hidden tab." **That assertion stops being true the moment
this change lands**, and the function would then be able to return a hidden tab for hr/ceo. The
fallback must resolve against `visibleIds` instead, and its comment must be corrected rather than
left asserting the old invariant.

**O2 — extract both.** The filter bar and the upcoming-leave list panel both become shared
components before being used by three tabs. `FILTER_BAR_CLASS` is currently duplicated verbatim
(comment included) across `MyLeaveTab.jsx:43` and `TeamLeaveTab.jsx`; adding a third inline copy for
`review` was the alternative and is rejected. Extraction is what stops D3/D4 recurring.

**O3 — stat rows added to `team` and `review`.** `CompactStatRow` gains callers on both tabs, so
every tab opens with the same at-a-glance shape. Each needs its own four numbers chosen for what
that tab is *for* (a queue's useful numbers are not a history's).
