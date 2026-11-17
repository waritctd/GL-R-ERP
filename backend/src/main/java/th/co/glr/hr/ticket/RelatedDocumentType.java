package th.co.glr.hr.ticket;

/** What a ticket_event produced (V58), so a status change can be correlated with its document without matching
 * timestamps or parsing free text. The link is polymorphic and has no foreign key: the target lives in one of
 * several tables, so callers must not assume the row still exists. */
public final class RelatedDocumentType {
    public static final String QUOTATION       = "QUOTATION";
    public static final String DEPOSIT_NOTICE  = "DEPOSIT_NOTICE";
    public static final String PAYMENT_RECEIPT = "PAYMENT_RECEIPT";
    public static final String DELIVERY_RECORD = "DELIVERY_RECORD";
    /** {@code sales.import_request} (V154/V184) — the stored, per-factory ใบขอซื้อ. */
    public static final String IMPORT_REQUEST  = "IMPORT_REQUEST";

    private RelatedDocumentType() {}
}
