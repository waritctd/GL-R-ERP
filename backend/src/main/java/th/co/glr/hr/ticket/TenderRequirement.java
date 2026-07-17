package th.co.glr.hr.ticket;

import java.util.Set;

public final class TenderRequirement {
    public static final String REQUIRED     = "REQUIRED";
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    public static final String UNKNOWN      = "UNKNOWN";

    public static final Set<String> VALID = Set.of(REQUIRED, NOT_REQUIRED, UNKNOWN);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    private TenderRequirement() {}
}
