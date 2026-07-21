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
    void readyForCeoReview_toCostingInProgress_isNoLongerAllowed() {
        // Step 3 (CEO Selling Price Decision, "one return-to-Import path"): the direct
        // READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS entry (added by the commit this test used to
        // document) let Import silently reopen a SUBMITTED costing without any CEO action, which
        // made "submitted costing is immutable" false. It is removed; the only way back to
        // COSTING_IN_PROGRESS from a submitted-to-CEO request is now
        // CEO_REVIEWING -> COSTING_REVISION_REQUIRED -> COSTING_IN_PROGRESS (the CEO must
        // explicitly return it) — see the next two tests.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.COSTING_IN_PROGRESS)).isFalse();
    }

    @Test
    void readyForCeoReview_toCeoReviewing_isAllowed() {
        // The CEO explicitly starting review (PricingDecisionService.startReview) — the one entry
        // point into Step 3.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.CEO_REVIEWING)).isTrue();
    }

    @Test
    void ceoReviewing_toApprovedForQuotationOrCostingRevisionRequired_isAllowed() {
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.APPROVED_FOR_QUOTATION)).isTrue();
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.COSTING_REVISION_REQUIRED)).isTrue();
    }

    @Test
    void costingRevisionRequired_toCostingInProgress_isAllowed() {
        // The single named return-to-Import state (design correction 4) — Import calling
        // PricingCostingService.createDraft is what actually performs this transition.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.COSTING_REVISION_REQUIRED, PricingRequestStatus.COSTING_IN_PROGRESS)).isTrue();
    }

    // Step 4 (Customer Quotation Generation and Issuance) deliberately extends this map:
    // APPROVED_FOR_QUOTATION -> QUOTATION_ISSUED is now the ONE forward exit (issuing a
    // customer quotation via CustomerQuotationService.issue). This test used to assert
    // APPROVED_FOR_QUOTATION was terminal (true for Step 3's own scope, which explicitly
    // deferred Step 4) — renamed and inverted to assert the new, intentional edge instead of
    // the old absence, per this repo's own precedent for a map change (see
    // PricingRequestStatusTest's Step 3 predecessor commit and
    // docs/agent-handoffs/92_feat-sales-ceo-pricing-decision.md's "isNoLongerAllowed" rename).
    @Test
    void approvedForQuotation_toQuotationIssued_isNowAllowed_andNothingElseIs() {
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.APPROVED_FOR_QUOTATION, PricingRequestStatus.QUOTATION_ISSUED)).isTrue();
        for (String to : PricingRequestStatus.VALUES) {
            if (PricingRequestStatus.QUOTATION_ISSUED.equals(to)) continue;
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.APPROVED_FOR_QUOTATION, to)).isFalse();
        }
    }

    @Test
    void quotationIssued_isTerminalForStep4() {
        for (String to : PricingRequestStatus.VALUES) {
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.QUOTATION_ISSUED, to)).isFalse();
        }
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
