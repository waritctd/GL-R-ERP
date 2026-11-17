package th.co.glr.hr.ticket;

import java.util.Map;
import java.util.Set;

public final class TicketStatus {
    public static final String DRAFT            = "draft";
    public static final String SUBMITTED        = "submitted";
    public static final String IN_REVIEW        = "in_review";
    public static final String PRICE_PROPOSED   = "price_proposed";
    public static final String APPROVED         = "approved";
    /** Dead constant, deliberately kept. It therefore appears in #ALLOWED only as a "from" state (a legacy row can
     * still be cancelled), never as a "to" — nothing may transition a deal into it. */
    public static final String REJECTED         = "rejected";
    public static final String QUOTATION_ISSUED = "quotation_issued";
    /** Legacy-only terminal-ish status: no code path writes it any more (issuing a deposit notice stopped flipping
     * the ticket here in the 2026-07-16 audit, findings #3/#4 — see DepositNoticeService.issue). */
    public static final String DOCUMENT_ISSUED  = "document_issued";
    public static final String CLOSED           = "closed";
    public static final String CANCELLED        = "cancelled";

    /** Exactly the set the DB's chk_ticket_status constraint accepts (V6 as widened by V17). */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IN_REVIEW, PRICE_PROPOSED, APPROVED, REJECTED,
        QUOTATION_ISSUED, DOCUMENT_ISSUED, CLOSED, CANCELLED);

    /** The declared, enforced transition table for sales.ticket.status, modelled on
     * th.co.glr.hr.pricingrequest.PricingRequestStatus and PaymentTrack — the house pattern for a state machine in
     * this pipeline. Derived from the real write sites, not from a clean design. */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
        // The only forward exit from draft is OrderConfirmationService's bridge write; a draft deal is otherwise moved
        // by the PricingRequest chain, which never touches this column.
        Map.entry(DRAFT,            Set.of(QUOTATION_ISSUED, CANCELLED)),
        Map.entry(SUBMITTED,        Set.of(IN_REVIEW, CANCELLED)),
        Map.entry(IN_REVIEW,        Set.of(PRICE_PROPOSED, CANCELLED)),
        Map.entry(PRICE_PROPOSED,   Set.of(PRICE_PROPOSED, APPROVED, IN_REVIEW, CANCELLED)),
        Map.entry(APPROVED,         Set.of(APPROVED, PRICE_PROPOSED, IN_REVIEW, QUOTATION_ISSUED, CANCELLED)),
        // Legacy rows only — nothing transitions INTO rejected (see the constant's own Javadoc).
        Map.entry(REJECTED,         Set.of(CANCELLED)),
        Map.entry(QUOTATION_ISSUED, Set.of(QUOTATION_ISSUED, CLOSED, CANCELLED)),
        Map.entry(DOCUMENT_ISSUED,  Set.of(APPROVED, PRICE_PROPOSED, IN_REVIEW, CLOSED, CANCELLED)),
        Map.entry(CLOSED,           Set.<String>of()),
        Map.entry(CANCELLED,        Set.<String>of()));

    /** Strict single-hop check. Returns false — never throws — for a null/unknown state on either side, any
     * undeclared edge, and any self-edge not listed above. */
    public static boolean canTransition(String from, String to) {
        return from != null && to != null
            && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isValid(String status) {
        return status != null && VALUES.contains(status);
    }

    private TicketStatus() {}
}
