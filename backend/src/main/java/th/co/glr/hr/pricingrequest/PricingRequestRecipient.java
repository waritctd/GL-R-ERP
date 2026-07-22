package th.co.glr.hr.pricingrequest;

import java.util.Set;

/**
 * Duplicates {@code th.co.glr.hr.ticket.QuotationRecipient}'s values on purpose
 * for now. A shared sales-common type is the eventual home for this concept,
 * but moving it now would balloon this commit's diff for no functional gain.
 */
public final class PricingRequestRecipient {
    public static final String DESIGNER = "DESIGNER";
    public static final String OWNER    = "OWNER";
    public static final String BUYER    = "BUYER";

    public static final Set<String> VALUES = Set.of(DESIGNER, OWNER, BUYER);

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    private PricingRequestRecipient() {}
}
