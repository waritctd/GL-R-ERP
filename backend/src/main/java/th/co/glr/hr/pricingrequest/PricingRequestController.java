package th.co.glr.hr.pricingrequest;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestAttachmentDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CancelPricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RequestMoreInformationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RespondMoreInformationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestAttachmentRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestResponses.PricingRequestDetailResponse;

/**
 * Endpoints for the PricingRequest aggregate: createDraft, get, listForTicket, list
 * (the Import queue), updateDraft, submit, pickup, requestInformation,
 * respondInformation, cancel. {@code cancelOpenForTicket} is an internal cascade
 * invoked by {@code TicketService} and is deliberately NOT exposed here.
 *
 * <p>Routes straddle two prefixes, like the request shapes: ticket-scoped create
 * and list live under {@code /api/tickets/{ticketId}/...} (mirroring
 * {@code TicketController}), everything else lives under
 * {@code /api/pricing-requests/...} as its own resource.
 */
@RestController
@RequestMapping("/api")
public class PricingRequestController {
    private final PricingRequestService pricingRequests;
    private final SessionContext sessions;

    public PricingRequestController(PricingRequestService pricingRequests, SessionContext sessions) {
        this.pricingRequests = pricingRequests;
        this.sessions        = sessions;
    }

    @PostMapping("/tickets/{ticketId}/pricing-requests")
    ResponseEntity<PricingRequestDetailResponse> createDraft(
        @PathVariable long ticketId,
        @Valid @RequestBody CreatePricingRequestRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        var created = pricingRequests.createDraft(ticketId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PricingRequestDetailResponse(created));
    }

    @GetMapping("/tickets/{ticketId}/pricing-requests")
    Map<String, List<PricingRequestSummaryDto>> listForTicket(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", pricingRequests.listForTicket(ticketId, user));
    }

    @GetMapping("/pricing-requests")
    Map<String, List<PricingRequestSummaryDto>> list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long assignedImportId,
        @RequestParam(defaultValue = "true") boolean activeOnly,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", pricingRequests.list(status, assignedImportId, activeOnly, user));
    }

    @GetMapping("/pricing-requests/{id}")
    PricingRequestDetailResponse get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.get(id, user));
    }

    @PutMapping("/pricing-requests/{id}")
    PricingRequestDetailResponse updateDraft(
        @PathVariable long id,
        @Valid @RequestBody UpdatePricingRequestRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.updateDraft(id, request, user));
    }

    @PostMapping("/pricing-requests/{id}/submit")
    PricingRequestDetailResponse submit(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.submit(id, user));
    }

    @PostMapping("/pricing-requests/{id}/pickup")
    PricingRequestDetailResponse pickup(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.pickup(id, user));
    }

    @PostMapping("/pricing-requests/{id}/request-information")
    PricingRequestDetailResponse requestInformation(
        @PathVariable long id,
        @Valid @RequestBody RequestMoreInformationRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.requestInformation(id, request, user));
    }

    @PostMapping("/pricing-requests/{id}/respond-information")
    PricingRequestDetailResponse respondInformation(
        @PathVariable long id,
        @Valid @RequestBody RespondMoreInformationRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.respondInformation(id, request, user));
    }

    @PostMapping("/pricing-requests/{id}/cancel")
    PricingRequestDetailResponse cancel(
        @PathVariable long id,
        @Valid @RequestBody CancelPricingRequestRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.cancel(id, request, user));
    }

    @PostMapping("/pricing-requests/{id}/customer-change-revision")
    ResponseEntity<PricingRequestDetailResponse> customerChangeRevision(
        @PathVariable long id,
        @Valid @RequestBody CustomerChangeRevisionRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        var created = pricingRequests.createCustomerChangeRevision(id, request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PricingRequestDetailResponse(created));
    }

    // --- Pricing Request attachments (V69, review remediation COMMIT 4) ---

    @PostMapping("/pricing-requests/{id}/attachments")
    Map<String, PricingRequestAttachmentDto> uploadAttachment(
        @PathVariable long id,
        @RequestParam("file") MultipartFile file,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("attachment", pricingRequests.uploadAttachment(id, file, user));
    }

    @GetMapping("/pricing-requests/{id}/attachments")
    Map<String, List<PricingRequestAttachmentDto>> listAttachments(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", pricingRequests.listAttachments(id, user));
    }

    @GetMapping("/pricing-request-attachments/{attachmentId}/file")
    ResponseEntity<Resource> downloadAttachment(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        PricingRequestAttachmentDto attachment = pricingRequests.getAttachment(attachmentId, user);
        String path = pricingRequests.attachmentFilePath(attachmentId, user);
        Resource resource = new FileSystemResource(path);
        if (!resource.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Pricing request attachment file not found");
        }
        String mime = attachment.mimeType() != null ? attachment.mimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(attachment.fileName()).build().toString())
            .body(resource);
    }

    @DeleteMapping("/pricing-request-attachments/{attachmentId}")
    Map<String, Boolean> deleteAttachment(@PathVariable long attachmentId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        pricingRequests.deleteAttachment(attachmentId, user);
        return Map.of("ok", true);
    }

    @PutMapping("/pricing-request-attachments/{attachmentId}/include-in-factory-email")
    Map<String, PricingRequestAttachmentDto> setAttachmentIncludeInFactoryEmail(
        @PathVariable long attachmentId,
        @Valid @RequestBody UpdatePricingRequestAttachmentRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("attachment", pricingRequests.setAttachmentIncludeInFactoryEmail(attachmentId, request, user));
    }
}
