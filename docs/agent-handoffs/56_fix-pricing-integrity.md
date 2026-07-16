# Agent Handoff

## Task
Fix six pricing-integrity issues from the 2026-07-16 sales-ticket-flow audit (user-approved P2
tier). These are deliberate business-behavior changes, not refactors:
1. Missing FX rate must fail loudly (422), not silently cost at 1:1 THB.
2. Recalculation must not destroy a CEO manual price override.
3. Price overrides need an audit-trail ticket event.
4. `editItems` must not let sales overwrite import's proposed / CEO's approved / manual
   prices.
5. Quotation xlsx printed subtotal must match the DB total for tickets with >15 items.
6. Mirror all of the above in `mockApi.js` where the mock actually models the behavior.

## Branch
`fix/pricing-integrity`

## Base Commit
`fd60b68` (origin/main tip at branch-off — includes PR #216, the deposit-notice unification)

## Current Commit
See `git log -1` on this branch after the commit made at the end of this task.

## Agent / Model Used
Claude Sonnet 5 (implementation)

## Scope

### In Scope
- `backend/.../pricing/PriceCalcService.java` — FX-rate fix + manual-price preservation.
- `backend/.../ticket/TicketRepository.java` — new `replaceItemsPreservingPricing` method.
- `backend/.../ticket/TicketService.java` — `editItems` merge logic, `overrideItemPrice`
  audit event.
- `backend/.../ticket/TicketEventKind.java` — new `PRICE_OVERRIDDEN` constant.
- `backend/.../ticket/QuotationRenderer.java` — subtotal fix.
- `backend/src/main/resources/db/migration/V48__ticket_event_price_overridden.sql` — new
  migration extending `chk_event_kind`.
- `frontend/src/api/mockApi.js` — mirrors for `calculatePrices`, `overrideItemPrice`,
  `editItems`.
- `frontend/src/features/tickets/TicketDetailPage.jsx` — Thai label for the new event kind.
- New/updated tests (see below).

### Out of Scope
- Any other pricing/ticket business logic not named in the six items above.
- Sales/CRM UI redesign, Tailwind migration work.
- The pre-existing `editItems` mock authz divergence (mock allows `import` role to edit
  items in addition to `sales`; the real `TicketService.editItems` only allows
  `SALES_ROLES`). Not touched — out of scope for a pricing-fields-only task, and per
  CLAUDE.md mock authz is already known non-authoritative.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/pricing/PriceCalcService.java` — both
  `fxRates.findByCurrency(...).orElse(BigDecimal.ONE)` call sites replaced by a shared
  `resolveFxRate(rawCurrency)` helper: THB (or null, treated as THB) returns `ONE` with no
  lookup; any other currency with no `fx_rates` row throws
  `ApiException(422, "ไม่พบอัตราแลกเปลี่ยนสำหรับสกุลเงิน <CUR> — กรุณาตั้งค่าใน CEO Settings")`.
  `calculateForTicket` also now computes
  `proposedPrice = item.manualPrice() != null ? item.manualPrice() : calcedPrice` and passes
  that (not the raw `calcedPrice`) to `updateItemCalcResults`, so a manual override survives
  recalculation while `calced_cost`/`calced_price`/`calc_config_version` still refresh.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` — added
  `replaceItemsPreservingPricing(long ticketId, List<TicketItemDto> items)`: deletes and
  re-inserts ticket items writing ALL columns (including `approved_price`, `calced_cost`,
  `calced_price`, `calc_config_version`, `manual_price`, `manual_override_reason`, `currency`)
  from the already-merged `TicketItemDto` list, instead of `replaceItems`'s
  descriptive-fields-only insert.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java`:
  - `editItems` now builds a merged item list via new private
    `mergeEditedItemsPreservingPricing(ticketId, existingItems, requestItems)` — descriptive
    fields (brand/model/color/texture/size/factory/qty/qtySqm/rawPrice/rawCurrency/rawUnit/
    unitBasis) come from the request at each position; `proposedPrice`/`approvedPrice`/
    `calcedCost`/`calcedPrice`/`calcConfigVersion`/`manualPrice`/`manualOverrideReason`/
    `currency` come from the existing item at that position (null for new rows beyond the
    current count) — then calls `tickets.replaceItemsPreservingPricing(...)` instead of
    `tickets.replaceItems(...)`. `proposePrice` is untouched (still uses `replaceItems`,
    since import re-proposing intentionally replaces pricing wholesale).
  - `overrideItemPrice` now logs a `PRICE_OVERRIDDEN` event via `tickets.addEvent(...)` with a
    note containing the item id, the manual price, and the reason.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketEventKind.java` — added
  `PRICE_OVERRIDDEN = "PRICE_OVERRIDDEN"`.
- `backend/src/main/resources/db/migration/V48__ticket_event_price_overridden.sql` — NEW.
  `sales.ticket_event.chk_event_kind` (last redefined in V39) had an inline CHECK enumerating
  allowed kinds — extended the list with `PRICE_OVERRIDDEN`. V47 was the prior highest
  version; V46 exists only in `migration-demo` (avoided).
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationRenderer.java` — `toXlsx`'s subtotal is
  now computed as a separate reduce over ALL `priceItems` (all items with `approvedPrice !=
  null`), not just the first `MAX_ITEMS` (15) rendered rows. The render loop still caps at 15
  rows (template limit, documented in a comment) but no longer accumulates the subtotal.
- `frontend/src/api/mockApi.js`:
  - `editItems`: `proposedPrice` in the merged item now always comes from
    `ticket.items[i]?.proposedPrice ?? null` (was `item.proposedPrice ?? ticket.items[i]?...`,
    which let the request's value win — the actual bug). Other pricing fields
    (approvedPrice/calcedCost/calcedPrice/calcConfigVersion/manualPrice/manualOverrideReason)
    were already preserved implicitly via the object spread and weren't the bug.
  - `calculatePrices`: FX lookup now mirrors the backend — THB needs no lookup, any other
    currency missing from `mockFxRates` throws (via `fail(...)`, HTTP 422) instead of
    defaulting to rate 1. Also preserves `manualPrice` as `proposedPrice` across recalculation
    (was always overwritten with `calcedPrice`).
  - `overrideItemPrice`: now calls `pushEvent(ticket, user, 'PRICE_OVERRIDDEN', ...)` with the
    same note shape as the backend (item id, manual price, reason).
- `frontend/src/features/tickets/TicketDetailPage.jsx` — added
  `PRICE_OVERRIDDEN: 'แก้ไขราคาด้วยตนเอง (CEO)'` to the `EVENT_KIND_LABEL` map so the new event
  renders a Thai label in the timeline instead of the raw enum string.
- `backend/src/test/java/th/co/glr/hr/pricing/PriceCalcServiceTest.java` — NEW. 6 tests:
  missing-FX throws 422 with currency in message; THB skips lookup; null currency treated as
  THB; FX resolved when present; manual price preserved as proposedPrice through
  `calculateForTicket`; no-manual-price uses the calculated price.
- `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java` — added: 3 `editItems`
  tests (preserves existing pricing + ignores request-supplied prices; new item beyond
  current count gets null pricing; rejects non-owner sales) and 2 `overrideItemPrice` tests
  (logs `PRICE_OVERRIDDEN` with item id/manual price/reason in the note; rejects wrong
  status).
- `backend/src/test/java/th/co/glr/hr/ticket/QuotationRendererTest.java` — added
  `xlsxSubtotalCellSumsAllPricedItemsNotJustTheRenderedFifteen`: 20 priced items (prices
  1.00..20.00), reads back cell I38 (row 37, col 8) via POI `WorkbookFactory`, asserts it
  equals 210.00 (the sum of all 20, not just the first 15).

## Commands Run
```bash
git fetch origin && git switch -c fix/pricing-integrity origin/main
cd backend && ./mvnw -q -o compile
cd backend && ./mvnw -q -o test-compile
cd backend && ./mvnw -o test -Dtest=PriceCalcServiceTest,TicketServiceTest,QuotationRendererTest -DfailIfNoTests=false
cd backend && ./mvnw -B clean verify
cd frontend && npm ci && npm run lint && npm test && npm run build
```

## Test / Build Results
- Backend targeted tests: `PriceCalcServiceTest` 6/6, `TicketServiceTest` 86/86 (incl. 5 new),
  `QuotationRendererTest` 5/5 (incl. 1 new) — all pass.
- Backend full `mvnw -B clean verify` (Testcontainers): **427 tests, 0 failures, 0 errors,
  BUILD SUCCESS**. Flyway migrated cleanly through V48 ("ticket event price overridden").
  Jacoco coverage checks passed.
- Frontend lint: **0 errors**, 9 pre-existing warnings (unrelated `react-hooks/exhaustive-deps`
  in files this branch didn't touch).
- Frontend tests: **94/94 passed**, 19/19 files.
- Frontend build: **success** (vite build, no errors).

## Decisions Made
- **Manual-price preservation (#2) implemented in `PriceCalcService`, not SQL.** The task said
  "fix in the repository SQL or service" — chose service-level so it's directly unit-testable
  with mocked repos (as the task's own test spec required: "manual_price preserved through
  calculateForTicket (mock repos)"). A pure-SQL `CASE WHEN manual_price IS NOT NULL` fix would
  be invisible to a mocked-`TicketRepository` unit test.
- **`editItems` preservation (#4) needed a new repository method, not a change to
  `TicketItemRequest`.** `TicketItemRequest` (the `@Valid` JSON-bound DTO) has no
  approvedPrice/calcedCost/calcedPrice/calcConfigVersion/manualPrice/manualOverrideReason
  fields, and `insertItems`/`replaceItems` never wrote those columns at all (they defaulted to
  NULL on any delete+reinsert). Rather than widen the public request DTO (which would let a
  malicious JSON body attempt to smuggle those fields, even if ignored), the merge happens in
  `TicketService` producing `TicketItemDto` values (full internal shape), written by a new
  `TicketRepository.replaceItemsPreservingPricing(ticketId, List<TicketItemDto>)`.
- **`currency` (display currency, distinct from `rawCurrency`) treated as a pricing-adjacent
  field, not a request-driven descriptive field** — the audit's bullet #4 listed exactly which
  fields come from the request (brand/model/color/texture/size/factory/qty/qtySqm/rawPrice/
  rawCurrency/rawUnit/unitBasis/sortOrder) and which are carried over
  (proposedPrice/approvedPrice/calcedCost/calcedPrice/calcConfigVersion/manualPrice/
  manualOverrideReason), and `currency` appears in neither list. Chose to carry it over from
  the existing item (falls back to request/THB only for brand-new rows with no prior item) —
  this is a spec-interpretation call, flagging for reviewer attention.
- **Merge matches request position to existing item position by array index**, per the task's
  explicit instruction ("request order = display order"), not by any item id (the request has
  none).
- Mock's `editItems` `importCanEdit` branch (import role can also call editItems, which the
  real `TicketService.editItems` doesn't allow) was left as-is — a pre-existing mock/backend
  authz divergence unrelated to the pricing-fields bug being fixed here.

## Assumptions
- Currency codes are uppercase-only by convention (verified: no CHECK constraint enforces it,
  but no lowercase usage exists anywhere in migrations, seed data, or Java code) — so
  `"THB".equals(currency)` (exact match, no `equalsIgnoreCase`) is consistent with the existing
  codebase style.
- V48 is the correct next free main-schema migration version (V47 was the prior highest; V46
  is taken only in `db/migration-demo`, confirmed no `db/migration-uat` directory exists in
  this worktree).

## Known Risks
- `TicketItemDto`'s pricing fields silently defaulting to null for a "new" row beyond the
  existing item count in `editItems` means a sales user adding a new line item via `editItems`
  gets no `proposedPrice` until import re-proposes — this matches current `proposePrice`-driven
  workflow (import owns pricing) but is a behavior a reviewer should sanity-check against the
  actual UI flow for adding items mid-review.
- The `currency` field decision (carried over from existing item rather than the request) is a
  spec-interpretation call — flagged above under Decisions Made — reviewer should confirm this
  matches intent, since the audit's field lists didn't mention `currency` either way.
- Did not exercise the fix end-to-end in the running frontend-mock app (no browser drive this
  session) — relied on the unit/integration test suite (427 backend tests green, 94 frontend
  tests green) plus reading the mock's actual code paths. If the reviewer wants a live UI
  check, drive: propose price (import) → CEO override a price → recalculate → confirm the
  override survives → approve → generate quotation with >15 items → confirm downloaded xlsx
  I38 matches the ticket total.

## Things Not Finished
- None within the six-item scope. All six audit findings implemented, tested, and green
  end-to-end (including the Testcontainers-backed Flyway migration).

## Recommended Next Agent
Claude Opus (review), per the standing Sonnet-implements/Opus-reviews loop.

## Exact Next Prompt
```
Repo GL-R-ERP, branch fix/pricing-integrity (based on origin/main at fd60b68, includes PR
#216). Read CLAUDE.md and docs/agent-handoffs/56_fix-pricing-integrity.md, then review this
branch's diff against its base for the six 2026-07-16 pricing-integrity audit fixes:
(1) PriceCalcService FX-rate 422 vs THB-skip-lookup correctness in both calculateForTicket
and calculateBreakdown, (2) manual-price preservation through recalculation (service-level
choice — verify this doesn't miss a path where updateItemCalcResults is called from
elsewhere), (3) the V48 migration's chk_event_kind extension + PRICE_OVERRIDDEN audit event
correctness, (4) TicketService.editItems / TicketRepository.replaceItemsPreservingPricing —
in particular the "currency" field decision (carried from existing item, not the request —
flagged as a spec-interpretation call in the handoff) and whether position-based (not
id-based) merging is safe if the frontend ever reorders items without also reordering the
request array, (5) QuotationRenderer subtotal-vs-render-cap correctness, (6) the mockApi
mirrors. Run cd backend && ./mvnw -B clean verify and cd frontend && npm run lint && npm
test && npm run build to confirm still green, then merge on the user's explicit say-so (this
branch has not been merged or PR'd yet).
```
