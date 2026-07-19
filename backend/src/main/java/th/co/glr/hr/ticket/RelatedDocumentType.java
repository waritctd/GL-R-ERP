package th.co.glr.hr.ticket;

/**
 * What a ticket_event produced (V57), so a status change can be correlated with
 * its document without matching timestamps or parsing free text.
 *
 * Every value here IS written somewhere — an import request has no row of its
 * own, so there is deliberately no IMPORT_REQUEST constant to avoid adding to
 * this codebase's stock of declared-but-unreachable vocabulary.
 *
 * The link is polymorphic and has no foreign key: the target lives in one of
 * several tables, so callers must not assume the row still exists.
 */
public final class RelatedDocumentType {
    public static final String QUOTATION       = "QUOTATION";
    public static final String DEPOSIT_NOTICE  = "DEPOSIT_NOTICE";
    public static final String PAYMENT_RECEIPT = "PAYMENT_RECEIPT";
    public static final String DELIVERY_RECORD = "DELIVERY_RECORD";

    private RelatedDocumentType() {}
}
