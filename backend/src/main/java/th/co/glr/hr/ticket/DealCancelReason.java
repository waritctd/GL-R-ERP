package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Reasons a deal is cancelled (V57). Rolling the two together would poison win/loss reporting with deals that
 * were never winnable. Mirrors DealLostReason's shape. Cancel previously recorded no reason at all, so a
 * cancelled deal carried zero explanation while its lost sibling carried a structured code.
 *
 * <p>Owner ruling 2026-11-16 (the ยกเลิกงาน drawing): cancelling now means "this deal was opened by mistake", so a
 * NEW cancellation is offered {@link #OFFERED} — duplicate / wrong customer or project / no real project / other.
 * The three earlier codes ({@link #RETIRED}) stay VALID: already-cancelled deals carry them and keep their labels,
 * and the API still accepts them. Additive only (V219 widened the CHECK constraint); no row is rewritten. */
public final class DealCancelReason {
    /** เปิดซ้ำกับงานอื่น — the same opportunity already has a deal. */
    public static final String DUPLICATE_DEAL            = "DUPLICATE_DEAL";
    /** เปิดผิดลูกค้า / โครงการ — keyed against the wrong customer or project. */
    public static final String WRONG_CUSTOMER_OR_PROJECT = "WRONG_CUSTOMER_OR_PROJECT";
    /** ไม่มีโครงการจริง — there was never a real project behind it. */
    public static final String NO_REAL_PROJECT           = "NO_REAL_PROJECT";
    /** Anything else — the note carries the detail. Offered both before and after the 2026-11-16 ruling. */
    public static final String OTHER                     = "OTHER";

    /** Retired 2026-11-16 — still valid, never offered for a new cancellation. เจ้าของยกเลิกโครงการ. */
    public static final String OWNER_CANCELLED   = "OWNER_CANCELLED";
    /** Retired 2026-11-16. โครงการถูกระงับไม่มีกำหนด. */
    public static final String PROJECT_SUSPENDED = "PROJECT_SUSPENDED";
    /** Retired 2026-11-16. งบประมาณถูกยกเลิก. */
    public static final String BUDGET_CANCELLED  = "BUDGET_CANCELLED";

    /** What a NEW cancellation offers, in the drawing's order, OTHER last. GET /api/meta/deal-stages serves this as
     * {@code cancelReasons}. */
    public static final List<String> OFFERED = List.of(
        DUPLICATE_DEAL, WRONG_CUSTOMER_OR_PROJECT, NO_REAL_PROJECT, OTHER);

    /** The pre-2026-11-16 codes that are no longer offered but stay valid. */
    public static final List<String> RETIRED = List.of(OWNER_CANCELLED, PROJECT_SUSPENDED, BUDGET_CANCELLED);

    /** Presentation order for a client rendering the choice as a list — the same list as {@link #OFFERED}. */
    public static final List<String> ORDER = OFFERED;

    /** Every code the API accepts and a row may carry: offered and retired. */
    public static final Set<String> VALID = Set.copyOf(Stream.concat(OFFERED.stream(), RETIRED.stream()).toList());

    public static boolean isValid(String reason) {
        return reason != null && VALID.contains(reason);
    }

    // Wording per the owner ruling of 2026-11-16: labels say งาน, not ดีล (the drawing's "เปิดซ้ำกับดีลอื่น").
    private static final java.util.Map<String, String> LABELS = java.util.Map.of(
        DUPLICATE_DEAL, "เปิดซ้ำกับงานอื่น",
        WRONG_CUSTOMER_OR_PROJECT, "เปิดผิดลูกค้า / โครงการ",
        NO_REAL_PROJECT, "ไม่มีโครงการจริง",
        OTHER, "อื่น ๆ",
        OWNER_CANCELLED, "เจ้าของยกเลิกโครงการ",
        PROJECT_SUSPENDED, "โครงการถูกระงับไม่มีกำหนด",
        BUDGET_CANCELLED, "งบประมาณถูกยกเลิก");

    /** Thai label of a code (same wording as frontend CANCEL_REASON_LABEL); an unknown code reads as itself. */
    public static String label(String code) {
        return LABELS.getOrDefault(code, String.valueOf(code));
    }

    private DealCancelReason() {}
}
