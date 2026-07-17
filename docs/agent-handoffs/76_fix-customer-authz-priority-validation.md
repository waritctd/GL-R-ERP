# Fix: customer-create authz (gap #1) + ticket priority validation (F2) — 2026-07-18

Branch: `fix/customer-authz-priority-validation` (off `main`). Fixes two live-confirmed
defects from `74_...md` / `75_...md`. Both files were byte-identical on `main` and `uat`,
so this branch promotes cleanly to both.

## What was wrong (live-confirmed over HTTP in `75_...md`)
- **Auth gap #1** — `POST /api/customers`, `/customers/{id}/contacts`,
  `/customers/{id}/projects` were `requireUser`-only. An `employee` reached the customer
  INSERT (controller → repository, service bypassed); only a NOT-NULL `branch` DB error
  (500) stopped it, not a 403.
- **F2** — `TicketService.create` validated `entryChannel` but not `priority`, so
  `priority:"urgent"` reached `chk_ticket_priority` and failed closed (500).
- (Related) customer create with no `branch` → 500: the explicit NULL bypassed the
  column's `DEFAULT 'สำนักงานใหญ่'`.

## Changes
1. `ticket/Priority.java` (new) — LOW/NORMAL/HIGH + `isValid`, mirroring `EntryChannel`.
   `Priority.DEFAULT = NORMAL`.
2. `ticket/TicketService.java` — `create` now rejects an invalid `priority` with 400
   (guard mirrors the existing `entryChannel` guard). Null/blank still defaults to NORMAL.
3. `ticket/TicketRepository.java` — default literal `"NORMAL"` → `Priority.DEFAULT`.
4. `customer/CustomerController.java` — `create`/`createContact`/`createProject` now
   `requireAnyRole(user, "sales")` (403 for any other role); reads stay open for the
   customer pickers. `branch` is coalesced to `'สำนักงานใหญ่'` when blank.
5. `frontend/src/api/mockApi.js` — mirrored the authz: `customers.create` /
   `createContact` / `createProject` now `hasRole('sales')` (were `requireSession`).
   Keeps the mock a faithful (not more-permissive) stand-in per CLAUDE.md.
6. Tests: `customer/CustomerControllerTest.java` (new, 9) — employee/sales_manager 403,
   sales 2xx, branch-default, reads open. `TicketServiceTest` +2 — invalid priority 400,
   valid priority accepted.

## Commands run / results
- `cd backend && ./mvnw -B clean test` → **523 pass / 0 fail** (incl. Testcontainers
  integration; base `main` was 512, +11 new).
- `cd frontend && npm run lint && npm test && npm run build` → lint 0 errors (4
  pre-existing warnings), **123 tests pass**, build ok.

## Known risks
- Role choice is `sales` only (matches `TicketService.create`; the customer-create UI
  lives in `TicketCreateModal`, sales-only). If a CEO/admin ever needs to seed a customer
  outside the deal flow, widen the set deliberately.
- Not yet committed/pushed (awaiting the user's say-so). Not yet deployed — hosted
  verification requires deploying this branch (or promoting to `uat`/`main`).

## Next prompt
> Promote `fix/customer-authz-priority-validation` to `main` (and `uat`), deploy, then
> re-run the `75_...md` §3 negatives against hosted UAT: `employee POST /api/customers`
> must be **403** (was 500/allowed), `POST /api/tickets priority:"urgent"` must be
> **400** (was 500), and a customer create without `branch` must **201** with branch
> `'สำนักงานใหญ่'`. Also fix F3 (profile-request status) and address the stale-deploy /
> deal-pipeline `chk_ticket_status` issue from `75_...md` §0.
