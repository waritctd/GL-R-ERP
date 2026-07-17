package th.co.glr.hr.ticket;

import java.util.Set;

public final class DepositPolicy {
    public static final String REQUIRED        = "REQUIRED";
    public static final String NOT_REQUIRED    = "NOT_REQUIRED";
    public static final String WAIVED          = "WAIVED";
    public static final String CREDIT_CUSTOMER = "CREDIT_CUSTOMER";

    public static final Set<String> VALID = Set.of(REQUIRED, NOT_REQUIRED, WAIVED, CREDIT_CUSTOMER);
    public static final Set<String> NON_REQUIRED = Set.of(NOT_REQUIRED, WAIVED, CREDIT_CUSTOMER);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    public static boolean bypassesDepositNotice(String value) {
        return NON_REQUIRED.contains(value);
    }

    private DepositPolicy() {}
}
