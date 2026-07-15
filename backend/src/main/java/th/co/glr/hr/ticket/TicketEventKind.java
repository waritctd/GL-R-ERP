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
    public static final String EDITED                = "EDITED";
    public static final String DOCUMENT_ISSUED      = "DOCUMENT_ISSUED";
    public static final String REVISION_REQUESTED   = "REVISION_REQUESTED";
    public static final String PRICE_REVISED         = "PRICE_REVISED";
    // Dual-track post-quotation events (Track P = payment, Track F = fulfillment)
    public static final String CUSTOMER_CONFIRMED    = "CUSTOMER_CONFIRMED";
    public static final String DEPOSIT_NOTICE_ISSUED = "DEPOSIT_NOTICE_ISSUED";
    public static final String DEPOSIT_PAID          = "DEPOSIT_PAID";
    public static final String IR_ISSUED             = "IR_ISSUED";
    public static final String IR_SENT               = "IR_SENT";
    public static final String SHIPPING              = "SHIPPING";
    public static final String GOODS_RECEIVED        = "GOODS_RECEIVED";
    public static final String AWAITING_FINAL_PAYMENT = "AWAITING_FINAL_PAYMENT";
    public static final String FULLY_PAID            = "FULLY_PAID";

    private TicketEventKind() {}
}
