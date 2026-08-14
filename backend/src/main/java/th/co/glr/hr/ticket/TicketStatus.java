package th.co.glr.hr.ticket;

import java.util.Map;
import java.util.Set;

public final class TicketStatus {
    public static final String DRAFT            = "draft";
    public static final String SUBMITTED        = "submitted";
    public static final String IN_REVIEW        = "in_review";
    public static final String PRICE_PROPOSED   = "price_proposed";
    public static final String APPROVED         = "approved";
    /**
     * Dead constant, deliberately kept. Nothing anywhere in the codebase reads or writes it — the
     * CEO's rejection path has always been {@code TicketService.reject}, which sends the ticket
     * back to {@link #IN_REVIEW} for Import to re-price rather than to a terminal "rejected".
     *
     * <p>Kept because the DB's {@code chk_ticket_status} CHECK (V6, re-declared by V17) still
     * lists {@code 'rejected'}, so a legacy row could carry it and {@link #VALUES} must stay
     * exactly the set the constraint accepts — that identity is what stops
     * {@code addEventInternal} ever attempting a value the column would reject. Dropping the
     * constant without a migration would break that correspondence for no gain; dropping it
     * <em>with</em> a migration is a schema change this task has no reason to make.
     *
     * <p>It therefore appears in {@link #ALLOWED} only as a "from" state (a legacy row can still
     * be cancelled), never as a "to" — nothing may transition a deal <em>into</em> it.
     */
    public static final String REJECTED         = "rejected";
    public static final String QUOTATION_ISSUED = "quotation_issued";
    /**
     * Legacy-only terminal-ish status: no code path writes it any more (issuing a deposit notice
     * stopped flipping the ticket here in the 2026-07-16 audit, findings #3/#4 — see
     * {@code DepositNoticeService.issue}). It still has OUT-edges in {@link #ALLOWED} because
     * pre-audit rows carry it and {@code DepositNoticeService.requestRevision} /
     * {@code TicketService.verifyClose} both still accept it as a starting point.
     */
    public static final String DOCUMENT_ISSUED  = "document_issued";
    public static final String CLOSED           = "closed";
    public static final String CANCELLED        = "cancelled";

    /**
     * Exactly the set the DB's chk_ticket_status constraint accepts (V6 as widened
     * by V17). Event logging consults this to decide whether an event's toStatus is
     * a real ticket status: deal-pipeline events (STAGE_CHANGED, MARKED_LOST,
     * ON_HOLD, POLICY_CHANGED, …) reuse the same from/to slots to carry sales_stage
     * / lifecycle values as timeline labels, and those must never be mistaken for a
     * status.
     */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IN_REVIEW, PRICE_PROPOSED, APPROVED, REJECTED,
        QUOTATION_ISSUED, DOCUMENT_ISSUED, CLOSED, CANCELLED);

    /**
     * The declared, enforced transition table for {@code sales.ticket.status}, modelled on
     * {@code th.co.glr.hr.pricingrequest.PricingRequestStatus} and {@link PaymentTrack} — the
     * house pattern for a state machine in this pipeline.
     *
     * <p><strong>Derived from the real write sites, not from a clean design.</strong> Every edge
     * below is a transition production code performs today; nothing was invented, and nothing that
     * the code can reach was left out. Before this table existed the column was written by
     * {@code TicketRepository.addEventInternal} as a side effect of logging an event, gated only
     * by a MEMBERSHIP check ({@link #isValid}) — so any event carrying any valid status overwrote
     * the column from any current state, while {@code DepositNoticeService} and
     * {@code TicketService.requireClosePrerequisites} read that same column as a guard.
     *
     * <p>The sites, and the edges each contributes:
     *
     * <table>
     *   <caption>write sites</caption>
     *   <tr><th>Site</th><th>Guard it already enforces</th><th>Edges</th></tr>
     *   <tr><td>{@code TicketRepository.create}</td><td>—</td>
     *       <td>none: {@code draft} is the INSERT's initial state, not an edge</td></tr>
     *   <tr><td>{@code TicketService.pickup}</td><td>status == submitted</td>
     *       <td>submitted → in_review</td></tr>
     *   <tr><td>{@code TicketService.proposePrice}</td><td>status ∈ PROPOSE_ALLOWED_STATUSES</td>
     *       <td>in_review / price_proposed / approved → price_proposed</td></tr>
     *   <tr><td>{@code TicketService.approve}</td><td>status == price_proposed</td>
     *       <td>price_proposed → approved</td></tr>
     *   <tr><td>{@code TicketService.reject}</td><td>status == price_proposed</td>
     *       <td>price_proposed → in_review</td></tr>
     *   <tr><td>{@code TicketService.generateQuotation}</td><td>status ∈ QUOTATION_ALLOWED_STATUSES</td>
     *       <td>approved / quotation_issued → quotation_issued</td></tr>
     *   <tr><td>{@code TicketService.verifyClose}</td><td>requireClosePrerequisites</td>
     *       <td>quotation_issued / document_issued → closed</td></tr>
     *   <tr><td>{@code TicketService.cancel}</td><td>status ∉ {closed, cancelled}</td>
     *       <td>every non-terminal status → cancelled</td></tr>
     *   <tr><td>{@code OrderConfirmationService.confirmOrder}</td><td>compare-and-set FROM draft</td>
     *       <td>draft → quotation_issued</td></tr>
     *   <tr><td>{@code DepositNoticeService.requestRevision}</td><td>status ∈ {approved, document_issued}</td>
     *       <td>→ approved / price_proposed / in_review by revision scope</td></tr>
     * </table>
     *
     * <p><strong>The backward edges out of {@code approved} / {@code document_issued} are
     * intentional</strong>, not an oversight: {@code DepositNoticeService.requestRevision} moves a
     * deal back so the right party re-does its part (PRICE_CHANGE → the CEO re-approves, NEW_ITEM
     * → Import re-prices). Converging that flow onto the pricing-request revision path is a
     * separate, later piece of work; until then these are declared edges, not accidents.
     *
     * <p><strong>Self-edges</strong> ({@code approved → approved},
     * {@code price_proposed → price_proposed}, {@code quotation_issued → quotation_issued}) are
     * equally deliberate: re-proposing a price, re-issuing a quotation and a QTY_OR_NOTE revision
     * are all real repeat actions that re-confirm the same state, exactly as
     * {@link PaymentTrack}'s one deposit-notice self-loop does.
     *
     * <p>{@code draft → closed} is absent on purpose, as is anything out of {@link #CLOSED} or
     * {@link #CANCELLED}: closing runs through {@code requireClosePrerequisites}, which only ever
     * admits {@code quotation_issued} or {@code document_issued}, and both terminals are terminal.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
        // The only forward exit from draft is OrderConfirmationService's bridge write; a draft
        // deal is otherwise moved by the PricingRequest chain, which never touches this column.
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

    /**
     * Strict single-hop check. Returns {@code false} — never throws — for a null/unknown state on
     * either side, any undeclared edge, and any self-edge not listed above. There is no multi-hop
     * walker (contrast {@link PaymentTrack#stepsBetween}): every ticket-status write site knows
     * both ends of its own transition, so no caller ever needs one.
     */
    public static boolean canTransition(String from, String to) {
        return from != null && to != null
            && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isValid(String status) {
        return status != null && VALUES.contains(status);
    }

    private TicketStatus() {}
}
