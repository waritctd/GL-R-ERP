package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres evidence for {@link TicketService#reserveStock}'s role gate: sales declares stock coverage
 * on their own deal, the CEO may correct, and import / non-owning roles are refused. A mocked
 * {@link TicketRepository} cannot prove the decision reaches the UPDATE, so each refusal re-reads
 * {@code sales.ticket_item.qty_from_stock} straight out of Postgres to prove nothing moved.
 *
 * <p>{@code qty_from_stock} is the input to the owning rep's STOCK_BONUS, so writing it wrongly is writing
 * someone's pay. Services are hand-wired with {@code new}, so {@code @Transactional} is inert here (see
 * {@link AbstractPostgresIntegrationTest}); the "unmoved" assertions hold because the guard throws before any write.
 */
class StockDeclarationAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;
    private CommissionRepository commissions;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal hrUser;
    private UserPrincipal salesManagerUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        commissions = new CommissionRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // PricingRequestService is mocked exactly as in DealTrackingAndActivityIntegrationTest: reserveStock never
        // calls it. The stub on cancelOpenForTicket exists only so Mockito's default null return cannot NPE if a
        // future edit routes through markLost/cancel.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "stock-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee(employees, "พนักงานขายอื่น", "stock-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า", "stock-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "stock-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "stock-account@glr.co.th"), "account");
        hrUser = principal(createEmployee(employees, "ฝ่ายบุคคล", "stock-hr@glr.co.th"), "hr");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย", "stock-salesmgr@glr.co.th"), "sales_manager");
    }

    /** The case the widened gate must NOT open. otherSalesRep holds the same sales role as the owner and passes the
     * coarse role pre-filter — the only thing standing between them and another rep's commission input is the
     * per-row ownership test. */
    @Test
    void salesRepWhoDoesNotOwnTheDeal_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "100.00"), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertNothingMoved(ticketId, itemId);
    }

    /** The rep's per-line declaration is withdrawn: reps declare through POST /procurement-source only. */
    @Test
    void dealOwner_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "40.00"), owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(e.getMessage()).contains("จัดซื้อสินค้า");
            });

        assertNothingMoved(ticketId, itemId);
    }

    /**
     * The routing half of the rule: a full-coverage declaration (CEO only now) routes the deal
     * to FROM_STOCK / DELIVERY_SCHEDULING.
     *
     * <p>The deal starts at {@code ORDER_RECEIVED}, not {@code LEAD_APPROACH}: {@code autoAdvanceStage}
     * has no stage precondition, so a full-coverage declaration would jump the deal straight to
     * {@code DELIVERY_SCHEDULING} from wherever it was — including an untouched lead — which also
     * blocked {@code ISSUE_IMPORT_REQUEST}. That risk is closed by {@code reserveStock}'s stage
     * floor (see {@code TicketService.stockCoverageStageReached} and
     * {@code StageFactGateIntegrationTest}); the ROUTING above the floor is deliberately unchanged,
     * which is what this test still pins.
     */
    @Test
    void ceoFullCoverage_routesTheDealToDeliveryScheduling() {
        long ceoDeclared = createTicketWithOneItem();
        assertThat(salesStage(ceoDeclared)).isEqualTo(DealStage.ORDER_RECEIVED);

        ticketService.reserveStock(ceoDeclared, declare(onlyItemId(ceoDeclared), "100.00"), ceoUser);

        assertThat(fulfillmentStatus(ceoDeclared)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        // PROCUREMENT is skipped — a fully-stocked deal has no import journey.
        assertThat(salesStage(ceoDeclared)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    /** Every axis the refused call could have touched, re-read from Postgres. */
    private void assertNothingMoved(long ticketId, long itemId) {
        assertThat(qtyFromStock(itemId)).isEqualByComparingTo("0.00");
        assertThat(stockNote(itemId)).isNull();
        assertThat(fulfillmentStatus(ticketId)).isNull();
        assertThat(salesStage(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(stockReservedEvents(ticketId)).isZero();
    }

    private static StockReservationRequest declare(long itemId, String qtyFromStock) {
        return new StockReservationRequest(List.of(
            new StockReservationRequest.Line(itemId, new BigDecimal(qtyFromStock), "มีของในสต็อก")));
    }

    /** A deal parked at DealStage#ORDER_RECEIVED — the stage floor a stock declaration must clear since the
     * stage-fact-gate branch (below it every call 409s regardless of role, which would make every case in this
     * class pass for the wrong reason). */
    private long createTicketWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบสต็อก", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ทดสอบ");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private BigDecimal qtyFromStock(long itemId) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), BigDecimal.class);
    }

    private String stockNote(long itemId) {
        return jdbc.queryForObject("SELECT stock_note FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), String.class);
    }

    private String fulfillmentStatus(long ticketId) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), String.class);
    }

    private String salesStage(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), String.class);
    }

    private int stockReservedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STOCK_RESERVED), Integer.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-stock@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
