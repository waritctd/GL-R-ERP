package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.dealparty.DealPartyDtos.AddPartyRequest;
import th.co.glr.hr.dealparty.DealPartyRepository;
import th.co.glr.hr.dealparty.DealPartyService;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeRequest;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeResult;
import th.co.glr.hr.dealparty.DealPartyDtos.DealPartyDto;
import th.co.glr.hr.dealparty.DealPartyDtos.OpenDealDto;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * V204 deal party (docs/decisions/deal-party-model.md), against a real database: the backfill, the first
 * party every new deal gets, sibling bids with their own customer snapshot / number family / live-quotation
 * lock, the bid outcome in its three shapes (one winner, two winners with a fork, keep-waiting), the editor's
 * open-deal lookup, and the access rules.
 */
class DealPartyIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {
    private static final String V204 = "db/migration/V204__deal_party.sql";

    private DealPartyService partyService;
    private TicketRepository tickets;
    private CustomerRepository customers;
    private UserPrincipal otherSalesActor;

    @BeforeEach
    void wirePartyService() {
        tickets = new TicketRepository(jdbc);
        customers = new CustomerRepository(jdbc);
        partyService = transactional(new DealPartyService(new DealPartyRepository(jdbc), tickets, customers,
            new ContactRepository(jdbc), quotationService));
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        otherSalesActor = actor(createEmployee(employees, "พนักงานขาย อีกคน", "sales-other-dp@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");
    }

    // ── backfill and the first party ─────────────────────────────────────────────────────────

    @Test
    void newDeal_getsItsCustomerAsFirstBuyerPartyAndHeadline() {
        long ticketId = deal(salesActor);
        List<DealPartyDto> parties = partyService.list(ticketId, salesActor);
        assertThat(parties).hasSize(1);
        DealPartyDto first = parties.get(0);
        assertThat(first.role()).isEqualTo("BUYER");
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.headline()).isTrue();
        assertThat(first.customerId()).isEqualTo(summary(ticketId).customerId());
    }

    @Test
    void quotationWithoutParty_attachesToTheSolePartyAndMarksItQuoted() {
        long ticketId = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticketId, draft().withRecipientType("BUYER"), salesActor);
        DealPartyDto party = partyService.list(ticketId, salesActor).get(0);
        assertThat(created.partyId()).isEqualTo(party.id());
        assertThat(created.dealBuyerCount()).isEqualTo(1);
        assertThat(party.latestQuotationId()).isEqualTo(created.id());
        assertThat(party.status()).isEqualTo("QUOTED");
    }

    @Test
    void v204Backfill_runsTwiceWithoutDuplicatingParties() {
        long ticketId = deal(salesActor);
        quotationService.create(ticketId, draft().withRecipientType("BUYER"), salesActor);
        // Simulate a pre-V204 deal: strip the party rows and the quotation's link, then run the file verbatim twice.
        jdbc.update("UPDATE sales.quotation SET party_id = NULL WHERE ticket_id = :id", Map.of("id", ticketId));
        jdbc.update("DELETE FROM sales.deal_party WHERE ticket_id = :id", Map.of("id", ticketId));
        runV204();
        runV204();
        List<DealPartyDto> parties = partyService.list(ticketId, salesActor);
        assertThat(parties).hasSize(1);
        assertThat(parties.get(0).status()).isEqualTo("QUOTED");
        assertThat(parties.get(0).headline()).isTrue();
        Long partyId = jdbc.queryForObject("SELECT party_id FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", ticketId), Long.class);
        assertThat(partyId).isEqualTo(parties.get(0).id());
    }

    // ── sibling bids ─────────────────────────────────────────────────────────────────────────

    @Test
    void secondContractor_quotesUnderItsOwnCustomerNumberFamilyAndLock() {
        long ticketId = deal(salesActor);
        DealQuotationDto first = quotationService.create(ticketId, draft().withRecipientType("BUYER"), salesActor);
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา บี");
        DealPartyDto partyB = partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "buyer", null), salesActor);
        assertThat(partyB.role()).isEqualTo("BUYER");
        assertThat(partyB.headline()).isFalse();

        // The first contractor's live DRAFT does not block B's first document (per-party lock) ...
        DealQuotationDto second = quotationService.create(ticketId, draft().withPartyId(partyB.id()), salesActor);
        // ... which prints B, not the deal's customer, gets its own base number, and counts both bidders.
        assertThat(second.customerName()).isEqualTo(contractorB.name());
        assertThat(second.partyId()).isEqualTo(partyB.id());
        assertThat(second.recipientType()).isEqualTo("BUYER");
        assertThat(second.number()).endsWith("-1");
        assertThat(second.number()).isNotEqualTo(first.number());
        assertThat(baseOf(second.number())).isNotEqualTo(baseOf(first.number()));
        assertThat(second.dealBuyerCount()).isEqualTo(2);
        assertThat(partyService.list(ticketId, salesActor)).extracting(DealPartyDto::status)
            .containsExactly("QUOTED", "QUOTED");

        // B's own second document IS blocked by B's live one, with the live number in the refusal.
        ApiException blocked = refusal(() -> quotationService.create(ticketId, draft().withPartyId(partyB.id()), salesActor),
            HttpStatus.CONFLICT);
        assertThat(blocked.getMessage()).contains(second.number());
    }

    @Test
    void addParty_refusesDuplicateRoleAndForeignPartyOnCreate() {
        long ticketId = deal(salesActor);
        long otherTicket = deal(salesActor);
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา ซ้ำ");
        DealPartyDto onOther = partyService.add(otherTicket, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor);
        partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor);
        refusal(() -> partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor),
            HttpStatus.CONFLICT);
        refusal(() -> quotationService.create(ticketId, draft().withPartyId(onOther.id()), salesActor), HttpStatus.BAD_REQUEST);
    }

    // ── bid outcome ──────────────────────────────────────────────────────────────────────────

    @Test
    void outcome_oneWinner_becomesBillToAndTheRestLose() {
        long ticketId = deal(salesActor);
        String code = summary(ticketId).code();
        DealPartyDto partyA = partyService.list(ticketId, salesActor).get(0);
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา ชนะ");
        DealPartyDto partyB = partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor);
        approved(ticketId, draft().withRecipientType("BUYER"), salesActor);
        DealQuotationDto quoteB = approved(ticketId, draft().withPartyId(partyB.id()), salesActor);

        BidOutcomeResult result = partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(partyB.id()), null, "NOT_AWARDED", "PRICE"), salesActor);

        assertThat(result.childTicketIds()).isEmpty();
        TicketSummaryDto after = summary(ticketId);
        assertThat(after.customerId()).isEqualTo(contractorB.id());
        assertThat(after.customerName()).isEqualTo(contractorB.name());
        assertThat(after.title()).isEqualTo(contractorB.name());
        assertThat(after.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(after.code()).isEqualTo(code);
        Map<Long, DealPartyDto> byId = partyService.list(ticketId, salesActor).stream()
            .collect(java.util.stream.Collectors.toMap(DealPartyDto::id, p -> p));
        assertThat(byId.get(partyB.id()).status()).isEqualTo("AWARDED");
        assertThat(byId.get(partyB.id()).headline()).isTrue();
        assertThat(byId.get(partyA.id()).status()).isEqualTo("NOT_AWARDED");
        assertThat(byId.get(partyA.id()).notAwardedReason()).isEqualTo("PRICE");
        assertThat(byId.get(partyA.id()).headline()).isFalse();
        assertThat(quotationService.get(quoteB.id(), salesActor).partyStatus()).isEqualTo("AWARDED");
        assertThat(eventKinds(ticketId)).contains(TicketEventKind.BID_OUTCOME_RECORDED, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION);
        // A second outcome naming the already-awarded party is refused.
        refusal(() -> partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(partyB.id()), null, "NOT_AWARDED", null), salesActor), HttpStatus.CONFLICT);
    }

    @Test
    void outcome_twoWinners_forksAChildDealForTheSecond() {
        long ticketId = deal(salesActor);
        String code = summary(ticketId).code();
        DealPartyDto partyA = partyService.list(ticketId, salesActor).get(0);
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา แบ่งงาน");
        DealPartyDto partyB = partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor);
        approved(ticketId, draft().withRecipientType("BUYER"), salesActor);
        DealQuotationDto quoteB = approved(ticketId, draft().withPartyId(partyB.id()), salesActor);

        BidOutcomeResult result = partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(partyA.id(), partyB.id()), partyA.id(), "NOT_AWARDED", null), salesActor);

        assertThat(result.childTicketIds()).hasSize(1);
        long childId = result.childTicketIds().get(0);
        TicketSummaryDto child = summary(childId);
        assertThat(child.code()).isEqualTo(code + "-A");
        assertThat(child.customerId()).isEqualTo(contractorB.id());
        assertThat(child.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(child.projectId()).isEqualTo(summary(ticketId).projectId());
        assertThat(jdbc.queryForObject("SELECT parent_ticket_id FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", childId), Long.class)).isEqualTo(ticketId);
        // B's document moved to the child, with the child's own AWARDED party.
        DealQuotationDto movedB = quotationService.get(quoteB.id(), salesActor);
        assertThat(movedB.ticketId()).isEqualTo(childId);
        List<DealPartyDto> childParties = partyService.list(childId, salesActor);
        assertThat(childParties).hasSize(1);
        assertThat(childParties.get(0).status()).isEqualTo("AWARDED");
        assertThat(movedB.partyId()).isEqualTo(childParties.get(0).id());
        // On the parent: A awarded (bill-to unchanged), B marked as split with the link.
        Map<Long, DealPartyDto> byId = partyService.list(ticketId, salesActor).stream()
            .collect(java.util.stream.Collectors.toMap(DealPartyDto::id, p -> p));
        assertThat(byId.get(partyA.id()).status()).isEqualTo("AWARDED");
        assertThat(byId.get(partyB.id()).status()).isEqualTo("AWARDED_SPLIT");
        assertThat(byId.get(partyB.id()).splitTicketId()).isEqualTo(childId);
        assertThat(byId.get(partyB.id()).splitTicketCode()).isEqualTo(code + "-A");
        assertThat(summary(ticketId).salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(eventKinds(childId)).contains(TicketEventKind.DEAL_SPLIT_FROM_PARENT);
    }

    @Test
    void outcome_keepWaiting_leavesTheOtherBidderOpen() {
        long ticketId = deal(salesActor);
        DealPartyDto partyA = partyService.list(ticketId, salesActor).get(0);
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา รอผล");
        DealPartyDto partyB = partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor);
        approved(ticketId, draft().withRecipientType("BUYER"), salesActor);
        // B has only a DRAFT: still waiting, and that draft must survive A's confirmation.
        DealQuotationDto draftB = quotationService.create(ticketId, draft().withPartyId(partyB.id()), salesActor);

        partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(partyA.id()), null, "KEEP_WAITING", null), salesActor);

        Map<Long, DealPartyDto> byId = partyService.list(ticketId, salesActor).stream()
            .collect(java.util.stream.Collectors.toMap(DealPartyDto::id, p -> p));
        assertThat(byId.get(partyA.id()).status()).isEqualTo("AWARDED");
        assertThat(byId.get(partyB.id()).status()).isEqualTo("QUOTED");
        assertThat(quotationService.get(draftB.id(), salesActor).docStatus()).isEqualTo("DRAFT");
        // Still bidding: B's own document may go to approval after A's order was confirmed ...
        quotationService.submit(draftB.id(), salesActor);
        quotationService.approve(draftB.id(), new DealQuotationRequests.ApproveRequest(null), salesManagerActor);
        // ... and a later outcome awards B on a child deal of its own.
        BidOutcomeResult later = partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(partyB.id()), null, "NOT_AWARDED", null), salesActor);
        assertThat(later.childTicketIds()).hasSize(1);
        assertThat(summary(later.childTicketIds().get(0)).customerId()).isEqualTo(contractorB.id());
        assertThat(summary(ticketId).customerId()).isNotEqualTo(contractorB.id());
    }

    @Test
    void outcome_refusesAWinnerWhoseQuotationIsNotApproved() {
        long ticketId = deal(salesActor);
        DealPartyDto partyA = partyService.list(ticketId, salesActor).get(0);
        quotationService.create(ticketId, draft().withRecipientType("BUYER"), salesActor); // DRAFT only
        ApiException refused = refusal(() -> partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(partyA.id()), null, "NOT_AWARDED", null), salesActor), HttpStatus.CONFLICT);
        assertThat(refused.getMessage()).contains("ยังไม่อนุมัติ");
        assertThat(partyService.list(ticketId, salesActor).get(0).status()).isNotEqualTo("AWARDED");
    }

    // ── the editor's open-deal lookup ────────────────────────────────────────────────────────

    @Test
    void openDealsForProject_findsTheRepsOwnPreOutcomeDealByNormalisedName() {
        long ticketId = deal(salesActor);
        List<OpenDealDto> found = partyService.openDealsForProject("  " + PROJECT_NAME.toUpperCase() + "  ", salesActor);
        assertThat(found).extracting(OpenDealDto::id).contains(ticketId);
        OpenDealDto deal = found.stream().filter(d -> d.id() == ticketId).findFirst().orElseThrow();
        assertThat(deal.buyerCount()).isEqualTo(1);
        assertThat(deal.parties()).hasSize(1);
        // Another rep does not see it; the sales manager does.
        assertThat(partyService.openDealsForProject(PROJECT_NAME, otherSalesActor)).extracting(OpenDealDto::id)
            .doesNotContain(ticketId);
        assertThat(partyService.openDealsForProject(PROJECT_NAME, salesManagerActor)).extracting(OpenDealDto::id)
            .contains(ticketId);
        assertThat(partyService.openDealsForProject("ไม่มีโครงการนี้", salesActor)).isEmpty();
    }

    // ── access ───────────────────────────────────────────────────────────────────────────────

    @Test
    void access_nonOwnerSalesIsRefused_salesManagerIsNot() {
        long ticketId = deal(salesActor);
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา สิทธิ์");
        refusal(() -> partyService.list(ticketId, otherSalesActor), HttpStatus.FORBIDDEN);
        refusal(() -> partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), otherSalesActor),
            HttpStatus.FORBIDDEN);
        refusal(() -> partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(1L), null, null, null), otherSalesActor), HttpStatus.FORBIDDEN);
        refusal(() -> partyService.list(ticketId, actor(otherSalesActor.id(), "account")), HttpStatus.FORBIDDEN);
        assertThat(partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesManagerActor)
            .customerName()).isEqualTo(contractorB.name());
        assertThat(partyService.list(ticketId, salesManagerActor)).hasSize(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private CustomerDto contractor(String name) {
        return customers.create(name, null, "99 ถนนผู้รับเหมา", "สนญ.", "02-999-0000");
    }

    private TicketSummaryDto summary(long ticketId) {
        return tickets.findSummaryById(ticketId).orElseThrow();
    }

    private List<String> eventKinds(long ticketId) {
        return jdbc.query("SELECT kind FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id",
            Map.of("id", ticketId), (rs, n) -> rs.getString("kind"));
    }

    private static String baseOf(String number) {
        return number.substring(0, number.lastIndexOf('-'));
    }

    private void runV204() {
        ClassPathResource resource = new ClassPathResource(V204);
        assertThat(resource.exists()).as("migration file %s must exist on the classpath", V204).isTrue();
        try {
            jdbc.getJdbcOperations().execute(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
