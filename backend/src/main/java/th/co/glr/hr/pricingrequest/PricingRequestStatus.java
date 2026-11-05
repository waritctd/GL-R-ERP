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
     * <p>This one status covers both "waiting for the factory" and "costing what the factory
     * said". Import's workflow is exactly three user-visible states — รับเรื่อง
     * ({@link #IMPORT_REVIEWING}), เจรจาราคากับโรงงาน (this), and รอ CEO อนุมัติราคา
     * ({@link #READY_FOR_CEO_REVIEW}) — since splitting those two would describe an internal step
     * nobody outside Import could act on. Costing still happens; it does not move the request to
     * a status of its own.
     *
     * <p>NOTE ON THE NAME: the literal reads narrower than its meaning (it covers costing as
     * well as awaiting a reply), but it is deliberately NOT renamed: renaming would rewrite the
     * status string in every historical {@code pricing_request_event} row, and the display label
     * is what users actually read.
     */
    public static final String AWAITING_FACTORY_RESPONSE = "AWAITING_FACTORY_RESPONSE";
    public static final String READY_FOR_CEO_REVIEW = "READY_FOR_CEO_REVIEW";
    // CEO selling-price decision: the CEO has explicitly opened a
    // READY_FOR_CEO_REVIEW request (PricingDecisionService.startReview creates a DRAFT
    // pricing_decision and makes this transition). Factory-quote mutations are frozen from here
    // onward (FactoryQuoteService's RESPONSE_STATUSES/MUTABLE_STATUSES/DRAFT_STATUSES
    // deliberately exclude this status) until the request is returned to Import.
    public static final String CEO_REVIEWING         = "CEO_REVIEWING";
    // Terminal for the price decision: a customer-facing selling price now exists
    // (sales.pricing_decision, status APPROVED). Quotation generation picks up from
    // here; this status itself does not create a quotation or touch the deal stage.
    public static final String APPROVED_FOR_QUOTATION = "APPROVED_FOR_QUOTATION";
    // Customer quotation issuance: the sales rep has issued a customer
    // quotation sourced from the current APPROVED pricing_decision
    // (th.co.glr.hr.customerquotation.CustomerQuotationService.issue). A cancelled/superseded
    // quotation does not roll this back to APPROVED_FOR_QUOTATION; a correction creates a new
    // quotation revision instead and the
    // pricing request stays QUOTATION_ISSUED throughout (see CustomerQuotationService.issue,
    // which only calls PricingRequestRepository.transition on the FIRST issue).
    public static final String QUOTATION_ISSUED = "QUOTATION_ISSUED";
    // Customer decision: the customer accepted the issued quotation (th.co.glr.hr.customerquotation.CustomerQuotationService.
    // recordOutcome, outcome=ACCEPTED). Terminal. Deliberately no QUOTATION_REJECTED counterpart
    // — REJECTED lives entirely on quotation.doc_status; Sales decides what happens next (a new
    // revision, or a separate ticket-level lost-deal action). Same for
    // EXPIRED (sweep-only, never rolls the pricing request back).
    public static final String QUOTATION_ACCEPTED = "QUOTATION_ACCEPTED";
    public static final String CANCELLED             = "CANCELLED";
    public static final String SUPERSEDED            = "SUPERSEDED";

    /**
     * Exactly the set the DB's chk_pricing_request_status constraint accepts. There is no
     * standalone "revise the costing" status: the CEO owns costing, computed fresh at review time.
     */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE,
        READY_FOR_CEO_REVIEW, CEO_REVIEWING, APPROVED_FOR_QUOTATION,
        QUOTATION_ISSUED, QUOTATION_ACCEPTED, CANCELLED, SUPERSEDED);

    /**
     * Allowed forward/lateral transitions. DRAFT -> DRAFT is deliberately absent:
     * editing a draft's fields is a mutation guarded by WHERE status = 'DRAFT',
     * not a state transition, so it is never checked against this table.
     *
     * <p><strong>The CANCELLED cutoff: cancel is allowed up to and
     * including {@link #APPROVED_FOR_QUOTATION}, and refused from {@link #QUOTATION_ISSUED}
     * onward.</strong> A deal that dies while the CEO is looking at it — or after the CEO has
     * approved a price but before anything went to the customer — must be cancellable, or it
     * sits open forever, or the whole ticket has to be killed to reach it (see
     * {@code PricingRequestService#cancelOpenForTicket}).
     *
     * <p>The line is drawn where the work stops being internal. READY_FOR_CEO_REVIEW,
     * CEO_REVIEWING and APPROVED_FOR_QUOTATION are all states in which nothing has left the
     * building: a factory has been asked for a price, a cost has been computed, a selling price
     * has been approved — but the customer has been shown none of it, so abandoning the work is
     * purely an internal bookkeeping act. QUOTATION_ISSUED is different in kind: the customer now
     * holds an offer, and retracting an offer already made is a commercial act, not a cancellation
     * of internal work. The pipeline already has the right tool for replacing an issued
     * quotation ({@link #SUPERSEDED}, via a customer-change revision), and the right tool for a
     * deal that dies outright at that point is killing the DEAL — {@code TicketService.markLost} /
     * {@code cancel}, whose cascade reaches a pricing request in ANY open status through
     * {@code PricingRequestRepository#cancelForDeadDeal}. So refusing here removes no capability;
     * it routes the act through the path that also tells the customer's side of the story.
     *
     * <p>QUOTATION_ACCEPTED stays fully terminal, for the same reason it has no SUPERSEDED edge
     * (see its own Javadoc): once the customer has accepted, the deal is moving to PO and
     * fulfilment, and unwinding it is an ORDER amendment that must not re-enter the pricing chain.
     *
     * <p>⚠️ Widening this set is not free — every status newly admitted here brings its own
     * CHILDREN, and cancelling a parent whose children stay live is worse than not cancelling at
     * all. The statuses READY_FOR_CEO_REVIEW, CEO_REVIEWING and APPROVED_FOR_QUOTATION are
     * exactly the ones that can own a READY_FOR_COSTING factory quote, a SUBMITTED
     * {@code pricing_costing}, a DRAFT/APPROVED {@code pricing_decision}, and a DRAFT
     * {@code quotation}; {@code PricingRequestRepository#cancelOpenChildrenForDeadRequest} and
     * {@code #supersedeOpenPricingDecisionAndQuotation} reach all four. If another status is
     * admitted here, check what it can own first.
     */
    // SUPERSEDED stays terminal here; the one exit is PricingRequestRepository#reviveParentOfCancelledRevision (direct SQL).
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
        Map.entry(DRAFT,               Set.of(SUBMITTED, CANCELLED)),
        // SUPERSEDED (reissue-through-CEO-chain): a customer-change
        // revision is legitimate from EVERY non-terminal status, so every one of them
        // declares the edge. PricingRequestRepository.supersedeForCustomerRevision goes through
        // the same canTransition assertion as every other transition, which is what makes
        // these entries load-bearing rather than decorative.
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
        // Import's three states, in order. IMPORT_REVIEWING -> AWAITING_FACTORY_RESPONSE
        // -> READY_FOR_CEO_REVIEW. There is no ขอข้อมูลเพิ่มเติม round-trip status: Import and
        // Sales just message each other directly.
        // Stock lines: IMPORT_REVIEWING -> READY_FOR_CEO_REVIEW is the edge for a
        // request with NO import line (only IN_TRANSIT / IN_THAILAND lines): there is no factory
        // quote to move it through AWAITING_FACTORY_RESPONSE, so saving the last ETA advances it
        // directly. Taken only by PricingRequestService's readiness predicate.
        Map.entry(IMPORT_REVIEWING,    Set.of(AWAITING_FACTORY_RESPONSE, READY_FOR_CEO_REVIEW, CANCELLED, SUPERSEDED)),
        // The CEO owns costing: FactoryQuoteService.markReadyForCosting auto-advances a
        // request straight to READY_FOR_CEO_REVIEW the moment every item's factory quote is
        // ready (LandedCostCalculator.isFullyResolvable) — there is no more Import-driven costing
        // draft/submit step in between.
        Map.entry(AWAITING_FACTORY_RESPONSE, Set.of(READY_FOR_CEO_REVIEW, CANCELLED, SUPERSEDED)),
        // TWO ways back to AWAITING_FACTORY_RESPONSE exist from a request the CEO has
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
        Map.entry(READY_FOR_CEO_REVIEW, Set.of(CEO_REVIEWING, AWAITING_FACTORY_RESPONSE, CANCELLED, SUPERSEDED)),
        // CEO_REVIEWING's two live-user exits: approve (produces a selling price) or return
        // (PricingDecisionService.returnToImport) — which sends straight
        // to AWAITING_FACTORY_RESPONSE, not to a dedicated "revise the costing" status, since
        // Import's only remaining job after a return is to renegotiate/re-mark the factory
        // quote(s) ready; the CEO's next startReview recomputes the cost from scratch.
        Map.entry(CEO_REVIEWING,       Set.of(APPROVED_FOR_QUOTATION, AWAITING_FACTORY_RESPONSE, CANCELLED, SUPERSEDED)),
        // The ONLY forward exit from APPROVED_FOR_QUOTATION is issuing a customer
        // quotation (CustomerQuotationService.issue). No transition is needed for creating a
        // DRAFT quotation (rule 6: drafts do not move the deal stage OR the pricing request
        // status) — only the first successful issue moves the pricing request on.
        Map.entry(APPROVED_FOR_QUOTATION, Set.of(QUOTATION_ISSUED, CANCELLED, SUPERSEDED)),
        // The customer's ACCEPTED outcome is the one forward exit from QUOTATION_ISSUED
        // (CustomerQuotationService.recordOutcome). REJECTED/REVISION_REQUESTED/EXPIRED
        // deliberately do NOT transition the pricing request at all — see QUOTATION_ACCEPTED's
        // own Javadoc above for why.
        Map.entry(QUOTATION_ISSUED,    Set.of(QUOTATION_ACCEPTED, SUPERSEDED)),
        // Terminal — and deliberately WITHOUT a SUPERSEDED edge: once the customer has
        // accepted, the deal is moving to PO and fulfilment; amending it there is an ORDER
        // amendment, not a quotation revision, and it must not go back through the pricing
        // chain.
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
