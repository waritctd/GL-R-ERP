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
    void readyForCeoReview_toAwaitingFactoryResponse_isAllowedAgain_forADifferentReason() {
        // V140-era history: the direct READY_FOR_CEO_REVIEW -> AWAITING_FACTORY_RESPONSE (then
        // named "...toCostingInProgress...") entry let Import silently reopen a SUBMITTED costing
        // without any CEO action, so it was removed ("submitted costing is immutable"). V141
        // ("CEO owns costing") reintroduces the SAME edge for a DIFFERENT reason: there is no
        // submitted-costing row sitting around any more for a factory-quote revision to mark
        // "stale" (markOpenCostingsStale, deleted) — the cost is computed fresh, once, at
        // CEO-review time. So FactoryQuoteService.receive()'s revision branch now pulls the
        // REQUEST back here instead when a revision arrives while READY_FOR_CEO_REVIEW: the price
        // the CEO would be about to review is about to change under them. Import must re-mark the
        // revised quote ready to re-advance (FactoryQuoteService.markReadyForCosting).
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.AWAITING_FACTORY_RESPONSE)).isTrue();
    }

    @Test
    void readyForCeoReview_toCeoReviewing_isAllowed() {
        // The CEO explicitly starting review (PricingDecisionService.startReview) — the one entry
        // point into Step 3.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.READY_FOR_CEO_REVIEW, PricingRequestStatus.CEO_REVIEWING)).isTrue();
    }

    @Test
    void ceoReviewing_toApprovedForQuotationOrAwaitingFactoryResponse_isAllowed() {
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.APPROVED_FOR_QUOTATION)).isTrue();
        // V141 ("CEO owns costing"): PricingDecisionService.returnToImport now sends a returned
        // request straight to AWAITING_FACTORY_RESPONSE — the replacement for the retired
        // COSTING_REVISION_REQUIRED (see the next test). There is no standalone costing draft any
        // more for Import to revise; its only remaining job is to renegotiate/re-mark a factory
        // quote ready, and the CEO's next startReview recomputes the cost from scratch.
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.CEO_REVIEWING, PricingRequestStatus.AWAITING_FACTORY_RESPONSE)).isTrue();
    }

    @Test
    void costingRevisionRequired_statusNoLongerExists() {
        // V141 ("CEO owns costing"): COSTING_REVISION_REQUIRED is RETIRED — a submitted costing
        // used to be "revised" by sending the pricing request back to Import for a brand-new
        // DRAFT/CALCULATED/SUBMITTED cycle; now there is no draft cycle to send it back TO (see
        // the two tests above for what replaced both of its edges). The constant itself is
        // deleted from PricingRequestStatus, so this test uses the literal string — the DB's own
        // chk_pricing_request_status CHECK constraint was narrowed the same way by V141.
        assertThat(PricingRequestStatus.VALUES).doesNotContain("COSTING_REVISION_REQUIRED");
        assertThat(PricingRequestStatus.isValid("COSTING_REVISION_REQUIRED")).isFalse();
        // A status that no longer exists can transition neither from nor to anywhere.
        for (String to : PricingRequestStatus.VALUES) {
            assertThat(PricingRequestStatus.canTransition("COSTING_REVISION_REQUIRED", to)).isFalse();
        }
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.CEO_REVIEWING, "COSTING_REVISION_REQUIRED")).isFalse();
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

    // Step 5 (Customer Decision and Commercial Revisions) deliberately extends this map again:
    // QUOTATION_ISSUED -> QUOTATION_ACCEPTED is now the ONE forward exit (recordOutcome's
    // ACCEPTED path). This test used to assert QUOTATION_ISSUED was terminal (true for Step 4's
    // own scope) — renamed and inverted to assert the new, intentional edge instead of the old
    // absence, per this repo's own precedent for a map change (see the
    // approvedForQuotation_toQuotationIssued_... rename above and
    // docs/agent-handoffs/92_feat-sales-ceo-pricing-decision.md's "isNoLongerAllowed" rename).
    @Test
    void quotationIssued_toQuotationAccepted_isNowAllowed_andNothingElseIs() {
        assertThat(PricingRequestStatus.canTransition(
            PricingRequestStatus.QUOTATION_ISSUED, PricingRequestStatus.QUOTATION_ACCEPTED)).isTrue();
        for (String to : PricingRequestStatus.VALUES) {
            if (PricingRequestStatus.QUOTATION_ACCEPTED.equals(to)) continue;
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.QUOTATION_ISSUED, to)).isFalse();
        }
    }

    @Test
    void quotationAccepted_isTerminal() {
        for (String to : PricingRequestStatus.VALUES) {
            assertThat(PricingRequestStatus.canTransition(PricingRequestStatus.QUOTATION_ACCEPTED, to)).isFalse();
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
            PricingRequestStatus.DRAFT, PricingRequestStatus.AWAITING_FACTORY_RESPONSE)).isFalse();
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
