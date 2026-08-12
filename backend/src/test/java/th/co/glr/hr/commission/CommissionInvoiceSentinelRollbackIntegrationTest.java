package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * REVIEW-ADDED (2026-07-30, reviewer of feat/commission-documentation-gate).
 *
 * <p>V102 forced {@link CommissionRepository#createInvoice} to write a transient
 * {@code PENDING_ATTACHMENT} value into {@code sales.invoice_details.evidence_provenance}, because a
 * Postgres {@code CHECK} is not deferrable and the real flow inserts the bare invoice row before the
 * file can be stored. That sentinel SATISFIES {@code chk_invoice_details_evidence_present} while
 * being neither a real attachment nor a real evidence category — so the whole safety of V102's new
 * write path rests on one claim in {@code CommissionRepository}'s Javadoc:
 *
 * <blockquote>"if anything throws before {@code attachInvoiceFile} runs, the whole transaction
 * (including this insert) rolls back"</blockquote>
 *
 * <p>Nothing in the branch tested that claim. Every commission integration test hand-wires
 * {@code new CommissionService(...)}, which has NO Spring proxy and therefore NO transaction at all
 * — so those tests would stay green even if {@code @Transactional} were deleted from
 * {@code submit}/{@code createFromDeal}, while production started committing sentinel rows. These
 * two tests close that gap:
 *
 * <ol>
 *   <li>{@link #submitFailingAfterTheInvoiceInsert_rollsBack_leavingNoPendingSentinelRowBehind()} —
 *       real Postgres, real service, inside a REAL transaction: a submission that fails between
 *       {@code createInvoice} and {@code attachInvoiceFile} (rejected MIME type, the first thing
 *       {@code FileStorageService#store} checks) must leave no {@code invoice_details} row at all,
 *       and above all no committed {@code PENDING_ATTACHMENT} row.</li>
 *   <li>{@link #bothCreatePathsAreTransactional_soTheSentinelCanNeverCommit()} — pins the
 *       annotation the rollback above depends on. Deleting {@code @Transactional} from either
 *       create path turns the sentinel from transient into permanent, evidence-free data that
 *       V102's CHECK happily accepts; this test goes red the moment that happens.</li>
 * </ol>
 */
class CommissionInvoiceSentinelRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    /** Must match {@code CommissionRepository#PENDING_ATTACHMENT_SENTINEL} (private by design). */
    private static final String SENTINEL = "PENDING_ATTACHMENT";

    private CommissionService commissionService;
    private EmployeeRepository employees;
    private TransactionTemplate transactionTemplate;
    private UserPrincipal managerActor;

    @BeforeEach
    void wireService() {
        CommissionRepository commissions = new CommissionRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            new CommissionCalculator(),
            new FileStorageService("/tmp/glr-commission-sentinel-test-uploads"),
            org.mockito.Mockito.mock(AuditService.class),
            org.mockito.Mockito.mock(NotificationService.class),
            org.mockito.Mockito.mock(TicketRepository.class),
            org.mockito.Mockito.mock(AttachmentRepository.class));
        // The hand-wired service above has no Spring proxy, so @Transactional does nothing. Drive
        // it through a real transaction manager on the SAME DataSource the repository uses, so the
        // production transaction boundary is genuinely exercised rather than assumed.
        transactionTemplate = new TransactionTemplate(
            new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        long managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย เซนทิเนล", "sm-sentinel@glr.co.th");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
    }

    @Test
    void submitFailingAfterTheInvoiceInsert_rollsBack_leavingNoPendingSentinelRowBehind() {
        long salesRepId = createEmployee("พนักงานขาย เซนทิเนล", "rep-sentinel@glr.co.th");
        String invoiceNumber = "INV-SENTINEL-" + UUID.randomUUID();
        // application/zip is not in COMMISSION_INVOICE_MIME_TYPES, so FileStorageService#store
        // throws AFTER createInvoice() has already inserted the sentinel row — precisely the
        // window the Javadoc claims rollback protects.
        MultipartFile rejectedFile =
            new MockMultipartFile("invoiceAttachment", "invoice.zip", "application/zip", "zip".getBytes());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                commissionService.submit(request(salesRepId, invoiceNumber), rejectedFile, managerActor)))
            .isInstanceOf(ApiException.class);

        assertThat(countByInvoiceNumber(invoiceNumber))
            .as("the failed submission must leave no sales.invoice_details row at all")
            .isZero();
        assertThat(countSentinelRows())
            .as("a committed evidence_provenance = '%s' row would satisfy V102's CHECK while "
                + "carrying neither a real attachment nor a real evidence category — it must never "
                + "survive a transaction", SENTINEL)
            .isZero();
    }

    @Test
    void successfulSubmitCommits_withARealAttachmentAndNoSentinelLeftOver() {
        long salesRepId = createEmployee("พนักงานขาย เซนทิเนลสำเร็จ", "rep-sentinel-ok@glr.co.th");
        String invoiceNumber = "INV-SENTINEL-OK-" + UUID.randomUUID();
        MultipartFile acceptedFile =
            new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());

        CommissionRecord created = transactionTemplate.execute(status ->
            commissionService.submit(request(salesRepId, invoiceNumber), acceptedFile, managerActor));

        // Positive control: proves the failure test above is not passing because submit() is broken
        // for every input, and that the committed row ends in the intended shape.
        assertThat(created).isNotNull();
        assertThat(created.invoiceDetails().invoiceAttachmentId()).isNotNull();
        assertThat(countByInvoiceNumber(invoiceNumber)).isOne();
        assertThat(countSentinelRows())
            .as("attachInvoiceFile must clear the sentinel in the same statement that sets the "
                + "real attachment")
            .isZero();
    }

    @Test
    void bothCreatePathsAreTransactional_soTheSentinelCanNeverCommit() throws Exception {
        Method submit = CommissionService.class.getMethod(
            "submit", SubmitCommissionRequest.class, MultipartFile.class, UserPrincipal.class);
        assertThat(submit.getAnnotation(Transactional.class))
            .as("CommissionService#submit must stay @Transactional — without it, a failure between "
                + "createInvoice() and attachInvoiceFile() COMMITS an evidence-free "
                + "'%s' invoice row that V102's CHECK cannot distinguish from real provenance",
                SENTINEL)
            .isNotNull();

        long createFromDealMethods = java.util.Arrays.stream(CommissionService.class.getMethods())
            .filter(method -> "createFromDeal".equals(method.getName()))
            .peek(method -> assertThat(method.getAnnotation(Transactional.class))
                .as("CommissionService#createFromDeal must stay @Transactional for the same reason")
                .isNotNull())
            .count();
        assertThat(createFromDealMethods)
            .as("guard against this test silently passing because the method was renamed")
            .isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Transaction-harness coverage (test/transaction-harness): the reflection-only test above
    // pins that @Transactional is PRESENT on createFromDeal, but never executes it — this
    // fixture's commissionService field is wired with mock(TicketRepository.class) and
    // mock(AttachmentRepository.class), so createFromDeal (which calls tickets.findById,
    // tickets.findSalesStage, tickets.payableAmount, and attachments.save) cannot run against it
    // at all. createFromDeal also writes a TICKET ATTACHMENT (attachments.save, the AttachType
    // .INVOICE row that satisfies the deal-close invoiceOnFile gate) which submit() never does —
    // so submit()'s rollback coverage above does not cover createFromDeal's own extra write. These
    // two tests close that gap with a REAL TicketRepository/AttachmentRepository, proxied vs. not.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Real proxy, real rollback: fails at the LAST write (audit — mirrors {@link
     * #submitFailingAfterTheInvoiceInsert_rollsBack_leavingNoPendingSentinelRowBehind()}'s
     * injection style, but at createFromDeal's own last write instead of submit's file-validation
     * gate) and proves all five writes before it — invoice row, attachment content,
     * attachInvoiceFile, ticket attachment, commission record — are undone. See {@link
     * #createFromDeal_withoutTheProxy_leavesPartialCommissionDataBehind_theHarnessDefectItself()}
     * for the vacuity control proving this is not trivially true.
     */
    @Test
    void createFromDeal_failingAfterEveryWrite_rollsBackAllOfThem_whenProxied() {
        FailingDealFixture fixture = wireFailingCreateFromDealFixture();
        String invoiceNumber = "INV-DEAL-" + UUID.randomUUID();
        MultipartFile acceptedFile =
            new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());

        assertThatThrownBy(() -> transactional(fixture.service()).createFromDeal(
                fixture.ticketId(), invoiceNumber, LocalDate.of(2026, 7, 1), new BigDecimal("50000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, acceptedFile, fixture.accountActor()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(countByInvoiceNumber(invoiceNumber))
            .as("no sales.invoice_details row must survive a rollback triggered by the audit write "
                + "(the last of createFromDeal's five writes) failing")
            .isZero();
        // NOT rollback evidence, and deliberately labelled as such: attachInvoiceFile clears the
        // sentinel before auditService.record is ever reached, so this count reads zero on the
        // un-proxied path too. Kept as a true invariant (no sentinel ever escapes, by any route),
        // but the three assertions around it are what actually discriminate rollback from
        // auto-commit here — see the vacuity control below for the same three, inverted.
        assertThat(countSentinelRows())
            .as("no committed PENDING_ATTACHMENT sentinel row must survive (invariant, not "
                + "rollback evidence — see comment above)")
            .isZero();
        assertThat(countCommissionRecordsForTicket(fixture.ticketId()))
            .as("createCommissionRecord's write must not survive")
            .isZero();
        assertThat(countTicketAttachments(fixture.ticketId()))
            .as("attachments.save's ticket-attachment write must not survive — no phantom "
                + "invoiceOnFile left on a deal whose commission creation actually failed")
            .isZero();
    }

    /**
     * The vacuity control, and the reason the assertions above are not passing for a trivial
     * reason. Same injected failure, but calls the RAW un-proxied service — no {@link
     * AbstractPostgresIntegrationTest#transactional} wrapping, exactly how {@code
     * commissionService} (this file's OTHER three tests) and ~123 other integration tests in this
     * suite are all driven. Because {@code @Transactional} does nothing without a real AOP proxy,
     * all five writes before the audit failure COMMIT independently: the invoice row, the ticket
     * attachment (a phantom {@code invoiceOnFile} for a commission that does not exist), and the
     * commission record itself all survive.
     *
     * <p>Documents today's broken auto-commit behaviour deliberately — it asserts the DEFECT, not
     * a desired outcome, and should be DELETED the day the base test harness itself runs inside a
     * real Spring context with proxied beans.
     */
    @Test
    void createFromDeal_withoutTheProxy_leavesPartialCommissionDataBehind_theHarnessDefectItself() {
        FailingDealFixture fixture = wireFailingCreateFromDealFixture();
        String invoiceNumber = "INV-DEAL-NOPROXY-" + UUID.randomUUID();
        MultipartFile acceptedFile =
            new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());

        assertThatThrownBy(() -> fixture.service().createFromDeal(
                fixture.ticketId(), invoiceNumber, LocalDate.of(2026, 7, 1), new BigDecimal("50000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, acceptedFile, fixture.accountActor()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(countByInvoiceNumber(invoiceNumber))
            .as("without a proxy, createInvoice's write commits on its own and survives")
            .isOne();
        assertThat(countCommissionRecordsForTicket(fixture.ticketId()))
            .as("createCommissionRecord's write also survives, unaffected by the audit failure after it")
            .isOne();
        assertThat(countTicketAttachments(fixture.ticketId()))
            .as("the ticket attachment row survives too — the phantom invoiceOnFile the harness "
                + "defect creates for a commission that (per the invoice row above) does exist here, "
                + "but would not have if a real proxy had rolled this back")
            .isOne();
    }

    /** Everything Task 4's two tests share: a CLOSED_PAID deal (ticket) created by its own sales
     * rep, and a {@code CommissionService} wired with a REAL {@link TicketRepository} and REAL
     * {@link AttachmentRepository} (unlike this file's {@code commissionService} field, which mocks
     * both — see the section comment above) whose {@link AuditService} throws on the LAST write of
     * {@code createFromDeal}'s sequence. Every other dependency is real. */
    private record FailingDealFixture(CommissionService service, long ticketId, UserPrincipal accountActor) {}

    private FailingDealFixture wireFailingCreateFromDealFixture() {
        long dealSalesRepId = createEmployee("พนักงานขาย ดีลคอมมิชชั่น",
            "rep-deal-" + UUID.randomUUID() + "@glr.co.th");
        long accountEmployeeId = createEmployee("บัญชี ดีลคอมมิชชั่น",
            "account-deal-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal accountActor = new UserPrincipal(accountEmployeeId, accountEmployeeId + "@glr.co.th",
            "Account", "account", accountEmployeeId, true, LocalDate.now(), false, null, false);

        TicketRepository realTickets = new TicketRepository(jdbc);
        long ticketId = realTickets.create(
            new CreateTicketRequest("ดีล Commission Harness", "NORMAL", null, null, null, null, null, null, null),
            realTickets.nextTicketCode(), dealSalesRepId, "พนักงานขาย ดีลคอมมิชชั่น");
        // createFromDeal requires tickets.findSalesStage(ticketId) == DealStage.CLOSED_PAID (via
        // resolveDealLinkage) — the ticket starts at V50's lead-stage default, so advance it here.
        realTickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);

        AuditService failingAudit = mock(AuditService.class);
        doThrow(new IllegalStateException("injected failure after every createFromDeal write"))
            .when(failingAudit).record(any(), any(), any(), any(), any(), any());

        CommissionService service = new CommissionService(
            new CommissionRepository(jdbc),
            new CommissionAttachmentRepository(jdbc),
            new CommissionCalculator(),
            new FileStorageService("/tmp/glr-commission-createfromdeal-test-uploads"),
            failingAudit,
            mock(NotificationService.class),
            realTickets,
            new AttachmentRepository(jdbc));
        return new FailingDealFixture(service, ticketId, accountActor);
    }

    private int countCommissionRecordsForTicket(long ticketId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.commission_record WHERE source_ticket_id = :ticketId",
            Map.of("ticketId", ticketId), Integer.class);
        return count == null ? 0 : count;
    }

    private int countTicketAttachments(long ticketId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.attachment WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), Integer.class);
        return count == null ? 0 : count;
    }

    private SubmitCommissionRequest request(long salesRepId, String invoiceNumber) {
        return new SubmitCommissionRequest(
            null, salesRepId, invoiceNumber, LocalDate.of(2026, 7, 1), new BigDecimal("120000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private int countByInvoiceNumber(String invoiceNumber) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.invoice_details WHERE invoice_number = :invoiceNumber",
            Map.of("invoiceNumber", invoiceNumber), Integer.class);
        return count == null ? 0 : count;
    }

    private int countSentinelRows() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.invoice_details WHERE evidence_provenance = :sentinel",
            Map.of("sentinel", SENTINEL), Integer.class);
        return count == null ? 0 : count;
    }

    private long createEmployee(String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "SA", "แผนกขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
