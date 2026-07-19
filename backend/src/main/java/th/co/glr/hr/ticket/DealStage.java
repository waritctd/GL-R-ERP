package th.co.glr.hr.ticket;

import java.util.List;

/**
 * The 14-stage deal pipeline (V50), grouped into 5 phases. One ticket = one
 * deal; sales_stage tracks what it takes to close that deal.
 *
 * Auto-advanced stages (from the deal's own operational transitions):
 * ORDER_RECEIVED (confirmCustomer), DEPOSIT_RECEIVED (confirmDepositPaid),
 * PROCUREMENT (issueImportRequest), CLOSED_PAID (confirmFinalPayment). The
 * mid-fulfillment states (IR_SENT / SHIPPING / GOODS_RECEIVED) all live inside
 * PROCUREMENT via fulfillment_status — no separate stages.
 *
 * Business cross-reference (boss's S1–S20 sheet): LEAD_APPROACH=S1,
 * PRESENTATION=S2, SPEC_APPROVED=S3, QUOTE_DESIGN_SIDE=S4+S5, OWNER_SIGNOFF=S6,
 * AWAITING_BUYER=S7, QUOTE_BUYER=S8, NEGOTIATION=S9, ORDER_RECEIVED=S10,
 * DEPOSIT_RECEIVED=S11, PROCUREMENT=S12–S17, DELIVERY_SCHEDULING=S18,
 * DELIVERED=S19, CLOSED_PAID=S20.
 */
public final class DealStage {
    // Phase 1 — การเข้าถึงโครงการ (Lead)
    public static final String LEAD_APPROACH = "LEAD_APPROACH";
    public static final String PRESENTATION  = "PRESENTATION";
    // Phase 2 — งานสเปค (Specification)
    public static final String SPEC_APPROVED     = "SPEC_APPROVED";
    public static final String QUOTE_DESIGN_SIDE = "QUOTE_DESIGN_SIDE";
    public static final String OWNER_SIGNOFF     = "OWNER_SIGNOFF";
    // Phase 3 — ประมูลและเจรจา (Bidding)
    public static final String AWAITING_BUYER = "AWAITING_BUYER";
    public static final String QUOTE_BUYER    = "QUOTE_BUYER";
    public static final String NEGOTIATION    = "NEGOTIATION";
    // Phase 4 — คำสั่งซื้อและนำเข้า (Order & import)
    public static final String ORDER_RECEIVED   = "ORDER_RECEIVED";
    public static final String DEPOSIT_RECEIVED = "DEPOSIT_RECEIVED";
    public static final String PROCUREMENT      = "PROCUREMENT";
    // Phase 5 — ส่งมอบและปิดงาน (Delivery & closing)
    public static final String DELIVERY_SCHEDULING = "DELIVERY_SCHEDULING";
    public static final String DELIVERED           = "DELIVERED";
    public static final String CLOSED_PAID         = "CLOSED_PAID";

    /** Pipeline order — index defines forward/backward for monotonic auto-advance. */
    public static final List<String> ORDER = List.of(
        LEAD_APPROACH, PRESENTATION,
        SPEC_APPROVED, QUOTE_DESIGN_SIDE, OWNER_SIGNOFF,
        AWAITING_BUYER, QUOTE_BUYER, NEGOTIATION,
        ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT,
        DELIVERY_SCHEDULING, DELIVERED, CLOSED_PAID
    );

    /** 0-based position in the pipeline, or -1 for an unknown code. */
    public static int indexOf(String stage) {
        return ORDER.indexOf(stage);
    }

    /**
     * Backward moves that are a normal part of the sales flow, and so do not
     * require a written reason.
     *
     * QUOTE_DESIGN_SIDE (S4+S5) sits after SPEC_APPROVED (S3) in {@link #ORDER},
     * but the business's most common path quotes the designer *before* the
     * designer signs off the spec — the flow analysis gives the fullest route as
     * S1 → S2 → S4 → S3 → S5. Treating that everyday step as an exception to be
     * justified in writing puts friction on the default path.
     *
     * Deliberately an allowlist of one adjacent pair rather than a reordering of
     * {@link #ORDER}: the order is mirrored by the V50 CHECK constraint, the uat
     * seeds, the frontend stage metadata and every historical sales_stage value,
     * so renumbering carries far more risk than this ergonomic fix is worth.
     */
    public static boolean isRoutineBackwardMove(String fromStage, String toStage) {
        return QUOTE_DESIGN_SIDE.equals(fromStage) && SPEC_APPROVED.equals(toStage);
    }

    public static boolean isValid(String stage) {
        return stage != null && ORDER.contains(stage);
    }

    private DealStage() {}
}
