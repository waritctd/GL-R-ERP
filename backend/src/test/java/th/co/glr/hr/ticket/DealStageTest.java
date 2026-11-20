package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The pure stage decisions: the pipeline ORDER after QUOTE_OWNER was inserted (Part B), and
 * DealStage#requiresJustification (Part C). The Part C tests are written from the owner's four real routes. */
class DealStageTest {

    /** Pinned in full rather than spot-checked. ORDER's positions are mirrored by the V50 CHECK constraint (as
     * widened by V143), the uat seeds, the frontend stage metadata and every historical sales_stage value, so a
     * reordering is a data-compatibility break, not a refactor. */
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

    /** Case A, the full designer → owner → contractor route, hop by hop:
     * S1→S2→S4→S3→S5→S6→S7→S8→S9→S10→S11→S12…S17→S18→S19→S20. Note S4→S3, which is backward in ORDER and is
     * nevertheless the everyday path. */
    @Test
    void caseA_theFullRoute_neverAsksForANote() {
        assertNoNoteAlongPath(List.of(
            DealStage.LEAD_APPROACH, DealStage.PRESENTATION, DealStage.QUOTE_DESIGN_SIDE,
            DealStage.SPEC_APPROVED, DealStage.QUOTE_OWNER, DealStage.OWNER_SIGNOFF,
            DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER, DealStage.NEGOTIATION,
            DealStage.ORDER_RECEIVED, DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT,
            DealStage.DELIVERY_SCHEDULING, DealStage.DELIVERED, DealStage.CLOSED_PAID));
    }

    /** Wrong-way-round half of Part C: the five stages every route has in common. Stepping OVER one of them is a
     * genuine exception and still costs a sentence. */
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

    /** The three write-gate sets partition ORDER exactly. */
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
        // No set may reach outside the pipeline either — a stray code there would be a gate on a stage that cannot
        // exist.
        assertThat(DealStage.SALES_TARGET_STAGES).isSubsetOf(DealStage.ORDER);
        assertThat(DealStage.ACCOUNT_TARGET_STAGES).isSubsetOf(DealStage.ORDER);
        assertThat(DealStage.IMPORT_TARGET_STAGES).isSubsetOf(DealStage.ORDER);
        assertThat(DealStage.gateOf("NOT_A_STAGE")).isNull();
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
