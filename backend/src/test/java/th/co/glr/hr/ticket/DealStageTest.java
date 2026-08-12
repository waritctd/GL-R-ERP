package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure stage decisions: the pipeline ORDER after {@code QUOTE_OWNER} was inserted (Part B), and
 * {@link DealStage#requiresJustification} (Part C). {@code DealStageRouteIntegrationTest} proves the
 * same rules survive into {@code TicketService.updateStage} against a real row; this class pins the
 * function itself, where every route can be enumerated cheaply.
 *
 * <p>The Part C tests are written from the owner's four real routes. The assertion that matters is
 * the "needs NO note" one: the old rule ({@code indexOf(to) - indexOf(from) > 1}) demanded a written
 * justification for three of those four routes, i.e. friction on the default path.
 */
class DealStageTest {

    // ── Part B: QUOTE_OWNER inserted, nothing else moved ─────────────────────

    /**
     * Pinned in full rather than spot-checked. {@code ORDER}'s positions are mirrored by the V50
     * CHECK constraint (as widened by V143), the uat seeds, the frontend stage metadata and every
     * historical {@code sales_stage} value, so a reordering is a data-compatibility break, not a
     * refactor. QUOTE_OWNER goes between QUOTE_DESIGN_SIDE (S4) and OWNER_SIGNOFF (S6); everything
     * else keeps the index it had.
     */
    @Test
    void orderIsTheFifteenStagePipelineWithQuoteOwnerBetweenS4AndS6() {
        assertThat(DealStage.ORDER).containsExactly(
            "LEAD_APPROACH", "PRESENTATION",
            "SPEC_APPROVED", "QUOTE_DESIGN_SIDE", "QUOTE_OWNER", "OWNER_SIGNOFF",
            "AWAITING_BUYER", "QUOTE_BUYER", "NEGOTIATION",
            "ORDER_RECEIVED", "DEPOSIT_RECEIVED", "PROCUREMENT",
            "DELIVERY_SCHEDULING", "DELIVERED", "CLOSED_PAID");
        assertThat(DealStage.QUOTE_DESIGN_SIDE).isEqualTo("QUOTE_DESIGN_SIDE");
        assertThat(DealStage.isValid(DealStage.QUOTE_OWNER)).isTrue();
        assertThat(DealStage.indexOf(DealStage.QUOTE_OWNER))
            .isEqualTo(DealStage.indexOf(DealStage.QUOTE_DESIGN_SIDE) + 1);
        assertThat(DealStage.indexOf(DealStage.OWNER_SIGNOFF))
            .isEqualTo(DealStage.indexOf(DealStage.QUOTE_OWNER) + 1);
    }

    // ── Part C: the four real routes need no written justification ───────────

    /**
     * Case A, the full designer → owner → contractor route, hop by hop:
     * S1→S2→S4→S3→S5→S6→S7→S8→S9→S10→S11→S12…S17→S18→S19→S20. Note S4→S3, which is backward in
     * {@code ORDER} and is nevertheless the everyday path.
     */
    @Test
    void caseA_theFullRoute_neverAsksForANote() {
        assertNoNoteAlongPath(List.of(
            DealStage.LEAD_APPROACH, DealStage.PRESENTATION, DealStage.QUOTE_DESIGN_SIDE,
            DealStage.SPEC_APPROVED, DealStage.QUOTE_OWNER, DealStage.OWNER_SIGNOFF,
            DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER, DealStage.NEGOTIATION,
            DealStage.ORDER_RECEIVED, DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT,
            DealStage.DELIVERY_SCHEDULING, DealStage.DELIVERED, DealStage.CLOSED_PAID));
    }

    /** Case B — the owner buys directly, so S3, S4, S7 and S8 never happen. */
    @Test
    void caseB_ownerBuysDirect_needsNoNoteForTheStagesTheOwnerReplaces() {
        assertNoNoteAlongPath(List.of(
            DealStage.LEAD_APPROACH, DealStage.PRESENTATION, DealStage.QUOTE_OWNER,
            DealStage.OWNER_SIGNOFF, DealStage.NEGOTIATION, DealStage.ORDER_RECEIVED,
            DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT, DealStage.DELIVERY_SCHEDULING,
            DealStage.DELIVERED, DealStage.CLOSED_PAID));
    }

    /**
     * Case C — a contractor arrives holding a BOQ and a spec, so the deal starts at S8. A ticket is
     * always created at LEAD_APPROACH, so this shows up as one seven-stage jump.
     */
    @Test
    void caseC_contractorArrivesAtS8_needsNoNoteForTheSevenStagesBefore() {
        assertThat(DealStage.requiresJustification(DealStage.LEAD_APPROACH, DealStage.QUOTE_BUYER))
            .isFalse();
        assertNoNoteAlongPath(List.of(
            DealStage.LEAD_APPROACH, DealStage.QUOTE_BUYER, DealStage.NEGOTIATION,
            DealStage.ORDER_RECEIVED, DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT,
            DealStage.DELIVERY_SCHEDULING, DealStage.DELIVERED, DealStage.CLOSED_PAID));
    }

    /** Case D — everything is already in stock, so PROCUREMENT (S12–S17) is skipped entirely. */
    @Test
    void caseD_filledFromStock_needsNoNoteForSkippingProcurement() {
        assertThat(DealStage.requiresJustification(DealStage.DEPOSIT_RECEIVED, DealStage.DELIVERY_SCHEDULING))
            .isFalse();
    }

    /** A deposit that is not required means S11 never happens either. */
    @Test
    void aDealWithNoDeposit_needsNoNoteForSkippingDepositReceived() {
        assertThat(DealStage.requiresJustification(DealStage.ORDER_RECEIVED, DealStage.PROCUREMENT))
            .isFalse();
        assertThat(DealStage.requiresJustification(DealStage.ORDER_RECEIVED, DealStage.DELIVERY_SCHEDULING))
            .isFalse();
    }

    // ── …and the relaxation stops at the mandatory stages ────────────────────

    /**
     * Wrong-way-round half of Part C: the five stages every route has in common. Stepping OVER one
     * of them is a genuine exception and still costs a sentence.
     */
    @Test
    void skippingAMandatoryStage_stillRequiresANote() {
        // over NEGOTIATION (S9)
        assertThat(DealStage.requiresJustification(DealStage.QUOTE_BUYER, DealStage.ORDER_RECEIVED)).isTrue();
        // over ORDER_RECEIVED (S10)
        assertThat(DealStage.requiresJustification(DealStage.NEGOTIATION, DealStage.DEPOSIT_RECEIVED)).isTrue();
        // over DELIVERY_SCHEDULING (S18)
        assertThat(DealStage.requiresJustification(DealStage.PROCUREMENT, DealStage.DELIVERED)).isTrue();
        // over DELIVERED (S19)
        assertThat(DealStage.requiresJustification(DealStage.DELIVERY_SCHEDULING, DealStage.CLOSED_PAID)).isTrue();
        // the whole pipeline at once crosses all five
        assertThat(DealStage.requiresJustification(DealStage.LEAD_APPROACH, DealStage.CLOSED_PAID)).isTrue();
    }

    /** Landing ON a mandatory stage is not skipping it — only stepping over it is. */
    @Test
    void landingOnAMandatoryStageIsNotASkip() {
        assertThat(DealStage.requiresJustification(DealStage.QUOTE_DESIGN_SIDE, DealStage.NEGOTIATION)).isFalse();
        assertThat(DealStage.requiresJustification(DealStage.LEAD_APPROACH, DealStage.NEGOTIATION)).isFalse();
        assertThat(DealStage.requiresJustification(DealStage.QUOTE_OWNER, DealStage.NEGOTIATION)).isFalse();
        // …but one stage further IS a skip, because NEGOTIATION is then stepped over rather than
        // landed on. This is the boundary the rule turns on.
        assertThat(DealStage.requiresJustification(DealStage.QUOTE_OWNER, DealStage.ORDER_RECEIVED)).isTrue();
    }

    // ── backward moves are unchanged ─────────────────────────────────────────

    /**
     * The one allowlisted pair, and nothing else. In particular Part B did NOT widen it: S5 is
     * reached going forward from S3 in the real route, so no {@code QUOTE_OWNER ->} backward move
     * became routine.
     */
    @Test
    void onlyQuoteDesignSideBackToSpecApproved_isARoutineBackwardMove() {
        assertThat(DealStage.requiresJustification(DealStage.QUOTE_DESIGN_SIDE, DealStage.SPEC_APPROVED))
            .isFalse();

        assertThat(DealStage.requiresJustification(DealStage.QUOTE_OWNER, DealStage.SPEC_APPROVED)).isTrue();
        assertThat(DealStage.requiresJustification(DealStage.QUOTE_OWNER, DealStage.QUOTE_DESIGN_SIDE)).isTrue();
        assertThat(DealStage.requiresJustification(DealStage.OWNER_SIGNOFF, DealStage.QUOTE_OWNER)).isTrue();
        assertThat(DealStage.requiresJustification(DealStage.QUOTE_DESIGN_SIDE, DealStage.PRESENTATION)).isTrue();
        assertThat(DealStage.requiresJustification(DealStage.PRESENTATION, DealStage.LEAD_APPROACH)).isTrue();
        assertThat(DealStage.requiresJustification(DealStage.CLOSED_PAID, DealStage.DELIVERED)).isTrue();
    }

    /** Every backward move that is not that one pair needs a reason — checked exhaustively. */
    @Test
    void everyOtherBackwardMoveNeedsAReason() {
        for (int from = 0; from < DealStage.ORDER.size(); from++) {
            for (int to = 0; to < from; to++) {
                String fromStage = DealStage.ORDER.get(from);
                String toStage = DealStage.ORDER.get(to);
                boolean routine = DealStage.QUOTE_DESIGN_SIDE.equals(fromStage)
                    && DealStage.SPEC_APPROVED.equals(toStage);
                assertThat(DealStage.requiresJustification(fromStage, toStage))
                    .as("%s -> %s", fromStage, toStage)
                    .isEqualTo(!routine);
            }
        }
    }

    // ── degenerate inputs ────────────────────────────────────────────────────

    /**
     * {@code updateStage} rejects an unknown code (400) and a no-op move (409) before it ever asks,
     * so the predicate must not second-guess either — it answers false and lets those guards speak.
     */
    @Test
    void unknownCodesAndNoOpMovesAreNotJustifiable() {
        assertThat(DealStage.requiresJustification("NOT_A_STAGE", DealStage.DELIVERED)).isFalse();
        assertThat(DealStage.requiresJustification(DealStage.DELIVERED, "NOT_A_STAGE")).isFalse();
        assertThat(DealStage.requiresJustification(null, DealStage.DELIVERED)).isFalse();
        assertThat(DealStage.requiresJustification(DealStage.DELIVERED, null)).isFalse();
        for (String stage : DealStage.ORDER) {
            assertThat(DealStage.requiresJustification(stage, stage)).as("%s -> itself", stage).isFalse();
        }
    }

    private static void assertNoNoteAlongPath(List<String> path) {
        for (int i = 0; i + 1 < path.size(); i++) {
            assertThat(DealStage.requiresJustification(path.get(i), path.get(i + 1)))
                .as("%s -> %s is a normal route hop and must not demand a note",
                    path.get(i), path.get(i + 1))
                .isFalse();
        }
    }
}
