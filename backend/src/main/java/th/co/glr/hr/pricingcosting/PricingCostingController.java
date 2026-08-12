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

/**
 * V141 ("CEO owns costing"): Import no longer drives a costing of its own —
 * {@code th.co.glr.hr.pricingdecision.PricingDecisionController} owns every live
 * costing-affecting action now (start review, recalculate-cost, cost-override).
 *
 * <p>The three write routes below are therefore <strong>severed, not removed</strong>: each is
 * {@code @Deprecated} and 409s immediately. Keeping the route shape is deliberate — {@code
 * frontend/src/api/{routes,hrApi,mockApi}.js} still declare all three, and {@code
 * frontend/src/api/contract.test.js} asserts hrApi's and mockApi's method surfaces match in both
 * directions, so deleting the endpoints here while the frontend pass is still pending would turn
 * that test red. This mirrors how {@code TicketService#submit}/{@code #pickup}/{@code
 * #proposePrice} were severed in the earlier redesign: the client contract stays stable while the
 * old behaviour becomes unreachable. A follow-up frontend pass removes all three together.
 *
 * <p>Both reads are kept live and unchanged: Import and the CEO can still see a computed cost,
 * which is what Import needs when renegotiating with a factory.
 */
@RestController
@RequestMapping("/api")
public class PricingCostingController {
    private final PricingCostingService costings;
    private final SessionContext sessions;

    public PricingCostingController(PricingCostingService costings, SessionContext sessions) {
        this.costings = costings;
        this.sessions = sessions;
    }

    /** Severed by V141 — always 409s. See {@link PricingCostingService#createDraft}. */
    @Deprecated
    @PostMapping("/pricing-requests/{pricingRequestId}/costings")
    Map<String, PricingCostingDto> createDraft(
        @PathVariable long pricingRequestId,
        @RequestBody CreateCostingRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("costing", costings.createDraft(pricingRequestId, request, user));
    }

    /** Severed by V141 — always 409s. See {@link PricingCostingService#recalculate}. */
    @Deprecated
    @PostMapping("/pricing-costings/{costingId}/recalculate")
    Map<String, PricingCostingDto> recalculate(
        @PathVariable long costingId,
        @RequestBody RecalculateCostingRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("costing", costings.recalculate(costingId, request, user));
    }

    /** Severed by V141 — always 409s. See {@link PricingCostingService#submit}. */
    @Deprecated
    @PostMapping("/pricing-costings/{costingId}/submit")
    Map<String, PricingCostingDto> submit(
        @PathVariable long costingId,
        @RequestBody SubmitCostingRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("costing", costings.submit(costingId, request, user));
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
}
