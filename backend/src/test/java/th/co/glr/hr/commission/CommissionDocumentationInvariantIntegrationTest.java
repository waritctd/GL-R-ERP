package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** ข้อ 15 ("no commission payment until customer receipt evidence is on file") real-DB coverage — see this
 * branch's PR body for the full investigation. No new mechanism, status, table, or migration was needed for
 * that half of the rule — this class pins it so a future change cannot silently weaken it. */
class CommissionDocumentationInvariantIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private EmployeeRepository employees;
    private long managerEmployeeId;
    private UserPrincipal managerActor;
    private long ceoEmployeeId;
    private UserPrincipal ceoActor;

    private void wireService() {
        commissions = new CommissionRepository(jdbc);
        CommissionCalculator calculator = new CommissionCalculator();
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            calculator,
            new FileStorageService("/tmp/glr-commission-doc-invariant-test-uploads"),
            org.mockito.Mockito.mock(AuditService.class),
            org.mockito.Mockito.mock(NotificationService.class),
            org.mockito.Mockito.mock(TicketRepository.class),
            org.mockito.Mockito.mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย เอกสาร", "sm-docinvariant@glr.co.th", "SA", "แผนกขาย");
        managerActor = actor(managerEmployeeId, "sales_manager");
        ceoEmployeeId = createEmployee("ผู้บริหาร เอกสาร", "ceo-docinvariant@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = actor(ceoEmployeeId, "ceo");
    }

    // Status gate: SUBMITTED / MANAGER_APPROVED SALE commissions must not reach payroll totals, even though their
    // invoice already carries an attachment (isolates the STATUS half of the invariant from the ATTACHMENT half
    // tested further down).

    @Test
    void submittedSaleCommission_withAttachment_doesNotReachPayrollTotals() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย รอส่ง", "pending-submitted@glr.co.th", "SA", "แผนกขาย");
        seedSaleCommission(salesRepId, new BigDecimal("500000.00"), true, CommissionStatus.SUBMITTED);

        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);

        assertThat(totals.getOrDefault(salesRepId, BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void managerApprovedSaleCommission_withAttachment_doesNotReachPayrollTotals() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย รอซีอีโอ", "pending-mgr-approved@glr.co.th", "SA", "แผนกขาย");
        long commissionId = seedSaleCommission(salesRepId, new BigDecimal("500000.00"), true, CommissionStatus.SUBMITTED);
        commissionService.approve(commissionId, managerActor); // -> MANAGER_APPROVED, no CEO sign-off yet

        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);

        assertThat(totals.getOrDefault(salesRepId, BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Positive control: proves the two tests above are actually exercising the aggregation (not silently no-op'ing
     * for some unrelated reason) — the exact same record, once it clears BOTH approvals, DOES reach payroll with a
     * non-zero amount. */
    @Test
    void fullyApprovedSaleCommission_withAttachment_reachesPayrollTotals() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย อนุมัติครบ", "fully-approved@glr.co.th", "SA", "แผนกขาย");
        long commissionId = seedSaleCommission(salesRepId, new BigDecimal("500000.00"), true, CommissionStatus.SUBMITTED);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);

        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);

        assertThat(totals.getOrDefault(salesRepId, BigDecimal.ZERO)).isGreaterThan(BigDecimal.ZERO);
    }

    // RECORDED, NOT GATED (V102, owner decision 2026-07-30 — see handoff 120): payroll's own logic never checked
    // invoice_attachment_id and still doesn't — that decision was explicit, not an oversight left in place.

    @Test
    void grandfatheredLegacyRow_withNoAttachmentAndNoProvenance_stillReachesPayrollTotals() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย นำเข้าเดิม", "bulk-import-style@glr.co.th", "SA", "แผนกขาย");
        // Mirrors db/migration-demo/V21 DEMO-INV-0002 and the real 2026 historical commission import: an
        // invoice_details row with NEITHER invoice_attachment_id NOR evidence_provenance, and a commission_record
        // inserted directly as APPROVED — never touching CommissionService#submit/#createFromDeal.
        long commissionId = seedSaleCommission(salesRepId, new BigDecimal("500000.00"), false, CommissionStatus.SUBMITTED);
        jdbc.update("""
            UPDATE sales.commission_record
               SET status = 'APPROVED', approved_by_id = :ceoId, approved_at = now(),
                   manager_approved_by = :managerId, manager_approved_at = now(),
                   ceo_approved_by = :ceoId, ceo_approved_at = now()
             WHERE commission_id = :id
            """,
            Map.of("id", commissionId, "ceoId", ceoEmployeeId, "managerId", managerEmployeeId));

        CommissionRecord seeded = commissions.findById(commissionId).orElseThrow();
        assertThat(seeded.invoiceDetails().invoiceAttachmentId()).isNull();
        assertThat(seeded.status()).isEqualTo(CommissionStatus.APPROVED);

        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);

        // Payroll is unchanged by V102 — a grandfathered legacy row still reaches its rep's total exactly as before.
        // See the section comment above before changing this assertion.
        assertThat(totals.getOrDefault(salesRepId, BigDecimal.ZERO)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void demoSeedStyleGrandfatheredRow_alsoReachesPayrollTotals() {
        wireService();
        // Same shape as db/migration-demo/V21's DEMO-INV-0002 block: invoice_details with only (invoice_number,
        // invoice_date, gross_amount) set -- no attachment, no provenance -- and commission_record inserted straight
        // to APPROVED via approved_by_id/approved_at, never via managerApprove()/ceoApprove().
        long invoiceId = insertGrandfatheredInvoiceRow(
            "DEMO-STYLE-" + UUID.randomUUID(), INVOICE_DATE, new BigDecimal("180000.00"));
        long salesRepId = createEmployee("พนักงานขาย สไตล์ demo", "demo-style@glr.co.th", "SA", "แผนกขาย");
        jdbc.update("""
            INSERT INTO sales.commission_record
                (invoice_id, sales_rep_id, submitted_by_id, status, payroll_month,
                 actual_received, commissionable_base, approved_by_id, approved_at)
            VALUES
                (:invoiceId, :salesRepId, :salesRepId, 'APPROVED', :payrollMonth,
                 180000.00, 180000.00, :ceoId, now())
            """,
            new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("salesRepId", salesRepId)
                .addValue("payrollMonth", PAYROLL_MONTH)
                .addValue("ceoId", ceoEmployeeId));

        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);

        assertThat(totals.getOrDefault(salesRepId, BigDecimal.ZERO)).isGreaterThan(BigDecimal.ZERO);
    }

    // Helpers

    /** Seeds an invoice_details + commission_record row via the real repository, optionally with an attachment
     * recorded (mirrors what CommissionService#submit persists after a successful upload) -- without needing the
     * full ticket/deal fixture chain this class doesn't touch. */
    private long seedSaleCommission(long salesRepId, BigDecimal actualReceived, boolean withAttachment, String status) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-DOCINV-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        CommissionCalculator calculator = new CommissionCalculator();
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId;
        if (withAttachment) {
            invoiceId = commissions.createInvoice(request);
            long attachmentId = insertFileAttachment(invoiceId);
            commissions.attachInvoiceFile(invoiceId, attachmentId);
        } else {
            invoiceId = insertGrandfatheredInvoiceRow(request.invoiceNumber(), request.invoiceDate(), request.grossAmount());
        }
        long commissionId = commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, PAYROLL_MONTH, calculation);
        if (!CommissionStatus.SUBMITTED.equals(status)) {
            jdbc.update("UPDATE sales.commission_record SET status = :status WHERE commission_id = :id",
                Map.of("status", status, "id", commissionId));
        }
        return commissionId;
    }

    /** V102 correctly rejects a brand-new sales.invoice_details row with neither an attachment nor a recognized
     * evidence_provenance (see InvoiceEvidenceProvenanceIntegrationTest). */
    private long insertGrandfatheredInvoiceRow(String invoiceNumber, LocalDate invoiceDate, BigDecimal grossAmount) {
        jdbc.update("ALTER TABLE sales.invoice_details DROP CONSTRAINT chk_invoice_details_evidence_present", Map.of());
        Long invoiceId = jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
            VALUES (:invoiceNumber, :invoiceDate, :grossAmount)
            RETURNING invoice_id
            """,
            new MapSqlParameterSource()
                .addValue("invoiceNumber", invoiceNumber)
                .addValue("invoiceDate", invoiceDate)
                .addValue("grossAmount", grossAmount),
            Long.class);
        jdbc.update("""
            ALTER TABLE sales.invoice_details
                ADD CONSTRAINT chk_invoice_details_evidence_present CHECK (
                    invoice_attachment_id IS NOT NULL
                    OR (evidence_provenance IS NOT NULL
                        AND evidence_provenance IN
                            ('PRE_ERP_PAPER_APPROVAL', 'PENDING_ATTACHMENT', 'UAT_SEED', 'DEMO_SEED'))
                ) NOT VALID
            """, Map.of());
        return invoiceId;
    }

    private long insertFileAttachment(long invoiceId) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                ('commission-invoice', :ownerId, 'invoice.pdf', '/tmp/invoice.pdf', 'application/pdf', 1024, :uploadedBy)
            """,
            new MapSqlParameterSource()
                .addValue("ownerId", invoiceId)
                .addValue("uploadedBy", managerEmployeeId),
            key,
            new String[]{"attachment_id"});
        return key.getKey().longValue();
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
