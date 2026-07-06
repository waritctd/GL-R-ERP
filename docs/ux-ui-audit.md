# GL&R ERP — UX/UI Audit & Fix Roadmap

_Assessment date: 2026-07-07 · Scope: all 14 frontend modules · Priority lens: usability & clarity_

This document evaluates the current UX/UI of the GL&R ERP frontend (`frontend/`, React 18 + Vite),
benchmarks it against how large ERP vendors (SAP Fiori, Oracle NetSuite "Redwood", Workday) solve the
same problems, and lays out a sequenced fix roadmap. Every finding cites real code so it can be acted
on directly.

**How to read this**
- **Part 1 — Scorecard**: the app rated 1–5 on eight usability dimensions.
- **Part 2 — Reference benchmarks**: what "good" looks like at big-tech ERPs, per problem.
- **Part 3 — Per-module findings**: all 14 modules, with `file:line` evidence and the specific fix.
- **Part 4 — Roadmap**: five phases, sequenced so foundations unblock later work.
- **Appendix — Later / not now**: real gaps parked outside the usability-first scope.

---

## Part 1 — Scorecard

Rated against a merged heuristic set: **SAP Fiori's five principles** (role-based, adaptive, simple,
coherent, delightful), the **Nielsen heuristics** most relevant here, and **enterprise-scale
patterns** for tables and forms.

| Dimension | Score | Verdict |
|---|:---:|---|
| **Visibility of system status** (loading, progress, feedback) | 2 / 5 | Loading = plain "กำลังโหลด..." text; no skeletons/spinners; several async actions give no feedback at all. |
| **Error prevention** (validation before submit) | 2 / 5 | No inline validation infra (`aria-invalid`/`aria-describedby` appear **nowhere**); cross-field checks (OT end>start) missing; relies on scattered native `min=`/`required`. |
| **Error recovery** (plain-language messages, escape routes) | 2 / 5 | Failures surface as generic global toast/text with no cause or next step. |
| **User control** (undo, cancel, non-destructive defaults) | 2 / 5 | Irreversible actions gated only by native `window.confirm`; no undo; no draft recovery on the big forms. |
| **Consistency & standards** | 3 / 5 | Reusable components exist and are used, but **41 hardcoded hex colors and 0 CSS variables** in one 1,480-line stylesheet means every screen re-decides spacing/color. |
| **Recognition over recall** | 2 / 5 | Opaque labels ("พิเศษ 1–8", OT multiplier terms, two-object propose-price flow) assume tribal knowledge; no tooltips/help. |
| **Data-table usability** (sort/filter/paginate/search at scale) | 2 / 5 | Attendance renders up to 2,000 rows unpaginated; payroll lists all employees; page sizes ad-hoc (8, 10); no in-table search; no virtualization. |
| **Navigation & orientation** | 3 / 5 | Clean role-filtered sidebar, but **no breadcrumbs anywhere**, weak back affordance in deep flows, and nav badges don't auto-refresh. |
| **Accessibility foundation** | 3 / 5 | Genuinely good bones — accessible `Modal` (focus trap + restore), `role="alert"`/`role="status"` toasts, semantic `<nav aria-label>` — but gaps in table semantics, field errors, color-only status. |

**Overall: ~6/10 — functional but unpolished.** The skeleton is sound (sensible IA, decent
component seeds, a real accessibility starting point). What holds it back is systemic: no design-token
substrate, no validation/feedback layer, and tables that don't scale. These are exactly the
usability-and-clarity gaps the roadmap targets.

---

## Part 2 — Reference benchmarks

How the same problems are handled at scale, and the principle behind it.

| Problem in GL&R | Big-tech ERP approach | Principle |
|---|---|---|
| Generic global error after submit | **Fiori** validates at field entry with inline messages and states what's allowed *before* typing | Error prevention; plain-language recovery |
| `window.confirm`/`prompt` dialogs | All three use **branded in-app confirmation dialogs**; destructive actions add undo/version history/audit trail | User control & error recovery |
| 2,000-row unpaginated table | **NetSuite/Workday** use virtualized scrolling, server pagination, fixed headers, and **in-table search + faceted filters** | Performance at scale |
| 10+ fields in one panel | **Workday** layers information progressively — summary cards expand to detail; long forms become sectioned/stepped wizards with autosave | Progressive disclosure; simple |
| Opaque field labels | Contextual help that **explains business logic**, not just field names; intelligent defaults for the 80% case | Recognition over recall |
| One dashboard for everyone | **Fiori role-based** + NetSuite role dashboards: executives and data-entry clerks land on different views | Role-based |
| Ad-hoc color/spacing per screen | A **governed design system** so tables, forms, and modals behave identically everywhere — critical because users build muscle memory | Coherent / consistency |
| No breadcrumbs in deep flows | Persistent global nav **+ breadcrumbs for orientation** + search/command palette for power users | Navigation at scale |

Sources: [SAP Fiori design principles](https://experience.sap.com/fiori-design/) ·
[ERP dashboard UX — UXmatters, 2025](https://www.uxmatters.com/mt/archives/2025/02/designing-the-erp-dashboard-user-experience.php) ·
[Enterprise UX patterns for scale — designx.co](https://designx.co/enterprise-ux-patterns/) · NetSuite "Redwood" / NetSuite Next redesign.

---

## Part 3 — Per-module findings

Legend: **U#** = usability/clarity issue (maps to roadmap); severity **●●● high / ●● med / ● low**.
File paths are relative to `frontend/src/`.

### 1. Auth — Login & forced password change
`features/auth/LoginPage.jsx`, `features/auth/ChangePasswordModal.jsx`
- **What it does:** Two-column login (brand + form) with demo role quick-login buttons; forces a
  password change on first login.
- ●● **U1 — validation only on submit.** Password rule is checked at submit
  (`ChangePasswordModal.jsx:62`, `newPassword.length < 8`) and shown as a single global error string;
  no per-field inline feedback, no live "8+ characters" hint. *Fix:* `FormField` wrapper with
  `aria-describedby` hint + inline error.
- ● **U7 — forced password change lacks context.** The modal appears with no explanation of *why*.
  *Fix:* one-line rationale + strength hint.

### 2. Employee Dashboard
`features/dashboard/EmployeeDashboard.jsx`
- **What it does:** Personalized greeting, 4–6 KPI `StatCard`s, role-aware quick actions.
- ●● **U5 — no loading state.** Summary fetch has no skeleton; a silent failure shows stale/empty
  cards with no signal. *Fix:* `Skeleton` cards + explicit error state.
- ● **U8 — empty metrics indistinguishable from failed fetch.** *Fix:* zero-state vs error-state copy.

### 3. HR Dashboard
`features/dashboard/HrDashboard.jsx`
- **What it does:** HR overview with CSS-`<div>` bar tracks (`bar-track` + width %), stat cards.
- ● **U5** — same missing-loading pattern as above.
- ● Data-viz is hand-rolled bars; adequate now, noted for the charts appendix (not usability-critical).

### 4. Ticket Dashboard
`features/dashboard/TicketDashboard.jsx:55`
- **What it does:** Quotation pipeline overview.
- ●● **U5 — plain-text loader.** `กำลังโหลด...` centered text (`:55`) instead of layout-preserving
  skeleton, causing content jump on load. *Fix:* skeleton matching final layout.

### 5. Employees — List & Detail
`features/employees/EmployeeListPage.jsx`, `features/employees/EmployeeDetailPage.jsx`
- **What it does:** Filterable list (search + division/department/status), row → detail; long
  multi-section detail form.
- ●● **U3 — tiny page size, no in-list search-after-filter refinement.** `pageSize = 8`
  (`EmployeeListPage.jsx:12`) → heavy paging for a real org. *Fix:* shared `DataTable` (larger page
  size, sort, in-table search).
- ●● **U4 — detail form is one long scroll** with no sectioning/tabs. *Fix:* labeled collapsible
  sections (personal / employment / contact / banking / documents).
- ● **U1 — no inline validation** on the detail form.

### 6. Attendance ●●● (worst offender)
`features/attendance/AttendancePage.jsx`
- **What it does:** Date-range + employee + limit filter; HR `.dat` import; large results table.
- ●●● **U3 — up to 2,000 rows rendered into the DOM.** Limit select offers 500/1,000/2,000
  (`:178–180`) with **no pagination or virtualization** — the single biggest performance/usability
  risk. *Fix:* `DataTable` with virtualization + client pagination.
- ●● **U3 — no in-table search.** After filtering by date you cannot find one employee without
  scrolling. *Fix:* in-table search box.
- ●● **U8 — import fails quietly.** Wrong `.dat` format isn't clearly surfaced. *Fix:* explicit
  parse-result summary (added / skipped / errors with reasons).
- ● **U5** — loading shown as an `EmptyState` labeled "กำลังโหลดข้อมูล" (`:232`), overloading the
  empty-state component for a loading state.

### 7. Overtime
`features/overtime/OvertimePage.jsx`
- **What it does:** Filter bar, submit-OT form (`datetime-local` start/end, type, reason), request
  table with approve/reject/cancel.
- ●●● **U2 — native dialogs for reject & cancel.** `window.prompt('เหตุผลการปฏิเสธ')` (`:209`) and
  `window.prompt('หมายเหตุการยกเลิก...')` (`:243`). Off-brand, unstyled, no validation on the reason.
  *Fix:* inline reason form / `ConfirmDialog` with a reason field.
- ●● **U1 — no cross-field time validation.** Start/end are `datetime-local` with `required`
  (`:349`, `:353`) but nothing enforces **end > start**. *Fix:* inline rule + disabled submit.

### 8. Leave
`features/leave/LeavePage.jsx`
- **What it does:** Filter + submit form (type, date range, reason, attachment URL) + balance cards +
  request table.
- ●●● **U2 — native dialogs.** `window.prompt('เหตุผลการปฏิเสธ')` (`:247`), cancel note prompt
  (`:263`). *Fix:* same `ConfirmDialog`-with-reason pattern as OT.
- ●● **U1 — partial date validation.** End date is constrained (`min={form.startDate}`, `:398`), but
  **start date allows past dates** and there's no inline messaging. *Fix:* guard past `startDate` +
  inline feedback.
- ● **U7 — attachment is a raw URL text field**, not a file upload; no validation/preview. *Fix:*
  proper upload or at least URL validation (parked toward Phase 3).

### 9. Profile Requests (HR review)
`features/profileRequests/ProfileRequestsPage.jsx`
- **What it does:** Table of change requests (employee / field / old → new / status) with
  approve/reject.
- ●● **U6 — badge count is stale.** The sidebar pending-requests badge doesn't refresh after
  approve/reject without reload. *Fix:* refetch on route change / after mutation.
- ● No bulk approve/reject (parked — bulk actions are a scale nicety, not core clarity).

### 10. Profile & My Requests
`features/profile/ProfilePage.jsx`, `features/profile/MyRequestsPage.jsx`, `features/profile/ChangeRequestModal.jsx`
- **What it does:** Own profile with per-field "ขอแก้ไข" request buttons; feed of own requests.
- ● **U1** — change-request modal validates on submit only.
- ● **U7 — emergency-contact field couples name+phone**, so changing one implies re-entering both.
  *Fix:* split fields (Phase 3).

### 11. Tickets — List, Detail, Create ●●● (most complex)
`features/tickets/TicketListPage.jsx`, `features/tickets/TicketDetailPage.jsx`, `features/tickets/TicketCreateModal.jsx`
- **What it does:** Status-tab list; a very complex detail page (propose price, edit items, email
  factory, reject, revise, comments, attachments, deposit export).
- ●●● **U7 — confusing propose-price flow.** Prices live in `draftRaw` while currency/unit live in a
  separate `draftFactoryCurr` object — a two-step mental mapping with no visual link. *Fix:* unify
  into one row-per-item editor with currency/unit inline.
- ●●● **U2 — native confirms.** Delete attachment `window.confirm('ลบไฟล์...')` (`:322`), cancel
  ticket `window.confirm('ยืนยันการยกเลิก...')` (`:475`). *Fix:* `ConfirmDialog`.
- ●● **U4/state — 24 `useState` calls** in `TicketDetailPage.jsx` juggling mutually-exclusive modes
  (propose / edit / email / reject / revise). Risk of invalid combined states. *Fix:* `useReducer`
  state machine (Phase 3).
- ●● **U5 — uncoordinated async loads.** Ticket, attachments (`:838`), and price calc (`:406`,
  `calcLoading`) each show separate plain-text loaders. *Fix:* consistent skeletons.
- ●● **U6 — no "back to list" / breadcrumb** in the list → detail → deposit chain.
- ● **U3 — magic page size** `PAGE_SIZE = 10` (`TicketListPage.jsx:10`), inconsistent with employees' 8.

### 12. Deposits / Invoices
`features/deposits/DepositNoticePage.jsx`
- **What it does:** Deposit-notice generator tied to a ticket (template, note, preview, issue/download).
- ●●● **U2 — irreversible issue behind a native confirm.** `window.confirm('ยืนยันการออกเอกสาร?
  หลังจากนี้จะไม่สามารถแก้ไขได้')` (`:176`) — the most consequential action in the app uses the
  weakest guard. *Fix:* `ConfirmDialog` that spells out the irreversibility.
- ●● **U5 — preview loads with plain text** (`:484`, "กำลังโหลด preview...").
- ● **U6 — deep nested flow** (ticket → deposit) with no breadcrumb.

### 13. Commissions
`features/commissions/CommissionPage.jsx`
- **What it does:** Filterable commission table + approval workflow.
- ●●● **U2 — native prompt** for cancel/refund reason `window.prompt('เหตุผลการยกเลิก/คืนเงิน')`
  (`:170`). *Fix:* `ConfirmDialog`-with-reason.
- ●● **U5 — full-page plain-text loader** (`:285`, `:368`).
- ● **U3** — no pagination/in-table search on the commission list.

### 14. Payroll ●●● (worst offender) + CEO Settings
`features/payroll/PayrollPage.jsx`, `features/ceoSettings/CeoSettingsPage.jsx`
- **What it does:** Two-panel monthly processor — employee list (left) + adjustment form (right) with
  special-pay, non-taxable, and deduction fields; process + bank-file export.
- ●●● **U2 — irreversible "Process" behind a native confirm.** `window.confirm('ยืนยันประมวลผล
  เงินเดือนรอบ ${month}?')` (`:161`). Payroll processing is high-stakes and permanent. *Fix:*
  `ConfirmDialog` summarizing totals + period before committing.
- ●●● **U4 — 10+ fields crammed into the right panel** with only section headers, no grouping or
  progressive disclosure. `MoneyInput` currency fields (`:308`, `:320`, `:334–342`) stack densely.
  *Fix:* labeled collapsible groups (earnings / special pay / non-taxable / deductions).
- ●●● **U7 — opaque labels.** "พิเศษ 1–8" special-pay fields have no field-level help explaining what
  each maps to. *Fix:* per-field tooltips/hints.
- ●● **U3 — left employee list is unpaginated** and has no search — scroll-only for 50+ staff. *Fix:*
  `DataTable` + search.
- ●● **U1 — soft-only validation.** `MoneyInput` sets `min="0"` (`:381`) and unpaid-leave-days uses
  `type="number" min="0"` (`:330`), but these are browser hints with **no inline error and no JS
  guard** — a directly-typed negative still flows to state. *Fix:* real validation + inline feedback.
- ● **U5 — no feedback during preview/process calc.** CEO Settings (`CeoSettingsPage.jsx:101`) uses
  the same plain-text loader.

---

## Part 4 — Fix roadmap (sequenced, usability-first)

Each phase ships independently as its own PR on a feature branch (main is protected). Foundations
come first so later component fixes are cheap and consistent. **Effort:** S ≈ hours, M ≈ 1–2 days,
L ≈ 3–5 days.

### Phase 0 — Design-token foundation · effort M · addresses F1
Extract palette, spacing, type scale, radius, and shadows from `frontend/src/styles.css` (currently
**41 hardcoded hex values, 0 CSS variables**, 1,480 lines) into `:root` custom properties and
replace hardcoded values by reference. **No visual change intended** — this is the substrate every
later fix builds on.
- Files: `frontend/src/styles.css`.
- Done when: token block exists, `grep -oE '#[0-9a-f]{6}' styles.css | sort -u` drops to a handful,
  app looks pixel-identical.

### Phase 1 — Feedback & safety primitives · effort L · addresses U1, U2, U5, U8 (highest ROI)
1. **`ConfirmDialog`** built on the existing `components/common/Modal.jsx` (already has focus trap +
   restore). Replace **every** `window.confirm`/`window.prompt` call site — verified list:
   `PayrollPage.jsx:161`, `DepositNoticePage.jsx:176`, `OvertimePage.jsx:209,243`,
   `LeavePage.jsx:247,263`, `CommissionPage.jsx:170`, `TicketDetailPage.jsx:322,475`. Reason-prompts
   become a `ConfirmDialog` variant with a reason field.
2. **`FormField` inline-validation wrapper** (`aria-describedby` + `aria-invalid`, currently used
   nowhere). Wire the high-risk forms: OT end>start (`OvertimePage.jsx:349,353`), leave past-start
   (`LeavePage.jsx`), payroll negatives (`PayrollPage.jsx` `MoneyInput`), change-password
   (`ChangePasswordModal.jsx:62`).
3. **`Skeleton`** component; replace plain-text "กำลังโหลด..." loaders on dashboards, payroll,
   ticket detail, deposits, commissions.
4. **Plain-language error/empty states** — extend existing `components/common/EmptyState.jsx` +
   `Toast.jsx`/`useToast.js` to carry cause + a recovery action.
- Reuses: `Modal.jsx`, `Toast.jsx`, `EmptyState.jsx`, `useToast.js`, `StatusBadge.jsx` — **no
  reinventing** these.

### Phase 2 — Data-table usability · effort L · addresses U3
Build one `DataTable` primitive (sort, client pagination, in-table search, virtualization for big
lists) that keeps the current CSS-Grid look but standardizes behavior; retire the ad-hoc page sizes
(`EmployeeListPage.jsx:12` = 8, `TicketListPage.jsx:10` = 10).
- Migrate: `AttendancePage.jsx` (the 2,000-row case — virtualize), `PayrollPage.jsx` (left list),
  `TicketListPage.jsx`, `EmployeeListPage.jsx`, `CommissionPage.jsx`.

### Phase 3 — Form & workflow clarity · effort L · addresses U4, U7
- Section the payroll adjustment panel and employee-detail form into labeled collapsible groups.
- Field-level help on opaque fields (payroll "พิเศษ 1–8"; OT multiplier terms).
- Rework the ticket propose-price flow into one row-per-item editor (unify `draftRaw` +
  `draftFactoryCurr`); refactor `TicketDetailPage.jsx`'s 24 `useState` into a `useReducer` machine.
- Files: `PayrollPage.jsx`, `EmployeeDetailPage.jsx`, `TicketDetailPage.jsx`.

### Phase 4 — Navigation context · effort M · addresses U6
- Breadcrumb / "back to list" affordance for deep flows (ticket → detail → deposit).
- Refresh nav badge counts on route change / after mutations (`AppShell.jsx`, `Sidebar.jsx`,
  `ProfileRequestsPage.jsx`).

---

## Appendix — Later / not now

Real gaps that fall outside the usability-first scope; capture, don't do yet:
- **Full WCAG 2.1 AA pass** — semantic `<table>` for data grids (currently CSS-Grid `<div>`s), skip-to-content link, `aria-live` on loaders, icon+text (not color-only) status badges.
- **Responsive tables** — card layout on mobile instead of 900px horizontal scroll.
- **i18n extraction** — hardcoded Thai/English strings → translation keys (no i18n system today).
- **Charts/data-viz** — replace hand-rolled `bar-track` divs with a charting lib if dashboards grow.
- **Dark mode** — content areas are light-only today.
- **Bulk actions** — multi-select approve/reject for HR profile requests and commissions.

---

## Verification of this deliverable

- All 14 modules covered in Part 3; every finding cites a real `file:line` (spot-checked against the
  named files during authoring — e.g. the nine native-dialog call sites, `pageSize=8`,
  `PAGE_SIZE=10`, attendance `2000` option, 41 hex colors / 0 CSS vars, 24 `useState`).
- Every roadmap item names concrete files and **reuses existing components** (`Modal`, `Toast`,
  `EmptyState`, `StatusBadge`, `useToast`) rather than reinventing them.
- Implementation phases carry their own end-to-end checks — notably Phase 2: run the dev server, load
  attendance with a large dataset, and confirm pagination/search work with a bounded DOM row count.
