package th.co.glr.hr.ticket;

/** sales.attachment.attach_type values. The invoice itself is produced by an external system and uploaded here
 * as a file. */
public final class AttachType {
    public static final String PO                = "PO";
    public static final String SIGNED_QUOTATION  = "SIGNED_QUOTATION";
    /** The tax invoice (ใบกำกับภาษี) issued to the customer — required before close. */
    public static final String INVOICE           = "INVOICE";
    public static final String OTHER             = "OTHER";
    /** Proof of a delivery round's hand-over, attached by the warehouse or sales (a later slice). */
    public static final String DELIVERY_PROOF    = "DELIVERY_PROOF";

    /** A system document (quotation, deposit notice, remaining invoice, IR) copied onto a delivery round at send time. */
    public static final String SYSTEM_DOC        = "SYSTEM_DOC";

    private AttachType() {}
}
