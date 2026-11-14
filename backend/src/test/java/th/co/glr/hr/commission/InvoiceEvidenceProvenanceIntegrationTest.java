package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** V102 (sales.invoice_details.evidence_provenance + chk_invoice_details_evidence_present) — real-Postgres
 * coverage for the CHECK constraint that records ข้อ 15 evidence going forward. */
class InvoiceEvidenceProvenanceIntegrationTest extends th.co.glr.hr.support.AbstractPostgresIntegrationTest {
    private static final String CONSTRAINT_NAME = "chk_invoice_details_evidence_present";

    // Wrong-way-round: a NEW row with neither evidence route is rejected outright. No other test in this class was
    // affected. Reverted (re-ran the fully-migrated schema via the normal @BeforeEach reset, which re-applies V102
    // including the constraint); confirmed green again immediately after.

    @Test
    void newInvoiceDetailsRow_withNeitherAttachmentNorProvenance_isRejected() {
        int before = countInvoiceDetails();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
                VALUES (:invoiceNumber, :invoiceDate, :grossAmount)
                """,
                new MapSqlParameterSource()
                    .addValue("invoiceNumber", "V102-REJECT-" + UUID.randomUUID())
                    .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                    .addValue("grossAmount", new BigDecimal("100000.00"))))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining(CONSTRAINT_NAME);

        assertThat(countInvoiceDetails()).isEqualTo(before);
    }

    // the CHECK used during development accepted ANY non-null string in evidence_provenance -- '', ' ', 'x' all
    // satisfied `evidence_provenance IS NOT NULL`, so the constraint could not actually distinguish real
    // provenance from noise.

    @Test
    void newInvoiceDetailsRow_withBlankStringProvenance_isRejected() {
        assertProvenanceValueIsRejected("");
    }

    @Test
    void newInvoiceDetailsRow_withWhitespaceOnlyProvenance_isRejected() {
        assertProvenanceValueIsRejected(" ");
    }

    @Test
    void newInvoiceDetailsRow_withUnrecognizedProvenance_isRejected() {
        // 'x' is exactly the kind of stray/typo'd value a hand-run UPDATE could introduce -- not blank, not
        // whitespace, just not one of the four real categories this column recognizes (PRE_ERP_PAPER_APPROVAL,
        // PENDING_ATTACHMENT, UAT_SEED, DEMO_SEED).
        assertProvenanceValueIsRejected("x");
    }

    private void assertProvenanceValueIsRejected(String provenanceValue) {
        int before = countInvoiceDetails();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
                VALUES (:invoiceNumber, :invoiceDate, :grossAmount, :evidenceProvenance)
                """,
                new MapSqlParameterSource()
                    .addValue("invoiceNumber", "V102-BADVALUE-" + UUID.randomUUID())
                    .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                    .addValue("grossAmount", new BigDecimal("100000.00"))
                    .addValue("evidenceProvenance", provenanceValue)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining(CONSTRAINT_NAME);

        assertThat(countInvoiceDetails()).isEqualTo(before);
    }

    // Right-way-round: either evidence route alone is sufficient.

    @Test
    void newInvoiceDetailsRow_withOnlyEvidenceProvenance_isAccepted() {
        // The pre-ERP case going forward would never actually happen for a NEW row (the historical import is a
        // one-time, already-completed event) -- but the constraint must accept this shape on its own terms,
        // independent of attachment, exactly as written.
        int before = countInvoiceDetails();

        jdbc.update("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
            VALUES (:invoiceNumber, :invoiceDate, :grossAmount, 'PRE_ERP_PAPER_APPROVAL')
            """,
            new MapSqlParameterSource()
                .addValue("invoiceNumber", "V102-PROVENANCE-" + UUID.randomUUID())
                .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                .addValue("grossAmount", new BigDecimal("100000.00")));

        assertThat(countInvoiceDetails()).isEqualTo(before + 1);
    }

    @Test
    void newInvoiceDetailsRow_withUatSeedProvenance_isAccepted() {
        // Added 2026-07-30 after cross-checking against the uat branch's own fix: db/migration-uat/V903__uat_sales.sql
        // and V910__uat_golden_pcr_deal.sql insert sales.invoice_details rows with no attachment and supply UAT_SEED
        // -- synthetic fixture data, no receipt exists at all.
        assertProvenanceValueIsAccepted("UAT_SEED");
    }

    @Test
    void newInvoiceDetailsRow_withDemoSeedProvenance_isAccepted() {
        // Reserved, not yet consumed by any migration (db/migration-demo/V21 is currently safe by version luck, not by
        // using this value) -- allow-listed now so a future renumbering of the demo seed above V102 has somewhere to
        // point instead of silently hitting the CHECK.
        assertProvenanceValueIsAccepted("DEMO_SEED");
    }

    private void assertProvenanceValueIsAccepted(String provenanceValue) {
        int before = countInvoiceDetails();

        jdbc.update("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
            VALUES (:invoiceNumber, :invoiceDate, :grossAmount, :evidenceProvenance)
            """,
            new MapSqlParameterSource()
                .addValue("invoiceNumber", "V102-" + provenanceValue + "-" + UUID.randomUUID())
                .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                .addValue("grossAmount", new BigDecimal("100000.00"))
                .addValue("evidenceProvenance", provenanceValue));

        assertThat(countInvoiceDetails()).isEqualTo(before + 1);
    }

    @Test
    void newInvoiceDetailsRow_withOnlyAttachment_isAccepted() {
        // The normal, everyday app case: CommissionService#submit/#createFromDeal always attach a real file (via
        // CommissionAttachmentRepository#save + #attachInvoiceFile) and end with evidence_provenance NULL /
        // invoice_attachment_id set -- this proves that path still works end-to-end under V102.
        CommissionRepository commissions = new CommissionRepository(jdbc);
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, 1L, "V102-ATTACHMENT-" + UUID.randomUUID(), LocalDate.of(2026, 7, 1),
            new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        long invoiceId = commissions.createInvoice(request);
        long attachmentId = insertFileAttachment(invoiceId);
        commissions.attachInvoiceFile(invoiceId, attachmentId);

        Long attachmentIdColumn = jdbc.queryForObject(
            "SELECT invoice_attachment_id FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), Long.class);
        String provenanceColumn = jdbc.queryForObject(
            "SELECT evidence_provenance FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), String.class);
        assertThat(attachmentIdColumn).isNotNull();
        assertThat(provenanceColumn).isNull();
    }

    @Test
    void createInvoiceAlone_withoutAttachInvoiceFile_wouldLeaveThePendingSentinel_soCallersMustAlwaysAttach() {
        // Documents WHY CommissionRepository#createInvoice needs the PENDING_ATTACHMENT sentinel at all: without it,
        // this exact statement -- the real INSERT CommissionService#submit/ #createFromDeal issue before a file can be
        // stored -- would violate the CHECK immediately and break every commission submission.
        CommissionRepository commissions = new CommissionRepository(jdbc);
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, 1L, "V102-SENTINEL-" + UUID.randomUUID(), LocalDate.of(2026, 7, 1),
            new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        long invoiceId = commissions.createInvoice(request);

        String provenanceColumn = jdbc.queryForObject(
            "SELECT evidence_provenance FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), String.class);
        Long attachmentIdColumn = jdbc.queryForObject(
            "SELECT invoice_attachment_id FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), Long.class);
        assertThat(provenanceColumn).isNotNull(); // the transient sentinel -- not a real category
        assertThat(attachmentIdColumn).isNull();
    }

    // Grandfathering technique: NOT VALID tolerates a pre-existing violating row. This test no longer mirrors what
    // V102 itself does. MUTATION-CHECK RECORD: re-ran this test's re-ADD step WITHOUT "NOT VALID" (a plain `ADD
    // CONSTRAINT ...

    @Test
    void preExistingAttachmentlessRow_isGrandfathered_becauseConstraintIsNotValid() {
        // A freshly-migrated test database already has V102 (and its constraint) applied before this test runs, so a
        // violating row can no longer be inserted normally -- exactly the protection this constraint is for.
        jdbc.update("ALTER TABLE sales.invoice_details DROP CONSTRAINT " + CONSTRAINT_NAME, Map.of());
        Long invoiceId = jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
            VALUES (:invoiceNumber, :invoiceDate, :grossAmount)
            RETURNING invoice_id
            """,
            new MapSqlParameterSource()
                .addValue("invoiceNumber", "V102-LEGACY-" + UUID.randomUUID())
                .addValue("invoiceDate", LocalDate.of(2026, 1, 1))
                .addValue("grossAmount", new BigDecimal("50000.00")),
            Long.class);

        // Re-adding NOT VALID must succeed even though the row just inserted violates the check.
        jdbc.update("""
            ALTER TABLE sales.invoice_details
                ADD CONSTRAINT chk_invoice_details_evidence_present CHECK (
                    invoice_attachment_id IS NOT NULL
                    OR (evidence_provenance IS NOT NULL
                        AND evidence_provenance IN
                            ('PRE_ERP_PAPER_APPROVAL', 'PENDING_ATTACHMENT', 'UAT_SEED', 'DEMO_SEED'))
                ) NOT VALID
            """, Map.of());

        Long attachmentId = jdbc.queryForObject(
            "SELECT invoice_attachment_id FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), Long.class);
        String provenance = jdbc.queryForObject(
            "SELECT evidence_provenance FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), String.class);
        assertThat(attachmentId).isNull();
        assertThat(provenance).isNull();

        // OPERATIONAL NUANCE worth pinning explicitly: NOT VALID only skips the one-time validation SCAN that ADD
        // CONSTRAINT would otherwise run over existing rows -- it does NOT exempt a grandfathered row from being
        // re-checked on every future UPDATE, even one that touches a completely unrelated column.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE sales.invoice_details SET metadata = '{}'::jsonb WHERE invoice_id = :id",
                Map.of("id", invoiceId)))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Once the row is backfilled, it becomes updatable again on unrelated columns too -- the constraint problem is
        // specific to rows that still show neither evidence route, not to "old" rows in general.
        jdbc.update("""
            UPDATE sales.invoice_details SET evidence_provenance = 'PRE_ERP_PAPER_APPROVAL'
             WHERE invoice_id = :id
            """, Map.of("id", invoiceId));
        jdbc.update("UPDATE sales.invoice_details SET metadata = '{}'::jsonb WHERE invoice_id = :id",
            Map.of("id", invoiceId));
    }

    private int countInvoiceDetails() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.invoice_details", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private long insertFileAttachment(long invoiceId) {
        // uploaded_by is a nullable FK (ON DELETE SET NULL, V33) -- null keeps this helper free of any dependency on a
        // seeded employee row existing in a fresh test database.
        org.springframework.jdbc.support.GeneratedKeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                ('commission-invoice', :ownerId, 'invoice.pdf', '/tmp/invoice.pdf', 'application/pdf', 1024, NULL)
            """,
            new MapSqlParameterSource()
                .addValue("ownerId", invoiceId),
            key,
            new String[]{"attachment_id"});
        return key.getKey().longValue();
    }
}
