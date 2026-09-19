package th.co.glr.hr.ticket;

/**
 * What a ticket_event produced (V58), so a status change can be correlated with
 * its document without matching timestamps or parsing free text.
 *
 * Every value here IS written somewhere. IMPORT_REQUEST joined this list in V154 (ticket_event's own
 * chk_event_related_document was re-declared to allow it there) and is used from V184 onward, once
 * the stored {@code sales.import_request} aggregate actually writes IR_ISSUED / IMPORT_STEP_ADVANCED
 * events naming the row that produced them — this Javadoc previously said the constant was absent
 * "because an import request has no row of its own", which stopped being true the moment V154's
 * table existed and is corrected here rather than left to mislead the next reader.
 *
 * The link is polymorphic and has no foreign key: the target lives in one of
 * several tables, so callers must not assume the row still exists.
 */
public final class RelatedDocumentType {
    public static final String QUOTATION       = "QUOTATION";
    public static final String DEPOSIT_NOTICE  = "DEPOSIT_NOTICE";
    public static final String PAYMENT_RECEIPT = "PAYMENT_RECEIPT";
    public static final String DELIVERY_RECORD = "DELIVERY_RECORD";
    /** {@code sales.import_request} (V154/V184) — the stored, per-factory ใบขอซื้อ. */
    public static final String IMPORT_REQUEST  = "IMPORT_REQUEST";

    private RelatedDocumentType() {}
}
