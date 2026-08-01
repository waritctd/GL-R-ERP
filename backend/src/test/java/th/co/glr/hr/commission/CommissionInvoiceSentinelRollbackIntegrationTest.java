package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
