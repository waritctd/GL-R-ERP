package th.co.glr.hr.ticket;

import java.util.Set;

/** What kind of follow-up a sales.deal_activity row records. Backs chk_deal_activity_kind (V83, widened by V224) — keep
 * in sync with that CHECK constraint (DealActivitySampleSentIntegrationTest compares the two on a live database, and
 * frontend/src/features/tickets/activityKinds.guard.test.js fails when a place that lists kinds misses one).
 * Owner-review assumption (Slice B1, handoff 103): this is a first-pass list, not an owner-confirmed taxonomy of how
 * the sales team actually follows up on a deal. */
public final class DealActivityKind {
    public static final String CALL              = "CALL";
    public static final String MEETING            = "MEETING";
    public static final String SITE_VISIT          = "SITE_VISIT";
    public static final String EMAIL              = "EMAIL";
    public static final String MESSAGE            = "MESSAGE";
    public static final String QUOTATION_FOLLOWUP = "QUOTATION_FOLLOWUP";
    public static final String OTHER              = "OTHER";
    /** ส่งตัวอย่าง (slice 6d, V224): a sample sent to the customer. Its note — what was sent — is required
     * ({@link #requiresNote}); it is a follow-up touch like any other kind. */
    public static final String SAMPLE_SENT        = "SAMPLE_SENT";

    public static final Set<String> VALID = Set.of(
        CALL, MEETING, SITE_VISIT, EMAIL, MESSAGE, QUOTATION_FOLLOWUP, OTHER, SAMPLE_SENT);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    /** The kinds whose note is what the activity IS, so a blank one is refused: only ส่งตัวอย่าง ("what was sent"). */
    public static boolean requiresNote(String kind) {
        return SAMPLE_SENT.equals(kind);
    }

    private DealActivityKind() {}
}
