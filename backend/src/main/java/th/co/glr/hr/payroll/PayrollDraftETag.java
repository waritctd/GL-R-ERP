package th.co.glr.hr.payroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Payroll draft optimistic concurrency (issue #422 follow-up): computes the month-level ETag that
 * {@code GET /api/payroll/input-draft} returns and {@code PUT /api/payroll/input-draft} requires
 * back as {@code If-Match}.
 *
 * <p>The concurrency unit is the whole month's draft set, not one employee's row -- the form
 * submits every employee unfiltered on every save (see {@code PayrollPage.jsx}'s
 * {@code draftPayload()}), so a per-row conflict signal would be unusable: HR did not edit "row
 * 47", they edited the month. {@code hr.payroll_input_draft.version} (V113) is the durable
 * per-row truth; this class deterministically folds every row's {@code (draftId, employeeId,
 * version)} triple into one token that changes if ANY row's version changes, if a row is added,
 * or if a row is removed.
 *
 * <p><b>{@code draftId} is in the material too, not just {@code version} (Opus review finding F1,
 * an ABA hazard)</b>: {@code version} alone is monotonic within one row's lifetime but NOT across
 * a delete-and-reinsert -- {@code process()} deletes every draft row for a processed month, and a
 * later re-save for the same employee/month lands right back at {@code version = 0} (the column
 * DEFAULT), producing the identical token an earlier, already-superseded pre-process state also
 * had. {@code draftId} is the {@code GENERATED ALWAYS AS IDENTITY} primary key -- a fresh sequence
 * value on every INSERT, including a reinsert -- so it breaks that collision. See {@link
 * PayrollRepository.DraftRowVersion}'s own javadoc for the full walkthrough.
 *
 * <p>Deliberately a pure function of already-fetched rows (per the plan: "prefer computing the
 * ETag in Java from the fetched rows over doing it in SQL") -- easier to unit-test than a SQL
 * aggregate, and callable from both {@link PayrollService#getInputDraft} (a plain snapshot) and
 * {@link PayrollService#saveInputDraft} (the locked compare-then-write).
 */
final class PayrollDraftETag {
    private PayrollDraftETag() {
    }

    /**
     * The well-defined token for a month with zero saved draft rows -- deliberately NOT {@code
     * null} or {@code ""}: a brand-new month's first save still needs a real token to send as
     * {@code If-Match}, and a caller comparing against {@code null}/blank invites exactly the kind
     * of "headerless write silently allowed" hole this feature exists to close.
     */
    static final String EMPTY = "empty-v1";

    /**
     * Rows MUST already be ordered by {@code employeeId} (both {@link
     * PayrollRepository#findInputDraftVersions} and {@link PayrollRepository#findInputDraftRows}
     * guarantee this via {@code ORDER BY employee_id}) -- this method does not re-sort, so a
     * caller passing an unordered list would get a token that is unstable across two reads of the
     * identical row set, defeating the whole point of an ETag.
     */
    static String compute(List<PayrollRepository.DraftRowVersion> rows) {
        if (rows.isEmpty()) {
            return EMPTY;
        }
        // `:` separates fields and `;` separates rows -- safe delimiters because every field here
        // is a decimal integer (draftId/employeeId are BIGINT, version is INTEGER) and neither
        // character can appear inside one. draftId comes first specifically so two rows that
        // differ only in HOW their digits are split (e.g. draftId=1/employeeId=23 vs
        // draftId=12/employeeId=3) still produce distinguishable material -- "1:23:" vs "12:3:",
        // never colliding on the concatenated digit string the way an unseparated encoding would.
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
