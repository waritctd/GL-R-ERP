# 83 — feat/sales-cancel-reason

**Branch:** `feat/sales-cancel-reason` — **stacked on `feat/sales-close-verification`** (82),
which is stacked on `fix/sales-transition-gates` (81). Merge 81 → 82 → 83.
**Migration:** V56 (V55 belongs to 82) · **Date:** 2026-07-19 · **Status:** committed, not pushed

## Why

Marking a deal LOST has required a structured reason since V50 (`lost_reason`/`lost_at`, the
F1–F8 sheet). Cancelling recorded **nothing**: no column, and `addEvent(..., null)` hardcoded a
null message. A cancelled deal carried zero explanation.

Cancelled is deliberately **not** folded into lost. Lost is a competitive outcome — beaten on
price, spec, lead time. Cancelled means the opportunity itself disappeared (owner pulled the
project, budget withdrawn), which says nothing about how GL&R competed. Merging them would
poison win/loss reporting with deals that were never winnable.

## What changed

- **V56**: `cancel_reason VARCHAR(40)` + `cancelled_at` on `sales.ticket`, CHECK on the four
  codes. Nullable, **no backfill** — deals cancelled before this have no recorded reason and
  inventing one would be a fabrication; they read NULL, which is honest.
- `DealCancelReason` mirroring `DealLostReason`: `OWNER_CANCELLED`, `PROJECT_SUSPENDED`,
  `BUDGET_CANCELLED`, `OTHER`.
- `cancel(ticketId, reason, note, actor)` — **reason mandatory**, matching `markLost`. An
  optional one gets skipped in practice and the gap stays open.
- Frontend: `CancelDealModal` (copy of `MarkLostModal`'s reason picker) replaces the bare
  yes/no `ConfirmDialog`. A confirm dialog cannot capture *why*, and cancel is irreversible.

### Deliberately NOT changed
`cancel` still has **no `requireRole`** — ownership by `createdById` is the only gate, as
before. Tightening that is an authz decision on its own merits, not something to slip in
alongside a reason column. Noted in the javadoc so it stays visible.

### Incidental fix
The mock's `cancel` had **no ownership check** while the Java service always did — the mock was
*more permissive* than production, the direction CLAUDE.md calls out as dangerous (issue #199
was exactly this). Added.

## Files changed

`V56__cancel_reason.sql` (new) · `DealCancelReason.java` (new) · `TicketService.cancel` ·
`TicketController` (+`CancelRequest`) · `TicketRepository.cancelDeal` + summary wiring ·
`TicketSummaryDto` (+`cancelReason`, `cancelledAt`) · `hrApi.js` · `mockApi.js` (reason +
owner gate + summary fields) · `stageMeta.js` (+`CANCEL_REASONS`) ·
`CancelDealModal.jsx` (new) · `TicketDetailPage.jsx` · `TicketServiceTest`

## Results

- **Backend 531 pass**, 0 failures. Integration tests ran on Testcontainers; V56 applies clean
  in the full V1..V56 + V900..V909 chain.
- **Frontend 191 pass**, lint 0 errors (4 pre-existing warnings), build clean.
- New tests: reason is mandatory (null / empty / unknown code / **a lost code rejected as a
  cancel code** — the vocabularies are distinct), and reason+note both persist.

### Browser verification (mock, `sales` persona)
Modal opens with all 4 reasons; submit **disabled until a reason is picked**; after picking
`PROJECT_SUSPENDED` and typing a note, the deal cancels and the audit trail reads
`ยกเลิกดีล (PROJECT_SUSPENDED) — รอผลอนุมัติงบรอบหน้า`.

⚠️ **Harness note for whoever verifies next:** the page has **two** textareas — the deal's
comment box and the modal's note field, and `document.querySelector('textarea')` returns the
*comment box*. Two earlier runs looked like a "note not saved" bug that was really the harness
typing into the wrong element. Scope to `[role=dialog] textarea`.

## Known risks

- `POST /{id}/cancel` now **requires a body** with a valid `reason` — breaking for any external
  caller. Nothing in the repo calls it without one.
- Historical cancelled deals show no reason. Intended.

## Next

Branch C (`feat/sales-audit-trail`, V57) — preserve `lost_reason` across reopen, and add
document linkage to `ticket_event`. Then **stop**: Part 2 (the sales-pathway UX audit) is not
to start until the logic review is signed off.
