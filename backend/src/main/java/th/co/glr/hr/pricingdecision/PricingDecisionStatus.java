package th.co.glr.hr.pricingdecision;

public final class PricingDecisionStatus {
    /** Open, editable, currently under CEO review. */
    public static final String DRAFT = "DRAFT";
    /** Terminal: a customer-facing selling price now exists. */
    public static final String APPROVED = "APPROVED";
    /** Terminal: the CEO sent the request back to Import for a new costing version. */
    public static final String RETURNED = "RETURNED";
    /**
     * Terminal (Step 5, V75, design correction 1): a customer-change (cost-affecting) revision
     * superseded this decision's own pricing request. Set by
     * {@code PricingRequestRepository}'s supersede cascade, called alongside
     * {@code cancelOpenStep2Children} from {@code PricingRequestService.createCustomerChangeRevision}
     * — without this, a DRAFT/APPROVED decision stayed silently readable as current after its
     * parent pricing request moved on.
     */
    public static final String SUPERSEDED = "SUPERSEDED";

    private PricingDecisionStatus() {}
}
