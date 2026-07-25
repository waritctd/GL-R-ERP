# IA Decision Log

Every material Phase-2 architecture decision, with evidence, the alternatives weighed, why
they were rejected, the risk, whether it's reversible, and what must be validated in a later
phase. Decisions here are **analysis only** — none changes production code, routes, or authz.

Legend: **Rev** = reversibility (Easy / Moderate / Hard).

---

### D-01 · Navigate around work, not modules
- **Decision** Reorganise nav into work-oriented concepts (My Work, Pipeline, Pricing &
  Import, Orders & Fulfilment, Finance, People & Attendance, Administration), role-scoped;
  lead each role at its worklist.
- **Evidence** Design law ("Operations Control Desk"); F-04/F-05 (metric-card heroes bury
  work; no consistent mine-vs-waiting split); account/import/employee worklists already work.
- **Alternatives** Keep the module grouping (งานขาย/บุคคล/…); a fully flat nav.
- **Reason for rejection** Module grouping makes users translate "my job" into "which table";
  flat nav loses phase structure for multi-role deals.
- **Risks** Users habituated to the current labels; regrouping without care could hide a
  familiar item.
- **Rev** Easy (nav is a data array + labels).
- **Validate later** Phase-4 usability check with real personas; confirm no role loses access
  to anything it has today (NAVIGATION_MIGRATION_MAP preserves all routes).

### D-02 · One computed work-state per record, per viewer
- **Decision** Introduce a `workState(record, viewer)` classifier (9 states) derived from
  existing data + the real role gates; no new backend status.
- **Evidence** F-05 (no consistent needs-mine vs waiting); WORK_STATE_MODEL mapping fits every
  aggregate without schema change.
- **Alternatives** Add DB status columns; per-page ad-hoc logic (status quo).
- **Reason for rejection** Schema change is out of scope and unnecessary; ad-hoc logic is why
  the split is inconsistent today.
- **Risks** Mis-classifying *Waiting* as *Needs-my-action* would invite a 403 action.
- **Rev** Moderate (a shared function many surfaces would use).
- **Validate later** Unit-test the "can act now" branch against the **Java** role sets; any
  surface acting on it re-checked against the backend, never the mock (CLAUDE.md).

### D-03 · Worklist-first landings; metrics are a compact strip, never the hero
- **Decision** Every role landing leads with a worklist + one primary action; metrics demoted
  to a compact, non-zero, no-icon-tile strip below.
- **Evidence** F-04; design law anti-patterns (oversized metric cards, icon tiles); the CEO's
  all-zero-cards landing.
- **Alternatives** Keep metric-card dashboards; add analytics dashboards per role.
- **Reason for rejection** Metric grids fail to route the user to a decision; analytics is not
  what most roles need on login.
- **Risks** Some roles may want a couple of real numbers up top — handled by the strip.
- **Rev** Moderate (landing components).
- **Validate later** Phase-4 per-role landing review; confirm empty states route onward.

### D-04 · Create-deal moves from modal to a dedicated responsive flow
- **Decision** Replace the 6-section create modal with a dedicated full-page (desktop) /
  full-screen (mobile) flow at a first-class URL, keeping the checklist metaphor.
- **Evidence** F-06; DESIGN.md ("complex mobile tasks may become full-screen workflows");
  drafts are first-class records (`status=draft`).
- **Alternatives** Wider modal; keep the modal, fix only mobile.
- **Reason for rejection** A modal can't deep-link, resume-as-URL, or recover from failure;
  mobile-in-a-modal is the core F-06 failure.
- **Risks** A route/registration implication if implemented as a new path.
- **Rev** Moderate–Hard (touches the create surface).
- **Validate later** Phase-4 decides `/tickets/new` vs draft-`/tickets/:id`; preserve all
  routes; verify `canCreateTickets` unchanged.

### D-05 · The ticket is the canonical record; PCR is an aggregate inside it
- **Decision** Model the deal detail as header + role-projected tabs, with **Pricing** as a
  *list of PCRs* (designer/owner/buyer + revisions), not a single field.
- **Evidence** Code: PCR is a separate aggregate with its own status chain; multiple PCRs per
  deal; `salesView` strips cost/margin from sales.
- **Alternatives** Treat pricing as a ticket sub-status (the pre-Step-1 model).
- **Reason for rejection** Step-1 severed ticket `submit()` (409s); pricing genuinely is its
  own aggregate now.
- **Risks** Complexity — a deal can have several concurrent PCRs.
- **Rev** Moderate (detail-page structure).
- **Validate later** Phase-4 render with non-empty PCR data (Phase-1 gap: PCR detail never
  captured — seed it).

### D-06 · Preserve every route; regroup and relabel only
- **Decision** No route/path changes in Phase 2–3; nav change is grouping + labels + ordering +
  which landing leads.
- **Evidence** `UI_REPAIR_RULES.md` (routes/permissions don't change as a side effect); all 24
  routes catalogued in NAVIGATION_MIGRATION_MAP.
- **Alternatives** Rename routes to match the new concepts.
- **Reason for rejection** Route renames break deep-links and are out of scope; regrouping
  delivers the IA benefit without the risk.
- **Risks** None to routing (nothing changes).
- **Rev** Easy.
- **Validate later** Phase-4 confirms deep-link compatibility (the preserved list in
  NAVIGATION_MIGRATION_MAP §C).

### D-07 · HR & CEO OT/leave surfaces are oversight, not self-service (business rule)
- **Decision** For HR and CEO, `/employee-requests` (OT) and `/leave` are **read-only
  summary/history over all employees**, labelled "ภาพรวม…", not "submit a request" forms.
  CEO's OT hop-2 approvals surface as actionable rows within; HR's OT is view-only (cannot
  approve); HR's leave includes the rare `SUBMITTED` review.
- **Evidence** Owner clarification (2026-07-25); code already grants `canViewAllOvertime`/
  `canViewAllLeave` to `['hr','ceo']`; HR **cannot** approve OT (403, `OvertimeService`); CEO is
  **not** a leave reviewer (`LeaveService`); nav `show:` already keys on `canViewAll…`.
- **Alternatives** Keep the same self-service submit framing for all viewers.
- **Reason for rejection** HR/CEO don't submit their own OT/leave and their own don't need
  approval; a submit-form framing is wrong for them and hides the oversight they actually need.
- **Risks** Must not remove HR's ability to file its own leave if it happens to be a linked
  employee (kept possible, just not the primary framing); must keep CEO's OT hop-2 actionable.
- **Rev** Easy (page-framing + label by viewer; no route/authz change — the permissions exist).
- **Validate later** Phase-4 renders the oversight view for HR/CEO; confirm CEO OT approval
  still works and HR OT stays view-only (no 403-invite).

### D-08 · Two-signature close and two-hop approvals render as distinct actors/steps
- **Decision** Deal close shows account "confirm-ready" and CEO "verify-close" as two distinct
  states; OT/SM/commission show which hop this is.
- **Evidence** `CLOSE_CONFIRM_ROLES={account}` excludes CEO by design; commission/OT/SM are
  manager→CEO two-hop.
- **Alternatives** One combined "close"/"approve" button.
- **Reason for rejection** Collapsing hides the control that one person can't sign both halves.
- **Risks** None (reflects the backend).
- **Rev** Easy (action-bar rendering).
- **Validate later** Phase-4 action-bar states per role.

### D-09 · Account & HR-profile are pull roles; make their worklists strong
- **Decision** Because `notifyByRole` has no account mapping and profile-requests emit no
  notification, account and HR-profile work is worklist-driven; the landing worklist is their
  primary signal.
- **Evidence** `notifyByRole` maps only import/ceo/sales (PCIM/MD/SA); ProfileRequestService
  emits no notification; account gets no commission notifications.
- **Alternatives** Add notifications (backend change, out of scope).
- **Reason for rejection** Backend notification changes are not this effort; the worklist
  already carries the signal.
- **Risks** Overdue has no push — the worklist must make it visually loud.
- **Rev** Easy (landing emphasis).
- **Validate later** Consider a notification follow-up for account/profile as a *separate*
  backend task (recorded, not scoped here).

### D-10 · Keep the sidebar-as-drawer; don't build a separate mobile nav
- **Decision** Retain the single sidebar component that is a rail ≥721px and an off-canvas
  drawer ≤720px; order the drawer by the per-role priority.
- **Evidence** It already works (focus-trap, Escape/backdrop, auto-expand on active route);
  RESPONSIVE_AUDIT confirms mobile drawer is good.
- **Alternatives** A bottom tab bar; a separate mobile nav component.
- **Reason for rejection** Duplicating nav risks divergence; the drawer is sound. (A mobile
  bottom action bar for 1–2 self-service actions is noted as a Phase-4 *option*, not decided.)
- **Risks** The 720px breakpoint is hardcoded ×3 — reconcile to a token in Phase 3 (F-01).
- **Rev** Easy.
- **Validate later** Phase-3 breakpoint token; Phase-4 tablet band (F-01) fix.

### D-11 · Sales lifecycle deep review (Step 2.2) — CONDITIONAL PASS
- **Decision** Reviewed the full 18-stage sales vertical against code + the existing diagram
  (`docs/sales-workflow.md`); verdict **CONDITIONAL PASS to Phase 3** with 5 doc corrections
  applied to TICKET_IA (see [SALES_LIFECYCLE_REVIEW](SALES_LIFECYCLE_REVIEW.md)).
- **Evidence** 11 problem classes assessed; findings MB1-3, AO1-3, CS1-3, IR1-2, NR1, DU1, GD1,
  DA1-3, SC1-3, MS1-2, CF1-3. Corrections: work-state never keyed off `ticket.status` (MS1);
  cost/margin/factory-raw import+ceo only (CF1/CF3); per-role actions on ambiguous stages
  (AO1/SC1/SC2); disabled-with-why (DA1-3); two return paths re-quote vs re-price (MB3/IR1).
- **Alternatives** BLOCK (rejected — no impossible core path, no data leak once CF1 applied);
  clean PASS (rejected — the 5 corrections are genuine and needed before visual design).
- **Reason for rejection** The model is sound and matches the code; the issues are doc
  refinements + recorded backend limits, not architecture failures.
- **Risks** The out-of-scope backend gaps (MB1 reseller path/QC, MB2 no-supply terminal, DU1
  invoice coupling, AO3 account push) could confuse Phase-4 if forgotten — hence recorded.
- **Rev** Easy (documentation).
- **Validate later** Phase-4 must render the PCR/procurement chain with non-empty data (Phase-1
  gap) and re-verify cost/margin scoping against the **Java** service, not the mock.

### D-12 · The "system price calculation" step does not exist as an automatic action (NR1)
- **Decision** Model stage 7 as **two human steps** — import landed-cost costing → CEO margin
  decision — not a "system calculates price" step.
- **Evidence** `calculatePrices` is `@Deprecated`; the live chain is `PricingCostingService`
  (import) → `PricingDecisionService` (CEO); no auto engine.
- **Alternatives** Keep the hypothesis's "system applies pricing logic" framing.
- **Reason for rejection** It would imply an owner/trigger that doesn't exist (an action with no
  responsible role).
- **Rev** Easy. **Validate later** confirm no UI presents an automatic price step.

### D-13 · Step 2.3 independent red-team — CONDITIONAL PASS
- **Decision** An adversarial 27-scenario sweep ([IA_REDTEAM_REVIEW](IA_REDTEAM_REVIEW.md))
  found **no BLOCK-level failure** (no impossible core path, no unroutable live action, no
  confidential-data leak in the proposed IA) and surfaced **five non-blocking gaps** (G-1…G-5)
  the earlier docs had missed. Verdict **CONDITIONAL PASS to Phase 3**.
- **Evidence** Six verdict-changing claims re-checked against source: cost/margin isolation
  (`RAW_QUOTE_ROLES={import,ceo}`) TRUE; ticket `submit()` 409 TRUE; one-role-per-viewer TRUE;
  **no `@Version`** anywhere (concurrency = state guards, not locks); **no reassignment**
  (`createdById` immutable); **no delegation**. All 11 exit-gate criteria pass.
- **New gaps recorded** G-1 leaver-ownership orphaning · G-2 no approver delegation · G-3 no
  optimistic locking · G-4 partial-pricing / stale-approval / deleted-item coupling · G-5
  auto-transition failure has no recovery surface. All are **[OUT OF SCOPE — backend]** with a
  UX mitigation each; see the review's risk register (R-1…R-5).
- **Alternatives** BLOCK (rejected — nothing impossible/leaking); clean PASS (rejected — the 5
  gaps are real and belong in the record before visual design).
- **Rev** Easy (documentation). **Validate later** Phase-4 must design the *unhappy* states the
  gaps name, and re-verify confidentiality with a real-DB IT through the Java service (R-6).

---

## Gap resolutions (D-14…D-18) — each red-team gap closed at the IA level

The five red-team gaps are **resolved as architecture decisions here** — each gets a firm,
build-ready UX mitigation that lives inside Phase-2 scope (no production code), plus an
explicitly-scoped backend follow-up that is *not* required for Phase 3. This converts open
questions Q6–Q9 (now marked resolved) into decided design.

### D-14 · G-1 leaver ownership → an "ownerless deals" oversight cluster (no reassignment invented)
- **Decision** Do **not** fake a reassignment the backend can't do. Instead make *owner
  presence* a first-class worklist condition: a deal whose `createdById` resolves to an
  **inactive** employee (`active=false` / `EmployeeStatus RSG`) classifies as **Ownerless** and
  surfaces in a dedicated **"ดีลไร้เจ้าของ" (Ownerless deals)** cluster on the CEO and
  sales_manager `งานของฉัน` landing — the two roles that already have all/team deal visibility
  (`VIEWER_ROLES` + sales_manager oversight, `requireDealOwnership` includes sales_manager).
- **UX mitigation (build-ready)** Each ownerless row shows deal · last owner (badged "ลาออก") ·
  computed work-state · a role-appropriate action the backend *already* permits (manager/CEO
  `updateStage`/`reopenDeal`/`markLost`, drive the next transition as oversight). The plain-rep
  worklist is untouched (they never owned it). Detection joins owner→`employee.active`; the
  owner-active flag is already on the employee DTO the manager/CEO surfaces load (confirm the
  ticket *summary* carries it in Phase 4 — a one-field DTO addition at most, not a new endpoint).
- **Backend follow-up (scoped, not now)** A real `reassignOwner(ticketId,newRepId)` action
  (authz sales_manager/CEO, audit event, rewrites `createdById`). **[OUT OF SCOPE — backend]**,
  own branch, needs an authz IT. Resolves Q6.
- **Rev** Moderate (classifier + one landing cluster). **Validate later** Phase-4 renders the
  cluster with a resigned-owner fixture.

### D-15 · G-2 manager-absent → make stalls visible via *Overdue* ageing + a stalled-approvals oversight (no UI-invented delegation)
- **Decision** The UI does **not** invent delegation or let HR/CEO approve a hop-1 they'd 403
  on. It makes a stall **visible and chase-able**: the WORK_STATE_MODEL *Overdue* modifier (a
  UX-computed ageing signal, no backend SLA) applies to hop-1 OT/leave/special-money `SUBMITTED`
  states waiting on a manager past a threshold.
- **UX mitigation (build-ready)** (a) On the employee's routing strip the wait renders
  **Overdue** ("รออนุมัตินานกว่าปกติ") past the threshold; (b) the request appears in a
  **"คำขอค้างนาน" (stalled requests)** oversight list on HR's and CEO's *existing* all-employee
  oversight surfaces (`canViewAll*`) so a human can chase the manager — **without** granting
  hop-1 approval (HR still 403s OT; the list is visibility, not an action). Threshold is a
  Phase-3 UX constant, not a persisted SLA.
- **Backend follow-up (scoped, not now)** A real delegation / out-of-office model (deputy
  approver, or a division-manager leave-review fallback) — which would *also* resolve the
  div-manager leave-review inconsistency (Q2). **[OUT OF SCOPE — backend]**, own branch, authz IT.
  Resolves Q7.
- **Rev** Easy (ageing + an oversight list off data already fetched). **Validate later** Phase-4
  ageing threshold + the oversight list render.

### D-16 · G-3 concurrency → "already-decided" is a required state + refetch-on-focus (guard-based safety accepted)
- **Decision** With **no `@Version`** in the backend, the state-machine guard is the safety net
  (fail-safe against lost updates, returns 409/422 on the losing write). The UI carries the rest,
  so the *already-decided* handling is **mandatory**, not a nicety — promoted from PAGE_PATTERN §5
  prose to a firm contract.
- **UX mitigation (build-ready)** Every Approval-task (pattern 5) and any state-transition action
  (pattern 12): on a 409/422 "state changed / already decided", **refetch the record, render the
  new work-state, disable the now-invalid action with a reason** ("รายการนี้ถูกดำเนินการแล้วโดย
  <actor>" from the audit) — never a raw error toast. Live-approval surfaces set
  `refetchOnWindowFocus` (React Query, already a dependency) to shrink the stale-tab window
  (scenario 23).
- **Backend follow-up (scoped, not now)** Add `@Version` optimistic locking to the mutable
  aggregates (deal/PCR/costing/decision/commission) for defence-in-depth. **[OUT OF SCOPE —
  backend]**, schema + service, own branch. Resolves Q8.
- **Rev** Easy (pattern contract). **Validate later** Phase-4 forces a double-submit and asserts
  the already-decided state, not a toast.

### D-17 · G-4 editable-when → the item edit-state matrix drives enable/disable (UI mirrors, backend enforces)
- **Decision** The "what's editable when" matrix (now in TICKET_IA) is the **source of truth for
  UI enable/disable**, rendered through the existing disabled-with-why affordance; the UI never
  offers an edit the backend would (or should) reject.
- **UX mitigation (build-ready)** Each Overview-tab item line carries a computed **edit-state**:
  **Free** (no PCR yet — fully editable), **Priced/locked** (bound to an `APPROVED_FOR_QUOTATION`
  PCR — inline edit disabled; the affordance routes to *re-price* via
  `createCustomerChangeRevision`, IR1), **Document-bound** (cited by an issued quotation/deposit/
  invoice — delete disabled-with-why). "Priced vs not-yet-priced" is legible per line so an
  unpriced item (scenario 3) is clearly not quotable.
- **Backend follow-up (scoped, not now)** Enforce the matrix server-side (reject item edit/delete
  that violates it). **[OUT OF SCOPE — backend]**, repo/service + IT; the UI mirror is not the
  security boundary. Resolves Q9.
- **Rev** Moderate (item-row state on the deal). **Validate later** Phase-4 renders each edit-state.

### D-18 · G-5 auto-transition failure → axis-computed work-state is the resilience; add a non-blocking consistency advisory
- **Decision** Keep computing work-state from `salesStage`+`paymentStatus`+`fulfilmentStatus`+
  viewer (WORK_STATE_MODEL) — a stuck auto-advance still classifies from the money/fulfilment
  truth, so the record stays actionable. That is the primary mitigation; do **not** trust the
  `salesStage` flag alone.
- **UX mitigation (build-ready)** Add a low-severity, **non-blocking** advisory
  "สถานะไม่สอดคล้อง (state looks inconsistent)" when the computed work-state and persisted
  `salesStage` disagree in a missed-auto-advance shape (e.g. `FULLY_PAID`+`FULLY_DELIVERED` but
  stage ≠ `CLOSED_PAID`), shown on CEO/account oversight with a refresh/re-check affordance —
  informational, never gating an action.
- **Backend follow-up (scoped, not now)** An idempotent re-evaluation / retry of the auto-advance
  chain. **[OUT OF SCOPE — backend]**.
- **Rev** Easy (a computed advisory). **Validate later** Phase-4 renders the advisory from a
  divergent fixture.

**Status:** G-1…G-5 are **resolved at the IA level** — Phase 3 designs the mitigations above;
the five backend follow-ups are recorded, scoped, and *not* required to start Phase 3.

---

## Business-rule discrepancies found (hypothesis vs. implementation)

Full detail in [ROLE_HANDOFF_MAP §Reconciliation](ROLE_HANDOFF_MAP.md#reconciliation--the-sales-hypothesis-vs-the-implementation). Summary:

1. **Pricing is a separate PCR aggregate**, not a ticket status — ticket `submit()` now 409s.
   Sales does `createDraft`→`submit`; **multiple PCRs per deal**.
2. **Import does landed-cost costing**, not just "contact the factory." Cost (import) vs
   selling price (CEO) is a deliberate split; **margin is hidden from sales**.
3. **No auto pricing engine** in the live chain (`calculatePrices` deprecated) — costing and
   margin are human-driven.
4. **Close is two signatures** — account confirm-ready, CEO verify-close (CEO excluded from the
   first by design).
5. **Commission is account-initiated at `CLOSED_PAID`**, then **dual-approved** (manager→CEO),
   and pays **M+1** — not "auto at close."
6. **Tax invoice is uploaded, not generated** (only quotation/deposit-notice/remaining-invoice
   are system docs); the uploaded invoice doubles as the close-gate artifact.
7. **Deposit notice is sales-generated; deposit receipt is account-confirmed** — a split.
8. **HR cannot approve OT** (403) — mock may allow it (issue-#199 shape). **CEO is not a leave
   reviewer.**
9. **Account has no commission list access** — deep-link-only for the record-invoice step.
10. **`warehouse`/`qc` roles exist but render the plain-employee experience**; there is **no
    real QC step** (only a free-text `qc_note` at goods receipt).
11. **Special-money evidence upload is not implemented** (disabled placeholder); its
    notification emails deep-link the wrong page (`/requests` vs `/employee-requests`).
12. **HR/CEO OT/leave are oversight, not self-service** (D-07) — resolved as a business rule
    this phase.

---

## Phase 2 completion response

**1. Roles mapped** — 9 role literals (`ceo, sales, sales_manager, import, account, hr,
employee, warehouse, qc`) + the derived **division manager** (`employee`+manager). All in
[ROLE_JOB_MAP](ROLE_JOB_MAP.md); `warehouse`/`qc` mapped as latent (plain-employee surface,
tracked gap).

**2. Cross-role handoffs mapped** — 17 handoffs (B-01…B-17) across sales, money, procurement,
and HR in [ROLE_HANDOFF_MAP](ROLE_HANDOFF_MAP.md), each with trigger/status/manual-auto/
cancel-return/notification/waiting-view/audit, plus 5 cross-cutting observations.

**3. Navigation changes proposed** — 7 work-oriented concepts, role-scoped, worklist-led;
badges from the work-state count; per-role OT/leave oversight framing for HR/CEO. In
[INFORMATION_ARCHITECTURE](INFORMATION_ARCHITECTURE.md) + [NAVIGATION_MIGRATION_MAP](NAVIGATION_MIGRATION_MAP.md).

**4. Existing routes preserved** — **all 24** route registrations unchanged; full list in
NAVIGATION_MIGRATION_MAP §B. No path, guard, or permission changed this phase.

**5. Routes that may need migration later** — **none required.** One *recommendation* carried
to Phase 4: implement create-deal as a first-class URL (either `/tickets/new` or a draft
`/tickets/:id` editing state) — a Phase-4 choice, not a Phase-2 change. One *defect* to fix in
the owning workflow: the special-money notification deep-link (`/requests` → `/employee-requests`),
a backend copy change, not a UI edit.

**6. Highest-risk assumptions** —
- (a) The work-state classifier's "can act now" branch must match the **Java** role gates
  exactly; a loose mock-based mirror would invite 403 actions (D-02).
- (b) Import/account/sales list-scoping is enforced server-side; the IA assumes the projected
  DTOs, but Phase-1 verified authz only on mocks — F-03/F-11 remain **unverified against
  production** until the real service is exercised.
- (c) PCR detail, procurement detail, and warehouse/qc surfaces were **not rendered** in
  Phase 1 (empty/absent seed) — their IA is inferred from code, not observed.
- (d) The account close-ready/`CLOSED_PAID` server scope gap means those worklist tabs read
  empty today (`AccountFinancePage.jsx:58-63`) — a backend follow-up the landing must not mask.

**7. Business-rule discrepancies found** — the 12 above (§"Business-rule discrepancies").

**8. Open questions (do not block Phase 3)** —
- Q1. Create-deal route: new path vs. draft-detail editing state? (Phase-4 impl choice.)
- Q2. Should the division-manager **leave** review inconsistency be resolved by enabling
  division-manager leave review (authz change) or hiding the nav? (Owner + authz evidence.)
- Q3. Do account and HR-profile want push notifications (currently pull-only)? (Separate
  backend task.)
- Q4. Is a real **QC gate** before delivery wanted (today only a free-text `qc_note`)? If so,
  it's a new backend workflow, not a UI repair.
- Q5. Mobile bottom action bar for self-service — build it or not? (Phase-4 option.)
- ~~Q6 (G-1)~~ · ~~Q7 (G-2)~~ · ~~Q8 (G-3)~~ · ~~Q9 (G-4)~~ — **RESOLVED** at the IA level in
  **D-14…D-17** (G-5 in D-18). Each has a decided, build-ready UX mitigation inside Phase-2
  scope; only the backend follow-up remains, scoped and not required for Phase 3. The
  div-manager leave-review inconsistency (Q2) is folded into D-15's backend follow-up.

**9. Files created or modified** — Created in `docs/ui-repair/02-information-architecture/`:
`README.md`, `ROLE_JOB_MAP.md`, `ROLE_HANDOFF_MAP.md`, `WORK_STATE_MODEL.md`,
`INFORMATION_ARCHITECTURE.md`, `NAVIGATION_MIGRATION_MAP.md`, `ROLE_LANDING_STRATEGY.md`,
`TICKET_INFORMATION_ARCHITECTURE.md`, `CREATE_TICKET_FLOW.md`, `PAGE_PATTERN_CATALOG.md`,
`IA_DECISION_LOG.md`. **No other files touched.**

**10. Production code not modified** — Confirmed. No JSX, CSS, routes, APIs, backend services,
permission logic, statuses, or schema were changed. Phase 2 is documentation only; the ground
truth was gathered by *reading* the code (Java services authoritative per `CLAUDE.md`), and all
permission facts are source-verified, not test-verified.
