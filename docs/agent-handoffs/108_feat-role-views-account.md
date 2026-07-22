# Agent Handoff

## Task
Implement the ACCOUNT (บัญชี/การเงิน) role-scoped "money" view: a landing
`AccountOverview` (money pulse + "สิ่งที่ต้องทำ" worklist) plus a `/finance`
deep workspace (`AccountFinancePage`), the `canViewDealPipeline` permission
split (account excluded from the deal-pipeline browser), and the corresponding
nav/route wiring. Frontend-only, per the approved plan
(`~/.claude/plans/valiant-watching-map.md`, "Per-role target views → 2.
Account"). Mirrors the mechanics `docs/agent-handoffs/100_feat-sales-role-scoped-views.md`
and this program's Import branch (`feat/role-views-import`, built in parallel
on its own worktree/branch, not present in this one).

## Branch
`feat/role-views-account`

## Base Commit
`20a568385b1a293d1447a480ecd9e0a33e3cb836` (origin/main, "Merge pull request
#281 from waritctd/feat/payroll-special-pay-carryforward")

## Current Commit
Not committed — changes left in the worktree for review per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `routes.js`: new `canViewDealPipeline` permission (sales/sales_manager/ceo;
  account excluded).
- `permissions.js`: split `/tickets` (pipeline browser) from `/tickets/:id`
  (detail read); new `/finance` guard; legacy `allowedRoute()` twins synced.
- `AppShell.jsx`: `รายการดีล` gated on `canViewDealPipeline`; `ค่าคอมมิชชัน`
  hidden for account (route access unchanged); new `งานการเงิน` nav item.
- `SalesTabs.jsx`: pipeline tabs gated on `canViewDealPipeline` (defensive).
- New `accountActions.js` (`nextAccountAction`/`accountMoneyBucket`, factored
  out of `DealDepositPanel.jsx`/`TicketDetailPage.jsx`'s existing account
  gates — single source of truth).
- New `AccountOverview.jsx` (landing) + `AccountFinancePage.jsx` (`/finance`).
- `App.jsx`: lazy imports, `/` role branch, `/finance` route.
- `mockApi.js`: `tickets.list()` row projection gained
  `closeConfirmedAt`/`invoiceOnFile` (DTO-parity fix, not a new field).
- `queryKeys.js`: `ticketListBySalesStage`.
- `docs/role-scoped-views.md` (new — Import hadn't created it in this
  worktree/branch): pattern doc + Account's full section.
- Tests: `accountActions.test.js`, `AppShell.test.jsx`, `permissions.test.js`
  additions, `AccountOverview.test.jsx`, `AccountFinancePage.test.jsx`.

### Out of Scope
- Backend/DB changes (none needed or made).
- Widening account's server-side ticket-list scope to include close-ready/
  `CLOSED_PAID` deals — see "Known Risks" below; that would be an authz
  change requiring the full evidence pipeline, explicitly not done here.
- Sales / Sales Manager / HR / CEO role views (separate branches).
- Backend real-DB smoke test (reviewer's job per the task instructions).

## Files Changed
- `frontend/src/api/routes.js` — added `canViewDealPipeline: ['sales',
  'sales_manager', 'ceo']`.
- `frontend/src/app/permissions.js` — split `/tickets` (exact) vs
  `/tickets/*` PATH_GUARDS; added `/finance`; synced `allowedRoute()`
  (`'tickets'`/`'ticket-dashboard'` → `canViewDealPipeline`, `'ticket-detail'`
  → `canViewTickets`, new `'finance'` case).
- `frontend/src/components/layout/AppShell.jsx` — `รายการดีล` show condition
  → `canViewDealPipeline`; `ค่าคอมมิชชัน` show condition adds
  `&& user.role !== 'account'`; new `งานการเงิน` nav item (`/finance`, group
  `finance`, `show: canConfirmPayments && SALES_ENABLED`).
- `frontend/src/components/layout/AppShell.test.jsx` (new) — 3 tests.
- `frontend/src/features/sales/SalesTabs.jsx` — `baseTabs` (ดีลทั้งหมด/ภาพรวม)
  now conditional on `canViewDealPipeline`.
- `frontend/src/features/tickets/accountActions.js` (new) —
  `nextAccountAction(ticket)`, `accountMoneyBucket(ticket)`.
- `frontend/src/features/tickets/accountActions.test.js` (new) — 14 tests.
- `frontend/src/features/dashboard/AccountOverview.jsx` (new).
- `frontend/src/features/dashboard/AccountOverview.test.jsx` (new) — 4 tests.
- `frontend/src/features/finance/AccountFinancePage.jsx` (new).
- `frontend/src/features/finance/AccountFinancePage.test.jsx` (new) — 4 tests.
- `frontend/src/App.jsx` — lazy `AccountOverview`/`AccountFinancePage`
  imports; `/` route branches `role === 'account' && SALES_ENABLED`; new
  `/finance` route (inside the existing `SALES_ENABLED` + `RequireAccess`
  block).
- `frontend/src/api/mockApi.js` — `tickets.list()` row gained
  `closeConfirmedAt: t.closeConfirmedAt ?? null` and
  `invoiceOnFile: hasInvoiceAttachment(t)` (both already existed on
  `buildTicketDetail`'s summary; the real backend's `TicketSummaryDto` is the
  same shape for `list()` and `get()`, so this was a mock under-projection,
  not a new field).
- `frontend/src/api/queryKeys.js` — added `ticketListBySalesStage(salesStage)`.
- `frontend/src/app/permissions.test.js` — 2 `hasPermission` cases + 2
  `canAccessPath` cases for the new split.
- `docs/role-scoped-views.md` (new).

## Commands Run
```bash
cd frontend
npm run lint
npx vitest run          # full suite
npm run build
```

## Test / Build Results
- Lint: **pass** — 0 errors, 1 pre-existing warning in `PayrollPage.jsx`
  (react-hooks/exhaustive-deps, untouched by this branch).
- Tests: **pass** — 53 files / 464 tests, 0 failures (includes the 22 new
  tests this branch adds: 14 `accountActions`, 3 `AppShell`, 4
  `AccountOverview`, 4 `AccountFinancePage`, and the 4 new
  `permissions.test.js` cases counted within the pre-existing file's total).
- Build: **pass** — `vite build` succeeds; `AccountOverview`/
  `AccountFinancePage`/`accountActions` each code-split into their own chunk.
- Backend: **not run** — frontend-only change, no backend touched, per the
  task's own instruction ("Do NOT run backend or the real-DB smoke — reviewer
  does").

## Authz Evidence
No authorization change in this task. `canViewDealPipeline` and the
`/tickets` vs `/tickets/:id` route-guard split are a frontend **presentation**
change layered on top of gates that already exist and are already enforced
server-side (`TicketService.VIEWER_ROLES` / `TicketRepository.appendRoleScope`
for list scope; `TicketService.ACCOUNT_ROLES` for
`confirmDepositPaid`/`confirmFinalPayment`/`confirmCloseReady`;
`CommissionService.CREATE_FROM_DEAL_ROLES` for `createCommissionFromDeal`).
Nothing in `ROLE_PERMISSIONS` (`routes.js`) that maps to a real server
endpoint was loosened — `canViewDealPipeline` is a brand-new frontend-only key
with no backend counterpart to diverge from, and `canViewTickets` (which does
map to `TicketService.VIEWER_ROLES`) is unchanged (still includes account).
The `mockApi.js` change is a DTO-parity fix (adds two fields the real backend
already returns), not an authz change.

## Decisions Made
- **`nextAccountAction` priority order**: overdue-first (an overdue balance
  resolves to "ติดตามชำระ" even when the ticket is also
  `DEPOSIT_NOTICE_ISSUED`/`AWAITING_FINAL_PAYMENT`), matching the money
  worklist's own overdue-first sort. The task prompt's mapping reads as
  ordered by pipeline position; I read "overdue balance" as a cross-cutting
  override rather than a fifth mutually-exclusive pipeline stage, since a
  ticket can be simultaneously overdue AND deposit-pending/final-payment-due,
  and chasing the money is more urgent than the routine confirmation wording.
  Documented in `accountActions.js`'s own doc comment.
- **CTA button variants**: success (green) for `confirmDeposit`/
  `confirmFinalPayment`, primary (indigo) for `confirmCloseReady`, text
  (ghost) for `recordInvoiceCommission` — all as specified. `chaseOverdue` was
  not specified in the prompt; I used the `danger` variant (outlined red) as
  the closest semantic fit for an urgent-but-not-a-money-receipt action.
- **Right-rail "รับแล้ว (เดือนนี้)"** is a best-effort approximation
  (`amountPaid` summed over tickets whose `updatedAt` falls in the current
  calendar month), not a true receipt-date aggregate — no cheap client-side
  way to get exact monthly-received totals without a per-ticket
  payment-receipt fetch for every visible deal. Documented inline in
  `AccountOverview.jsx`.
- Kept `AccountFinancePage`'s desktop table on the existing `.ticket-table`
  grid-column CSS class (4 columns: deal / stage-action / balance / CTA)
  rather than inventing a new column-count class, since the existing ratio
  fit without modification.

## Assumptions
- Import's `feat/role-views-import` branch (referenced throughout the task
  prompt and the plan) is a separate worktree/branch not present here, so
  `canViewDealPipeline` did not already exist in `routes.js` — I added it
  fresh with the account-excluded value directly (the plan's own fallback
  note: "if a merge later shows import's branch added `canViewDealPipeline`
  including account, integration keeps the EXCLUDE-account version").
- `docs/role-scoped-views.md` did not exist in this worktree, so I created it
  fresh with the shared pattern section + Account's section, structured so
  Import's section can be added later with minimal conflict (though the plan
  accepts conflicts on shared files as normal for this program).

## Known Risks
- **Close-ready / `CLOSED_PAID` buckets read empty under the current backend
  scope** (see `docs/role-scoped-views.md`'s "KNOWN DATA GAP" section and
  `accountActions.js`'s doc comment for the full mechanism): account's
  server-side ticket-list scope only returns deals with a payment action
  pending or an overdue balance, never a fully-paid/closed deal (both have
  `amountOutstanding = 0` by definition). The "รอปิดงาน" and "ออกค่าคอม"
  buckets/worklist rows are wired correctly end-to-end but will show 0/empty
  in both the mock and (almost certainly) the real backend until either (a)
  someone looks up a specific ticket by ID (unaffected by this scope), or (b)
  a future branch deliberately widens the account list scope with full authz
  evidence. This is inherited from the existing `TicketRepository
  .appendRoleScope`/mock `accountListScopeIncludes` design (handoff 100), not
  introduced by this branch — flagged, not silently worked around.
- `AccountFinancePage`'s stage-filter chip and a row's CTA button can share
  the exact same Thai label (e.g. "บันทึกใบกำกับ + ออกค่าคอม" appears both as a
  filter chip and as the matching row's CTA) — purely a test-authoring
  footgun (fixed by scoping queries with `within(row)`), not a UI bug, but
  worth knowing if extending the tests later.
- This branch was built without Import's branch merged, so the shared-file
  edits (`routes.js`, `permissions.js`, `AppShell.jsx`, `App.jsx`,
  `SalesTabs.jsx`) will very likely conflict with Import's equivalent edits at
  integration — expected and accepted per the plan's "Branching" section.

## Things Not Finished
- Backend real-DB smoke test (explicitly the reviewer's job, not this
  agent's, per the task instructions).
- The close-ready/`CLOSED_PAID` list-scope gap above is flagged for the plan
  owner's decision, not resolved.
- Sales / Sales Manager / HR / CEO role views — separate branches, out of
  scope here.

## Recommended Next Agent
Opus review (per the program's 5-step path: design → validate → implement →
**review against the approved design** → tests) — verify the built UI against
`valiant-watching-map.md`'s Account widget description, then decide on the
close-ready/`CLOSED_PAID` scope gap follow-up.

## Exact Next Prompt
```
Review the built UI for feat/role-views-account against the approved Account
widget in ~/.claude/plans/valiant-watching-map.md ("Per-role target views →
2. Account") and docs/role-scoped-views.md's Account section. Pay particular
attention to the documented KNOWN DATA GAP (close-ready/CLOSED_PAID buckets
read empty under the current account ticket-list scope — see accountActions.js
and docs/role-scoped-views.md) and decide whether that needs a follow-up
branch to widen the scope (would require the full authz evidence pipeline per
CLAUDE.md) or should stay as documented behavior. Then run the real-backend
UI smoke test as the account role (frontend-mock is not sufficient per
CLAUDE.md's authz-verification rule) before merge.
```
