# Agent Handoff

## Task

Implement **Phase 5A — Ticket Workspace Repair**: rebuild `/tickets/:id`
(`frontend/src/features/tickets/TicketDetailPage.jsx`, 1,843 lines) from a single stacked scroll
into the Phase 2 record-detail structure — persistent header, work-state banner, role-projected
tabs, desktop context panel, sticky action bar — building only the shared primitives that
structure actually consumes.

**The plan is the contract. Read it first and follow it:**
[`docs/ui-repair/05-ticket-workspace/PHASE_5A_PLAN.md`](../ui-repair/05-ticket-workspace/PHASE_5A_PLAN.md)

It holds the full current-structure map, query/mutation/action inventories, the role/action and
section-visibility matrices, the tab mapping, the work-state derivation rules, the test and
screenshot matrices, and the 14 implementation checkpoints. This handoff does not repeat them.

## Branch

`refactor/ui-phase-5-ticket-workspace` — **create it from `origin/main`.**

Do **not** branch from `refactor/ui-phase-4-ticket-worklist`. Phase 4A is already merged into
`origin/main` as the squashed commit `c345e1c refactor(ui): repair ticket worklist and shared
table interactions (#319)`; `git diff --stat origin/main refactor/ui-phase-4-ticket-worklist --
frontend/src` is empty. Re-fetch and rebase onto the latest `origin/main` before opening the PR
(standing repo rule).

One implementation agent per branch. Do not let a second agent edit it concurrently.

## Base Commit

`c345e1c` (`origin/main` at handoff time)

## Current Commit

Not started — this handoff is written ahead of implementation.

## Agent / Model Used

Planning + inventory: Claude Opus 5 (this session).
Implementation: **Codex** (recommended).
Review: Claude Opus, independently, before merge.

## Scope

### In Scope

**A. Ticket workspace shell** — nine required regions: breadcrumb/up-nav, persistent deal header,
stage + work-state summary, blocker/returned-work message, role-projected tabs, active-tab
content, desktop context panel, sticky next-action bar, and the loading / not-found / error-retry
states.

**B. Shared primitives — only what this slice consumes.** New:
`components/common/Tabs.jsx`, `InlineAlert.jsx`, `DescriptionList.jsx`, `StickyActionBar.jsx`,
`Timeline.jsx`, and `features/tickets/WorkStateBanner.jsx`. Modified:
`components/common/Modal.jsx` (the A-05 labelling / describedby / portal / inert fix, applied
app-wide to all 23 consumers).
**Do not build Drawer, FilterBar, ApprovalTask or WorklistRow** — no consumer in this slice.

**C. Role-projected tabs**, in this exact order:
`ภาพรวม · ราคา · ใบเสนอราคา · การเงิน · จัดซื้อและส่งมอบ · เอกสาร · กิจกรรม`.
A role with no data access gets **no tab**. Tab visibility mirrors
`features/tickets/salesViewScope.js` and is **presentation projection, not the security
boundary** — the backend still enforces per endpoint.

**D. Responsive** — the deal must be actionable at 390×844, 768×1024, 1024×768, 1366×768 and
1440×900. Use the existing `mobile:` / `tablet:` variants (`frontend/src/index.css:16-25`); do
not invent a breakpoint. The one-off `@media (max-width: 900px)` at `styles.css:1720` goes away
with `.ticket-detail-grid` and is **not** replaced.

**E. Two pre-existing defects, explicitly authorised by the owner:**
1. `TicketDetailPage.jsx:383` and `:396` call `queryKeys.ticket(ticketId)`. `queryKeys.js` has
   no `ticket` key (only `ticketDetail`), so `undefined(…)` **throws a TypeError inside
   `onSuccess`** — the attachment upload/delete success toast never fires and `ticketDetail` is
   never invalidated. Fix to `queryKeys.ticketDetail(ticketId)` and add a regression test.
2. A-05 on the shared `Modal.jsx` (see B).

**F. Documentation** — append a contract addendum to
`docs/ui-repair/03-design-foundation/COMPONENT_CONTRACTS.md` (**new §22 Tabs, §23 Work-state
banner**). Phase 3 has no contract for either; they exist only as Phase 2 IA structure, so they
must be authored, not cited.

### Out of Scope

`/tickets/new`, draft persistence, create-ticket redesign, `TicketCreateModal` (**Phase 6**) ·
any new backend work-state field, endpoint, permission, status or migration · pricing /
quotation / payment / deposit / fulfilment / commission **calculation** changes · fulfilment
business rules · status-machine transitions · navigation architecture migration · full
`styles.css` cleanup · whole-app Tailwind migration · the 122 `max-[720px]` literals outside
touched files · dark mode · any new component framework, animation library, font or icon library
· `ChangePasswordModal` consolidation (F-19) · `EmptyState` CTA slot · `SET_ENTRY_CHANNEL`
(offered by the server, consumed nowhere).

### Behaviour That Must Be Preserved

Verbatim from the phase scope, and each is covered by a test in the plan's Test Matrix:

- The existing `/tickets/:id` route and `TicketDetailPage`'s `{ user, ticketId, onBack, showToast }` props.
- Browser back behaviour. `onBack` stays `navigate(-1)` (`App.jsx:59-73`) so the list's `q`,
  `phase`, `life`, `flag`, `inbox` filters survive the round trip. **Tab changes use
  `setSearchParams(next, { replace: true })`** so they never push history.
- All six ticket query keys, verbatim — including `placeholderData: (prev) => prev` on
  `ticketActions`, which is load-bearing (without it the action surface blanks on every mutation).
- The action query and the server's `availableActions`. **Never replace server-provided action
  availability with a looser frontend approximation.**
- `applyTicketUpdate()` (`:323-331`) and every existing invalidation set, on this page and in the
  four child panels.
- Existing ownership checks (`isOwner`), role visibility, and the three `can.*` flags that are
  status/role-derived with no `hasAction` check (`revise`, `comment`,
  `downloadRemainingInvoice`) — preserved as-is, neither tightened nor loosened.
- The pricing-request, quotation, payment, deposit, fulfilment and tracking workflows;
  attachments; the three-party close-confirmation sequence; cancellation and revision; document
  generation/download.
- Mutation success and error behaviour, **except** the documented `queryKeys.ticket` defect above.

## Files Changed

To be filled in during implementation. Expected shape:

**Production source**
- `frontend/src/components/common/Tabs.jsx` (new)
- `frontend/src/components/common/InlineAlert.jsx` (new)
- `frontend/src/components/common/DescriptionList.jsx` (new)
- `frontend/src/components/common/StickyActionBar.jsx` (new)
- `frontend/src/components/common/Timeline.jsx` (new)
- `frontend/src/components/common/Modal.jsx` (A-05)
- `frontend/src/features/tickets/dealWorkState.js` (new)
- `frontend/src/features/tickets/WorkStateBanner.jsx` (new)
- `frontend/src/features/tickets/tabs/{Overview,Pricing,Quotations,Money,Fulfilment,Documents,Activity}Tab.jsx` (new)
- `frontend/src/features/tickets/TicketDetailPage.jsx` (decomposed)
- `frontend/src/features/tickets/DealStateHeader.jsx` (banner extracted out)
- `frontend/src/styles.css` (labelled `/* Phase 5A */` blocks only)

**Tests** — one per new primitive, plus `dealWorkState.test.js`, `Modal.test.jsx`, the re-scoped
`TicketDetailPage.test.jsx`, focused `tabs/*.test.jsx`, and `frontend/e2e/phase5a-acceptance.spec.js`.

**Docs and evidence** — `docs/ui-repair/05-ticket-workspace/PHASE_5A_{PLAN,IMPLEMENTATION,QA_INVENTORY,QA_RESULTS,QA_MATRIX,VISUAL_QA_RESULTS}.md`,
the `COMPONENT_CONTRACTS.md` addendum, and
`docs/ui-repair/evidence/proposed/phase-5a-ticket-workspace/` (LFS).

## Commands Run

```bash
cd frontend
npm run lint
npm test
npm run build
npm run test:e2e
CAPTURE_EVIDENCE=1 npm run test:e2e   # evidence capture only
git diff --check
```

Re-record the baseline **before** touching anything — do not inherit Phase 4A's figures.

## Test / Build Results

To be filled in. Phase 4A's closing numbers, for comparison only: lint pass with 1 pre-existing
warning (`frontend/src/features/payroll/PayrollPage.jsx:312:6`, `react-hooks/exhaustive-deps`);
`npm test` 66 files / 612 tests; build clean; `test:e2e` 64 specs.

- Frontend lint: _pending_
- Frontend unit (Vitest): _pending_
- Frontend build: _pending_
- Playwright e2e: _pending_
- Backend: **not run — no backend change in this phase.**

## Authz Evidence

**UNVERIFIED — mock-only (`VITE_USE_MOCKS=true`). Permission behaviour is NOT confirmed.**

This is acceptable here **only because this phase changes no authorization.** No role gate, no
scope, no filter, no `ROLE_PERMISSIONS` entry, no route guard, and no backend permission is
modified. Tab visibility is presentation projection mirroring
`frontend/src/features/tickets/salesViewScope.js`, which that file itself documents as never a
security boundary.

Planning addendum: Step 2 of the plan now contains the evidence-backed role/action inventory for
`sales`, `sales_manager`, `import`, `account`, and `ceo`, cross-checked against the frontend
route guard, `visibleSections`, server `availableActions`, Java service gates, and existing authz
tests. It records current behaviour only; it is **not** a new permission test run and must not be
reported as proving authz under the mock server.

Do **not** describe role scoping as tested, and do not let "I clicked through it as import"
stand in for an authz claim. If implementation turns out to require a real permission change,
**stop** — that is no longer a UI-repair task; split it out and follow the repo's authz-evidence
rules (real-DB integration test through the real Java service).

## Decisions Made

Confirmed with the owner before implementation:

1. **Tab state = `?tab=<id>` with `replace: true`.** Deep-linkable and consistent with the
   existing `?tab=` convention in `features/requests/RequestsPage.jsx:42-60`, while browser-back
   still returns to `/tickets` with its filters. An absent/unknown/not-permitted value falls back
   to `overview` — never a blank panel, never a redirect.
2. **Sticky action bar = page-level primary only.** It renders the existing four-way
   `primaryAction` cascade plus the secondary overflow (`revise`, `editItems`,
   `revokeCloseConfirm`, `cancel`). `DealQuotationPanel`, `DealDepositPanel`,
   `DealFulfilmentPanel` and `PricingRequestPanel` **keep their own in-panel primaries** — when
   the viewer's next move lives in one of them, the bar shows a "ไปที่แท็บ …" jump, not a
   duplicated mutation button. A deliberate narrowing of the Phase 2 IA ideal, taken to leave
   four preserved workflows untouched.
3. **Both pre-existing defects are fixed in this phase** — the `queryKeys.ticket` TypeError, and
   A-05 on the shared `Modal.jsx` (app-wide, not ticket-only).
4. **`SectionPeek` retires for tabbed sections.** No access ⇒ no tab (Phase 2: "must not render a
   tab whose data the backend would 403"). The deal-level context the peek carried
   (customer · current stage) already lives in the persistent header, which every viewer sees.
5. **Phase 5 docs live in `docs/ui-repair/05-ticket-workspace/`**, not
   `04-production-repair/`, by explicit instruction. Deliberate, not drift.

## Assumptions

- The mock (`VITE_USE_MOCKS=true`) returns `availableActions` shapes faithful to
  `TicketService.actions()` (`backend/.../ticket/TicketService.java:1646-1676`). Shapes are
  contract-tested (`frontend/src/api/contract.test.js`); **authz is not.**
- The four child panels are self-contained enough to relocate into tabs without touching their
  internals. Verify by moving them **verbatim first** (checkpoint 6) and confirming the suite
  stays green before any restyling.
- `salesViewScope.js`'s section ids remain the right projection source; the tab gates are
  composed from them rather than a new parallel notion.

## Known Risks

1. **Tab-gated query mounting is a real behaviour change.** A panel behind an inactive tab does
   not mount, so `depositNotices` / `ticketDeliveries` / `customerQuotations` load later than
   today. Intended (a CEO no longer fires ~13 queries on open), but it changes when
   `availableActions`-dependent buttons appear. Test it, and never let a hidden tab suppress an
   action the sticky bar should show.
2. **34 existing `TicketDetailPage.test.jsx` tests will break on structure, not behaviour.**
   Re-scope each to open the owning tab first. **Do not delete or weaken an assertion to make it
   pass** — if one cannot be preserved, stop and report.
3. **`Modal.jsx` has 23 consumers** and portalling changes where the DOM lands. Land the A-05 fix
   early (checkpoint 3) and run the full suite before stacking anything on it.
4. **The work-state classifier can invite a 403.** Every `needs_my_action` branch must be backed
   by an `availableActions` entry. Write the tests **wrong-way-round** — assert a viewer is *not*
   told to act — per the repo's standing testing rule.
5. **`styles.css` is `layer(legacy)`** and loses to Tailwind utilities on the same property.
   Measure computed styles before believing a CSS finding; do not reach for `!important`.
6. **Page-scoped class names must not be shared** — Phase 4A's `.ticket-table` →
   `.ticket-worklist-table` incident. Prefix anything new `ticket-workspace-`.
7. **Evidence PNGs are LFS-tracked** (`.gitattributes`). Do not commit multi-MB blobs outside LFS.
8. `frontend/e2e/helpers/auth.js`: the mock `db` is module state — **never `page.goto()` after
   the initial load**, use `spaGoto`. And always log in at desktop width before resizing to
   mobile; the logout control is inside the collapsed drawer below 720px.

## Things Not Finished

Everything — this handoff precedes implementation. Carried-forward items that are **explicitly
not** Phase 5's job, recorded so they are not chased:

- `frontend/.claude/rules/frontend-ui.md` is referenced as the frontend design charter by both
  `docs/ui-repair/00-governance/UI_REPAIR_RULES.md` and root `AGENTS.md`, and **does not exist in
  this tree.** A dead cross-reference. Record it; do not fix it under this ticket.
- The dead legacy CSS `.tabs` / `.status-tabs` (`styles.css:1906-1929`, `:1650-1673`) has no
  consumer today. The `Tabs` primitive either adopts it or deletes it — it must not be left
  dangling.
- `SET_ENTRY_CHANNEL` is offered by the server and consumed nowhere in the frontend.
- F-19 (`ChangePasswordModal` hand-rolled backdrop) and the `EmptyState` CTA slot remain open.

## Recommended Next Agent

**Codex** for implementation, working through the plan's 14 checkpoints in order. Then **Claude
Opus** for an independent review that re-verifies every claim — reads the diff, checks it against
the plan, runs the tests, and hunts for regressions in the four preserved workflows. Merge only
on the owner's explicit say-so.

## Exact Next Prompt

```
Implement Phase 5A — Ticket Workspace Repair.

Read first, in this order:
  1. CLAUDE.md and AGENTS.md (repo rules; note there is no `typecheck` script)
  2. docs/agent-handoffs/00_MASTER_CONTEXT.md
  3. docs/agent-handoffs/115_refactor-ui-phase-5-ticket-workspace.md  (this handoff)
  4. docs/ui-repair/05-ticket-workspace/PHASE_5A_PLAN.md              (the contract)
  5. docs/ui-repair/00-governance/UI_REPAIR_RULES.md and CHANGE_CONTROL.md
  6. docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
     and WORK_STATE_MODEL.md
  7. docs/ui-repair/03-design-foundation/COMPONENT_CONTRACTS.md and TOKENS.md

Then:
  - git status; create `refactor/ui-phase-5-ticket-workspace` from the latest origin/main
    (NOT from refactor/ui-phase-4-ticket-worklist — 4A is already merged as c345e1c).
  - Re-record the lint/test/build/e2e baseline before changing anything.
  - Work the plan's 14 Implementation Checkpoints in order. Do not reorder them; checkpoint 3
    (Modal A-05) and checkpoint 6 (verbatim tab extraction) exist specifically to isolate risk.

Hard constraints:
  - Server `availableActions` stays authoritative. Never widen it with a frontend approximation,
    and never let the work-state classifier report "needs my action" for an action the server
    did not offer.
  - Preserve every query key, `applyTicketUpdate`, every invalidation set, `onBack = navigate(-1)`,
    and all existing ownership/role checks.
  - Tab changes use setSearchParams(..., { replace: true }).
  - No new backend field, endpoint, permission, status, migration, or calculation change.
  - No new page-specific CSS file; no new breakpoint; no new UI library.
  - All 34 existing TicketDetailPage tests must pass, re-scoped to open the owning tab first.
    Do not delete or weaken an assertion to make one pass.

Finish with:
  - cd frontend && npm run lint && npm test && npm run build && npm run test:e2e
  - CAPTURE_EVIDENCE=1 npm run test:e2e for the 5 roles x 5 viewports evidence set
  - PHASE_5A_IMPLEMENTATION.md + the QA inventory/results/matrix docs + the Change Control
    checklist, and the COMPONENT_CONTRACTS.md addendum (§22 Tabs, §23 Work-state banner)
  - Report the permission aspect as UNVERIFIED — mock only. Do not describe role scoping as tested.

Stop and report instead of silently fixing any unrelated failure. Do not commit or push
unless asked.
```
