package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.dealparty.DealPartyDtos.AddPartyRequest;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeRequest;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeResult;
import th.co.glr.hr.dealparty.DealPartyDtos.DealPartyDto;
import th.co.glr.hr.dealparty.DealPartyRepository;
import th.co.glr.hr.dealparty.DealPartyService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * DealPartyService against a real database: add-on order (สั่งเพิ่ม) authorisation and numbering, the order-chain
 * money endpoint (import never sees a price), and the party access rules.
 */
class DealPartyIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {
    private DealPartyService partyService;
    private TicketRepository tickets;
    private CustomerRepository customers;
    private UserPrincipal otherSalesActor;

    @BeforeEach
    void wirePartyService() {
        tickets = new TicketRepository(jdbc);
        customers = new CustomerRepository(jdbc);
        partyService = transactional(new DealPartyService(new DealPartyRepository(jdbc), tickets, customers,
            new ContactRepository(jdbc), quotationService, ticketService));
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        otherSalesActor = actor(createEmployee(employees, "พนักงานขาย อีกคน", "sales-other-dp@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");
    }

    // ── add-on orders (สั่งเพิ่ม) ───────────────────────────────────────────────────────────

    @Test
    void addOnOrder_numbersFromTheRoot_andLeavesBidSplitLettersAlone() {
        long ticketId = deal(salesActor);
        String code = summary(ticketId).code();
        DealPartyDto partyA = partyService.list(ticketId, salesActor).get(0);

        var first = partyService.createAddOnOrder(ticketId, salesActor);
        var second = partyService.createAddOnOrder(first.ticketId(), salesActor);

        assertThat(first.code()).isEqualTo(code + "-2");
        assertThat(second.code()).as("add-on of an add-on hangs under the root").isEqualTo(code + "-3");
        assertThat(second.parentTicketId()).isEqualTo(ticketId);
        assertThat(summary(ticketId).addOnCount()).isEqualTo(2);
        assertThat(summary(first.ticketId()).addOnCount()).as("an add-on reports its root's count").isEqualTo(2);
        TicketSummaryDto child = summary(second.ticketId());
        assertThat(child.childKind()).isEqualTo("ADD_ON");
        assertThat(child.salesStage()).as("starts where the buyer quotation is drafted").isEqualTo(DealStage.QUOTE_BUYER);
        assertThat(eventKinds(second.ticketId())).contains(TicketEventKind.STAGE_CHANGED);
        assertThat(child.parentCode()).isEqualTo(code);
        assertThat(child.customerId()).isEqualTo(summary(ticketId).customerId());
        assertThat(partyService.list(second.ticketId(), salesActor)).singleElement()
            .satisfies(p -> assertThat(p.customerId()).isEqualTo(partyA.customerId()));
        assertThat(eventKinds(second.ticketId())).contains(TicketEventKind.ADD_ON_ORDER_CREATED);
        assertThat(eventKinds(ticketId)).contains(TicketEventKind.ADD_ON_ORDER_CREATED);

        // A bid split after the add-ons still takes the first letter, and is a BID_SPLIT.
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา แยกหลังสั่งเพิ่ม");
        DealPartyDto partyB = partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor);
        approved(ticketId, draft().withRecipientType("BUYER"), salesActor);
        approved(ticketId, draft().withPartyId(partyB.id()), salesActor);
        BidOutcomeResult result = partyService.recordBidOutcome(ticketId,
            new BidOutcomeRequest(List.of(partyA.id(), partyB.id()), partyA.id(), "NOT_AWARDED", null), salesActor);
        TicketSummaryDto split = summary(result.childTicketIds().get(0));
        assertThat(split.code()).isEqualTo(code + "-A");
        assertThat(split.childKind()).isEqualTo("BID_SPLIT");

        // An add-on whose root was hard-deleted (parent_ticket_id SET NULL) is its own root, not a 500.
        jdbc.update("UPDATE sales.ticket SET parent_ticket_id = NULL WHERE ticket_id = :id", Map.of("id", first.ticketId()));
        assertThat(partyService.createAddOnOrder(first.ticketId(), salesActor).code()).isEqualTo(code + "-2-2");
    }

    @Test
    void addOnOrder_isForOwnersAndGrantHoldersOnly_andOnlyOnActiveDeals() {
        long ticketId = deal(salesActor);
        refusal(() -> partyService.createAddOnOrder(ticketId, otherSalesActor), HttpStatus.FORBIDDEN);
        refusal(() -> partyService.createAddOnOrder(ticketId, actor(otherSalesActor.id(), "account")), HttpStatus.FORBIDDEN);
        refusal(() -> partyService.createAddOnOrder(ticketId, salesManagerActor), HttpStatus.FORBIDDEN);
        refusal(() -> partyService.createAddOnOrder(-1L, salesActor), HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED' WHERE ticket_id = :id", Map.of("id", ticketId));
        refusal(() -> partyService.createAddOnOrder(ticketId, salesActor), HttpStatus.CONFLICT);
    }

    @Test
    void orders_totalsSumTheLiveOrders_skipUnreadableSiblings_andImportGetsNoMoney() {
        long rootId = deal(salesActor);
        approved(rootId, draft(), salesActor);
        var live = partyService.createAddOnOrder(rootId, salesActor);
        var cancelled = partyService.createAddOnOrder(rootId, salesActor);
        approved(live.ticketId(), draft(), salesActor);
        approved(cancelled.ticketId(), draft(), salesActor);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED' WHERE ticket_id = :id", Map.of("id", cancelled.ticketId()));

        var chain = partyService.orders(live.ticketId(), salesActor);
        assertThat(chain.rootTicketId()).isEqualTo(rootId);
        assertThat(chain.orders()).extracting(o -> o.orderNo()).containsExactly(1, 2, 3);
        assertThat(chain.orders().get(0).displayCode()).isEqualTo(chain.rootCode() + "-1");
        assertThat(chain.orders().get(1).displayCode()).isEqualTo(live.code());
        assertThat(chain.orders().get(0).valueExVat()).isPositive();
        // The cancelled add-on stays listed with its value but is not in the totals: two live orders of equal value.
        assertThat(chain.orders().get(2).lifecycle()).isEqualTo("CANCELLED");
        assertThat(chain.orders().get(2).valueExVat()).isPositive();
        assertThat(chain.totals().valueExVat())
            .isEqualByComparingTo(chain.orders().get(0).valueExVat().add(chain.orders().get(1).valueExVat()));

        // A rep who owns only the root does not see a sibling someone else owns (it is skipped, not a 403).
        jdbc.update("UPDATE sales.ticket SET created_by = :o, keyed_by = :o WHERE ticket_id = :id",
            Map.of("o", otherSalesActor.id(), "id", live.ticketId()));
        assertThat(partyService.orders(rootId, salesActor).orders()).extracting(o -> o.ticketId())
            .containsExactly(rootId, cancelled.ticketId());
        refusal(() -> partyService.orders(live.ticketId(), salesActor), HttpStatus.FORBIDDEN);

        // import reads the chain (inside its scope) but never a price.
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id",
            Map.of("s", DealStage.ORDER_RECEIVED, "id", rootId));
        var forImport = partyService.orders(rootId, actor(otherSalesActor.id(), "import"));
        assertThat(forImport.orders()).extracting(o -> o.ticketId()).contains(rootId);
        assertThat(forImport.orders()).allSatisfy(o -> {
            assertThat(o.valueExVat()).isNull();
            assertThat(o.received()).isNull();
            assertThat(o.outstanding()).isNull();
        });
        assertThat(forImport.totals().valueExVat()).isNull();
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

    /** Confirming the order from a SECOND buyer's quotation (not through the bid outcome) moves the headline and bill-to to it. */
    @Test
    void confirmOrderFromAnotherBuyersQuotation_movesHeadlineAndBillToToThatBuyer() {
        long ticketId = deal(salesActor);
        CustomerDto contractorB = contractor("บจก.ผู้รับเหมา ชนะ");
        DealPartyDto partyB = partyService.add(ticketId, new AddPartyRequest(contractorB.id(), "BUYER", null), salesActor);
        approved(ticketId, draft().withRecipientType("BUYER"), salesActor);
        var quotationB = approved(ticketId, draft().withPartyId(partyB.id()), salesActor);

        quotationService.confirmOrderFromDirectQuotation(quotationB.id(), salesActor);

        assertThat(partyService.list(ticketId, salesActor)).filteredOn(DealPartyDto::headline).singleElement()
            .satisfies(p -> assertThat(p.id()).isEqualTo(partyB.id()));
        assertThat(summary(ticketId).customerId()).isEqualTo(contractorB.id());
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
}
