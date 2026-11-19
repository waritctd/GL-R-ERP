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

/** Verifies per-item commission-weight permissions through the real service and repository. */
class ItemWeightMultiplierAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

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
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // Same collaborator-mocking rationale as StockDeclarationAuthzIntegrationTest: setItemWeightMultipliers never
        // calls PricingRequestService. The stub on cancelOpenForTicket only guards against a future edit routing
        // through markLost/cancel.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล น้ำหนัก", "weight-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(createEmployee(employees, "พนักงานขายอื่น น้ำหนัก", "weight-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า น้ำหนัก", "weight-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ น้ำหนัก", "weight-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี น้ำหนัก", "weight-account@glr.co.th"), "account");
        hrUser = principal(createEmployee(employees, "ฝ่ายบุคคล น้ำหนัก", "weight-hr@glr.co.th"), "hr");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย น้ำหนัก", "weight-salesmgr@glr.co.th"), "sales_manager");
    }

    /** The case this gate exists for: reserveStock's own authorized declarer — the deal's OWNER, a sales role —
     * must NOT also be able to approve this deal's commission weight. The two decisions (declare quantity vs.
     * approve weight) are deliberately held by different people. */
    @Test
    void dealOwner_salesRole_isRefused_andTheRowIsUnmoved() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        assertThatThrownBy(() -> ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 2), owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertNothingMoved(itemId);
    }

    @Test
    void salesManager_setsTheWeight_andTheRowIsActuallyWritten() {
        long ticketId = createTicketWithOneItem();
        long itemId = onlyItemId(ticketId);

        ticketService.setItemWeightMultipliers(ticketId, weightRequest(itemId, 2), salesManagerUser);

        assertThat(weightMultiplier(itemId)).isEqualTo(2);
    }

    private void assertNothingMoved(long itemId) {
        assertThat(weightMultiplier(itemId)).isEqualTo(1);
    }

    private static ItemWeightMultiplierRequest weightRequest(long itemId, int weightMultiplier) {
        return new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, weightMultiplier)));
    }

    /** Same fixture shape as StockDeclarationAuthzIntegrationTest#createTicketWithOneItem: a deal parked at
     * DealStage#ORDER_RECEIVED with exactly one item. */
    private long createTicketWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบน้ำหนัก", "NORMAL", "ลูกค้าทดสอบน้ำหนัก", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล น้ำหนัก");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private int weightMultiplier(long itemId) {
        Integer value = jdbc.queryForObject("SELECT weight_multiplier FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), Integer.class);
        return value == null ? 0 : value;
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-weight@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
