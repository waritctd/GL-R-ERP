# Agent Handoff

## Task
Investigate the sales ticket flow for bugs ("it isn't seamless"), then fix the P0 dual-track
deadlocks and — per the user's explicit request — move the money-receipt confirmations
(รับยอดมัดจำ / รับชำระเต็มจำนวน) from sales to a new **account** role (ฝ่ายบัญชี), with CEO fallback.

## Branch
`claude/charming-gauss-f118ad` (worktree branch; equivalent to plan branches
`fix/ticket-dualtrack-deadlocks` + `feat/accountant-payment-confirmations` — stacked because
both touch the same TicketService methods). Not committed, not pushed.

## Base Commit
`a7991d7` (main tip — PR #210 merge)

## Context / investigation
Full audit findings + remediation plan live in the plan file
`~/.claude/plans/can-you-investigate-the-tranquil-newell.md` (29 findings, severity-ranked).
Three parallel audits (backend lifecycle, frontend UX, mock/contract drift) confirmed the
dual-track flow forward-ported in handoff 39 was never business-reviewed and shipped two hard
deadlocks, now fixed here. **Only P0 deadlocks + the accountant change are in this branch** —
remaining P0/P1/P2 findings (deposit-notice mechanism split, broken `document/` module,
factory-email open relay, `comment()` ticket leak, read-authz gaps, pricing integrity, FX
`orElse(ONE)`, frontend seams) are documented in the plan file for follow-up branches.

## What changed (business rules — explicitly user-authorized)
1. **Deadlock A fixed:** `issueImportRequest` now accepts `paymentStatus` of
   `DEPOSIT_NOTICE_ISSUED` **or** `DEPOSIT_PAID` (previously the natural ordering —
   customer pays deposit before import issues the IR — bricked fulfillment forever).
   Added guard: refuses when `fulfillmentStatus != null` (re-issue would downgrade an
   in-flight track — this became reachable once DEPOSIT_PAID qualified).
2. **Deadlock B fixed:** `confirmDepositPaid` now advances payment to
   `AWAITING_FINAL_PAYMENT` when `fulfillmentStatus == GOODS_RECEIVED` (mirror of the
   existing `markGoodsReceived` logic; previously goods-first ordering stranded payment at
   `DEPOSIT_PAID` with `FULLY_PAID`/close unreachable).
3. **Downgrade guard:** `confirmCustomer` refuses when the payment track is already past
   `CUSTOMER_CONFIRMED` (re-confirming used to reset `paymentStatus` and re-arm the deadlocks).
4. **New `account` role (ฝ่ายบัญชี):**
   - `ApplicationRoles`: `account` added to PRIORITY/ALLOWED (after `hr`).
   - `DivisionAccessPolicy`: division code `ac` → `account`. Verified against the live
     Supabase employee master (project tdyzcqzxmhtxpbouewud): division_id 10 `source_code='AC'`
     "ฝ่ายบัญชี"; legacy import row "AC-บัญชี" (null code) is covered by the existing
     name-prefix fallback.
   - `TicketService.ACCOUNT_ROLES = {account, ceo}`; `confirmDepositPaid` and
     `confirmFinalPayment` moved from `SALES_ROLES` to `ACCOUNT_ROLES` (sales now 403s).

## Files Changed
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — items 1–4 above
- `backend/src/main/java/th/co/glr/hr/auth/ApplicationRoles.java` — `account` role
- `backend/src/main/java/th/co/glr/hr/auth/DivisionAccessPolicy.java` — `ac` branch + javadoc
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java` — +24 tests: all 8
  dual-track methods (previously **zero** coverage), both deadlock orderings, dual-track
  close paths, account/ceo allowed + sales/import 403s, downgrade + re-issue guards
- `backend/src/test/java/th/co/glr/hr/auth/DivisionAccessPolicyTest.java` — AC→account
  (incl. null-source_code prefix fallback); the old "AC manager → employee" assertion moved to WH
- `backend/src/test/java/th/co/glr/hr/auth/ApplicationRolesTest.java` — `account` allowed
- `frontend/src/api/routes.js` — `canViewTickets` += `account`; new `canConfirmPayments:
  ['account','ceo']` (nav/route/dashboard all key off canViewTickets so account gets access)
- `frontend/src/features/tickets/TicketDetailPage.jsx` — `confirmDepositPaid`/
  `confirmFinalPayment` gated on `canConfirmPayments`; IR button also shows at `DEPOSIT_PAID`
  (and hides once fulfillment starts); new passive "รอดำเนินการ: รอฝ่ายบัญชี…" hint so
  sales/import see who the payment track waits on
- `frontend/src/api/mockApi.js` — mirrors every backend change (close() dual-track path,
  confirmCustomer downgrade guard, confirmDepositPaid account+ceo + auto-advance,
  issueImportRequest DEPOSIT_PAID + re-issue guard, confirmFinalPayment account+ceo,
  list/get allow `account`); fixes the pre-existing mock bug where the dual-track completion
  path could never close (demo ticket 14)
- `frontend/src/data/demoData.js` — demo persona `account@glr.co.th` / demo1234
  (id 11, คุณบัญชี การเงิน)

## Commands Run
```bash
cd backend && ./mvnw -B clean verify          # full suite incl. Testcontainers
cd frontend && npm ci                          # worktree had no node_modules
cd frontend && npm run lint && npm test && npm run build   # run twice (re-run after last JSX edit)
```

## Tests / Build Results
- **Backend:** `mvnw -B clean verify` — **403 tests, 0 failures, 0 errors, BUILD SUCCESS.**
  Testcontainers ran (integration tests NOT skipped). TicketServiceTest 70/70.
- **Frontend:** lint 0 errors (10 pre-existing exhaustive-deps warnings), **94/94 tests**,
  build OK (~150ms).
- **Manual (frontend-mock, VITE_USE_MOCKS=true):** drove the full deposit-first ordering —
  sales issues notice → **account persona** confirms deposit (sales correctly loses the
  button; waiting hint renders) → **import issues IR from DEPOSIT_PAID** (the previously
  deadlocked ordering) → account confirms final payment on ticket 13 → **sales owner closes
  the dual-track ticket** (previously always 409'd in mock). Event timeline shows FULLY_PAID
  logged by คุณบัญชี การเงิน. Zero console errors.
  ⚠️ Per CLAUDE.md: mock authz is not authoritative — the *permission* rules were verified
  in the Java unit tests, the mock drive verifies UI wiring/UX only.

## Known Risks
1. **`account` users previously mapped to `employee`.** Any real ฝ่ายบัญชี employee logging in
   after deploy silently gains ticket visibility (list/get is barely gated in the backend —
   see plan finding #8). That's the intent of this change, but worth stating.
2. **The two-mechanism deposit-notice split (plan finding #4) is NOT fixed here.** The legacy
   `DOCUMENT_ISSUED` path can still bypass the payment gate on close and strand the dual
   track. Needs the product decision in plan item 4.
3. **Timeline still renders raw enum kinds** (IR_SENT, FULLY_PAID, …) — cosmetic, plan item 6.
4. **`sales_manager` remains locked out of the sales flow** (plan finding #11) — unchanged.
5. Mock demo db resets per page load; mock sessions die on reload (pre-existing).

## Recommended Next Agent
Reviewer (Opus) on this diff, then implementation branches for plan items 3–7 — starting with
`security/ticket-endpoint-authz` (factory-email relay + comment() leak are live in prod).

## Exact Next Prompt
```text
Repo GL-R-ERP, worktree branch claude/charming-gauss-f118ad (10 files changed on top of main
a7991d7, NOT committed). Read CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md, and
docs/agent-handoffs/51_fix-ticket-dualtrack-accountant.md first, plus the audit plan at
~/.claude/plans/can-you-investigate-the-tranquil-newell.md.

Review the diff as a reviewer — do not implement beyond tiny safe fixes. Focus on:
1. The deadlock fixes in TicketService (issueImportRequest DEPOSIT_PAID acceptance + re-issue
   guard, confirmDepositPaid auto-advance, confirmCustomer downgrade guard) — any remaining
   ordering that strands a ticket?
2. The account-role gating (ACCOUNT_ROLES on confirmDepositPaid/confirmFinalPayment) — is CEO
   fallback correctly allowed, sales correctly 403'd, and does the frontend can-map mirror it?
3. mockApi parity — each edited mock method's `// Mirrors` behavior vs the Java service.
Then, if approved, hand back for commit + PR per the repo's normal flow (user must say so).
```
