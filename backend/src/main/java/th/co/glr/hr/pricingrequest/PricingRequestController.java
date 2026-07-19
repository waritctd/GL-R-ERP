package th.co.glr.hr.pricingrequest;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CancelPricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestResponses.PricingRequestDetailResponse;

/**
 * Endpoints for commit 3 only: createDraft, get, listForTicket, list (the Import
 * queue), updateDraft, submit, cancel. Pickup / requestInformation /
 * respondInformation (commit 4) are not wired up here.
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

    @PostMapping("/pricing-requests/{id}/cancel")
    PricingRequestDetailResponse cancel(
        @PathVariable long id,
        @Valid @RequestBody CancelPricingRequestRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new PricingRequestDetailResponse(pricingRequests.cancel(id, request, user));
    }
}
