package th.co.glr.hr.deposit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.deposit.DealDocumentListRepository.Viewer;
import th.co.glr.hr.deposit.DealDocumentRows.Counts;
import th.co.glr.hr.deposit.DealDocumentRows.InvoiceRef;
import th.co.glr.hr.deposit.DealDocumentRows.InvoiceRow;
import th.co.glr.hr.deposit.DealDocumentRows.NoticeRef;
import th.co.glr.hr.deposit.DealDocumentRows.NoticeRow;
import th.co.glr.hr.deposit.DealDocumentRows.Paged;
import th.co.glr.hr.deposit.DealDocumentRows.QuotationDocuments;
import th.co.glr.hr.deposit.DepositNoticeService.DealDocumentScope;
import th.co.glr.hr.ticket.TicketRepository;

/** The เอกสารขาย tabs' reads. Who sees what is DepositNoticeService#dealDocumentScope (the per-deal read rule, applied
 * in SQL); on top of it account and ceo never see a DRAFT (Ploy, 2026-11-20). */
@Service
public class DealDocumentListService {
    private static final Set<String> STATUS_FILTERS = Set.of("DRAFT", "ISSUED");
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final DealDocumentListRepository repo;
    private final TicketRepository tickets;

    public DealDocumentListService(DealDocumentListRepository repo, TicketRepository tickets) {
        this.repo = repo;
        this.tickets = tickets;
    }

    private Viewer viewer(UserPrincipal actor) {
        boolean grant = tickets.holdsAllSalesDocumentsGrant(actor);
        DealDocumentScope scope = grant || th.co.glr.hr.auth.OversightReader.holder(actor, tickets::holdsReadAllSalesGrant)
            ? DealDocumentScope.ALL : DepositNoticeService.dealDocumentScope(actor.role()); // the read-all grant reads every deal; writesEverywhere stays the documents grant only
        return new Viewer(scope, actor.id(),
            !grant && ("ceo".equals(actor.role()) || "account".equals(actor.role())), grant, "sales".equals(actor.role()));
    }

    private Viewer requireViewer(UserPrincipal actor) {
        Viewer v = viewer(actor);
        if (v.scope() == DealDocumentScope.NONE) throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        return v;
    }

    private static String requireStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.trim().toUpperCase();
        if (!STATUS_FILTERS.contains(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "สถานะต้องเป็น DRAFT หรือ ISSUED");
        }
        return s;
    }

    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 1000;

    private static int limitOf(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    public Paged<NoticeRow> listNotices(String status, Integer limit, Integer offset, UserPrincipal actor) {
        return repo.findNotices(requireViewer(actor), requireStatus(status), limitOf(limit), offset == null ? 0 : Math.max(0, offset));
    }

    public Counts noticeCounts(UserPrincipal actor) {
        return repo.countNotices(requireViewer(actor));
    }

    public Paged<InvoiceRow> listInvoices(String status, Integer limit, Integer offset, UserPrincipal actor) {
        return repo.findInvoices(requireViewer(actor), requireStatus(status), limitOf(limit), offset == null ? 0 : Math.max(0, offset));
    }

    public Counts invoiceCounts(UserPrincipal actor) {
        return repo.countInvoices(requireViewer(actor));
    }

    /** The {@code documents} of each row of ONE quotation list response, keyed by quotation id: two statements for the
     * whole page. A viewer who may not read these deals' documents gets none (no error: the list itself is allowed). */
    public Map<Long, QuotationDocuments> summarize(Map<Long, Long> ticketIdByQuotationId, UserPrincipal actor) {
        Viewer v = viewer(actor);
        if (v.scope() == DealDocumentScope.NONE || ticketIdByQuotationId.isEmpty()) return Map.of();
        List<Long> ticketIds = ticketIdByQuotationId.values().stream().distinct().toList();
        Map<Long, List<DealDocumentListRepository.LeanNotice>> notices = repo.findLeanNotices(v, ticketIds).stream()
            .collect(Collectors.groupingBy(DealDocumentListRepository.LeanNotice::ticketId));
        List<DealDocumentListRepository.LeanInvoice> leanInvoices = repo.findLeanInvoices(v, ticketIds);
        Map<Long, List<InvoiceRef>> invoices = leanInvoices.stream()
            .collect(Collectors.groupingBy(DealDocumentListRepository.LeanInvoice::quotationId,
                Collectors.mapping(DealDocumentListRepository.LeanInvoice::ref, Collectors.toList())));
        Map<Long, Boolean> invoiceMayWrite = new HashMap<>();
        leanInvoices.forEach(i -> invoiceMayWrite.put(i.quotationId(), i.mayWrite()));
        Map<Long, QuotationDocuments> out = new HashMap<>();
        for (Map.Entry<Long, Long> q : ticketIdByQuotationId.entrySet()) {
            long quotationId = q.getKey();
            // one notice per deal; if a stale DRAFT ever sits beside an ISSUED one, the ISSUED one speaks for the deal
            var notice = notices.getOrDefault(q.getValue(), List.of()).stream()
                .min((a, b) -> Boolean.compare("ISSUED".equals(b.status()), "ISSUED".equals(a.status()))).orElse(null);
            List<InvoiceRef> refs = invoices.getOrDefault(quotationId, List.of());
            if (notice == null && refs.isEmpty()) continue;
            // the flag is per deal, so any one of the deal's documents carries it
            boolean mayWrite = notice != null ? notice.mayWrite()
                : invoiceMayWrite.getOrDefault(quotationId, false);
            out.put(quotationId, new QuotationDocuments(notice == null ? null
                : new NoticeRef(notice.id(), notice.status(), notice.docNumber(),
                    Objects.equals(notice.quotationId(), quotationId)), refs, mayWrite));
        }
        return out;
    }
}
