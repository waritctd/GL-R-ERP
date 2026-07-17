# DB & API Health Audit + Regression Test Plan (2026-07-18)

Scope: check database + API health, audit the full API surface and DB integration,
run regression on both `uat` and `main`, and reset the UAT test data. Covers the
hosted stacks (Render + Supabase) and the codebase at `uat`@7c1af4e / `main`@18c0ceb.

## 1. Health snapshot

### Databases (Supabase, direct-Postgres — the Spring backend connects with a privileged role)
| Env | Supabase project | Migration state | API |
|---|---|---|---|
| UAT | `wuypxdznuhhluwzncafh` (GL&R's UAT, ap-southeast-2) | V1–V54 + V900–V909, **all success** | up (401 on `/api/auth/me`) |
| MAIN/demo | `tdyzcqzxmhtxpbouewud` (GL&R, ap-northeast-1) — **real payroll data, treat as prod** | **V47** (missing V48–V54) | up (401) |

- UAT is fully migrated and healthy. The earlier `chk_ticket_status` error was the
  runtime bug (now fixed) and rolled back cleanly — **no bad data persisted**
  (statuses are all valid; TKT-10 still correctly at `AWAITING_BUYER`).
- MAIN/demo is 7 versions behind `main`. The next `gl-r-erp` deploy migrates it to
  V54 (additive columns/tables + V50 backfill over 8 tickets) shipped together with
  the `addEventInternal` crash fix — safe, but a deliberate migration of the real DB.

### CHECK-constraint drift: NONE
`chk_ticket_status` on both hosted DBs = exactly the 10-value set that
`TicketStatus.VALUES` encodes. Deployed constraints match the migrations.

## 2. Regression results (both branches, local)
| Branch | Backend (`mvnw test`, incl. Testcontainers integration) | Frontend (`lint`+`test`+`build`) |
|---|---|---|
| `uat`@7c1af4e | PASS (529) | PASS (126; 0 lint errors, 4 warnings; build ok) |
| `main`@18c0ceb | PASS (512) | PASS (123; build ok) |

(Backend count differs because `uat` carries extra Mailer/UAT tests. New this cycle:
`TicketEventStatusIntegrationTest` — real-DB coverage of the addEvent/status fix.)

## 3. API surface audit — 136 endpoints / 19 controllers

SecurityConfig is **default-deny** (`anyRequest().authenticated()`); only `POST
/api/auth/login`, `POST /api/attendance/punch`, `OPTIONS /**`, `GET
/actuator/health` are anonymous. Authorization is enforced through a mix of
`@PreAuthorize`, controller `requireAnyRole`, inline role checks, and service-layer
gates. Endpoint inventory (per-area counts): Ticket/Sales 44, Deposit/Quotation 10,
Price-import 10, Leave 9, Commission 9, Payroll 7, Attendance 6, Overtime 6,
Customer 6, Catalog 5, Employee 5, Auth 4, Attachment 4, Profile-requests 3, FX 2,
Price-calc-config 2, Notification 2, Factory-config 1, Dashboard 1.

### Authorization findings (all pre-existing; ranked)
1. **Customer create endpoints have no role check** — `POST /api/customers`,
   `/customers/{id}/contacts`, `/customers/{id}/projects`
   (`CustomerController.java:43,55,70`) are authenticated-only; any role (incl.
   `employee`) can create customer/contact/project rows. Service layer is bypassed
   (controller → repository directly).
2. **Attachment upload missing access check** — `POST
   /api/tickets/{id}/attachments` (`AttachmentController.java:62`) calls only
   `requireUser`, while its sibling list/download/delete all call
   `requireTicketAccess`/`requireAttachmentAccess`. Any authenticated user can
   attach files to any ticket. Also not `@Transactional` (FS write + DB insert can
   orphan a file).
3. **`GET /api/employees/{id}`** is `requireUser`-only at the controller while every
   other employee endpoint requires `hr` — verify `EmployeeService.get` scopes to
   self/HR or it is a PII exposure.
4. **Open sensitive reads** — `/api/catalog`, `/catalog/prices`, `/fx-rates`,
   `/price-calc-configs`, `/factory-configs` readable by any authenticated role
   (commercially sensitive price/FX data).
5. **Style drift** — four different authz mechanisms coexist; this inconsistency is
   the root cause of gaps #1/#2. Consider standardizing.

## 4. Security finding — Supabase RLS disabled (both projects)
All 71 tables have Row-Level Security disabled. For the Spring backend (direct
Postgres, app-enforced authz) this is expected. It becomes a **data-exposure risk
only if the Supabase Data API (PostgREST) is enabled and the anon/public key is
reachable** — most serious for the MAIN project (213 real employees + real payroll).
**Action for the user:** confirm the Data API is disabled (or the anon key is not
public) on `tdyzcqzxmhtxpbouewud`; do NOT blanket-enable RLS without policies (it
would break the app's access). Remediation SQL available on request.

## 5. DB integration audit
**DB layer is solid:** no SQL injection (every value is a named param; the only
string-built SQL is static fragments / loop indices), all `UPDATE`/`DELETE` are
WHERE-guarded, transaction boundaries are correct (37 `@Transactional` in
TicketService; the few non-transactional services — payslip distribution, BOT FX —
are deliberately per-item), and the `addEventInternal`/`TicketStatus.isValid` fix is
verified against the full CHECK-constraint × write-path matrix.

**3 more latent bugs of the same class as the fixed one** (an API value reaching a
CHECK-constrained column unvalidated). All fail **closed** (Postgres rejects → 500),
so no silent corruption, but real defects:
- **F1 (highest)** — `PriceImportService.updateProduct:712` writes `in.priceUnit()`
  raw into `price_catalog.product_prices.price_unit` (NOT NULL + CHECK). Nullable,
  unvalidated; `addProductManual` at least defaults null→`'per_sqm'`. An edit that
  omits `priceUnit` or sends `"sqm"`/`"m2"` → 500. Fix: validate/normalize +
  null-default in `updateProduct`.
- **F2** — `TicketRepository.create:142` writes `priority` unvalidated
  (`chk_ticket_priority` LOW/NORMAL/HIGH). `TicketService.create` validates
  `entryChannel` right beside it but not `priority` — guard was forgotten. Fix: add
  a `Priority.isValid` guard mirroring entryChannel.
- **F3** — `ProfileRequestService.update:66` writes review `status` with only a
  non-blank check (`CHECK pending/approved/rejected`). `"Approved"` → 500; `"pending"`
  → passes CHECK but the code treats ≠approved as reject → **silent semantic drift**.
  Fix: constrain to `{approved,rejected}` (400 otherwise).

**Design note:** `payment_status` / `fulfillment_status` have **no** DB CHECK (free
VARCHAR); vocabulary is Java-enforced only. Clean today (all writes use constants),
but unprotected at the DB level. Also watch the `ADD COLUMN IF NOT EXISTS` "silent
no-op on an existing column" hazard (bit V39, fixed by V44).

**Mock contract test** (`contract.test.js`) is healthy and bidirectional but checks
**method-name parity only** — not DTO shapes, not authz — so it would not catch
F1/F2/F3 or the auth gaps in §3. `KNOWN_GAPS` is empty.

## 6. UAT data reset — DONE (targeted delete, 2026-07-18)
Chosen method: targeted delete (keep the V909 seed, remove tester rows). Backed up
row contents first, verified FK-safe order, executed one transaction on
`wuypxdznuhhluwzncafh` only (MAIN untouched):
- Deleted tester ticket `PR-2026-0001` (id 9) + children (1 item, 14 events via
  CASCADE; 1 deposit_notice + items) and the 6 tester quotations
  (`QN-2026-00002/10/11/12`, `QT-2026-0001/0002`) + 1 quotation_item. 5 of the 6
  quotations sat on seed tickets (removing them restores those tickets toward the
  fresh-DB seed; payable is derived, so no integrity break).
- **Post-state verified:** 14 tickets (all `UAT-TKT-*`), 14 distinct stages, 6
  quotations (all `QN-UAT-*`), 5 receipts, 3 deliveries, **0 orphans, 0 invalid
  statuses** — matches `assertUatDealPipelineSeed`.

## 7. Regression test plan

### A. Automated (gates every change — run on both branches)
- Backend: `cd backend && ./mvnw -B test` — unit (Mockito) + Testcontainers
  integration (`FlywayMigrationTest` runs V1–V54+V900–V909 on real Postgres and
  asserts the 14-stage seed; `TicketEventStatusIntegrationTest` guards the
  addEvent/status class of bug; `*RepositoryIntegrationTest` cover dynamic SQL).
  Requires Docker (or `TEST_DB_URL`), else integration tests skip.
- Frontend: `cd frontend && npm run lint && npm test && npm run build`.
- Current status: `uat`@7c1af4e 529 BE / 126 FE ✔; `main`@18c0ceb 512 BE / 123 FE ✔.

### B. Deal-pipeline behavior (the recently-changed surface) — verify on hosted UAT
Drive each as the seeded persona and confirm no 500 + correct transition:
1. Advance stage on a mid-pipeline deal (e.g. `UAT-TKT-06` AWAITING_BUYER→QUOTE_BUYER)
   — **the exact case that was crashing**; must succeed now.
2. mark-lost / hold / dormant / resume / reopen — all use the same event path; must
   not 500.
3. Goods-received on a PROCUREMENT deal → stage lands on `DELIVERY_SCHEDULING`
   (flowchart fix), not straight to DELIVERED.
4. Pay-in-full before delivery → deal does NOT reach `CLOSED_PAID` until
   `FULLY_DELIVERED` (two-gate fix). Full delivery then closes it.
5. Full stock coverage (`reserveStock`) → `DELIVERY_SCHEDULING`, skipping PROCUREMENT.

### C. Auth matrix (from §3 findings) — add negative tests
- Customer create as `employee` → expect 403 (currently **allowed** — F/gap #1).
- Attachment upload to a non-participant ticket → expect 403 (currently **allowed**
  — gap #2).
- `GET /api/employees/{id}` as non-HR for another employee → confirm scoping.
- Payroll/commission endpoints already `@PreAuthorize`-guarded — spot-check role denial.

### D. CHECK-constraint negative tests (from §5 — add before fixing F1/F2/F3)
- `updateProduct` with missing/`"sqm"` price_unit → currently 500 (should be 400).
- Create ticket with `priority:"urgent"` → currently 500 (should be 400 or default).
- Review profile-request with `status:"pending"` / `"Approved"` → drift / 500.

### E. Post-deploy smoke (both hosted stacks)
`GET /api/auth/me` → 401 unauth (health), login as a seeded persona → 200, list
`/api/tickets` → 200. UAT personas: `@uat.glr`, pw `Uat@2026`.

## 8. Open items / recommendations
1. Fix F1/F2/F3 (small, add input validation) — offered, not yet done.
2. Close auth gaps #1 (customer-create role) and #2 (attachment-upload access check).
3. Confirm Supabase Data API is disabled / anon key not public on the **real**
   project (`tdyzcqzxmhtxpbouewud`) given RLS is off (§4).
4. The next `gl-r-erp` (demo/main) deploy will migrate the real DB V47→V54 — expected
   and safe, but do it knowingly.
