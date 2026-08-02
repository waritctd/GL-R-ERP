package th.co.glr.hr.ticket;

import java.util.Set;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * The one place that answers "who may reach this deal, and its documents".
 *
 * <p>This class exists because the answer used to be written down twice, and the two copies
 * drifted (issue #389). {@link TicketService} gated ticket reads on {@code VIEWER_ROLES}
 * (sales/import/ceo/account/sales_manager) while {@code AttachmentController} gated the SAME
 * deal's documents on its own private {@code MANAGER_ROLES = {hr, sales_manager, ceo}} — so
 * {@code hr}, which cannot read a deal, a quotation, a payment or an activity feed anywhere else
 * in the system, could list and download every customer contract, PO and tax invoice on every
 * deal; while {@code account}, the role that confirms deposit and final-payment receipts
 * ({@link TicketService} {@code ACCOUNT_ROLES}) and the ONLY role permitted to file invoice
 * evidence ({@code CommissionService#createFromDeal}), was refused the documents it is asked to
 * confirm money against. Both directions were wrong, and neither was intended.
 *
 * <p>{@code TicketService.VIEWER_ROLES} is now an alias of {@link #VIEWER_ROLES} rather than a
 * second literal, so those two cannot drift apart again. That unified two of THREE hand-written
 * copies of the same five roles — {@code DepositNoticeService} (see its own {@code VIEWER_ROLES},
 * which additionally 403s import right after the role check) still carries a third. Folding it in
 * is a separate change: its gate is not identical, so aliasing it blindly would alter behaviour.
 * Until then, an edit here must be checked against that file by hand.
 *
 * <p>Everything else here is stated as the access QUESTION each caller needs answered, not as a
 * role list to copy.
 */
public final class TicketAccessPolicy {

    /**
     * Who may read a deal at all. hr/employee/warehouse/qc have no business reading customer
     * pricing, and that includes the deal's documents. sales_manager is read+comment-only
     * oversight — it must NEVER be added to TicketService's SALES_ROLES/IMPORT_ROLES/CEO_ROLES/
     * ACCOUNT_ROLES, only here.
     */
    public static final Set<String> VIEWER_ROLES =
        Set.of("sales", "import", "ceo", "account", "sales_manager");

    /**
     * Who may ADD or REMOVE a deal's documents without being one of its participants — strictly
     * narrower than {@link #VIEWER_ROLES}, because writing a document is not the same question as
     * reading one.
     *
     * <p>Deliberately excludes {@code account} even though account can now READ every deal
     * document: the tax invoice is recorded ONLY through {@code POST /api/commissions/from-deal}
     * ({@code CommissionService#createFromDeal}), which dual-writes the file as an
     * {@code AttachType.INVOICE} ticket attachment AND creates the rep's commission in one
     * transaction. A second account-writable upload path here would satisfy the close gate's
     * {@code invoiceOnFile} check WITHOUT creating that commission — the rep would silently lose
     * it. The equivalent control was removed from TicketDetailPage for exactly this reason; do not
     * re-open it from the API side either.
     *
     * <p>Excludes {@code import} too, which is refused document READS as a non-participant in the
     * first place (see {@link #canViewDocuments}) — import touches a deal's documents once it has
     * actually picked the deal up, i.e. once it is the assignee, exactly as before issue #389.
     */
    public static final Set<String> DOCUMENT_WRITER_ROLES = Set.of("sales_manager", "ceo");

    private TicketAccessPolicy() {
    }

    /**
     * The two identities the deal itself records: the sales rep who created it and (via the
     * import pickup event, {@code TicketRepository#addEventInternal}) whoever picked it up.
     */
    public static boolean isParticipant(TicketSummaryDto summary, UserPrincipal actor) {
        if (summary == null || actor == null) {
            return false;
        }
        return actor.id() == summary.createdById()
            || (summary.assignedToId() != null && actor.id() == summary.assignedToId());
    }

    /**
     * May {@code actor} read this deal's documents (list them, download one)?
     *
     * <p>Aligned with {@code TicketService#requireViewAccess} — a viewer role, with sales scoped
     * to its own deals — but NOT identical to it, in two deliberate ways:
     *
     * <ol>
     *   <li><b>Participants are admitted regardless of role.</b> Whoever created the deal or
     *       picked it up reaches its documents even if their role is not in
     *       {@link #VIEWER_ROLES}; {@code requireViewAccess} checks the role first and would
     *       refuse them. Nothing can reach that branch today (deals are created by {@code sales}
     *       and picked up by {@code import}, both viewers), but it IS wider, so it is stated here
     *       rather than left to be discovered. It preserves the pre-#389 participant grant.</li>
     *   <li><b>{@code import} is refused unless it is a participant.</b> This is the one place
     *       document reads are deliberately NARROWER than deal reads. Import may read the deal
     *       shell, but the codebase withholds the customer-facing documents from it in five
     *       separate places: {@code TicketService#projectForRole} nulls quotations out of the
     *       DTO, {@code TicketService#loadQuotationContext} 403s a quotation file download
     *       outright ("a permission question, not a lookup miss"),
     *       {@code TicketService#listPayments} 403s the payment ledger,
     *       {@code DepositNoticeService#requireTicketViewer} 403s deposit notices as "customer
     *       financial documents", and {@code salesViewScope.visibleSections('import')} hides
     *       quotation/dealQuotation/payment/depositNotice. {@code AttachType} is
     *       {@code {PO, SIGNED_QUOTATION, INVOICE, OTHER}} and
     *       {@code AttachmentRepository#findByTicketId} applies no type filter — so granting
     *       import a blanket document read would hand it the countersigned quotation and the
     *       ใบกำกับภาษี, both carrying the approved customer price, on a deal whose quotation
     *       file and payment ledger it is refused by two other endpoints. Import that has
     *       actually picked a deal up is its assignee, i.e. a participant, and keeps access
     *       exactly where it had it before.</li>
     * </ol>
     */
    public static boolean canViewDocuments(TicketSummaryDto summary, UserPrincipal actor) {
        if (actor == null) {
            return false;
        }
        if (isParticipant(summary, actor)) {
            return true;
        }
        if (!VIEWER_ROLES.contains(actor.role())) {
            return false;
        }
        // A sales rep reaches only their OWN deals; on someone else's they are not a participant,
        // so being in VIEWER_ROLES is not enough (same rule as requireViewAccess). import is
        // refused for the customer-price-exposure reason in this method's javadoc.
        return !"sales".equals(actor.role()) && !"import".equals(actor.role());
    }

    /** May {@code actor} attach a document to, or remove one from, this deal? */
    public static boolean canManageDocuments(TicketSummaryDto summary, UserPrincipal actor) {
        if (actor == null) {
            return false;
        }
        return isParticipant(summary, actor) || DOCUMENT_WRITER_ROLES.contains(actor.role());
    }
}
