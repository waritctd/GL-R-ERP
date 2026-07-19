# 84 — feat/sales-audit-trail

**Branch:** `feat/sales-audit-trail` — **stacked**: 81 → 82 → 83 → 84. Merge in that order.
**Migration:** V58 · **Date:** 2026-07-19 · **Status:** committed, not pushed

## Why

Two places where deal history was unrecoverable.

**C1 — reopening a lost deal erased why it was lost.** `clearDealLost` ran
`lost_reason = NULL, lost_at = NULL`, leaving a row indistinguishable from one never lost.
The reason survived only as Thai free text inside an event message (`"เสียงาน (PRICE)"`), so
"why did we lose this before we reopened it" was a parsing exercise. Reason-code analytics on
reopened deals was impractical.

**C2 — events recorded what changed but never which document it produced.** Correlating a
status change to its quotation / receipt / delivery meant matching timestamps or parsing text.

## What changed

**V58** adds `reopened_at` + `reopen_count` on `sales.ticket`, and
`related_document_type` + `related_document_id` on `sales.ticket_event` with a CHECK
enforcing *both columns or neither* plus a closed type vocabulary, and a partial index.

- `clearDealLost` no longer nulls the reason; it stamps `reopened_at` and bumps `reopen_count`.
- `addEventWithDocument(...)` threads a link through `addEventInternal`. Populated at the four
  sites that produce a document: `generateQuotation`, `recordPaymentInternal`,
  `recordDeliveryInternal` (both the RECORDED and COMPLETED events). Both inserts already
  returned their ids — the values were simply being discarded.
- `RelatedDocumentType`: QUOTATION / DEPOSIT_NOTICE / PAYMENT_RECEIPT / DELIVERY_RECORD.
  **No IMPORT_REQUEST** — an import request has no row of its own, and this codebase already
  carries enough declared-but-unreachable vocabulary (`PICKED_UP`, `CUSTOMS_CLEARANCE`,
  `QuotationStatus.EXPIRED`…). It was in the first draft of the CHECK and was removed.
- No `REOPENED` lifecycle value: a reopened deal genuinely **is** ACTIVE, and a parallel state
  would fork every lifecycle check. The marker columns answer the question instead.

### The dangerous part of C1 — `lostReason != null` was load-bearing

Persisting the reason changed what `lost_reason` *means*. It used to imply "currently lost";
now it means "was lost at some point". **Eight** call sites tested its truthiness as a liveness
check, and every one would have misbehaved on a reopened deal:

| Site | Consequence if left alone |
|---|---|
| `TicketService.updateStage` | reopened deal refuses all stage changes |
| `TicketService.markLost` | reopened deal can never be lost again |
| `TicketService.autoAdvanceStage` | **auto-advance silently disabled** on every reopened deal |
| `mockApi` autoAdvance / updateStage / markLost | same, in the mock |
| `TicketListPage` ×6 | renders as เสียงาน, filters into the lost bucket, drops out of phase counts, sorts to -1 |
| `DealStagePanel` | detail page shows the lost banner on a live deal |

All now key on `lifecycle === 'CLOSED_LOST'`. In `updateStage` the check also **moved above
`requireActive`** so a genuinely lost deal still gets the specific "reopen it first" message
rather than the generic not-active one.

**If anything else starts reading `lost_reason`, it must check the lifecycle, not the reason.**

## Results

- **Backend 534 pass**, 0 failures. V58 applies clean in the full V1..V58 + V900..V909 chain.
- **Frontend 191 pass**, lint 0 errors (4 pre-existing warnings), build clean.
- New backend tests: a reopened deal is fully operable (stage change + losable again), stage
  writes are still refused while actually lost, and a **Testcontainers** test that the document
  link persists and that a half-set link (id without type) or an unknown type is **rejected by
  the CHECK** — neither can fire against a Mockito stub.

### Browser verification (mock, `sales` persona)
Marked deal 3 lost on PRICE → reopened it → **not** shown as lost, stage panel operable, can be
marked lost again, and the list's เสียงาน filter count stays 0. Those are exactly the
regressions the guard migration was meant to prevent.

## Known risks

- **Semantic change to `lost_reason`.** Any future reader must use the lifecycle. Called out
  above and in code comments at each site.
- Deals reopened *before* V58 have already lost their reason — unrecoverable, not backfilled.
- Historical events keep NULL document linkage; no reconstruction from timestamps was attempted.
- The document link is **polymorphic with no FK**. Callers must not assume the row still exists.
- The mock does not model `related_document_*` (it has no document-link consumer yet); the DB
  behaviour is covered by the integration test instead.

## Status of the sales remediation

| Branch | Scope | State |
|---|---|---|
| 81 `fix/sales-transition-gates` | close gate + stage-order friction | committed |
| 82 `feat/sales-close-verification` | V56, three-party close | committed |
| 83 `feat/sales-cancel-reason` | V57, structured cancel reason | committed |
| 84 `feat/sales-audit-trail` | V58, reopen history + document linkage | committed |
| D1 typed `ADJUSTMENT` reason | not started | deferred |
| D2 aging / collections / write-off | **blocked on a business decision** | deferred |
| Tier 3 quotation identity | recipient-as-party, per-quotation quantities | **unscoped, needs its own design round** |

Nothing pushed; no PRs opened.

## Next

**Part 2 (the sales-pathway UX audit) is deliberately NOT started** — it waits on sign-off of
the logic in 81–84. Groundwork already done and recorded in the plan file: the mock's 15 deals
derive their stage and occupy only 6 of 14 stages, so 8 stages (including DELIVERY_SCHEDULING
and DELIVERED) have no seeded deal and must be reached by driving transitions in the UI; all 15
deals belong to one sales user, so own-deals-only scoping is not observable in the mock.
