package th.co.glr.hr.customerquotation;

public final class DiscountApprovalRequests {
    private DiscountApprovalRequests() {}

    /** reason is mandatory. */
    public record RejectDiscountApprovalRequest(String reason) {}
}
