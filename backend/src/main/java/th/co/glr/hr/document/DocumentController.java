package th.co.glr.hr.document;

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
public class DocumentController {
    private final DocumentService service;
    private final SessionContext  sessions;

    public DocumentController(DocumentService service, SessionContext sessions) {
        this.service  = service;
        this.sessions = sessions;
    }

    // Note templates
    @GetMapping("/document-note-templates")
    Map<String, List<DocumentNoteTemplateDto>> noteTemplates() {
        return Map.of("templates", service.getNoteTemplates());
    }

    // Draft creation from ticket
    @PostMapping("/tickets/{ticketId}/document/draft")
    Map<String, DocumentDto> createDraft(
        @PathVariable long ticketId,
        @RequestBody DocumentDraftRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("document", service.createDraft(ticketId, req, user));
    }

    // List documents for a ticket
    @GetMapping("/tickets/{ticketId}/documents")
    Map<String, List<DocumentDto>> listByTicket(@PathVariable long ticketId) {
        return Map.of("documents", service.listByTicket(ticketId));
    }

    // Get single document
    @GetMapping("/documents/{docId}")
    Map<String, DocumentDto> getDoc(@PathVariable long docId) {
        return Map.of("document", service.getById(docId));
    }

    // Update draft
    @PutMapping("/documents/{docId}")
    Map<String, DocumentDto> update(
        @PathVariable long docId,
        @RequestBody DocumentDraftRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("document", service.update(docId, req, user));
    }

    // HTML preview (iframe src)
    @PostMapping("/documents/{docId}/preview")
    ResponseEntity<byte[]> preview(@PathVariable long docId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        String html = service.preview(docId, user);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // Issue (assign doc number + transition ticket)
    @PostMapping("/documents/{docId}/issue")
    Map<String, DocumentDto> issue(@PathVariable long docId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("document", service.issue(docId, user));
    }

    // File download
    @GetMapping("/documents/{docId}/file")
    ResponseEntity<byte[]> file(
        @PathVariable long docId,
        @RequestParam(defaultValue = "xlsx") String format,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        byte[] bytes = service.getXlsx(docId, user);
        DocumentDto doc = service.getById(docId);
        String filename = (doc.docNumber() != null ? doc.docNumber() : "draft") + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    // Revision request (Part A)
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
}
