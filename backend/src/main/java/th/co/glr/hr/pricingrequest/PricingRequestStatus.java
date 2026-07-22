package th.co.glr.hr.pricingrequest;

import java.util.Map;
import java.util.Set;

public final class PricingRequestStatus {
    public static final String DRAFT               = "DRAFT";
    public static final String SUBMITTED            = "SUBMITTED";
    public static final String IMPORT_REVIEWING      = "IMPORT_REVIEWING";
    public static final String AWAITING_FACTORY_RESPONSE = "AWAITING_FACTORY_RESPONSE";
    public static final String COSTING_IN_PROGRESS  = "COSTING_IN_PROGRESS";
    public static final String READY_FOR_CEO_REVIEW = "READY_FOR_CEO_REVIEW";
    // Step 3 (CEO Selling Price Decision): the CEO has explicitly opened a
    // READY_FOR_CEO_REVIEW request (PricingDecisionService.startReview creates a DRAFT
    // pricing_decision and makes this transition). Factory-quote and costing mutations are
    // frozen from here onward (FactoryQuoteService's RESPONSE_STATUSES/MUTABLE_STATUSES/
    // DRAFT_STATUSES and PricingCostingService.COSTING_CREATE_STATUSES all deliberately exclude
    // this status) until the request is returned to Import.
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
    // The CEO returned the request to Import for a new costing version
    // (PricingDecisionService.returnToImport). This is the ONE named "return to Import" state —
    // PricingCostingService.createDraft is reachable from here (not from READY_FOR_CEO_REVIEW,
    // which no longer permits reopening a submitted costing; see COSTING_CREATE_STATUSES) and
    // that createDraft call is what actually moves the request on to COSTING_IN_PROGRESS.
    public static final String COSTING_REVISION_REQUIRED = "COSTING_REVISION_REQUIRED";
    public static final String MORE_INFO_REQUIRED    = "MORE_INFO_REQUIRED";
    public static final String CANCELLED             = "CANCELLED";
    public static final String SUPERSEDED            = "SUPERSEDED";

    /** Exactly the set the DB's chk_pricing_request_status constraint (V59+V61+V72) accepts. */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE, COSTING_IN_PROGRESS,
        READY_FOR_CEO_REVIEW, CEO_REVIEWING, APPROVED_FOR_QUOTATION, COSTING_REVISION_REQUIRED,
        QUOTATION_ISSUED, QUOTATION_ACCEPTED, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED);

    /**
     * Allowed forward/lateral transitions. DRAFT -> DRAFT is deliberately absent:
     * editing a draft's fields is a mutation guarded by WHERE status = 'DRAFT',
     * not a state transition, so it is never checked against this table.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
        Map.entry(DRAFT,               Set.of(SUBMITTED, CANCELLED)),
        Map.entry(SUBMITTED,           Set.of(IMPORT_REVIEWING, CANCELLED)),
        Map.entry(IMPORT_REVIEWING,    Set.of(AWAITING_FACTORY_RESPONSE, COSTING_IN_PROGRESS, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED)),
        Map.entry(AWAITING_FACTORY_RESPONSE, Set.of(COSTING_IN_PROGRESS, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED)),
        Map.entry(COSTING_IN_PROGRESS, Set.of(AWAITING_FACTORY_RESPONSE, READY_FOR_CEO_REVIEW, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED)),
        Map.entry(MORE_INFO_REQUIRED,  Set.of(IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE, COSTING_IN_PROGRESS, CANCELLED)),
        // Step 3 (review remediation, "one return-to-Import path"): a submitted costing must
        // stay genuinely immutable once it reaches READY_FOR_CEO_REVIEW — the previous
        // READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS entry (Costing v2 path, commit 5) let
        // Import silently reopen a SUBMITTED costing without any CEO action, which is exactly
        // what made "submitted costing is immutable" false. That direct edge is removed; the
        // CEO must explicitly start review, then either approve or return it. SUPERSEDED (a
        // customer-change revision superseding a request under CEO review) is preserved.
        Map.entry(READY_FOR_CEO_REVIEW, Set.of(CEO_REVIEWING, SUPERSEDED)),
        // CEO_REVIEWING's two live-user exits: approve (produces a selling price, terminal for
        // Step 3) or return (the one named state PricingCostingService.createDraft reopens
        // costing from — see COSTING_REVISION_REQUIRED below).
        Map.entry(CEO_REVIEWING,       Set.of(APPROVED_FOR_QUOTATION, COSTING_REVISION_REQUIRED)),
        // The single reopen path: Import calls PricingCostingService.createDraft, which (now
        // that COSTING_REVISION_REQUIRED — not READY_FOR_CEO_REVIEW — is in
        // COSTING_CREATE_STATUSES) transitions here to COSTING_IN_PROGRESS, and
        // PricingCostingService.submit() carries it back to READY_FOR_CEO_REVIEW as before.
        Map.entry(COSTING_REVISION_REQUIRED, Set.of(COSTING_IN_PROGRESS)),
        // Step 4: the ONLY forward exit from APPROVED_FOR_QUOTATION is issuing a customer
        // quotation (CustomerQuotationService.issue). No transition is needed for creating a
        // DRAFT quotation (rule 6: drafts do not move the deal stage OR the pricing request
        // status) — only the first successful issue moves the pricing request on.
        Map.entry(APPROVED_FOR_QUOTATION, Set.of(QUOTATION_ISSUED)),
        // Step 5: the customer's ACCEPTED outcome is the one forward exit from QUOTATION_ISSUED
        // (CustomerQuotationService.recordOutcome). REJECTED/REVISION_REQUESTED/EXPIRED
        // deliberately do NOT transition the pricing request at all — see QUOTATION_ACCEPTED's
        // own Javadoc above for why.
        Map.entry(QUOTATION_ISSUED,    Set.of(QUOTATION_ACCEPTED)),
        // Terminal.
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
