package th.co.glr.hr.importrequest;

import java.util.List;
import java.util.Map;

/**
 * The six per-factory import steps (S12-S17, GLA-100), tracked on the issued ใบขอซื้อ row for one
 * (deal, factory) rather than on a purchase order — this codebase has no live purchase-order
 * aggregate for imports (see {@code sales.factory_purchase_order}'s own header: that module is
 * dormant, ruled out of scope by the owner for this feature).
 *
 * <p><strong>A deliberately SEPARATE vocabulary from {@link th.co.glr.hr.ticket.FulfilmentStatus}.
 * </strong> {@code FulfilmentStatus.IMPORT_SEQUENCE} (IR_ISSUED/IR_SENT/SHIPPING/GOODS_RECEIVED) is
 * the DEAL-level rollup that {@link ImportRequestService}'s {@code advanceStep} writes TO when the
 * last factory reaches {@link #RECEIVED} — it is not extended or reused here, and a step name here
 * must never be written as a {@code ticket_event.kind} or a notification {@code type}: {@code
 * PICKED_UP} in particular is already a live, unrelated {@code TicketEventKind} constant and
 * notification type (a deal-pickup event, nothing to do with import). Every step advance is
 * recorded as ONE event kind, {@link th.co.glr.hr.ticket.TicketEventKind#IMPORT_STEP_ADVANCED},
 * with the actual step named in the event's free-text message/note — never as the kind itself.
 *
 * <p>Deliberately NOT Yang's set (commit 83f4fa78, {@code origin/feat/per-factory-import-tracking}):
 * that branch's {@code IR_SENT} collides in meaning with {@code FulfilmentStatus.IR_SENT}/{@code
 * TicketEventKind.IR_SENT} (a different, deal-level concept), and its {@code CUSTOMS_CLEARANCE} is a
 * constant a prior commit (7991b9f1) deliberately deleted. This set is the owner's own S12-S17 sheet
 * (GLA-100) instead.
 */
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

    /**
     * The owner's own Thai labels for each step (S12-S17), defined ONCE here (REVIEW ROUND 1 nit) so
     * every caller that prints a step in a human-facing message — today only {@code
     * ImportRequestService#advanceStep}'s {@code ticket_event} text — uses the same wording instead
     * of each inventing its own.
     */
    private static final Map<String, String> THAI_LABELS = Map.of(
        CONTACTED,        "ติดต่อโรงงาน",
        ORDERED,          "สั่งซื้อแล้ว",
        PICKED_UP,        "รับสินค้าจากโรงงาน",
        IN_TRANSIT,       "ระหว่างขนส่ง",
        AWAITING_CUSTOMS, "รอผ่านพิธีการศุลกากร",
        RECEIVED,         "รับสินค้าเข้าคลัง"
    );

    /** The Thai label for {@code step}, or the raw code itself if unrecognised (never throws — this
     * backs free-text event messages, where a raw code is a safer fallback than a thrown exception). */
    public static String thaiLabel(String step) {
        return THAI_LABELS.getOrDefault(step, step);
    }

    private ImportRequestStep() {}
}
