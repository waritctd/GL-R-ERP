package th.co.glr.hr.ticket;

/**
 * The ONE definition of "this actor is on the deal as its owner or as the person who keyed it in"
 * (owner/keyer split, 2026-11-05). Every sales access check goes through here; commission and
 * notifications deliberately stay owner-only ({@code createdById}).
 */
public final class DealOwnership {
    private DealOwnership() {}

    public static boolean isOwnerOrKeyer(long createdById, Long keyedById, long actorId) {
        return createdById == actorId || (keyedById != null && keyedById == actorId);
    }

    public static boolean isOwnerOrKeyer(TicketSummaryDto s, long actorId) {
        return isOwnerOrKeyer(s.createdById(), s.keyedById(), actorId);
    }
}
