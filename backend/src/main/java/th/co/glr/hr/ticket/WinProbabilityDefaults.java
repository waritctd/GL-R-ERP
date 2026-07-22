package th.co.glr.hr.ticket;

import java.util.Map;

/**
 * Default win probability, derived from a deal's current {@link DealStage} when the rep has not
 * set an explicit override (V83 {@code sales.ticket.win_probability}).
 *
 * <p><b>Owner-review assumption (2026-07-22, Slice B1 handoff 103):</b> these percentages are a
 * first-pass mapping chosen by the agent building this slice to give a monotonically
 * non-decreasing curve across the 14-stage pipeline (ties broken by "later phase never scores
 * lower than an earlier one") — they are NOT sourced from real historical win/loss data or an
 * owner sign-off. Treat this exactly like {@code DealActivityKind}'s kind list: usable today,
 * revisit once the owner has looked at actual deal outcomes per stage. Never treat a value here as
 * verified business policy without that review.
 */
public final class WinProbabilityDefaults {
    private static final Map<String, Integer> DEFAULTS = Map.ofEntries(
        Map.entry(DealStage.LEAD_APPROACH, 10),
        Map.entry(DealStage.PRESENTATION, 20),
        Map.entry(DealStage.SPEC_APPROVED, 30),
        Map.entry(DealStage.QUOTE_DESIGN_SIDE, 40),
        Map.entry(DealStage.OWNER_SIGNOFF, 50),
        Map.entry(DealStage.AWAITING_BUYER, 50),
        Map.entry(DealStage.QUOTE_BUYER, 60),
        Map.entry(DealStage.NEGOTIATION, 70),
        Map.entry(DealStage.ORDER_RECEIVED, 90),
        Map.entry(DealStage.DEPOSIT_RECEIVED, 100),
        Map.entry(DealStage.PROCUREMENT, 100),
        Map.entry(DealStage.DELIVERY_SCHEDULING, 100),
        Map.entry(DealStage.DELIVERED, 100),
        Map.entry(DealStage.CLOSED_PAID, 100)
    );

    /** The stage-derived default, or 0 for an unrecognised/null stage (defensive only). */
    public static int defaultFor(String stage) {
        Integer value = DEFAULTS.get(stage);
        return value == null ? 0 : value;
    }

    /** The rep's override when set, else the stage default. Never a blocker on its own. */
    public static int effective(Integer override, String stage) {
        return override != null ? override : defaultFor(stage);
    }

    private WinProbabilityDefaults() {}
}
