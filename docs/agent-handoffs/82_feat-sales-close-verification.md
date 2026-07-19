# 82 — feat/sales-close-verification

**Branch:** `feat/sales-close-verification` — **stacked on `fix/sales-transition-gates`**, not on
`main`. Merge 81 first. · **Date:** 2026-07-19 · **Status:** implemented, verified, committed — not pushed

## Why

Handoff 81 tightened the close gate to require FULLY_DELIVERED. On review the business owner
gave the actual definition of "closed", which is stricter than anything the code did:

> closed = goods delivered to the customer, remaining payment received, **account uploads the
> invoice**, **CEO verifies** — ถึงจะนับว่างานปิด

Closing was a single unilateral action by the **sales owner**, with no invoice requirement and
no CEO involvement. This branch implements the three-party sequence.

Decisions confirmed with the owner before building:
- The invoice is an **uploaded file** (produced by an external vendor system). The gate checks
  that one exists, not its contents.
- Order is **account confirms → CEO verifies**.
- The CEO **verifies, never overrides** — no force-close of an incomplete deal.

## What changed

**Sales can no longer close deals.** That is the direct consequence of the chosen order and is
the single most user-visible change here.

### Flow
`FULLY_DELIVERED + FULLY_PAID + outstanding ≤ 0 + INVOICE attachment`
→ ฝ่ายบัญชี `confirmCloseReady` → CEO `verifyClose` → `status=closed`, `lifecycle=COMPLETED`.

`revokeCloseConfirmation` (account **or** CEO) withdraws a confirmation before the CEO acts.

### Two-signature integrity
`CLOSE_CONFIRM_ROLES = {account}` deliberately **excludes ceo**, unlike `ACCOUNT_ROLES`
(`{account, ceo}`). The CEO signs the second half; letting them sign the first would collapse
two signatures into one person. Same reasoning as `CommissionService`'s manager→ceo chain, and
commented as such so it doesn't get "fixed" later.

`verifyClose` **re-checks every prerequisite**. A deal that regresses between the two signatures
(refund, returned delivery, deleted invoice) cannot ride through on a stale confirmation.

### Legacy deals
Pre-dual-track tickets (`status=document_issued`, `payment_status` never set) predate the
delivery and invoice tracks, so those two prerequisites are waived for them — but they still
need both signatures. Requiring an invoice would have stranded that data permanently.

## Files changed

| File | Change |
|---|---|
| `V55__close_verification.sql` | + `close_confirmed_by/at` on `sales.ticket`, partial index for the CEO queue, `chk_event_kind` re-declared with `CLOSE_CONFIRMED` / `CLOSE_CONFIRM_REVOKED`. No backfill (closed deals keep NULL — attributing a confirmation to an accountant who never gave one would be a lie). |
| `AttachType.java` | new — PO / SIGNED_QUOTATION / **INVOICE** / OTHER |
| `TicketService.java` | `close()` → `requireClosePrerequisites` + `confirmCloseReady` / `revokeCloseConfirmation` / `verifyClose`; `canClose` → three predicates; `CLOSE_CONFIRM_ROLES` |
| `TicketController.java` | `POST /{id}/close` → `close/confirm`, `close/revoke`, `close/verify` |
| `TicketRepository.java` | `confirmClose`, `clearCloseConfirmation`, `hasInvoiceAttachment`; summary carries the new fields |
| `TicketSummaryDto.java` | + `closeConfirmedAt`, `closeConfirmedByName`, `invoiceOnFile` |
| `TicketEventKind.java` | + 2 kinds |
| `hrApi.js` / `mockApi.js` | mirrored (contract.test.js enforces parity) |
| `TicketDetailPage.jsx` | new primary actions, revoke button, dedicated **แนบใบกำกับภาษี** upload, waiting-for-CEO hint, invalidation fixes |
| `useHrData.js` | `resetData` → `queryClient.clear()` (see below) |

## Results

- **Backend 529 pass**, 0 failures. Integration tests **ran** on Testcontainers (not skipped);
  V55 applies cleanly in the full V1..V55 + V900..V909 chain.
- **Frontend 191 pass**, lint 0 errors (4 pre-existing warnings), build clean.

### Two bugs found by verification that tests did not catch

**1. `GROUP BY` (caught by Testcontainers, invisible to Mockito).** The summary SELECT
aggregates `COUNT(item_id)`, so the new columns had to join the `GROUP BY`. Mockito stubs the
repository and never sees the SQL — exactly the failure class the repo was bitten by before.
A second Postgres error followed: `hr.employee` uses `first_name_th`/`last_name_th`, not
`first_name`/`last_name` (that's `customers.contact`).

**2. Role-scoped data leaked across logout — pre-existing, not introduced here.** After the
account confirmed, the CEO's page showed **no verify button**. Console instrumentation proved
the mock's `actions` endpoint was **never called** for the CEO: react-query keys are scoped by
entity id, never by user, and `resetData()` cleared only an enumerated list of HR keys. Every
sales/ticket key survived logout, so **the CEO was served the accountant's `availableActions`
for the same deal** — the wrong role's workflow buttons. `resetData` now does
`queryClient.clear()`; an allowlist has to be updated for every new query and forgetting is
silent. **This affected all cached ticket data, not just this feature.**

Also fixed: uploading/deleting an attachment did not invalidate the actions query, so the
confirm button only appeared after navigating away and back.

### Browser verification (mock, full three-party walk)

| Step | Result |
|---|---|
| import records ส่งมอบครบ → 800/800 | ✓ import offered no close action |
| account, before invoice | ✓ confirm **blocked** |
| account uploads ใบกำกับภาษี | ✓ confirm appears immediately (no manual refresh) |
| account confirms | ✓ deal **not** closed; revoke appears |
| CEO | ✓ sees ตรวจสอบและปิดงาน |
| CEO verifies | ✓ ปิดแล้ว, lifecycle COMPLETED, **both signatures in the audit trail** |

## Known risks

- **Sales reps lose the ability to close deals.** Intended, but it needs telling before deploy.
- **`POST /{id}/close` is removed** — a breaking API change. Nothing else in the repo calls it,
  but any external caller would 404.
- `queryClient.clear()` on logout drops all cached data. Correct, marginally more refetching.
- **Not yet driven against the real backend** — the walk was on the mock, whose authz is not
  authoritative. The Java role checks are unit-tested (164 tests) but the end-to-end sequence
  should be re-run against a real deploy before release.
- No uat seed touched, but V909 seeds closed deals; V55 adds nullable columns only.

## Next prompt

> Continue on a fresh branch `feat/sales-cancel-reason` off `main` (rebase after 81 and 82 land).
> Read handoffs 81 and 82 first. `TicketService.cancel` takes only `(ticketId, actor)` — no
> reason, null event message — so a cancelled deal carries zero explanation, unlike CLOSED_LOST.
> Add **V56** with `cancel_reason` + `cancelled_at` on `sales.ticket` + a CHECK; add
> `DealCancelReason` mirroring `DealLostReason.java` (`OWNER_CANCELLED`, `PROJECT_SUSPENDED`,
> `BUDGET_CANCELLED`, `OTHER`); make it mandatory as `markLost` does. Thread through controller,
> service, repository, `mockApi.js` and a modal copying `MarkLostModal.jsx`. Do not touch the
> quotation model (Tier 3, still unscoped).
