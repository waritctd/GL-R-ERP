package th.co.glr.hr.deposit;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Arrays;
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
            String filename = (doc.docNumber() != null ? doc.docNumber() : "draft") + ".xls";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                    "application/vnd.ms-excel"))
                .body(bytes);
        }
        byte[] bytes = service.getPdf(docId, user);
        String filename = (doc.docNumber() != null ? doc.docNumber() : "draft") + ".pdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }

    // Remaining invoice: prefill + preview for the download dialog (ข้อ 13.5). Same viewer gate
    // and status check as the /file download below (service enforces both) — this never throws
    // for an over-capacity item list, only reports itemCount/maxItems, so the dialog can always
    // render a preview alongside a capacity refusal instead of a failed round trip.
    // `quotationId` is optional — see DepositNoticeService#getRemainingInvoiceOptions's own
    // Javadoc: lets the dialog live-preview a specific qualifying quotation when several exist.
    @GetMapping("/tickets/{ticketId}/remaining-invoice/options")
    Map<String, RemainingInvoiceOptionsDto> remainingInvoiceOptions(
        @PathVariable long ticketId,
        @RequestParam(required = false) Long quotationId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("options", service.getRemainingInvoiceOptions(ticketId, quotationId, user));
    }

    // Remaining invoice download (ข้อ 13.5). Every query param is optional — a bare call (no
    // params at all) reproduces the dialog's own defaults, so one-click download keeps working.
    // Tri-state semantics, matched to how Spring binds an unset vs. an explicitly-empty
    // @RequestParam: `reference`/`depositReference` ABSENT (null) => use the default; PRESENT but
    // empty ("") => leave that cell blank; PRESENT non-empty => use exactly that value. `noteIds`
    // is parsed by hand (not List<Long>) for the same reason: Spring's collection conversion does
    // not reliably distinguish "absent" from "present but empty" the way a raw String does.
    //
    // `issueDate`/`noteIds` used to be parsed with LocalDate.parse/Long.valueOf directly, which
    // throw DateTimeParseException/NumberFormatException — neither is mapped by
    // ApiExceptionHandler, so a malformed value surfaced as an opaque 500. Both are now caught and
    // turned into a 400 with a Thai message (finding 3). `reference`/`depositReference` are also
    // bounded to a sane length here — the same @Size(max=255) DepositNoticeDraftRequest already
    // enforces for the deposit notice's own free-text fields — since these two are unvalidated
    // @RequestParam strings, not a @Valid @RequestBody.
    @GetMapping("/tickets/{ticketId}/remaining-invoice/file")
    ResponseEntity<byte[]> remainingInvoiceFile(
        @PathVariable long ticketId,
        @RequestParam(required = false) String reference,
        @RequestParam(required = false) String depositReference,
        @RequestParam(required = false) String issueDate,
        @RequestParam(required = false) String noteIds,
        @RequestParam(required = false) Long quotationId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        requireReasonableLength("reference", reference);
        requireReasonableLength("depositReference", depositReference);
        LocalDate parsedIssueDate = parseIssueDate(issueDate);
        List<Long> parsedNoteIds = parseNoteIds(noteIds);
        byte[] bytes = service.getRemainingInvoiceXlsx(
            ticketId, user, reference, depositReference, parsedIssueDate, parsedNoteIds, quotationId);
        String filename = "remaining-invoice-" + ticketId + ".xls";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType(
                "application/vnd.ms-excel"))
            .body(bytes);
    }

    private static final int MAX_QUERY_PARAM_LENGTH = 100;

    private void requireReasonableLength(String fieldName, String value) {
        if (value != null && value.length() > MAX_QUERY_PARAM_LENGTH) {
            throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                fieldName + " ยาวเกินไป (สูงสุด " + MAX_QUERY_PARAM_LENGTH + " ตัวอักษร)");
        }
    }

    private LocalDate parseIssueDate(String issueDate) {
        if (issueDate == null || issueDate.isBlank()) return null;
        try {
            return LocalDate.parse(issueDate);
        } catch (java.time.format.DateTimeParseException e) {
            throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "รูปแบบวันที่ไม่ถูกต้อง (ต้องเป็น YYYY-MM-DD)");
        }
    }

    // null (param absent) => "use the default" (DepositNoticeService resolves the
    // defaultSelected note templates); "" (param present, empty) => no notes at all;
    // "1,2,3" => exactly those template ids.
    private List<Long> parseNoteIds(String raw) {
        if (raw == null) return null;
        if (raw.isBlank()) return List.of();
        try {
            return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(Long::valueOf)
                .toList();
        } catch (NumberFormatException e) {
            throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "รูปแบบรายการหมายเหตุไม่ถูกต้อง");
        }
    }
}
