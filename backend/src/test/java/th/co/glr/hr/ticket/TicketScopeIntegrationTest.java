package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.deposit.DepositNoticeRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceRenderer;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms the role-scoped งานขาย views (Phase B) against the real service and the real SQL —
 * mirrors {@code AttendanceScopeIntegrationTest}. mockApi.js / salesViewScope.js approximate this
 * at the UI layer, but per CLAUDE.md's "Permission changes must ship evidence" section, only this
 * proves the decision survives the WHERE clause and actually filters rows; Mockito cannot reach
 * that (a mocked repository would happily "pass" while the SQL does something else).
 *
 * <p>Every case here asks the question the wrong way round — can this caller reach a deal, or a
 * sub-resource, they should not — rather than confirming they can reach their own.
 */
class TicketScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService pricingCostingService;

    private long salesRepId;
    private long salesRepBId;

    private UserPrincipal salesRep;
    private UserPrincipal salesRepB;
    private UserPrincipal importUser;
    private UserPrincipal accountUser;
    private UserPrincipal ceoUser;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        PricingCostingRepository pricingCostings = new PricingCostingRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        FileStorageService fileStorage = new FileStorageService("./build/test-uploads");

        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, new ObjectMapper(), contacts, fileStorage);

        // Real TicketService, wired the same way TicketRepositoryIntegrationTest wires the
        // repository — PriceCalcService is mocked since none of the read paths under test
        // (list/listPage/get/listPayments/quotation download) ever call it.
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequestService);

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            notifications, new DepositNoticeRenderer(), new RemainingInvoiceRenderer());

        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), new FactoryEmailService(mock(Mailer.class)),
            notifications, fileStorage, new AppProperties());

        pricingCostingService = new PricingCostingService(pricingCostings, pricingRequests, factoryQuotes,
            tickets, new FxRateRepository(jdbc), new PriceCalcConfigRepository(jdbc),
            new FactoryConfigRepository(jdbc), notifications);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales@glr.co.th");
        salesRepBId = createEmployee(employees, "พนักงานขาย บี ทดสอบ", "sales-b@glr.co.th");
        long importId  = createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "import@glr.co.th");
        long accountId = createEmployee(employees, "ฝ่ายบัญชี ทดสอบ", "account@glr.co.th");
        long ceoId     = createEmployee(employees, "ซีอีโอ ทดสอบ", "ceo@glr.co.th");

        salesRep   = principal(salesRepId, "sales");
        salesRepB  = principal(salesRepBId, "sales");
        importUser = principal(importId, "import");
        accountUser = principal(accountId, "account");
        ceoUser    = principal(ceoId, "ceo");
    }

    // ── import list scoping ──────────────────────────────────────────────────

    @Test
    void importListPage_leadStageDealWithNoPricingRequest_isNotReturned() {
        long ticketId = createTicket(DealStage.LEAD_APPROACH);

        assertThat(idsIn(listPage(importUser))).doesNotContain(ticketId);
    }

    @Test
    void importListPage_procurementStageDeal_isReturned() {
        long ticketId = createTicket(DealStage.PROCUREMENT);

        assertThat(idsIn(listPage(importUser))).contains(ticketId);
    }

    @Test
    void importListPage_activeNonTerminalPricingRequest_isReturned() {
        long ticketId = createTicket(DealStage.NEGOTIATION);
        insertPricingRequest(ticketId, "SUBMITTED");

        assertThat(idsIn(listPage(importUser))).contains(ticketId);
    }

    @Test
    void importListPage_onlyCancelledPricingRequestAtEarlyStage_isNotReturned() {
        long ticketId = createTicket(DealStage.NEGOTIATION);
        insertPricingRequest(ticketId, "CANCELLED");

        assertThat(idsIn(listPage(importUser))).doesNotContain(ticketId);
    }

    @Test
    void importListPage_closedLostDealAtProcurementStage_isNotReturned() {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        tickets.updateLifecycle(ticketId, DealLifecycle.CLOSED_LOST);

        assertThat(idsIn(listPage(importUser))).doesNotContain(ticketId);
    }

    @Test
    void ceoListPage_seesEveryDealRegardlessOfImportAccountScoping() {
        long leadTicketId = createTicket(DealStage.LEAD_APPROACH);
        long procurementTicketId = createTicket(DealStage.PROCUREMENT);

        List<Long> ids = idsIn(listPage(ceoUser));
        assertThat(ids).contains(leadTicketId, procurementTicketId);
    }

    // ── account list scoping ─────────────────────────────────────────────────

    @Test
    void accountListPage_noPendingPaymentActionAndNotOverdue_isNotReturned() {
        long ticketId = createTicket(DealStage.NEGOTIATION);

        assertThat(idsIn(listPage(accountUser))).doesNotContain(ticketId);
    }

    @Test
    void accountListPage_depositNoticeIssuedPending_isReturned() {
        long ticketId = createTicket(DealStage.DEPOSIT_RECEIVED);
        tickets.updatePaymentStatus(ticketId, "DEPOSIT_NOTICE_ISSUED");

        assertThat(idsIn(listPage(accountUser))).contains(ticketId);
    }

    @Test
    void accountListPage_awaitingFinalPaymentPending_isReturned() {
        long ticketId = createTicket(DealStage.DELIVERED);
        tickets.updatePaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");

        assertThat(idsIn(listPage(accountUser))).contains(ticketId);
    }

    @Test
    void accountListPage_overdueOutstandingBalance_isReturnedEvenWithoutAPendingPaymentStatus() {
        long ticketId = createTicket(DealStage.DELIVERED);
        // No pending paymentStatus at all — overdue is the ONLY signal here.
        tickets.createQuotation(ticketId, "QT-SCOPE-0001", salesRepId, new BigDecimal("50000.00"));
        tickets.updateBilling(ticketId, LocalDate.now().minusDays(30), LocalDate.now().minusDays(1),
            30, null, null);

        assertThat(idsIn(listPage(accountUser))).contains(ticketId);
    }

    @Test
    void accountListPage_pastDueDateButFullyPaid_isNotReturned() {
        long ticketId = createTicket(DealStage.DELIVERED);
        tickets.createQuotation(ticketId, "QT-SCOPE-0002", salesRepId, new BigDecimal("50000.00"));
        tickets.updateBilling(ticketId, LocalDate.now().minusDays(30), LocalDate.now().minusDays(1),
            30, null, null);
        recordFullPayment(ticketId, new BigDecimal("50000.00"));

        assertThat(idsIn(listPage(accountUser))).doesNotContain(ticketId);
    }

    // ── import: quotation is not part of its read surface ───────────────────

    @Test
    void importGet_quotationIsProjectedOutOfTheResponse() {
        long ticketId = createTicket(DealStage.QUOTE_BUYER);
        tickets.createQuotation(ticketId, "QT-SCOPE-0003", salesRepId, new BigDecimal("10000.00"));

        TicketDto seenByImport = ticketService.get(ticketId, importUser);
        assertThat(seenByImport.quotation()).isNull();
        assertThat(seenByImport.quotations()).isEmpty();

        // Positive control: sales/ceo still get the real quotation chain.
        TicketDto seenByCeo = ticketService.get(ticketId, ceoUser);
        assertThat(seenByCeo.quotations()).isNotEmpty();
    }

    @Test
    void importGetQuotationFile_denied() {
        long ticketId = createTicket(DealStage.QUOTE_BUYER);
        var quotation = tickets.createQuotation(ticketId, "QT-SCOPE-0004", salesRepId, new BigDecimal("10000.00"));

        assertThatThrownBy(() -> ticketService.getQuotationXlsx(ticketId, quotation.id(), importUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        // Positive control: ceo can still download it.
        assertThat(ticketService.getQuotationXlsx(ticketId, quotation.id(), ceoUser)).isNotEmpty();
    }

    @Test
    void importListPayments_denied() {
        long ticketId = createTicket(DealStage.DELIVERED);

        assertThatThrownBy(() -> ticketService.listPayments(ticketId, importUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        // Positive control: account (the ledger's own role) is unaffected.
        assertThat(ticketService.listPayments(ticketId, accountUser)).isEmpty();
    }

    // ── import: deposit notice is entirely off-limits ────────────────────────

    @Test
    void importDepositNotice_listByTicketDenied() {
        long ticketId = createTicket(DealStage.DEPOSIT_RECEIVED);

        assertThatThrownBy(() -> depositNoticeService.listByTicket(ticketId, importUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        // Positive control: account (ฝ่ายบัญชี) still reads its own document.
        assertThat(depositNoticeService.listByTicket(ticketId, accountUser)).isEmpty();
    }

    // ── account: pricing-request/factory-quote/costing are entirely off-limits ──

    @Test
    void accountPricingRequestGet_denied() {
        long ticketId = createTicket(DealStage.NEGOTIATION);
        long prId = insertPricingRequest(ticketId, "SUBMITTED");

        assertThatThrownBy(() -> pricingRequestService.get(prId, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));

        // Positive control: import (the intended reader of a submitted request) can reach it.
        assertThat(pricingRequestService.get(prId, importUser)).isNotNull();
    }

    @Test
    void accountFactoryQuoteGet_deniedBeforeAnyLookup() {
        // The role gate runs before the repository lookup, so a non-existent id is enough to
        // distinguish "denied" (403) from "role passed, resource not found" (404).
        assertThatThrownBy(() -> factoryQuoteService.get(999_999L, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> factoryQuoteService.get(999_999L, importUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    void accountPricingCostingGet_deniedBeforeAnyLookup() {
        assertThatThrownBy(() -> pricingCostingService.get(999_999L, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> pricingCostingService.get(999_999L, importUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    // ── L2 (Stage L): sales own-only created_by row-scope ───────────────────────
    //
    // TicketRepository.findSummaries/countSummaries carry
    // "AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy)", bound by TicketService
    // only when actor.role().equals("sales"). The import/account appendRoleScope slices above are
    // thoroughly tested; this own-only filter — the one that actually protects a sales rep's deal
    // data from a colleague — was not. Written wrong-way-round: can rep A see rep B's ticket.
    //
    // Note: the "injection guard" case from the Stage L spec (a caller passing another rep's id as
    // a filter param) does not apply here — TicketService.list/listPage never accept a createdBy
    // parameter from the caller at all; the filter is derived solely from actor.role()/actor.id()
    // server-side, so there is no parameter surface to inject through.

    /**
     * MUTATION-CHECK RECORD (actually run, not simulated): temporarily changed both occurrences
     * of {@code AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy)} in {@link
     * TicketRepository#findSummaries} and {@link TicketRepository#countSummaries} to {@code AND
     * (TRUE)} (neutralizing the sales own-only predicate) and ran this class (20 tests). Exactly
     * two went red — {@code salesListPage_ownOnly_excludesOtherRepsTicket} and {@code
     * salesList_ownOnly_excludesOtherRepsTicket} (both failed with "expected not to contain [the
     * other rep's ticket id], but found it") — while all 18 import/account/ceo scoping tests
     * above stayed green (they exercise {@code appendRoleScope}, a separate predicate). Reverted
     * both occurrences; {@code git diff} against the pre-mutation tree was empty afterwards.
     */
    @Test
    void salesListPage_ownOnly_excludesOtherRepsTicket() {
        long ticketA = createTicket(DealStage.LEAD_APPROACH, salesRepId, "พนักงานขาย ทดสอบ");
        long ticketB = createTicket(DealStage.LEAD_APPROACH, salesRepBId, "พนักงานขาย บี ทดสอบ");

        List<Long> idsSeenByRepA = idsIn(listPage(salesRep));

        assertThat(idsSeenByRepA).contains(ticketA);
        assertThat(idsSeenByRepA).doesNotContain(ticketB);
    }

    @Test
    void salesList_ownOnly_excludesOtherRepsTicket() {
        long ticketA = createTicket(DealStage.LEAD_APPROACH, salesRepId, "พนักงานขาย ทดสอบ");
        long ticketB = createTicket(DealStage.LEAD_APPROACH, salesRepBId, "พนักงานขาย บี ทดสอบ");

        List<Long> idsSeenByRepB = ticketService.list(null, salesRepB).stream().map(TicketSummaryDto::id).toList();

        assertThat(idsSeenByRepB).contains(ticketB);
        assertThat(idsSeenByRepB).doesNotContain(ticketA);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long createTicket(String salesStage) {
        return createTicket(salesStage, salesRepId, "พนักงานขาย ทดสอบ");
    }

    private long createTicket(String salesStage, long repId, String repName) {
        long ticketId = tickets.create(sampleTicket(), tickets.nextTicketCode(), repId, repName);
        tickets.updateSalesStage(ticketId, salesStage);
        return ticketId;
    }

    private CreateTicketRequest sampleTicket() {
        return new CreateTicketRequest("ดีลทดสอบ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of());
    }

    private long insertPricingRequest(long ticketId, String status) {
        // chk_pricing_request_cancelled_pair requires cancelled_at whenever status = CANCELLED.
        boolean cancelled = "CANCELLED".equals(status);
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request
                (request_code, ticket_id, recipient_type, status, requested_by, cancelled_at)
            VALUES (:code, :ticketId, 'DESIGNER', :status, :requestedBy, :cancelledAt)
            RETURNING pricing_request_id
            """,
            new MapSqlParameterSource()
                .addValue("code", "PCR-SCOPE-" + ticketId + "-" + status)
                .addValue("ticketId", ticketId)
                .addValue("status", status)
                .addValue("requestedBy", salesRepId)
                .addValue("cancelledAt", cancelled ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                    java.sql.Types.TIMESTAMP),
            Long.class);
    }

    private void recordFullPayment(long ticketId, BigDecimal amount) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref)
            VALUES (:ticketId, 'BALANCE', :amount, 'THB', now(), :recordedBy, :ref)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("amount", amount)
                .addValue("recordedBy", salesRepId)
                .addValue("ref", "SCOPE-" + ticketId));
    }

    private List<TicketSummaryDto> listPage(UserPrincipal actor) {
        return ticketService.listPage(null, actor, PageRequest.resolve(0, 100)).items();
    }

    private static List<Long> idsIn(List<TicketSummaryDto> rows) {
        return rows.stream().map(TicketSummaryDto::id).toList();
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
