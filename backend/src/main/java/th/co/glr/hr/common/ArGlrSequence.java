package th.co.glr.hr.common;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Mints the next GLR<yy><5-digit seq> number from the shared sales.document_sequence row for doc_type =
 * 'AR_GLR' — the one AR-numbering sequence the STORED remaining invoice and the STORED ใบวางบิล both draw
 * from, so their two document families can never collide with each other, only with themselves. */
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
