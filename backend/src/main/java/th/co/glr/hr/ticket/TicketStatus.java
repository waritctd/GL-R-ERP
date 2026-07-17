package th.co.glr.hr.ticket;

import java.util.Set;

public final class TicketStatus {
    public static final String DRAFT            = "draft";
    public static final String SUBMITTED        = "submitted";
    public static final String IN_REVIEW        = "in_review";
    public static final String PRICE_PROPOSED   = "price_proposed";
    public static final String APPROVED         = "approved";
    public static final String REJECTED         = "rejected";
    public static final String QUOTATION_ISSUED = "quotation_issued";
    public static final String DOCUMENT_ISSUED  = "document_issued";
    public static final String CLOSED           = "closed";
    public static final String CANCELLED        = "cancelled";

    /**
     * Exactly the set the DB's chk_ticket_status constraint accepts (V6 as widened
     * by V17). Event logging consults this to decide whether an event's toStatus is
     * a real ticket status to persist onto sales.ticket.status: deal-pipeline events
     * (STAGE_CHANGED, MARKED_LOST, ON_HOLD, POLICY_CHANGED, …) reuse the same
     * from/to slots to carry sales_stage / lifecycle values as timeline labels, and
     * those must never be written into the status column.
     */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IN_REVIEW, PRICE_PROPOSED, APPROVED, REJECTED,
        QUOTATION_ISSUED, DOCUMENT_ISSUED, CLOSED, CANCELLED);

    public static boolean isValid(String status) {
        return status != null && VALUES.contains(status);
    }

    private TicketStatus() {}
}
