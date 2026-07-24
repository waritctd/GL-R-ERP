# Phase 2 IA — Independent Red-Team (Step 2.3)

A fresh, adversarial pass over the Phase-2 information architecture. The working assumption
was that the proposal is **overly neat, incomplete, or built on incorrect assumptions**, and
the job was to break it against 27 stress scenarios rather than confirm it.

**No production code modified.** Backend problems are labelled and *not* solved here (per the
brief). Where a scenario exposed a genuine gap, it is recorded in the relevant Phase-2 doc so
it survives into Phase 4 — the docs' own method is to record gaps, not smooth them, and this
review extends that record.

## Method & code-ground-truth spot-checks

The 12 Phase-2 docs are unusually well cited (`file:line` throughout). Rather than re-verify
every citation, the red-team re-checked the **verdict-changing** claims directly against the
Java/JS source — the ones that would force a BLOCK if false:

| Claim under test | Result | Evidence |
|---|---|---|
| Cost/margin/factory-raw is import+ceo only (no leak to sales) — CF1/CF3 | **TRUE** | `FactoryQuoteService.RAW_QUOTE_ROLES = Set.of("import","ceo")` + `requireRole(...)` at :132/:138/:361; `CustomerQuotationService` builds from a stripped `salesView` DTO |
| Ticket-level `submit()` is dead (H4) | **TRUE** | `TicketService.submit` `@Deprecated`, "always 409s from `draft`" (:193-211) |
| A viewer has exactly one derived role (per-viewer work-state premise) | **TRUE** | `roleForDivision()` returns a single role (position rule → division rule → `'employee'`), `roles.js:22` |
| Optimistic-concurrency control exists (scenarios 22/23) | **FALSE** | `@Version` appears **0 times** in `backend/src/main/java` — safety rests on state-machine guards, not locks |
| Ownership can be reassigned when a rep leaves (scenario 13) | **FALSE** | no `reassign`/`transferOwner`/`changeOwner`; `createdById` is the immutable owner. Employee table *does* carry a Resigned status (`EmployeeStatus "RSG"`) |
| Approver delegation / out-of-office exists (scenario 14) | **FALSE** | no `delegate`/`deputy`/`out-of-office` in the services |

These six checks anchor the scenario analysis below.

---

## Scenario-by-scenario

Legend — **Handled** / **Partial** / **Gap**. *Blocks P3?* is the only gate that matters for
this step; *Type* = UX-only vs needs-future-backend.

| # | Scenario | Verdict | What's missing | Doc to update | Blocks P3? | Type |
|---|----------|---------|----------------|---------------|-----------|------|
| 1 | Draft w/ incomplete product info | **Handled** | — (partial specs allowed; catalog snapshot mandatory only at PCR-submit) | — | No | UX |
| 2 | One deal, products across different factories | **Partial** | Multiple PCRs are modelled; multiple factories *within one costing* not made explicit | SALES_LIFECYCLE / TICKET_IA | No | UX + note |
| 3 | Only some items need a pricing request | **Gap** | No stated model for a deal where some `ticket_item`s never enter a PCR; quotation builds only from an approved PCR sales-view | TICKET_IA + IA_DECISION_LOG (Q) | No | Backend + UX |
| 4 | Import gets partial factory pricing | **Handled** | Costing `submit` requires every item priced → blocker-with-why; MB2 covers all-`NOT_AVAILABLE` | — | No | UX |
| 5 | Factory pricing changes after CEO review | **Handled** | Costing immutable once submitted → revision / `returnToImport`; re-price path (IR1) | — | No | UX + backend-existing |
| 6 | CEO rejects the calculated price | **Handled** | `returnToImport`→`COSTING_REVISION_REQUIRED`→work-state *Returned* | — | No | UX |
| 7 | Sales edits items after approval | **Gap** | IR1 covers re-*price*; the **item-edit-invalidates-approval** guard is not specified (approved sales-view goes stale) | TICKET_IA | No | Backend + UX |
| 8 | Quotation generation fails | **Partial** | Generic pattern-12 recovery covers it; no *generated-doc* failure state called out | PAGE_PATTERN_CATALOG §9 | No | UX |
| 9 | Customer requests a revised quotation | **Handled** | `createRevision` (same price) vs `createCustomerChangeRevision` (re-price); REVISION_REQUESTED | — | No | UX |
| 10 | Deposit partial or mis-recorded | **Partial** | Revision + adjustment payments exist; **reversal/correction of a wrongly-confirmed receipt** not modelled | SALES_LIFECYCLE (stage 11/14) | No | Backend + UX |
| 11 | Procurement starts before every item ready | **Partial** | IR gated on deposit; per-item partial procurement under `PROCUREMENT` (SC1) not decomposed | TICKET_IA (Fulfilment) | No | Backend |
| 12 | A document is missing | **Handled** | DA3 (close-ready shows *which* prerequisite missing); blocker work-state; pattern 9 | — | No | UX |
| 13 | User leaves owning active records | **Gap** | **No ownership reassignment** — a leaver's deals/PCRs orphan from every worklist except CEO/mgr oversight | ROLE_JOB_MAP + IA_DECISION_LOG (Q) | No | Backend |
| 14 | Manager absent | **Gap** | **No delegation/escalation** — OT/leave hop-1 stalls; CEO is hop-2, not a hop-1 substitute | ROLE_HANDOFF_MAP + IA_DECISION_LOG (Q) | No | Backend |
| 15 | Role lacks permission for a deep link | **Handled** | Explicit fix: calm "ไม่มีสิทธิ์เข้าถึง" (F-03), don't render a 403 tab | — | No | UX |
| 16 | Ticket cancelled then resumed | **Partial** | Reopen modelled from `CLOSED_LOST`; **`CANCELLED` reopen asymmetry** not spelled out | TICKET_IA (returned/terminal) | No | UX + note |
| 17 | One user spanning >1 role | **Gap** | Model derives exactly one role/viewer; a genuine dual-hat user is unrepresentable (except employee+manager) | ROLE_JOB_MAP | No | Backend |
| 18 | Mobile loses connectivity mid-entry | **Partial** | Create-flow preserves input on failure; no offline/local-autosave spec (arguably out of scope) | CREATE_TICKET_FLOW | No | UX |
| 19 | Extremely long Thai names/descriptions | **Partial** | F-12 clipping acknowledged; truncation/overflow rules are a Phase-3 visual deliverable, not yet specified | (Phase 3 owns) | No | UX (Phase 3) |
| 20 | Record waiting but no SLA | **Handled** | Explicitly acknowledged ("no ageing except quotation expiry") → *Overdue* work-state as a computed UX signal, no schema change | — | No | UX |
| 21 | Automatic status transition fails | **Partial** | Work-state computed from stage+payment+fulfilment axes is resilient, but a stuck auto-advance isn't surfaced as a recoverable state | WORK_STATE_MODEL | No | Backend + UX |
| 22 | Two users act on the same approval | **Partial** | Pattern-5 "already-decided → show new state, disable" is right; **no `@Version`** so safety = state guards returning 409/422, which the UX must handle gracefully | PAGE_PATTERN_CATALOG §5 | No | UX + backend-robustness |
| 23 | Outdated browser tab | **Partial** | Same staleness class; no explicit refetch-on-focus / stale-banner spec (React Query could) | PAGE_PATTERN_CATALOG §5/§12 | No | UX |
| 24 | Item removed after downstream docs exist | **Gap** | Referential integrity of a deleted `ticket_item` cited by an issued quotation/deposit/invoice not addressed | TICKET_IA + SALES_LIFECYCLE | No | Backend + UX |
| 25 | Finance data not visible to Sales | **Handled** | Cost/margin hidden from sales ✓. **Nuance:** sales-*owner* DOES see their own deal's payment/deposit ledger by design (they issue the deposit notice) — that is intended, not a leak | TICKET_IA (already states) | No | — |
| 26 | Factory costs not visible to unauthorised roles | **Handled** | Verified in code (`RAW_QUOTE_ROLES` import+ceo; sales_manager = price only) | — | No | — |
| 27 | Salary not visible outside permitted roles | **Handled** | **More restrictive than the scenario:** salary is **HR-only** (`PRIVILEGED_EMPLOYEE_ROLES={hr}`) — *not* finance/account, not CEO-unless-HR. Ticket never shows PII | ROLE_JOB_MAP (already states) | No | — |

**Net:** 8 Handled, 12 Partial, 5 Gap, 0 Block. Every Gap is a **backend limitation or a
Phase-4 implementation edge**, not a defect in the Phase-2 architecture's structure. No
scenario produced an impossible core path, an unroutable action in the live chain, or a
confidential-data leak in the proposed IA.

---

## The five genuine gaps (newly surfaced — now RESOLVED at the IA level)

These are the red-team's material additions. None was in the docs' existing gap lists; all are
now **resolved as Phase-2 architecture decisions D-14…D-18** — each with a build-ready UX
mitigation inside doc-only scope, plus an explicitly-scoped backend follow-up that is *not*
required for Phase 3. The gap descriptions below are retained for context; the **Resolution**
line on each states the decision (full text in [IA_DECISION_LOG](IA_DECISION_LOG.md) D-14…D-18).

1. **G-1 · No ownership reassignment for a leaver (scenario 13).** `createdById` is the
   immutable deal owner and the key the whole "งานของฉัน" worklist is built on. When a rep
   resigns (the employee record even flips to `RSG`), their in-flight deals and PCRs vanish
   from every worklist except CEO/manager oversight — no one is prompted to pick them up. The
   IA's owner-centric worklist model has no answer for a departed owner. **[OUT OF SCOPE —
   backend]** to add a reassignment action; the IA must at minimum not assume the owner is
   always present, and a manager/CEO "orphaned deals" view is the UX mitigation.
   → **Resolution (D-14):** owner-inactive (`active=false`/`RSG`) → **Ownerless** work-state in a
   "ดีลไร้เจ้าของ" cluster on the CEO/sales_manager landing; no faked reassignment. Backend
   `reassignOwner` is the scoped follow-up.

2. **G-2 · No approver delegation / out-of-office (scenario 14).** OT and leave hop-1 require
   the *direct or division* manager (`managesEmployee`); CEO is hop-2 and cannot stand in for
   hop-1. An absent manager stalls their whole team's OT/leave/special-money with no escalation
   path. Commission/pricing route to CEO (a single well-staffed approver) so they are less
   exposed. **[OUT OF SCOPE — backend]**; the IA should surface "waiting on <manager>" ageing
   (the *Overdue* work-state) so a stall is at least visible.
   → **Resolution (D-15):** *Overdue* ageing on a manager-waiting `SUBMITTED` past a threshold +
   a "คำขอค้างนาน" oversight list on HR/CEO's existing `canViewAll*` surfaces (visibility to
   chase, not a hop-1 approval power). Backend delegation is the scoped follow-up (also fixes Q2).

3. **G-3 · No optimistic-concurrency control (scenarios 22/23).** There is **no `@Version`**
   anywhere in the backend. Two approvers (or one approver + a stale tab) hitting the same
   transition are caught only by the state-machine guard on the second write (a 409/422), not
   by a version token — so it is fail-safe against lost updates, but the UX carries the whole
   burden. Pattern-5's "already-decided → show new state, disable" is the correct contract; it
   must be treated as a **required** state, not a nicety, and the app should refetch on
   focus/visibility to shrink the stale-tab window. Hardening the backend with `@Version` is
   **[OUT OF SCOPE — backend]**.
   → **Resolution (D-16):** *already-decided* is a **required** contract — 409/422 → refetch,
   render new work-state, disable stale action with a reason (never a raw toast); live-approval
   surfaces set `refetchOnWindowFocus`. Backend `@Version` is the scoped follow-up.

4. **G-4 · Partial-pricing / stale-approval item coupling (scenarios 3, 7, 24).** Three
   scenarios converge on one weak seam: the relationship between free-form `ticket_item`s and
   the PCR/approved-price/document chain is under-specified. A deal may hold items that never
   enter a PCR (3); editing items after CEO approval silently staleness the approved sales-view
   (7); deleting an item cited by an issued document breaks referential integrity (24). The IA
   models the *happy* item→PCR→quote→doc path well; it does not state the **guards** (which
   edits are locked once a PCR is approved / a document issued). Enforcement is
   **[OUT OF SCOPE — backend]**; the IA owes a "what's editable when" note on the Overview/
   Pricing tabs.
   → **Resolution (D-17):** the "what's editable when" matrix (now in TICKET_IA) drives UI
   enable/disable via disabled-with-why; each item line carries a computed edit-state
   **Free / Priced-locked / Document-bound**, and unpriced items read as not-quotable. Server-side
   enforcement is the scoped follow-up.

5. **G-5 · Auto-transition failure not a surfaced state (scenario 21).** The lifecycle leans on
   auto-advances (`maybeAdvanceClosedPaid`, stage bumps on issue/confirm/goods-received). The
   work-state model is resilient because it computes from the *axes* rather than one flag — a
   real strength — but a genuinely stuck auto-advance has no "something didn't fire, here's how
   to recover" surface. Low probability; recorded so Phase 4 doesn't assume auto-advances are
   infallible. **[OUT OF SCOPE — backend]** to add retries.
   → **Resolution (D-18):** axis-computed work-state is the resilience (keep it) + a non-blocking
   "สถานะไม่สอดคล้อง" advisory when computed work-state and `salesStage` disagree in a
   missed-auto-advance shape, on CEO/account oversight — informational, never gating. Backend
   retry/re-eval is the scoped follow-up.

---

## Where the architecture is genuinely strong (survived the red-team)

- **The `ticket.status`-is-not-the-work-state rule (MS1/CS2)** held up under every money/
  fulfilment scenario — computing from `salesStage`+`paymentStatus`+`fulfilmentStatus`+viewer
  is what makes scenarios 21–23 tolerable instead of catastrophic.
- **Per-viewer work-state** (never `record` alone) is the right call and is code-grounded
  (single role per viewer).
- **Confidentiality (25/26/27)** is not just asserted — it is enforced in the services the red-
  team read, and the proposed IA respects the boundary (cost/margin sub-sections import+ceo;
  salary HR-only; sales-owner-sees-own-payments is intended).
- **Disabled-with-why + empty-states-route-onward + two-signature-as-two-actors** directly
  answer scenarios 12, 15, and the close flow.

---

## Risk register

| ID | Risk | Likelihood | Impact | Blocks P3? | IA resolution → residual (backend follow-up) |
|----|------|-----------|--------|-----------|---------------------|
| R-1 (G-1) | Leaver's deals orphan from all worklists | Medium | High (deals stall invisibly) | No | **Resolved D-14** (Ownerless cluster) → residual: backend `reassignOwner` |
| R-2 (G-2) | Absent manager stalls team OT/leave, no escalation | Medium | Medium | No | **Resolved D-15** (*Overdue* ageing + stalled-approvals oversight) → residual: backend delegation |
| R-3 (G-3) | Concurrent/stale approval relies solely on state guards | Medium | Low–Med (fail-safe, not lost-update) | No | **Resolved D-16** (already-decided required + refetch-on-focus) → residual: backend `@Version` |
| R-4 (G-4) | Item edits/removal desync PCR/approval/documents | Med–High | Medium | No | **Resolved D-17** (edit-state matrix drives UI) → residual: backend edit-lock enforcement |
| R-5 (G-5) | A stuck auto-transition has no recovery surface | Low | Medium | No | **Resolved D-18** (axis-computed + consistency advisory) → residual: backend retry |
| R-6 | Confidentiality is **source-verified, not test-verified** (CLAUDE.md) — Phase-4 must prove cost/margin & salary scoping with a real-DB IT through the Java service, never the mock | — | Critical if it regresses | No | Phase-4 gate (already flagged D-11/§6b) |
| R-7 | PCR/procurement/warehouse-QC surfaces were never rendered with data in Phase 1 — their IA is inferred | Medium | Medium | No | Phase-4 must seed & render (already flagged §6c) |
| R-8 | Account close-ready/`CLOSED_PAID` tabs read empty due to a server scope gap | High (today) | Medium | No | Backend follow-up; empty-state must not read "done" (already flagged §6d) |

R-6…R-8 restate risks the docs already carry; R-1…R-5 are this review's additions, now
**resolved at the IA level** (D-14…D-18) — only the scoped backend follow-up in each "residual"
cell remains, and none of those is required for Phase 3.

---

## Exit-gate check (Phase 2)

| Gate criterion | Met? | Where |
|---|---|---|
| Every role has a job map | ✅ | ROLE_JOB_MAP (9 literals + derived division-manager; warehouse/qc latent) |
| Cross-role handoffs have explicit ownership | ✅ | ROLE_HANDOFF_MAP (B-01…B-17) — sender→receiver on each |
| Backend status vs UX work-state separated | ✅ | WORK_STATE_MODEL (5 layers; `workState(record,viewer)`) |
| Navigation is task-oriented | ✅ | INFORMATION_ARCHITECTURE (7 work concepts, worklist-led) |
| Ticket structure defined | ✅ | TICKET_INFORMATION_ARCHITECTURE (18 regions) |
| Create-ticket branches specified | ✅ | CREATE_TICKET_FLOW (18 cases + container decision) |
| Manual vs automatic actions documented | ✅ | ROLE_HANDOFF_MAP + SALES_LIFECYCLE table (Manual/Auto per step) |
| Uploaders & generated documents identified | ✅ | TICKET_IA region 15; PAGE_PATTERN §9; DU1/GD1 |
| Mobile transformation specified | ✅ | Every doc's Mobile section + Mobile-IA |
| Production code unchanged | ✅ | `git status` = docs only; verified no JSX/CSS/route/authz/schema change |
| Red-team finds no blocking architecture gap | ✅ | This review — 0 blocking; 5 non-blocking gaps, all resolved at IA level (D-14…D-18) |

All eleven gates pass.

---

## Verdict

**CONDITIONAL PASS — Phase 3 may begin.**

The Phase-2 architecture is sound, code-grounded, and survived an adversarial 27-scenario
sweep with no BLOCK-level failure: no impossible core path, no unroutable live action, and —
verified against the Java services, not the mock — no confidential-data leak in the proposed
IA. It is *not* "overly neat": the docs already record most of the messy edges honestly, and
this review adds the five it missed.

**Update:** the five gaps are now **resolved at the IA level** (D-14…D-18) — each has a decided,
build-ready UX mitigation inside Phase-2 scope, and the "what's editable when" / concurrency-state
notes are folded into TICKET_IA and PAGE_PATTERN §5. The verdict stays **CONDITIONAL PASS**
because two conditions remain, and both are Phase-4 gates rather than Phase-2 work:

1. Phase 4 must **build the D-14…D-18 mitigations** (Ownerless cluster, stalled-approvals
   oversight + Overdue ageing, mandatory already-decided + refetch-on-focus, item edit-state
   matrix, consistency advisory) — the design foundation must design these *unhappy* states, not
   just the happy path.
2. Honour the pre-existing R-6…R-8 gates — above all, **re-verify cost/margin and salary scoping
   with a real-DB integration test through the Java service** before any Phase-4 surface acts on
   them (CLAUDE.md; the confidentiality claims here are source-verified only).

The five **backend follow-ups** (reassignment, delegation, `@Version`, edit-lock enforcement,
auto-advance retry) are scoped and recorded, each on its own future branch — none is required to
start Phase 3.

These are the same class of condition as the docs' own Step-2.2 verdict (D-11): documentation
completeness, not architecture failure.
