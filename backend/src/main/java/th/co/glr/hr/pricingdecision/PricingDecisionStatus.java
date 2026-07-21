package th.co.glr.hr.pricingdecision;

public final class PricingDecisionStatus {
    /** Open, editable, currently under CEO review. */
    public static final String DRAFT = "DRAFT";
    /** Terminal: a customer-facing selling price now exists. */
    public static final String APPROVED = "APPROVED";
    /** Terminal: the CEO sent the request back to Import for a new costing version. */
    public static final String RETURNED = "RETURNED";

    private PricingDecisionStatus() {}
}
