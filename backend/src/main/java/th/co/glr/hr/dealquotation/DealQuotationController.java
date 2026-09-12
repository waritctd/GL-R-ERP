package th.co.glr.hr.dealquotation;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRepository.PictureImage;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.PicturePlacementRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RejectRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;

/**
 * Quotation v2 (direct deal quotation, V165) endpoints — see docs/sales/quotation-v2-plan.md's "API"
 * section for the exact routes/envelopes this implements. Detail-shaped responses are wrapped as
 * {@code {quotation: ...}}, list-shaped as {@code {items: [...]}} and the single-item preview as
 * {@code {item: ...}} — mirrors {@code CustomerQuotationController}'s own envelope convention.
 *
 * <p>Every authz decision lives in {@link DealQuotationService}; this class only resolves the
 * session and delegates.
 */
@RestController
@RequestMapping("/api")
public class DealQuotationController {
    private final DealQuotationService quotations;
    private final SessionContext sessions;

    public DealQuotationController(DealQuotationService quotations, SessionContext sessions) {
        this.quotations = quotations;
        this.sessions = sessions;
    }

    @PostMapping("/tickets/{ticketId}/deal-quotations")
    ResponseEntity<Map<String, DealQuotationDto>> create(@PathVariable long ticketId,
                                                          @Valid @RequestBody UpsertDealQuotationRequest request,
                                                          HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("quotation", quotations.create(ticketId, request, user)));
    }

    @GetMapping("/tickets/{ticketId}/deal-quotations")
    Map<String, List<DealQuotationDto>> listForTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", quotations.listForTicket(ticketId, user));
    }

    /** {@code needsRework=true} (owner feedback F5, 2026-09-10) narrows to the "แก้" bucket —
     * DRAFT rows sent back with a reason or revisions in progress — server-side, composed with
     * {@code status} (AND); see {@code DealQuotationRepository#search}. */
    @GetMapping("/deal-quotations")
    Map<String, List<DealQuotationDto>> search(@RequestParam(required = false) List<String> status,
                                               @RequestParam(required = false, defaultValue = "false") boolean needsRework,
                                               HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", quotations.search(status, needsRework, user));
    }

    /** Per-status counts for the caller's own list scope — {@code {all, pendingApproval,
     * needsRework, cancelled, approved}} — so the list page's tabs carry counts without a second
     * full fetch (owner feedback F5). Same scope rules as {@link #search}. */
    @GetMapping("/deal-quotations/counts")
    DealQuotationCountsDto counts(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return quotations.counts(user);
    }

    @GetMapping("/deal-quotations/{id}")
    Map<String, DealQuotationDto> get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.get(id, user));
    }

    @PutMapping("/deal-quotations/{id}")
    Map<String, DealQuotationDto> update(@PathVariable long id,
                                         @Valid @RequestBody UpsertDealQuotationRequest request,
                                         HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.update(id, request, user));
    }

    @PostMapping("/deal-quotations/calculate-line")
    Map<String, DealQuotationItemDto> calculateLine(@Valid @RequestBody ItemInput input, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("item", quotations.calculateLine(input, user));
    }

    @PostMapping("/deal-quotations/{id}/submit")
    Map<String, DealQuotationDto> submit(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.submit(id, user));
    }

    @PostMapping("/deal-quotations/{id}/approve")
    Map<String, DealQuotationDto> approve(@PathVariable long id,
                                          @RequestBody(required = false) ApproveRequest request,
                                          HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.approve(id, request != null ? request : new ApproveRequest(null), user));
    }

    @PostMapping("/deal-quotations/{id}/reject")
    Map<String, DealQuotationDto> reject(@PathVariable long id, @Valid @RequestBody RejectRequest request,
                                         HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.reject(id, request, user));
    }

    @PostMapping("/deal-quotations/{id}/revisions")
    Map<String, DealQuotationDto> createRevision(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.createRevision(id, user));
    }

    @PostMapping("/deal-quotations/{id}/cancel")
    Map<String, DealQuotationDto> cancel(@PathVariable long id,
                                         @RequestBody(required = false) CancelRequest request,
                                         HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.cancel(id, request != null ? request : new CancelRequest(null), user));
    }

    @GetMapping("/deal-quotations/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable long id, @RequestParam(defaultValue = "pdf") String format,
                                HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        if (!"xlsx".equalsIgnoreCase(format) && !"pdf".equalsIgnoreCase(format)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับรูปแบบไฟล์ '" + format + "'");
        }
        // Download filename is the quotation NUMBER, not the internal id -- "deal-quotation-42.pdf"
        // told the rep nothing about which document they just downloaded when they have several
        // deals open. quotations.get() re-runs requireViewAccess (also re-run inside
        // renderPdf/renderXlsx below) -- an extra read, not a second authz decision; both are the
        // same view-access check on the same row.
        String number = quotations.get(id, user).number();
        String filename = sanitizeFilename(number);
        if ("xlsx".equalsIgnoreCase(format)) {
            byte[] bytes = quotations.renderXlsx(id, user);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".xls\"")
                .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
                .body(bytes);
        }
        byte[] bytes = quotations.renderPdf(id, user);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }

    // ── GLA-75: one picture per item (V170). Writes are DRAFT-only and gated exactly like
    // PUT /deal-quotations/{id}; the read is gated like GET /deal-quotations/{id}. See
    // DealQuotationService#uploadItemPicture for the rule. Writes answer {quotation: ...} so the
    // client re-reads hasPicture/picturePlacement/pictureUrl from one response.

    /** Multipart {@code file} (PNG/JPEG by magic bytes, ≤ 2 MB) and optional {@code placement}
     * ({@code BELOW} default | {@code BESIDE}). Replaces any existing picture on the item. PUT +
     * multipart, like {@code EmployeeSignatureController#upload}. */
    @PutMapping("/deal-quotations/{id}/items/{itemId}/picture")
    Map<String, DealQuotationDto> uploadItemPicture(@PathVariable long id, @PathVariable long itemId,
                                                    @RequestParam("file") MultipartFile file,
                                                    @RequestParam(value = "placement", required = false) String placement,
                                                    HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.uploadItemPicture(id, itemId, file, placement, user));
    }

    @PatchMapping("/deal-quotations/{id}/items/{itemId}/picture")
    Map<String, DealQuotationDto> setItemPicturePlacement(@PathVariable long id, @PathVariable long itemId,
                                                          @Valid @RequestBody PicturePlacementRequest request,
                                                          HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.setItemPicturePlacement(id, itemId, request.placement(), user));
    }

    @DeleteMapping("/deal-quotations/{id}/items/{itemId}/picture")
    Map<String, DealQuotationDto> removeItemPicture(@PathVariable long id, @PathVariable long itemId,
                                                    HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.removeItemPicture(id, itemId, user));
    }

    @GetMapping("/deal-quotations/{id}/items/{itemId}/picture")
    ResponseEntity<byte[]> itemPicture(@PathVariable long id, @PathVariable long itemId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        PictureImage image = quotations.getItemPicture(id, itemId, user);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.mimeType()))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(image.image());
    }

    /** Keeps a quotation number ({@code QT-2026-0042-1}, or a legacy pre-2026-09-11 bare
     * {@code QT-2026-0042}, or a later revision's {@code QT-2026-0042-2}) safe as a bare
     * Content-Disposition filename token: anything outside a conservative filesystem-safe set
     * becomes {@code _}, and a wiped-out result falls back to a generic name rather than emitting
     * an empty/blank filename. */
    private String sanitizeFilename(String number) {
        String base = number == null ? "" : number.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return base.isEmpty() ? "quotation" : base;
    }
}
