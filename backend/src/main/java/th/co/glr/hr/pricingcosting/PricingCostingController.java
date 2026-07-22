package th.co.glr.hr.pricingcosting;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;

@RestController
@RequestMapping("/api")
public class PricingCostingController {
    private final PricingCostingService costings;
    private final SessionContext sessions;

    public PricingCostingController(PricingCostingService costings, SessionContext sessions) {
        this.costings = costings;
        this.sessions = sessions;
    }

    @PostMapping("/pricing-requests/{pricingRequestId}/costings")
    Map<String, PricingCostingDto> createDraft(
        @PathVariable long pricingRequestId,
        @RequestBody CreateCostingRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("costing", costings.createDraft(pricingRequestId, request, user));
    }

    @GetMapping("/pricing-requests/{pricingRequestId}/costings")
    Map<String, List<PricingCostingDto>> list(@PathVariable long pricingRequestId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", costings.list(pricingRequestId, user));
    }

    @GetMapping("/pricing-costings/{costingId}")
    Map<String, PricingCostingDto> get(@PathVariable long costingId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("costing", costings.get(costingId, user));
    }

    @PostMapping("/pricing-costings/{costingId}/recalculate")
    Map<String, PricingCostingDto> recalculate(
        @PathVariable long costingId,
        @RequestBody RecalculateCostingRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("costing", costings.recalculate(costingId, request, user));
    }

    @PostMapping("/pricing-costings/{costingId}/submit")
    Map<String, PricingCostingDto> submit(
        @PathVariable long costingId,
        @RequestBody SubmitCostingRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("costing", costings.submit(costingId, request, user));
    }
}
