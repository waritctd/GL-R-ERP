package th.co.glr.hr.ticket;

/**
 * sales.attachment.attach_type values.
 *
 * The column is a plain VARCHAR with no CHECK constraint (V27), so these are a
 * convention rather than an enforced vocabulary — but INVOICE is load-bearing:
 * {@link TicketService#confirmCloseReady} refuses to let ฝ่ายบัญชี confirm a close
 * until an attachment of this type exists on the deal. The invoice itself is
 * produced by an external system and uploaded here as a file.
 */
public final class AttachType {
    public static final String PO                = "PO";
    public static final String SIGNED_QUOTATION  = "SIGNED_QUOTATION";
    /** The tax invoice (ใบกำกับภาษี) issued to the customer — required before close. */
    public static final String INVOICE           = "INVOICE";
    public static final String OTHER             = "OTHER";

    private AttachType() {}
}
