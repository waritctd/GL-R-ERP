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

    // Grandfathering technique: NOT VALID tolerates a pre-existing violating row. This test no longer mirrors what
    // V102 itself does. MUTATION-CHECK RECORD: re-ran this test's re-ADD step WITHOUT "NOT VALID" (a plain `ADD
    // CONSTRAINT ...

    private int countInvoiceDetails() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.invoice_details", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

}
