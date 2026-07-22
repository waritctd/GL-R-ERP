# Agent Handoff

## Task
Manual commission entries: sales_manager/CEO can hand-type a signed commission amount
(ADJUSTMENT / MANAGER / STOCK_BONUS / INCENTIVE) against a rep's payroll month, with a required
reason, no invoice, and no tier-calc involvement. Approved-manual amounts feed the rep's payroll
total on top of the tier commission (never touching the tier calc itself). Owner decision: **all
four kinds are manual for now** — no auto-computation anywhere.

This handoff file did not exist when the frontend agent started (highest existing file in this
folder was `99_...md`); the backend below was already implemented and committed-to-disk on this
branch (uncommitted, per `git status`) with no accompanying handoff. This file was created from
scratch, documenting the pre-existing backend from a read of the code, then the frontend section
below.

## Branch
`feat/commission-manual-adjustments`

## Base Commit
`8fb916d` (perf(test): run backend suite in parallel forks) — branch tip at the time this file
was created; the commission-manual-adjustments changes below are uncommitted working-tree changes
on top of it (see `git status`).

## Current Commit
Not committed (per CLAUDE.md: do not commit unless asked). Working tree only.

## Agent / Model Used
Claude Sonnet 5 (frontend). Backend author unknown (pre-existing on branch, no handoff left
behind).

## Scope

### In Scope (frontend, this session)
- `POST /api/commissions/manual` API wiring (routes/hrApi/mockApi/contract parity).
- `CommissionPage.jsx`: manual-entry create form (sales_manager/ceo only), kind picker with Thai
  labels, manual entries shown in the existing commission list/expand/mobile-card views, reusing
  the existing approve/reject controls, sales read-only total.

### Out of Scope (not touched)
- Backend Java (already done on this branch before this session; verified by reading, not
  modified).
- Any auto-computation of a manual amount (owner-confirmed: manual across the UI for now).
- Track-B / deal-workspace code.

## Backend (pre-existing on this branch — summarized, not authored by the frontend agent)
- `CommissionKind.java`: adds `ADJUSTMENT`, `MANAGER`, `STOCK_BONUS`, `INCENTIVE` alongside
  existing `SALE`/`CLAWBACK`.
- `ManualCommissionRequest.java` (new): `{ salesRepId, kind, amount, reason, payrollMonth }`,
  `@NotNull`/`@NotBlank` on all but `payrollMonth` (defaults to current business month).
- `CommissionController#createManual`: `POST /api/commissions/manual`,
  `@PreAuthorize("hasAnyRole('SALES_MANAGER','CEO')")`.
- `CommissionService#createManualCommission`: validates role (`MANUAL_CREATE_ROLES`), kind
  (`MANUAL_KINDS` = the four above), amount present, reason non-blank, and rejects a negative
  `MANAGER`-kind amount (no sign restriction on `ADJUSTMENT`/`STOCK_BONUS`/`INCENTIVE` at the
  backend — the frontend form additionally restricts STOCK_BONUS/INCENTIVE to non-negative
  client-side per the owner's UI spec). Created by `sales_manager` → status `MANAGER_APPROVED`;
  by `ceo` → `APPROVED` directly. From `MANAGER_APPROVED`, the existing `approve()` chain (CEO)
  carries it to `APPROVED` — no parallel approval path.
- `CommissionRepository#createManualCommission`: two INSERT branches (ceo vs manager), `invoice_id`
  and `source_ticket_id` NULL, `actual_received`/`commissionable_base` = 0, `manual_amount`/
  `manual_reason` set. `RECORD_SELECT`'s LEFT JOIN yields `invoiceDetails() == null` for these rows.
- `CommissionRecord.java`: adds `manualAmount`/`manualReason` (both null for SALE/CLAWBACK).
- `SalesRepCommissionSummaryDto.java`: adds `manualAdjustmentAmount` (sum of approved manual
  amounts for the rep this month, already folded into `commissionAmount`).
- `CommissionService#payrollReadySummary`: accumulates manual-kind APPROVED records into a
  separate `manualTotals` map (never into the tier base), adds it onto each rep's tier commission
  for the final total, and synthesizes a summary row for a rep whose *only* approved commission
  this month is a manual entry.
- `V84__commission_manual_entries.sql`: new migration (schema for the above; not read in detail
  by the frontend agent beyond confirming it exists).
- `ManualCommissionIntegrationTest.java` (new, real Postgres via `AbstractPostgresIntegrationTest`):
  wrong-way-round authz (sales/plain-employee → 403, zero rows), positive manager+ceo creation,
  positive/negative ADJUSTMENT feeding payroll on top of tier commission, STOCK_BONUS/INCENTIVE
  parity, MANAGER-negative rejection, blank-reason rejection, MANAGER-kind-for-the-manager
  synthesized summary row, and "manual never feeds the tier calc" (verified via
  `sumActiveWeightedActualReceived`).

## Files Changed (frontend, this session)
- `frontend/src/api/routes.js`: added `commissions.manual` route path; added
  `ROLE_PERMISSIONS.canCreateManualCommission: ['sales_manager', 'ceo']` (own key, same role set
  as `canApproveCommissions` today but independently defined server-side).
- `frontend/src/api/hrApi.js`: added `commissions.createManualCommission(payload)` — plain JSON
  POST to `/api/commissions/manual`.
- `frontend/src/api/mockApi.js`:
  - `buildCommissionRecord`: now returns `invoiceDetails: null` for a manual-kind record (mirrors
    the backend's LEFT JOIN yielding a null `invoiceDetails()`), instead of defaulting to a stub
    invoice object.
  - New `MANUAL_COMMISSION_KINDS` / `isManualCommissionKind()`.
  - New `commissions.createManualCommission(payload)`: role gate (`sales_manager`/`ceo` via
    `hasRole`, approximating the Java `@PreAuthorize` — **not authoritative**, see Authz Evidence
    below), kind/amount/reason validation, MANAGER-negative rejection, sets status
    `MANAGER_APPROVED` (manager-created) or `APPROVED` (ceo-created) with the matching
    approver fields, mirrors `CommissionRepository#createManualCommission`'s two INSERT branches.
  - `commissions.payrollReady`: reworked to accumulate manual-kind APPROVED records into a
    separate `manualTotals` map, add it onto each rep's tier commission (never into
    `commissionableBase`), add `manualAdjustmentAmount` to each summary row, and synthesize a row
    for a manual-only rep — mirrors `CommissionService#payrollReadySummary` exactly.
- `frontend/src/features/commissions/CommissionPage.jsx`:
  - `MANUAL_KIND_LABELS` (Thai labels for the 4 kinds) / `MANUAL_KINDS` / `isManualKind()`.
  - `kindLabel()` extended to cover the manual kinds.
  - `canCreateManual` permission flag; `showManualForm`/`manualForm`/`manualSaving`/`repOptions`
    state; a best-effort rep picker sourced from `api.tickets.list({})` (sales_manager/ceo have no
    `/api/employees` access — that's hr-only — so this is a convenience list, not authoritative;
    the numeric Employee ID field is always the primary path, same UX pattern as the existing
    account "record invoice" ticket lookup).
  - `submitManual()`: client-side validation (salesRepId, amount, MANAGER/STOCK_BONUS/INCENTIVE
    non-negative, required reason) then `api.commissions.createManualCommission(...)`. No
    auto-computation anywhere — every amount is typed (a comment notes a future CEO-confirmed
    auto-config may prefill suggestions later; not implemented).
  - New `ManualCommissionForm` component (kind picker, Employee ID input + convenience dropdown,
    amount with a negative-allowed hint for ADJUSTMENT only, payroll month, required reason).
  - "เพิ่มค่าคอมด้วยตนเอง" button added to the page header actions (sales_manager/ceo only).
  - Commission list/expand/mobile-card/approve-confirm-dialog all branch on `isManualKind(kind)`:
    manual rows show a "Manual" `StatusBadge`, the kind label, and the reason instead of an
    invoice number; the amount column shows `manualAmount` (red when negative) instead of
    `actualReceived`; the base column shows "ไม่มีฐานคำนวณ" instead of `commissionableBase`; the
    "แก้ไขค่าหัก" (edit deductions) pencil button is hidden for manual rows (no invoice fields to
    edit). Manual rows flow through the *same* approve/reject buttons and status badges as SALE
    rows — no parallel review UI.
  - `monthlyTierSummary` (sales-only estimate panel): now also sums the rep's own APPROVED manual
    amounts and adds them on top of the tier commission for `summary.total`, with a new line
    "ค่าคอมปรับปรุง/โบนัสที่อนุมัติแล้ว (นอกขั้นบันได)" — mirrors
    `CommissionService#payrollReadySummary`'s manual-on-top-of-tier behavior for the rep's own
    view. Manual entries are excluded from the weighted tier-base sum itself (they contribute 0
    either way since `actualReceived` is 0, but the filter is explicit for clarity).

## Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
# Manual mock self-check (see Test / Build Results below) via a throwaway
# VITE_USE_MOCKS=true vite dev server on port 5201, browser-driven.
```

## Test / Build Results
- Lint: **pass** (0 errors; 1 pre-existing unrelated warning in `PayrollPage.jsx`, untouched by
  this change).
- Frontend tests: **pass** — 48 test files, 422 tests, including `src/api/contract.test.js`
  (hrApi/mockApi method-name parity) with no changes needed there since both files gained
  `createManualCommission` together.
- Frontend build: **pass** (`vite build`, 172ms, no warnings).
- Backend: **not run** by this (frontend-only) session — CLAUDE.md scopes this task to frontend;
  the backend integration test (`ManualCommissionIntegrationTest`) was read, not executed.
- Mock self-check (`VITE_USE_MOCKS=true`): **done**, browser-driven, on a throwaway dev server
  (port 5201; the pre-existing server on port 5200 turned out to be a stale/different process —
  see Known Risks):
  1. Logged in as `sales_manager`, opened "เพิ่มค่าคอมด้วยตนเอง", created a `STOCK_BONUS` of
     ฿1,500 for employee id 6 (คุณสมหมาย ขายดี) — landed `MANAGER_APPROVED` ("รอ CEO").
  2. Created an `ADJUSTMENT` of -฿750 for the same rep — landed `MANAGER_APPROVED`, shown in red,
     hint text correctly said negative-allowed only for this kind.
  3. Logged in as `ceo` — dashboard "ค่าคอมมิชชั่นรออนุมัติ: 2" picked up both manual entries;
     commissions page showed both with the *existing* approve/reject buttons (no parallel UI);
     the approve `ConfirmDialog` correctly branched to a manual-specific message (kind label,
     signed amount, reason) instead of the invoice-shaped one. Approved both.
  4. Logged in as `hr` → payroll-ready summary for July 2026: `คุณสมหมาย ขายดี` showed base ฿0,
     commission **฿750** (1,500 − 750, confirming the manual amounts net correctly on top of a
     ฿0 tier base).
  5. Logged in as `sales` (คุณสมหมาย ขายดี herself): saw both approved manual entries in her list
     with correct badges/reasons/amounts, the tier panel showed "ค่าคอมประมาณการ ฿750" with the
     new "ค่าคอมปรับปรุง/โบนัสที่อนุมัติแล้ว ฿750" breakdown line, and — confirmed via the
     accessibility tree — **no** "เพิ่มค่าคอมด้วยตนเอง" button and **no** approve/reject/edit
     controls anywhere on the page.

## Authz Evidence
UNVERIFIED (frontend) — mock-only (`VITE_USE_MOCKS=true`). The mock's role gate on
`createManualCommission` (`hasRole('sales_manager', 'ceo')`) approximates
`CommissionController#createManual`'s `@PreAuthorize` and is commented as such, but is not
authoritative per CLAUDE.md. The real boundary is `ManualCommissionIntegrationTest` (pre-existing
on this branch, real Postgres, wrong-way-round: sales/plain-employee → 403 with zero rows
created) — this frontend session read that test but did not re-run it (no backend build was run
in this frontend-only session; note in Test/Build Results above).

## Decisions Made
- Reused `ROLE_PERMISSIONS.canApproveCommissions`'s role set for a new, separately-named
  `canCreateManualCommission` key rather than reusing the existing key directly, since the two
  backend gates (`@PreAuthorize` on `/manual` vs. on `/approve`+`/reject`) are defined
  independently server-side and could diverge later.
- No dedicated "list sales reps" endpoint exists that `sales_manager`/`ceo` can call (that's
  hr-only via `/api/employees`), so the rep picker is a best-effort convenience dropdown sourced
  from `api.tickets.list({})` (deduped by `createdById`/`createdByName`), with a required numeric
  Employee ID field as the actual authoritative input — same UX pattern already used by
  `AccountCreateFromDeal`'s ticket lookup.
- Frontend additionally restricts `STOCK_BONUS`/`INCENTIVE` to non-negative amounts client-side,
  per the task's explicit UI spec, even though the backend only enforces the sign restriction for
  `MANAGER`. This is a UI-only tightening, not a contract mismatch — the backend would still
  accept a negative `STOCK_BONUS`/`INCENTIVE` if sent directly.
- `buildCommissionRecord` (mock) now returns `invoiceDetails: null` for any manual-kind record,
  matching the backend's LEFT JOIN behavior exactly, rather than defaulting to a stub invoice
  object — this was necessary for `CommissionPage.jsx`'s null-safe branches to be meaningfully
  exercised in mock mode (otherwise the mock would silently diverge from the real backend's shape
  here).

## Assumptions
- The backend section above is inferred entirely from reading the pre-existing, uncommitted
  working-tree code and its integration test; it was not written or reviewed by this session's
  agent, and no prior handoff existed to cross-check against.
- `V84__commission_manual_entries.sql` was not read line-by-line; only confirmed to exist.

## Known Risks
- **Stale dev server gotcha (re-confirmed this session)**: port 5200 was already occupied by a
  `node` process serving what turned out to be different/older code (an account-style "record
  invoice" flow) rather than this branch's current `CommissionPage.jsx` — consistent with the
  `concurrent-session-worktree-collision` memory note. This session did not touch or kill that
  process; verification used a fresh dev server on port 5201 instead. If another agent/session is
  still relying on port 5200, be aware it is not serving this branch's frontend.
- Disk space on this machine was critically low during this session (as low as ~286Mi free at one
  point, recovering to ~500Mi later) — the Bash tool intermittently failed with `ENOSPC`. All
  commands eventually succeeded on retry, but this is worth flagging to the user independent of
  this task; a prior session ("Health audit + branch roles") hit the same 98%-full condition.
- The frontend's MANAGER/STOCK_BONUS/INCENTIVE non-negative restriction is enforced only in the
  form's `submitManual()` validation, not in `mockApi.js`'s `createManualCommission` (which only
  rejects negative `MANAGER`, matching the real backend). This is intentional (mirrors the real
  contract) but means a caller bypassing the form (e.g. a future bulk-import feature) would not be
  blocked from a negative STOCK_BONUS/INCENTIVE by the mock either — same as production.

## Things Not Finished
- Backend was not modified or re-verified this session (out of scope per the task).
- No dedicated `CommissionPage.test.jsx` exists in this repo (the whole page has been verified via
  the existing 422-test suite passing, `contract.test.js` parity, and the manual browser
  self-check above) — adding component-level tests for the new form was not requested and was not
  added.

## Recommended Next Agent
Claude Opus review (per the "Sonnet-implements / Opus-reviews" standing loop) — verify the
frontend changes against the pre-existing backend contract, and independently confirm the authz
mock-vs-real-service gap noted above is acceptable to merge as-is (no frontend authz change was
made; the backend authz evidence already exists via `ManualCommissionIntegrationTest`).

## Exact Next Prompt
```
Review branch feat/commission-manual-adjustments end to end (backend + frontend) against
docs/agent-handoffs/106_feat-commission-manual-adjustments.md. Confirm: (1) the backend
(CommissionController/Service/Repository, V84 migration, ManualCommissionIntegrationTest) matches
the handoff's Backend summary and the integration test actually passes against real Postgres;
(2) the frontend (routes.js/hrApi.js/mockApi.js/CommissionPage.jsx) matches the mock/UI summary
and mock self-check described; (3) run `cd backend && ./mvnw -B clean verify` and
`cd frontend && npm run lint && npm test && npm run build` fresh and record results. If all green,
this branch is ready for a PR against main.
```
