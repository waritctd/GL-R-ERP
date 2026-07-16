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

    public DepositNoticeController(DepositNoticeService service, SessionContext sessions) {
        this.service  = service;
        this.sessions = sessions;
    }

    // Note templates (route + response key preserved from the retired document/ module;
    // sales.document_note_template is shared infra that V29 deliberately did not rename)
    @GetMapping("/document-note-templates")
    Map<String, List<DocumentNoteTemplateDto>> noteTemplates(HttpSession session) {
        sessions.requireUser(session);
        return Map.of("templates", service.getNoteTemplates());
    }

    // Revision request (moved verbatim from the retired document/ module — the deposit
    // twin service method was already identical, previously unmapped)
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
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotice", service.createDraft(ticketId, req, user));
    }

    // List documents for a ticket
    @GetMapping("/tickets/{ticketId}/deposit-notices")
    Map<String, List<DepositNoticeDto>> listByTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotices", service.listByTicket(ticketId, user));
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
    Map<String, DepositNoticeDto> issue(@PathVariable long docId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("depositNotice", service.issue(docId, user));
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
            String filename = (doc.docNumber() != null ? doc.docNumber() : "draft") + ".xlsx";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
        }
        byte[] bytes = service.getPdf(docId, user);
        String filename = (doc.docNumber() != null ? doc.docNumber() : "draft") + ".pdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }

    // Remaining invoice download (ข้อ 13.5)
    @GetMapping("/tickets/{ticketId}/remaining-invoice/file")
    ResponseEntity<byte[]> remainingInvoiceFile(
        @PathVariable long ticketId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        byte[] bytes = service.getRemainingInvoiceXlsx(ticketId, user);
        String filename = "remaining-invoice-" + ticketId + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
}
