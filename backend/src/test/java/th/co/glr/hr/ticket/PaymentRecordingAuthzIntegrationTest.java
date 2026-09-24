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

/**
 * GLA-118 (owner ruling 2026-09-20, part A). Real-DB enforcement coverage for
 * {@link TicketService#recordPayment} (both {@code DEPOSIT} and {@code BALANCE} receipt kinds) —
 * recording ANY payment is now {@code account} ONLY, via the new {@code PAYMENT_RECORD_ROLES}
 * constant. This used to be {@code TicketService.ACCOUNT_ROLES} ({@code account}/{@code ceo}); the
 * CEO fallback, and the owning sales rep's own deal, both grant nothing here any more.
 *
 * <p>This is the required evidence under {@code CLAUDE.md}'s "permission changes must ship
 * evidence": {@link TicketServiceTest}'s Mockito-based companion cases
 * ({@code recordPayment_rejectsCeoRole}, {@code recordPayment_rejectsOwningSalesRep},
 * {@code recordPayment_rejectsNonAccountAndInactiveDeals},
 * {@code actions_neverOffersFinalPaymentToCeo_onlyToAccount}) pin which branch is chosen; only a
 * real {@link TicketRepository} against real Postgres proves the decision survives into the
 * {@code INSERT} — a mocked repository happily "passes" while the SQL does something else.
 *
 * <p><b>Written wrong-way-round on purpose.</b> Every refusal case re-reads
 * {@code sales.payment_receipt}'s row count, {@code sales.ticket.payment_status} and
 * {@code sales.ticket.sales_stage} straight out of Postgres afterwards, so "account can record a
 * payment on any deal" is necessary but is never the evidence on its own.
 *
 * <p>Mirrors {@link DepositPolicyAuthzIntegrationTest} and
 * {@code th.co.glr.hr.attendance.AttendanceScopeIntegrationTest}. Note the suite-wide trap
 * documented on {@link AbstractPostgresIntegrationTest}: services here are hand-wired with
 * {@code new}, so {@code @Transactional} is inert and no rollback is ever exercised — the
 * "unchanged" assertions hold because the guard throws before any write, not because a transaction
 * rolled back.
 */
class PaymentRecordingAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal salesManagerUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // PricingRequestService is mocked exactly as in DepositPolicyAuthzIntegrationTest:
        // recordPayment never calls it. The two things under test — the TicketService gate and the
        // TicketRepository INSERT it protects — are both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests,
            new EmployeeAuthRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "payrec-owner@glr.co.th");
        owner = principal(ownerId, "sales");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า", "payrec-import@glr.co.th"), "import");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ", "payrec-ceo@glr.co.th"), "ceo");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี", "payrec-account@glr.co.th"), "account");
        salesManagerUser = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย", "payrec-salesmgr@glr.co.th"), "sales_manager");
    }

    // ── the refusals (this is the evidence) ──────────────────────────────────────────────

    /**
     * The case the account-only gate must NOT open. Even the deal's OWNING sales rep is refused —
     * recording money received was never a sales-side action, and Rule A has no ownership clause
     * at all (unlike deposit-policy Rule B).
     */
    @Test
    void nonAccountRoles_areRefusedRecordingADepositReceipt_andNothingChanges() {
        long ticketId = createPricedDeal();

        for (UserPrincipal stranger : List.of(ceoUser, salesManagerUser, owner, importUser)) {
            assertThatThrownBy(() -> ticketService.recordPayment(ticketId,
                new RecordPaymentRequest("DEPOSIT", new BigDecimal("500.00"), null, "ลองบันทึก", null, null, false),
                stranger))
                .describedAs("role %s must not be able to record a DEPOSIT receipt", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(paymentReceiptCount(ticketId)).isZero();
        assertThat(paymentStatus(ticketId)).isNull();
        assertThat(salesStage(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);
    }

    /** Same refusal set, same discipline, for a BALANCE receipt instead of DEPOSIT. */
    @Test
    void nonAccountRoles_areRefusedRecordingABalanceReceipt_andNothingChanges() {
        long ticketId = createPricedDeal();

        for (UserPrincipal stranger : List.of(ceoUser, salesManagerUser, owner, importUser)) {
            assertThatThrownBy(() -> ticketService.recordPayment(ticketId,
                new RecordPaymentRequest("BALANCE", new BigDecimal("500.00"), null, "ลองบันทึก", null, null, false),
                stranger))
                .describedAs("role %s must not be able to record a BALANCE receipt", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(paymentReceiptCount(ticketId)).isZero();
        assertThat(paymentStatus(ticketId)).isNull();
    }

    /** {@link TicketService#confirmFinalPayment} is a payment-recording entry point too — same
     * account-only gate, pinned separately because it can advance payment_status WITHOUT ever
     * calling recordPaymentInternal (the "nothing outstanding" branch). */
    @Test
    void nonAccountRoles_areRefusedConfirmingFinalPayment_andPaymentStatusIsUnchanged() {
        // A deal genuinely awaiting final payment, so without the gate these callers would SUCCEED —
        // the refusal (and the unchanged-state assertions) are then meaningful, not a 409 in disguise.
        long ticketId = createPricedDealAwaitingFinalPayment();
        long receiptsBefore = paymentReceiptCount(ticketId);
        String statusBefore = paymentStatus(ticketId);

        for (UserPrincipal stranger : List.of(ceoUser, salesManagerUser, owner, importUser)) {
            assertThatThrownBy(() -> ticketService.confirmFinalPayment(ticketId, stranger))
                .describedAs("role %s must not be able to confirm final payment", stranger.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(paymentReceiptCount(ticketId)).isEqualTo(receiptsBefore);
        assertThat(paymentStatus(ticketId)).isEqualTo(statusBefore);
    }

    // ── the grants (necessary, but not the evidence) ─────────────────────────────────────

    @Test
    void account_recordsADepositReceipt_andTheRowIsActuallyWritten() {
        long ticketId = createPricedDeal();

        ticketService.recordPayment(ticketId,
            new RecordPaymentRequest("DEPOSIT", new BigDecimal("300.00"), null, "บันทึกมัดจำ", null, "RC-DEP-1", false),
            accountUser);

        assertThat(paymentReceiptCount(ticketId)).isEqualTo(1);
    }

    @Test
    void account_recordsABalanceReceipt_andTheRowIsActuallyWritten() {
        long ticketId = createPricedDeal();

        ticketService.recordPayment(ticketId,
            new RecordPaymentRequest("BALANCE", new BigDecimal("300.00"), null, "บันทึกยอดคงเหลือ", null, "RC-BAL-1", false),
            accountUser);

        assertThat(paymentReceiptCount(ticketId)).isEqualTo(1);
    }

    // ── available actions: the advertiser must never drift from the gate ────────────────

    @Test
    void availableActions_neverOfferFinalPaymentToCeo_onlyToAccount() {
        long ticketId = createPricedDealAwaitingFinalPayment();

        assertThat(actionCodes(ticketId, ceoUser)).doesNotContain("FINAL_PAYMENT");
        assertThat(actionCodes(ticketId, accountUser)).contains("FINAL_PAYMENT");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private List<String> actionCodes(long ticketId, UserPrincipal actor) {
        return ticketService.actions(ticketId, actor).availableActions().stream()
            .map(TicketResponses.TicketActionDto::action).toList();
    }

    /** A deal priced at ฿1,000 (approved_price × qty), so {@code payableAmount} is nonzero and
     * {@code recordPayment}'s validation never rejects the amount as unpayable. */
    private long createPricedDeal() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบบันทึกรับชำระ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("1"), null, "PIECE", null, null, null,
                new BigDecimal("1000.00"), "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ทดสอบ");
        tickets.approveItemPrices(ticketId);
        return ticketId;
    }

    /** Walks the real {@link PaymentTrack} machine to {@code AWAITING_FINAL_PAYMENT} — the state
     * {@code canConfirmFinalPaymentNow} requires before FINAL_PAYMENT is advertised. */
    private long createPricedDealAwaitingFinalPayment() {
        long ticketId = createPricedDeal();
        advancePaymentStatus(ticketId, null, PaymentTrack.CUSTOMER_CONFIRMED);
        advancePaymentStatus(ticketId, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        advancePaymentStatus(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_PAID);
        advancePaymentStatus(ticketId, PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT);
        return ticketId;
    }

    private void advancePaymentStatus(long ticketId, String expected, String next) {
        int rows = tickets.advancePaymentStatus(ticketId, DepositPolicy.REQUIRED, expected, next);
        assertThat(rows).describedAs("payment_status %s -> %s must apply", expected, next).isEqualTo(1);
    }

    private String paymentStatus(long ticketId) {
        return jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String salesStage(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
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
        return new UserPrincipal(employeeId, role + "-payrec@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
