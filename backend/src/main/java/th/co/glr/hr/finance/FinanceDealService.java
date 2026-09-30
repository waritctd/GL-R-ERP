package th.co.glr.hr.finance;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.attachment.AttachmentDto;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.CommentRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.MoneyMilestone;
import th.co.glr.hr.ticket.RecordPaymentRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketResponses.TicketActionDto;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Finance-only deal read and money actions (H1, owner decisions A2 + part 2). Roles: {@code account}
 * and {@code ceo} only -- NOT {@code TicketAccessPolicy.VIEWER_ROLES}, so a deal's own sales rep,
 * sales_manager, import, hr and employee are all refused. {@code account} is further limited to the
 * same list scope as {@code GET /api/tickets} ({@link TicketRepository#isInAccountScope}); {@code ceo}
 * reads any deal.
 *
 * <p><b>Actions delegate.</b> Every write below calls the EXISTING {@link TicketService} business method
 * (payment / close / billing / stage logic is neither duplicated nor changed) and then returns the
 * finance view. Each business method keeps TODAY's role gate for that action -- deposit-paid,
 * final-payment, payments and close-confirm are account only (a ceo caller is 403); billing,
 * close-revoke, stage and comments are account + ceo -- and enforces the account row scope itself, so
 * the scope holds for any caller of those methods, not only this class. This class adds the up-front
 * role check, the 404, and the finance-shaped response.
 *
 * <p>The payload is built field by field from finance-safe sources. It never touches {@code TicketDto}
 * (events, tracking), pricing requests, factory quotes or costing.
 *
 * <p><b>Attachment mapping (a choice the owner did not specify):</b> {@code INVOICE} -&gt; taxInvoices,
 * {@code PO} -&gt; purchaseOrders, and {@code SIGNED_QUOTATION} plus {@code OTHER} -&gt; contracts, because
 * the UI's own uploader files a contract under the default {@code OTHER} type (only a filename
 * containing "po" becomes {@code PO}), so a contract genuinely lives under both.
 */
@Service
public class FinanceDealService {
    private static final Set<String> ALLOWED_ROLES = Set.of("account", "ceo");
    private static final Set<String> CONTRACT_TYPES = Set.of(AttachType.SIGNED_QUOTATION, AttachType.OTHER);
    /** The ticket actions that are money actions -- the only ones the finance view offers. */
    private static final Set<String> MONEY_ACTIONS = Set.of(
        "DEPOSIT_PAID", "RECORD_PAYMENT", "FINAL_PAYMENT", "CONFIRM_CLOSE", "REVOKE_CLOSE_CONFIRM");

    private final TicketRepository tickets;
    private final FinanceDealRepository finance;
    private final DepositNoticeRepository depositNotices;
    private final RemainingInvoiceRepository remainingInvoices;
    private final AttachmentRepository attachments;
    private final TicketService ticketService;

    public FinanceDealService(TicketRepository tickets, FinanceDealRepository finance,
                              DepositNoticeRepository depositNotices, RemainingInvoiceRepository remainingInvoices,
                              AttachmentRepository attachments, TicketService ticketService) {
        this.tickets = tickets;
        this.finance = finance;
        this.depositNotices = depositNotices;
        this.remainingInvoices = remainingInvoices;
        this.attachments = attachments;
        this.ticketService = ticketService;
    }

    // ── read ────────────────────────────────────────────────────────────────────────────────

    public FinanceDealDto get(long ticketId, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        return build(ticketId, actor);
    }

    // ── money actions (each delegates, then returns the finance view) ───────────────────────

    public FinanceDealDto addComment(long ticketId, CommentRequest request, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        ticketService.financeComment(ticketId, request, actor);
        return build(ticketId, actor);
    }

    public FinanceDealDto confirmDepositPaid(long ticketId, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        ticketService.confirmDepositPaid(ticketId, actor);
        return build(ticketId, actor);
    }

    public FinanceDealDto confirmFinalPayment(long ticketId, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        ticketService.confirmFinalPayment(ticketId, actor);
        return build(ticketId, actor);
    }

    public FinanceDealDto recordPayment(long ticketId, RecordPaymentRequest request, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        ticketService.recordPayment(ticketId, request, actor);
        return build(ticketId, actor);
    }

    public FinanceDealDto confirmCloseReady(long ticketId, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        ticketService.confirmCloseReady(ticketId, actor);
        return build(ticketId, actor);
    }

    public FinanceDealDto revokeCloseConfirmation(long ticketId, String note, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        ticketService.financeRevokeCloseConfirmation(ticketId, note, actor);
        return build(ticketId, actor);
    }

    public FinanceDealDto updateStage(long ticketId, String stage, String note, UserPrincipal actor) {
        requireFinanceAccess(ticketId, actor);
        ticketService.financeUpdateStage(ticketId, stage, note, actor);
        return build(ticketId, actor);
    }

    // ── access + assembly ───────────────────────────────────────────────────────────────────

    /** Role (403), existence (404), then account's row scope (403) -- in that order. */
    private void requireFinanceAccess(long ticketId, UserPrincipal actor) {
        if (actor == null || !ALLOWED_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        tickets.findSummaryById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        if ("account".equals(actor.role()) && !tickets.isInAccountScope(ticketId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private FinanceDealDto build(long ticketId, UserPrincipal actor) {
        TicketSummaryDto s = tickets.findSummaryById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));

        var current = MoneyMilestone.of(s.salesStage());
        List<FinanceDealDto.Milestone> track = MoneyMilestone.TRACK.stream()
            .map(step -> toMilestone(step, s.depositPolicy(), current.isPresent() && current.get().index() == step.index()))
            .toList();
        FinanceDealDto.Milestone currentMilestone = track.stream().filter(FinanceDealDto.Milestone::current)
            .findFirst().orElse(null);

        var quotation = finance.findPayableQuotation(ticketId);
        List<FinanceDealDto.Item> items = quotation.map(q -> finance.findQuotationItems(q.id()))
            .filter(list -> !list.isEmpty())
            .orElseGet(() -> finance.findTicketItemsAtApprovedPrice(ticketId));

        var payments = tickets.findReceiptsByTicket(ticketId).stream()
            .map(r -> new FinanceDealDto.Payment(r.receiptId(), r.kind(), r.amount(), r.currency(), r.receivedAt(),
                r.receiptRef(), r.note(), r.depositNoticeId(), r.recordedByName()))
            .toList();
        var money = new FinanceDealDto.Money(s.amountPayable(), s.amountPaid(), s.amountOutstanding(),
            s.depositPolicy(), s.paymentStatus(), s.paymentStage(), s.fulfillmentStatus(),
            s.paymentDueDate(), s.paymentDueBasis(), s.paymentDueCreditDays(), s.overdue(),
            s.closeConfirmedAt(), s.invoiceOnFile(), s.commissionRecorded(), FinanceDealDto.INCLUDING_VAT, payments);

        List<AttachmentDto> files = attachments.findByTicketId(ticketId);
        var documents = new FinanceDealDto.Documents(
            quotation.map(q -> new FinanceDealDto.QuotationDoc(q.id(), q.number(), q.status(), q.totalAmount(),
                FinanceDealDto.EXCLUDING_VAT, q.currency(), q.issuedAt(), q.acceptedAt(),
                quotationPath(ticketId, q))).orElse(null),
            depositNotices.findByTicket(ticketId).stream().filter(d -> !"DRAFT".equals(d.status()))
                .map(d -> new FinanceDealDto.DepositNoticeDoc(d.id(), d.docNumber(), d.version(), d.status(),
                    d.issueDate(), d.depositAmount(), d.totalPayable(), FinanceDealDto.INCLUDING_VAT,
                    "/api/deposit-notices/" + d.id() + "/file"))
                .toList(),
            remainingInvoices.findByTicket(ticketId).stream().filter(r -> !"DRAFT".equals(r.status()))
                .map(r -> new FinanceDealDto.RemainingInvoiceDoc(r.id(), r.docNumber(), r.version(), r.status(),
                    r.docDate(), r.grandTotal(), FinanceDealDto.INCLUDING_VAT,
                    "/api/remaining-invoices/" + r.id() + "/file"))
                .toList(),
            files.stream().filter(a -> AttachType.INVOICE.equals(a.attachType())).map(FinanceDealService::toFileDoc).toList(),
            finance.findLiveBillingNotesForTicket(ticketId),
            files.stream().filter(a -> AttachType.PO.equals(a.attachType())).map(FinanceDealService::toFileDoc).toList(),
            files.stream().filter(a -> CONTRACT_TYPES.contains(a.attachType())).map(FinanceDealService::toFileDoc).toList());

        return new FinanceDealDto(s.id(), s.code(), s.title(), s.salesStage(), s.lifecycle(), s.status(),
            currentMilestone, track, s.customerName(), s.customerId(), s.projectName(), s.contactName(),
            items, money, documents, finance.findComments(ticketId), availableActions(ticketId, actor));
    }

    /**
     * The money actions this caller can take right now: the ticket action list ({@code TicketService.
     * financeActions} -- the same gate/readiness logic, not a second copy) narrowed to money actions, and to
     * ADVANCE_STAGE / UPDATE_STAGE only for the two money stages.
     */
    private List<FinanceDealDto.Action> availableActions(long ticketId, UserPrincipal actor) {
        // Scope was enforced up front (requireFinanceAccess); an authorised action may have moved the deal out of it.
        var all = ticketService.financeActionsAlreadyScoped(ticketId, actor).availableActions();
        boolean anyMoneyStage = all.stream().anyMatch(a -> "ADVANCE_STAGE".equals(a.action())
            && DealStage.ACCOUNT_TARGET_STAGES.contains(a.targetStage()));
        return all.stream()
            .filter(a -> MONEY_ACTIONS.contains(a.action())
                || ("ADVANCE_STAGE".equals(a.action()) && DealStage.ACCOUNT_TARGET_STAGES.contains(a.targetStage()))
                || ("UPDATE_STAGE".equals(a.action()) && anyMoneyStage))
            .map(FinanceDealService::toAction)
            .toList();
    }

    private static FinanceDealDto.Action toAction(TicketActionDto a) {
        return new FinanceDealDto.Action(a.action(), a.label(), a.targetStage(), a.requiredFields());
    }

    private static FinanceDealDto.Milestone toMilestone(MoneyMilestone.Step step, String depositPolicy, boolean current) {
        return new FinanceDealDto.Milestone(step.key(), step.index(), step.label(),
            MoneyMilestone.isSkipped(step, depositPolicy), current);
    }

    /**
     * Points at the existing download endpoint for the quotation's chain; that endpoint enforces its own authz.
     * The link must open the document the CUSTOMER received:
     * <ul>
     *   <li>{@code origin} DEAL_DIRECT / PRICING_REQUEST -&gt; the deal-quotation PDF;</li>
     *   <li>{@code origin} NULL with a {@code pricing_request_id} -&gt; the PCR-generated customer quotation
     *       ({@code CustomerQuotationService}; that chain never sets origin, which is why NULL alone is
     *       ambiguous);</li>
     *   <li>{@code origin} NULL and no pricing request -&gt; a genuine legacy ticket-native quotation.</li>
     * </ul>
     */
    private static String quotationPath(long ticketId, FinanceDealRepository.QuotationHeader q) {
        if (q.origin() != null) {
            return "/api/deal-quotations/" + q.id() + "/file?format=pdf";
        }
        if (q.pricingRequestId() != null) {
            return "/api/customer-quotations/" + q.id() + "/file?format=pdf";
        }
        return "/api/tickets/" + ticketId + "/quotations/" + q.id() + "/file?format=pdf";
    }

    private static FinanceDealDto.FileDoc toFileDoc(AttachmentDto a) {
        return new FinanceDealDto.FileDoc(a.id(), a.fileName(), a.attachType(), a.uploadedAt(), a.fileSize(),
            "/api/attachments/" + a.id() + "/file");
    }
}
