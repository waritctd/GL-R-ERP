package th.co.glr.hr.pricingrequest;

import java.util.Set;

/**
 * How firm a pricing-request item's requested quantity is. This is an
 * independent property of the line item and must NOT be inferred from the
 * request's {@link PricingRequestRecipient}.
 */
public final class QuantityType {
    /** Standard reference quantity, e.g. 1 sqm for a designer quote. */
    public static final String REFERENCE = "REFERENCE";
    /** Project quantity that is not yet final. */
    public static final String ESTIMATE = "ESTIMATE";
    /** Actual commercial quantity taken from a BOQ. */
    public static final String CONFIRMED = "CONFIRMED";

    public static final Set<String> VALUES = Set.of(REFERENCE, ESTIMATE, CONFIRMED);

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    private QuantityType() {}
}
