package th.co.glr.hr.ticket;

import java.util.Set;

public final class QuotationRecipient {
    private QuotationRecipient() {}

    public static final String DESIGNER = "DESIGNER";
    public static final String OWNER = "OWNER";
    public static final String BUYER = "BUYER";
    public static final String UNSPECIFIED = "UNSPECIFIED";

    public static final Set<String> VALID = Set.of(DESIGNER, OWNER, BUYER, UNSPECIFIED);

    public static boolean isValid(String value) {
        return VALID.contains(value);
    }
}
