# 0002. Import adds or moves a line on a contacted factory quote

**Date**: 2026-11-23
**Status**: In Progress

## Summary

Today a pricing request gets stuck when Import has already contacted a factory and then needs to add a missed line to it. With this change, Import can add that line, or move a line to another factory, on the same request. Prices already entered stay, and Import confirms the factory's quote once more before it reaches the CEO. No database migration is needed.

## Context

- `PricingRequestService.setItemFactory` only fills a blank factory, and returns 409 when the target factory's quote is past `DRAFT`. The only way out is a customer change revision, which only the Sales owner can start, for a problem that belongs to Import.
- Factory quotes are grouped by factory name. `receive` requires the response to cover exactly the lines on the quote, and `LandedCostCalculator` requires every line to sit on a `READY_FOR_COSTING` quote with a price. So a line outside its quote blocks the request from reaching the CEO.
- A quote already has a revision chain (`supersede` plus `createRevision`), which keeps history without a schema change.
- A factory may price by total volume, so a changed line set can make a confirmed price wrong.
- Catalog lines are snapshotted again by `snapshotCatalogSelections` on a later revision, and their catalog link also decides the origin country used for freight. A moved line that keeps this link would silently return to the old factory or use the wrong freight.

## Requirements

**User stories**:
- As Import, I want to add a missed line to a factory I already contacted, so the request does not wait for Sales.
- As Import, I want to move a line to the right factory, with a reason, so a wrong or unavailable factory never blocks the work.
- As the CEO or the rep who asked, I want to be told what changed and why.

**Acceptance criteria**:
- **AC-1**: Import can assign a blank line to a factory whose current quote is `REQUESTED`, `RESPONSE_RECEIVED`, `NEGOTIATING` or `READY_FOR_COSTING`, while the request is `IMPORT_REVIEWING` or `AWAITING_FACTORY_RESPONSE`.
- **AC-2**: After a line joins a quote that has a response, existing prices and terms are unchanged, the new line is blank, and the quote is back at `RESPONSE_RECEIVED`. The old revision stays in history.
- **AC-3**: The request cannot reach the CEO until the new line is priced and Import confirms the quote again.
- **AC-4**: After adding to a contacted factory, Import can copy a follow up email listing only the added line, in the same language and format as the first factory email, with the subject `Additional item - <request code>`. If the factory has no email, the subject and body can still be copied. Copying it is optional and never blocks entering a price. The price form still needs a price on every line of the quote before it saves.
- **AC-5**: Import can move a line with an assigned factory to another factory. A short reason is required.
- **AC-6**: A moved line arrives blank at the new factory, including when it returns to a factory it left (A to B to A). Its old price stays only in the old quote revision and is not suggested. A cancelled quote is never revived: the return goes through a new quote.
- **AC-7**: Lines left on the old factory keep their prices, and a quote that had a response must be confirmed again. If no line remains, the old quote is cancelled with the move reason.
- **AC-8**: A catalog line can be moved after a warning that it is on the old factory's price list. Its product code and name stay for reference, and a later revision does not send it back to the old factory. Its thickness is kept. Its origin becomes the new factory's country, or blank when that country cannot be mapped, so the CEO must resolve it before approval. The old country is never used.
- **AC-9**: Every add or move writes a request history event. Every move, and every add to a factory already contacted, notifies the CEO and the rep who asked. An add to a factory not yet contacted notifies nobody. A move shows from, to and reason.
- **AC-10**: Only Import can do this. Stock lines, a factory marked `NOT_AVAILABLE` as the target, and requests outside the two statuses in AC-1 are refused with a clear Thai message.
- **AC-11**: A repeated save with the same factory changes nothing and creates no event. Two people saving at once cannot leave a line on two quotes. If the line's factory no longer matches what the screen showed, the save is refused with 409 and the screen reloads the current factory.

## Options considered

### Option 1: Revise the quote in place through its revision chain

Add or remove the line by creating the next quote revision, copying the other lines.

**Pros**: no migration, keeps history, reuses the confirm step and CEO gate.
**Cons**: Import confirms the whole quote again, even for lines that did not change.

### Option 2: Confirm each line separately

Add a status per quote line so only the new line needs confirming.

**Pros**: one less confirm for Import.
**Cons**: needs a migration and a change to the costing gate, and can pass a volume based price nobody checked.

### Option 3: Keep the block and let Import start a request revision

**Pros**: almost no new quote logic.
**Cons**: replaces the whole request for one line, restarts the flow, and blurs the rule that Sales owns customer changes.

## Decision

**Chosen option**: Option 1: Revise the quote in place through its revision chain.

Extend the existing set factory action so Import can add or move a line, and treat every change to a responded quote as a new quote revision that needs confirming again.

## Rationale

The block exists to protect the link between a quote and its lines. A new revision keeps that link true and uses the gate the CEO already relies on. One extra confirm is a fair cost, because the changed line set is exactly when a volume based price can go wrong.

## Feature design

**Data model sketch**: no new table or column.

Fields written on a move, on `sales.pricing_request_item`:

| Field | New value |
|---|---|
| `factory`, `resolved_factory_id` | target factory |
| `resolved_factory_name` | target name if it was set, else stays NULL |
| `product_id`, `catalog_price_id`, `price_list_version_id`, `catalog_base_price` | NULL, so later snapshots and catalog pricing no longer apply |
| `catalog_product_code`, `catalog_brand`, `catalog_collection`, `catalog_model` | kept for reference |
| `thickness_mm` | catalog thickness when the catalog has one, else the current value. Read before the link is cleared |
| `origin_country` | Sales label for the target factory's ISO country, the reverse of `ISO_BY_SALES_ORIGIN` (IT, ES, CN). NULL when there is no mapping, which `approve` already refuses as `MISSING_ORIGIN` |
| `origin_country_other` | NULL |

`sales.ticket_item.factory` follows the move only while it still equals the old name (the `repointFactoryQuote` rule).

**State transitions**, by current quote status:

| Quote status | Line joins | Line leaves |
|---|---|---|
| none | `generateDrafts` creates the quote as today | not applicable |
| `DRAFT` | `generateDrafts` adds it as today | remove the line, cancel the quote if empty |
| `REQUESTED` | add a blank line, stays `REQUESTED` | remove the line, cancel the quote if empty |
| `RESPONSE_RECEIVED`, `NEGOTIATING`, `READY_FOR_COSTING` | new revision at `RESPONSE_RECEIVED` with all lines plus a blank one | new revision at `RESPONSE_RECEIVED` without the line, or cancel if empty |
| `NOT_AVAILABLE` | refused with 409 | quote stays as a record, the line moves |

Copy revision lines with `INSERT ... SELECT`, as `copyReadyQuotesToRevision` does, so `catalog_product_id_snapshot` and every other column survive. Pass the previous header values to `createRevision`: supplier reference, currency, payment terms, lead time and negotiation note. It already carries the email and contact fields. The revision reason names the added or moved line. A line returning to a factory follows this same table using that factory's current quote, and a factory with no current quote gets a new one from `generateDrafts`.

**API surface**:

| Endpoint | Method | Key inputs | Key outputs | Auth | Key errors |
|---|---|---|---|---|---|
| `/pricing-requests/{id}/items/{itemId}/factory` (changed) | PUT | `factoryId` (required), `expectedFactoryName` (the factory shown on screen, null for a blank line), `reason` (required on a move, max 500) | request detail | Import | 400 no reason, 404 unknown factory or line, 409 stale screen, stock, target unavailable, wrong status |
| `/factory-quotes/{quoteId}/follow-up-email?itemId=` (new) | GET | `quoteId`, `itemId` | `emailTo`, `subject`, `body` | Import, CEO | 404, 409 if the line is not on this quote |

**Value sourcing**:

| Action | Value | Source |
|---|---|---|
| Add or move | target factory name | `price_catalog.factories` by `factoryId` |
| Add or move | old factory | `firstText(resolvedFactoryName, factory)` on the line |
| New blank line | quantity, unit, basis, currency | `addItemsToDraft` query from the request line |
| Revision | kept prices and terms | previous current quote and its lines |
| Stale check | factory the screen showed | request `expectedFactoryName` |
| Move | thickness | catalog thickness through `catalogPriceId`, else `thickness_mm` |
| Move | origin country | target `price_catalog.factories.country`, reverse of `ISO_BY_SALES_ORIGIN` |
| Move | reason | request `reason`, trimmed |
| Event | `action`, `fromFactory`, `toFactory`, `reason` | `metadata` on `PRICING_REQUEST_ITEM_FACTORY_SET`. `fromFactory` and `reason` are null on an add |
| Notice | recipients | `notifyCeo` and `summary.requestedById`, one notice each per action |
| Notice | Thai text | request code, line name, from and to factory, reason, built like the `markNotAvailable` notice |
| Follow up email | recipient | quote `emailTo`, may be blank |
| Follow up email | subject | `Additional item - ` plus `summary.requestCode` |
| Follow up email | body | existing `emailBody` with only that line |

**Key invariants**:
- A line is on exactly one live quote, the quote of its resolved factory. A `NOT_AVAILABLE` quote may keep a moved line as a record.
- A quote with a blank price can never be `READY_FOR_COSTING` (`markReady` already enforces this).
- The whole action runs in one transaction under `lockPricingRequest`, and the line update compares the old factory.
- A superseded quote withdraws its pending lead time change and tells the people involved, using `notifyAutoWithdrawnLeadTimeChange`.

**Security model**: the backend enforces Import only on the write through `requireRole` and `requireViewable`. No new customer data is exposed. The follow up email uses the same factory data Import and the CEO already see.

**Critical test scenarios**:
- Blank line joins a `READY_FOR_COSTING` quote, the old price survives, and the CEO gate stays closed until it is confirmed, verifies **AC-1**, **AC-2**, **AC-3**.
- Catalog line moves, then a customer revision is submitted, and the line stays at the new factory with its thickness and the right freight origin, verifies **AC-6**, **AC-8**.
- A line moves A to B to A and returns blank, including when A's quote was cancelled, verifies **AC-6**.
- A stale screen tries to move a line another user already moved and gets 409, verifies **AC-11**.
- Moving the last line cancels the old quote, and moving a line off a partly filled quote needs a new confirm, verifies **AC-7**.
- Sales is refused and an unavailable target gets 409, verifies **AC-10**.

## Build plan

Tracer Bullet: each slice runs through API and screen and is shown working before the next.

1. Add a blank line to a contacted factory: remove the 409, add the quote revision helper, keep the existing picker. This fixes the stuck case, satisfies **AC-1**, **AC-2**, **AC-3**, **AC-10**, **AC-11**
2. History, notices and the follow up email endpoint with a copy button on the factory card, satisfies **AC-4**, **AC-9**
3. Move a line: backend leave path, catalog and origin handling, and a phone friendly move sheet on the line with factory picker, required reason and catalog warning, satisfies **AC-5**, **AC-6**, **AC-7**, **AC-8**, **AC-9**
4. Mirror the shape in `hrApi.js` and `mockApi.js`, regenerate `docs/api/`, and revise the tests that pin the old 409, satisfies **AC-1**, **AC-5**, **AC-10**

## Consequences

**Positive**:
- Import is no longer stuck, and Sales is not pulled in for an Import correction.
- Existing prices are not typed again.

**Negative / tradeoffs**:
- Import confirms the whole quote again after any line change.
- A moved catalog line loses its live catalog link, so it no longer follows price list updates.
- A brand new request made from the deal line may still pick the catalog factory, because the deal's product link is not changed.

**Neutral**:
- `setItemFactory` is no longer gap fill only, so its Javadoc and the "must never change" notes in the quote repository need updating.
- Backend change, so it ships in a backend image. No migration.

## Follow-up

- [ ] Before slice 3, confirm no reader or report depends on `product_id` or `catalog_price_id` for a line after a move. A search of the migrations found no check constraint on them.
