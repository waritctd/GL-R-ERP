package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pure decision function of the sales.ticket.status machine, in isolation — the same split
 * PaymentTrackTest and PricingRequestStatusTest make: this class proves the TABLE,
 * TicketStatusMachineIntegrationTest proves that the table actually reaches a real row's WHERE clause. */
class TicketStatusTest {

    /** Terminal in both directions: nothing leaves closed or cancelled. */
    private static final Set<String> TERMINAL = Set.of(TicketStatus.CLOSED, TicketStatus.CANCELLED);

    /** The whole point of the change. Each of these was a write the old code performed happily — isValid(toStatus)
     * is true for all of them — from a state no business rule allows. */
    @Test
    void statusJumpsThatSkipTheWorkflow_areRefused() {
        // A brand-new draft cannot be approved, quoted-and-closed, or picked up as if submitted.
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.APPROVED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.CLOSED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.IN_REVIEW)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.PRICE_PROPOSED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.DRAFT, TicketStatus.SUBMITTED)).isFalse();

        // Import cannot approve its own price by writing the status directly, and a submitted deal cannot leapfrog the
        // pickup.
        assertThat(TicketStatus.canTransition(TicketStatus.SUBMITTED, TicketStatus.PRICE_PROPOSED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.SUBMITTED, TicketStatus.APPROVED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.IN_REVIEW, TicketStatus.APPROVED)).isFalse();
        assertThat(TicketStatus.canTransition(TicketStatus.IN_REVIEW, TicketStatus.QUOTATION_ISSUED)).isFalse();

        // A quotation cannot be issued straight out of price_proposed, skipping CEO approval.
        assertThat(TicketStatus.canTransition(TicketStatus.PRICE_PROPOSED, TicketStatus.QUOTATION_ISSUED)).isFalse();

        // …and an issued quotation cannot be walked back to a pricing state. The deposit-notice revision flow starts
        // from approved/document_issued, never from quotation_issued.
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

}
