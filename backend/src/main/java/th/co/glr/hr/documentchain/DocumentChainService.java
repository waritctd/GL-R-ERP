package th.co.glr.hr.documentchain;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRenderAdapter;
import th.co.glr.hr.dealquotation.DealQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.DepositNoticeService.DealDocumentScope;
import th.co.glr.hr.deposit.RemainingInvoiceDocumentDto;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * The document chain summary and the quotation list's chain mark. Computes nothing of its own: the states are
 * {@link DocumentChainRules}', the read scope is {@link DepositNoticeService#requireDealDocumentViewer} (the deposit
 * notice / remaining invoice read gate), and the "may act" flags are the write endpoints' own authz decisions.
 */
@Service
public class DocumentChainService {

    private final DocumentChainRepository chain;
    private final TicketRepository tickets;
    private final DepositNoticeService depositNoticeService;
    private final DepositNoticeRepository depositNotices;
    private final RemainingInvoiceRepository remainingInvoices;
    private final DealQuotationService quotations;

    public DocumentChainService(DocumentChainRepository chain, TicketRepository tickets,
                                DepositNoticeService depositNoticeService, DepositNoticeRepository depositNotices,
                                RemainingInvoiceRepository remainingInvoices, DealQuotationService quotations) {
        this.chain = chain;
        this.tickets = tickets;
        this.depositNoticeService = depositNoticeService;
        this.depositNotices = depositNotices;
        this.remainingInvoices = remainingInvoices;
        this.quotations = quotations;
    }

    /**
     * {@code GET /api/tickets/{ticketId}/document-chain}. Gated FIRST, before any read, by exactly the gate of
     * {@code GET /tickets/{id}/deposit-notices} and {@code GET /tickets/{id}/remaining-invoices}; the document rows
     * below are then the very rows those two endpoints return this viewer (each is that gate + findByTicket).
     */
    public DocumentChainDto forTicket(long ticketId, UserPrincipal actor) {
        depositNoticeService.requireDealDocumentViewer(ticketId, actor);
        TicketSummaryDto s = tickets.findSummaryById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
        List<DepositNoticeDto> notices = depositNotices.findByTicket(ticketId);
        List<RemainingInvoiceDocumentDto> invoices = remainingInvoices.findByTicket(ticketId);

        DocumentChainRules.Facts facts = new DocumentChainRules.Facts(s.status(), s.lifecycle(), s.depositPolicy(),
            s.paymentStatus(), s.fulfillmentStatus(),
            notices.stream().anyMatch(d -> "DRAFT".equals(d.status())),
            notices.stream().anyMatch(d -> "ISSUED".equals(d.status())),
            invoices.stream().anyMatch(r -> "DRAFT".equals(r.status())),
            invoices.stream().anyMatch(r -> "ISSUED".equals(r.status())));

        // ── order ──
        boolean promoted = tickets.hasPromotionFromQuotationEvent(ticketId);
        DealQuotationDto confirmedRow = confirmedQuotationVisibleTo(ticketId, actor);
        boolean confirmed = DocumentChainRules.orderConfirmed(facts);
        // account reads no ticket events at all (TicketService#requireViewAccess refuses it outright), so it gets no
        // confirmation time; every other role that passes the gate above reads the deal's events on the deal page.
        Instant confirmedAt = confirmed && !"account".equals(actor.role())
            ? chain.findOrderConfirmedAt(ticketId).orElse(null) : null;
        boolean confirmable = TicketStatus.DRAFT.equals(s.status()) && DealLifecycle.ACTIVE.equals(s.lifecycle())
            && !promoted;
        DocumentChainDto.Order order = new DocumentChainDto.Order(confirmed, confirmable,
            confirmedRow == null ? null : confirmedRow.id(), confirmedRow == null ? null : confirmedRow.number(),
            confirmedAt, promoted && quotations.isOrderConfirmationUndoable(s));

        // ── deposit notice: the deal page's pick — the DRAFT, else the latest ISSUED ──
        DepositNoticeDto draftNotice = notices.stream().filter(d -> "DRAFT".equals(d.status())).findFirst().orElse(null);
        DepositNoticeDto issuedNotice = notices.stream().filter(d -> "ISSUED".equals(d.status()))
            .max(Comparator.comparingInt(DepositNoticeDto::version)).orElse(null);
        DepositNoticeDto picked = draftNotice != null ? draftNotice : issuedNotice;
        Integer percent = null;
        if (picked != null && picked.depositPercent() != null) {
            percent = picked.depositPercent().movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();
        } else if (confirmedRow != null) {
            percent = DealQuotationRenderAdapter.effectiveDepositPercent(confirmedRow.depositPercent());
        }
        String depositState = DocumentChainRules.depositState(facts);
        DocumentChainDto.Deposit deposit = new DocumentChainDto.Deposit(s.depositPolicy(),
            !DepositPolicy.bypassesDepositNotice(s.depositPolicy()), percent, depositState,
            DocumentChainRules.depositBlockedReason(facts),
            picked == null ? null : picked.id(), issuedNotice == null ? null : issuedNotice.id(),
            issuedNotice == null ? null : issuedNotice.docNumber(), issuedNotice == null ? null : issuedNotice.issueDate(),
            draftNotice != null, DocumentChainRules.PAID.equals(depositState));

        // ── remaining invoice: the deal page's pick — the ISSUED, else the DRAFT ──
        RemainingInvoiceDocumentDto invoice = invoices.stream().filter(r -> "ISSUED".equals(r.status())).findFirst()
            .orElseGet(() -> invoices.stream().filter(r -> "DRAFT".equals(r.status())).findFirst().orElse(null));
        String remainingState = DocumentChainRules.remainingState(facts);
        String remainingReason = DocumentChainRules.remainingBlockedReason(facts);
        DocumentChainDto.Remaining remaining = new DocumentChainDto.Remaining(remainingState, remainingReason,
            DocumentChainRules.TO_CREATE.equals(remainingState) && remainingReason == null,
            invoice == null ? null : invoice.id(), invoice == null ? null : invoice.docNumber(),
            invoice == null ? null : invoice.docDate(), facts.invoiceDraft());

        // ── who may act: the write endpoints' own decisions ──
        boolean mayWriteDocuments = depositNoticeService.mayWriteDealDocuments(s, actor);
        DocumentChainDto.Viewer viewer = new DocumentChainDto.Viewer(
            quotations.hasEditAccess(actor, s), mayWriteDocuments, mayWriteDocuments);

        return new DocumentChainDto(ticketId, s.code(), s.status(), s.lifecycle(), order, deposit, remaining, viewer);
    }

    /** The quotation the order was confirmed from, only when THIS viewer can see it: the deal page's own rule
     * (DealQuotationService#listForTicket + #orderConfirmedQuotationId); null when the viewer cannot list the deal's
     * quotations at all (account reaching a dead deal only through its issued notice). */
    private DealQuotationDto confirmedQuotationVisibleTo(long ticketId, UserPrincipal actor) {
        List<DealQuotationDto> visible;
        try {
            visible = quotations.listForTicket(ticketId, actor);
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.FORBIDDEN) return null;
            throw e;
        }
        Long id = quotations.orderConfirmedQuotationId(ticketId, visible);
        return id == null ? null : visible.stream().filter(row -> row.id() == id).findFirst().orElse(null);
    }

    /**
     * The quotation list's per-row chain mark, for the rows of ONE list response: only APPROVED direct quotations, and
     * only on deals whose documents this viewer may read ({@link DepositNoticeService#dealDocumentScope}, applied in
     * the WHERE clause). One statement for the whole page.
     */
    public Map<Long, String> listStages(List<DealQuotationDto> rows, UserPrincipal actor) {
        DealDocumentScope scope = DepositNoticeService.dealDocumentScope(actor.role());
        if (scope == DealDocumentScope.NONE) return Map.of();
        List<Long> ids = rows.stream()
            .filter(row -> DealQuotationRepository.ORIGIN_DEAL_DIRECT.equals(row.origin())
                && QuotationStatus.APPROVED.equals(row.docStatus()))
            .map(DealQuotationDto::id).toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>();
        chain.findListFacts(ids, scope, actor.id()).forEach((quotationId, f) -> {
            String stage = DocumentChainRules.listStage(f.facts(), f.promoted(), f.confirmedQuotationId(), quotationId);
            if (stage != null) out.put(quotationId, stage);
        });
        return out;
    }
}
