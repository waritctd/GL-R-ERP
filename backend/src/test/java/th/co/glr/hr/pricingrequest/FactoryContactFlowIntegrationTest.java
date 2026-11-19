package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;

/**
 * CR-1 (GLA-167), parts A and B — written BEFORE the implementation, against real Postgres and the
 * real services/repositories.
 *
 * <p>A: the "ติดต่อโรงงานแล้ว" (factory contacted) step replaces "send" and gates price entry.
 * B: Sales terms seed the quote; Import may change currency and switch sqm/piece pricing.
 *
 * <p>Authz cases are written wrong-way-round: they assert who CANNOT reach the step, and which
 * states refuse it, rather than that the happy caller can.
 */
class FactoryContactFlowIntegrationTest extends Cr1FixtureSupport {

    @BeforeEach
    void setUp() {
        wireServicesAndCreateDeal();
    }

    // A — factory contacted

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

    // round 2 — review fixes

    // B — currency + price unit locked from the sales request

}
