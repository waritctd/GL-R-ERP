package th.co.glr.hr.importprogress;

import java.util.List;
import java.util.Map;

/**
 * The six per-factory import steps (the boss's S12–S17 sheet range), tracked on each {@code
 * sales.factory_import_progress} row's {@code import_step} column.
 *
 * <p>There is deliberately no purchase-order / supplier-price machinery behind this — the real
 * flow is that Import generates an order-email template per factory, sends it themselves, and
 * records progress here (owner ruling, 2026-09). So this axis is price-free and safe for sales to
 * see, unlike the dormant {@code th.co.glr.hr.procurement} PO module.
 *
 * <p>Progression order is {@link #SEQUENCE}; {@link #rank} gives a step its position so an advance
 * can be checked monotonic (forward only). A value not in the sequence ranks -1 — callers MUST
 * treat that as "not a step", never "earliest".
 */
public final class ImportStep {
    /** S12 — the ใบขอซื้อ (IR) has been sent to the purchasing team. */
    public static final String IR_SENT = "IR_SENT";
    /** S13 — purchasing has placed the order with the manufacturer. */
    public static final String ORDERED = "ORDERED";
    /** S14 — the carrier has collected the goods from the manufacturer. */
    public static final String PICKED_UP = "PICKED_UP";
    /** S15 — the goods are in transit. */
    public static final String IN_TRANSIT = "IN_TRANSIT";
    /** S16 — the goods have reached Thailand and are awaiting customs clearance. */
    public static final String CUSTOMS_CLEARANCE = "CUSTOMS_CLEARANCE";
    /** S17 — the goods have reached the warehouse. */
    public static final String RECEIVED = "RECEIVED";

    public static final List<String> SEQUENCE =
        List.of(IR_SENT, ORDERED, PICKED_UP, IN_TRANSIT, CUSTOMS_CLEARANCE, RECEIVED);

    /** The business's S-number for each step (the CEO's own S1–S20 sheet); display only. */
    private static final Map<String, String> BUSINESS_CODE = Map.of(
        IR_SENT, "S12", ORDERED, "S13", PICKED_UP, "S14",
        IN_TRANSIT, "S15", CUSTOMS_CLEARANCE, "S16", RECEIVED, "S17");

    private ImportStep() {}

    public static boolean isValid(String step) {
        return step != null && SEQUENCE.contains(step);
    }

    /** Position within {@link #SEQUENCE}, or -1 when {@code step} is not a real step (or null). */
    public static int rank(String step) {
        return step == null ? -1 : SEQUENCE.indexOf(step);
    }

    public static String businessCodeOf(String step) {
        return BUSINESS_CODE.get(step);
    }
}
