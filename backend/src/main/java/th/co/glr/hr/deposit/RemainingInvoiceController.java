package th.co.glr.hr.deposit;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * The STORED ใบแจ้งหนี้ส่วนที่เหลือ (GLA-99 step 2) — DRAFT/ISSUED/SUPERSEDED lifecycle routes.
 * The pre-existing stateless preview route ({@code GET /tickets/{ticketId}/remaining-invoice/
 * options} on {@link DepositNoticeController}) is UNCHANGED and stays in place: it is still how a
 * caller previews what a NEW draft would snapshot before creating one, and remains the source this
 * class's own writes snapshot from — see {@link RemainingInvoiceService}'s own class Javadoc. Its
 * sibling stateless {@code .../remaining-invoice/file} route is GONE (owner ruling O1, GLA-99 step
 * 2 review-round-1, 2026-09-20) — only an ISSUED/SUPERSEDED STORED document is downloadable now,
 * via THIS class's own {@code GET /remaining-invoices/{id}/file} below.
 */
@RestController
@RequestMapping("/api")
public class RemainingInvoiceController {
    private final RemainingInvoiceService service;
    private final SessionContext sessions;

    public RemainingInvoiceController(RemainingInvoiceService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/tickets/{ticketId}/remaining-invoices")
    Map<String, List<RemainingInvoiceDocumentDto>> list(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("remainingInvoices", service.list(ticketId, user));
    }

    @PostMapping("/tickets/{ticketId}/remaining-invoices")
    Map<String, RemainingInvoiceDocumentDto> createDraft(
        @PathVariable long ticketId,
        @Valid @RequestBody(required = false) RemainingInvoiceDraftRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("remainingInvoice", service.createDraft(ticketId, req, user));
    }

    @GetMapping("/remaining-invoices/{id}")
    Map<String, RemainingInvoiceDocumentDto> get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("remainingInvoice", service.get(id, user));
    }

    @PutMapping("/remaining-invoices/{id}")
    Map<String, RemainingInvoiceDocumentDto> update(
        @PathVariable long id,
        @Valid @RequestBody(required = false) RemainingInvoiceDraftRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("remainingInvoice", service.updateDraft(id, req, user));
    }

    @PostMapping("/remaining-invoices/{id}/issue")
    Map<String, RemainingInvoiceDocumentDto> issue(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("remainingInvoice", service.issue(id, user));
    }

    @PostMapping("/remaining-invoices/{id}/revise")
    Map<String, RemainingInvoiceDocumentDto> revise(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("remainingInvoice", service.revise(id, user));
    }

    @DeleteMapping("/remaining-invoices/{id}")
    Map<String, Boolean> deleteDraft(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.deleteDraft(id, user);
        return Map.of("deleted", true);
    }

    @GetMapping("/remaining-invoices/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        RemainingInvoiceDocumentDto doc = service.get(id, user);
        byte[] bytes = service.file(id, user);
        String filename = (doc.docNumber() != null ? doc.docNumber() : "draft-" + id) + ".xls";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
            .body(bytes);
    }
}
