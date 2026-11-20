package th.co.glr.hr.deposit;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestController
@RequestMapping("/api")
public class DepositNoticeController {
    private final DepositNoticeService service;
    private final SessionContext  sessions;
    private final DealDocumentListService lists;

    public DepositNoticeController(DepositNoticeService service, SessionContext sessions, DealDocumentListService lists) {
        this.service  = service;
        this.sessions = sessions;
        this.lists    = lists;
    }

    /** Every deposit notice the viewer may read, across deals (the เอกสารขาย tab); optional {@code status} DRAFT|ISSUED. */
    @GetMapping("/deposit-notices")
    Map<String, List<DealDocumentRows.NoticeRow>> listAll(@RequestParam(required = false) String status, HttpSession session) {
        return Map.of("depositNotices", lists.listNotices(status, sessions.requireUser(session)));
    }

    @GetMapping("/deposit-notices/counts")
    DealDocumentRows.Counts counts(HttpSession session) {
        return lists.noticeCounts(sessions.requireUser(session));
    }

    // Note templates (route + response key preserved from the retired document/ module;
    // sales.document_note_template is shared infra that V29 deliberately did not rename)
    @GetMapping("/document-note-templates")
    Map<String, List<DocumentNoteTemplateDto>> noteTemplates(HttpSession session) {
        sessions.requireUser(session);
        return Map.of("templates", service.getNoteTemplates());
    }

    // Revision request (moved verbatim from the retired document/ module — the deposit twin service method was
    // already identical, previously unmapped)
    @PostMapping("/tickets/{ticketId}/revision")
    Map<String, Object> requestRevision(
        @PathVariable long ticketId,
        @Valid @RequestBody RevisionRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        var ticket = service.requestRevision(ticketId, req, user);
        return Map.of("ticket", ticket);
    }

    // Draft creation from ticket
    @PostMapping("/tickets/{ticketId}/deposit-notice/draft")
    Map<String, DepositNoticeDto> createDraft(
        @PathVariable long ticketId,
        @Valid @RequestBody DepositNoticeDraftRequest req,
        @RequestParam(value = "quotationId", required = false) Long quotationId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotice", quotationId == null ? service.createDraft(ticketId, req, user)
            : service.createDraft(ticketId, req, quotationId, user));
    }

    // List documents for a ticket
    @GetMapping("/tickets/{ticketId}/deposit-notices")
    Map<String, List<DepositNoticeDto>> listByTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotices", service.listByTicket(ticketId, user));
    }

    // ผู้จัดทำ choices for this deal's deposit notice / remaining invoice
    @GetMapping("/tickets/{ticketId}/document-preparer-options")
    Map<String, List<DocumentPreparerOption>> preparerOptions(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", service.documentPreparerOptions(ticketId, user));
    }

    // Get single document
    @GetMapping("/deposit-notices/{docId}")
    Map<String, DepositNoticeDto> getDoc(@PathVariable long docId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotice", service.getById(docId, user));
    }

    // Update draft
    @PutMapping("/deposit-notices/{docId}")
    Map<String, DepositNoticeDto> update(
        @PathVariable long docId,
        @Valid @RequestBody DepositNoticeDraftRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotice", service.update(docId, req, user));
    }

    // HTML preview (iframe src)
    @PostMapping("/deposit-notices/{docId}/preview")
    ResponseEntity<byte[]> preview(@PathVariable long docId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        String html = service.preview(docId, user);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // Issue (assign doc number + transition ticket)
    @PostMapping("/deposit-notices/{docId}/issue")
    Map<String, DepositNoticeDto> issue(@PathVariable long docId,
                                        @RequestBody(required = false) DepositNoticeIssueRequest body,
                                        HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotice", service.issue(docId, body == null ? null : body.expectedUpdatedAt(), user));
    }

    /** What the PDF download sheet starts from: the remembered choice, else the quotation and every PO. */
    @GetMapping("/deposit-notices/{docId}/bundle-selection")
    DocumentBundle.Selection bundleSelection(@PathVariable long docId, HttpSession session) {
        return service.bundleSelection(docId, sessions.requireUser(session));
    }

    /** The download sheet saves its choice here before downloading; the PDF GET then uses the saved choice. */
    @PutMapping("/deposit-notices/{docId}/bundle-selection")
    Map<String, Boolean> saveBundleSelection(@PathVariable long docId, @RequestBody DocumentBundle.Selection selection,
                                             HttpSession session) {
        service.saveBundleSelection(docId, sessions.requireUser(session), selection);
        return Map.of("ok", true);
    }

    // File download
    @GetMapping("/deposit-notices/{docId}/file")
    ResponseEntity<byte[]> file(
        @PathVariable long docId,
        @RequestParam(defaultValue = "pdf") String format,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        DepositNoticeDto doc = service.getById(docId, user);
        String normalized = format == null ? "pdf" : format.trim().toLowerCase();
        if ("xlsx".equals(normalized)) {
            byte[] bytes = service.getXlsx(docId, user);
            String filename = (doc.docNumber() != null ? doc.docNumber() : "draft") + ".xls";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                    "application/vnd.ms-excel"))
                .body(bytes);
        }
        DocumentBundle.Result bundle = service.getPdfBundle(docId, user);
        String filename = (doc.docNumber() != null ? doc.docNumber() : "draft") + ".pdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .headers(DocumentBundle.skippedHeader(bundle))
            .contentType(MediaType.APPLICATION_PDF)
            .body(bundle.pdf());
    }

    // Remaining invoice: prefill + preview for the download dialog (ข้อ 13.5). `quotationId` is optional — see
    // DepositNoticeService#getRemainingInvoiceOptions's own Javadoc: lets the dialog live-preview a specific
    // qualifying quotation when several exist.
    @GetMapping("/tickets/{ticketId}/remaining-invoice/options")
    Map<String, RemainingInvoiceOptionsDto> remainingInvoiceOptions(
        @PathVariable long ticketId,
        @RequestParam(required = false) Long quotationId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("options", service.getRemainingInvoiceOptions(ticketId, quotationId, user));
    }

    // Remaining invoices download through RemainingInvoiceController from issued or superseded stored snapshots.
}
