package th.co.glr.hr.factoryquote;

import java.util.Set;

public final class FactoryQuoteStatus {
    public static final String DRAFT = "DRAFT";
    public static final String REQUESTED = "REQUESTED";
    public static final String RESPONSE_RECEIVED = "RESPONSE_RECEIVED";
    public static final String NEGOTIATING = "NEGOTIATING";
    public static final String READY_FOR_COSTING = "READY_FOR_COSTING";
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String CANCELLED = "CANCELLED";

    public static final Set<String> VALUES = Set.of(
        DRAFT, REQUESTED, RESPONSE_RECEIVED, NEGOTIATING, READY_FOR_COSTING,
        NOT_AVAILABLE, SUPERSEDED, CANCELLED);

    private FactoryQuoteStatus() {}
}
