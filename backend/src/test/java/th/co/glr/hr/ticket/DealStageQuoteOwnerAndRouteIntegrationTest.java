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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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

/** Real-Postgres proof for Part B (the S4/S5 split) and Part C (route-dependent stages need no written
 * justification), plus the authorization surface Part B widens. Modelled on
 * DealTrackingAndActivityIntegrationTest, which covers the tracking gate itself. */
class DealStageQuoteOwnerAndRouteIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal otherRep;
    private UserPrincipal salesManager;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "พนักงานขาย เจ้าของดีล", "sales-owner-s5@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        otherRep = principal(createEmployee(employees, "พนักงานขาย คนอื่น", "sales-other-s5@glr.co.th"), "sales");
        salesManager = principal(createEmployee(employees, "ผจก.ขาย", "salesmgr-s5@glr.co.th"), "sales_manager");
        ceo = principal(createEmployee(employees, "ซีอีโอ", "ceo-s5@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "บัญชี", "account-s5@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า", "import-s5@glr.co.th"), "import");
    }

    /** Wrong-way-round authz. QUOTE_OWNER is in SALES_TARGET_STAGES, so the money roles and a rep who does not own
     * this deal must be refused — and the stage must genuinely not move, not merely throw. */
    @Test
    void quoteOwnerIsRefusedToAccountImportAndANonOwningRep() {
        long ticketId = readyToAdvanceFrom(DealStage.SPEC_APPROVED);

        for (UserPrincipal denied : List.of(accountActor, importActor, otherRep)) {
            assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, denied))
                .as("%s must not reach QUOTE_OWNER", denied.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
            assertThat(stageOf(ticketId)).as("%s must not have moved the deal", denied.role())
                .isEqualTo(DealStage.SPEC_APPROVED);
        }
    }

    /** …and the wrong-way-round half: stepping over a MANDATORY stage still costs a sentence. QUOTE_BUYER ->
     * ORDER_RECEIVED steps over NEGOTIATION. */
    @Test
    void skippingAMandatoryStage_isStillRefusedWithoutANoteAndTheStageDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        // ORDER_RECEIVED is fact-gated as of the stage-fact-gate branch, and that gate runs BEFORE the note rule —
        // without a verified customer order this call would now 409 on the fact and never reach the rule under test.
        jdbc.update("UPDATE sales.ticket SET payment_status = :status WHERE ticket_id = :id",
            new MapSqlParameterSource()
                .addValue("status", PaymentTrack.CUSTOMER_CONFIRMED).addValue("id", ticketId));

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(400);
                assertThat(e.getMessage()).isEqualTo("การข้ามขั้นตอนต้องระบุเหตุผล");
            });
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);

        ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, "ลูกค้าสั่งซื้อทันที", ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
    }

    /** A deal parked at stage with the tracking fields the B1 forward-advance gate demands (a next follow-up date
     * and one activity logged after the last stage change), so that a test asserting the NOTE rule fails on the
     * note rule and nothing else. */
    private long readyToAdvanceFrom(String stage) {
        long ticketId = createTicket();
        jdbc.update("UPDATE sales.ticket SET sales_stage = :stage WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("stage", stage).addValue("id", ticketId));
        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(null, null, null, null, LocalDate.now().plusDays(3)), ownerRep);
        ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), ownerRep);
        return ticketId;
    }

    private long createTicket() {
        return tickets.create(
            new CreateTicketRequest("ดีลทดสอบขั้นตอน", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), ownerRepId, "พนักงานขาย เจ้าของดีล");
    }

    private String stageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-s5@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
