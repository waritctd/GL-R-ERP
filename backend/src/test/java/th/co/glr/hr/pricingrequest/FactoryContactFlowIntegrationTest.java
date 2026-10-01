package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.UpdateFactoryQuoteDraftRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;

/**
 * CR-1 (GLA-167), parts A and B — written BEFORE the implementation, against real Postgres and the
 * real services/repositories.
 *
 * <p>A: the "ติดต่อโรงงานแล้ว" (factory contacted) step replaces "send" and gates price entry.
 * B: currency and price unit are fixed by Sales on the request line and locked for import.
 *
 * <p>Authz cases are written wrong-way-round: they assert who CANNOT reach the step, and which
 * states refuse it, rather than that the happy caller can.
 */
class FactoryContactFlowIntegrationTest extends Cr1FixtureSupport {

    @BeforeEach
    void setUp() {
        wireServicesAndCreateDeal();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // A — factory contacted
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /** A1 */
    @Test
    void importMarksDraftQuoteContacted_storesFields_advancesRequest_andRaisesOneEvent() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");
        assertThat(draft.status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        LocalDate today = todayBangkok();

        FactoryQuoteDto contacted = factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(today, "โทรคุณ Marco"), importActor);

        assertThat(contacted.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(contacted.contactedOn()).isEqualTo(today);
        assertThat(contacted.contactedNote()).isEqualTo("โทรคุณ Marco");
        assertThat(contacted.contactedBy()).isEqualTo(importUserId);
        assertThat(contacted.contactedAt()).isNotNull();
        assertThat(factoryQuoteService.get(draft.id(), importActor).contactedOn()).isEqualTo(today);
        assertThat(requestStatus(prId)).isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(eventCount(prId, PricingRequestEventKind.FACTORY_CONTACTED)).isEqualTo(1L);
    }

    /** A2 */
    @Test
    void ceoMayAlsoMarkContacted_withTheSameResult() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        FactoryQuoteDto contacted = factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(todayBangkok(), null), ceoActor);

        assertThat(contacted.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(contacted.contactedBy()).isEqualTo(ceoUserId);
        assertThat(contacted.contactedNote()).isNull();
        assertThat(requestStatus(prId)).isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(eventCount(prId, PricingRequestEventKind.FACTORY_CONTACTED)).isEqualTo(1L);
    }

    /** A3 — wrong-way-round: sales, sales_manager and account cannot mark a factory contacted. */
    @Test
    void salesSalesManagerAndAccountCannotMarkContacted_andNothingIsWritten() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        for (UserPrincipal forbidden : List.of(salesActor, otherSalesActor, salesManagerActor, accountActor)) {
            assertThatThrownBy(() -> factoryQuoteService.markContacted(draft.id(),
                    new MarkFactoryContactedRequest(todayBangkok(), "x"), forbidden))
                .as("role %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        FactoryQuoteDto after = factoryQuoteService.get(draft.id(), importActor);
        assertThat(after.status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(after.contactedOn()).isNull();
        assertThat(after.contactedBy()).isNull();
        assertThat(requestStatus(prId)).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
        assertThat(eventCount(prId, PricingRequestEventKind.FACTORY_CONTACTED)).isZero();
    }

    /** A4 — R7: no undo, no edit. */
    @Test
    void markingAnAlreadyContactedQuoteIs409_andLeavesTheContactedFieldsUntouched() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");
        LocalDate yesterday = todayBangkok().minusDays(1);
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(yesterday, "first"), importActor);

        assertThatThrownBy(() -> factoryQuoteService.markContacted(draft.id(),
                new MarkFactoryContactedRequest(todayBangkok(), "second"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        FactoryQuoteDto after = factoryQuoteService.get(draft.id(), importActor);
        assertThat(after.contactedOn()).isEqualTo(yesterday);
        assertThat(after.contactedNote()).isEqualTo("first");
        assertThat(after.contactedBy()).isEqualTo(importUserId);
        assertThat(eventCount(prId, PricingRequestEventKind.FACTORY_CONTACTED)).isEqualTo(1L);
    }

    /** A5 — contactedOn is required and cannot be in the future (Asia/Bangkok). */
    @Test
    void contactedOnIsRequired_andCannotBeInTheFuture_bothAre400() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        assertThatThrownBy(() -> factoryQuoteService.markContacted(draft.id(),
                new MarkFactoryContactedRequest(null, "no date"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> factoryQuoteService.markContacted(draft.id(),
                new MarkFactoryContactedRequest(todayBangkok().plusDays(1), "tomorrow"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(factoryQuoteService.get(draft.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(eventCount(prId, PricingRequestEventKind.FACTORY_CONTACTED)).isZero();
    }

    /** A6 — the pricing request must still be with import (IMPORT_REVIEWING / AWAITING_FACTORY_RESPONSE). */
    @Test
    void markContactedIs409_whenThePricingRequestIsPastImport() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");
        // Force a state the quote could never reach through the API while still DRAFT, so the
        // request-status gate is the ONLY thing that can refuse.
        jdbc.update("UPDATE sales.pricing_request SET status = 'CEO_REVIEWING' WHERE pricing_request_id = :id",
            Map.of("id", prId));

        assertThatThrownBy(() -> factoryQuoteService.markContacted(draft.id(),
                new MarkFactoryContactedRequest(todayBangkok(), null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(factoryQuoteService.get(draft.id(), importActor).contactedOn()).isNull();
    }

    /** A6 (cancelled) */
    @Test
    void markContactedIs409_whenThePricingRequestWasCancelled() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");
        pricingRequestService.cancel(prId, new PricingRequestRequests.CancelPricingRequestRequest("ลูกค้ายกเลิก"), salesActor);

        assertThatThrownBy(() -> factoryQuoteService.markContacted(draft.id(),
                new MarkFactoryContactedRequest(todayBangkok(), null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(eventCount(prId, PricingRequestEventKind.FACTORY_CONTACTED)).isZero();
    }

    /** A7 — BEHAVIOUR CHANGE: today receive() accepts a DRAFT quote. */
    @Test
    void priceEntryIsLockedUntilTheFactoryIsContacted() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        assertThatThrownBy(() -> factoryQuoteService.receive(draft.id(), receiveAll(draft, "THB", "PER_PIECE"), importActor))
            .as("receive on a DRAFT (not yet contacted) quote")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        FactoryQuoteDto untouched = factoryQuoteService.get(draft.id(), importActor);
        assertThat(untouched.status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(untouched.items()).allSatisfy(i -> assertThat(i.rawUnitPrice()).isNull());

        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(todayBangkok(), null), importActor);
        FactoryQuoteDto received = factoryQuoteService.receive(draft.id(),
            receiveAll(draft, "THB", "PER_PIECE"), importActor);
        assertThat(received.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
    }

    /** A9 — CEO can run the whole contact step. */
    @Test
    void ceoCanGenerateAndEditFactoryEmailDrafts() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(prId, ceoActor);
        assertThat(drafts).hasSize(1);
        FactoryQuoteDto edited = factoryQuoteService.updateDraft(drafts.get(0).id(),
            new UpdateFactoryQuoteDraftRequest("x@example.com", "CEO subject", "CEO body", null), ceoActor);
        assertThat(edited.emailSubject()).isEqualTo("CEO subject");
    }

    /** A9 — regression guard: the widening must not reach sales / account. (Passes before CR-1.) */
    @Test
    void salesSalesManagerAndAccountStillCannotGenerateOrEditDrafts() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        for (UserPrincipal forbidden : List.of(salesActor, salesManagerActor, accountActor)) {
            assertThatThrownBy(() -> factoryQuoteService.generateDrafts(prId, forbidden))
                .as("generateDrafts as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> factoryQuoteService.updateDraft(draft.id(),
                    new UpdateFactoryQuoteDraftRequest(null, "hack", null, null), forbidden))
                .as("updateDraft as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(factoryQuoteService.get(draft.id(), importActor).emailSubject()).isNotEqualTo("hack");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // round 2 — review fixes
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /** A price revision is a NEW quote row: it must keep who contacted the factory, when, and the note. */
    @Test
    void aRevisedReceiveKeepsTheContactedFieldsOnTheNewRevisionRow() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");
        LocalDate yesterday = todayBangkok().minusDays(1);
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(yesterday, "โทรคุณ Marco"), importActor);
        FactoryQuoteDto first = factoryQuoteService.receive(draft.id(), receiveAll(draft, "THB", "PER_PIECE"), importActor);

        FactoryQuoteDto revised = factoryQuoteService.receive(first.id(), receiveAll(first, "THB", "PER_PIECE"), importActor);

        assertThat(revised.id()).isNotEqualTo(first.id());
        assertThat(revised.current()).isTrue();
        assertThat(revised.contactedOn()).isEqualTo(yesterday);
        assertThat(revised.contactedNote()).isEqualTo("โทรคุณ Marco");
        assertThat(revised.contactedBy()).isEqualTo(importUserId);
        assertThat(revised.contactedAt()).isNotNull();
    }

    /** Reissue carry-forward copies READY_FOR_COSTING quotes onto the revision: contacted fields ride along. */
    @Test
    void carryForwardCopiesTheContactedFields() {
        long fromPr = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(fromPr, importActor), "Factory A");
        LocalDate yesterday = todayBangkok().minusDays(1);
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(yesterday, "note"), importActor);
        FactoryQuoteDto received = factoryQuoteService.receive(draft.id(), receiveAll(draft, "THB", "PER_PIECE"), importActor);
        factoryQuoteService.markReadyForCosting(received.id(), importActor);
        long toPr = pricingRequestService.createDraft(ticketId,
            request(line("Tile A", "Factory A", 10, null, null, null, null)), salesActor).summary().id();
        long fromItem = received.items().get(0).pricingRequestItemId();
        long toItem = pricingRequestService.get(toPr, salesActor).items().get(0).id();

        List<Long> copied = factoryQuoteRepository.copyReadyQuotesToRevision(fromPr, toPr, Map.of(fromItem, toItem), importUserId);

        assertThat(copied).hasSize(1);
        FactoryQuoteDto copy = factoryQuoteRepository.find(copied.get(0)).orElseThrow();
        assertThat(copy.contactedOn()).isEqualTo(yesterday);
        assertThat(copy.contactedNote()).isEqualTo("note");
        assertThat(copy.contactedBy()).isEqualTo(importUserId);
        assertThat(copy.contactedAt()).isNotNull();
    }

    /** The CEO who performs the step is not told about their own action; another CEO-role user still is. */
    @Test
    void ceoMarkingContactedDoesNotNotifyThemselves_butAnotherCeoIsStillNotified() {
        long otherCeoId = createSecondCeo();
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, ceoActor), "Factory A");
        assertThat(notificationCount(ceoUserId, PricingRequestEventKind.FACTORY_EMAIL_READY))
            .as("generateDrafts by the CEO must not notify that CEO").isZero();
        assertThat(notificationCount(otherCeoId, PricingRequestEventKind.FACTORY_EMAIL_READY)).isEqualTo(1L);

        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(todayBangkok(), null), ceoActor);

        assertThat(notificationCount(ceoUserId, PricingRequestEventKind.FACTORY_CONTACTED)).isZero();
        assertThat(notificationCount(otherCeoId, PricingRequestEventKind.FACTORY_CONTACTED)).isEqualTo(1L);
    }

    /** Control: import marking contacted still notifies the CEO. */
    @Test
    void importMarkingContactedStillNotifiesTheCeo() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(todayBangkok(), null), importActor);

        assertThat(notificationCount(ceoUserId, PricingRequestEventKind.FACTORY_CONTACTED)).isEqualTo(1L);
    }

    private long notificationCount(long employeeId, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE employee_id = :e AND type = :t",
            Map.of("e", employeeId, "t", type), Long.class);
    }

    private long createSecondCeo() {
        th.co.glr.hr.employee.EmployeeRepository employees = new th.co.glr.hr.employee.EmployeeRepository(
            jdbc, new th.co.glr.hr.employee.EmployeeReferenceRepository(jdbc),
            new th.co.glr.hr.employee.EmployeeCodeGenerator(jdbc));
        return employees.create(new th.co.glr.hr.employee.UpsertEmployeeRequest(
            null, null, "ผู้บริหาร คนที่สอง", null, null, null, null, null, null, null,
            "ceo2-cr1@glr.co.th", null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new java.math.BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

    /** R1: a draft item starts from the terms Sales locked on the line (EUR, PER_SQM), not the quantity basis. */
    @Test
    void generateDraftsSeedsTheDraftItemWithTheRequestedCurrencyAndPriceUnit() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, "EUR", UnitBasis.PER_SQM));

        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        assertThat(draft.items()).singleElement().satisfies(i -> {
            assertThat(i.currency()).isEqualTo("EUR");
            assertThat(i.unitBasis()).isEqualTo(UnitBasis.PER_SQM);
        });
    }

    /** A legacy line (no requested terms) seeds exactly as before: its quantity basis, no currency. */
    @Test
    void generateDraftsSeedsALegacyLineExactlyAsBefore() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));

        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");

        assertThat(draft.items()).singleElement().satisfies(i -> {
            assertThat(i.currency()).isNull();
            assertThat(i.unitBasis()).isEqualTo(UnitBasis.PER_PIECE);
            assertThat(i.quotedQuantity()).isEqualByComparingTo("10");
        });
    }

    /** Lines appended to an existing DRAFT are seeded the same way. */
    @Test
    void linesAddedToAnExistingDraftAreSeededWithTheirRequestedTerms() {
        long prId = requestInImportReview(
            line("Tile A", "Factory A", 10, null, null, "EUR", UnitBasis.PER_SQM),
            line("Tile A2", "Factory A", 5, null, null, "USD", UnitBasis.PER_BOX),
            line("Tile A3", "Factory A", 5, null, null, null, null));
        List<PricingRequestDtos.PricingRequestItemDto> items = pricingRequestService.get(prId, importActor).items();
        long quoteId = factoryQuoteRepository.createDraft(prId, null, "Factory A", null, "s", "b", importUserId, null);
        factoryQuoteRepository.insertDraftItems(quoteId, List.of(items.get(0).id()));

        factoryQuoteRepository.addItemsToDraft(quoteId, List.of(items.get(1).id(), items.get(2).id()));

        List<th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto> seeded =
            factoryQuoteRepository.find(quoteId).orElseThrow().items();
        assertThat(seeded).extracting(i -> i.currency() + "/" + i.unitBasis())
            .containsExactly("EUR/PER_SQM", "USD/PER_BOX", "null/PER_PIECE");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // B — currency + price unit locked from the sales request
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /** B1 */
    @Test
    void salesCurrencyAndPriceUnitAreStoredAndReturnedOnTheItemDto() {
        PricingRequestDtos.PricingRequestDetailDto created = pricingRequestService.createDraft(ticketId,
            request(line("Tile A", "Factory A", 10, null, null, "EUR", UnitBasis.PER_SQM),
                line("Tile B", "Factory B", 5, null, null, null, null)), salesActor);

        assertThat(created.items().get(0).requestedCurrency()).isEqualTo("EUR");
        assertThat(created.items().get(0).requestedPriceUnitBasis()).isEqualTo(UnitBasis.PER_SQM);
        // legacy / unspecified line stays null
        assertThat(created.items().get(1).requestedCurrency()).isNull();
        assertThat(created.items().get(1).requestedPriceUnitBasis()).isNull();
        // and it survives a re-read
        PricingRequestDtos.PricingRequestItemDto reread =
            pricingRequestService.get(created.summary().id(), salesActor).items().get(0);
        assertThat(reread.requestedCurrency()).isEqualTo("EUR");
        assertThat(reread.requestedPriceUnitBasis()).isEqualTo(UnitBasis.PER_SQM);
    }

    /** B2 — today the draft's currency is hard-coded THB. */
    @Test
    void generateDraftsSeedsEachFactoryQuotesDefaultCurrencyFromItsLinesRequestedCurrency() {
        long prId = requestInImportReview(
            line("Tile A", "Factory A", 10, null, null, "EUR", UnitBasis.PER_SQM),
            line("Tile B", "Factory B", 5, null, null, "USD", UnitBasis.PER_PIECE));

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(prId, importActor);

        assertThat(quoteFor(drafts, "Factory A").defaultCurrency()).isEqualTo("EUR");
        assertThat(quoteFor(drafts, "Factory B").defaultCurrency()).isEqualTo("USD");
    }

    /** B3 */
    @Test
    void receiveWithACurrencyOtherThanTheRequestedOneIs409_andStoresNothing() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, "EUR", UnitBasis.PER_PIECE));
        FactoryQuoteDto quote = requestedQuote(prId, "Factory A");

        assertThatThrownBy(() -> factoryQuoteService.receive(quote.id(), receiveAll(quote, "USD", "PER_PIECE"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        FactoryQuoteDto after = factoryQuoteService.get(quote.id(), importActor);
        assertThat(after.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(after.items()).allSatisfy(i -> assertThat(i.rawUnitPrice()).isNull());

        // control: the SAME state accepts the requested currency, so the 409 above was about currency
        assertThat(factoryQuoteService.receive(quote.id(), receiveAll(quote, "EUR", "PER_PIECE"), importActor).status())
            .isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
    }

    /** B4 */
    @Test
    void receiveWithAPriceUnitOtherThanTheRequestedOneIs409_andStoresNothing() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, "EUR", UnitBasis.PER_SQM));
        FactoryQuoteDto quote = requestedQuote(prId, "Factory A");

        assertThatThrownBy(() -> factoryQuoteService.receive(quote.id(), receiveAll(quote, "EUR", "PER_PIECE"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        FactoryQuoteDto after = factoryQuoteService.get(quote.id(), importActor);
        assertThat(after.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(after.items()).allSatisfy(i -> assertThat(i.rawUnitPrice()).isNull());

        // control: the matching basis is accepted
        assertThat(factoryQuoteService.receive(quote.id(), receiveAll(quote, "EUR", "PER_SQM"), importActor).status())
            .isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
    }

    /** B5 — backward compatible. Goes through the real contact step, so it is red until A exists. */
    @Test
    void aLegacyLineWithNoRequestedTermsAcceptsAnyCurrencyAndUnit() {
        long prId = requestInImportReview(line("Tile A", "Factory A", 10, null, null, null, null));
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), "Factory A");
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(todayBangkok(), null), importActor);

        FactoryQuoteDto received = factoryQuoteService.receive(draft.id(), receiveAll(draft, "GBP", "PER_SQM"), importActor);

        assertThat(received.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(received.items().get(0).currency()).isEqualTo("GBP");
        assertThat(received.items().get(0).unitBasis()).isEqualTo("PER_SQM");
    }

    /** B6 — after submit, sales cannot change the terms through the plain update path. */
    @Test
    void afterSubmitSalesCannotChangeTheRequestedCurrency() {
        long id = pricingRequestService.createDraft(ticketId,
            request(line("Tile A", "Factory A", 10, null, null, "EUR", UnitBasis.PER_SQM)), salesActor).summary().id();
        pricingRequestService.submit(id, salesActor);

        PricingRequestRequests.UpdatePricingRequestRequest change = new PricingRequestRequests.UpdatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14), null, "THB", "change",
            List.of(line("Tile A", "Factory A", 10, null, null, "USD", UnitBasis.PER_SQM)));
        assertThatThrownBy(() -> pricingRequestService.updateDraft(id, change, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(pricingRequestService.get(id, salesActor).items().get(0).requestedCurrency()).isEqualTo("EUR");
    }

    /**
     * A quote that is already contacted, reached by forcing the state directly so B3/B4 isolate the
     * currency/unit rule from the A-part API (and from the A7 price-entry lock).
     */
    private FactoryQuoteDto requestedQuote(long prId, String factoryName) {
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(prId, importActor), factoryName);
        jdbc.update("UPDATE sales.factory_quote SET status = 'REQUESTED', requested_at = now() WHERE factory_quote_id = :id",
            Map.of("id", draft.id()));
        return factoryQuoteService.get(draft.id(), importActor);
    }
}
