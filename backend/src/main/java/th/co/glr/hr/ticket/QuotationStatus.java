package th.co.glr.hr.ticket;

public final class QuotationStatus {
    private QuotationStatus() {}

    public static final String DRAFT = "DRAFT";
    public static final String ISSUED = "ISSUED";
    public static final String SENT = "SENT";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SUPERSEDED = "SUPERSEDED";
    // Step 4 (Customer Quotation Generation and Issuance, V74): a draft ready for the sales
    // rep to issue (all discount/price validation already passed on the last recalculation).
    // Distinct from DRAFT so the frontend can gate the "Issue" button on a fresh-enough state.
    public static final String READY_TO_ISSUE = "READY_TO_ISSUE";
    // Declared now (Step 5 completes the customer-response lifecycle) so the V74 CHECK
    // constraint widening does not need a second migration later.
    public static final String REVISION_REQUESTED = "REVISION_REQUESTED";
    // Quotation v2 (direct deal quotation, V165): a submitted draft awaiting sales_manager/ceo
    // approval. See th.co.glr.hr.dealquotation.DealQuotationService for the full status machine
    // (DRAFT -> PENDING_APPROVAL -> APPROVED | back to DRAFT on reject).
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    // Quotation v2: terminal-until-revised. Editing an APPROVED quotation creates a new DRAFT
    // revision instead of mutating this row (see DealQuotationService#createRevision).
    public static final String APPROVED = "APPROVED";
}
