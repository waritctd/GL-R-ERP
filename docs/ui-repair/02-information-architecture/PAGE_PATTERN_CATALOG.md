# Page & Component Pattern Catalog

Structural patterns — the *shape* of each recurring surface, not its styling (that's
Phase 3). Each pattern says what jobs it serves, its required regions, where the primary
action goes, how it transforms on mobile, its accessibility contract, its loading/empty/
error states, and which existing pages should migrate to it. This is the vocabulary Phase 4
builds against, extending the healthy shared design system (`components/common/`) rather
than rebuilding it.

> The audit found the shared primitives are **largely healthy and well-adopted**
> (`PageHeader` ×28, `StatusBadge` ×39, `DataTable` ×7, `Modal` ×16, `FormField` ×11,
> `EmptyState` ×16). The one systemic duplication is the **dual button system** (cva
> `<Button>` vs legacy `.*-button` CSS). These patterns assume consolidation onto the
> shared primitives, one verified surface at a time.

---

## 1. Work queue

- **Jobs** — "what needs me?" The app-wide realisation of WORK_STATE_MODEL. Powers every
  role landing and the "งานของฉัน" concept.
- **Required regions** — (a) *mine-to-act* list (states 1/4/8), sorted by urgency; (b) a
  clearly separated *waiting/for-reference* list (states 2/9); (c) per-row: subject · who ·
  since-when · the **one** next action; (d) optional compact stat strip *below*, never above.
- **Primary action** — per-row primary action button (right-aligned on desktop, full-width
  in the mobile card). The row's most likely single action, not a menu of all.
- **Mobile** — rows reflow to record cards; primary action full-width; waiting section
  collapsed by default.
- **A11y** — list semantics (not a fake grid); each action a real button with an accessible
  name; the "mine vs waiting" split conveyed by heading + text, not colour alone.
- **States** — *loading*: skeleton rows; *empty*: "ไม่มีงานที่ต้องดำเนินการ" + a route-onward
  link (never a dead end, F-04); *error*: inline retry, preserve any loaded rows.
- **Migrate** — `AccountOverview`/`AccountFinancePage` (already the reference), `ImportOverview`,
  `EmployeeSelfService` (already good), plus the *new* CEO/HR/sales/sales_manager landing
  worklists (replacing their metric-card heroes, F-04). One shared **WorklistRow** /
  action-cell primitive (proposed in COMPONENT_DUPLICATION) so the split is identical
  everywhere — the fix for F-05.

## 2. Data-heavy list (dense table)

- **Jobs** — scan/sort/filter many records with several columns (deals, employees,
  commissions, catalog, PCR queue, procurement).
- **Required regions** — filter/search bar (with URL-persisted state), the table, pagination,
  a row-open affordance.
- **Primary action** — a single clear "open" affordance per row; per-row secondary actions as
  siblings, **not** nested inside a row-button (fixes F-02/F-07 — the row must not be a
  `<button>` wrapping more buttons).
- **Mobile** — reflow to record cards via the `mobileCard` prop (already the mechanism); never
  a squeezed desktop grid; any `DataTable` caller **without** a `mobileCard` is a defect to
  fix (RESPONSIVE_AUDIT).
- **A11y** — rebuild the table's a11y contract: native `<table>` semantics or a correct
  `grid` with `rowgroup`/`gridcell`; global focus-visible ring; skeleton rows not announced as
  data (fixes A-01/A-02/A-03).
- **States** — *loading*: skeleton rows; *empty*: `EmptyState` with context ("ยังไม่มีดีล");
  *error*: inline, retry; *overflow*: horizontal scroll contained, never body-level.
- **Migrate** — `TicketListPage`, `EmployeeListPage`, `CommissionPage`, `CatalogSearchPage`,
  `PricingRequestQueuePage`, `ProcurementListPage` — all onto the fixed shared `DataTable`.

## 3. Mobile record list (card reflow)

- **Jobs** — the mobile form of pattern 2; a scannable list of records as cards.
- **Required regions** — per card: identity · status badge · the 2–3 facts that matter for the
  work · one primary action.
- **Primary action** — full-width or thumb-reachable within the card.
- **Mobile** — this *is* the mobile pattern; ≤720px.
- **A11y** — each card a landmark/list item; status via text+badge; tap target ≥44px (the cva
  `<Button>` carries the 44px floor; legacy `.*-button` may not — migrate).
- **States** — as pattern 2.
- **Migrate** — the `mobileCard` renderers (`DealCard`, commission/attendance/procurement/
  employee cards) — already the mechanism; standardise into one card contract.

## 4. Record detail

- **Jobs** — view/act on one complex record (the deal, an employee, a PCR, a commission).
- **Required regions** — persistent header (identity + status + work-state + stage where
  relevant), tabs for depth, optional context side-panel, sticky action bar. (For the deal,
  fully specified in [TICKET_INFORMATION_ARCHITECTURE](TICKET_INFORMATION_ARCHITECTURE.md).)
- **Primary action** — sticky action bar; the single next allowed action for the viewer;
  disabled actions explain *why* (WHY gap).
- **Mobile** — header condensed but always showing identity + work-state + primary action;
  tabs become a scrollable/accordion set; context panel folds into sections.
- **A11y** — one breadcrumb as the up-nav (drop the full-width back bar, F-14); tab
  semantics; focus management on tab change.
- **States** — *loading*: header skeleton + tab skeleton; *empty sub-tab*: contextual empty;
  *error*: keep the header, error the body; *permission*: don't render a tab the backend 403s.
- **Migrate** — `TicketDetailPage` (biggest), `EmployeeDetailPage`, `PricingRequestDetailPage`,
  `ProcurementDetailPage`, `DepositNoticePage`.

## 5. Approval task

- **Jobs** — decide on someone else's submitted work (pricing decision, commission, OT/SM,
  leave, profile-request, close-verify).
- **Required regions** — what's being decided · who submitted · the evidence needed to decide ·
  approve / reject(+reason) / (return where the workflow has it) · the routing/where-it-goes-next.
- **Primary action** — the approve action; reject/return require a reason (existing
  `requireReason` pattern). **Two-signature** flows (deal close) and **two-hop** flows
  (OT/SM/commission) must show which signature/hop this is.
- **Mobile** — one-tap approve; reason entry as a focused sheet; the persona (CEO/manager) is
  mobile-heavy, so this must be excellent on a phone.
- **A11y** — actions are real buttons; reason field associated errors; decision result
  announced.
- **States** — *loading*: skeleton; *empty*: "ไม่มีรายการรออนุมัติ" + onward link; *error*:
  don't lose the decision context; *already-decided* (race): show the new state, disable.
- **⚠ Concurrency is guard-based, not lock-based (G-3, red-team).** There is **no `@Version`**
  optimistic locking anywhere in the backend — two approvers on the same transition, or one
  approver acting from a stale tab (scenarios 22/23), are caught only by the state-machine
  guard on the second write (a 409/422). It is fail-safe against lost updates, but the whole
  burden falls on this pattern: the *already-decided* state is therefore **required, not a
  nicety**, and surfaces should **refetch on window focus/visibility** to shrink the stale-tab
  window. Backend `@Version` hardening is [OUT OF SCOPE — backend]. **Resolution (D-16):** the
  *already-decided* handling is a **required** contract — on a 409/422 refetch, render the new
  work-state, and disable the stale action with a reason (never a raw toast); live-approval
  surfaces set `refetchOnWindowFocus`.
- **Migrate** — the CEO/manager approval surfaces across pricing (`PricingRequestDetailPage`
  CEO section), `CommissionPage` approvals, OT/SM panels, `ProfileRequestsPage`, and the deal
  close-verify action bar. A shared **ApprovalTask** shell keeps them consistent.

## 6. Multi-step creation

- **Jobs** — build an aggregate progressively (create-deal; create-PCR; create-employee).
- **Required regions** — progress indicator (the "n/m เสร็จ" checklist), the current section,
  save-as-draft, review-before-commit.
- **Primary action** — advance / save-draft / final create; disabled-until-valid with the
  reason shown.
- **Mobile** — full-screen focused flow, **not** a modal in a viewport (see
  [CREATE_TICKET_FLOW](CREATE_TICKET_FLOW.md)).
- **A11y** — step labels, error association (F-09), unsaved-changes guard.
- **States** — *loading*: section skeleton; *validation*: inline field errors (not just a
  disabled submit); *failure*: preserve input, recoverable; *resume*: rehydrate a draft.
- **Migrate** — `TicketCreateModal` → dedicated create route/flow (F-06);
  `PricingRequestCreateModal`, `EmployeeFormModal`, `ProductFormModal` — evaluate each: keep
  as a modal only if genuinely single-step; promote to this pattern if multi-section.

## 7. Configuration page

- **Jobs** — set system config (CEO price/FX settings).
- **Required regions** — grouped settings, current values, save with confirmation, validation.
- **Primary action** — save (with a confirm for consequential changes).
- **Mobile** — single-column stacked; desktop-acceptable to deprioritise (low-frequency, CEO).
- **A11y** — labelled fields, associated help/errors.
- **States** — *loading*: form skeleton; *save success/error*: clear feedback; *dirty*: guard.
- **Migrate** — `CeoSettingsPage`.

## 8. Self-service form

- **Jobs** — an employee submits one request (leave, OT, welfare, profile-change).
- **Required regions** — the form, the routing preview ("ส่งแล้ว › ผู้จัดการ › CEO"), balances/
  quota context, own-request history/status.
- **Primary action** — submit; cancel-own where allowed.
- **Mobile** — this is the phone-first surface; one-thumb, minimal, focused.
- **A11y** — associated errors (F-09), attachment states, clear success.
- **States** — *loading*; *validation* (e.g. SICK needs cert or auto-rejects — surface
  *before* submit); *over-quota unpaid warning* (surface the money consequence clearly);
  *success*: show the routing + status; *disabled evidence upload* (special-money — the
  not-implemented placeholder must read as "not available", not broken).
- **Migrate** — `LeavePage`, `OvertimePanel`, `SpecialMoneyPanel`, `ProfilePage`/
  `ChangeRequestModal`. **Note the HR/CEO oversight framing (business rule):** for HR/CEO the
  OT/leave surfaces are **not** this pattern — they are pattern 1/2 (oversight summary/history
  over all employees), not a submit form. Same route, different pattern by viewer.

## 9. Document list

- **Jobs** — see/download a record's documents; distinguish generated vs uploaded.
- **Required regions** — doc name · type (generated: quotation/deposit-notice/remaining-invoice
  · uploaded: tax invoice/attachments) · date · download; upload affordance where the role may.
- **Primary action** — download (per row); upload (where permitted).
- **Mobile** — list of rows, tap to download.
- **A11y** — links/buttons named; file-type conveyed in text.
- **States** — *empty*: "ยังไม่มีเอกสาร"; *upload*: progress/success/error (an audit gap —
  upload states weren't exercised); *permission*: hide docs a role can't see (deposit notice
  hidden from import).
- **Migrate** — the deal Documents tab, deposit/quotation/invoice surfaces, PCR/factory-quote
  attachments.

## 10. Audit timeline / routing

- **Jobs** — show a record's history: activity, status transitions with actor + timestamp, and
  the "who's next" routing path.
- **Required regions** — chronological events; actor + time per transition; the forward routing
  strip ("ส่งแล้ว › หัวหน้าฝ่าย › CEO").
- **Primary action** — none (read-only); add-note where relevant.
- **Mobile** — vertical timeline.
- **A11y** — ordered list semantics; time elements.
- **States** — *loading*: skeleton; *empty*: "ยังไม่มีกิจกรรม".
- **Migrate** — the deal Activity tab (activity + audit), the self-service routing strip
  (already exists — the model), the deal pipeline strip (a specialised stage timeline). Candidates
  to unify conceptually (COMPONENT_DUPLICATION §5).

## 11. Empty state

- **Jobs** — communicate "nothing here" **and route onward** — never a dead end (F-04).
- **Required regions** — a plain-language line; the next best action/link; (optionally) why
  it's empty.
- **Primary action** — the onward CTA (create, go-to-queue, adjust-filter).
- **Mobile** — centered, compact.
- **A11y** — text, not just an illustration; the CTA is a real control.
- **States** — distinguish *truly empty* from *empty-because-out-of-scope* (the account
  close-ready tabs read empty due to a backend scope gap — the empty state must not imply
  "done", `AccountFinancePage.jsx:58-63`) and from *filtered-to-empty* (offer clear-filter).
- **Migrate** — standardise on `EmptyState` (×16 already); replace inline empties.

## 12. Error / recovery state

- **Jobs** — tell the user what failed and how to recover (F-03 silent-redirect, F-16 raw
  toast are current failures).
- **Required regions** — plain-language cause; a recovery action (retry / go-back / contact);
  preserved user input where relevant.
- **Primary action** — retry or the safe way out.
- **Mobile** — full-width, thumb-reachable action.
- **A11y** — error announced (role=alert); focus moved to the message; not colour-only.
- **States** — *permission-denied*: a calm "ไม่มีสิทธิ์เข้าถึง" notice instead of a silent
  bounce to `/` (F-03); *network/500*: retry; *validation*: inline, associated (F-09);
  *unlinked-user seed*: degrade quietly, no raw error toast (F-16).
- **Migrate** — `RequireAccess` (add the denied notice), the global toast/`ErrorBoundary`, the
  deal pages' unlinked-user handling.

---

## Cross-pattern rules
1. **Extend, don't rebuild** the shared design system — most primitives are healthy.
2. **One button system** — migrate legacy `.*-button` sites to the cva `<Button>` (44px floor,
   focus, tokens); retire the CSS classes one verified surface at a time (F-13).
3. **One worklist idiom** (pattern 1) everywhere — the app-wide "mine vs waiting" contract.
4. **Every pattern specifies loading + empty + error + permission** — not just the happy path
   (the Change-Control checklist requires it).
5. **Same route can be different patterns by viewer** (self-service form vs oversight list for
   OT/leave) — pattern is chosen by `(route, viewer)`, matching WORK_STATE_MODEL's per-viewer
   rule.
