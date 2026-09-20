package th.co.glr.hr.common;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Mints the next {@code GLR<yy><5-digit seq>} number (e.g. {@code GLR6900001}) from the shared
 * {@code sales.document_sequence} row for {@code doc_type = 'AR_GLR'} — the one AR-numbering
 * sequence the STORED remaining invoice ({@code sales.remaining_invoice}, V188) and the STORED
 * ใบวางบิล (billing note, {@code sales.billing_note}, V189) both draw from, so their two document
 * families can never collide with each other, only with themselves.
 *
 * <p><b>Moved here (GLA-99 step 3) from {@code RemainingInvoiceRepository#nextDocNumber}</b>, which
 * originally owned this method before any second caller existed. A plain static helper over a
 * caller-supplied {@link NamedParameterJdbcTemplate} — not a Spring bean — so adopting it required
 * no constructor change (and so no test-fixture churn) in either repository that already existed
 * when this class was extracted: {@link th.co.glr.hr.deposit.RemainingInvoiceRepository#nextDocNumber}
 * now delegates here verbatim, and {@link th.co.glr.hr.billing.BillingNoteRepository} calls it
 * directly.
 *
 * <p>Must be called INSIDE the caller's own issuing transaction — a refused issue then rolls the
 * mint back rather than burning a number, the same property {@code DepositNoticeRepository
 * #nextDocNumber} and {@code ImportRequestRepository#nextDocNumber} document for their own
 * per-doc-type sequences.
 */
public final class ArGlrSequence {
    private static final String DOC_TYPE = "AR_GLR";

    private ArGlrSequence() {}

    public static String next(NamedParameterJdbcTemplate jdbc, int yearTh) {
        jdbc.update("""
            INSERT INTO sales.document_sequence (doc_type, year_th, last_seq)
            VALUES (:docType, :y, 0) ON CONFLICT DO NOTHING
            """, Map.of("docType", DOC_TYPE, "y", yearTh));
        Integer seq = jdbc.queryForObject("""
            UPDATE sales.document_sequence SET last_seq = last_seq + 1
             WHERE doc_type = :docType AND year_th = :y
            RETURNING last_seq
            """, Map.of("docType", DOC_TYPE, "y", yearTh), Integer.class);
        return String.format("GLR%02d%05d", yearTh % 100, seq);
    }
}
