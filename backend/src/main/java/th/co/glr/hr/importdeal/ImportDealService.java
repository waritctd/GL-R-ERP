package th.co.glr.hr.importdeal;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealCommentDto;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealDto;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealItemDto;
import th.co.glr.hr.importrequest.ImportRequestService;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * The per-deal IMPORT view: one deal, projected down to what the import role works with —
 * factories, per-factory ใบขอซื้อ progress, items (never prices), read-only delivery status and
 * the comment thread. Mirrors the account finance view ruling: the role gets its own endpoint and
 * is refused the whole-deal read ({@code GET /api/tickets/{id}}, see {@code TicketService#get}).
 *
 * <p><b>Authorisation (an AUTHZ surface — real-DB tests in {@code ImportDealAuthzIntegrationTest}):</b>
 * <ol>
 *   <li>Role: {@code import} and {@code ceo} only, checked FIRST so a wrong role learns nothing
 *       about whether the deal exists.
 *   <li>Row scope, {@code import} only: the deal must be inside the SAME set the import worklist
 *       ({@code GET /api/tickets}) returns — {@link TicketRepository#isInImportScope} runs the
 *       list's own {@code appendRoleScope("import")} predicate, not a second copy of it. A deal
 *       outside it, and one that does not exist, both answer 403 (no existence oracle). {@code ceo}
 *       is not row-scoped; for the CEO a missing deal is an ordinary 404.
 * </ol>
 *
 * <p>The response DTO is built fresh field by field ({@link ImportDealDtos}) and never carries a
 * price/cost/margin/commission/weighting component — see that class.
 */
@Service
public class ImportDealService {

    static final Set<String> IMPORT_DEAL_ROLES = Set.of("import", "ceo");

    private final TicketRepository tickets;
    private final ImportRequestService importRequests;

    public ImportDealService(TicketRepository tickets, ImportRequestService importRequests) {
        this.tickets = tickets;
        this.importRequests = importRequests;
    }

    public ImportDealDto get(long ticketId, UserPrincipal actor) {
        if (!IMPORT_DEAL_ROLES.contains(actor.role())) {
            throw forbidden();
        }
        if ("import".equals(actor.role()) && !tickets.isInImportScope(ticketId)) {
            throw forbidden();
        }
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        TicketSummaryDto s = ticket.summary();

        // Same display-only fallback TicketService#get applies: sales.ticket_item stays empty
        // until order confirmation, so a deal still in its pricing/quotation phase shows the live
        // quotation's lines instead. The fallback rows carry no ticket-native price history, and
        // this projection drops prices regardless.
        List<TicketItemDto> lines = ticket.items();
        if (lines.isEmpty()) {
            lines = tickets.findPricingChainFallbackItems(ticketId);
        }

        return new ImportDealDto(
            s.id(),
            s.code(),
            s.title(),
            s.status(),
            s.lifecycle(),
            s.salesStage(),
            s.customerName(),
            s.projectName(),
            s.createdByName(),
            s.fulfillmentStatus(),
            lines.stream().map(ImportDealService::toItem).toList(),
            // Already price-free by construction (ImportRequestDtos); the service applies its own
            // read gate (import/ceo unrestricted), which this method has already narrowed.
            importRequests.list(ticketId, actor),
            // ONLY the human comment thread. The raw event feed is not exposed: price-override /
            // price-proposal events quote prices in their notes.
            ticket.events().stream()
                .filter(e -> TicketEventKind.COMMENTED.equals(e.kind()))
                .map(e -> new ImportDealCommentDto(e.id(), e.actorName(), e.message(), e.createdAt()))
                .toList());
    }

    private static ImportDealItemDto toItem(TicketItemDto i) {
        return new ImportDealItemDto(i.id(), i.brand(), i.model(), i.color(), i.texture(), i.size(),
            i.catalogProductCode(), i.qty(), i.qtySqm(), i.unitBasis(), i.qtyDelivered());
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }
}
