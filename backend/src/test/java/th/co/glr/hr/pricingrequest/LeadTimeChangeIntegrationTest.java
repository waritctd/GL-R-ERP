package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.pricingrequest.LeadTimeChangeDtos.LeadTimeChangeDto;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.CreateLeadTimeChangeRequest;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.LineInput;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.RejectLeadTimeChangeRequest;

/**
 * CR-1 (GLA-167), part C — lead-time change request (option C: one request per factory quote,
 * lines pre-ticked, per-line override, ONE decision for the whole request). Written BEFORE the
 * implementation, against real Postgres through the real services and repositories.
 *
 * <p>Ruling: import raises and edits; only the OWNING sales rep or a sales manager decides. The
 * CEO, import, account and any OTHER sales rep cannot — those cases are written wrong-way-round.
 *
 * <p>Fixture: one request with THREE Factory A lines (lead times 30-45, 60-90, 10-20) and one
 * Factory B line (5-8), every quote already contacted (status forced to REQUESTED).
 */
class LeadTimeChangeIntegrationTest extends Cr1FixtureSupport {
    private long prId;
    private FactoryQuoteDto quoteA;
    private FactoryQuoteDto quoteB;
    private long a1;
    private long a2;
    private long a3;
    private long b1;

    @BeforeEach
    void setUp() {
        wireServicesAndCreateDeal();
        prId = requestInImportReview(
            line("A-1", "Factory A", 10, 30, 45, null, null),
            line("A-2", "Factory A", 10, 60, 90, null, null),
            line("A-3", "Factory A", 10, 10, 20, null, null),
            line("B-1", "Factory B", 10, 5, 8, null, null));
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(prId, importActor);
        // Contacted by forcing the status directly (not via markContacted): these tests are about the
        // lead-time flow, so they must stay red for THAT reason and not for the A-part stub.
        for (FactoryQuoteDto d : drafts) {
            jdbc.update("UPDATE sales.factory_quote SET status = 'REQUESTED', requested_at = now() WHERE factory_quote_id = :id",
                java.util.Map.of("id", d.id()));
        }
        quoteA = quoteFor(factoryQuoteService.list(prId, importActor), "Factory A");
        quoteB = quoteFor(factoryQuoteService.list(prId, importActor), "Factory B");
        a1 = itemId("A-1");
        a2 = itemId("A-2");
        a3 = itemId("A-3");
        b1 = itemId("B-1");
    }

    // ── C1 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void importRaisesAPendingChangeForTwoOfThreeLines_andCurrentLeadTimesStayUntouched() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(),
            body("ผู้ผลิตแจ้งเลื่อนการผลิต", line(a1, 100, 120), line(a2, 100, 120)), importActor);

        assertThat(change.status()).isEqualTo(LeadTimeChangeStatus.PENDING);
        assertThat(change.factoryQuoteId()).isEqualTo(quoteA.id());
        assertThat(change.requestedBy()).isEqualTo(importUserId);
        assertThat(change.reason()).isEqualTo("ผู้ผลิตแจ้งเลื่อนการผลิต");
        assertThat(change.lines()).extracting(LeadTimeChangeDtos.LeadTimeChangeLineDto::pricingRequestItemId)
            .containsExactlyInAnyOrder(a1, a2);
        assertThat(change.lines()).allSatisfy(l -> {
            assertThat(l.newMinDays()).isEqualTo(100);
            assertThat(l.newMaxDays()).isEqualTo(120);
        });
        // nothing is applied until sales approves
        assertLeadTime(a1, 30, 45);
        assertLeadTime(a2, 60, 90);
        assertLeadTime(a3, 10, 20);
        assertLeadTime(b1, 5, 8);
    }

    // ── C2 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void onlyOnePendingChangePerFactoryQuote_aSecondCreateIs409() {
        leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        assertThatThrownBy(() -> leadTimeChangeService.create(quoteA.id(), body("r2", line(a2, 100, 120)), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor)).hasSize(1);
    }

    @Test
    void afterWithdrawingTheSlotIsFreeAgain_andADifferentFactoryIsNotBlocked() {
        LeadTimeChangeDto first = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);
        // a different factory quote is independent of A's pending change
        assertThat(leadTimeChangeService.create(quoteB.id(), body("rb", line(b1, 20, 30)), importActor).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);

        assertThat(leadTimeChangeService.withdraw(first.id(), importActor).status())
            .isEqualTo(LeadTimeChangeStatus.WITHDRAWN);
        assertThat(leadTimeChangeService.create(quoteA.id(), body("r2", line(a2, 100, 120)), importActor).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);
    }

    // ── C3 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void invalidRequestsAre400_aForeignLineBadRangeBlankReasonAndNoLines() {
        assertBadRequest(body("ok", line(b1, 20, 30)));                 // b1 belongs to Factory B, not quote A
        assertBadRequest(body("ok", line(a1, 120, 100)));               // min > max
        assertBadRequest(body("ok", line(a1, 0, 10)));                  // min < 1
        assertBadRequest(body("   ", line(a1, 100, 120)));              // blank reason
        assertBadRequest(body(null, line(a1, 100, 120)));               // missing reason
        assertBadRequest(new CreateLeadTimeChangeRequest("ok", List.of()));   // no lines
        assertBadRequest(new CreateLeadTimeChangeRequest("ok", null));        // null lines
        assertBadRequest(body("ok", line(a1, null, 120)));              // missing min
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor)).isEmpty();
    }

    // ── C4 — who may create / edit / withdraw ────────────────────────────────────────────────

    @Test
    void onlyImportMayCreate_salesSalesManagerCeoAndAccountAreForbidden() {
        for (UserPrincipal forbidden : List.of(salesActor, otherSalesActor, salesManagerActor, ceoActor, accountActor)) {
            assertThatThrownBy(() -> leadTimeChangeService.create(quoteA.id(), body("x", line(a1, 100, 120)), forbidden))
                .as("create as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor)).isEmpty();
    }

    @Test
    void onlyImportMayUpdateOrWithdraw_andImportCanEditWhilePending() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (UserPrincipal forbidden : List.of(salesActor, salesManagerActor, ceoActor, accountActor)) {
            assertThatThrownBy(() -> leadTimeChangeService.update(change.id(), body("hack", line(a1, 1, 2)), forbidden))
                .as("update as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> leadTimeChangeService.withdraw(change.id(), forbidden))
                .as("withdraw as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        LeadTimeChangeDto stillPending = leadTimeChangeService.listForPricingRequest(prId, importActor).get(0);
        assertThat(stillPending.status()).isEqualTo(LeadTimeChangeStatus.PENDING);
        assertThat(stillPending.lines()).singleElement().satisfies(l -> assertThat(l.newMinDays()).isEqualTo(100));

        // import may untick a line / give a per-line value while it is still pending
        LeadTimeChangeDto edited = leadTimeChangeService.update(change.id(),
            body("r1 edited", line(a2, 150, 180), line(a3, 40, 50)), importActor);
        assertThat(edited.status()).isEqualTo(LeadTimeChangeStatus.PENDING);
        assertThat(edited.reason()).isEqualTo("r1 edited");
        assertThat(edited.lines()).extracting(LeadTimeChangeDtos.LeadTimeChangeLineDto::pricingRequestItemId)
            .containsExactlyInAnyOrder(a2, a3);
    }

    @Test
    void updateAndWithdrawOfAChangeThatIsNoLongerPendingAre409() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);
        leadTimeChangeService.approve(change.id(), change.version(), salesActor);

        assertThatThrownBy(() -> leadTimeChangeService.update(change.id(), body("late", line(a1, 1, 2)), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> leadTimeChangeService.withdraw(change.id(), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertLeadTime(a1, 100, 120); // the approved value was not disturbed
    }

    // ── C5 / C6 — approval ───────────────────────────────────────────────────────────────────

    @Test
    void theOwningRepApproves_listedLinesTakeTheNewValues_unlistedLinesAreUntouched() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(),
            body("r1", line(a1, 100, 120), line(a2, 150, 180)), importActor);

        LeadTimeChangeDto approved = leadTimeChangeService.approve(change.id(), change.version(), salesActor);

        assertThat(approved.status()).isEqualTo(LeadTimeChangeStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo(salesRepId);
        assertThat(approved.decidedAt()).isNotNull();
        assertLeadTime(a1, 100, 120);
        assertLeadTime(a2, 150, 180);   // per-line override honoured
        assertLeadTime(a3, 10, 20);     // unlisted line on the SAME factory
        assertLeadTime(b1, 5, 8);       // other factory
    }

    @Test
    void aSalesManagerMayApprove() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        LeadTimeChangeDto approved = leadTimeChangeService.approve(change.id(), change.version(), salesManagerActor);

        assertThat(approved.status()).isEqualTo(LeadTimeChangeStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo(salesManagerUserId);
        assertLeadTime(a1, 100, 120);
    }

    // ── C7 — wrong-way-round ─────────────────────────────────────────────────────────────────

    @Test
    void nobodyElseMayApprove_aDifferentSalesRepImportCeoAndAccountAreForbidden() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (UserPrincipal forbidden : List.of(otherSalesActor, importActor, ceoActor, accountActor)) {
            assertThatThrownBy(() -> leadTimeChangeService.approve(change.id(), change.version(), forbidden))
                .as("approve as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);
        assertLeadTime(a1, 30, 45);
    }

    @Test
    void nobodyElseMayReject_aDifferentSalesRepImportCeoAndAccountAreForbidden() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (UserPrincipal forbidden : List.of(otherSalesActor, importActor, ceoActor, accountActor)) {
            assertThatThrownBy(() -> leadTimeChangeService.reject(change.id(),
                    new RejectLeadTimeChangeRequest("ไม่อนุมัติ", change.version()), forbidden))
                .as("reject as %s", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);
    }

    // ── C8 — reject ──────────────────────────────────────────────────────────────────────────

    @Test
    void rejectNeedsAReason_andLeavesLeadTimesUnchanged() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> leadTimeChangeService.reject(change.id(), new RejectLeadTimeChangeRequest(blank, change.version()), salesActor))
                .as("reason=%s", blank)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);

        LeadTimeChangeDto rejected = leadTimeChangeService.reject(change.id(),
            new RejectLeadTimeChangeRequest("ลูกค้าไม่รับระยะเวลานี้", change.version()), salesActor);
        assertThat(rejected.status()).isEqualTo(LeadTimeChangeStatus.REJECTED);
        assertThat(rejected.decisionReason()).isEqualTo("ลูกค้าไม่รับระยะเวลานี้");
        assertThat(rejected.decidedBy()).isEqualTo(salesRepId);
        assertLeadTime(a1, 30, 45);
    }

    // ── C9 — terminal states ─────────────────────────────────────────────────────────────────

    @Test
    void approveOrRejectOfAChangeThatIsNotPendingIs409() {
        LeadTimeChangeDto pending1 = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);
        LeadTimeChangeDto approved = leadTimeChangeService.approve(pending1.id(), pending1.version(), salesActor);
        assertConflict(() -> leadTimeChangeService.approve(approved.id(), approved.version(), salesActor));
        assertConflict(() -> leadTimeChangeService.reject(approved.id(),
            new RejectLeadTimeChangeRequest("late", approved.version()), salesActor));

        LeadTimeChangeDto pending2 = leadTimeChangeService.create(quoteA.id(), body("r2", line(a2, 100, 120)), importActor);
        LeadTimeChangeDto rejected = leadTimeChangeService.reject(pending2.id(),
            new RejectLeadTimeChangeRequest("no", pending2.version()), salesActor);
        assertConflict(() -> leadTimeChangeService.approve(rejected.id(), rejected.version(), salesActor));

        LeadTimeChangeDto pending3 = leadTimeChangeService.create(quoteA.id(), body("r3", line(a3, 100, 120)), importActor);
        LeadTimeChangeDto withdrawn = leadTimeChangeService.withdraw(pending3.id(), importActor);
        assertConflict(() -> leadTimeChangeService.approve(withdrawn.id(), withdrawn.version(), salesActor));
        assertLeadTime(a2, 60, 90);
        assertLeadTime(a3, 10, 20);
    }

    // ── C10 — non-blocking ───────────────────────────────────────────────────────────────────

    @Test
    void aPendingChangeDoesNotBlockTheRequestFromReachingTheCeo() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (FactoryQuoteDto quote : List.of(quoteA, quoteB)) {
            FactoryQuoteDto received = factoryQuoteService.receive(quote.id(), receiveAll(quote, "THB", "PER_PIECE"), importActor);
            factoryQuoteService.markReadyForCosting(received.id(), importActor);
        }

        assertThat(requestStatus(prId)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        // and the change is still waiting for sales — it was neither auto-approved nor dropped
        assertThat(leadTimeChangeService.listForPricingRequest(prId, ceoActor)).singleElement()
            .satisfies(c -> {
                assertThat(c.id()).isEqualTo(change.id());
                assertThat(c.status()).isEqualTo(LeadTimeChangeStatus.PENDING);
            });
        assertLeadTime(a1, 30, 45); // "until approval the OLD value is used"
    }

    // ── C11 — visibility ─────────────────────────────────────────────────────────────────────

    @Test
    void listIsVisibleToImportCeoOwningRepAndSalesManager_butNotToAnotherSalesRep() {
        leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (UserPrincipal allowed : List.of(importActor, ceoActor, salesActor, salesManagerActor)) {
            assertThat(leadTimeChangeService.listForPricingRequest(prId, allowed))
                .as("list as %s", allowed.role()).hasSize(1);
        }
        assertThatThrownBy(() -> leadTimeChangeService.listForPricingRequest(prId, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── round 2: guards the first review found untested ──────────────────────────────────────

    /** Approve is refused (409) once the request is CANCELLED or SUPERSEDED, and writes nothing. */
    @Test
    void approveIsRefusedWhenThePricingRequestIsCancelledOrSuperseded_andLeadTimesStayUntouched() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);

        for (String deadStatus : new String[] {"CANCELLED", "SUPERSEDED"}) {
            // forced by SQL so THIS guard, not the quote cascade or auto-withdraw, is what refuses
            jdbc.update("UPDATE sales.pricing_request SET status = :s, cancelled_at = now() WHERE pricing_request_id = :id",
                java.util.Map.of("s", deadStatus, "id", prId));
            assertConflict(() -> leadTimeChangeService.approve(change.id(), change.version(), salesActor));
        }
        assertLeadTime(a1, 30, 45);
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);
    }

    /** A stock line can never be part of a lead-time change (it has no factory lead time). */
    @Test
    void aStockLineInAChangeIs400() {
        jdbc.update("UPDATE sales.pricing_request_item SET stock_source = 'IN_THAILAND' WHERE pricing_request_item_id = :id",
            java.util.Map.of("id", a3));

        assertBadRequest(body("ok", line(a3, 100, 120)));
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor)).isEmpty();
    }

    /** create is refused (409) when the request is CANCELLED/SUPERSEDED even though the quote row is still current. */
    @Test
    void createIsRefusedWhenThePricingRequestIsSuperseded() {
        jdbc.update("UPDATE sales.pricing_request SET status = 'SUPERSEDED' WHERE pricing_request_id = :id",
            java.util.Map.of("id", prId));

        assertConflict(() -> leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor));
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor)).isEmpty();
    }

    /** create is refused (409) when the deal is no longer ACTIVE. */
    @Test
    void createIsRefusedWhenTheDealIsNotActive() {
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :id", java.util.Map.of("id", ticketId));

        assertConflict(() -> leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor));
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor)).isEmpty();
    }

    // ── round 2: a price revision retires the quote row the change was raised on ─────────────

    @Test
    void aPriceRevisionAutoWithdrawsThePendingChangeOnTheOldQuoteRow_andImportCanOpenANewOneOnTheNewRow() {
        FactoryQuoteDto v1 = factoryQuoteService.receive(quoteA.id(), receiveAll(quoteA, "THB", "PER_PIECE"), importActor);
        LeadTimeChangeDto change = leadTimeChangeService.create(v1.id(), body("r1", line(a1, 100, 120)), importActor);

        FactoryQuoteDto v2 = factoryQuoteService.receive(v1.id(), receiveAll(v1, "THB", "PER_PIECE"), importActor);
        assertThat(v2.id()).as("a second receive on a RESPONSE_RECEIVED quote is a revision row").isNotEqualTo(v1.id());

        LeadTimeChangeDto old = leadTimeChangeService.listForPricingRequest(prId, importActor).stream()
            .filter(c -> c.id() == change.id()).findFirst().orElseThrow();
        assertThat(old.status()).isEqualTo(LeadTimeChangeStatus.WITHDRAWN);
        assertThat(old.decisionReason()).isEqualTo("ใบราคาถูกแก้ไข — คำขอเดิมถูกยกเลิกอัตโนมัติ");
        assertThat(eventCount(prId, PricingRequestEventKind.LEAD_TIME_CHANGE_WITHDRAWN)).isEqualTo(1L);
        // the owning rep can no longer approve the dead request, and nothing was applied
        assertConflict(() -> leadTimeChangeService.approve(old.id(), old.version(), salesActor));
        assertLeadTime(a1, 30, 45);
        // import may raise a fresh one on the NEW current row, but not on the retired one
        assertThat(leadTimeChangeService.create(v2.id(), body("r2", line(a1, 110, 130)), importActor).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);
        assertConflict(() -> leadTimeChangeService.create(v1.id(), body("r3", line(a2, 110, 130)), importActor));
    }

    // ── round 2: optimistic concurrency between import's edit and the approver's decision ────

    @Test
    void approveOrRejectWithAStaleVersionIs409_afterImportEdited_andNothingIsApplied() {
        LeadTimeChangeDto change = leadTimeChangeService.create(quoteA.id(), body("r1", line(a1, 100, 120)), importActor);
        assertThat(change.version()).isEqualTo(1);
        LeadTimeChangeDto edited = leadTimeChangeService.update(change.id(), body("r1 edited", line(a2, 150, 180)), importActor);
        assertThat(edited.version()).as("an import edit bumps the version").isEqualTo(2);

        // the approver still holds version 1
        assertConflict(() -> leadTimeChangeService.approve(change.id(), 1, salesActor));
        assertConflict(() -> leadTimeChangeService.reject(change.id(), new RejectLeadTimeChangeRequest("no", 1), salesActor));
        assertLeadTime(a1, 30, 45);
        assertLeadTime(a2, 60, 90);
        assertThat(leadTimeChangeService.listForPricingRequest(prId, importActor).get(0).status())
            .isEqualTo(LeadTimeChangeStatus.PENDING);

        // a missing expectedVersion is a caller bug (400), and the current version still works
        assertThatThrownBy(() -> leadTimeChangeService.approve(change.id(), null, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(leadTimeChangeService.approve(change.id(), edited.version(), salesActor).status())
            .isEqualTo(LeadTimeChangeStatus.APPROVED);
        assertLeadTime(a2, 150, 180);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private long itemId(String model) {
        return pricingRequestService.get(prId, importActor).items().stream()
            .filter(i -> model.equals(i.model())).findFirst().orElseThrow().id();
    }

    private void assertLeadTime(long itemId, int min, int max) {
        PricingRequestDtos.PricingRequestItemDto item = pricingRequestService.get(prId, importActor).items().stream()
            .filter(i -> i.id() == itemId).findFirst().orElseThrow();
        assertThat(item.leadTimeMinDays()).as("item %d min", itemId).isEqualTo(min);
        assertThat(item.leadTimeMaxDays()).as("item %d max", itemId).isEqualTo(max);
    }

    private static LineInput line(long itemId, Integer min, Integer max) {
        return new LineInput(itemId, min, max);
    }

    private static CreateLeadTimeChangeRequest body(String reason, LineInput... lines) {
        return new CreateLeadTimeChangeRequest(reason, List.of(lines));
    }

    private void assertBadRequest(CreateLeadTimeChangeRequest request) {
        assertThatThrownBy(() -> leadTimeChangeService.create(quoteA.id(), request, importActor))
            .as("create %s", request)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
