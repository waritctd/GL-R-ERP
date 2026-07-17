package th.co.glr.hr.ticket;

import java.util.Set;

/**
 * Reasons a deal is marked lost (V50). Cross-reference to the business's F1–F8
 * sheet: PRODUCT_FIT=F1, PRICE=F2, LEAD_TIME=F3, PAYMENT_TERMS=F4,
 * RELATIONSHIP=F5, PROJECT_ON_HOLD=F6, PROJECT_CANCELLED=F7, ALREADY_PURCHASED=F8.
 *
 * Lost is orthogonal to the sales stage — marking lost preserves the stage so a
 * reopen (expected for PROJECT_ON_HOLD) resumes exactly where the deal was.
 */
public final class DealLostReason {
    public static final String PRODUCT_FIT       = "PRODUCT_FIT";
    public static final String PRICE             = "PRICE";
    public static final String LEAD_TIME         = "LEAD_TIME";
    public static final String PAYMENT_TERMS     = "PAYMENT_TERMS";
    public static final String RELATIONSHIP      = "RELATIONSHIP";
    public static final String PROJECT_ON_HOLD   = "PROJECT_ON_HOLD";
    public static final String PROJECT_CANCELLED = "PROJECT_CANCELLED";
    public static final String ALREADY_PURCHASED = "ALREADY_PURCHASED";

    public static final Set<String> VALID = Set.of(
        PRODUCT_FIT, PRICE, LEAD_TIME, PAYMENT_TERMS,
        RELATIONSHIP, PROJECT_ON_HOLD, PROJECT_CANCELLED, ALREADY_PURCHASED
    );

    public static boolean isValid(String reason) {
        return reason != null && VALID.contains(reason);
    }

    private DealLostReason() {}
}
