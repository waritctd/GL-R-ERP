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
import th.co.glr.hr.auth.EmployeeAuthRepository;
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

/** Verifies deposit-policy and related action permissions through real service/database gates. */
class DepositPolicyAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal salesManagerUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // PricingRequestService is mocked exactly as in StockDeclarationAuthzIntegrationTest: neither waiveDeposit nor
        // confirmDepositPaid calls it. The two things under test — the TicketService gate and the TicketRepository
        // UPDATE it protects — are both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests,
            new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "deposit-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(
            createEmployee(employees, "พนักงานขายอื่น", "deposit-other@glr.co.th"), "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า", "deposit-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "deposit-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "deposit-account@glr.co.th"), "account");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย", "deposit-salesmgr@glr.co.th"), "sales_manager");
    }

    /** The case the narrowed gate must NOT open. Each of these roles used to be able to reach here (account/ceo via
     * the old ACCOUNT_ROLES gate); another sales rep was never allowed, and is re-pinned here so the whole refusal
     * set is exercised together. */
    @Test
    void nonOwningRoles_areRefusedSettingDepositPolicy_andTheRowIsUnchanged() {
        long ticketId = createPricedDeal();

        for (UserPrincipal stranger : List.of(otherSalesRep, importUser, accountUser, ceoUser)) {
            assertThatThrownBy(() ->
                ticketService.waiveDeposit(ticketId, DepositPolicy.WAIVED, "ลองเปลี่ยนนโยบาย", stranger))
                .describedAs("role %s must not be able to set the deposit policy", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(depositPolicy(ticketId)).isEqualTo(DepositPolicy.REQUIRED);
        assertThat(depositPolicyReason(ticketId)).isNull();
    }

    /** Rule FR-A-05, unchanged by this ticket: not even the owner may flip the policy once a deposit notice has
     * actually been issued. */
    @Test
    void depositPolicy_cannotChangeAfterNoticeIssued_evenForTheOwner() {
        long ticketId = createPricedDeal();
        advancePaymentStatus(ticketId, null, PaymentTrack.CUSTOMER_CONFIRMED);
        advancePaymentStatus(ticketId, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        assertThatThrownBy(() ->
            ticketService.waiveDeposit(ticketId, DepositPolicy.WAIVED, "ขอเปลี่ยนนโยบาย", owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(depositPolicy(ticketId)).isEqualTo(DepositPolicy.REQUIRED);
    }

    /** The case the narrowed gate must NOT open. owner (the deal's own sales rep) is deliberately included:
     * money-receipt confirmation was already sales-excluded before this ticket, and this pins that ownership
     * grants nothing extra here. */
    @Test
    void nonAccountRoles_areRefusedConfirmingDeposit_andPaymentStateIsUnchanged() {
        long ticketId = createPricedDealAwaitingDepositConfirmation();

        for (UserPrincipal stranger : List.of(ceoUser, owner, otherSalesRep, salesManagerUser, importUser)) {
            assertThatThrownBy(() -> ticketService.confirmDepositPaid(ticketId, stranger))
                .describedAs("role %s must not be able to confirm the deposit", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(paymentStatus(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        assertThat(paymentReceiptCount(ticketId)).isZero();
    }

    /** A deal priced at ฿1,000 (approved_price × qty), so payableAmount is nonzero and confirmDepositPaid's "no
     * deposit amount to record" guard never fires. */
    private long createPricedDeal() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบนโยบายมัดจำ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("1"), null, "PIECE", null, null, null,
                new BigDecimal("1000.00"), "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ทดสอบ");
        tickets.approveItemPrices(ticketId);
        return ticketId;
    }

    /** Walks the real PaymentTrack machine to DEPOSIT_NOTICE_ISSUED — the state confirmDepositPaid requires before
     * it will record anything. */
    private long createPricedDealAwaitingDepositConfirmation() {
        long ticketId = createPricedDeal();
        advancePaymentStatus(ticketId, null, PaymentTrack.CUSTOMER_CONFIRMED);
        advancePaymentStatus(ticketId, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        return ticketId;
    }

    private void advancePaymentStatus(long ticketId, String expected, String next) {
        int rows = tickets.advancePaymentStatus(ticketId, DepositPolicy.REQUIRED, expected, next);
        assertThat(rows).describedAs("payment_status %s -> %s must apply", expected, next).isEqualTo(1);
    }

    private String depositPolicy(long ticketId) {
        return jdbc.queryForObject("SELECT deposit_policy FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String depositPolicyReason(long ticketId) {
        return jdbc.queryForObject("SELECT deposit_policy_reason FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String paymentStatus(long ticketId) {
        return jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private int paymentReceiptCount(long ticketId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.payment_receipt WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-deposit@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
