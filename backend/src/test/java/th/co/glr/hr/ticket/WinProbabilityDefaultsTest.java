package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The stage → win% table, and above all the guard that it covers every stage.
 *
 * <p>V143 inserted {@link DealStage#QUOTE_OWNER} (S5) into {@link DealStage#ORDER} and
 * {@link WinProbabilityDefaults} was not updated with it. Nothing failed: {@code defaultFor}
 * answers 0 for a key it does not hold, so every deal sitting at S5 reported a 0% chance and
 * contributed exactly nothing to the win-weighted pipeline forecast — a wrong number rather than a
 * visible error, on a screen whose whole purpose is that number. The same omission is the shape
 * {@code stageCatalog.test.js} was written for on the frontend after #713.
 *
 * <p>{@link #everyStageInThePipelineHasADefault} is why this class exists. The value tests below it
 * are ordinary pinning; that one is the thing that makes the next stage insertion loud.
 */
class WinProbabilityDefaultsTest {

    /**
     * THE COMPLETENESS GUARD. Every code in {@link DealStage#ORDER} must resolve to a real default.
     *
     * <p>Read through {@code defaultFor} rather than the private map, because that is the only
     * thing any caller can see, and it is exactly where the defect was invisible: 0 is the
     * documented sentinel for "no entry", so "has an entry" and "answers non-zero" are the same
     * question at every call site. That also means a future {@code Map.entry(SOMETHING, 0)} would
     * fail here — correctly. A stage the business believes has a genuine 0% chance is not a stage a
     * deal can sit on; it is a lost deal, which {@code lifecycle = CLOSED_LOST} already says, so a
     * zero in this table would be indistinguishable from the bug and must be argued for, not slipped in.
     */
    @Test
    void everyStageInThePipelineHasADefault() {
        for (String stage : DealStage.ORDER) {
            assertThat(WinProbabilityDefaults.defaultFor(stage))
                .as("%s is in DealStage.ORDER but has no WinProbabilityDefaults entry — a deal on "
                    + "this stage would report 0%% and drop out of the win-weighted forecast", stage)
                .isBetween(1, 100);
        }
        // Non-vacuous: if ORDER were ever emptied or renamed out from under this loop it would pass
        // trivially, which is the failure mode the loop exists to prevent. A lower bound rather than
        // an exact 15 on purpose — a legitimate 16th stage must fail on the loop above, naming the
        // stage that has no entry, not here on a count that says nothing about the defect.
        assertThat(DealStage.ORDER).hasSizeGreaterThanOrEqualTo(15).contains(DealStage.QUOTE_OWNER);
        // …and 0 is still the sentinel, so the assertion above is really testing membership.
        assertThat(WinProbabilityDefaults.defaultFor("NOT_A_STAGE")).isZero();
    }

    /**
     * The null probe throws — pinned because the Javadoc used to promise it answered 0, and
     * writing this class is how that was found. {@code DEFAULTS} is a {@code Map.ofEntries()} and
     * the JDK's immutable map rejects a null key rather than missing on it, the same trap
     * {@code DealStage.requiresJustification} null-checks around before calling
     * {@code List.of().indexOf}.
     *
     * <p>Asserting the real behaviour rather than fixing it: {@code sales_stage} is NOT NULL since
     * V50 and the only production caller is {@code TicketSummaryDto.effectiveWinProbability}, so
     * nothing can reach this. If a future caller can pass null, this test is where that decision
     * gets made deliberately instead of by NPE in production.
     */
    @Test
    void aNullStageThrowsRatherThanAnsweringZero() {
        assertThatThrownBy(() -> WinProbabilityDefaults.defaultFor(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WinProbabilityDefaults.effective(null, null))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * S5's value, and the owner's reasoning for it (2026-08-13): quoting the project owner is the
     * same act as quoting the designer — the recipient differs, nothing has been confirmed, so the
     * odds have not moved. Pinned as an equality with S4 rather than as a bare 40 so the intent
     * survives a future re-rating of the pre-quote stages: whatever S4 becomes, S5 follows it until
     * the owner says otherwise.
     */
    @Test
    void quoteOwnerScoresTheSameAsQuoteDesignSide() {
        assertThat(WinProbabilityDefaults.defaultFor(DealStage.QUOTE_OWNER)).isEqualTo(40);
        assertThat(WinProbabilityDefaults.defaultFor(DealStage.QUOTE_OWNER))
            .isEqualTo(WinProbabilityDefaults.defaultFor(DealStage.QUOTE_DESIGN_SIDE));
        // The step after it still rises — S5 sits flat against S4, not against S6.
        assertThat(WinProbabilityDefaults.defaultFor(DealStage.OWNER_SIGNOFF)).isEqualTo(50);
    }

    /**
     * The class Javadoc's own claim about the table's shape: non-decreasing along the pipeline, a
     * later stage never scoring lower than an earlier one. Ties are expected and allowed (S4/S5,
     * S6/S7, and the four terminal stages at 100) — this asserts the curve never goes backwards,
     * not that it strictly rises.
     */
    @Test
    void theCurveNeverGoesBackwardsAlongThePipeline() {
        int previous = 0;
        for (String stage : DealStage.ORDER) {
            int probability = WinProbabilityDefaults.defaultFor(stage);
            assertThat(probability)
                .as("%s scores lower than the stage before it", stage)
                .isGreaterThanOrEqualTo(previous);
            previous = probability;
        }
        assertThat(WinProbabilityDefaults.defaultFor(DealStage.CLOSED_PAID)).isEqualTo(100);
    }

    /**
     * {@code effective} prefers the rep's override, including an override that happens to equal 0 —
     * "I know this one is dead" is a real answer and must not be re-read as "no entry".
     */
    @Test
    void anExplicitOverrideBeatsTheStageDefault() {
        assertThat(WinProbabilityDefaults.effective(75, DealStage.LEAD_APPROACH)).isEqualTo(75);
        assertThat(WinProbabilityDefaults.effective(0, DealStage.NEGOTIATION)).isZero();
        assertThat(WinProbabilityDefaults.effective(null, DealStage.NEGOTIATION)).isEqualTo(70);
        assertThat(WinProbabilityDefaults.effective(null, "NOT_A_STAGE")).isZero();
    }
}
