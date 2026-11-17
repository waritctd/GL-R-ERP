package th.co.glr.hr.commission;

public final class CommissionStatus {
    public static final String SUBMITTED = "SUBMITTED";
    public static final String MANAGER_APPROVED = "MANAGER_APPROVED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** Unreachable by design — nothing in the application ever writes it, and that is correct. The only writes in
     * the tree are fixtures that bypass the service entirely. Do not delete this constant. Removing the Java
     * constant without those is silent divergence, not cleanup. */
    public static final String VOID = "VOID";

    private CommissionStatus() {
    }
}
