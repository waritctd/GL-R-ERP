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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * ข้อ 15 ("no commission payment until customer receipt evidence is on file") real-DB coverage
 * — see this branch's PR body for the full investigation.
 *
 * <p><b>Finding (2026-07-30, owner-directed investigation):</b> ข้อ 15's actual control — a
 * customer-receipt-backed (SALE/CLAWBACK) commission may not reach payroll without both (a) a tax
 * invoice attachment on file and (b) full sales_manager -&gt; CEO approval — is ALREADY enforced
 * for every commission created through the application:
 * <ul>
 *   <li>{@link CommissionService#submit} and {@link CommissionService#createFromDeal} both reject
 *       (400) with no invoice attachment, before any row is written.</li>
 *   <li>{@link CommissionService#payrollCommissionTotalsByEmployee} only ever sums {@code
 *       APPROVED} records ({@link CommissionRepository#findApprovedRecordsByMonth}); reaching
 *       {@code APPROVED} requires a real {@code sales_manager} then a real {@code ceo} to walk the
 *       record there — a rep can never approve, let alone approve their own (see {@link
 *       CommissionAutoCreateIntegrationTest#salesCannotApprove_evenAManagerApprovedCommission}).</li>
 * </ul>
 * No new mechanism, status, table, or migration was needed for that half of the rule — this class
 * pins it so a future change cannot silently weaken it.
 *
 * <p><b>The gap that WAS real, now RECORDED (V102, 2026-07-30 — see handoff for the full
 * history):</b> the invariant above holds only for rows created THROUGH {@link CommissionService}.
 * {@link CommissionRepository#findApprovedRecordsByMonth} — the query payroll actually reads —
 * filters only on {@code payroll_month}/{@code status}; it never re-checks {@code
 * invoice_details.invoice_attachment_id}. A {@code sales.commission_record} row that reaches
 * {@code APPROVED} some OTHER way (raw SQL) — with no attachment at all — is paid exactly like a
 * fully-evidenced one. This is not hypothetical: {@code
 * db/migration-demo/V21__demo_seed_accounts.sql} inserts {@code DEMO-INV-0002} this way, and per
 * team memory ("2026-commission-historical-import") 1,083 SALE + 17 CLAWBACK real prod commission
 * rows were imported the same way — direct SQL, no scanned receipts, because (owner's words) "the
 * invoice was before we built the ERP, it's already all approved." These are genuine PRE-ERP
 * PAPER APPROVALS, not missing evidence.
 *
 * <p><b>The decision, in full:</b> record the fact, don't gate the payment. V102
 * (see {@code V102__invoice_evidence_provenance.sql}) adds {@code
 * sales.invoice_details.evidence_provenance} and a CHECK requiring every row to show EITHER a
 * real attachment OR a named provenance from a closed allow-list — enforced on every NEW row from
 * V102 onward (see {@link InvoiceEvidenceProvenanceIntegrationTest}), while the ~1,100 existing
 * historical rows are backfilled to {@code PRE_ERP_PAPER_APPROVAL} by V102 itself, inline, before
 * the CHECK is added (owner decision 2026-07-30, reversing an earlier "backfill manually" plan —
 * see the migration's comments for why the window before that backfill runs is not safe to leave
 * open). Payroll's own logic is UNCHANGED — it still does not care whether a row has an
 * attachment, a provenance, or neither;
 * gating it was explicitly rejected because a bare attachment filter would turn those genuinely-owed
 * rows into a silent, PERMANENT forfeiture, which is exactly what {@code CLAUSE_15 =
 * EARNED_BUT_HELD} forbids. The two tests below prove payroll's behavior is unchanged for a
 * grandfathered legacy row — that is the intended, recorded outcome, not a residual bug.
 */
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
            org.mockito.Mockito.mock(AttachmentRepository.class));
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย เอกสาร", "sm-docinvariant@glr.co.th", "SA", "แผนกขาย");
        managerActor = actor(managerEmployeeId, "sales_manager");
        ceoEmployeeId = createEmployee("ผู้บริหาร เอกสาร", "ceo-docinvariant@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = actor(ceoEmployeeId, "ceo");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Status gate: SUBMITTED / MANAGER_APPROVED SALE commissions must not reach payroll totals,
    // even though their invoice already carries an attachment (isolates the STATUS half of the
    // invariant from the ATTACHMENT half tested further down).
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /**
     * Positive control: proves the two tests above are actually exercising the aggregation (not
     * silently no-op'ing for some unrelated reason) — the exact same record, once it clears BOTH
     * approvals, DOES reach payroll with a non-zero amount.
     *
     * <p>MUTATION-CHECK RECORD (per CLAUDE.md "permission changes must ship evidence"):
     * temporarily removed {@code AND cr.status = 'APPROVED'} from {@link
     * CommissionRepository#findApprovedRecordsByMonth}'s WHERE clause (reintroducing the exact
     * vulnerability {@code submittedSaleCommission_withAttachment_doesNotReachPayrollTotals} and
     * {@code managerApprovedSaleCommission_withAttachment_doesNotReachPayrollTotals} guard
     * against) — both of those tests went red (expected {@code ZERO}, got the full tier
     * commission instead) and no other test in this class flipped; this positive-control test
     * stayed green throughout, as expected. Reverted the change; {@code git diff} against the
     * pre-mutation tree was empty afterwards.
     */
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // RECORDED, NOT GATED (V102, owner decision 2026-07-30 — see handoff 120): payroll's own
    // logic never checked invoice_attachment_id and still doesn't — that decision was explicit,
    // not an oversight left in place. V102 only changes what a NEW row is allowed to look like
    // (see InvoiceEvidenceProvenanceIntegrationTest); the real 2026 historical-import rows are
    // backfilled to PRE_ERP_PAPER_APPROVAL by V102 itself, so in production none of them are
    // actually "neither route" any more. The fixture below reproduces the shape V102's CHECK
    // itself would reject for any brand-new row — standing in for a hypothetical row that reaches
    // APPROVED via some future direct-SQL path that also manages to dodge the CHECK, since that
    // is the gap this test class documents as real-but-not-gated (see class Javadoc). These two
    // tests prove payroll flows it through unchanged: if either ever goes red because payroll's
    // query changed to check evidence, that is a DIFFERENT, separate policy decision (gating
    // payment) that was explicitly rejected here and must be re-opened deliberately, not an
    // accidental fix to celebrate.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void grandfatheredLegacyRow_withNoAttachmentAndNoProvenance_stillReachesPayrollTotals() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย นำเข้าเดิม", "bulk-import-style@glr.co.th", "SA", "แผนกขาย");
        // Mirrors db/migration-demo/V21 DEMO-INV-0002 and the real 2026 historical commission
        // import: an invoice_details row with NEITHER invoice_attachment_id NOR
        // evidence_provenance, and a commission_record inserted directly as APPROVED — never
        // touching CommissionService#submit/#createFromDeal. V102's constraint would reject this
        // shape for a brand-new row (see InvoiceEvidenceProvenanceIntegrationTest), so
        // seedSaleCommission(..., withAttachment=false, ...) reproduces it the same way V102 itself
        // had to tolerate the real historical rows: drop the constraint, insert, re-add NOT VALID.
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

        // Payroll is unchanged by V102 — a grandfathered legacy row still reaches its rep's
        // total exactly as before. See the section comment above before changing this assertion.
        assertThat(totals.getOrDefault(salesRepId, BigDecimal.ZERO)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void demoSeedStyleGrandfatheredRow_alsoReachesPayrollTotals() {
        wireService();
        // Same shape as db/migration-demo/V21's DEMO-INV-0002 block: invoice_details with only
        // (invoice_number, invoice_date, gross_amount) set -- no attachment, no provenance -- and
        // commission_record inserted straight to APPROVED via approved_by_id/approved_at, never
        // via managerApprove()/ceoApprove(). Goes through the same drop/insert/re-add-NOT-VALID
        // technique as seedSaleCommission's withAttachment=false path, since a plain INSERT of
        // this shape is now correctly rejected by V102 for any NEW row.
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Seeds an invoice_details + commission_record row via the real repository, optionally with
     * an attachment recorded (mirrors what CommissionService#submit persists after a successful
     * upload) -- without needing the full ticket/deal fixture chain this class doesn't touch.
     * When {@code withAttachment} is false, the invoice is seeded via {@link
     * #insertGrandfatheredInvoiceRow} rather than {@link CommissionRepository#createInvoice} --
     * since V102, a plain insert with neither an attachment nor evidence_provenance is correctly
     * rejected, so reproducing "a row with neither" needs the same grandfathering technique V102
     * itself relies on for the real historical rows. */
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

    /**
     * V102 correctly rejects a brand-new {@code sales.invoice_details} row with neither an
     * attachment nor a recognized {@code evidence_provenance} (see
     * {@link InvoiceEvidenceProvenanceIntegrationTest}). In production this shape no longer
     * exists for the real historical rows — V102's own inline backfill sets {@code
     * evidence_provenance = 'PRE_ERP_PAPER_APPROVAL'} on all ~1,100 of them before its CHECK is
     * even added. To still exercise "payroll doesn't care" for a row that satisfies neither
     * evidence route (standing in for some future path that reaches {@code APPROVED} while also
     * dodging the CHECK — see the class Javadoc's "gap that WAS real" section), this drops the
     * constraint, inserts the violating row, then re-adds V102's exact constraint, mirroring the
     * general Postgres technique V102 could have used (but no longer needs to, since its own
     * backfill covers every row it actually has to grandfather).
     */
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
