package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

@Service
public class DepositNoticeService {
    private static final String PREPARER = "จินตนา หาญมนตรี";
    private static final java.util.Set<String> SALES_ROLES  = java.util.Set.of("sales");
    private static final java.util.Set<String> CEO_ROLES    = java.util.Set.of("ceo");
    private static final java.util.Set<String> IMPORT_ROLES = java.util.Set.of("import");

    private final DepositNoticeRepository docs;
    private final TicketRepository   tickets;
    private final NotificationRepository notifications;
    private final DepositNoticeRenderer renderer;
    private final RemainingInvoiceRenderer remainingRenderer;

    public DepositNoticeService(DepositNoticeRepository docs, TicketRepository tickets,
                           NotificationRepository notifications, DepositNoticeRenderer renderer,
                           RemainingInvoiceRenderer remainingRenderer) {
        this.docs              = docs;
        this.tickets           = tickets;
        this.notifications     = notifications;
        this.renderer          = renderer;
        this.remainingRenderer = remainingRenderer;
    }

    public List<DocumentNoteTemplateDto> getNoteTemplates() {
        return docs.findNoteTemplates();
    }

    public List<DepositNoticeDto> listByTicket(long ticketId) {
        return docs.findByTicket(ticketId);
    }

    public DepositNoticeDto getById(long docId) {
        return docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deposit notice not found"));
    }

    // Create a DRAFT from approved ticket items
    @Transactional
    public DepositNoticeDto createDraft(long ticketId, DepositNoticeDraftRequest req, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireApprovedTicket(ticketId, actor);

        // Auto-populate items from approved ticket items if not provided
        List<DepositNoticeItemRequest> items = buildItemsFromRequest(req, ticketId);
        List<String> notes = req.notes() != null ? req.notes()
            : docs.findNoteTemplates().stream()
                .filter(DocumentNoteTemplateDto::defaultSelected)
                .map(DocumentNoteTemplateDto::text)
                .toList();

        var effective = new DepositNoticeDraftRequest(
            req.customerName() != null ? req.customerName() : s.customerName(),
            req.customerTaxId(), req.customerAddress(),
            req.projectName(), req.reference(),
            req.depositPercent() != null ? req.depositPercent() : new BigDecimal("0.50"),
            notes, items
        );

        long docId = docs.createDraft(ticketId, effective, items);
        return docs.findById(docId).orElseThrow();
    }

    @Transactional
    public DepositNoticeDto update(long docId, DepositNoticeDraftRequest req, UserPrincipal actor) {
        DepositNoticeDto doc = requireDraft(docId);
        requireTicketOwner(doc.ticketId(), actor);
        docs.update(docId, req);
        return docs.findById(docId).orElseThrow();
    }

    // Returns HTML preview (PDF requires LibreOffice — mock for now)
    public String preview(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = docs.findById(docId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deposit notice not found"));
        try {
            return renderer.toPreviewHtml(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Preview failed: " + e.getMessage());
        }
    }

    // Issue: assign doc number, freeze document, transition ticket to document_issued
    @Transactional
    public DepositNoticeDto issue(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = requireDraft(docId);
        requireRole(actor, SALES_ROLES);
        requireTicketOwner(doc.ticketId(), actor);

        TicketSummaryDto s = requireApprovedTicket(doc.ticketId(), actor);

        String docNumber = docs.issue(docId, actor.id(), actor.name());

        // Render downloadable files at issue time. For now the DB stores render flags; bytes
        // remain regenerable from the persisted document snapshot.
        try {
            DepositNoticeDto issued = docs.findById(docId).orElseThrow();
            renderer.toPdf(issued);
            renderer.toXlsx(issued);
            docs.setFilePaths(docId, "rendered", "rendered");
        } catch (Exception e) {
            // Non-fatal: files can be regenerated on download.
        }

        tickets.addEvent(doc.ticketId(), actor.id(), actor.name(),
            TicketEventKind.DOCUMENT_ISSUED,
            s.status(), TicketStatus.DOCUMENT_ISSUED,
            "เอกสาร " + docNumber + " ออกแล้ว");

        return docs.findById(docId).orElseThrow();
    }

    // Download Excel bytes
    public byte[] getXlsx(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deposit notice not found"));
        try {
            return renderer.toXlsx(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Excel render failed: " + e.getMessage());
        }
    }

    public byte[] getPdf(long docId, UserPrincipal actor) {
        DepositNoticeDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deposit notice not found"));
        try {
            return renderer.toPdf(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF render failed: " + e.getMessage());
        }
    }

    // ── Remaining Invoice (ข้อ 13.5) ─────────────────────────────────────────

    public byte[] getRemainingInvoiceXlsx(long ticketId, UserPrincipal actor) {
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TicketSummaryDto s = ticket.summary();
        if (!"quotation_issued".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Remaining invoice only available for quotation_issued tickets");
        }

        // Find issued deposit notice to get deposit amount and reference
        BigDecimal depositAmount = BigDecimal.ZERO;
        String depositRef = null;
        List<DepositNoticeDto> notices = docs.findByTicket(ticketId);
        DepositNoticeDto issued = notices.stream()
            .filter(n -> "ISSUED".equals(n.status()))
            .findFirst()
            .orElse(null);
        if (issued != null) {
            depositAmount = issued.depositAmount() != null ? issued.depositAmount() : BigDecimal.ZERO;
            depositRef = issued.docNumber();
        }

        // Build items from approved ticket items
        int[] seq = {1};
        List<RemainingInvoiceItemDto> items = ticket.items().stream()
            .filter(it -> it.approvedPrice() != null)
            .map(it -> {
                String desc = java.util.stream.Stream.of(
                    it.brand(), it.model(), it.color(), it.texture(), it.size()
                ).filter(v -> v != null && !v.isBlank())
                 .reduce((a, b) -> a + " " + b).orElse(nullSafe(it.brand()));
                String unit = "SQM".equals(it.unitBasis()) ? "ตร.ม." : "แผ่น";
                BigDecimal qty = "SQM".equals(it.unitBasis()) && it.qtySqm() != null
                    ? it.qtySqm() : (it.qty() != null ? it.qty() : BigDecimal.ZERO);
                return new RemainingInvoiceItemDto(seq[0]++, desc, qty, unit, it.approvedPrice());
            })
            .toList();

        // Build a running doc number (GLR + thai year 2 digits + running — sequential per ticket for now)
        int thaiYear = java.time.Year.now().getValue() + 543;
        String docNumber = "GLR" + (thaiYear % 100) + String.format("%03d", ticketId);

        // Get quotation reference
        String quotationRef = depositRef;
        if (quotationRef == null && !notices.isEmpty()) {
            quotationRef = notices.get(0).reference();
        }

        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            docNumber,
            java.time.LocalDate.now(),
            quotationRef,
            nullSafe(s.customerName()),
            "",   // address not in TicketSummaryDto — left blank
            "",
            nullSafe(s.projectName()),
            depositAmount,
            items
        );

        try {
            return remainingRenderer.toXlsx(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Excel render failed: " + e.getMessage());
        }
    }

    private String nullSafe(String s) { return s != null ? s : ""; }

    // Revision flow (Part A of plan)
    @Transactional
    public TicketDto requestRevision(long ticketId, RevisionRequest req, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        TicketSummaryDto s = ticket.summary();
        String st = s.status();

        if (!TicketStatus.APPROVED.equals(st) && !TicketStatus.DOCUMENT_ISSUED.equals(st)) {
            throw new ApiException(HttpStatus.CONFLICT, "Revision only allowed from approved or document_issued");
        }
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only ticket owner can request revision");
        }

        String toStatus = switch (req.scope()) {
            case QTY_OR_NOTE   -> TicketStatus.APPROVED;          // stays approved, new document version
            case PRICE_CHANGE  -> TicketStatus.PRICE_PROPOSED;    // CEO re-approves
            case NEW_ITEM      -> TicketStatus.IN_REVIEW;         // Import re-prices
        };

        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.REVISION_REQUESTED, st, toStatus,
            "[" + req.scope().name() + "] " + req.reason());

        if (req.scope() == RevisionScope.PRICE_CHANGE) {
            notifications.notifyByRole("ceo", ticketId, "REVISION_REQUESTED",
                "Ticket " + s.code() + " ขอแก้ไขราคา — รออนุมัติใหม่");
        } else if (req.scope() == RevisionScope.NEW_ITEM) {
            notifications.notifyByRole("import", ticketId, "REVISION_REQUESTED",
                "Ticket " + s.code() + " มีสินค้าเพิ่มใหม่ — กรุณาตั้งราคา");
        }

        return tickets.findById(ticketId).orElseThrow();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TicketSummaryDto requireApprovedTicket(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        String st = t.summary().status();
        if (!TicketStatus.APPROVED.equals(st) && !TicketStatus.QUOTATION_ISSUED.equals(st)
                && !TicketStatus.DOCUMENT_ISSUED.equals(st)) {
            throw new ApiException(HttpStatus.CONFLICT, "Deposit notice can only be created for approved tickets");
        }
        return t.summary();
    }

    private DepositNoticeDto requireDraft(long docId) {
        DepositNoticeDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deposit notice not found"));
        if (!"DRAFT".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Deposit notice is not in DRAFT status");
        }
        return doc;
    }

    private void requireTicketOwner(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        if (t.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireRole(UserPrincipal actor, java.util.Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private List<DepositNoticeItemRequest> buildItemsFromRequest(DepositNoticeDraftRequest req, long ticketId) {
        if (req.items() != null && !req.items().isEmpty()) return req.items();
        // Auto-build from approved ticket items
        return tickets.findById(ticketId)
            .map(t -> {
                int[] seq = {1};
                return t.items().stream()
                    .filter(it -> it.approvedPrice() != null)
                    .map(it -> {
                        String desc = List.of(
                            it.brand(), it.model(), it.color(), it.texture(), it.size()
                        ).stream().filter(v -> v != null && !v.isBlank())
                            .reduce((a, b) -> a + " " + b).orElse(it.brand());
                        BigDecimal price = it.approvedPrice();
                        return new DepositNoticeItemRequest(
                            seq[0]++, desc, it.qty(), "แผ่น", price, null, price
                        );
                    })
                    .toList();
            })
            .orElse(List.of());
    }
}
