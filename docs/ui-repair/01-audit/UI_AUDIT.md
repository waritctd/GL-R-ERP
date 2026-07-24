# UI Audit — Findings

Current-state UI findings for the GL-R ERP frontend, reconciled from: live capture
this session (`../evidence/current/`), an Impeccable **critique** pass (design
review / Nielsen) and an Impeccable **audit** pass (technical / a11y / responsive),
each weighed against actual ERP workflows, role permissions, Thai content, and the
design law (`frontend/.claude/rules/frontend-ui.md`, `DESIGN.md`). Recommendations
that would push the tool toward a decorative/consumer app were **rejected** (see
"Rejected directions" at the end).

**Severity spread (deliberately not P1-inflated):** P0 = 0 · P1 = 2 · P2 = 11 ·
P3 = 8. **No P0** (no security/privacy exposure, destructive-action, impossible
critical task, or data-corruption risk was found in the rendered UI).

**Field key:** Sev · Role · Workflow · Route · Viewport · State · Evidence ·
Observation · Consequence · Likely root cause · Repair direction · Component ·
Confidence (confirmed/probable/uncertain).

---

## P1

### F-01 — Tablet band (721–1040px) renders a broken, unreadable sidebar
- **Sev** P1 · **Role** all (esp. managers: ceo / sales_manager / division_manager, the mixed-device persona) · **Workflow** navigation (all) · **Route** all · **Viewport** 768×1024, 1024×768 · **State** populated
- **Evidence** [ceo-commissions tablet](../evidence/current/_responsive-spot/ceo-commissions/tablet-768x1024.png), [ceo-landing tablet](../evidence/current/_responsive-spot/ceo-landing/tablet-768x1024.png)
- **Observation** The mobile breakpoint is 720px, so 721–1040px still gets the full 260px desktop rail — but group headers wrap and collide (“งาน / ขาย”, “การ / เงิน”, “บุคคล / ของ / ฉัน”), the English sub-labels overlap them, and on `/commissions` the page subtitle “Sales & Commission Management” wraps over the toolbar. Content is squeezed into one cramped column.
- **Consequence** A manager on an iPad-portrait or half-width laptop window cannot navigate by reading — labels are fragments; the shell looks broken at a whole device class.
- **Likely root cause** Single hardcoded 720px breakpoint in three places (`hooks/useIsMobile.js:3`, `styles.css` `@media` `:1871/:2021`, `Button.jsx:24`); no intermediate tablet treatment.
- **Repair direction** Add one deliberate tablet behaviour: keep the full labelled rail until a higher breakpoint, **or** a true icon-rail with tooltips/`aria-label` and group-header text fully suppressed when collapsed (labels-or-nothing, never fragments). Introduce a shared breakpoint token (see RESPONSIVE_AUDIT).
- **Component** `Sidebar.jsx` / `AppShell.jsx`; the 720px breakpoint token · **Confidence** confirmed

### F-02 — Interactive controls nested inside clickable table rows (invalid DOM + keyboard/SR breakage)
- **Sev** P1 · **Role** all with clickable tables (sales/sales_manager/ceo/hr/import) · **Workflow** deal list, employees, any `DataTable` with `onRowClick` · **Route** `/tickets`, `/employees`, … · **Viewport** all · **State** populated
- **Evidence** source `DataTable.jsx:255` (`RowTag = onRowClick ? 'button' : 'div'`) with `role="row"` at `:336/:362`, cells holding real `Button`s (`Button.jsx:61`) → an invalid `<button>`-in-`<button>` (`validateDOMNesting`) is structurally certain on every clickable-row table (account & ceo were the live-captured callers). Call site e.g. `TicketListPage.jsx:609`.
- **Observation** `DataTable` renders each clickable row as `<button role="row">` (`DataTable.jsx:255,360-378`); cells contain further real buttons (`Button`, status-badge, action buttons), producing the confirmed React warning `validateDOMNesting: <button> cannot appear as a descendant of <button>` (`Button.jsx:61/67`). Call site e.g. `TicketListPage.jsx:609`.
- **Consequence** Invalid HTML; ambiguous/non-deterministic hit-testing (a click on an inner action may also fire the row navigation); inner row actions are unreachable or mis-announced for keyboard and screen-reader users — an AT user may be unable to complete a per-row action.
- **Likely root cause** Whole-row-as-button pattern (`RowTag = onRowClick ? 'button' : 'div'`) combined with interactive cell content.
- **Repair direction** Stop nesting interactives: make the row a non-button container with a single primary “open” affordance (a real link/button in one cell) and keep other actions as siblings, **or** use a grid pattern where the row is not itself a button. Do not remove keyboard access.
- **Component** `DataTable.jsx` (shared) · **Confidence** confirmed

---

## P2

### F-03 — Permission-denied is a silent redirect to `/` (no feedback)
- **Sev** P2 · **Role** all · **Workflow** any deep-link to a route the role lacks · **Route** guarded routes · **Viewport** all · **State** ⛔ permission
- **Evidence** source `RequireAccess.jsx:10` (`<Navigate to="/" replace />`); denied-probe captures, each landing on `/` with no message — [hr→/finance](../evidence/current/hr/denied-probe/desktop-1366x768.png), [employee→/payroll](../evidence/current/employee/denied-probe/desktop-1366x768.png), [sales→/payroll](../evidence/current/sales/denied-probe/desktop-1366x768.png), [import→/finance](../evidence/current/import/denied-probe/desktop-1366x768.png), [account→/payroll](../evidence/current/account/denied-probe/desktop-1366x768.png)
- **Observation** `RequireAccess` renders `<Navigate to="/">` on a denied path — the user is bounced to the dashboard with no message.
- **Consequence** A user following a stale notification/deep-link to a page they can’t access lands on home with no explanation and may think the link or app is broken.
- **Likely root cause** `RequireAccess.jsx` uses a bare redirect; no “not authorized” surface.
- **Repair direction** Show a brief, calm “ไม่มีสิทธิ์เข้าถึง” notice (toast or inline) before/instead of the silent bounce; keep it utilitarian.
- **Component** `RequireAccess.jsx` · **Confidence** confirmed

### F-04 — Landings lead with an oversized metric-card grid that buries the actionable content
- **Sev** P2 · **Role** ceo (worst), hr (payroll), all landings · **Workflow** dashboards · **Route** `/`, `/commissions`, `/payroll` · **Viewport** 1366×768 · **State** ∅/populated
- **Evidence** [ceo landing](../evidence/current/ceo/landing/desktop-1366x768.png), [commissions](../evidence/current/ceo/commissions/desktop-1366x768.png), [payroll](../evidence/current/hr/payroll/desktop-1366x768.png)
- **Observation** Each landing opens with 4–6 equally-weighted metric cards, each with an icon tile; the CEO’s is five cards all showing `0`/`฿0`, with the live “รออนุมัติจากคุณ” worklist below the fold. This is the design law’s named anti-patterns (“oversized metric cards that displace operational information”, “icon tiles above every heading”).
- **Consequence** The highest-value user opens the app to a wall of zeros instead of “3 deals awaiting your price approval”; the dashboard fails to route them to a decision; eye has no entry point (hierarchy).
- **Likely root cause** Per-role Overview components each hand-build a metric-card header first, worklist second.
- **Repair direction** Demote metrics to a compact stat strip (no icon tiles), promote the worklist to the top (as account/import already do); cards earn space only for non-zero actionable counts. **Not** bigger/animated hero tiles.
- **Component** the Overview components; a shared stat-strip + worklist pattern (Phase 3) · **Confidence** confirmed

### F-05 — “Needs my action” vs “waiting on others” is not a consistent system
- **Sev** P2 · **Role** ceo/hr/employee (weak) vs account/import/sales (strong) · **Workflow** all worklists/landings · **Route** `/`, request lists · **Viewport** all · **State** populated
- **Evidence** [employee landing](../evidence/current/employee/landing/desktop-1366x768.png) (mixes รอผู้จัดการ + อนุมัติแล้ว), [account landing](../evidence/current/account/landing/desktop-1366x768.png) (clean)
- **Observation** Three roles get a scannable action queue with scoped next-action buttons; others get counts to interpret. The employee timeline mixes waiting-on-others (`รอผู้จัดการ`) and done (`อนุมัติแล้ว`) in one undifferentiated list.
- **Consequence** Staff can’t tell at a glance what is blocked on *them* vs on someone else — the exact question the workflow law says every record must answer.
- **Repair direction** One app-wide worklist pattern with an explicit “mine to act” vs “waiting” split, driven by whether the current user can perform the next transition; reuse the account worklist idiom.
- **Component** shared worklist pattern (propose Phase 3) · **Confidence** confirmed

### F-06 — Create-deal is a 6-step wizard trapped in a modal
- **Sev** P2 · **Role** sales · **Workflow** deal creation · **Route** `/tickets` (modal) · **Viewport** 1366 + 390 · **State** M wizard
- **Evidence** [modal desktop](../evidence/current/sales/create-deal-modal/desktop-1366x768.png), [modal mobile](../evidence/current/sales/create-deal-modal/mobile-390x844.png)
- **Observation** A multi-section aggregate-creation flow (`0/6 เสร็จ`: customer/project/contact/lines/details/review) lives in an overlay; sections read as cards-inside-the-modal; on mobile it’s a modal in a small viewport with a toast firing over it. DESIGN.md: “modals are a last resort … complex mobile tasks may become full-screen workflows.”
- **Consequence** A rep loses page context, can’t deep-link/resume a draft as a first-class URL, and fights a cramped overlay on the phone-first surface.
- **Repair direction** Promote to a full-page (full-screen on mobile) create route, same 6-section progressive checklist + draft-save. Keep the checklist metaphor; get it out of the overlay.
- **Component** `TicketCreateModal.jsx` → a create route · **Confidence** confirmed

### F-07 — Shared table exposes a malformed ARIA grid
- **Sev** P2 (important a11y) · **Role** all · **Workflow** every list · **Route** all with `DataTable` · **Viewport** all · **State** populated/loading
- **Evidence** source `DataTable.jsx`
- **Observation** `role="table"` on a `<section>` (`:284`), rows `role="row"` on a `<button>`/`<div>`, cells `role="cell"` on `<span>` — **no `rowgroup`**, `role="row"` overrides the button’s role, and interactive rows sit in a static `table` (should be a `grid` with `gridcell`). Skeleton rows are `role="row"` too.
- **Consequence** Screen readers announce the app’s tables inconsistently (row/column counts and header association unreliable); compounds F-02.
- **Repair direction** Rebuild the table’s a11y contract (native `<table>` semantics or a correct `grid` with `rowgroup`/`gridcell`); resolve alongside F-02.
- **Component** `DataTable.jsx` (shared) · **Confidence** confirmed

### F-08 — No global visible focus indicator on buttons/links
- **Sev** P2 (a11y, WCAG 2.4.7) · **Role** all · **Route** all · **Viewport** all · **State** keyboard focus
- **Evidence** source `styles.css:240,249-254`; `Button.jsx`
- **Observation** `outline:none` is stripped and a `box-shadow` focus ring is added only for inputs and a few named classes; there is no global `button:focus-visible`/`a:focus-visible` ring, and the `Button` component sets none. Icon-only pagination/close buttons risk showing no focus.
- **Consequence** Keyboard users can lose track of focus on many controls.
- **Repair direction** Add a single global `:focus-visible` ring token applied to all interactive elements; verify per control class.
- **Component** `styles.css` / `Button.jsx` · **Confidence** confirmed (source); **probable** on exact per-control rendering

### F-09 — Form errors are not programmatically associated
- **Sev** P2 (a11y, WCAG 3.3.1/4.1.3) · **Role** all · **Workflow** every form · **Route** create/edit forms · **Viewport** all · **State** validation error
- **Evidence** source `styles.css:1586-1596,294-301`; `ConfirmDialog.jsx`, `FormField.jsx`
- **Observation** Invalid state is conveyed by a red border + a colored `.form-error` text block, with no `aria-invalid` on the field and no `aria-describedby` linking field→message.
- **Consequence** Screen-reader users aren’t told which field failed or why; error state is also colour-dependent.
- **Repair direction** Wire `aria-invalid` + `aria-describedby` in the shared `FormField`; ensure a non-colour error cue.
- **Component** `FormField.jsx` (shared) · **Confidence** confirmed

### F-10 — Thai-first violated: English strings on load-bearing controls
- **Sev** P2 · **Role** hr (payroll), ceo/account (commissions), sales (ticket detail) · **Workflow** payroll, commissions, deal · **Route** `/payroll`, `/commissions`, `/tickets/:id` · **Viewport** all · **State** populated
- **Evidence** [payroll](../evidence/current/hr/payroll/desktop-1366x768.png) (“Process Payroll”, “Preview”, “Email payslips”, “Payroll Processing”), [commissions](../evidence/current/ceo/commissions/desktop-1366x768.png) (“Sales & Commission Management”, “OT / COMMISSION”), [ticket detail](../evidence/current/ceo/ticket-detail/desktop-1366x768.png) (“พัก dormant”)
- **Observation** Action buttons and column headers — the load-bearing verbs — are English on Thai screens; `พัก dormant` welds a Thai verb to an English adjective (`DealStagePanel.jsx:209/374`, `TicketDetailPage.jsx:40`).
- **Consequence** A Thai-primary staffer runs payroll from an English verb on the single most consequential (money) button; the dormant vs on-hold distinction is invisible.
- **Repair direction** Translate all action buttons, page subtitles, and column headers to Thai (`ประมวลผลเงินเดือน`, `ดูตัวอย่าง`, `ส่งสลิปทางอีเมล`; a real Thai DORMANT label). English *helper* subtitles in nav may stay.
- **Component** payroll/commission/deal feature copy · **Confidence** confirmed

### F-11 — HR sees the ค่าคอมมิชชัน nav item but lacks list access
- **Sev** P2 · **Role** hr · **Workflow** commissions · **Route** `/commissions` · **Viewport** all · **State** likely ∅/limited
- **Evidence** `AppShell.jsx:94` (`canViewCommissions` includes hr) vs `routes.js` `canListCommissionRecords` = sales/sales_manager/ceo only
- **Observation** HR gets the sidebar item because `canViewCommissions` includes `hr`, but the list read is a *different* gate (`canListCommissionRecords`) that excludes HR — so the page likely renders empty/limited for HR.
- **Consequence** HR clicks a menu item that leads to a page that can’t show them commission records — a dead-end/misleading nav entry.
- **Repair direction** Either hide the nav item for HR or make the page state explicit (“HR view: payroll-ready only”). *Note: this touches the permission↔nav mapping — treat any change per CLAUDE.md authz-evidence rules, not as UI polish.*
- **Component** `AppShell.jsx` nav condition · **Confidence** probable (not confirmed by rendering HR /commissions this pass — see AUDIT_GAPS)

### F-12 — Metric cards horizontal-scroll and clip Thai labels on mobile
- **Sev** P2 · **Role** account (and other 5-card landings) · **Workflow** dashboards · **Route** `/` · **Viewport** 390×844 · **State** populated
- **Evidence** [account landing mobile](../evidence/current/account/landing/mobile-390x844.png)
- **Observation** Five metric cards form a horizontally-scrolling row on the phone; “รอชำระส่...” is clipped mid-word.
- **Consequence** Key figures are half-hidden on the phone-first surface; violates “long Thai labels must wrap/truncate intentionally”.
- **Repair direction** Reflow the stat row to a 2-up wrapping grid or a compact summary line on mobile; never a clipped card row.
- **Component** Overview stat row · **Confidence** confirmed

### F-13 — Consistency debt: dual button system across the app
- **Sev** P2 · **Role** all · **Workflow** all · **Route** many · **Viewport** all · **State** all
- **Evidence** see [COMPONENT_DUPLICATION](COMPONENT_DUPLICATION.md#1-buttons--two-parallel-systems-️-the-main-finding)
- **Observation** The cva `<Button>` component (26 files) coexists with legacy `.primary-button`/`.secondary-button`/`.icon-button` CSS classes (16–22 files); a single page can mix both, and the CSS classes don’t all carry the 44px touch floor / focus handling.
- **Consequence** Subtly inconsistent button sizing/focus/touch targets across screens; the lowest Nielsen score (Consistency = 2) traces largely here.
- **Repair direction** Migrate raw `.*-button` sites to `<Button>`; retire the classes — one verified surface at a time (Phase 4+).
- **Component** `Button.jsx` vs `styles.css` button classes · **Confidence** confirmed

---

## P3

### F-14 — Redundant back-navigation on detail records
- **Sev** P3 · **Role** ceo/hr · **Route** `/tickets/:id`, `/employees/:id` · **Viewport** all · **State** RO/detail · **Evidence** [ticket detail](../evidence/current/ceo/ticket-detail/desktop-1366x768.png), [employee detail](../evidence/current/hr/employee-detail/desktop-1366x768.png)
- **Observation/consequence** Both a breadcrumb *and* a full-width centered “กลับ” button point up; the full-bleed bar wastes prime vertical space above every record and reads marketing-ish.
- **Repair** Keep the breadcrumb as the single up-nav; drop or shrink the back bar to an inline text-back. · **Confidence** confirmed

### F-15 — Duplicate nav destinations / “tickets/ภาพรวม” stutter
- **Sev** P3 · **Role** sales/ceo · **Route** `/tickets` + `/ticket-overview` · **Observation** Sales landing’s “ดูรายการดีลทั้งหมด” duplicates the `รายการดีล` nav item; `/tickets` (list) + `ภาพรวม` tab are one workspace under one nav item. **Repair** Audit the 5 nav groups for one-destination-two-controls. · **Confidence** probable

### F-16 — “User is not linked to an employee” error toast (sales)
- **Sev** P3 · **Role** sales · **Route** `/tickets` mobile · **State** ✗ error · **Evidence** [tickets mobile](../evidence/current/sales/tickets/mobile-390x844.png)
- **Observation/consequence** A red error toast fires for the sales persona (seed `employeeId:null`). Largely a seed artifact, but the app surfaces a raw-ish error rather than degrading quietly. **Repair** Ensure deal pages tolerate an unlinked user without an error toast. · **Confidence** probable (seed-related)

### F-17 — Colour literals instead of tokens
- **Sev** P3 · `TicketCreateModal.jsx` (`#ef4444` ×14), `NotificationBell.jsx` (4). **Repair** Use `--color-danger` etc. Maintainability only. · **Confidence** confirmed

### F-18 — Modal labelling / background inert-ness
- **Sev** P3 (a11y) · `Modal.jsx:53-58,65` uses `aria-label` only (no `aria-labelledby` to the visible `<h2>`); background not `inert`/`aria-hidden`, so AT can traverse behind the modal. **Repair** Add `aria-labelledby`; mark background inert. · **Confidence** confirmed

### F-19 — One hand-rolled modal outlier
- **Sev** P3 · `features/auth/ChangePasswordModal.jsx` hand-rolls `modal-backdrop` instead of using shared `Modal.jsx`. **Repair** Move onto `Modal.jsx`. · **Confidence** confirmed

### F-20 — Icon-only buttons rely on caller-supplied names
- **Sev** P3 (latent a11y) · `Button variant="icon"` doesn’t enforce an `aria-label`; most callers pass one (pagination/close do). **Repair** Enforce/default an accessible name on icon variant. · **Confidence** probable

### F-21 — Payroll is desktop-only (by design)
- **Sev** P3 (note) · `PayrollPage.jsx:447` renders `DesktopOnlyNotice` <720px. Acceptable for a month-end grid, but recorded: payroll is unavailable on phones. · **Confidence** confirmed

---

## Rejected directions (do NOT do)
Per the design law and the reconciliation, these tempting “fixes” were **rejected**
because they’d turn a control desk into a consumer/marketing app:
- Replacing flat zero-cards with hero banners, gradient/animated stat tiles, or
  illustration-heavy empty states (F-04 is *fewer, quieter* cards + promoted
  worklist, not louder ones).
- Multiplying teal beyond “live/active” (progress dots/fills must not become decor).
- Any gradient, glassmorphism, decorative motion, or card-in-card to “modernise”.

## Verified-good (not defects)
StatusBadge conveys status by **text + colour** (no WCAG 1.4.1 failure); the
muted/faint text-contrast “Muted Floor” is already remediated (faint reserved for
icons + navy sidebar); reduced-motion is handled; `DataTable` mobile-card reflow
and the create-deal mobile modal reflow correctly; `Modal.jsx` focus-trap/Escape/
restore is solid.
