package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
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

    // ── Part D: the catalog served to clients ────────────────────────────────
    //
    // Every assertion below covers a field the frontend used to declare for itself in
    // features/tickets/stageMeta.js. That copy silently stopped at fourteen stages when V143 added
    // QUOTE_OWNER, so these are not decoration: they are the guard the deleted copy never had.

    /**
     * The three write-gate sets partition {@code ORDER} exactly. This is what makes
     * {@link DealStage#gateOf} total and unambiguous, and it is also a live authz property:
     * {@code TicketService.requireStageWriteAccess} runs an if/else-if chain over these sets and
     * falls through to 403, so a stage in none of them would be unreachable by every role except
     * ceo, and a stage in two would silently take the first branch's gate.
     */
    @Test
    void theThreeWriteGateSetsPartitionThePipelineExactly() {
        assertThat(DealStage.SALES_TARGET_STAGES).hasSize(12);
        assertThat(DealStage.ACCOUNT_TARGET_STAGES)
            .containsExactlyInAnyOrder(DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID);
        assertThat(DealStage.IMPORT_TARGET_STAGES).containsExactly(DealStage.PROCUREMENT);

        for (String stage : DealStage.ORDER) {
            long memberships = Stream.of(DealStage.SALES_TARGET_STAGES,
                    DealStage.ACCOUNT_TARGET_STAGES, DealStage.IMPORT_TARGET_STAGES)
                .filter(set -> set.contains(stage))
                .count();
            assertThat(memberships).as("%s must belong to exactly one gate set", stage).isEqualTo(1);
            assertThat(DealStage.gateOf(stage)).as("gate of %s", stage)
                .isIn(DealStage.GATE_SALES, DealStage.GATE_ACCOUNT, DealStage.GATE_IMPORT);
        }
        // No set may reach outside the pipeline either — a stray code there would be a gate on a
        // stage that cannot exist.
        assertThat(DealStage.SALES_TARGET_STAGES).isSubsetOf(DealStage.ORDER);
        assertThat(DealStage.ACCOUNT_TARGET_STAGES).isSubsetOf(DealStage.ORDER);
        assertThat(DealStage.IMPORT_TARGET_STAGES).isSubsetOf(DealStage.ORDER);
        assertThat(DealStage.gateOf("NOT_A_STAGE")).isNull();
    }

    /** Every stage has a phase, phases run 1–5, and they never go backwards along {@code ORDER}. */
    @Test
    void everyStageHasAPhaseAndPhasesAreMonotonicAlongTheOrder() {
        int previous = 0;
        for (String stage : DealStage.ORDER) {
            int phase = DealStage.phaseOf(stage);
            assertThat(phase).as("phase of %s", stage).isBetween(1, 5);
            assertThat(phase).as("phase of %s must not go backwards", stage).isGreaterThanOrEqualTo(previous);
            previous = phase;
        }
        assertThat(DealStage.ORDER.stream().map(DealStage::phaseOf).distinct().toList())
            .containsExactly(1, 2, 3, 4, 5);
        assertThat(DealStage.phaseOf("NOT_A_STAGE")).isZero();
    }

    /**
     * The display sequence and the owner's S-sheet number are different things, and this pins
     * exactly how. Both are served because neither can be computed from the other: PROCUREMENT is
     * one pipeline stage spanning six sheet steps, and V143 shifted the display numbers after S4
     * while leaving every sheet number alone.
     */
    @Test
    void displayNumberAndBusinessCodeAreBothTotalAndDeliberatelyDifferent() {
        for (int i = 0; i < DealStage.ORDER.size(); i++) {
            String stage = DealStage.ORDER.get(i);
            assertThat(DealStage.displayNoOf(stage)).as("display no of %s", stage).isEqualTo(i + 1);
            assertThat(DealStage.businessCodeOf(stage)).as("business code of %s", stage).isNotNull();
        }
        assertThat(DealStage.ORDER.stream().map(DealStage::businessCodeOf).distinct())
            .as("no two stages may share a sheet number").hasSize(DealStage.ORDER.size());

        // The divergence itself, stated: S5 is the 5th stage, but S6 is the 6th and S20 is the 15th.
        assertThat(DealStage.displayNoOf(DealStage.QUOTE_OWNER)).isEqualTo(5);
        assertThat(DealStage.businessCodeOf(DealStage.QUOTE_OWNER)).isEqualTo("S5");
        assertThat(DealStage.displayNoOf(DealStage.CLOSED_PAID)).isEqualTo(15);
        assertThat(DealStage.businessCodeOf(DealStage.CLOSED_PAID)).isEqualTo("S20");
        assertThat(DealStage.businessCodeOf(DealStage.PROCUREMENT)).isEqualTo("S12–S17");

        assertThat(DealStage.displayNoOf("NOT_A_STAGE")).isZero();
        assertThat(DealStage.businessCodeOf("NOT_A_STAGE")).isNull();
    }

    /**
     * The four auto-advanced stages are exactly the four with an {@code autoAdvanceStage} call
     * site keyed to them, and exactly the four {@code TicketService.requireStageFactsHold} gates —
     * a stage the system normally reaches for you is a stage whose fact must already hold.
     * DELIVERY_SCHEDULING is auto-advanced too but is deliberately absent: the rep also reaches it
     * by hand as part of the normal flow.
     */
    @Test
    void autoAdvancedIsExactlyTheFourFactGatedStages() {
        assertThat(DealStage.ORDER.stream().filter(DealStage::isAutoAdvanced).toList())
            .containsExactly(DealStage.ORDER_RECEIVED, DealStage.DEPOSIT_RECEIVED,
                DealStage.PROCUREMENT, DealStage.CLOSED_PAID);
        assertThat(DealStage.isAutoAdvanced(DealStage.DELIVERY_SCHEDULING)).isFalse();
        assertThat(DealStage.isAutoAdvanced("NOT_A_STAGE")).isFalse();
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
