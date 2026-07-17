# Live-fire API test — hosted UAT (writable) + MAIN (read-only smoke) — 2026-07-18

Scope: drive the real API surface over HTTP against the **hosted** stacks as each
seeded persona — reads + safe writes + auth/CHECK negatives on UAT (writable), and a
read-only smoke on MAIN (real payroll data, never mutated). Codebase at `uat`@7c1af4e.
Complements the static audit in `74_db-api-health-audit-and-test-plan.md` (that one
inventoried the surface + ran the local suites; this one live-fires the hosted DBs).

Method: `curl`/`urllib` against `https://gl-r-erp-uat.onrender.com` and
`https://gl-r-erp.onrender.com`. Session-cookie auth + Spring CSRF
(`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header, enforced on every unsafe method).
Personas `*@uat.glr` / `Uat@2026`. Harness in the session scratchpad
(`probe.py` / `run.py` / `writes.py`).

---

## 0. Headline finding (P0)

**The hosted UAT service is running a build OLDER than the fix commit `7c1af4e`.
Deal-pipeline stage/lifecycle mutations still crash live on hosted UAT** with the
exact `chk_ticket_status` bug that `7c1af4e` ("stop stage/lifecycle events corrupting
ticket status") fixed and that `74_...md` §1 reported as "now fixed".

Live proof (fresh DRAFT ticket, hosted UAT, as `sales`):

| Endpoint | Result | Failing row |
|---|---|---|
| `POST /api/tickets/{id}/hold` | **500** | `...violates check constraint "chk_ticket_status"` — status col = `LEAD_APPROACH` |
| `POST /api/tickets/{id}/stage` (→SPEC_APPROVED) | **500** | status col = `SPEC_APPROVED` |
| `POST /api/tickets/{id}/dormant` | **500** | status col = `LEAD_APPROACH` |
| `POST /api/tickets/{id}/resume` | 409 | (correct — hold above never took) |

The sales-stage value is landing in the `status` column — the pre-fix
`addEventInternal` behavior. **The fix is present and complete in the `uat` checkout**
(`TicketRepository.java:184` `TicketStatus.isValid(toStatus)` guard; line 190 is the
*only* writer of `ticket.status`, gated on `writeStatus`), and green in the local
Testcontainers suite (`TicketEventStatusIntegrationTest`). So this is **not a code
regression — it is a stale/failed hosted deploy**: the push carrying `7c1af4e` (+
`V909`) has not reached the running `gl-r-erp-uat` container. Consistent with the
memory note that the V909 deploy crashed hosted once (`ux_deposit_notice_ticket_version`);
a failed Render deploy keeps serving the previous image.

**Action for the user:** check the Render `gl-r-erp-uat` deploy log — confirm whether
the deploy for `7c1af4e` succeeded. Every deal-pipeline transition (stage / hold /
dormant / mark-lost / resume / policy-change — all `addEventInternal` pipeline events)
is currently broken on the live UAT stack until it redeploys. No data corrupts (the
failed UPDATE rolls back), but no deal can advance.

---

## 1. Read sweep — UAT (GET, as an authorized persona): 41/41 → 200 ✅

Every reachable GET across all 19 controllers returns 200 to an authorized persona.
Areas covered: Auth, Dashboard, Employee, Attendance, Leave, Overtime, Payroll,
Commission, Customer, Catalog, Price-import, FX, Price-calc-config, Factory-config,
Notification, Profile-requests, Deposit/Quotation (incl. PDF `/file` streams), Ticket
(list/detail/actions/payments/deliveries/quotation-file), Attachment list.

Four GETs first returned 403 — all **correct authorization**, re-probed 200 with the
right persona (not failures):

| Endpoint | 403 as | 200 as | Why the 403 is correct |
|---|---|---|---|
| `GET /api/commissions/payroll-ready` | ceo | **hr** | `@PreAuthorize("hasAnyRole('HR')")` — HR-only, not CEO |
| `GET /api/tickets/51/deposit-notices` | sales | **salesmgr/ceo** | own-deals-only: ticket 51's creator is id 7, `sales`=id 8 |
| `GET /api/deposit-notices/25` (+`/file`) | sales | **ceo** | same deposit access gate |

Seed-empty (200, no rows — expected, not a fault): `/api/leave`,
`/api/profile-requests`. No processed payroll period in the UAT seed, so
`/api/payroll/{periodId}/…` sub-resources have no id to probe.

---

## 2. Safe writes — UAT (CSRF-satisfied): mixed — see §0

| Endpoint | Persona | Result |
|---|---|---|
| `POST /api/tickets` (valid DRAFT) | sales | **200** ✅ |
| `POST /api/tickets/{id}/comments` | sales | **200** ✅ |
| `POST /api/tickets/{id}/cancel` | sales | **200** ✅ (used as cleanup) |
| `PATCH /api/notifications/{id}/read` | ceo | **204** ✅ idempotent |
| `POST /api/tickets/{id}/hold` / `/stage` / `/dormant` | sales | **500** ❌ (§0 stale deploy) |

Full write coverage of the ~90 mutation endpoints was **not** live-fired — that would
churn/corrupt the freshly-reset 14-stage seed, and those paths are covered by the
local Testcontainers integration suite. This run exercised a representative safe
subset on a throwaway ticket.

---

## 3. Negative tests — confirmed latent bugs from `74_...md` (live)

| Test | Expected | Live result | Verdict |
|---|---|---|---|
| **F2** — `POST /api/tickets` `priority:"urgent"` | reject | **500** `chk_ticket_priority` | ✅ **confirmed** — unvalidated priority reaches the CHECK column, fails closed |
| **Auth gap #1** — `POST /api/customers` as `employee` | 403 | **500** `null value in column "branch"` | ✅ **confirmed** — employee reached the customer INSERT with **no role check**; only a NOT-NULL DB error stopped it, not authz. (Also exposes: `CustomerController` doesn't validate required fields — missing `branch` → 500, not 400.) |
| `GET /api/employees` as `employee` | 403 | **403** | ✅ hr-only enforced |
| `POST /api/payroll/process` as `employee` | 403 | 400 (bean-validation ran first) | authz not reached; payroll GET as employee already 403 → gating OK |
| `POST /api/overtime/5/approve` as `hr` (#199) | 403? | 409 "already reviewed" | ⚠️ **inconclusive** — OT 5 is terminal; `approve` routes by state *before* the role check (`OvertimeService.approve`), so a **SUBMITTED** OT is needed to test HR-approve authz. Seed has none. |

CSRF: every unsafe method without `X-XSRF-TOKEN` → 403 "Invalid CSRF token". Good
posture; the mock does not model this, so mock-based verification misses it.

---

## 4. MAIN — read-only smoke (real payroll data, NOT mutated) ✅

| Probe | Result |
|---|---|
| `GET /actuator/health` | **200** UP |
| `GET /api/auth/me` | **401** (default-deny) |
| `GET /api/tickets` | **401** |
| `GET /api/employees` | **401** |

Default-deny is intact. No credentials used, no writes. (Per `74_...md`, MAIN/demo DB
is still at V47 — a separate, deliberate migration; out of scope for this test.)

---

## 5. Files changed
None (test-only; no source edited). New doc: this handoff.

## 6. Commands run
- Login × 9 personas, ID discovery, 41-endpoint read sweep, CSRF-aware write +
  negative matrix, hold/stage repro, MAIN smoke — all via the scratchpad harness.
- Code cross-checks: `git show 7c1af4e`, `TicketRepository.java` (status writer),
  `OvertimeService.approve`, `CustomerController`.

## 7. Tests / build results
- No local build run this cycle (see `74_...md`: `uat`@7c1af4e 529 BE / 126 FE ✔;
  `main`@18c0ceb 512 BE / 123 FE ✔). This run tested the **hosted** stacks over HTTP.

## 8. Known risks / residue
- **Stale hosted UAT deploy (§0)** — highest priority; deal pipeline broken live.
- **UAT residue from this run:** throwaway tickets **53 & 54** (both `CANCELLED`,
  titled `ZZ … DELETE / safe to delete`). No customers/quotations created (those
  attempts rolled back). Sweep on the next UAT reset. Hard-delete is prohibited/absent.
- Auth gap #1 and F2 are now **live-confirmed**, not just static findings.

## 9. Exact next prompt for the next agent
> Verify the `gl-r-erp-uat` Render deploy for commit `7c1af4e` actually succeeded and
> is the running image. If it failed (likely on a `V909`/migration step), fix the
> deploy so the running UAT build includes the `addEventInternal`/`chk_ticket_status`
> fix, then re-run the deal-pipeline live checks in
> `75_live-fire-api-test-uat-main.md` §0 (`hold`/`stage`/`dormant` on a fresh DRAFT
> ticket must return 200, not 500). Separately, close auth gap #1 (add a role check +
> field validation to `CustomerController` create endpoints) and fix F2
> (`Priority.isValid` guard in `TicketService.create`). Do not touch MAIN.
