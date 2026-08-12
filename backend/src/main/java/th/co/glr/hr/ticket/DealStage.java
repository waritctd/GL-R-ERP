package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Set;

/**
 * The 15-stage deal pipeline (V50, widened by V143), grouped into 5 phases. One
 * ticket = one deal; sales_stage tracks what it takes to close that deal.
 *
 * Auto-advanced stages (from the deal's own operational transitions):
 * ORDER_RECEIVED (confirmCustomer), DEPOSIT_RECEIVED (confirmDepositPaid),
 * PROCUREMENT (issueImportRequest), CLOSED_PAID (confirmFinalPayment). The
 * mid-fulfillment states (IR_SENT / SHIPPING / GOODS_RECEIVED) all live inside
 * PROCUREMENT via fulfillment_status — no separate stages.
 *
 * Business cross-reference (boss's S1–S20 sheet): LEAD_APPROACH=S1,
 * PRESENTATION=S2, SPEC_APPROVED=S3, QUOTE_DESIGN_SIDE=S4, QUOTE_OWNER=S5,
 * OWNER_SIGNOFF=S6, AWAITING_BUYER=S7, QUOTE_BUYER=S8, NEGOTIATION=S9,
 * ORDER_RECEIVED=S10, DEPOSIT_RECEIVED=S11, PROCUREMENT=S12–S17,
 * DELIVERY_SCHEDULING=S18, DELIVERED=S19, CLOSED_PAID=S20.
 */
public final class DealStage {
    // Phase 1 — การเข้าถึงโครงการ (Lead)
    public static final String LEAD_APPROACH = "LEAD_APPROACH";
    public static final String PRESENTATION  = "PRESENTATION";
    // Phase 2 — งานสเปค (Specification)
    public static final String SPEC_APPROVED     = "SPEC_APPROVED";
    /**
     * S4 — the quotation sent to the <strong>designer</strong>, and only the designer.
     *
     * <p>This constant used to mean "S4+S5" (designer AND owner quoted), because
     * {@code TicketService}'s recipient routing mapped both {@code DESIGNER} and {@code OWNER}
     * onto it. The CEO tracks them as two separate milestones with genuinely different document
     * content — the product list is the same, but quantity, price and terms differ by recipient
     * (S4 is model + price only, with a nominal quantity and terms usually omitted; S5 carries a
     * negotiated price, the owner's real quantity, and the import lead time) — so the merge made
     * {@code sales_stage} unable to answer "has the owner been quoted yet?", which is exactly the
     * milestone before {@link #OWNER_SIGNOFF}. {@link #QUOTE_OWNER} now carries S5.
     *
     * <p><strong>The string value is deliberately unchanged.</strong> Historical
     * {@code sales.ticket.sales_stage} rows, the uat seeds and the frontend stage metadata all
     * carry it, and a historical row is genuinely ambiguous about which recipient it meant — so
     * nothing is backfilled and nothing is renamed; only the forward meaning narrows.
     */
    public static final String QUOTE_DESIGN_SIDE = "QUOTE_DESIGN_SIDE";
    /**
     * S5 — the quotation sent to the <strong>project owner</strong> (V143). Split out of
     * {@link #QUOTE_DESIGN_SIDE}; see that constant's Javadoc for the full reasoning.
     */
    public static final String QUOTE_OWNER       = "QUOTE_OWNER";
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
        SPEC_APPROVED, QUOTE_DESIGN_SIDE, QUOTE_OWNER, OWNER_SIGNOFF,
        AWAITING_BUYER, QUOTE_BUYER, NEGOTIATION,
        ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT,
        DELIVERY_SCHEDULING, DELIVERED, CLOSED_PAID
    );

    /**
     * Stages whose absence from a deal's history is an <strong>anomaly</strong>, not a route.
     *
     * <p>The owner's four real sales routes are mostly not a straight line through {@link #ORDER}:
     *
     * <ul>
     *   <li><strong>Case A</strong> (designer → owner → contractor, the full route):
     *       S1→S2→S4→S3→S5→S6→S7→S8→S9→S10→(S11 if a deposit is required)→S12…S17→S18→S19 ∥ S20.
     *   <li><strong>Case B</strong> (the owner buys directly, no contractor): skips S3, S4, S7
     *       and S8 — the owner <em>is</em> the buyer.
     *   <li><strong>Case C</strong> (a contractor arrives with a BOQ and spec already in hand):
     *       starts at S8, skipping S1 through S7 entirely.
     *   <li><strong>Case D</strong> (everything already in stock): skips S12–S16, i.e.
     *       {@link #PROCUREMENT}.
     * </ul>
     *
     * <p>Every stage skipped by B, C or D is skipped because a <em>party</em> is not involved
     * (route-dependent: {@link #LEAD_APPROACH}, {@link #PRESENTATION}, {@link #SPEC_APPROVED},
     * {@link #QUOTE_DESIGN_SIDE}, {@link #QUOTE_OWNER}, {@link #OWNER_SIGNOFF},
     * {@link #AWAITING_BUYER}, {@link #QUOTE_BUYER}) or because of the <em>shape</em> of the deal
     * (conditional: {@link #DEPOSIT_RECEIVED} only when a deposit is required,
     * {@link #PROCUREMENT} only when the goods must be imported). Demanding a written
     * justification for any of those puts friction on the default path, which is the same defect
     * {@link #isRoutineBackwardMove} already patches by hand for one adjacent pair.
     *
     * <p>What remains — the five below — is what every route has in common, so stepping over one
     * of them really is an exception worth a sentence.
     */
    private static final Set<String> MANDATORY = Set.of(
        NEGOTIATION, ORDER_RECEIVED, DELIVERY_SCHEDULING, DELIVERED, CLOSED_PAID);

    /** 0-based position in the pipeline, or -1 for an unknown code. */
    public static int indexOf(String stage) {
        return ORDER.indexOf(stage);
    }

    /**
     * Backward moves that are a normal part of the sales flow, and so do not
     * require a written reason.
     *
     * QUOTE_DESIGN_SIDE (S4) sits after SPEC_APPROVED (S3) in {@link #ORDER},
     * but the business's most common path quotes the designer *before* the
     * designer signs off the spec — the flow analysis gives the fullest route as
     * S1 → S2 → S4 → S3 → S5. Treating that everyday step as an exception to be
     * justified in writing puts friction on the default path.
     *
     * Deliberately an allowlist of one adjacent pair rather than a reordering of
     * {@link #ORDER}: the order is mirrored by the V50 CHECK constraint, the uat
     * seeds, the frontend stage metadata and every historical sales_stage value,
     * so renumbering carries far more risk than this ergonomic fix is worth.
     *
     * <p>V143 did NOT widen this when {@link #QUOTE_OWNER} was inserted. S5 comes after S3 in the
     * real route (S2→S4→S3→S5), so quoting the owner is reached going <em>forward</em> from
     * SPEC_APPROVED — there is no new everyday backward step to allowlist. Any
     * {@code QUOTE_OWNER -> …} backward move stays a genuine regression that must be explained.
     *
     * <p>Private since V143: {@link #requiresJustification} is the one entry point callers use,
     * so this stays a special case inside the single decision rather than a second parallel
     * mechanism a call site could forget to consult.
     */
    private static boolean isRoutineBackwardMove(String fromStage, String toStage) {
        return QUOTE_DESIGN_SIDE.equals(fromStage) && SPEC_APPROVED.equals(toStage);
    }

    /**
     * Does moving {@code fromStage -> toStage} require the rep to write down why?
     *
     * <p>The single decision behind {@code TicketService.updateStage}'s note requirement,
     * replacing the raw index arithmetic ({@code indexOf(to) - indexOf(from) > 1}) that used to
     * decide it. That arithmetic treated <em>every</em> multi-stage forward move as an anomaly,
     * which demanded a justification for three of the business's four normal routes — see
     * {@link #MANDATORY} for the routes and the grouping.
     *
     * <ul>
     *   <li><strong>Forward:</strong> true only when a {@link #MANDATORY} stage lies strictly
     *       between the two. The target itself is never "skipped" — jumping straight to S8
     *       (Case C) crosses only route-dependent stages and needs no note; jumping from
     *       ORDER_RECEIVED past DELIVERY_SCHEDULING to DELIVERED does need one.
     *   <li><strong>Backward:</strong> unchanged from before — always true except for the one
     *       {@link #isRoutineBackwardMove} pair.
     *   <li><strong>Same stage / unknown code:</strong> false. {@code updateStage} rejects both
     *       before it ever asks (409 and 400 respectively), so this predicate does not
     *       second-guess them.
     * </ul>
     */
    public static boolean requiresJustification(String fromStage, String toStage) {
        // Null-checked here rather than in indexOf: ORDER is a List.of(), whose indexOf throws NPE
        // on a null probe instead of returning -1. Neither argument can be null through
        // updateStage (the target is isValid-checked first, and sales_stage is NOT NULL since
        // V50), but this method's contract says "unknown code -> false", and a null IS an unknown
        // code. Leaving indexOf itself alone: it is a pre-existing public helper with other
        // callers, and widening its contract is not this task.
        if (fromStage == null || toStage == null) {
            return false;
        }
        int from = indexOf(fromStage);
        int to = indexOf(toStage);
        if (from < 0 || to < 0 || from == to) {
            return false;
        }
        if (to < from) {
            return !isRoutineBackwardMove(fromStage, toStage);
        }
        // Strictly between: the stages the deal steps OVER. `to` itself is being landed on, not
        // skipped, and `from` has already happened.
        return ORDER.subList(from + 1, to).stream().anyMatch(MANDATORY::contains);
    }

    public static boolean isValid(String stage) {
        return stage != null && ORDER.contains(stage);
    }

    private DealStage() {}
}
