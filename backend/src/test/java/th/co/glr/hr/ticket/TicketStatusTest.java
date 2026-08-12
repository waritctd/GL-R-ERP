package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The pure decision function of the {@code sales.ticket.status} machine, in isolation — the same
 * split {@link PaymentTrackTest} and {@code PricingRequestStatusTest} make: this class proves the
 * TABLE, {@code TicketStatusMachineIntegrationTest} proves that the table actually reaches a real
 * row's {@code WHERE} clause.
 *
 * <p><strong>Written wrong-way-round.</strong> The interesting assertions are the ones that say a
 * transition is REFUSED. Before this table existed there was no from-state guard at all:
 * {@code TicketRepository.addEventInternal} wrote the column whenever
 * {@link TicketStatus#isValid(String)} — a membership check — so every "must be false" case below
 * is a write that previously succeeded silently.
 *
 * <p><strong>Behaviour preservation is the headline claim</strong>, so
 * {@link #everyEdgeTheNineWriteSitesPerform_isDeclared()} enumerates the edges the production write
 * sites actually perform today, one row per site, and asserts each is still legal. If a site is
 * ever re-pointed, that test is where it shows up.
 */
class TicketStatusTest {

    /** Terminal in both directions: nothing leaves closed or cancelled. */
    private static final Set<String> TERMINAL = Set.of(TicketStatus.CLOSED, TicketStatus.CANCELLED);

    // ── behaviour preservation: the edges production actually performs ────────

    /**
     * One row per real write site, transcribed from the code rather than from a clean design.
     * The corresponding "which site does what" table lives on {@code TicketStatus.ALLOWED}.
     *
     * <p>{@code TicketRepository.create} contributes no row: it INSERTs {@code draft} directly as
     * the initial state, which is not an edge from anything.
     */
    @Test
    void everyEdgeTheNineWriteSitesPerform_isDeclared() {
        // TicketService.pickup — guarded on status == submitted.
        assertThat(TicketStatus.canTransition(TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW)).isTrue();

        // TicketService.proposePrice — guarded on PROPOSE_ALLOWED_STATUSES = {in_review,
        // price_proposed, approved}; all three land on price_proposed.
        assertThat(TicketStatus.canTransition(TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.PRICE_PROPOSED, TicketStatus.PRICE_PROPOSED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.APPROVED, TicketStatus.PRICE_PROPOSED)).isTrue();

        // TicketService.approve / .reject — both guarded on status == price_proposed.
        assertThat(TicketStatus.canTransition(TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.PRICE_PROPOSED, TicketStatus.IN_REVIEW)).isTrue();

        // TicketService.generateQuotation — QUOTATION_ALLOWED_STATUSES = {approved,
        // quotation_issued}; the second is the amended re-issue self-edge.
        assertThat(TicketStatus.canTransition(TicketStatus.APPROVED, TicketStatus.QUOTATION_ISSUED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.QUOTATION_ISSUED, TicketStatus.QUOTATION_ISSUED)).isTrue();

        // TicketService.verifyClose — requireClosePrerequisites admits exactly these two.
        assertThat(TicketStatus.canTransition(TicketStatus.QUOTATION_ISSUED, TicketStatus.CLOSED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.DOCUMENT_ISSUED, TicketStatus.CLOSED)).isTrue();

        // TicketRepository.markQuotationIssuedForOrderConfirmation — the OrderConfirmationService
        // bridge, hardcoded FROM draft.
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.QUOTATION_ISSUED)).isTrue();

        // DepositNoticeService.requestRevision — guarded on status in {approved, document_issued},
        // three revision scopes. Two of the six edges move the deal BACKWARDS on purpose.
        assertThat(TicketStatus.canTransition(TicketStatus.APPROVED, TicketStatus.APPROVED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.APPROVED, TicketStatus.PRICE_PROPOSED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.APPROVED, TicketStatus.IN_REVIEW)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.DOCUMENT_ISSUED, TicketStatus.APPROVED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.DOCUMENT_ISSUED, TicketStatus.PRICE_PROPOSED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.DOCUMENT_ISSUED, TicketStatus.IN_REVIEW)).isTrue();

        // TicketService.cancel — the only guard is "not already terminal", so every OTHER member of
        // VALUES must be able to reach cancelled, including the dead 'rejected' legacy status.
        for (String from : TicketStatus.VALUES) {
            if (TERMINAL.contains(from)) {
                continue;
            }
            assertThat(TicketStatus.canTransition(from, TicketStatus.CANCELLED))
                .as("cancel() must still work from %s", from)
                .isTrue();
        }
    }

    // ── wrong-way-round: what the machine now refuses ────────────────────────

    /**
     * The whole point of the change. Each of these was a write the old code performed happily —
     * {@code isValid(toStatus)} is true for all of them — from a state no business rule allows.
     */
    @Test
    void statusJumpsThatSkipTheWorkflow_areRefused() {
        // A brand-new draft cannot be approved, quoted-and-closed, or picked up as if submitted.
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.APPROVED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.CLOSED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.IN_REVIEW)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.PRICE_PROPOSED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.SUBMITTED)).isFalse();

        // Import cannot approve its own price by writing the status directly, and a submitted deal
        // cannot leapfrog the pickup.
        assertThat(TicketStatus.canTransition(TicketStatus.SUBMITTED, TicketStatus.PRICE_PROPOSED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.SUBMITTED, TicketStatus.APPROVED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.IN_REVIEW, TicketStatus.APPROVED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.IN_REVIEW, TicketStatus.QUOTATION_ISSUED)).isFalse();

        // A quotation cannot be issued straight out of price_proposed, skipping CEO approval.
        assertThat(TicketStatus.canTransition(TicketStatus.PRICE_PROPOSED, TicketStatus.QUOTATION_ISSUED)).isFalse();

        // …and an issued quotation cannot be walked back to a pricing state. The deposit-notice
        // revision flow starts from approved/document_issued, never from quotation_issued.
        assertThat(TicketStatus.canTransition(TicketStatus.QUOTATION_ISSUED, TicketStatus.APPROVED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.QUOTATION_ISSUED, TicketStatus.PRICE_PROPOSED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.QUOTATION_ISSUED, TicketStatus.IN_REVIEW)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.QUOTATION_ISSUED, TicketStatus.DOCUMENT_ISSUED)).isFalse();

        // approved -> closed skips issuing anything at all.
        assertThat(TicketStatus.canTransition(TicketStatus.APPROVED, TicketStatus.CLOSED)).isFalse();
    }

    /** Both terminals are terminal: no resurrection, not even to each other. */
    @Test
    void nothingLeavesAClosedOrCancelledDeal() {
        for (String terminal : TERMINAL) {
            for (String to : TicketStatus.VALUES) {
                assertThat(TicketStatus.canTransition(terminal, to))
                    .as("%s -> %s must be refused", terminal, to)
                    .isFalse();
            }
        }
    }

    /**
     * {@code rejected} is a dead constant kept only because {@code chk_ticket_status} still lists
     * it (see its own Javadoc). It may appear as a "from" — a legacy row can still be cancelled —
     * but nothing may transition a deal INTO it.
     */
    @Test
    void nothingTransitionsIntoRejected() {
        for (String from : TicketStatus.VALUES) {
            assertThat(TicketStatus.canTransition(from, TicketStatus.REJECTED))
                .as("%s -> rejected must be refused", from)
                .isFalse();
        }
        assertThat(TicketStatus.canTransition(TicketStatus.REJECTED, TicketStatus.CANCELLED)).isTrue();
        assertThat(TicketStatus.canTransition(TicketStatus.REJECTED, TicketStatus.IN_REVIEW)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.REJECTED, TicketStatus.APPROVED)).isFalse();
    }

    /**
     * Only three self-edges exist, and they are the three real repeat actions (re-propose a price,
     * re-issue an amended quotation, a QTY_OR_NOTE revision that stays approved). Every other
     * status re-confirming itself is refused, so a no-op write cannot masquerade as a transition.
     */
    @Test
    void onlyThreeSelfEdgesExist() {
        Set<String> expected = Set.of(
            TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED, TicketStatus.QUOTATION_ISSUED);
        for (String status : TicketStatus.VALUES) {
            assertThat(TicketStatus.canTransition(status, status))
                .as("%s -> %s self-edge", status, status)
                .isEqualTo(expected.contains(status));
        }
    }

    /**
     * The machine can never ask the database for a value {@code chk_ticket_status} would reject.
     * Deal-pipeline events reuse the same from/to slots to carry {@code sales_stage} and lifecycle
     * labels, and those must never be reachable as a transition target.
     */
    @Test
    void aStageOrLifecycleCodeIsNeverAValidTarget() {
        List<String> notStatuses = List.of(
            DealStage.QUOTE_BUYER, DealStage.QUOTE_OWNER, DealStage.NEGOTIATION,
            DealLifecycle.ON_HOLD, DealLifecycle.CLOSED_LOST, "APPROVED", "Draft", "");
        for (String from : TicketStatus.VALUES) {
            for (String bogus : notStatuses) {
                assertThat(TicketStatus.canTransition(from, bogus))
                    .as("%s -> %s must be refused", from, bogus)
                    .isFalse();
                assertThat(TicketStatus.canTransition(bogus, from))
                    .as("%s -> %s must be refused", bogus, from)
                    .isFalse();
            }
        }
    }

    /** Null on either side is false, never a NullPointerException. */
    @Test
    void nullsAreRefusedNotThrown() {
        assertThat(TicketStatus.canTransition(null, TicketStatus.IN_REVIEW)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.SUBMITTED, null)).isFalse();
        assertThat(TicketStatus.canTransition(null, null)).isFalse();
    }

    /**
     * {@code VALUES} must stay exactly the set {@code chk_ticket_status} (V6 as widened by V17)
     * accepts. That identity is what lets the event logger tell a real status from a stage label.
     */
    @Test
    void valuesMirrorsTheCheckConstraint() {
        assertThat(TicketStatus.VALUES).containsExactlyInAnyOrder(
            "draft", "submitted", "in_review", "price_proposed", "approved",
            "rejected", "quotation_issued", "document_issued", "closed", "cancelled");
    }
}
