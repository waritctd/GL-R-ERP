# 100 — feat/sales-role-scoped-views

**Branch:** `feat/sales-role-scoped-views` (off `origin/main` `3e9dd32` / #257; 2 commits behind
current origin/main #258/#259 — non-sales, no expected conflict).
**Status:** ready for review. Not deployed.

## Goal
Each sales role sees only its part of the `งานขาย` deal as a mobile-first worklist, with a
one-line "peek" of cross-team status; roles with no sales business don't get the menu. Scoped to
the **5 current `VIEWER_ROLES`** (sales, sales_manager, import, account, ceo). warehouse/qc + the
3-sourcing-case + real-QC step are **deferred** (see bottom).

Two layers:
- **Phase A (frontend, presentation):** hide sections per role, degrade to a peek row.
- **Phase B (backend, authorization):** list-scoping + sub-resource read gates + a role-projected
  `TicketDto`, so the boundary is real, not just UI. This is an **authorization change**.

## 1. Files changed
**Phase A — frontend**
- `frontend/src/features/tickets/salesViewScope.js` (new) — pure policy: `visibleSections(role)`,
  `dealInScope(role, deal)`, reusing `stageMeta.js`. ceo/sales_manager/sales → all sections;
  import hides payment/quotation/depositNotice/priceApproval; account hides
  pricingRequest/delivery/priceApproval; unknown → nothing.
- `frontend/src/features/tickets/salesViewScope.test.js` (new) — 18 unit tests incl. wrong-way-round.
- `frontend/src/features/tickets/TicketDetailPage.jsx` — `SectionPeek` component; each section
  wrapped by `visibleSections(role)`; the two view-only quotation/deposit doc buttons gated.
- `frontend/src/features/tickets/TicketListPage.jsx` — role inbox toggle (`ต้องดำเนินการ / ทั้งหมด`,
  URL param `inbox`) for import/account; `MoneyWorklistCard` (account); import queue-label chip.
- `frontend/src/features/sales/SalesTabs.jsx` — import tab order leads with `คิวขอราคา`.

**Phase B — backend + mock parity**
- `backend/.../ticket/TicketService.java` — `list/listPage` pass `actor.role()`; `listPayments`
  import→403; `requireViewAccess` → `projectForRole` (strips quotations for import); explicit
  import→403 in `loadQuotationContext`; `comment()` re-projected (side-door closed).
- `backend/.../ticket/TicketRepository.java` — `appendRoleScope` additive `WHERE` for import
  (active non-terminal pricing request OR stage ≥ PROCUREMENT, not closed/lost) and account
  (payment pending OR overdue-with-balance); `findSummaries`/`countSummaries` gain `actorRole`;
  `payableAmountSelect`/`paidAmountSelect` extracted as reusable correlated SQL (no drift).
- `backend/.../deposit/DepositNoticeService.java` — import denied outright on all deposit reads.
- `backend/.../ticket/TicketScopeIntegrationTest.java` (new) — 18 wrong-way-round cases on real PG.
- `backend/.../ticket/TicketServiceTest.java`, `DepositNoticeServiceTest.java` — updated for new
  signatures/gates.
- `frontend/src/api/mockApi.js` — mirror the new gates (kept `// Mirrors …` headers).

## 2. Commands run
- Frontend: `npm run lint` (0 errors), `npx vitest run` (48 files / **418 pass**), `npm run build` (OK).
- Backend: `./mvnw -o test -Dtest=TicketScopeIntegrationTest,TicketServiceTest,DepositNoticeServiceTest,TicketRepositoryIntegrationTest`
  → **219 pass / 0 fail / BUILD SUCCESS**.
- HTTP SIT: two Docker stacks (origin/main :8080 vs Phase B :8082), demo profile, curl per role.

## 3. Tests / build results
- Frontend: PASS (mock-only → **presentation shape verified, authz NOT claimed from mocks**).
- Backend: PASS. Integration tests **ran** (Testcontainers/Docker available — `TicketScopeIntegrationTest`
  182s, `TicketRepositoryIntegrationTest` 51s), **not skipped**.

## 4. Authz evidence (real Java service — required, done)
- **Unit:** `salesViewScope.test.js` (18) + `TicketServiceTest` list-scope role branches.
- **Integration (real PG, real service+repo):** `TicketScopeIntegrationTest` 18 wrong-way-round
  cases — import lead-stage/cancelled-PR/closed-lost excluded; account non-pending/paid excluded;
  import quotation-file/payments/deposit denied; account pricing/factory/costing denied; ceo sees all.
- **Mutation-checks (4):** import `WHERE`, account `WHERE`, `projectForRole`, deposit denial — each
  neutered → its test went red (only it) → reverted. Guards genuinely bite.
- **HTTP SIT (running stack, before/after vs origin/main):** import — quotations in DTO 2→**0**,
  `/quotations/*/file` →**403**, `/payments` 200→**403**, `/deposit-notices` 200→**403**, list 6→**0**;
  sales control sees quotation (1) + 200; account list 6→**0**; account `/pricing-requests/1` **403**.

## 5. Known risks
- **Residual gap (documented in code):** import's own mutation responses built via `requireTicket`
  directly (reserveStock/recordDelivery/markGoodsReceived) still embed quotations — transient, tied
  to import doing its own action; not closed to avoid touching every call site. Revisit if tightened.
- `dealInScope`/`appendRoleScope` treat a **DRAFT** pricing request as "active" (import in scope
  slightly early). Intentional/approximate; adjust if the owner wants DRAFT excluded.
- Frontend `app/roles.js` still stale (no `account`, buggy `"sa"`); not touched here — reconcile
  separately if it feeds any UI role decision.
- Branch is 2 commits behind current origin/main; rebase/merge before final merge if desired.

## 6. Deferred to its own session (NOT in this branch)
warehouse + qc roles, the **3 sourcing cases** (stock / direct import / **buy-from-other-importer
reseller**), and **QC as a real warehouse quality-inspection step** (sampling vs an inspection
sheet, likely gating delivery). All new business logic — owner-confirmed 2026-07-21.

## 7. Next prompt
> Review PR for `feat/sales-role-scoped-views`. Phase A (frontend section-scoping) + Phase B
> (backend read-scoping, authz). Authz evidence is in handoff 100 §4 (18-case real-PG integration
> test + 4 mutation-checks + HTTP SIT). Verify `sales_manager` stays out of every action role set
> and the two-signature close gate is untouched. Then confirm the deferred warehouse/qc + 3-sourcing
> + QC-step scope is understood for the next session.
