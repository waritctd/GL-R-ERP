package th.co.glr.hr.ticket;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Test-only fixture for suites whose subject is a stage move, not a quotation: they need the D-3
 * quote-stage fact gate (owner ruling 2026-10-16) answered by a document row, and going through
 * {@code DealQuotationService} would drag the whole pricing-request chain into a test about one
 * stage refusal.
 */
final class QuoteStageFixtures {
    private QuoteStageFixtures() {}

    /**
     * A quotation row on {@code ticketId} addressed to {@code recipientType}, inserted directly.
     * Writes the row and nothing else — it never advances the stage, which is what makes it safe on
     * a deal deliberately parked at a particular stage. The columns set are exactly the NOT NULLs
     * without a default (V6) plus the two the gate keys on ({@code recipient_type}, V52;
     * {@code doc_status}, V27).
     */
    static void quotationOnDeal(NamedParameterJdbcTemplate jdbc, long ticketId, long issuedBy,
                                String recipientType, String docStatus) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, recipient_type, doc_status)
            VALUES (:ticketId, :number, :issuedBy, :recipientType, :docStatus)
            """,
            Map.of(
                "ticketId", ticketId,
                "number", "QT-FIX-" + ticketId + "-" + recipientType + "-" + docStatus,
                "issuedBy", issuedBy,
                "recipientType", recipientType,
                "docStatus", docStatus));
    }

    /** {@link #quotationOnDeal(NamedParameterJdbcTemplate, long, long, String, String)} with the usual ISSUED status. */
    static void issuedQuotationOnDeal(NamedParameterJdbcTemplate jdbc, long ticketId, long issuedBy,
                                      String recipientType) {
        quotationOnDeal(jdbc, ticketId, issuedBy, recipientType, "ISSUED");
    }
}
