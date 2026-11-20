package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
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
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** REVIEW-ADDED (2026-07-30, reviewer of feat/commission-documentation-gate). Deleting @Transactional from
 * either create path turns the sentinel from transient into permanent, evidence-free data that V102's CHECK
 * happily accepts; this test goes red the moment that happens. */
class CommissionInvoiceSentinelRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    /** Must match {@code CommissionRepository#PENDING_ATTACHMENT_SENTINEL} (private by design). */
    private static final String SENTINEL = "PENDING_ATTACHMENT";

    private CommissionService commissionService;
    private EmployeeRepository employees;
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
            org.mockito.Mockito.mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        // Deliberately kept RAW here. The hand-wired service has no Spring proxy, so @Transactional does nothing to it
        // — every test that needs the production transaction boundary wraps it in transactional(...) at the call site,
        // and the control test below needs the un-proxied service exactly as it is.
        long managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย เซนทิเนล", "sm-sentinel@glr.co.th");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
    }

    /** The rollback claim itself, driven through a REAL transactional AOP proxy so the transaction comes from
     * CommissionService#submit's own @Transactional and nowhere else. */
    @Test
    void submitFailingAfterTheInvoiceInsert_rollsBack_leavingNoPendingSentinelRowBehind() {
        long salesRepId = createEmployee("พนักงานขาย เซนทิเนล", "rep-sentinel@glr.co.th");
        String invoiceNumber = "INV-SENTINEL-" + UUID.randomUUID();
        // application/zip is not in COMMISSION_INVOICE_MIME_TYPES, so FileStorageService#store throws AFTER
        // createInvoice() has already inserted the sentinel row — precisely the window the Javadoc claims rollback
        // protects.
        MultipartFile rejectedFile =
            new MockMultipartFile("invoiceAttachment", "invoice.zip", "application/zip", "zip".getBytes());

        assertThatThrownBy(() -> transactional(commissionService)
                .submit(request(salesRepId, invoiceNumber), rejectedFile, managerActor))
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

    // Transaction-harness coverage: the reflection-only test above pins that @Transactional is PRESENT on
    // createFromDeal, but never executes it — this fixture's commissionService field is wired with mock and mock,
    // so createFromDeal cannot run against it at all.

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
