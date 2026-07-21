package th.co.glr.hr.pricingdecision;

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
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.RecalculatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ReturnPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;

@RestController
@RequestMapping("/api")
public class PricingDecisionController {
    private final PricingDecisionService decisions;
    private final SessionContext sessions;

    public PricingDecisionController(PricingDecisionService decisions, SessionContext sessions) {
        this.decisions = decisions;
        this.sessions = sessions;
    }

    @PostMapping("/pricing-requests/{pricingRequestId}/pricing-decisions")
    Map<String, PricingDecisionDto> startReview(
        @PathVariable long pricingRequestId,
        @RequestBody StartPricingDecisionRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("decision", decisions.startReview(pricingRequestId, request, user));
    }

    @GetMapping("/pricing-requests/{pricingRequestId}/pricing-decisions")
    Map<String, List<PricingDecisionDto>> list(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", decisions.list(pricingRequestId, user));
    }

    @GetMapping("/pricing-requests/{pricingRequestId}/pricing-decision/sales-view")
    Map<String, PricingDecisionSalesViewDto> salesView(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("decision", decisions.salesView(pricingRequestId, user));
    }

    @GetMapping("/pricing-decisions/{decisionId}")
    Map<String, PricingDecisionDto> get(@PathVariable long decisionId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("decision", decisions.get(decisionId, user));
    }

    @PutMapping("/pricing-decisions/{decisionId}")
    Map<String, PricingDecisionDto> update(
        @PathVariable long decisionId,
        @RequestBody UpdatePricingDecisionRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("decision", decisions.update(decisionId, request, user));
    }

    @PostMapping("/pricing-decisions/{decisionId}/recalculate")
    Map<String, PricingDecisionDto> recalculate(
        @PathVariable long decisionId,
        @RequestBody RecalculatePricingDecisionRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("decision", decisions.recalculate(decisionId, request, user));
    }

    @PostMapping("/pricing-decisions/{decisionId}/approve")
    Map<String, PricingDecisionDto> approve(
        @PathVariable long decisionId,
        @RequestBody ApprovePricingDecisionRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("decision", decisions.approve(decisionId, request, user));
    }

    @PostMapping("/pricing-decisions/{decisionId}/return-to-import")
    Map<String, PricingDecisionDto> returnToImport(
        @PathVariable long decisionId,
        @RequestBody ReturnPricingDecisionRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("decision", decisions.returnToImport(decisionId, request, user));
    }
}
