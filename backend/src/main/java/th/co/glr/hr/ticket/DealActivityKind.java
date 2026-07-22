package th.co.glr.hr.ticket;

import java.util.Set;

/**
 * What kind of follow-up a {@code sales.deal_activity} row records. Backs
 * {@code chk_deal_activity_kind} (V83) — keep in sync with that CHECK constraint.
 *
 * <p>Owner-review assumption (Slice B1, handoff 103): this is a first-pass list, not an
 * owner-confirmed taxonomy of how the sales team actually follows up on a deal. Revisit once B2
 * (the frontend activity log) is in front of real reps.
 */
public final class DealActivityKind {
    public static final String CALL              = "CALL";
    public static final String MEETING            = "MEETING";
    public static final String SITE_VISIT          = "SITE_VISIT";
    public static final String EMAIL              = "EMAIL";
    public static final String MESSAGE            = "MESSAGE";
    public static final String QUOTATION_FOLLOWUP = "QUOTATION_FOLLOWUP";
    public static final String OTHER              = "OTHER";

    public static final Set<String> VALID = Set.of(
        CALL, MEETING, SITE_VISIT, EMAIL, MESSAGE, QUOTATION_FOLLOWUP, OTHER);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    private DealActivityKind() {}
}
