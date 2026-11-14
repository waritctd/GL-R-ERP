package th.co.glr.hr.importrequest;

import java.util.List;
import java.util.Map;

/** The six per-factory import steps, tracked on the issued ใบขอซื้อ row for one rather than on a purchase order
 * — this codebase has no live purchase-order aggregate for imports. A deliberately SEPARATE vocabulary from
 * th.co.glr.hr.ticket.FulfilmentStatus. */
public final class ImportRequestStep {
    /** S12 — the factory has been contacted. Set automatically on first ISSUE. */
    public static final String CONTACTED        = "CONTACTED";
    /** S13 — the order has been placed with the factory. */
    public static final String ORDERED          = "ORDERED";
    /** S14 — goods picked up from the factory / port of origin. */
    public static final String PICKED_UP        = "PICKED_UP";
    /** S15 — in transit (shipping). */
    public static final String IN_TRANSIT       = "IN_TRANSIT";
    /** S16 — awaiting customs clearance. */
    public static final String AWAITING_CUSTOMS = "AWAITING_CUSTOMS";
    /** S17 — received at GL&R's own warehouse. Reaching this on every factory rolls the deal up. */
    public static final String RECEIVED          = "RECEIVED";

    /** Progression order — index defines forward/backward for the compare-and-set advance. */
    public static final List<String> ORDER = List.of(
        CONTACTED, ORDERED, PICKED_UP, IN_TRANSIT, AWAITING_CUSTOMS, RECEIVED);

    /** 0-based position, or -1 for an unknown/null code. */
    public static int indexOf(String step) {
        return step == null ? -1 : ORDER.indexOf(step);
    }

    public static boolean isValid(String step) {
        return step != null && ORDER.contains(step);
    }

    /** The owner's own Thai labels for each step (S12-S17), defined ONCE here (REVIEW ROUND 1 nit) so every caller
     * that prints a step in a human-facing message — today only ImportRequestService#advanceStep's ticket_event
     * text — uses the same wording instead of each inventing its own. */
    private static final Map<String, String> THAI_LABELS = Map.of(
        CONTACTED,        "ติดต่อโรงงาน",
        ORDERED,          "สั่งซื้อแล้ว",
        PICKED_UP,        "รับสินค้าจากโรงงาน",
        IN_TRANSIT,       "ระหว่างขนส่ง",
        AWAITING_CUSTOMS, "รอผ่านพิธีการศุลกากร",
        RECEIVED,         "รับสินค้าเข้าคลัง"
    );

    /** The Thai label for step, or the raw code itself if unrecognised (never throws — this backs free-text event
     * messages, where a raw code is a safer fallback than a thrown exception). */
    public static String thaiLabel(String step) {
        return THAI_LABELS.getOrDefault(step, step);
    }

    private ImportRequestStep() {}
}
