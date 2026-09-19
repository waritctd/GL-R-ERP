package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * REVIEW ROUND 2, S-C — against real Postgres, through the real service: the STORED per-factory
 * {@code issue()} path accepts any payment status AT OR AFTER deposit-ready ({@code
 * AWAITING_FINAL_PAYMENT}/{@code FULLY_PAID} too, in addition to the original {@code
 * DEPOSIT_NOTICE_ISSUED}/{@code DEPOSIT_PAID}/bypass set), so a deal's second/third factory's FIRST
 * issue, or ANY factory's REVISION issue, is not blocked just because the customer finished paying
 * the rest of the deal in the meantime — see {@code TicketService#requireImportRequestIssuable}'s
 * {@code allowAdvancedPayment} overload. A deal with NO deposit at all must still be refused, on
 * both paths — this is a WIDENING of what counts as "ready", never a bypass of the floor itself.
 */
class ImportRequestPaymentGateIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;

    private long ownerId;
    private UserPrincipal owner;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-payment-gate-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth);

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService);

        ownerId = insertEmployee("PAYGATE-OWN");
        owner = principal(ownerId, "sales");
    }

    @Test
    void secondFactorysFirstIssue_isAllowed_afterTheDealHasAdvancedToFullyPaid() {
        long factoryA = insertFactory("Pay Gate Factory A");
        long factoryB = insertFactory("Pay Gate Factory B");
        long ticketId = insertTicket("GATE-FULLYPAID", "DEPOSIT_PAID");
        insertItem(ticketId, "Pay Gate Factory A", "Line A", "60x60", "10", "pcs", 0);
        insertItem(ticketId, "Pay Gate Factory B", "Line B", "60x60", "10", "pcs", 1);

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        long aId = byFactory(drafts, factoryA).id();
        long bId = byFactory(drafts, factoryB).id();
        assertThat(service.issue(aId, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);

        // The customer finishes paying the deal in full before factory B's own IR is ever touched —
        // the original (narrower) gate would have refused this; the widened one must not.
        setPaymentStatus(ticketId, "FULLY_PAID");

        assertThat(service.issue(bId, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    @Test
    void aRevisionsIssue_isAllowed_afterTheDealHasAdvancedToAwaitingFinalPayment() {
        insertFactory("Pay Gate Revise Factory");
        long ticketId = insertTicket("GATE-AWAITING", "DEPOSIT_PAID");
        insertItem(ticketId, "Pay Gate Revise Factory", "Line", "60x60", "10", "pcs", 0);
        long v1 = service.createDrafts(ticketId, null, owner).get(0).id();
        assertThat(service.issue(v1, null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);

        setPaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");

        ImportRequestDto v2 = service.revise(v1, owner);
        assertThat(service.issue(v2.id(), null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);
    }

    /**
     * The floor itself is UNCHANGED — S-C widens what counts as "ready", it never removes the
     * requirement that the deal has SOME deposit-or-later payment state at all.
     */
    @Test
    void issue_isStillRefused_onADealWithNoDepositAtAll() {
        insertFactory("Pay Gate No Deposit Factory");
        long ticketId = insertTicket("GATE-NODEPOSIT", null);
        insertItem(ticketId, "Pay Gate No Deposit Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        assertThatThrownBy(() -> service.issue(id, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * Cheap nit (REVIEW ROUND 3): {@code CUSTOMER_CONFIRMED} on a deposit-REQUIRED policy (the
     * default — {@code insertTicket} never overrides {@code deposit_policy}, so this ticket carries
     * V51's {@code DEFAULT 'REQUIRED'}) is STILL refused even under the WIDENED {@code
     * allowAdvancedPayment} gate: it is neither one of the two original deposit-ready statuses, nor
     * does {@code depositPolicyBypassesNotice} apply (the policy is not one of {@code
     * DepositPolicy.NON_REQUIRED}), nor is {@code CUSTOMER_CONFIRMED} one of the two statuses S-C
     * added ({@code AWAITING_FINAL_PAYMENT}/{@code FULLY_PAID}). Distinguishes "the floor moved" from
     * "the floor widened" — S-C must never be misread as accepting ANY non-null payment status.
     */
    @Test
    void issue_isStillRefused_onCustomerConfirmed_whenDepositPolicyIsRequired() {
        insertFactory("Pay Gate Customer Confirmed Factory");
        long ticketId = insertTicket("GATE-CUSTCONF", "CUSTOMER_CONFIRMED");
        insertItem(ticketId, "Pay Gate Customer Confirmed Factory", "Line", "60x60", "10", "pcs", 0);
        long id = service.createDrafts(ticketId, null, owner).get(0).id();

        assertThatThrownBy(() -> service.issue(id, null, owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private static ImportRequestDto byFactory(List<ImportRequestDto> rows, long factoryId) {
        return rows.stream().filter(r -> r.factoryId() == factoryId).findFirst().orElseThrow();
    }

    private void setPaymentStatus(long ticketId, String paymentStatus) {
        jdbc.update("UPDATE sales.ticket SET payment_status = :s WHERE ticket_id = :id",
            Map.of("s", paymentStatus, "id", ticketId));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-paygate@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'เกตชำระเงิน') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, 'IT', 'EUR') RETURNING factory_id
            """, Map.of("name", name), Long.class);
    }

    /** {@code paymentStatus} nullable — a null deal has no deposit recorded at all. */
    private long insertTicket(String code, String paymentStatus) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage)
            VALUES (:code, 'ทดสอบเกตชำระเงิน', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    :paymentStatus, :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("paymentStatus", paymentStatus)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private void insertItem(long ticket, String factory, String model, String size, String qty,
                            String unit, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", factory)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory));
    }
}
