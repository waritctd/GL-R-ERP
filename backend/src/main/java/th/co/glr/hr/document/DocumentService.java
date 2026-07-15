package th.co.glr.hr.document;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

@Service
public class DocumentService {
    private static final String PREPARER = "จินตนา หาญมนตรี";
    private static final java.util.Set<String> SALES_ROLES  = java.util.Set.of("sales","admin");
    private static final java.util.Set<String> CEO_ROLES    = java.util.Set.of("ceo","admin");
    private static final java.util.Set<String> IMPORT_ROLES = java.util.Set.of("import","admin");
    private static final String REVISION_REQUESTED_TITLE = "ขอแก้ไขเอกสาร";

    private final DocumentRepository docs;
    private final TicketRepository   tickets;
    private final NotificationService notificationService;
    private final DepositNoticeRenderer renderer;

    public DocumentService(DocumentRepository docs, TicketRepository tickets,
                           NotificationService notificationService, DepositNoticeRenderer renderer) {
        this.docs          = docs;
        this.tickets       = tickets;
        this.notificationService = notificationService;
        this.renderer      = renderer;
    }

    public List<DocumentNoteTemplateDto> getNoteTemplates() {
        return docs.findNoteTemplates();
    }

    public List<DocumentDto> listByTicket(long ticketId) {
        return docs.findByTicket(ticketId);
    }

    public DocumentDto getById(long docId) {
        return docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    // Create a DRAFT from approved ticket items
    @Transactional
    public DocumentDto createDraft(long ticketId, DocumentDraftRequest req, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireApprovedTicket(ticketId, actor);

        // Auto-populate items from approved ticket items if not provided
        List<DocumentItemRequest> items = buildItemsFromRequest(req, ticketId);
        List<String> notes = req.notes() != null ? req.notes()
            : docs.findNoteTemplates().stream()
                .filter(DocumentNoteTemplateDto::defaultSelected)
                .map(DocumentNoteTemplateDto::text)
                .toList();

        var effective = new DocumentDraftRequest(
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
    public DocumentDto update(long docId, DocumentDraftRequest req, UserPrincipal actor) {
        DocumentDto doc = requireDraft(docId);
        requireTicketOwnerOrAdmin(doc.ticketId(), actor);
        docs.update(docId, req);
        return docs.findById(docId).orElseThrow();
    }

    // Returns HTML preview (PDF requires LibreOffice — mock for now)
    public String preview(long docId, UserPrincipal actor) {
        DocumentDto doc = docs.findById(docId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document not found"));
        try {
            return renderer.toPreviewHtml(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Preview failed: " + e.getMessage());
        }
    }

    // Issue: assign doc number, freeze document, transition ticket to document_issued
    @Transactional
    public DocumentDto issue(long docId, UserPrincipal actor) {
        DocumentDto doc = requireDraft(docId);
        requireRole(actor, SALES_ROLES);
        requireTicketOwnerOrAdmin(doc.ticketId(), actor);

        TicketSummaryDto s = requireApprovedTicket(doc.ticketId(), actor);

        // Render Excel at issue time
        try {
            byte[] xlsx = renderer.toXlsx(doc);
            // In production: save to file storage and set path. For now store as flag.
            docs.setFilePaths(docId, null, "rendered");
        } catch (Exception e) {
            // Non-fatal: file can be regenerated
        }

        String docNumber = docs.issue(docId, actor.id(), actor.name());

        tickets.addEvent(doc.ticketId(), actor.id(), actor.name(),
            TicketEventKind.DOCUMENT_ISSUED,
            s.status(), TicketStatus.DOCUMENT_ISSUED,
            "เอกสาร " + docNumber + " ออกแล้ว");

        return docs.findById(docId).orElseThrow();
    }

    // Download Excel bytes
    public byte[] getXlsx(long docId, UserPrincipal actor) {
        DocumentDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document not found"));
        try {
            return renderer.toXlsx(doc);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Excel render failed: " + e.getMessage());
        }
    }

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
        if (s.createdById() != actor.id() && !"admin".equals(actor.role())) {
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
            notificationService.notifyByRole("ceo", "REVISION_REQUESTED", REVISION_REQUESTED_TITLE,
                "Ticket " + s.code() + " ขอแก้ไขราคา — รออนุมัติใหม่", "/tickets/" + ticketId, true);
        } else if (req.scope() == RevisionScope.NEW_ITEM) {
            notificationService.notifyByRole("import", "REVISION_REQUESTED", REVISION_REQUESTED_TITLE,
                "Ticket " + s.code() + " มีสินค้าเพิ่มใหม่ — กรุณาตั้งราคา", "/tickets/" + ticketId, true);
        }

        return tickets.findById(ticketId).orElseThrow();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TicketSummaryDto requireApprovedTicket(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        String st = t.summary().status();
        if (!TicketStatus.APPROVED.equals(st) && !TicketStatus.DOCUMENT_ISSUED.equals(st)) {
            throw new ApiException(HttpStatus.CONFLICT, "Document can only be created for approved tickets");
        }
        return t.summary();
    }

    private DocumentDto requireDraft(long docId) {
        DocumentDto doc = docs.findById(docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document not found"));
        if (!"DRAFT".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Document is not in DRAFT status");
        }
        return doc;
    }

    private void requireTicketOwnerOrAdmin(long ticketId, UserPrincipal actor) {
        TicketDto t = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        if (t.summary().createdById() != actor.id() && !"admin".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireRole(UserPrincipal actor, java.util.Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private List<DocumentItemRequest> buildItemsFromRequest(DocumentDraftRequest req, long ticketId) {
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
                        return new DocumentItemRequest(
                            seq[0]++, desc, it.qty(), "แผ่น", price, null, price
                        );
                    })
                    .toList();
            })
            .orElse(List.of());
    }
}
