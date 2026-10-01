package th.co.glr.hr.pricingrequest;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.pricingrequest.LeadTimeChangeDtos.LeadTimeChangeDto;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.ApproveLeadTimeChangeRequest;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.CreateLeadTimeChangeRequest;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.RejectLeadTimeChangeRequest;

/** CR-1 (GLA-167): lead-time change requests. Role rules live in {@link LeadTimeChangeService}. */
@RestController
@RequestMapping("/api")
public class LeadTimeChangeController {
    private final LeadTimeChangeService service;
    private final SessionContext sessions;

    public LeadTimeChangeController(LeadTimeChangeService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping("/factory-quotes/{factoryQuoteId}/lead-time-changes")
    Map<String, LeadTimeChangeDto> create(
        @PathVariable long factoryQuoteId,
        @RequestBody CreateLeadTimeChangeRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("leadTimeChange", service.create(factoryQuoteId, request, user));
    }

    @PutMapping("/lead-time-changes/{changeId}")
    Map<String, LeadTimeChangeDto> update(
        @PathVariable long changeId,
        @RequestBody CreateLeadTimeChangeRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("leadTimeChange", service.update(changeId, request, user));
    }

    @PostMapping("/lead-time-changes/{changeId}/withdraw")
    Map<String, LeadTimeChangeDto> withdraw(@PathVariable long changeId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("leadTimeChange", service.withdraw(changeId, user));
    }

    @PostMapping("/lead-time-changes/{changeId}/approve")
    Map<String, LeadTimeChangeDto> approve(
        @PathVariable long changeId,
        @RequestBody ApproveLeadTimeChangeRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("leadTimeChange", service.approve(changeId, request.expectedVersion(), user));
    }

    @PostMapping("/lead-time-changes/{changeId}/reject")
    Map<String, LeadTimeChangeDto> reject(
        @PathVariable long changeId,
        @RequestBody RejectLeadTimeChangeRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("leadTimeChange", service.reject(changeId, request, user));
    }

    @GetMapping("/pricing-requests/{pricingRequestId}/lead-time-changes")
    Map<String, List<LeadTimeChangeDto>> list(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", service.listForPricingRequest(pricingRequestId, user));
    }
}
