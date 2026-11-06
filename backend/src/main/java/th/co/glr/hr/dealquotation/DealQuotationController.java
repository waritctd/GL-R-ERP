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
import th.co.glr.hr.commission.CommissionRepOptionDto;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.PromoteToDealResultDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRepository.PictureImage;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.PicturePlacementRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RecordOutcomeRequest;
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

    /** "เขียนใบเสนอราคาจากคำขอราคา": creates a PRICING_REQUEST-origin quotation prefilled from an
     * APPROVED-FOR-QUOTATION pricing request's CEO-approved decision, on this SAME engine (not a
     * new one — see {@link DealQuotationService#createFromPricingRequest}'s own Javadoc). No
     * request body: everything comes from the pricing request and its decision.
     *
     * <p>Deliberately NOT {@code POST /pricing-requests/{id}/quotations} — that exact path is
     * already {@code CustomerQuotationController#create} (the legacy path this feature sits
     * beside, not inside; Spring refuses an ambiguous-mapping startup if both claim it). Named
     * {@code deal-quotations} for consistency with every other route this engine already owns
     * ({@code /tickets/{ticketId}/deal-quotations}, {@code /deal-quotations/{id}}, …). */
    @PostMapping("/pricing-requests/{pricingRequestId}/deal-quotations")
    ResponseEntity<Map<String, DealQuotationDto>> createFromPricingRequest(@PathVariable long pricingRequestId,
                                                                            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("quotation", quotations.createFromPricingRequest(pricingRequestId, user)));
    }

    @GetMapping("/tickets/{ticketId}/deal-quotations")
    Map<String, Object> listForTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        List<DealQuotationDto> items = quotations.listForTicket(ticketId, user);
        // `orderConfirmedQuotationId`: the row the deal's order was confirmed from (null when none, or not visible to
        // this viewer). The deal page's document register leads its group with it. A HashMap because Map.of refuses null.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        body.put("orderConfirmedQuotationId", quotations.orderConfirmedQuotationId(ticketId, items));
        return body;
    }

    /** Read-only, no create side effect (unlike {@code POST .../deal-quotations} above, which
     * mints or idempotently replays one). Lets {@code PricingRequestDetailPage}'s
     * "ใบเสนอราคาลูกค้า" panel show a NEW-engine quotation's number/status/link. {@code quotation:
     * null} (200, not 404) when this PR has none yet — "no quotation" is the normal, expected
     * state for most of a pricing request's lifetime, not an error. */
    @GetMapping("/pricing-requests/{pricingRequestId}/deal-quotations")
    Map<String, DealQuotationDto> findForPricingRequest(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        Map<String, DealQuotationDto> body = new java.util.HashMap<>();
        body.put("quotation", quotations.findForPricingRequest(pricingRequestId, user).orElse(null));
        return body;
    }

    /** {@code needsRework=true} narrows to the "แก้" bucket — DRAFT rows sent back with a reason or
     * revisions in progress — server-side, composed with {@code status} (AND); see {@code
     * DealQuotationRepository#search}. Covers every origin (legacy rows tagged {@code LEGACY},
     * {@code readOnly}); optional {@code origin} ∈ {@code DEAL_DIRECT | PRICING_REQUEST | LEGACY}
     * narrows to one (anything else 400). */
    @GetMapping("/deal-quotations")
    Map<String, List<DealQuotationDto>> search(@RequestParam(required = false) List<String> status,
                                               @RequestParam(required = false, defaultValue = "false") boolean needsRework,
                                               @RequestParam(required = false) String origin,
                                               HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", quotations.search(status, needsRework, origin, user));
    }

    /** Per-status counts for the caller's own list scope — {@code {all, pendingApproval,
     * needsRework, cancelled, approved}} ({@code approved} = APPROVED/ISSUED/SENT/ACCEPTED) — so
     * the list page's tabs carry counts without a second full fetch. Same scope rules, and the
     * same optional {@code origin} filter, as {@link #search}. */
    @GetMapping("/deal-quotations/counts")
    DealQuotationCountsDto counts(@RequestParam(required = false) String origin, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return quotations.counts(origin, user);
    }

    /** V179 — the option list for the ผู้พิมพ์/พนักงานขาย print-name selectors on the quotation
     * editor. See {@link DealQuotationService#findQuotationDisplayNameOptions} for who is listed
     * and who may call this. */
    @GetMapping("/deal-quotations/display-name-options")
    Map<String, List<CommissionRepOptionDto>> displayNameOptions(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", quotations.findQuotationDisplayNameOptions(user));
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
    Map<String, DealQuotationItemDto> calculateLine(@Valid @RequestBody ItemInput input,
                                                    // Optional; blank = TH.
                                                    @RequestParam(name = "documentLanguage", required = false)
                                                    String documentLanguage,
                                                    HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("item", quotations.calculateLine(input, documentLanguage, user));
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

    /** "สร้างจากใบเดิม" / สั่งเหมือนเดิม — clone an APPROVED direct-deal quotation into a new,
     * independent DRAFT. Named {@code reorders}, sibling to {@code revisions} above, for the SAME
     * reason that route is plural: one source may be cloned any number of times. */
    @PostMapping("/deal-quotations/{id}/reorders")
    Map<String, DealQuotationDto> createReorder(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.createReorder(id, user));
    }

    /** Records what the customer said about an ISSUED PRICING_REQUEST-origin quotation. Refused
     * (409) for a DEAL_DIRECT row — see {@code DealQuotationService#recordOutcome}'s own Javadoc
     * (direct quotations never join this pipeline). */
    @PostMapping("/deal-quotations/{id}/outcome")
    Map<String, DealQuotationDto> recordOutcome(@PathVariable long id,
                                                @Valid @RequestBody RecordOutcomeRequest request,
                                                HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.recordOutcome(id, request, user));
    }

    /** "ยืนยันคำสั่งซื้อ" on a direct quotation: moves the APPROVED direct quotation's deal to
     * ORDER_RECEIVED with the quotation's lines. No request body. Idempotent. See
     * {@code DealQuotationService#confirmOrderFromDirectQuotation}. Not {@code /outcome}: a direct
     * quotation has no customer-outcome concept; this is the rep's own decision that the customer
     * ordered.
     *
     * <p>{@code …/promote-to-deal} is the former name, kept as an ALIAS of this same handler for
     * ONE release so a frontend bundle still calling it keeps working; remove it once the frontend
     * calls {@code …/confirm-order}. */
    @PostMapping({"/deal-quotations/{id}/confirm-order", "/deal-quotations/{id}/promote-to-deal"})
    Map<String, PromoteToDealResultDto> confirmOrder(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("result", quotations.confirmOrderFromDirectQuotation(id, user));
    }

    @PostMapping("/deal-quotations/{id}/cancel")
    Map<String, DealQuotationDto> cancel(@PathVariable long id,
                                         @RequestBody(required = false) CancelRequest request,
                                         HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.cancel(id, request != null ? request : new CancelRequest(null), user));
    }

    /** "คืนรายการ": re-adds a CEO-linked line a prior save dropped. No request body other than
     * which decision item to restore — everything else is rebuilt server-side from the same
     * approved decision {@code createFromPricingRequest} itself reads from. */
    @PostMapping("/deal-quotations/{id}/items/{pricingDecisionItemId}/restore")
    Map<String, DealQuotationDto> restoreRemovedItem(@PathVariable long id,
                                                       @PathVariable long pricingDecisionItemId,
                                                       HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("quotation", quotations.restoreRemovedItem(id, pricingDecisionItemId, user));
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

    // One picture per item (V170). Writes are DRAFT-only and gated exactly like
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

    /** Keeps a quotation number ({@code QT-2026-0042-1}, or a legacy bare
     * {@code QT-2026-0042}, or a later revision's {@code QT-2026-0042-2}) safe as a bare
     * Content-Disposition filename token: anything outside a conservative filesystem-safe set
     * becomes {@code _}, and a wiped-out result falls back to a generic name rather than emitting
     * an empty/blank filename. */
    private String sanitizeFilename(String number) {
        String base = number == null ? "" : number.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return base.isEmpty() ? "quotation" : base;
    }
}
