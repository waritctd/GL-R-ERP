package th.co.glr.hr.ticket;

import java.util.Set;

/**
 * Reasons a deal is cancelled (V56). Mirrors {@link DealLostReason}'s shape.
 *
 * Cancelled is NOT lost, and the distinction is the point: lost means the
 * customer bought elsewhere or we failed to win the work — a competitive
 * outcome worth analysing. Cancelled means the opportunity itself went away
 * (the owner pulled the project, the budget was withdrawn), which says nothing
 * about how GL&R competed. Rolling the two together would poison win/loss
 * reporting with deals that were never winnable.
 *
 * Cancel previously recorded no reason at all, so a cancelled deal carried zero
 * explanation while its lost sibling carried a structured code.
 */
public final class DealCancelReason {
    /** เจ้าของยกเลิกโครงการ — the customer called the project off. */
    public static final String OWNER_CANCELLED   = "OWNER_CANCELLED";
    /** โครงการถูกระงับไม่มีกำหนด — suspended indefinitely, no restart date. */
    public static final String PROJECT_SUSPENDED = "PROJECT_SUSPENDED";
    /** งบประมาณถูกยกเลิก — the budget was withdrawn. */
    public static final String BUDGET_CANCELLED  = "BUDGET_CANCELLED";
    /** Anything else — the note carries the detail. */
    public static final String OTHER             = "OTHER";

    public static final Set<String> VALID = Set.of(
        OWNER_CANCELLED, PROJECT_SUSPENDED, BUDGET_CANCELLED, OTHER
    );

    public static boolean isValid(String reason) {
        return reason != null && VALID.contains(reason);
    }

    private DealCancelReason() {}
}
