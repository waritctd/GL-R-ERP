package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
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

    // ── Write gates ─────────────────────────────────────────────────────────────────────────
    //
    // These three sets moved here from TicketService (where they were private static fields) so
    // that ONE definition serves both enforcement and description. TicketService.
    // requireStageWriteAccess still keys off exactly these sets — membership here is what makes
    // the difference between a 403 and a write — and {@link #gateOf} now reads the same sets to
    // tell a client which department owns a stage. Before this move the client's copy of the
    // gate lived in the frontend's stageMeta.js as a hand-maintained literal, which is precisely
    // the drift this class is being consolidated to end. Do not re-declare a gate anywhere else.

    /**
     * Stages whose manual fallback belongs to the deal owner / sales_manager / ceo.
     *
     * <p>{@link #QUOTE_OWNER} is in the set (V143) for the same reason {@link #QUOTE_DESIGN_SIDE}
     * is: quoting the owner is a sales action, so when the automatic advance at quotation-issue
     * time did not happen (a quotation raised outside the PCR chain, or a stage corrected after
     * the fact) the fallback must belong to the same three principals — not to account or import,
     * whose money/import stages are the separate sets below.
     */
    public static final Set<String> SALES_TARGET_STAGES = Set.of(
        LEAD_APPROACH, PRESENTATION, SPEC_APPROVED,
        QUOTE_DESIGN_SIDE, QUOTE_OWNER, OWNER_SIGNOFF,
        AWAITING_BUYER, QUOTE_BUYER, NEGOTIATION,
        ORDER_RECEIVED, DELIVERY_SCHEDULING, DELIVERED);
    /** Money stages — manual fallback for account/ceo (normally auto from the payment track). */
    public static final Set<String> ACCOUNT_TARGET_STAGES = Set.of(
        DEPOSIT_RECEIVED, CLOSED_PAID);
    /** Import stage — manual fallback for import/ceo (normally auto from the IR). */
    public static final Set<String> IMPORT_TARGET_STAGES = Set.of(PROCUREMENT);

    public static final String GATE_SALES = "sales";
    public static final String GATE_ACCOUNT = "account";
    public static final String GATE_IMPORT = "import";

    /**
     * Which department owns the manual fallback for {@code stage} — read straight off the three
     * target sets above, never a second literal. Null for an unknown code.
     *
     * <p>The three sets partition {@link #ORDER} exactly (12 + 2 + 1 = 15), a property
     * {@code DealStageTest} asserts, so every real stage has exactly one gate.
     */
    public static String gateOf(String stage) {
        if (SALES_TARGET_STAGES.contains(stage)) return GATE_SALES;
        if (ACCOUNT_TARGET_STAGES.contains(stage)) return GATE_ACCOUNT;
        if (IMPORT_TARGET_STAGES.contains(stage)) return GATE_IMPORT;
        return null;
    }

    /**
     * Stages the deal normally reaches on its own, from an operational transition rather than a
     * rep picking them out of a list: {@code confirmCustomer} → ORDER_RECEIVED,
     * {@code reconcilePaymentStatus} → DEPOSIT_RECEIVED, {@code issueImportRequest} →
     * PROCUREMENT, {@code maybeAdvanceClosedPaid} → CLOSED_PAID. Read off this class's own
     * "Auto-advanced stages" header, which lists exactly these four.
     *
     * <p>Purely descriptive — it changes no gate. A UI uses it to explain where a stage comes
     * from instead of offering a one-click advance into it; the manual fallback still exists and
     * is still governed by the target sets above (and, for all four of these, by
     * {@code TicketService.requireStageFactsHold}).
     *
     * <p>DELIVERY_SCHEDULING is deliberately absent although {@code recordDelivery} and
     * {@code reserveStock} auto-advance into it: the rep also reaches it by hand as a normal part
     * of the flow, so presenting it as "the system does this for you" would be wrong.
     */
    private static final Set<String> AUTO_ADVANCED = Set.of(
        ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT, CLOSED_PAID);

    public static boolean isAutoAdvanced(String stage) {
        return AUTO_ADVANCED.contains(stage);
    }

    /** The five phases of the pipeline, from this class's own section headers. */
    private static final Map<String, Integer> PHASE = Map.ofEntries(
        Map.entry(LEAD_APPROACH, 1), Map.entry(PRESENTATION, 1),
        Map.entry(SPEC_APPROVED, 2), Map.entry(QUOTE_DESIGN_SIDE, 2),
        Map.entry(QUOTE_OWNER, 2), Map.entry(OWNER_SIGNOFF, 2),
        Map.entry(AWAITING_BUYER, 3), Map.entry(QUOTE_BUYER, 3), Map.entry(NEGOTIATION, 3),
        Map.entry(ORDER_RECEIVED, 4), Map.entry(DEPOSIT_RECEIVED, 4), Map.entry(PROCUREMENT, 4),
        Map.entry(DELIVERY_SCHEDULING, 5), Map.entry(DELIVERED, 5), Map.entry(CLOSED_PAID, 5));

    /** 1-based phase, or 0 for an unknown code. */
    public static int phaseOf(String stage) {
        return PHASE.getOrDefault(stage, 0);
    }

    /**
     * The owner's S1–S20 sheet number for each stage — the business's own identifier, distinct
     * from {@link #displayNoOf}.
     *
     * <p><strong>The two genuinely differ and always will</strong>, which is why both are served
     * rather than one being derived from the other. {@code PROCUREMENT} is a single pipeline
     * stage covering six sheet steps (S12–S17), so the sheet runs to S20 while the pipeline has
     * 15 entries; and V143 inserting {@link #QUOTE_OWNER} moved every later display number by one
     * while changing no sheet number at all. A client that shows "4." next to a stage is showing
     * the display sequence; a client discussing "S12" with the CEO is using this.
     */
    private static final Map<String, String> BUSINESS_CODE = Map.ofEntries(
        Map.entry(LEAD_APPROACH, "S1"), Map.entry(PRESENTATION, "S2"),
        Map.entry(SPEC_APPROVED, "S3"), Map.entry(QUOTE_DESIGN_SIDE, "S4"),
        Map.entry(QUOTE_OWNER, "S5"), Map.entry(OWNER_SIGNOFF, "S6"),
        Map.entry(AWAITING_BUYER, "S7"), Map.entry(QUOTE_BUYER, "S8"),
        Map.entry(NEGOTIATION, "S9"), Map.entry(ORDER_RECEIVED, "S10"),
        Map.entry(DEPOSIT_RECEIVED, "S11"), Map.entry(PROCUREMENT, "S12–S17"),
        Map.entry(DELIVERY_SCHEDULING, "S18"), Map.entry(DELIVERED, "S19"),
        Map.entry(CLOSED_PAID, "S20"));

    public static String businessCodeOf(String stage) {
        return BUSINESS_CODE.get(stage);
    }

    /**
     * The 1-based DISPLAY sequence — what a stepper prints beside a stage. See
     * {@link #BUSINESS_CODE} for why this is not the S-number. 0 for an unknown code.
     */
    public static int displayNoOf(String stage) {
        int idx = indexOf(stage);
        return idx < 0 ? 0 : idx + 1;
    }

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
