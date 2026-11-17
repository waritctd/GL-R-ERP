package th.co.glr.hr.payroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Payroll draft optimistic concurrency (issue #422 follow-up): computes the month-level ETag that GET
 * /api/payroll/input-draft returns and PUT /api/payroll/input-draft requires back as If-Match. See
 * PayrollRepository.DraftRowVersion's own javadoc for the full walkthrough. */
final class PayrollDraftETag {
    private PayrollDraftETag() {
    }

    /** The well-defined token for a month with zero saved draft rows. */
    static final String EMPTY = "empty-v1";

    /** Rows MUST already be ordered by employeeId -- this method does not re-sort, so a caller passing an unordered
     * list would get a token that is unstable across two reads of the identical row set, defeating the whole point
     * of an ETag. */
    static String compute(List<PayrollRepository.DraftRowVersion> rows) {
        if (rows.isEmpty()) {
            return EMPTY;
        }
        // `:` separates fields and `;` separates rows -- safe delimiters because every field here is a decimal integer
        // (draftId/employeeId are BIGINT, version is INTEGER) and neither character can appear inside one.
        StringBuilder material = new StringBuilder();
        for (PayrollRepository.DraftRowVersion row : rows) {
            material.append(row.draftId()).append(':').append(row.employeeId()).append(':')
                .append(row.version()).append(';');
        }
        return sha256Hex(material.toString());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JLS/JCA baseline) -- unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
