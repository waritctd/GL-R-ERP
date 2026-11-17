package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The stage → win% table, and above all the guard that it covers every stage. V143 inserted
 * DealStage#QUOTE_OWNER (S5) into DealStage#ORDER and WinProbabilityDefaults was not updated with it. The
 * value tests below it are ordinary pinning; that one is the thing that makes the next stage insertion loud. */
class WinProbabilityDefaultsTest {

    /** THE COMPLETENESS GUARD. Every code in DealStage#ORDER must resolve to a real default. That also means a
     * future Map.entry(SOMETHING, 0) would fail here — correctly. */
    @Test
    void everyStageInThePipelineHasADefault() {
        for (String stage : DealStage.ORDER) {
            assertThat(WinProbabilityDefaults.defaultFor(stage))
                .as("%s is in DealStage.ORDER but has no WinProbabilityDefaults entry — a deal on "
                    + "this stage would report 0%% and drop out of the win-weighted forecast", stage)
                .isBetween(1, 100);
        }
        // Non-vacuous: if ORDER were ever emptied or renamed out from under this loop it would pass trivially, which
        // is the failure mode the loop exists to prevent.
        assertThat(DealStage.ORDER).hasSizeGreaterThanOrEqualTo(15).contains(DealStage.QUOTE_OWNER);
        // …and 0 is still the sentinel, so the assertion above is really testing membership.
        assertThat(WinProbabilityDefaults.defaultFor("NOT_A_STAGE")).isZero();
    }

    /** The null probe throws — pinned because the Javadoc used to promise it answered 0, and writing this class is
     * how that was found. If a future caller can pass null, this test is where that decision gets made
     * deliberately instead of by NPE in production. */
    @Test
    void aNullStageThrowsRatherThanAnsweringZero() {
        assertThatThrownBy(() -> WinProbabilityDefaults.defaultFor(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WinProbabilityDefaults.effective(null, null))
            .isInstanceOf(NullPointerException.class);
    }

    /** S5's value, and the owner's reasoning for it (2026-08-13): quoting the project owner is the same act as
     * quoting the designer — the recipient differs, nothing has been confirmed, so the odds have not moved. */
    @Test
    void quoteOwnerScoresTheSameAsQuoteDesignSide() {
        assertThat(WinProbabilityDefaults.defaultFor(DealStage.QUOTE_OWNER)).isEqualTo(40);
        assertThat(WinProbabilityDefaults.defaultFor(DealStage.QUOTE_OWNER))
            .isEqualTo(WinProbabilityDefaults.defaultFor(DealStage.QUOTE_DESIGN_SIDE));
        // The step after it still rises — S5 sits flat against S4, not against S6.
        assertThat(WinProbabilityDefaults.defaultFor(DealStage.OWNER_SIGNOFF)).isEqualTo(50);
    }

    /** The class Javadoc's own claim about the table's shape: non-decreasing along the pipeline, a later stage
     * never scoring lower than an earlier one. */
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

    /** effective prefers the rep's override, including an override that happens to equal 0 — "I know this one is
     * dead" is a real answer and must not be re-read as "no entry". */
    @Test
    void anExplicitOverrideBeatsTheStageDefault() {
        assertThat(WinProbabilityDefaults.effective(75, DealStage.LEAD_APPROACH)).isEqualTo(75);
        assertThat(WinProbabilityDefaults.effective(0, DealStage.NEGOTIATION)).isZero();
        assertThat(WinProbabilityDefaults.effective(null, DealStage.NEGOTIATION)).isEqualTo(70);
        assertThat(WinProbabilityDefaults.effective(null, "NOT_A_STAGE")).isZero();
    }
}
