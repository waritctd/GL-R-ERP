package th.co.glr.hr.billing;

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
 * ใบวางบิล (billing note, GLA-99 step 3) routes — backend + renderer only. Every route below stays
 * {@code SERVER_ONLY} in {@code frontend/src/api/serverContract.test.js} until GLA-99 step 4 wires
 * a screen to it; see {@link BillingNoteService}'s own Javadoc for the authorisation these routes
 * enforce (a per-employee grant, not a role, so a plain {@code employee} row can hold it).
 */
@RestController
@RequestMapping("/api")
public class BillingNoteController {
    private final BillingNoteService service;
    private final SessionContext sessions;

    public BillingNoteController(BillingNoteService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/customers/{customerId}/billing-note-candidates")
    BillingNoteCandidatesDto candidates(@PathVariable long customerId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.candidates(customerId, user);
    }

    @GetMapping("/customers/{customerId}/billing-notes")
    Map<String, List<BillingNoteDocumentDto>> list(@PathVariable long customerId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNotes", service.list(customerId, user));
    }

    @PostMapping("/customers/{customerId}/billing-notes")
    Map<String, BillingNoteDocumentDto> createDraft(
        @PathVariable long customerId,
        @Valid @RequestBody(required = false) BillingNoteDraftRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.createDraft(customerId, req, user));
    }

    @GetMapping("/billing-notes/{id}")
    Map<String, BillingNoteDocumentDto> get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.get(id, user));
    }

    @PutMapping("/billing-notes/{id}")
    Map<String, BillingNoteDocumentDto> update(
        @PathVariable long id,
        @Valid @RequestBody(required = false) BillingNoteDraftRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.updateDraft(id, req, user));
    }

    @PostMapping("/billing-notes/{id}/issue")
    Map<String, BillingNoteDocumentDto> issue(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.issue(id, user));
    }

    @PostMapping("/billing-notes/{id}/revise")
    Map<String, BillingNoteDocumentDto> revise(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.revise(id, user));
    }

    @PostMapping("/billing-notes/{id}/cancel")
    Map<String, BillingNoteDocumentDto> cancel(
        @PathVariable long id,
        @Valid @RequestBody BillingNoteCancelRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.cancel(id, req, user));
    }

    @PostMapping("/billing-notes/{id}/mark-received")
    Map<String, BillingNoteDocumentDto> markReceived(
        @PathVariable long id,
        @Valid @RequestBody BillingNoteMarkReceivedRequest req,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.markReceived(id, req, user));
    }

    @PostMapping("/billing-notes/{id}/mark-settled")
    Map<String, BillingNoteDocumentDto> markSettled(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("billingNote", service.markSettled(id, user));
    }

    @DeleteMapping("/billing-notes/{id}")
    Map<String, Boolean> deleteDraft(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.deleteDraft(id, user);
        return Map.of("deleted", true);
    }

    @GetMapping("/billing-notes/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        // Nit fix (Opus review, GLA-99 step 3 round 1): ONE load+authorize, not two — service.file
        // now returns the doc_number the filename needs alongside the rendered bytes.
        BillingNoteService.RenderedFile rendered = service.file(id, user);
        String filename = (rendered.docNumber() != null ? rendered.docNumber() : "draft-" + id) + ".xls";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
            .body(rendered.bytes());
    }
}
