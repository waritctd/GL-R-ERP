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
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;

/** Real-Postgres proof for the sales.ticket.status state machine: TicketStatus#canTransition +
 * TicketRepository#transitionStatus + every production write site that moves the column. Fixture note. Several
 * tests seed a ticket at a given status with a direct UPDATE. */
class TicketStatusMachineIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private RecordingSalesMailer mailer;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;

    private long salesRepId;
    private UserPrincipal salesRep;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        CustomerRepository customers = new CustomerRepository(jdbc);

        // Mocked exactly as DealTrackingAndActivityIntegrationTest mocks it, and for the same reason: only cancel()
        // reaches PricingRequestService.cancelOpenForTicket, whose real return value is irrelevant here but whose
        // Mockito default (null) would NPE inside cancel().
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            notifications, new DepositNoticePdfRenderer(), customers,
            new CustomerQuotationRepository(jdbc), th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = createEmployee(employees, "พนักงานขาย สถานะ", "sales-status-machine@glr.co.th");
        salesRep = principal(salesRepId, "sales");
        ceo = principal(createEmployee(employees, "ซีอีโอ สถานะ", "ceo-status-machine@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "บัญชี สถานะ", "account-status-machine@glr.co.th"), "account");
    }

    /** Wrong-way-round #1: an edge the machine does not declare is refused BEFORE any SQL runs, and the row is
     * untouched. draft -> closed was a perfectly happy write before this change — isValid("closed") is true, and
     * nothing looked at the current state at all. */
    @Test
    void anUndeclaredEdgeThrowsAndLeavesTheRowAlone() {
        long ticketId = createTicket();
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.DRAFT);

        assertThatThrownBy(() -> tickets.transitionStatus(ticketId, TicketStatus.DRAFT, TicketStatus.CLOSED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft -> closed");

        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.DRAFT);
        assertThat(closedAt(ticketId)).isNull();
    }

    /** Wrong-way-round #2, and the one that only a real database can show: the edge is DECLARED (price_proposed ->
     * cancelled is a legal cancel), but the row is not at price_proposed. */
    @Test
    void aStaleExpectedValueUpdatesZeroRowsRatherThanOverwriting() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.IN_REVIEW);

        int rows = tickets.transitionStatus(ticketId, TicketStatus.PRICE_PROPOSED, TicketStatus.CANCELLED);

        assertThat(rows).isZero();
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.IN_REVIEW);
    }

    @Test
    void aDeclaredEdgeOnAMatchingRowMovesItAndReportsOneRow() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.SUBMITTED);

        assertThat(tickets.transitionStatus(ticketId, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW))
            .isEqualTo(1);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.IN_REVIEW);
    }

    /** The OrderConfirmationService bridge, folded onto transitionStatus but keeping its observable contract:
     * hardcoded FROM draft, so a non-draft row yields 0 rows (which the service turns into a 409) and never an
     * IllegalStateException — the edge check is a constant that always passes. */
    @Test
    void theOrderConfirmationBridgeStillRefusesANonDraftRowWithZeroRowsNotAThrow() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.APPROVED);

        assertThat(tickets.markQuotationIssuedForOrderConfirmation(ticketId)).isZero();
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.APPROVED);

        long draftTicket = createTicket();
        assertThat(tickets.markQuotationIssuedForOrderConfirmation(draftTicket)).isEqualTo(1);
        assertThat(statusOf(draftTicket)).isEqualTo(TicketStatus.QUOTATION_ISSUED);
    }

    /** Site 7 — verifyClose, via the legacy document_issued route (requireClosePrerequisites's legacyOk branch: a
     * pre-dual-track deal whose payment track was never started). closed_at is still stamped, which is the side
     * effect that stayed behind in addEvent when the status write moved out. */
    @Test
    void verifyClose_stillReachesClosedAndStillStampsClosedAt() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);
        // H1 lockdown: account confirms a close only on a deal inside its list scope (live, S10+).
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED' WHERE ticket_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", ticketId));

        ticketService.confirmCloseReady(ticketId, accountActor);
        assertThat(statusOf(ticketId)).as("the account confirmation must not move the status")
            .isEqualTo(TicketStatus.DOCUMENT_ISSUED);

        assertThat(ticketService.verifyClose(ticketId, ceo).summary().status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.CLOSED);
        assertThat(closedAt(ticketId)).isNotNull();
    }

    /** A confirmation refused for an unmet prerequisite (balance still owed, 409) tells the CEO nothing. */
    @Test
    void confirmCloseReady_refusedWhileTheBalanceIsOwed_notifiesNobody() {
        mailer.seedRoleEmployee("ceo");
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED' WHERE ticket_id = :id", Map.of("id", ticketId));
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, qty, unit, sort_order, approved_price)
            VALUES (:t, 'Brand', 'Model', 2, 'pcs', 0, 1000)
            """, Map.of("t", ticketId)); // owed, nothing paid

        assertThatThrownBy(() -> ticketService.confirmCloseReady(ticketId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(409);
                assertThat(e.getMessage()).contains("ยังมียอดค้างชำระ");
            });

        mailer.assertNoNotice("CLOSE_READY_FOR_CEO", "ดีลพร้อมปิด — รอ CEO ตรวจสอบ");
    }

    private static final String DEAL_CLOSED_TITLE = "ดีลปิดเรียบร้อยแล้ว";
    private static final String FYI = "แจ้งเพื่อทราบ ไม่ต้องดำเนินการ";

    private String label(long ticketId) {
        TicketSummaryDto s = tickets.findById(ticketId).orElseThrow().summary();
        return "ดีล " + s.code() + " · ลูกค้า " + s.customerName();
    }

    /** verifyClose refused (account never confirmed) closes nothing and tells nobody. */
    @Test
    void verifyClose_refusedWithoutTheAccountConfirmation_notifiesNobody() {
        mailer.seedRoleEmployee("account");
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.DOCUMENT_ISSUED);

        assertThatThrownBy(() -> ticketService.verifyClose(ticketId, ceo))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));

        mailer.assertNoNotice("DEAL_CLOSED", DEAL_CLOSED_TITLE);
    }

    private static final String IMPORT_STOP = "สิ่งที่ต้องทำต่อ: หากสั่งซื้อกับโรงงานไปแล้ว กรุณาแจ้งโรงงานให้หยุดหรือยกเลิกคำสั่งซื้อ";
    private static final String ACCOUNT_REFUND = "สิ่งที่ต้องทำต่อ: ตรวจสอบเงินมัดจำที่รับไว้ และแจ้งผู้เกี่ยวข้องหากต้องคืนเงินลูกค้า";

    private record LifecycleRoles(long sm, long ceo, long importId, long account) {}

    private LifecycleRoles seedLifecycleRoles() {
        return new LifecycleRoles(mailer.seedRoleEmployee("sales_manager"), mailer.seedRoleEmployee("ceo"),
            mailer.seedRoleEmployee("import"), mailer.seedRoleEmployee("account"));
    }

    /** An ISSUED ใบขอซื้อ (IR) row for the deal — the "import has work in flight" signal. */
    private void seedIssuedImportRequest(long ticketId) {
        long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES ('Factory Lifecycle', 'IT', 'EUR') RETURNING factory_id
            """, Map.of(), Long.class);
        jdbc.update("""
            INSERT INTO sales.import_request
                (ticket_id, factory_id, factory_name, brand, version, status, doc_number, issue_date,
                 import_step, import_step_at, first_issued_date)
            VALUES (:t, :f, 'Factory Lifecycle', 'Brand', 1, 'ISSUED', 'IR-LIFE-1', CURRENT_DATE,
                    'CONTACTED', CURRENT_DATE, CURRENT_DATE)
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("f", factoryId));
    }

    /**
     * SM and CEO always hear it (FYI, /tickets/{id}); import only with an IR (its own deal page + a
     * "tell the factory" line); account only with a deposit notice (its finance page). Nobody else.
     */
    private void assertLifecycleNotices(long ticketId, String type, String title, String line1,
                                        LifecycleRoles r, String importLine3, String accountLine3,
                                        boolean toImport, boolean toAccount) {
        String head = line1 + "\n" + label(ticketId) + "\n";
        mailer.assertNoticeFor(r.sm(), type, title, head + FYI, "/tickets/" + ticketId);
        mailer.assertNoticeFor(r.ceo(), type, title, head + FYI, "/tickets/" + ticketId);
        java.util.List<Long> expected = new java.util.ArrayList<>(java.util.List.of(r.sm(), r.ceo()));
        if (toImport) {
            mailer.assertNoticeFor(r.importId(), type, title, head + importLine3, "/import/deals/" + ticketId);
            expected.add(r.importId());
        }
        if (toAccount) {
            mailer.assertNoticeFor(r.account(), type, title, head + accountLine3, "/finance/deals/" + ticketId);
            expected.add(r.account());
        }
        mailer.assertBellRecipients(type, expected.stream().mapToLong(Long::longValue).toArray());
    }

    /** An ISSUED IR puts import in the loop (to stop the factory order) but not account. */
    @Test
    void markLost_withAnIssuedImportRequest_alsoTellsImport_notAccount() {
        LifecycleRoles r = seedLifecycleRoles();
        long ticketId = createTicket();
        seedIssuedImportRequest(ticketId);

        ticketService.markLost(ticketId, DealLostReason.PRICE, null, salesRep);

        assertLifecycleNotices(ticketId, "DEAL_LOST", "ดีลเสียงาน",
            salesRep.name() + " บันทึกว่าดีลเสียงาน (ราคา)", r, IMPORT_STOP, ACCOUNT_REFUND, true, false);
    }

    /** …and a terminal deal is still refused by the service's own pre-guard, not by the machine. */
    @Test
    void cancel_onAnAlreadyClosedDeal_is409AndTheRowStaysClosed() {
        long ticketId = createTicket();
        seedStatus(ticketId, TicketStatus.CLOSED);

        assertThatThrownBy(() -> ticketService.cancel(ticketId, DealCancelReason.OTHER, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));

        assertThat(statusOf(ticketId)).isEqualTo(TicketStatus.CLOSED);
    }

    private long createTicket() {
        return tickets.create(
            new CreateTicketRequest("ดีลทดสอบสถานะ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), salesRepId, "พนักงานขาย สถานะ");
    }

    /** Seeds the row at a status directly. See the class Javadoc: the fact under test is always the transition OUT
     * of this state, and most of these states are no longer reachable through a live code path at all. */
    private void seedStatus(long ticketId, String status) {
        jdbc.update("UPDATE sales.ticket SET status = :status WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("status", status).addValue("id", ticketId));
    }

    private String statusOf(long ticketId) {
        return jdbc.queryForObject("SELECT status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private java.sql.Timestamp closedAt(long ticketId) {
        return jdbc.queryForObject("SELECT closed_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), java.sql.Timestamp.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-status@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
