package th.co.glr.hr.pricingrequest;

/** CR-1 (GLA-167) states of a lead-time change request. */
public final class LeadTimeChangeStatus {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";

    private LeadTimeChangeStatus() {}
}
