package th.co.glr.hr.pricingrequest;

import java.util.Map;
import java.util.Set;

public final class PricingRequestStatus {
    public static final String DRAFT               = "DRAFT";
    public static final String SUBMITTED            = "SUBMITTED";
    public static final String IMPORT_REVIEWING      = "IMPORT_REVIEWING";
    /**
     * Import is working the price with the factory — asking, negotiating, and costing. Displayed
     * throughout as "เจรจาราคากับโรงงาน".
     *
     * <p>V140 (owner ruling 2026-08-11) MERGED the former {@code COSTING_IN_PROGRESS} into this
     * status. Import's workflow is now exactly three user-visible states — รับเรื่อง
     * ({@link #IMPORT_REVIEWING}), เจรจาราคากับโรงงาน (this), and รอ CEO อนุมัติราคา
     * ({@link #READY_FOR_CEO_REVIEW}) — and splitting "waiting for the factory" from "costing what
     * the factory said" described an internal step nobody outside Import could act on. Costing
     * still happens; it simply no longer moves the request to a status of its own.
     *
     * <p>NOTE ON THE NAME: this constant is deliberately NOT renamed. It now covers costing as
     * well as awaiting a reply, so the literal reads narrower than its meaning — but renaming it
     * would rewrite the status string in every historical {@code pricing_request_event} row, and
     * the display label is what users actually read. Renaming is recorded as a follow-up.
     */
    public static final String AWAITING_FACTORY_RESPONSE = "AWAITING_FACTORY_RESPONSE";
    public static final String READY_FOR_CEO_REVIEW = "READY_FOR_CEO_REVIEW";
    // Step 3 (CEO Selling Price Decision): the CEO has explicitly opened a
    // READY_FOR_CEO_REVIEW request (PricingDecisionService.startReview creates a DRAFT
    // pricing_decision and makes this transition). Factory-quote mutations are frozen from here
    // onward (FactoryQuoteService's RESPONSE_STATUSES/MUTABLE_STATUSES/DRAFT_STATUSES
    // deliberately exclude this status) until the request is returned to Import.
    public static final String CEO_REVIEWING         = "CEO_REVIEWING";
    // Terminal for Step 3's purposes: a customer-facing selling price now exists
    // (sales.pricing_decision, status APPROVED). Step 4 (quotation generation) picks up from
    // here; this status itself does not create a quotation or touch the deal stage.
    public static final String APPROVED_FOR_QUOTATION = "APPROVED_FOR_QUOTATION";
    // Step 4 (Customer Quotation Generation and Issuance): the sales rep has issued a customer
    // quotation sourced from the current APPROVED pricing_decision
    // (th.co.glr.hr.customerquotation.CustomerQuotationService.issue). Terminal for Step 4's
    // first cut — a cancelled/superseded quotation does not currently roll this back to
    // APPROVED_FOR_QUOTATION; a correction creates a new quotation revision instead and the
    // pricing request stays QUOTATION_ISSUED throughout (see CustomerQuotationService.issue,
    // which only calls PricingRequestRepository.transition on the FIRST issue).
    public static final String QUOTATION_ISSUED = "QUOTATION_ISSUED";
    // Step 5 (Customer Decision and Commercial Revisions, V75, design correction 2): the customer
    // accepted the issued quotation (th.co.glr.hr.customerquotation.CustomerQuotationService.
    // recordOutcome, outcome=ACCEPTED). Terminal. Deliberately no QUOTATION_REJECTED counterpart
    // — REJECTED lives entirely on quotation.doc_status; Sales decides what happens next (a new
    // revision, or a separate ticket-level lost-deal action outside this step's scope). Same for
    // EXPIRED (sweep-only, never rolls the pricing request back).
    public static final String QUOTATION_ACCEPTED = "QUOTATION_ACCEPTED";
    public static final String CANCELLED             = "CANCELLED";
    public static final String SUPERSEDED            = "SUPERSEDED";

    /**
     * Exactly the set the DB's chk_pricing_request_status constraint accepts (V59+V61+V72,
     * narrowed by V140 which dropped COSTING_IN_PROGRESS and MORE_INFO_REQUIRED, and by V141
     * which dropped COSTING_REVISION_REQUIRED — see V141's header for why: the CEO owns costing
     * now, computed fresh at review time, so there is no more standalone "revise the costing"
     * step for the CEO to send a request back to).
     */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE,
        READY_FOR_CEO_REVIEW, CEO_REVIEWING, APPROVED_FOR_QUOTATION,
        QUOTATION_ISSUED, QUOTATION_ACCEPTED, CANCELLED, SUPERSEDED);

    /**
     * Allowed forward/lateral transitions. DRAFT -> DRAFT is deliberately absent:
     * editing a draft's fields is a mutation guarded by WHERE status = 'DRAFT',
     * not a state transition, so it is never checked against this table.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
        Map.entry(DRAFT,               Set.of(SUBMITTED, CANCELLED)),
        // SUPERSEDED (reissue-through-CEO-chain, owner ruling 2026-08-13): a customer-change
        // revision is legitimate from EVERY non-terminal status, so every one of them now
        // declares the edge. Before this change the map declared SUPERSEDED from only three
        // statuses while PricingRequestRepository.supersedeForCustomerRevision reached the row
        // with a raw `status <> 'SUPERSEDED' AND status <> 'CANCELLED'` UPDATE that never
        // consulted this table at all — so the declared machine and the actual behaviour
        // disagreed in both directions (SUBMITTED/CEO_REVIEWING/APPROVED_FOR_QUOTATION/
        // QUOTATION_ISSUED were reachable but undeclared, and QUOTATION_ACCEPTED was reachable
        // AND forbidden). That method is now routed through the same canTransition assertion as
        // every other transition, which is what makes these entries load-bearing rather than
        // decorative.
        //
        // SUBMITTED -> READY_FOR_CEO_REVIEW is the factory-quote carry-forward edge: when a
        // customer-change revision's items are IDENTICAL to its parent's (same products, same
        // requested quantities — only the commercial terms are being renegotiated), the parent's
        // factory quotes are copied onto the child and there is nothing left for Import to do,
        // so the child skips straight to the CEO. See
        // PricingRequestService#carryFactoryQuotesForwardOnSubmit, which is the ONLY caller and
        // which refuses to make this hop unless LandedCostCalculator.isFullyResolvable agrees the
        // copied quotes can actually be costed.
        Map.entry(SUBMITTED,           Set.of(IMPORT_REVIEWING, READY_FOR_CEO_REVIEW, CANCELLED, SUPERSEDED)),
        // V140: Import's three states, in order. IMPORT_REVIEWING -> AWAITING_FACTORY_RESPONSE
        // -> READY_FOR_CEO_REVIEW. The old COSTING_IN_PROGRESS hop is gone (merged into
        // AWAITING_FACTORY_RESPONSE) and so is MORE_INFO_REQUIRED — the ขอข้อมูลเพิ่มเติม
        // round-trip was removed from the product entirely, since in practice Import and Sales
        // just message each other directly.
        Map.entry(IMPORT_REVIEWING,    Set.of(AWAITING_FACTORY_RESPONSE, CANCELLED, SUPERSEDED)),
        // V141 ("CEO owns costing"): FactoryQuoteService.markReadyForCosting auto-advances a
        // request straight to READY_FOR_CEO_REVIEW the moment every item's factory quote is
        // ready (LandedCostCalculator.isFullyResolvable) — there is no more Import-driven costing
        // draft/submit step in between.
        Map.entry(AWAITING_FACTORY_RESPONSE, Set.of(READY_FOR_CEO_REVIEW, CANCELLED, SUPERSEDED)),
        // V141: TWO ways back to AWAITING_FACTORY_RESPONSE now exist from a request the CEO has
        // not yet started reviewing — one live-system edge (below) and one CEO action (see
        // CEO_REVIEWING's own comment):
        //   - FactoryQuoteService.receive()'s revision branch: a factory sends a revised price
        //     while the request already sits at READY_FOR_CEO_REVIEW. The cost the CEO would be
        //     about to review is computed fresh at review time (there is no submitted-costing
        //     row sitting around to go "stale" any more), so the correct response is to pull the
        //     REQUEST back — Import must re-mark the revised quote ready before the CEO can open
        //     review again.
        // CEO starting review (-> CEO_REVIEWING) and a customer-change revision superseding this
        // request (-> SUPERSEDED) are the other two live exits.
        Map.entry(READY_FOR_CEO_REVIEW, Set.of(CEO_REVIEWING, AWAITING_FACTORY_RESPONSE, SUPERSEDED)),
        // CEO_REVIEWING's two live-user exits: approve (produces a selling price, terminal for
        // Step 3) or return (PricingDecisionService.returnToImport) — which V141 sends straight
        // to AWAITING_FACTORY_RESPONSE, not to a dedicated "revise the costing" status, since
        // Import's only remaining job after a return is to renegotiate/re-mark the factory
        // quote(s) ready; the CEO's next startReview recomputes the cost from scratch.
        Map.entry(CEO_REVIEWING,       Set.of(APPROVED_FOR_QUOTATION, AWAITING_FACTORY_RESPONSE, SUPERSEDED)),
        // Step 4: the ONLY forward exit from APPROVED_FOR_QUOTATION is issuing a customer
        // quotation (CustomerQuotationService.issue). No transition is needed for creating a
        // DRAFT quotation (rule 6: drafts do not move the deal stage OR the pricing request
        // status) — only the first successful issue moves the pricing request on.
        Map.entry(APPROVED_FOR_QUOTATION, Set.of(QUOTATION_ISSUED, SUPERSEDED)),
        // Step 5: the customer's ACCEPTED outcome is the one forward exit from QUOTATION_ISSUED
        // (CustomerQuotationService.recordOutcome). REJECTED/REVISION_REQUESTED/EXPIRED
        // deliberately do NOT transition the pricing request at all — see QUOTATION_ACCEPTED's
        // own Javadoc above for why.
        Map.entry(QUOTATION_ISSUED,    Set.of(QUOTATION_ACCEPTED, SUPERSEDED)),
        // Terminal — and deliberately WITHOUT a SUPERSEDED edge, which is the one place this
        // change REMOVES a capability rather than declaring an existing one. Owner ruling
        // 2026-08-13: once the customer has accepted, the deal is moving to PO and fulfilment;
        // amending it there is an ORDER amendment, not a quotation revision, and it must not go
        // back through the pricing chain. Until now the raw UPDATE in
        // supersedeForCustomerRevision reached this status happily — see
        // InventoryDeliveryFulfilmentIntegrationTest, whose fixtures were built on exactly that
        // and have been rewritten to revise from QUOTATION_ISSUED instead.
        Map.entry(QUOTATION_ACCEPTED,  Set.<String>of()),
        Map.entry(SUPERSEDED,          Set.<String>of()),
        Map.entry(CANCELLED,           Set.<String>of()));

    public static boolean canTransition(String from, String to) {
        return from != null && to != null
            && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isValid(String status) {
        return status != null && VALUES.contains(status);
    }

    private PricingRequestStatus() {}
}
