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
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestCoverageResponse;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.CreateImportRequestsRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.IssueImportRequestRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetLeadTimeRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.SetRequiredByNoteRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateEmailDraftRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;

/** ใบขอซื้อ (F-SM-001). Authorisation is enforced in ImportRequestService, never here; this class only unwraps
 * the session and shapes the response. Read-only, import/ceo only (ImportRequestService#IR_ROLES).
 * /tickets/{id/import-requests} and /import-requests/{id…} (plural) — the STORED aggregate. */
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

    // The stored aggregate ────────────────────────────────────────────────────────────────── PLURAL paths
    // (/import-requests) throughout, so nothing here can be confused with the singular preview routes above: those
    // render live from the deal and store nothing, these act on real rows.

    /** request is required = false: the common case (every line resolves to an EXISTING factory) needs no body at
     * all. newFactoryCountries (owner decision 09-18) only matters when the deal's lines need a factory
     * auto-created on the fly — see ImportRequestService#resolveFactoryGroups. */
    @PostMapping("/tickets/{ticketId}/import-requests")
    Map<String, List<ImportRequestDto>> createDrafts(@PathVariable long ticketId,
            @Valid @RequestBody(required = false) CreateImportRequestsRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequests", service.createDrafts(ticketId, request, user));
    }

    @GetMapping("/tickets/{ticketId}/import-requests")
    Map<String, List<ImportRequestDto>> listForTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequests", service.list(ticketId, user));
    }

    /** Read-only: does any factory on the deal still need an IR? Its own route so the list stays cheap.
     *
     * <p>Slice 6c: {@code itemFactory} (optional, repeatable, {@code <ticketItemId>:<factoryId>}) carries the factory
     * the rep has picked, but not yet saved, for a line with none — the same pairs the confirm will send as
     * {@code itemFactories} — so the response's split preview places those lines where the create will. Ignored
     * for a caller who gets no preview. A pair that is not two numbers is a 400. */
    @GetMapping("/tickets/{ticketId}/import-requests/coverage")
    ImportRequestCoverageResponse coverage(@PathVariable long ticketId,
            @RequestParam(name = "itemFactory", required = false) List<String> itemFactory, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.coverage(ticketId, parseItemFactories(itemFactory), user);
    }

    /** More pairs than any deal has factory-less lines is a client bug or abuse, and each pair costs a factory read. */
    static final int MAX_ITEM_FACTORY_PAIRS = 500;

    static List<ImportRequestRequests.ItemFactoryInput> parseItemFactories(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_ITEM_FACTORY_PAIRS) {
            throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "itemFactory มากเกินไป");
        }
        List<ImportRequestRequests.ItemFactoryInput> out = new java.util.ArrayList<>();
        for (String pair : raw) {
            String[] parts = pair == null ? new String[0] : pair.split(":", -1);
            try {
                if (parts.length != 2) {
                    throw new NumberFormatException();
                }
                out.add(new ImportRequestRequests.ItemFactoryInput(
                    Long.parseLong(parts[0].strip()), Long.parseLong(parts[1].strip())));
            } catch (NumberFormatException e) {
                throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "รูปแบบ itemFactory ไม่ถูกต้อง (ต้องเป็น รหัสรายการ:รหัสโรงงาน)");
            }
        }
        return out;
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

    /** Advances an ISSUED row's per-factory progress by one step (V184, GLA-100/S12-S17) — import/CEO only.
     * SERVER_ONLY in frontend/src/api/serverContract.test.js as of this PR (no frontend wiring yet — see that
     * file's entry for the reason). */
    @PostMapping("/import-requests/{id}/advance-step")
    Map<String, Object> advanceStep(@PathVariable long id,
            @Valid @RequestBody AdvanceImportStepRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("importRequest", asConflict(() -> service.advanceStep(id, request, user)));
        // When the 30 s undo window closes, in SERVER time (V216); null when there is nothing to undo.
        body.put("undoUntil", service.stepUndoUntil(id));
        return body;
    }

    /** Review H1: a deadlock / lock / serialization victim on advance or undo is the same Thai 409 a lost
     * compare-and-set gets, never a 500. Caught here, outside the @Transactional proxy, because a serialization
     * failure can surface at commit. */
    private static ImportRequestDto asConflict(java.util.function.Supplier<ImportRequestDto> call) {
        try {
            return call.get();
        } catch (org.springframework.dao.ConcurrencyFailureException e) {
            throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                "ขั้นตอนถูกเปลี่ยนแปลงโดยผู้ใช้อื่น หรือฉบับนี้ถูกแทนที่แล้ว");
        }
    }

    /** Undoes the caller's own latest step advance within 30 s (V216, the /fulfilment undo snackbar) — same role gate
     * as advance, plus actor-only and step-unchanged; see ImportRequestService#undoStep. */
    @PostMapping("/import-requests/{id}/advance-step/undo")
    Map<String, ImportRequestDto> undoStep(@PathVariable long id,
            @RequestBody(required = false) ImportRequestRequests.UndoImportStepRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", asConflict(() -> service.undoStep(id, request, user)));
    }

    /** Sets lead time on an ISSUED row (owner decision 09-18 #2) — import/CEO only. The DRAFT-stage edit for the
     * owning rep/CEO goes through PATCH .../import-requests/{id} instead
     * (UpdateImportRequestRequest#leadTimeMinDays/MaxDays). */
    /** Import/CEO: an IR line that points at no deal line gets its deal line (the stuck-deal exit). */
    @PostMapping("/import-requests/lines/{lineId}/link")
    Map<String, Boolean> linkLine(@PathVariable long lineId, @RequestBody LinkLineRequest request, HttpSession session) {
        service.linkLine(lineId, request.ticketItemId(), sessions.requireUser(session));
        return Map.of("linked", true);
    }

    record LinkLineRequest(long ticketItemId) {}

    @PostMapping("/import-requests/{id}/lead-time")
    Map<String, ImportRequestDto> setLeadTime(@PathVariable long id,
            @Valid @RequestBody SetLeadTimeRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", service.setLeadTime(id, request, user));
    }

    /** Edits the drafted order-email (owner decision 09-18 #3 §B) — owning rep/CEO/import, while not yet marked
     * sent. SERVER_ONLY in frontend/src/api/serverContract.test.js as of this PR — PR-B wires the UI
     * (import-request-per-factory-PLAN.md §C). */
    @PatchMapping("/import-requests/{id}/email-draft")
    Map<String, ImportRequestDto> updateEmailDraft(@PathVariable long id,
            @Valid @RequestBody(required = false) UpdateEmailDraftRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", service.updateEmailDraft(id, request, user));
    }

    /** Records that a human sent the drafted order-email by hand — this system never sends mail. Idempotent: a
     * second call 409s. SERVER_ONLY as of this PR, same as email-draft above. */
    @PostMapping("/import-requests/{id}/mark-email-sent")
    Map<String, ImportRequestDto> markEmailSent(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("importRequest", service.markEmailSent(id, user));
    }

    @DeleteMapping("/import-requests/{id}")
    Map<String, String> deleteDraft(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.deleteDraft(id, user);
        return Map.of("status", "deleted");
    }

    /** A stored form printed from its OWN snapshot, not from the deal's current state. */
    @GetMapping(value = "/import-requests/{id}/file", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> storedFile(@PathVariable long id,
            @RequestParam(required = false) String copy, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        boolean factoryCopy = "factory".equals(copy);
        ImportRequestDto row = service.get(id, user);
        byte[] pdf = service.renderStored(id, user, factoryCopy);
        // V184: factory is the grouping key (was brand); the filename now names the factory rather than the brand
        // snapshot, which may span more than one brand.
        String name = ImportRequestService.downloadFileName(row.docNumber(), row.id(), row.factoryName(),
            row.brand(), factoryCopy);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8).build().toString())
            .body(pdf);
    }

    /** "กำหนดวันที่ต้องการของ" on the DEAL — the one route here that belongs to SALES, not import. Gated separately
     * inside the service; see ImportRequestService.setRequiredByNote. */
    @PutMapping("/tickets/{ticketId}/required-by-note")
    Map<String, String> setRequiredByNote(@PathVariable long ticketId,
            @Valid @RequestBody SetRequiredByNoteRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.setRequiredByNote(ticketId, request, user);
        return Map.of("status", "saved");
    }

    /** The form itself. */
    @GetMapping(value = "/tickets/{ticketId}/import-request", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> download(@PathVariable long ticketId,
                                    @RequestParam String brand,
                                    @RequestParam(required = false) String ref,
                                    @RequestParam(required = false) String requiredBy,
                                    HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        byte[] pdf = service.render(ticketId, brand, ref, requiredBy, user);
        // Brand and ref reach the filename, and both are caller-supplied, so the name is built with ContentDisposition
        // (which RFC-5987 encodes it) rather than string-concatenated into the header — a brand containing a quote or
        // newline would otherwise let a caller inject header content.
        String name = "IR-" + (ref == null || ref.isBlank() ? "draft" : ref.strip())
            + "-" + brand.strip() + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8).build().toString())
            .body(pdf);
    }
}
