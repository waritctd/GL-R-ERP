package th.co.glr.hr.importrequest;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.IssueImportRequestRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetRequiredByNoteRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;

/**
 * ใบขอซื้อ (F-SM-001). Import/CEO only — enforced in {@link ImportRequestService}, never here; this
 * class only unwraps the session and shapes the response.
 *
 * <p>Two families of route, and the SINGULAR/PLURAL split is what tells them apart:
 *
 * <ul>
 *   <li>{@code /tickets/{id}/import-request…} (singular) — PREVIEW. Renders live from the deal,
 *       stores nothing, mints no number. Read-only.
 *   <li>{@code /tickets/{id}/import-requests} and {@code /import-requests/{id}…} (plural) — the
 *       STORED aggregate. These write: draft, edit, issue, revise, delete.
 * </ul>
 *
 * <p>{@code PUT /tickets/{id}/required-by-note} is the one route here that belongs to SALES rather
 * than import, and it carries its own gate inside the service.
 */
@RestController
@RequestMapping("/api")
public class ImportRequestController {

    private final ImportRequestService service;
    private final SessionContext sessions;

    public ImportRequestController(ImportRequestService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    /** Which brands this deal needs a form for — one F-SM-001 per brand. */
    @GetMapping("/tickets/{ticketId}/import-request/brands")
    Map<String, List<String>> brands(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("brands", service.brands(ticketId, user));
    }

    /** Sheet count for a brand's form, so a client can warn before downloading a 2-page form. */
    @GetMapping("/tickets/{ticketId}/import-request/pages")
    Map<String, Integer> pages(@PathVariable long ticketId,
                               @RequestParam String brand,
                               @RequestParam(required = false) String requiredBy,
                               HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("pageCount", service.pageCount(ticketId, brand, requiredBy, user));
    }

    // ── The stored aggregate ──────────────────────────────────────────────────────────────────
    // PLURAL paths ({@code /import-requests}) throughout, so nothing here can be confused with the
    // singular preview routes above: those render live from the deal and store nothing, these act on
    // real rows. The create route is ticket-scoped and every other route acts on the row's own id —
    // the same shape ProcurementController uses.

    @PostMapping("/tickets/{ticketId}/import-requests")
    Map<String, List<ImportRequestDto>> createDrafts(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequests", service.createDrafts(ticketId, user));
    }

    @GetMapping("/tickets/{ticketId}/import-requests")
    Map<String, List<ImportRequestDto>> listForTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequests", service.list(ticketId, user));
    }

    @GetMapping("/import-requests/{id}")
    Map<String, ImportRequestDto> getStored(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", service.get(id, user));
    }

    /** PATCH, not PUT: an absent field is left alone rather than blanked — see the request record. */
    @PatchMapping("/import-requests/{id}")
    Map<String, ImportRequestDto> updateStored(@PathVariable long id,
            @Valid @RequestBody UpdateImportRequestRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", service.update(id, request, user));
    }

    @PostMapping("/import-requests/{id}/issue")
    Map<String, ImportRequestDto> issue(@PathVariable long id,
            @RequestBody(required = false) IssueImportRequestRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", service.issue(id, request, user));
    }

    @PostMapping("/import-requests/{id}/revise")
    Map<String, ImportRequestDto> revise(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", service.revise(id, user));
    }

    @DeleteMapping("/import-requests/{id}")
    Map<String, String> deleteDraft(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.deleteDraft(id, user);
        return Map.of("status", "deleted");
    }

    /** A stored form printed from its OWN snapshot, not from the deal's current state. */
    @GetMapping(value = "/import-requests/{id}/file", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> storedFile(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        ImportRequestDto row = service.get(id, user);
        byte[] pdf = service.renderStored(id, user);
        String name = "IR-" + (row.docNumber() == null ? "draft-" + row.id() : row.docNumber())
            + "-" + row.brand() + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8).build().toString())
            .body(pdf);
    }

    /**
     * "กำหนดวันที่ต้องการของ" on the DEAL — the one route here that belongs to SALES, not import.
     * Gated separately inside the service; see {@code ImportRequestService.setRequiredByNote}.
     */
    @PutMapping("/tickets/{ticketId}/required-by-note")
    Map<String, String> setRequiredByNote(@PathVariable long ticketId,
            @Valid @RequestBody SetRequiredByNoteRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.setRequiredByNote(ticketId, request, user);
        return Map.of("status", "saved");
    }

    /**
     * The form itself.
     *
     * @param ref        "ReF. No." printed on the dotted rule. Optional — the business writes it by
     *                   hand today, and this build mints nothing (no sequence, no uniqueness).
     * @param requiredBy "กำหนดวันที่ต้องการของ", free text.
     */
    @GetMapping(value = "/tickets/{ticketId}/import-request", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> download(@PathVariable long ticketId,
                                    @RequestParam String brand,
                                    @RequestParam(required = false) String ref,
                                    @RequestParam(required = false) String requiredBy,
                                    HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        byte[] pdf = service.render(ticketId, brand, ref, requiredBy, user);
        // Brand and ref reach the filename, and both are caller-supplied, so the name is built with
        // ContentDisposition (which RFC-5987 encodes it) rather than string-concatenated into the
        // header — a brand containing a quote or newline would otherwise let a caller inject header
        // content. Non-ASCII survives via filename*, which is why the Thai-capable UTF-8 charset is
        // set explicitly.
        String name = "IR-" + (ref == null || ref.isBlank() ? "draft" : ref.strip())
            + "-" + brand.strip() + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8).build().toString())
            .body(pdf);
    }
}
