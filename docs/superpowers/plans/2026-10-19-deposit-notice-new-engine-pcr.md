# Deposit Notice From a New-Engine Pricing-Request Quotation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/pricing-requests/{id}/deposit-notice` creates the มัดจำ draft when the pricing request's accepted quotation came from the new deal-quotation engine, instead of answering 409.

**Architecture:** One server-side change in `OrderConfirmationService.createDepositNoticeFromQuotation`: when no legacy (`origin IS NULL`) ACCEPTED quotation exists for the pricing request, accept the new-engine (`origin = 'PRICING_REQUEST'`) ACCEPTED quotation instead, pass its number as the draft's reference and let `DepositNoticeService.createDraft` source items and deposit percent exactly as the ticket-level route already does (`findDealQuotationItemsForDeposit` / `findDealQuotationDepositPercent`). No frontend change; the mock mirrors the same branch. No schema change. No authz change.

**Tech Stack:** Spring Boot service (Java), `AbstractPostgresIntegrationTest` on real Postgres, vitest for the mock mirror.

**Spec:** There is no separate design doc. The spec is the QA finding of 2026-10-18 on the develop QA stack plus the owner's confirmation on 2026-10-19:

- A deal priced through a pricing request, quoted through the new engine (`DealQuotationService.createFromPricingRequest` → submit → approve → `recordOutcome(ACCEPTED)`), then order-confirmed, reaches ขั้น 10, and the UI's มัดจำ step (`DealDepositPanel`, `DepositNoticePage`) calls the pricing-request route, which answers `409 "ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้"` because it only reads `CustomerQuotationRepository.findByPricingRequest` (legacy rows).
- The ticket-level route `POST /api/tickets/{id}/deposit-notice/draft` already handles the new engine; the UI never reaches it when a `QUOTATION_ACCEPTED` pricing request exists.
- Direct quotations (no pricing request) are unaffected and must stay so.
- The pricing-request route's own gate keeps its meaning: a quotation the customer has not accepted (ISSUED only) still yields the same 409. Owner-only access (`requireOwner`) is unchanged.
- Deposit percent is sourced from the quotation (owner ruling 2026-09-29), unchanged.

## Global Constraints

- Base branch `develop` at `c64a6e89`. Branch name `fix/deposit-notice-new-engine-pcr`. Never `gh pr merge --auto` into develop.
- Sales/CRM workflow logic may change (CLAUDE.md "Sales flow redesign"); every behaviour change is stated in the PR body. Payroll, tax, SSO, commission math untouched.
- Test-first: the failing test is written, shown red for the right reason, then the fix. Existing test files are revised; new files only where no file owns the behaviour (`tests-revise-dont-bloat`).
- Backend tests run on the local Postgres, one class at a time, with `-Dtest.fork.count=1` (exact property name) and a throwaway database; never the full `mvnw verify` locally.
- After any mutation-check revert of Java code, run `./mvnw -q clean` before trusting a re-run (stale `.class` files).
- `mockApi.js` keeps each `// Mirrors <JavaClass>` header accurate; the mock's authz is not evidence.
- The 409 message text `ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้` is unchanged (frontend shows `err.message`).
- Commit messages end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`; PR body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

## Review Focus

1. **New-engine quotation ISSUED but not yet ACCEPTED** — the route must still answer the same 409 and write no `sales.deposit_notice` row. Pinned in Task 1 (`createDepositNoticeFromQuotation_newEngineIssuedNotAccepted_still409`).
2. **Non-owning sales rep on a new-engine deal** — 403 before any quotation lookup, no row written. Pinned in Task 1 (`createDepositNoticeFromQuotation_newEngine_nonOwnerSales_cannotReach`).
3. **A hand-wired `OrderConfirmationService` with no `DealQuotationRepository` wired** (`dealQuotations == null`, as in `OrderConfirmationIntegrationTest`) — the legacy path must behave exactly as before and the new branch must not NPE. Pinned by the existing `OrderConfirmationIntegrationTest.fullChain_quotationAcceptedThroughDepositPaid_composesWithoutShortcuts` staying green (Task 2 step 4) and by the null guard in Task 2.
4. **Calling the route twice** — creates a second DRAFT version, same as the legacy path today (`DepositNoticeService.createDraft` numbers versions). Not a new behaviour; not separately pinned.
5. **Items and percent of the new-engine draft must equal what the ticket-level route produces** — the two routes share `createDraft`, so the test compares the two drafts field by field (Task 1, happy path). A divergence here is wrong money on a customer document.

---

### Task 1: Backend tests — the bug, and the two gates that must survive the fix

**Files:**
- Modify: `backend/src/test/java/th/co/glr/hr/dealquotation/DealQuotationOutcomeIntegrationTest.java` (add three `@Test` methods after `recordOutcome_byOwningSales_accepted_setsPrStatusAndDocStatus`, ~line 240; reuse its wiring — it already constructs `DealQuotationService`, `DepositNoticeService`, `OrderConfirmationService` and calls `orderConfirmation.wireDealQuotationRepository(quotationRepository)`)

**Interfaces:**
- Consumes (existing, in this test class): `acceptedNewOriginQuotation(String recipient, BigDecimal qty) → DealQuotationDto`, `issuedNewOriginQuotation(String recipient, BigDecimal qty) → DealQuotationDto`, `orderConfirmation.confirmOrder(long pricingRequestId, OrderConfirmationRequests.ConfirmOrderRequest, UserPrincipal)`, `orderConfirmation.createDepositNoticeFromQuotation(long pricingRequestId, OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest, UserPrincipal) → DepositNoticeDto`, the field `depositNoticeService` (make it a class field if it is still a local in `@BeforeEach`), `salesActor`, `otherSalesActor`, `ticketId`, `jdbc`.
- Consumes: `DealQuotationDto.pricingRequestId()`, `DealQuotationDto.number()`, `DepositNoticeDto.reference()`, `DepositNoticeDto.items()`, `DepositNoticeDto.depositPercent()`, `DepositNoticeItemDto(id, seq, description, qty, unit, unitPrice, discountLabel, netUnitPrice, amount)`, `DepositNoticeDraftRequest(customerName, customerTaxId, customerAddress, projectName, reference, depositPercent, notes, items)`.
- Produces: nothing new for later tasks; Task 2 turns the first test green.

- [ ] **Step 1: Add the three tests**

```java
// ── Deposit notice from the pricing-request route when the accepted quotation is NEW-engine ──
// QA finding 2026-10-18 (develop QA stack): DealDepositPanel/DepositNoticePage call
// POST /pricing-requests/{id}/deposit-notice whenever a QUOTATION_ACCEPTED pricing request exists;
// that route read only legacy CustomerQuotation rows and answered 409 for every deal quoted
// through DealQuotationService.createFromPricingRequest. Direct quotations never take this route.

@Test
void createDepositNoticeFromQuotation_newEngineAcceptedQuotation_createsTheSameDraftTheTicketRouteWould() {
    DealQuotationDto accepted = acceptedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
    long pricingRequestId = accepted.pricingRequestId();
    orderConfirmation.confirmOrder(pricingRequestId,
        new OrderConfirmationRequests.ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

    DepositNoticeDto viaPricingRequest = orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
        new OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest(null), salesActor);

    // The reference is the accepted quotation's number — the one thing this route adds over the ticket route.
    assertThat(viaPricingRequest.reference()).isEqualTo(accepted.number());
    assertThat(viaPricingRequest.items()).isNotEmpty();

    // Items and percent are exactly what the ticket-level route produces for this deal (shared createDraft).
    DepositNoticeDto viaTicket = depositNoticeService.createDraft(ticketId,
        new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
    assertThat(viaPricingRequest.depositPercent()).isEqualByComparingTo(viaTicket.depositPercent());
    assertThat(viaPricingRequest.items())
        .usingRecursiveComparison().ignoringFields("id")
        .isEqualTo(viaTicket.items());

    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM sales.pricing_request_event
         WHERE pricing_request_id = :id AND event_kind = 'DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION'
        """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
}

@Test
void createDepositNoticeFromQuotation_newEngineIssuedNotAccepted_still409() {
    DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
    long pricingRequestId = issued.pricingRequestId();

    assertThatThrownBy(() -> orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest(null), salesActor))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.deposit_notice WHERE ticket_id = :id",
        Map.of("id", ticketId), Long.class)).isZero();
}

@Test
void createDepositNoticeFromQuotation_newEngine_nonOwnerSales_cannotReach() {
    DealQuotationDto accepted = acceptedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
    long pricingRequestId = accepted.pricingRequestId();
    orderConfirmation.confirmOrder(pricingRequestId,
        new OrderConfirmationRequests.ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

    assertThatThrownBy(() -> orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest(null), otherSalesActor))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("ไม่มีสิทธิ์เข้าถึงรายการนี้");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.deposit_notice WHERE ticket_id = :id",
        Map.of("id", ticketId), Long.class)).isZero();
}
```

If `sales.pricing_request_event`'s column names differ (`kind` vs `event_kind`), copy the exact query `OrderConfirmationIntegrationTest.countPricingRequestEventsOfKind` uses (line ~774) rather than guessing. Add `import th.co.glr.hr.deposit.DepositNoticeDto;`, `import th.co.glr.hr.deposit.DepositNoticeDraftRequest;` if missing.

- [ ] **Step 2: Create the throwaway DB once and run the class; verify the red is the 409**

```bash
psql -h localhost -U "$(whoami)" -d postgres -c 'CREATE DATABASE glr_verify_it OWNER "'"$(whoami)"'";' 2>/dev/null || true
cd /Users/ploy_warit/Desktop/GL-R-ERP/backend && TEST_DB_URL=jdbc:postgresql://localhost:5432/glr_verify_it TEST_DB_USERNAME="$(whoami)" TEST_DB_PASSWORD="" \
  ./mvnw -B -q -Dtest.fork.count=1 -Dtest=DealQuotationOutcomeIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 | tail -40
```

Expected: `createDepositNoticeFromQuotation_newEngineAcceptedQuotation_createsTheSameDraftTheTicketRouteWould` FAILS with `ApiException ... ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้` (the bug). The other two PASS already — they are the gates that must survive Task 2 and are mutation-checked there. Every pre-existing test in the class still passes. If the happy-path test fails for any other reason (compile error, `confirmOrder` 409, fixture), fix the test, not the service, until the red is the 409.

- [ ] **Step 3: Commit the red tests**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP && git add backend/src/test/java/th/co/glr/hr/dealquotation/DealQuotationOutcomeIntegrationTest.java
git commit -m "test(sales): pin the มัดจำ draft from a new-engine pricing-request quotation (reproduces the 409)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Backend fix — accept the new-engine ACCEPTED quotation on the pricing-request route

**Files:**
- Modify: `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationService.java:433-459` (`createDepositNoticeFromQuotation`) and its Javadoc above it.

**Interfaces:**
- Consumes: `dealQuotations` (field, may be null — setter-injected via `wireDealQuotationRepository`), `DealQuotationRepository.findLatestForPricingRequest(long) → Optional<Long>`, `DealQuotationRepository.findById(long) → Optional<DealQuotationDto>`, `QuotationStatus.ACCEPTED`, `DepositNoticeService.createDraft(long ticketId, DepositNoticeDraftRequest, UserPrincipal)`.
- Produces: unchanged public signature `createDepositNoticeFromQuotation(long, CreateDepositNoticeFromQuotationRequest, UserPrincipal) → DepositNoticeDto`.

- [ ] **Step 1: Implement the two-branch resolution**

Replace the `orElseThrow` lookup with: legacy ACCEPTED quotation via `quotations.findByPricingRequest(pricingRequestId)` as today, else — only when `dealQuotations != null` — `dealQuotations.findLatestForPricingRequest(pricingRequestId).flatMap(dealQuotations::findById).filter(q -> QuotationStatus.ACCEPTED.equals(q.docStatus()))`; if neither, throw the existing 409 with the unchanged message. ("Latest" is correct: ACCEPTED is terminal for a pricing request's quotation family — R8 in `recordOutcome` — so no newer row can exist once one is accepted.)

Then build `DepositNoticeDraftRequest` with `reference = <number of whichever was found>` and `items = legacy ? DepositNoticeService.itemsFromQuotation(legacy.items()) : null`. A `null` items list makes `createDraft`'s `buildItemsFromRequest` fall through to `docs.findDealQuotationItemsForDeposit(ticketId)` — the same source the ticket-level route uses — and the percent already comes from `findDealQuotationDepositPercent(ticketId)`. Use the found number in the `DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION` event text as today.

Update the method's Javadoc: one paragraph stating the new-engine branch, why `items` is `null` for it, and that the 409 semantics (customer must have accepted) are unchanged. Delete nothing else from the existing Javadoc.

- [ ] **Step 2: Run the class; all green**

Same command as Task 1 step 2. Expected: every test in `DealQuotationOutcomeIntegrationTest` passes, including the three new ones.

- [ ] **Step 3: Mutation-check the ISSUED gate**

Temporarily change the filter to `QuotationStatus.ISSUED.equals(q.docStatus()) || QuotationStatus.ACCEPTED.equals(q.docStatus())`, re-run the class. Expected: ONLY `createDepositNoticeFromQuotation_newEngineIssuedNotAccepted_still409` fails. Revert, then `./mvnw -q clean` before the next run.

- [ ] **Step 4: Run the legacy-path class to prove the unwired case is unchanged**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP/backend && TEST_DB_URL=jdbc:postgresql://localhost:5432/glr_verify_it TEST_DB_USERNAME="$(whoami)" TEST_DB_PASSWORD="" \
  ./mvnw -B -q -Dtest.fork.count=1 -Dtest='OrderConfirmationIntegrationTest,DepositNoticeIssueGuardIntegrationTest,DepositNoticeAccountNotificationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 | tail -20
```

Expected: all pass (`OrderConfirmationIntegrationTest` never wires `DealQuotationRepository`, so this is the `dealQuotations == null` path).

- [ ] **Step 5: Commit**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP && git add backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationService.java
git commit -m "fix(sales): มัดจำ draft from the pricing-request route accepts a new-engine ACCEPTED quotation

The route read only legacy CustomerQuotation rows, so every deal quoted through
DealQuotationService.createFromPricingRequest got 409 at ขั้น 10. It now falls back
to the pricing request's new-engine ACCEPTED quotation and lets createDraft source
items and percent exactly as the ticket-level route does. ISSUED-only still 409s;
owner gate unchanged.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Mock mirror — same branch in `mockApi.js`, pinned in the existing mock deposit test

**Files:**
- Modify: `frontend/src/api/mockApi.js` — `pricingRequests.createDepositNoticeFromQuotation` (~line 16160) and `depositNotices.createDraft`'s item fallback (~line 13266); add one mapper next to `mockDepositNoticeItemsFromQuotation` (~line 1427).
- Modify: `frontend/src/api/mockApi.depositNotices.test.js` — split the existing drive helper and add two cases.

**Interfaces:**
- Consumes: `mockDealQuotations` rows (`pricingRequestId`, `origin`, `docStatus`, `number`, `ticketId`), `buildDealQuotationDto(row).items[]` (fields `descriptionLine`, `piecesFinal`, `unitPrice`, `discountPct`, `netUnitPrice` — confirm the names in `buildDealQuotationDto` before writing the mapper), `mockPickLiveQuotationForItemFallback(ticketId)` (already ranks ACCEPTED > ISSUED > APPROVED across both arrays, newest id wins), `api.dealQuotations.createFromPricingRequest / update / submit / approve / recordOutcome`, `api.pricingRequests.confirmOrder`.
- Produces: `function mockDepositNoticeItemsFromDealQuotation(row) → DepositNoticeItemRequest[]` (`{ seq, description, qty, unit, unitPrice, discountLabel, netUnitPrice }`, `qty = piecesFinal`, `unit = 'แผ่น'`, `discountLabel = discountPct > 0 ? \`ส่วนลด ${discountPct}% ต่อหน่วย\` : null`, `netUnitPrice` taken from the dto, never recomputed — the mock mirrors `DepositNoticeRepository.findDealQuotationItemsForDeposit`'s column pick, not a formula).

- [ ] **Step 1: Revise the mock test — extract the drive to an approved decision, add two cases**

In `mockApi.depositNotices.test.js`, split `driveTicketToAcceptedQuotation()` so the part up to and including the CEO-approved pricing decision becomes `driveTicketToApprovedDecision() → { ticketId, prId }`, and the existing helper calls it then continues on the legacy quotation path unchanged. Add:

```js
async function driveTicketToNewEngineAcceptedQuotation() {
  const { ticketId, prId } = await driveTicketToApprovedDecision();
  await api.auth.login({ role: 'sales' });
  const { quotation: draft } = await api.dealQuotations.createFromPricingRequest(prId);
  // validity + submit + approve + accept, exactly as DealQuotationOutcomeIntegrationTest.acceptedNewOriginQuotation
  await api.dealQuotations.update(draft.id, { ...payloadThatSetsValidityDays(draft, 30) });
  await api.dealQuotations.submit(draft.id, {});
  await api.auth.login({ role: 'sales_manager' });
  await api.dealQuotations.approve(draft.id, {});
  await api.auth.login({ role: 'sales' });
  const { quotation: accepted } = await api.dealQuotations.recordOutcome(draft.id, { outcome: 'ACCEPTED', customerNote: null, clientRequestId: uuid() });
  await api.pricingRequests.confirmOrder(prId, { clientRequestId: uuid() });
  return { ticketId, prId, accepted };
}

describe('mockApi.pricingRequests.createDepositNoticeFromQuotation — new-engine quotation (mirrors OrderConfirmationService fix)', () => {
  it('creates the draft with the accepted quotation number as reference and items from that quotation', async () => {
    const { prId, accepted } = await driveTicketToNewEngineAcceptedQuotation();
    const { depositNotice } = await api.pricingRequests.createDepositNoticeFromQuotation(prId, {});
    expect(depositNotice.reference).toBe(accepted.number);
    expect(depositNotice.items.length).toBeGreaterThan(0);
    expect(depositNotice.items[0].qty).toBe(accepted.items[0].piecesFinal);
    expect(depositNotice.items[0].netUnitPrice).toBe(accepted.items[0].netUnitPrice);
  });

  it('still 409s while the new-engine quotation is only ISSUED (customer has not accepted)', async () => {
    const { ticketId, prId } = await driveTicketToApprovedDecision();
    // same drive, stop after approve (ISSUED) — factor the first half of the helper above if needed
    …
    await expect(api.pricingRequests.createDepositNoticeFromQuotation(prId, {}))
      .rejects.toMatchObject({ status: 409, message: 'ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้' });
    const { depositNotices } = await api.depositNotices.listByTicket(ticketId);
    expect(depositNotices).toHaveLength(0);
  });
});
```

Resolve `payloadThatSetsValidityDays` by reading how `DealQuotationOutcomeIntegrationTest.upsertWithValidityDays` maps to the mock's `update` payload (the mock's own 400/409 messages name the missing field). The exact response envelope keys (`quotation`, `depositNotice`) are whatever the mock already returns for those calls.

- [ ] **Step 2: Run the mock test; verify the red is the 409 on the happy case**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP/frontend && npx vitest run src/api/mockApi.depositNotices.test.js 2>&1 | tail -25
```

Expected: the new happy-path case FAILS with the 409 message; the ISSUED case passes; every pre-existing case passes (the helper split changed no behaviour).

- [ ] **Step 3: Implement the mock mirror**

In `createDepositNoticeFromQuotation`: after the legacy `mockCustomerQuotations` lookup, if none, `const v2 = mockDealQuotations.find((q) => q.pricingRequestId === pr.id && q.origin === 'PRICING_REQUEST' && q.docStatus === 'ACCEPTED')`; 409 with the unchanged message if neither; `reference = (accepted ?? v2).number`; `items = accepted ? mockDepositNoticeItemsFromQuotation(accepted.items) : mockDepositNoticeItemsFromDealQuotation(v2)`. In `depositNotices.createDraft`'s fallback, after `mockPickTicketQuotationForDepositNotice` returns null, use `mockPickLiveQuotationForItemFallback(ticketId)` and the new mapper when its `origin` is not legacy — this mirrors `buildItemsFromRequest`'s last step, which the mock lacked. Update both `// Mirrors …` comments to name `OrderConfirmationService.createDepositNoticeFromQuotation` and `DepositNoticeRepository.findDealQuotationItemsForDeposit`.

- [ ] **Step 4: Run the mock test and the contract test; lint**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP/frontend && npx vitest run src/api/mockApi.depositNotices.test.js src/api/contract.test.js src/features/deposits/DepositNoticePage.test.jsx src/features/tickets/TicketDetailPage.test.jsx 2>&1 | grep -E "Test Files|Tests " && npx eslint src/api/mockApi.js src/api/mockApi.depositNotices.test.js
```

Expected: all pass; eslint clean.

- [ ] **Step 5: Commit**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP && git add frontend/src/api/mockApi.js frontend/src/api/mockApi.depositNotices.test.js
git commit -m "chore(mock): pricing-request deposit route and ticket draft fallback mirror the new-engine quotation branch

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Real-stack verification on the QA stack (the surface the bug was found on)

**Files:** none changed.

**Interfaces:**
- Consumes: the QA stack recipe — backend jar on `:8093` against DB `glr_qa_stages` (`SPRING_PROFILES_ACTIVE=demo SERVER_PORT=8093 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/glr_qa_stages SPRING_DATASOURCE_USERNAME=ploy_warit APP_MAIL_PROVIDER=log SERVER_SESSION_COOKIE_SECURE=false APP_CORS_ALLOWED_ORIGINS=http://127.0.0.1:5173,http://localhost:5173`), vite `VITE_USE_MOCKS=false VITE_API_PROXY_TARGET=http://127.0.0.1:8093 npm run dev -- --port 5173 --strictPort`, seed deals titled `[SEED-10-18] ขั้น N …`.

- [ ] **Step 1: Build the branch jar and start it on :8093**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP/backend && ./mvnw -B -q -DskipTests -Djacoco.skip=true package && cp target/*.jar "$SCRATCHPAD/deposit-fix-app.jar"
```

Stop whatever is on :8093 first (`lsof -ti:8093 | xargs kill`); start the new jar with the env above, in the background, and wait for `GET http://127.0.0.1:8093/actuator/health` → `{"status":"UP"}`. Restarting logs every cached browser session out.

- [ ] **Step 2: Reproduce the finding against the fixed jar**

With headless Playwright (or the Chrome extension) on `http://127.0.0.1:5173`: log in as the seed's sales persona, open the `[SEED-10-18] ขั้น 10` deal whose quotation is new-engine, press สร้างใบแจ้งยอดมัดจำ in the มัดจำ section. Expected: toast `สร้างร่างใบแจ้งยอดเงินรับมัดจำแล้ว`, navigation to `/tickets/<id>/deposit`, a DRAFT whose เลขที่อ้างอิง is the quotation number and whose rows match the quotation. Also open a `ขั้น 10` DIRECT-quotation seed deal and confirm its มัดจำ step still works (ticket-level route). Keep both screenshots in the scratchpad.

If no ขั้น 10 new-engine seed exists in `glr_qa_stages`, run `SEED_BACKEND_URL=http://127.0.0.1:8093 ONLY=S10 node .design/sales-pipeline-review/real-backend/seed-stages.mjs` first.

- [ ] **Step 3: Record the result** — the two screenshots' paths and the HTTP status of the create call go into the PR body (Task 5). No commit.

---

### Task 5: Pre-PR gate, review, PR

**Files:** none changed unless review finds something.

- [ ] **Step 1: Pre-PR gate (targeted, per the standing rule)**

```bash
cd /Users/ploy_warit/Desktop/GL-R-ERP/frontend && npm run lint && npx vitest run src/api/mockApi.depositNotices.test.js src/api/contract.test.js src/features/deposits src/features/tickets/DealDepositPanel.test.jsx 2>&1 | grep -E "Test Files|Tests "
cd /Users/ploy_warit/Desktop/GL-R-ERP && git rev-list --left-right --count HEAD...origin/develop   # must be N 0 (not behind)
```

- [ ] **Step 2: Independent Opus review** (`sonnet-implements-opus-reviews-loop` / `parallel-agents-use-opus`): the reviewer re-runs `DealQuotationOutcomeIntegrationTest`, reverts `OrderConfirmationService` to develop's version to see the happy-path test go red, checks the mock mirrors the Java branch and not more, and checks the PR body's coverage list against the assertions.

- [ ] **Step 3: Push and open the PR to develop** (on Ploy's say-so, per the standing rule). PR body sections: What (the behaviour change, stated as a sales-workflow change), Files changed, Commands run, Tests/build results (which ITs RAN on real Postgres), Authz evidence (`no authz change` — the owner gate is pre-existing; the wrong-way-round test pins it on the new path), Known risks (two DRAFT versions on a double call, same as before; the mock's ticket-level fallback gained the new-engine branch), Test coverage (the three Java cases, the two mock cases, red-before-green results, the mutation check, and what is NOT covered: no e2e-real spec drives the deposit step; the QA-stack walk in Task 4 is the browser evidence).

---

## Self-review notes

- **Spec coverage:** PCR new-engine → draft (Task 1/2); ISSUED-only → 409 (Task 1/2 + mutation); owner gate (Task 1); direct quotations unaffected (Task 4 step 2 second deal; the code path is untouched); mock fidelity (Task 3); real-stack reproduction (Task 4).
- **Types:** `DealQuotationDto.pricingRequestId()` is a `Long`; the tests unbox it where the accepted quotation is known to be PRICING_REQUEST-origin. `CreateDepositNoticeFromQuotationRequest(null)` is the controller's own default. `DepositNoticeItemDto` has `id` as the only per-document field, hence `ignoringFields("id")`.
- **Proportion:** the fix is ~15 lines of Java and ~25 of mock; the plan's length is in the tests, which are the deliverable the rules require.
