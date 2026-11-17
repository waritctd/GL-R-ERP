package th.co.glr.hr.ticket;

import java.util.Set;
import th.co.glr.hr.auth.UserPrincipal;

/** The one place that answers "who may reach this deal, and its documents". TicketService.VIEWER_ROLES is now
 * an alias of #VIEWER_ROLES rather than a second literal, so those two cannot drift apart again. */
public final class TicketAccessPolicy {

    /** Who may read a deal at all. hr/employee/warehouse/qc have no business reading customer pricing, and that
     * includes the deal's documents. sales_manager is read+comment-only oversight — it must NEVER be added to
     * TicketService's SALES_ROLES/IMPORT_ROLES/CEO_ROLES/ ACCOUNT_ROLES, only here. */
    public static final Set<String> VIEWER_ROLES =
        Set.of("sales", "import", "ceo", "account", "sales_manager");

    /** Who may ADD or REMOVE a deal's documents without being one of its participants — strictly narrower than
     * #VIEWER_ROLES, because writing a document is not the same question as reading one. */
    public static final Set<String> DOCUMENT_WRITER_ROLES = Set.of("sales_manager", "ceo");

    private TicketAccessPolicy() {
    }

    /** The two identities the deal itself records: the sales rep who created it and (via the import pickup event,
     * TicketRepository#addEventInternal) whoever picked it up. */
    public static boolean isParticipant(TicketSummaryDto summary, UserPrincipal actor) {
        if (summary == null || actor == null) {
            return false;
        }
        return DealOwnership.isOwnerOrKeyer(summary, actor.id())
            || (summary.assignedToId() != null && actor.id() == summary.assignedToId());
    }

    /** May actor read this deal's documents (list them, download one)? import is admitted only for a deal inside
     * its import scope (or as a participant). It preserves the pre-#389 participant grant. Owner ruling 2026-09-30
     * — it supersedes the blanket refusal issue #389's review pinned here. */
    public static boolean canViewDocuments(TicketSummaryDto summary, UserPrincipal actor) {
        return canViewDocuments(summary, actor, false);
    }

    /** Selects affected frontend tests, including readers outside the import graph. */

    public static boolean canViewDocuments(TicketSummaryDto summary, UserPrincipal actor, boolean inRoleScope) {
        if (actor == null) {
            return false;
        }
        if (isParticipant(summary, actor)) {
            return true;
        }
        if (!VIEWER_ROLES.contains(actor.role())) {
            return false;
        }
        // A sales rep reaches only their OWN deals; on someone else's they are not a participant, so being in
        // VIEWER_ROLES is not enough (same rule as requireViewAccess).
        if ("import".equals(actor.role()) || "account".equals(actor.role())) {
            return inRoleScope;
        }
        return !"sales".equals(actor.role());
    }

    /** May {@code actor} attach a document to, or remove one from, this deal? */
    public static boolean canManageDocuments(TicketSummaryDto summary, UserPrincipal actor) {
        if (actor == null) {
            return false;
        }
        return isParticipant(summary, actor) || DOCUMENT_WRITER_ROLES.contains(actor.role());
    }
}
