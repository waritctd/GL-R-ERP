package th.co.glr.hr.ticket;

public final class TicketEventKind {
    public static final String CREATED          = "CREATED";
    public static final String SUBMITTED        = "SUBMITTED";
    public static final String PICKED_UP        = "PICKED_UP";
    public static final String PRICE_PROPOSED   = "PRICE_PROPOSED";
    public static final String APPROVED         = "APPROVED";
    public static final String REJECTED         = "REJECTED";
    public static final String QUOTATION_ISSUED = "QUOTATION_ISSUED";
    public static final String COMMENTED        = "COMMENTED";
    public static final String CLOSED           = "CLOSED";
    public static final String CANCELLED        = "CANCELLED";

    private TicketEventKind() {}
}
