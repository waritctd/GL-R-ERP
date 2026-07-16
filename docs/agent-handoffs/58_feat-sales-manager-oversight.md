# Agent Handoff

## Task
Phase-2 Branch 1 (user-approved product decision): give `sales_manager` **read + comment
oversight ONLY** on the sales/ticket stack. Product framing from the user: `sales_manager` "acts
like a project manager for the sales team — follow up / check up, but not perform the actual
work." Previously `sales_manager` was fully locked out of tickets/deposit notices (403 on every
endpoint). The critical invariant for this branch: `sales_manager` must gain read+comment access
and **zero write actions** — it must never be added to any write-capable role set.

## Branch
`feat/sales-manager-oversight` (branched from `origin/main` at `bdd51ea`, tip = PR #218 "repair
five ticket-flow frontend UX seams")

## Base Commit
`bdd51ea` (fix(frontend): repair five ticket-flow frontend UX seams (#218))

## Current Commit
(set after commit — see `git log -1`)

## Agent / Model Used
Claude Sonnet 5 (implementation agent in the Sonnet-implements/Opus-reviews loop)

## Important context discovered mid-task
`sales_manager` is **not a brand-new role** — it already exists in production today. It is
derived automatically by `DivisionAccessPolicy.roleFor()` for any employee in the SA (sales)
division whose position title contains "ผู้จัดการ" (manager), is already in the canonical
`ApplicationRoles` allowlist, and already carries real write/approval power in the **commission**
module (`CommissionService.SUBMIT_ROLES`/`MANAGER_ROLES`, `CommissionController`'s
`@PreAuthorize(hasAnyRole('SALES_MANAGER', ...))`, `DashboardService.COMMISSION_APPROVER_ROLES`,
`AttachmentController.MANAGER_ROLES`). None of that is touched by this branch — this task is
purely additive: extending the *ticket/deposit* surface's read gate to a role that already exists
and already has unrelated write power elsewhere. Do not confuse "sales_manager has zero write
power on tickets" with "sales_manager has zero write power anywhere" — the latter is false and was
never the goal.

## Scope

### In Scope
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — `VIEWER_ROLES` only
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeService.java` — `VIEWER_ROLES` only
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java` — new fixture + tests
- `backend/src/test/java/th/co/glr/hr/deposit/DepositNoticeServiceTest.java` — new fixture,
  helper, and tests
- `frontend/src/api/routes.js` — `ROLE_PERMISSIONS.canViewTickets` only
- `frontend/src/api/mockApi.js` — `requireTicketViewer` + the two duplicated inline gates in
  `tickets.list`/`tickets.get`
- `frontend/src/features/tickets/TicketDetailPage.jsx` — one-line `hasActions` fix (see Decisions)

### Out of Scope
- `SALES_ROLES`/`IMPORT_ROLES`/`CEO_ROLES`/`ACCOUNT_ROLES` anywhere in ticket/deposit — untouched,
  verified by grep (see Action-Gate Confirmation below).
- `commission/`, `dashboard/DashboardService.java`, `attachment/AttachmentController.java` —
  `sales_manager`'s existing write powers there are unrelated to this change and intentionally
  untouched.
- `pricing/FxRateController.java`, `pricing/PriceCalcConfigController.java` — CEO-only, untouched.
- `catalog/`, `customer/`, `factory/` — no role gating exists there at all; out of scope, not
  touched.
- Demo data / persona seeding — `sales.manager@glr.co.th` (mock) and the backend
  `demo.salesmanager@demo.invalid` SA-division seed already resolve to `sales_manager`; no
  changes needed.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — added `"sales_manager"` to
  `VIEWER_ROLES` (now `Set.of("sales", "import", "ceo", "account", "sales_manager")`) with a
  comment noting the invariant. `SALES_ROLES`/`IMPORT_ROLES`/`CEO_ROLES`/`ACCOUNT_ROLES`
  unchanged. Since the createdBy-filter/ownership checks in `list()`, `listPage()`, and
  `requireViewAccess()` only special-case the literal `"sales"` role, `sales_manager` sees ALL
  tickets unfiltered (matches "check up on the whole team", not just their own).
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeService.java` — same one-line addition
  to its `VIEWER_ROLES` constant, same comment convention.
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java` — added
  `salesManagerActor = actor(8L, "sales_manager")` and 17 new tests: `list` unfiltered,
  `get`/`comment` allowed on someone else's ticket, and one 403 test per action category
  (submit, pickup, proposePrice, approve, calculatePrices, overrideItemPrice, generateQuotation,
  editItems, close, cancel, confirmCustomer, confirmDepositPaid, issueImportRequest,
  markGoodsReceived, confirmFinalPayment, assertFactoryEmailAllowed).
- `backend/src/test/java/th/co/glr/hr/deposit/DepositNoticeServiceTest.java` — added
  `salesManagerActor` fixture, a new `assertForbidden` helper (file previously had none — it only
  tested `issue()`'s status-transition logic, not authz), and 6 new tests: `listByTicket`/
  `getById` allowed, `createDraft`/`update`/`issue`/`requestRevision` 403.
- `frontend/src/api/routes.js` — `canViewTickets` now
  `['sales', 'import', 'ceo', 'account', 'sales_manager']` with a comment stating the invariant.
  `canCreateTickets`/`canPickupTickets`/`canProposePrices`/`canApproveReject`/
  `canGenerateQuotation`/`canConfirmPayments` unchanged.
- `frontend/src/api/mockApi.js` — `requireTicketViewer`'s `hasRole(...)` call and the two
  duplicated inline role arrays in `tickets.list`/`tickets.get` (these three didn't share the
  helper — a pre-existing triplication, not introduced here) all updated in lockstep to include
  `'sales_manager'`. `// Mirrors <JavaClass>` headers re-checked — still accurate, unchanged.
- `frontend/src/features/tickets/TicketDetailPage.jsx` — see Decisions Made below: `hasActions`
  now excludes the `comment` flag.

## Commands Run
```bash
git fetch origin && git switch -c feat/sales-manager-oversight origin/main
cd backend && ./mvnw -q -B test -Dtest=TicketServiceTest,DepositNoticeServiceTest   # fast pass
cd backend && ./mvnw -B clean verify
cd frontend && npm ci && npm run lint && npm test && npm run build
```

## Test / Build Results
- **Backend**: `./mvnw -B clean verify` — **BUILD SUCCESS**. Ran targeted
  `TicketServiceTest`/`DepositNoticeServiceTest` first (green), then the full `clean verify`:
  **453 tests, 0 failures, 0 errors, 0 skipped**, Jacoco coverage checks met. A local Postgres
  was reachable in this environment so the Testcontainers/integration tests
  (`OvertimeRepositoryIntegrationTest`, Flyway migration test through V48, etc.) ran for real
  rather than being skipped — a better result than the "usually skipped locally" default CLAUDE.md
  describes.
- **Frontend**: `npm run lint` — 0 errors, 7 pre-existing `react-hooks/exhaustive-deps` warnings
  (verified none are new — same 7 files/lines as the prior branch's handoff). `npm test` —
  **94/94 passed** (19 test files), including `permissions.test.js` and `contract.test.js`
  (mockApi/hrApi surface parity, untouched by this branch). `npm run build` — succeeds, 274
  modules transformed.
- **Live browser verification**: attempted, but the shared preview infrastructure's dev server
  (`frontend-mock`, port 5200) is bound to a **different worktree**
  (`.claude/worktrees/charming-gauss-f118ad`), not this branch's worktree
  (`agent-a2ea2e8aa9da6fcf9`) — same pre-existing environment limitation the prior agent
  documented in `docs/agent-handoffs/57_fix-ticket-frontend-seams.md` (Known Risk 3). That other
  worktree additionally has unrelated unresolved git-conflict markers in its own copy of
  `TicketDetailPage.jsx`, breaking its dev server entirely — unrelated to this branch, not
  touched. Verification instead relied on: full re-reads of every changed code path, a
  request-flow trace confirmed by a research subagent (routes.js → `RequireAccess`/
  `canAccessPath` → `AppShell` nav → `TicketListPage`/`TicketDetailPage` → mockApi, all keying
  off the same `ROLE_PERMISSIONS.canViewTickets` array with no independent literal-role check
  anywhere in that chain), and the full backend/frontend test suites, all green.

## Decisions Made
- **`TicketDetailPage.jsx` `hasActions` fix**: `hasActions = Object.values(can).some(Boolean)`
  included `can.comment` (`!TERMINAL.includes(st)`, true for almost every ticket). `can.comment`
  has its own fully independent UI in the event-history panel (`{can.comment && (...)}` around
  line 1530) — it was never meant to gate the "การดำเนินการอื่น ๆ" (other actions) panel at line
  673. This was a **pre-existing latent bug** (any role with zero real actions on some status,
  e.g. `import` viewing a `draft` ticket, would already see an empty actions-panel header with no
  buttons inside) that becomes **guaranteed** to show for `sales_manager`, since it now has zero
  real actions on every non-terminal ticket in every status. Fixed by destructuring `comment` out
  of the flags object before computing `hasActions`. This is a tiny, targeted, in-scope
  correctness fix (not a `role === 'x'` gate) directly required to satisfy "verify by construction
  that a sales_manager sees NO action buttons" — without it, `sales_manager` would see an empty
  but visually-present "Other actions" panel on every non-terminal ticket.
- **Ownership-only gates (`close`, `cancel`, `update` on deposit notices)**: these have no
  `requireRole` call at all — only an ownership check (`createdById != actor.id()` /
  `requireTicketOwner`). Confirmed by re-reading `TicketService.create()`/`submit()` (both
  `SALES_ROLES`-gated) that `sales_manager` can never become a ticket's `createdById`, since it
  can never call `create()`. Therefore the ownership check alone is sufficient to deny it on
  every owner-gated action — no additional role gate was needed or added on these three methods.
  Tests assert this via a ticket stubbed with a different owner.
- **Role-gated-then-owner-gated actions (`generateQuotation`, `confirmCustomer`,
  `requestRevision`, `createDraft`, `issue` on deposit notices)**: these call
  `requireRole(actor, SALES_ROLES)` (or equivalent) as the *first* line, before any ownership
  check runs. `sales_manager` 403s at the role check, so the ownership reasoning above doesn't
  even need to be reached for these — belt-and-suspenders. Tests call these directly without
  stubbing a ticket at all (matching the existing test file's own convention for other
  role-rejected actors), since the role check throws before any repository lookup.
- Did not add a `canCommentTickets` permission flag to `routes.js` — the frontend never had one;
  comment visibility on `TicketDetailPage.jsx` is derived purely from ticket status
  (`can.comment = !TERMINAL.includes(st)`), gated only by having already passed
  `canViewTickets` upstream at the route level. Adding a redundant flag would be scope creep with
  no corresponding backend concept to mirror.

## Assumptions
- "sales_manager sees ALL tickets, not just tickets it's somehow associated with" — confirmed
  correct per the user's framing ("acts like a project manager for the sales team", i.e.
  oversees the team, not one rep) and consistent with how the existing `createdBy`-filter logic
  already special-cases only the literal `"sales"` role.
- The three-way literal-array triplication in `mockApi.js` (`requireTicketViewer`, `list`,
  `get`) is pre-existing structure, not something to refactor into a single shared call as part
  of this branch — CLAUDE.md's "smallest diff" rule argues against an unrelated refactor here;
  flagging as a possible future cleanup rather than doing it unasked.

## Known Risks
1. Live browser/UI verification was not possible in this session for environmental reasons (see
   Test/Build Results) — the next reviewer should drive `frontend-mock` against **this**
   worktree/branch and confirm: logging in as the "Sales Manager" demo persona, reaching
   `/tickets`, opening any ticket (including one created by a different user), seeing zero action
   buttons and zero "Other actions" panel chrome, but a working comment box, and posting a
   comment successfully.
2. `sales_manager` already has real write power in the commission module today (pre-existing,
   unrelated to this branch) — worth the reviewer double-checking this isn't accidentally
   conflated with "sales_manager is read-only" in any future work on this role.
3. `docs/agent-handoffs/57_fix-ticket-frontend-seams.md`'s Known Risk 2 (StatCard tiles on
   `TicketDashboard.jsx` still navigate to unfiltered `/tickets`) is unaffected by this branch —
   `sales_manager` will hit the same un-filtered dashboard tiles as everyone else, which is
   actually the *correct* behavior for an oversight role (they want to see everything), so this
   pre-existing seam is arguably a non-issue for this specific role.

## Things Not Finished
- Live UI verification (see Known Risk 1).

## Recommended Next Agent
Claude Opus review (per this repo's standing Sonnet-implements/Opus-reviews loop) — should
additionally attempt live browser verification if it has working preview-tool access to this
branch's actual worktree, and independently re-confirm the action-gate grep sweep below.

## Action-Gate Confirmation (grep sweep, ticket + deposit + pricing)
Ran directly against the final diff:
```
ticket/TicketService.java:
  SALES_ROLES  = Set.of("sales")                                    — unchanged
  IMPORT_ROLES = Set.of("import")                                   — unchanged
  CEO_ROLES    = Set.of("ceo")                                      — unchanged
  ACCOUNT_ROLES = Set.of("account", "ceo")                          — unchanged
  VIEWER_ROLES = Set.of("sales","import","ceo","account",
                        "sales_manager")                            — sales_manager added HERE ONLY

deposit/DepositNoticeService.java:
  SALES_ROLES  = Set.of("sales")                                    — unchanged
  CEO_ROLES    = Set.of("ceo")                                      — unchanged
  IMPORT_ROLES = Set.of("import")                                   — unchanged
  VIEWER_ROLES = Set.of("sales","import","ceo","account",
                        "sales_manager")                            — sales_manager added HERE ONLY

pricing/FxRateController.java:          CEO_ROLES = Set.of("ceo")   — unchanged, untouched
pricing/PriceCalcConfigController.java: CEO_ROLES = Set.of("ceo")   — unchanged, untouched
```
Every `requireRole(actor, XXX_ROLES)` call site and every inline
`XXX_ROLES.contains(actor.role())` check in both files was individually re-read; none reference
`VIEWER_ROLES` except the read paths (`list`, `listPage`, `get`/`requireViewAccess`, `comment` via
`requireViewAccess`, quotation file downloads via `requireViewAccess`, and
`listByTicket`/`getById`/`preview`/`getXlsx`/`getPdf`/`getRemainingInvoiceXlsx` via
`requireTicketViewer` on the deposit side).

## Exact Next Prompt
```text
Repo GL-R-ERP, branch feat/sales-manager-oversight (based on origin/main at bdd51ea, includes PR
#218). Read CLAUDE.md and docs/agent-handoffs/58_feat-sales-manager-oversight.md — it documents
giving sales_manager read+comment-only oversight of the ticket/deposit-notice stack (added to
VIEWER_ROLES only in TicketService.java and DepositNoticeService.java, to canViewTickets only in
routes.js, and to the three mockApi.js viewer-gate call sites). Also fixed a pre-existing
TicketDetailPage.jsx bug where hasActions incorrectly counted the independent `comment` UI flag,
which would have shown sales_manager an empty "Other actions" panel. Known risk: live browser
verification wasn't possible because the shared preview server was bound to a different, unrelated
worktree (charming-gauss-f118ad, which has its own broken merge-conflict markers) — if your
environment can launch frontend-mock against THIS worktree, log in as the "Sales Manager" demo
persona and confirm: (1) /tickets is reachable and shows ALL tickets, (2) opening any ticket
(including one created by someone else) shows zero action buttons, zero "Other actions" panel
header, but a working comment box, (3) posting a comment succeeds. Also independently re-verify
the action-gate grep sweep at the bottom of the handoff (confirm SALES_ROLES/IMPORT_ROLES/
CEO_ROLES/ACCOUNT_ROLES in both Java services are unchanged) before merging on the user's say-so.
```
