package th.co.glr.hr.ticket;

import java.util.Map;

/**
 * Default win probability, derived from a deal's current {@link DealStage} when the rep has not
 * set an explicit override (V83 {@code sales.ticket.win_probability}).
 *
 * <p><b>Owner-review assumption (2026-07-22, Slice B1 handoff 103):</b> these percentages are a
 * first-pass mapping chosen by the agent building this slice to give a monotonically
 * non-decreasing curve across the 15-stage pipeline (ties broken by "later phase never scores
 * lower than an earlier one") — they are NOT sourced from real historical win/loss data or an
 * owner sign-off. Treat this exactly like {@code DealActivityKind}'s kind list: usable today,
 * revisit once the owner has looked at actual deal outcomes per stage. Never treat a value here as
 * verified business policy without that review.
 *
 * <p><b>The one exception is {@link DealStage#QUOTE_OWNER} = 40, which IS an owner ruling</b>
 * (2026-08-13): quoting the owner is the same act as quoting the designer, only the recipient
 * differs, and nothing has been confirmed either way — so the odds have not moved off
 * {@link DealStage#QUOTE_DESIGN_SIDE}'s 40. Deliberately NOT nudged to 45 to keep the curve
 * strictly increasing; the table already has flat steps (OWNER_SIGNOFF and AWAITING_BUYER are both
 * 50, PROCUREMENT through CLOSED_PAID are all 100) and inventing a difference the business does not
 * see would be worse than the tie. Every other value in the table remains unratified.
 *
 * <p><b>Adding a stage means adding a row here.</b> V143 inserted QUOTE_OWNER into
 * {@link DealStage#ORDER} and this map was not updated, so {@code defaultFor("QUOTE_OWNER")}
 * returned the 0 sentinel: every deal at S5 displayed 0% and contributed nothing to the
 * win-weighted pipeline forecast, silently, because a missing key is indistinguishable from a
 * deliberate zero at every call site. {@code WinProbabilityDefaultsTest} now fails when a stage in
 * {@code ORDER} has no entry, so the next insertion is loud instead.
 */
public final class WinProbabilityDefaults {
    private static final Map<String, Integer> DEFAULTS = Map.ofEntries(
        Map.entry(DealStage.LEAD_APPROACH, 10),
        Map.entry(DealStage.PRESENTATION, 20),
        Map.entry(DealStage.SPEC_APPROVED, 30),
        Map.entry(DealStage.QUOTE_DESIGN_SIDE, 40),
        Map.entry(DealStage.QUOTE_OWNER, 40),
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

    /**
     * The stage-derived default, or 0 for an unrecognised stage.
     *
     * <p>The 0 is a sentinel, not a probability — it is what a caller sees when this table is
     * missing a stage, which is the QUOTE_OWNER defect described above. No real stage answers 0.
     *
     * <p><b>A null stage throws NPE, it does not answer 0.</b> This Javadoc claimed "unrecognised/
     * null" until 2026-08-13 and was wrong: {@code DEFAULTS} is a {@code Map.ofEntries()}, whose
     * {@code get} rejects a null probe rather than missing on it — the same JDK immutable-collection
     * trap {@code DealStage.requiresJustification} already null-checks around for
     * {@code List.of().indexOf}. Left as-is rather than widened: {@code sales_stage} is NOT NULL
     * since V50 and the only caller is {@link #effective} via {@code TicketSummaryDto}, so the null
     * path is unreachable, and inventing a defensive branch for it would be dead code with a
     * misleading contract. The doc now says what the method does.
     */
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
