package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Review remediation: PricingRequestStatus#ALLOWED used to be decorative — nothing outside
 * PricingRequestService.cancel's own pre-check consulted PricingRequestStatus#canTransition, so
 * PricingRequestRepository.transition would happily persist a transition the map itself did not list. */
class PricingRequestStatusTest {

    // Step 4 (Customer Quotation Generation and Issuance) deliberately extends this map: APPROVED_FOR_QUOTATION ->
    // QUOTATION_ISSUED is now the ONE forward exit (issuing a customer quotation via
    // CustomerQuotationService.issue).
    @Test
    void approvedForQuotation_toQuotationIssuedCancelledOrSuperseded_isNowAllowed_andNothingElseIs() {
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.APPROVED_FOR_QUOTATION, PricingRequestStatus.QUOTATION_ISSUED)).isTrue();
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.APPROVED_FOR_QUOTATION, PricingRequestStatus.CANCELLED)).isTrue();
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.APPROVED_FOR_QUOTATION, PricingRequestStatus.SUPERSEDED)).isTrue();
        for (String to : PricingRequestStatus.VALUES) {
            if (PricingRequestStatus.QUOTATION_ISSUED.equals(to)) continue;
            if (PricingRequestStatus.CANCELLED.equals(to)) continue;
            if (PricingRequestStatus.SUPERSEDED.equals(to)) continue;
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.APPROVED_FOR_QUOTATION, to)).isFalse();
        }
    }

    /** The one status the reissue change deliberately did NOT give a SUPERSEDED edge, asserted wrong-way-round
     * because it is the capability being REMOVED. */
    @Test
    void quotationAccepted_toSuperseded_isNotAllowed_soAnAcceptedDealCannotBeRevised() {
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.QUOTATION_ACCEPTED, PricingRequestStatus.SUPERSEDED)).isFalse();
    }

    /** The guard itself, wrong-way-round: the cutoff is only worth anything if it REFUSES. */
    @Test
    void cancel_isRefusedOnceAQuotationHasReachedTheCustomer() {
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.QUOTATION_ISSUED, PricingRequestStatus.CANCELLED)).isFalse();
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.QUOTATION_ACCEPTED, PricingRequestStatus.CANCELLED)).isFalse();
    }

    @Test
    void terminalStatuses_allowNoFurtherTransitions() {
        for (String to : PricingRequestStatus.VALUES) {
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.SUPERSEDED, to)).isFalse();
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.CANCELLED, to)).isFalse();
        }
    }

}
