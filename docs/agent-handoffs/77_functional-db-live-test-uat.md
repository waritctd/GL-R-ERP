# Functional DB live test — hosted UAT (all endpoints, API↔DB) — 2026-07-18

Scope: drive the API surface against **live hosted UAT** (`gl-r-erp-uat`, build `bd9b4bb`)
and assert the **database actually changed correctly** via Supabase SQL
(`wuypxdznuhhluwzncafh`). Follows the read-only live-fire in `75_...md` — this one adds
writes + DB-state verification for every reachable endpoint. MAIN untouched.

## 0. Deploy health — the stale UAT deploy is FIXED ✅
The `uat` merge (`bd9b4bb`, PR #234) triggered a Render redeploy that **succeeded**,
finally carrying live BOTH the new fixes AND the previously-stale deal-pipeline fix
(`7c1af4e`) that `75_...md` §0 flagged. Verified live on a fresh DRAFT ticket:
`hold`/`resume`/`stage`/`dormant` → **200** (were 500), and DB shows `status='draft'`
(valid) with `sales_stage` moving independently — the `chk_ticket_status` corruption is
gone. Deal 59 drove all 7 lifecycle transitions (stage/hold/resume/dormant/lost/reopen/
comment); DB: `status=draft`, `sales_stage=SPEC_APPROVED`, 9 events — all persisted correctly.

## 1. PR #233/#234 fixes — verified live + at DB level ✅
| Fix | API | DB assertion |
|---|---|---|
| F2 priority guard | `POST /api/tickets` priority `urgent` → **400** | no row written |
| gap#1 customer authz | `employee POST /api/customers` → **403** | no row written |
| branch default | `sales` create w/o branch → **200** | row `branch='สำนักงานใหญ่'` |

## 2. Deal lifecycle — full happy path, DB-verified ✅
A throwaway deal (create-with-items → auto-submit → pickup → propose-price → approve →
quotation → sent → accepted → confirm-customer → deposit draft/issue/paid → import-request
→ ir-sent → shipping → goods-received → reserve-stock → delivery → payments → billing →
final-payment) drove end-to-end. **DB final state:** `status=quotation_issued` (valid),
`sales_stage=CLOSED_PAID`, `payment_status=FULLY_PAID`, `fulfillment_status=FULLY_DELIVERED`,
with **29 ticket_events, 1 quotation, 1 deposit_notice, 3 payment_receipts, 1 delivery_record**
all persisted. Every mutating endpoint's write reached the DB with correct values.

`calculate-prices`, `price-override`, `deposit-policy` were then driven on a deal at
`price_proposed` as CEO → 200; DB confirmed `proposed_price=299` (manual override),
`deposit_policy=CREDIT_CUSTOMER`.

## 3. Endpoint coverage (19 controllers / 136 endpoints)
**Reads (41 GET):** all 200 to an authorized persona; API counts reconciled to DB —
employees 93=93, customers 14=14 (seed), commissions 4=4 **exact**; tickets/product_prices
differ only by pagination / test-timing (explained, not a mismatch).

**Writes fired + DB-verified:** all ticket lifecycle + pipeline endpoints; deposit
draft/issue; quotation generate/sent/accepted; payments/billing/final; reserve-stock/
deliveries; leave submit/cancel; overtime submit; profile-request create/approve;
customer/contact/project create; catalog price **add→update→delete** (full CRUD); fx upsert;
price-calc-config create; notification read. Each asserted against its table.

**Authz/state enforcement confirmed (correct 4xx, not bugs):** `calculate-prices`
CEO-only + `price_proposed`-only; `deposit-policy`/`billing` reject `sales`; `price-override`
`price_proposed`-only; `employee`→`/employees` 403; CSRF required on every unsafe method.

**Not fired (with reason, no DB assertion):**
- `POST /tickets/{id}/factory-emails/send` — sends a real email; needs explicit user OK.
- `commission` create — requires a multipart tax-invoice **file** (JSON → 400 by design).
- `employee` create/patch/reset-password — heavy DTO / destructive; read side verified.
- `payroll` process/distribute/bank-export, `attendance` agent-token/imports/backfill,
  `price-import` upload/validate/commit — need files/devices/real-payroll side effects.
- `attendance/punch` needs `siteCode`/`badgeCode`/`punchTime`; `payroll/preview` needs `inputs`.

## 4. Finding — F3 is NOT a live bug (corrects `74_/75_` §5-F3)
`74_...md`/`75_...md` predicted `PATCH /api/profile-requests/{id}` with `status:"Approved"`
→ 500 (and `"pending"` → silent drift). **Live result: 400** "status must match
`approved|rejected`". `UpdateProfileRequestRequest` carries `@Pattern(regexp="approved|
rejected")` on `status`, so the invalid value is rejected at bean-validation before the
service — the 500 / drift cannot occur via the API. The valid path (`"approved"`) → 200,
DB row `status=approved`. **F3 needs no fix.** (F1 — `PriceImportService.updateProduct`
`price_unit` — was not exercised: its entry point is the xlsx import/commit path, not fired.)

## 5. UAT data — restored to clean seed ✅
All throwaway rows removed (FK-safe): 9 tickets + children, 2 customers + contacts/projects,
leave/overtime/profile-request rows, 2 price_calc_config rows, catalog test product, and
stale `hr.notification` rows. **Post-state:** 14 tickets, 14 customers, 0 invalid
priority/status, 0 null branch, 0 leftover notifications.
- **Restored:** the `fx_rates` USD row — the `PUT /fx-rates/USD` probe **overwrote** it
  (the repo upserts by currency, one row each), so it was reset to the seed value
  `35.2 @ 2026-07-15`. **Lesson:** `fx-rates`/`price-calc-config` upserts mutate shared
  reference rows in place — restore them after any live write test.
- **Unavoidable side effect:** creating deals with items fires `notifyByRole` →
  `NotificationEmailService` (Mailer with override-to), so test emails went to the UAT
  override inbox (never real factories). Cannot be un-sent; noted for awareness.

## 6. Files changed / commands
None (test-only). Harnesses in the session scratchpad: `funcdb.py`, `funcdb2.py`,
`funcdb3.py`; DB verification + cleanup via Supabase MCP `execute_sql`.

## 8. MAIN (real/demo stack) — read-only run
MAIN is real payroll prod: **no writes**, and no login creds available, so this is
DB-level integrity + hosted API smoke only (writes/authenticated-reads intentionally
excluded).
- **Hosted `gl-r-erp.onrender.com`:** up; default-deny intact — `/api/auth/me`,
  `/api/tickets`, `/api/employees`, `/api/catalog/prices` all **401** unauthenticated.
- **DB `tdyzcqzxmhtxpbouewud`:** `chk_ticket_priority` + `chk_ticket_status` + `branch`
  NOT-NULL default all present; **0 bad priority / 0 bad status / 0 null branch**; **0
  failed migrations**. Counts: 8 tickets, 213 employees, 4 customers.
- **⚠️ Migration state: latest applied = V47 (2026-07-15); V48–V54 NOT applied.** The
  `gl-r-erp` stack has **not** redeployed with main's merged work — the F2/gap#1 fixes and
  the entire deal-pipeline (V50–V54) are **not live on the real/demo stack**; it still runs
  pre-fix code. This is the deliberate real-DB migration `74_...md` §8 flagged.
  **Action for the user:** decide when to let the `gl-r-erp` deploy migrate the real DB
  V47→V54 (additive columns/tables + a V50 backfill over 8 tickets — safe but deliberate).

## 7. Result
Live hosted UAT is **healthy and behaviourally correct** across the exercised surface: the
deal engine drives to close, every write persists correctly, authz/state gates hold, the
`chk_ticket_status` corruption is fixed and deployed, and PR #233/#234's fixes are live.
No new defects. F3 downgraded to non-issue. Only F1 remains unverified (needs the xlsx
import path) and is the sole open item from the CHECK-constraint audit.
