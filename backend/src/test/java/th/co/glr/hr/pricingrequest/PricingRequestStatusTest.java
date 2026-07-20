package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Review remediation (COMMIT 5, P1 finding 1): {@link PricingRequestStatus#ALLOWED} used to be
 * decorative — nothing outside {@code PricingRequestService.cancel()}'s own pre-check consulted
 * {@link PricingRequestStatus#canTransition}, so {@code PricingRequestRepository.transition()}
 * would happily persist a transition the map itself did not list. These tests exercise the pure
 * decision function in isolation (no database); {@code PricingRequestRepositoryIntegrationTest}
 * separately proves {@code transition()} now enforces this map against a real Postgres row.
 */
class PricingRequestStatusTest {

    @Test
    void readyForCeoReview_toCostingInProgress_isNowAllowed() {
        // The new entry this commit adds: PricingCostingService.createDraft() (a fresh costing
        // draft opened against an already-submitted-to-CEO request) and
        // FactoryQuoteService.receive()'s revision branch (a factory revising its quote after CEO
        // review has already started) both legitimately reopen costing from here.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.COSTING_IN_PROGRESS)).isTrue();
    }

    @Test
    void readyForCeoReview_toSuperseded_remainsAllowed() {
        // Pre-existing entry (a customer-change revision superseding the request under CEO
        // review) — must not have been dropped by adding the new entry above.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.SUPERSEDED)).isTrue();
    }

    @Test
    void readyForCeoReview_toCancelled_isStillNotAllowedForANormalCaller() {
        // A live user action may not cancel a request straight out of READY_FOR_CEO_REVIEW — only
        // PricingRequestRepository.cancelForDeadDeal's dead-deal cascade may, and that method
        // deliberately bypasses this map rather than being added to it (see its own Javadoc).
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.CANCELLED)).isFalse();
    }

    @Test
    void draft_toCostingInProgress_isNotAllowed() {
        // Sanity check the map wasn't accidentally widened globally: a DRAFT can never jump
        // straight to COSTING_IN_PROGRESS.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.DRAFT, PricingRequestStatus.COSTING_IN_PROGRESS)).isFalse();
    }

    @Test
    void terminalStatuses_allowNoFurtherTransitions() {
        for (String to : PricingRequestStatus.VALUES) {
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.SUPERSEDED, to)).isFalse();
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.CANCELLED, to)).isFalse();
        }
    }

    @Test
    void nullFromOrTo_isNeverAllowed() {
        assertThat(PricingRequestStatus.canTransition(null, PricingRequestStatus.SUBMITTED)).isFalse();
        assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.DRAFT, null)).isFalse();
        assertThat(PricingRequestStatus.canTransition(null, null)).isFalse();
    }
}
