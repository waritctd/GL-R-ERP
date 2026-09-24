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
 * GLA-118. Real-DB enforcement coverage for two gates that both used to read
 * {@code TicketService.ACCOUNT_ROLES} ({@code account}/{@code ceo}) and now read something
 * narrower:
 *
 * <ul>
 *   <li>{@link TicketService#waiveDeposit} (the deposit-policy write, wired at
 *       {@code POST /api/tickets/{id}/deposit-policy}) — the OWNING sales rep, or
 *       {@code sales_manager} as a backup (owner ruling 2026-09-20, part B; the original
 *       2026-09-17 ruling was owner-only, see git history), via
 *       {@link TicketService#canSetDepositPolicy}. Not CEO, not account, not any other sales rep.
 *       </li>
 *   <li>{@link TicketService#confirmDepositPaid} (ยืนยันรับมัดจำ) — now {@code account} only, via
 *       the new {@code DEPOSIT_CONFIRM_ROLES} constant. The CEO fallback is gone.</li>
 * </ul>
 *
 * <p>This is the required evidence under {@code CLAUDE.md}'s "permission changes must ship
 * evidence": {@link TicketServiceTest}'s Mockito-based companion cases
 * ({@code waiveDeposit_ownerOrSalesManagerOnlyAndIssueImportRequestCanBypassNotice},
 * {@code waiveDeposit_grantsSalesManagerAsBackupOwner},
 * {@code waiveDeposit_sameIdAsOriginalOwnerButRoleNoLongerSales_decidesByRoleNotId},
 * {@code confirmDepositPaid_rejectsCeoRole}, {@code
 * actions_neverOffersWaiveDepositOnceDepositNoticeExists_butStillOffersDepositPaidToAccount},
 * {@code actions_offersWaiveDepositToOwnerAndSalesManager_whenPaymentTrackNotStarted}) pin which
 * branch is chosen; only a real {@link TicketRepository} against real Postgres proves the decision
 * survives into the {@code UPDATE} — a mocked repository happily "passes" while the SQL does
 * something else.
 *
 * <p><b>Written wrong-way-round on purpose.</b> Every refusal case re-reads
 * {@code sales.ticket.deposit_policy}/{@code deposit_policy_reason}/{@code payment_status} (plus
 * {@code sales.payment_receipt}'s row count for the confirm-deposit gate) straight out of Postgres
 * afterwards, so "the owner/account can act on their own deal" is necessary but is never the
 * evidence on its own.
 *
 * <p>Mirrors {@link StockDeclarationAuthzIntegrationTest} and
 * {@code th.co.glr.hr.attendance.AttendanceScopeIntegrationTest}. Note the suite-wide trap
 * documented on {@link AbstractPostgresIntegrationTest}: services here are hand-wired with
 * {@code new}, so {@code @Transactional} is inert and no rollback is ever exercised — the
 * "unchanged" assertions hold because the guard throws before any write, not because a
 * transaction rolled back.
 */
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

        // PricingRequestService is mocked exactly as in StockDeclarationAuthzIntegrationTest:
        // neither waiveDeposit nor confirmDepositPaid calls it. The two things under test — the
        // TicketService gate and the TicketRepository UPDATE it protects — are both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests,
            new EmployeeAuthRepository(jdbc));

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

    // ── deposit policy: the refusals (this is the evidence) ─────────────────────────────

    /**
     * The case the narrowed gate must NOT open. Each of these roles used to be able to reach here
     * (account/ceo via the old {@code ACCOUNT_ROLES} gate); another sales rep was never allowed,
     * and is re-pinned here so the whole refusal set is exercised together. {@code sales_manager}
     * is deliberately NOT in this list any more — see
     * {@code salesManager_setsDepositPolicyAsBackupOwner_andTheRowIsActuallyWritten} below for the
     * 2026-09-20 part-B grant.
     */
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

    /**
     * GLA-118 owner ruling 2026-09-20, part B: sales_manager may set deposit policy as a backup to
     * the owning rep. The clause reused is EXACTLY {@link TicketService#requireDealOwnership} /
     * {@code canDealOwnership}'s bare {@code "sales_manager".equals(role)} check — global, not
     * scoped to whichever team owns the deal — so {@code salesManagerUser} grants here even though
     * it did not create this ticket (owner is {@code ownerId}).
     */
    @Test
    void salesManager_setsDepositPolicyAsBackupOwner_andTheRowIsActuallyWritten() {
        long ticketId = createPricedDeal();

        ticketService.waiveDeposit(ticketId, DepositPolicy.CREDIT_CUSTOMER, "sales_manager สำรอง", salesManagerUser);

        assertThat(depositPolicy(ticketId)).isEqualTo(DepositPolicy.CREDIT_CUSTOMER);
        assertThat(depositPolicyReason(ticketId)).isEqualTo("sales_manager สำรอง");
        assertThat(depositPolicySetBy(ticketId)).isEqualTo(salesManagerUser.id());
    }

    /**
     * Nit case: a user whose id happens to equal {@code createdById} but whose CURRENT role is no
     * longer {@code sales} must be judged by {@link TicketService#canSetDepositPolicy}'s role-based
     * clauses, never by the id match alone. Constructed here with a real employee row whose role
     * changed after owning the deal (promoted to sales_manager, or moved to account) — exactly the
     * "manager took over a rep's old deal" or "rep left the sales team" scenario the id-based
     * ownership check alone cannot distinguish.
     */
    @Test
    void sameIdAsOriginalOwner_butRoleNoLongerSales_decidesByRoleNotId() {
        long ticketId = createPricedDeal();

        // Same employee id as the deal's createdById, but the ACTOR's role in this call is now
        // sales_manager: still granted, via the separate sales_manager clause (SALES_ROLES excludes
        // "sales_manager", so the ownership half of the OR never fires for them).
        UserPrincipal promotedOwner = principal(ownerId, "sales_manager");
        ticketService.waiveDeposit(ticketId, DepositPolicy.WAIVED, "เลื่อนตำแหน่งแล้ว", promotedOwner);
        assertThat(depositPolicy(ticketId)).isEqualTo(DepositPolicy.WAIVED);
        assertThat(depositPolicySetBy(ticketId)).isEqualTo(ownerId);

        // Same employee id, role moved to account entirely: refused — account is in neither
        // clause, and the id match alone grants nothing. The row from the successful call above
        // stays exactly as that call left it.
        UserPrincipal movedToAccount = principal(ownerId, "account");
        assertThatThrownBy(() ->
            ticketService.waiveDeposit(ticketId, DepositPolicy.CREDIT_CUSTOMER, "ย้ายแผนกแล้ว", movedToAccount))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(depositPolicy(ticketId)).isEqualTo(DepositPolicy.WAIVED);
    }

    /** Rule FR-A-05, unchanged by this ticket: not even the owner may flip the policy once a deposit
     * notice has actually been issued. */
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

    /** Pre-existing validation (not this ticket's own gate), pinned here as the task's own
     * "wrong-way-round" list asks for. */
    @Test
    void nonRequiredPolicyWithoutReason_isRejected_evenForTheOwner() {
        long ticketId = createPricedDeal();

        assertThatThrownBy(() -> ticketService.waiveDeposit(ticketId, DepositPolicy.WAIVED, " ", owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(depositPolicy(ticketId)).isEqualTo(DepositPolicy.REQUIRED);
        assertThat(depositPolicyReason(ticketId)).isNull();
    }

    // ── deposit policy: the grant (necessary, but not the evidence) ─────────────────────

    @Test
    void owner_setsDepositPolicy_andTheRowIsActuallyWritten() {
        long ticketId = createPricedDeal();

        ticketService.waiveDeposit(ticketId, DepositPolicy.CREDIT_CUSTOMER, "ลูกค้าเครดิตชั้นดี", owner);

        assertThat(depositPolicy(ticketId)).isEqualTo(DepositPolicy.CREDIT_CUSTOMER);
        assertThat(depositPolicyReason(ticketId)).isEqualTo("ลูกค้าเครดิตชั้นดี");
        assertThat(depositPolicySetBy(ticketId)).isEqualTo(ownerId);
    }

    // ── confirm deposit: the refusals (this is the evidence) ────────────────────────────

    /**
     * The case the narrowed gate must NOT open. {@code owner} (the deal's own sales rep) is
     * deliberately included: money-receipt confirmation was already sales-excluded before this
     * ticket, and this pins that ownership grants nothing extra here.
     */
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

    // ── confirm deposit: the grant (necessary, but not the evidence) ────────────────────

    @Test
    void account_confirmsDeposit_andPaymentStatusActuallyAdvances() {
        long ticketId = createPricedDealAwaitingDepositConfirmation();

        ticketService.confirmDepositPaid(ticketId, accountUser);

        assertThat(paymentStatus(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(paymentReceiptCount(ticketId)).isEqualTo(1);
    }

    // ── available actions: the advertiser must never drift from the gates ───────────────

    /**
     * Review fix: WAIVE_DEPOSIT must not be offered once a deposit notice exists, matching Rule 4
     * ({@link TicketService#waiveDeposit} 409s past that point — see
     * {@code depositPolicy_cannotChangeAfterNoticeIssued_evenForTheOwner} above). This used to
     * assert the OPPOSITE for {@code owner}; that assertion was wrong — see
     * {@code canSetDepositPolicy}'s own Javadoc for why the advertisement and the write gate are
     * deliberately NOT the same predicate.
     */
    @Test
    void availableActions_neverOfferWaiveDepositOnceDepositNoticeExists_butStillOfferDepositPaidToAccount() {
        long ticketId = createPricedDealAwaitingDepositConfirmation();

        assertThat(actionCodes(ticketId, owner)).doesNotContain("WAIVE_DEPOSIT", "DEPOSIT_PAID");
        assertThat(actionCodes(ticketId, salesManagerUser)).doesNotContain("WAIVE_DEPOSIT", "DEPOSIT_PAID");
        assertThat(actionCodes(ticketId, ceoUser)).doesNotContain("WAIVE_DEPOSIT", "DEPOSIT_PAID");
        assertThat(actionCodes(ticketId, accountUser)).contains("DEPOSIT_PAID").doesNotContain("WAIVE_DEPOSIT");
    }

    /**
     * The positive case the test above deliberately does not cover: a deal whose payment track has
     * not started yet (paymentStatus null) still owes WAIVE_DEPOSIT to the owner and, per Rule B,
     * to sales_manager as a backup — account/ceo see nothing.
     */
    @Test
    void availableActions_offerWaiveDepositToOwnerAndSalesManager_whenPaymentTrackNotStarted() {
        long ticketId = createPricedDeal();

        assertThat(actionCodes(ticketId, owner)).contains("WAIVE_DEPOSIT");
        assertThat(actionCodes(ticketId, salesManagerUser)).contains("WAIVE_DEPOSIT");
        assertThat(actionCodes(ticketId, ceoUser)).doesNotContain("WAIVE_DEPOSIT");
        assertThat(actionCodes(ticketId, accountUser)).doesNotContain("WAIVE_DEPOSIT");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private List<String> actionCodes(long ticketId, UserPrincipal actor) {
        return ticketService.actions(ticketId, actor).availableActions().stream()
            .map(TicketResponses.TicketActionDto::action).toList();
    }

    /** A deal priced at ฿1,000 (approved_price × qty), so {@code payableAmount} is nonzero and
     * {@code confirmDepositPaid}'s "no deposit amount to record" guard never fires. */
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

    /** Walks the real {@link PaymentTrack} machine to {@code DEPOSIT_NOTICE_ISSUED} — the state
     * {@code confirmDepositPaid} requires before it will record anything. */
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

    private Long depositPolicySetBy(long ticketId) {
        return jdbc.queryForObject("SELECT deposit_policy_set_by FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), Long.class);
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
