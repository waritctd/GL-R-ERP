package th.co.glr.hr.pricingcosting;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * V141 ("CEO owns costing"): effectively READ-ONLY. Import used to create/recalculate/submit a
 * costing draft that entered zero real data of its own; the CEO now computes the cost once,
 * deterministically, the moment they open review — see {@code
 * th.co.glr.hr.pricingdecision.PricingDecisionService#startReview}, which calls {@link
 * PricingCostingRepository#createComputed}/{@link PricingCostingRepository#replaceItemsPreservingOverrides}
 * directly. This class no longer owns any costing WRITE path. Import keeps read access ({@link
 * #RAW_COSTING_ROLES}, unchanged) — a computed cost is useful context while renegotiating with a
 * factory.
 *
 * <p>{@link #createDraft}, {@link #recalculate} and {@link #submit} are kept as {@code @Deprecated}
 * stubs that 409 immediately, rather than being deleted outright: the frontend still declares all
 * three in {@code routes.js}/{@code hrApi.js}/{@code mockApi.js}, and {@code contract.test.js}
 * asserts those two surfaces match, so removing them here ahead of the frontend pass would turn
 * that test red. Same treatment {@code TicketService#submit}/{@code #pickup}/{@code #proposePrice}
 * got when the PCR chain superseded the ticket-level flow.
 */
@Service
public class PricingCostingService {
    private static final Set<String> RAW_COSTING_ROLES = Set.of("import", "ceo");
    private static final String COSTING_MOVED_TO_CEO =
        "การคำนวณต้นทุนนำเข้าย้ายไปอยู่ในขั้นตอนการพิจารณาราคาของ CEO แล้ว — "
            + "ฝ่ายนำเข้าเพียงบันทึกราคาจากโรงงานและกดพร้อมคำนวณต้นทุน ระบบจะคำนวณต้นทุนให้เองเมื่อ CEO เปิดพิจารณาราคา";

    private final PricingCostingRepository costings;
    private final PricingRequestRepository pricingRequests;
    // Unused by list/get today (neither gates on deal liveness) — kept because the plan's
    // constructor shape names it explicitly; a future read-path here that needs to check the
    // deal is still ACTIVE would otherwise have to add it back as a second, disruptive change.
    private final TicketRepository tickets;

    public PricingCostingService(PricingCostingRepository costings, PricingRequestRepository pricingRequests,
                                 TicketRepository tickets) {
        this.costings = costings;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
    }

    public List<PricingCostingDto> list(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, RAW_COSTING_ROLES);
        requirePricingRequest(pricingRequestId);
        return costings.findByPricingRequest(pricingRequestId);
    }

    public PricingCostingDto get(long costingId, UserPrincipal actor) {
        requireRole(actor, RAW_COSTING_ROLES);
        return requireCosting(costingId);
    }

    /**
     * Deprecated and permanently severed by V141: Import no longer starts a costing. The cost is
     * computed by the CEO at review time — see {@code
     * th.co.glr.hr.pricingdecision.PricingDecisionService#startReview}. Always 409s; the route is
     * kept only so the pending frontend removal can drop all three write methods together.
     */
    @Deprecated
    public PricingCostingDto createDraft(long pricingRequestId, PricingCostingRequests.CreateCostingRequest request,
                                         UserPrincipal actor) {
        throw new ApiException(HttpStatus.CONFLICT, COSTING_MOVED_TO_CEO);
    }

    /**
     * Deprecated and permanently severed by V141. The CEO's own recompute is {@code
     * POST /api/pricing-decisions/{id}/recalculate-cost} — see {@code
     * th.co.glr.hr.pricingdecision.PricingDecisionService#recalculateCost}. Always 409s.
     */
    @Deprecated
    public PricingCostingDto recalculate(long costingId, PricingCostingRequests.RecalculateCostingRequest request,
                                         UserPrincipal actor) {
        throw new ApiException(HttpStatus.CONFLICT, COSTING_MOVED_TO_CEO);
    }

    /**
     * Deprecated and permanently severed by V141: there is no Import-submitted costing to hand to
     * the CEO any more. A pricing request reaches {@code READY_FOR_CEO_REVIEW} on its own once
     * every factory quote is ready — see {@code
     * th.co.glr.hr.factoryquote.FactoryQuoteService#markReadyForCosting}. Always 409s.
     */
    @Deprecated
    public PricingCostingDto submit(long costingId, PricingCostingRequests.SubmitCostingRequest request,
                                    UserPrincipal actor) {
        throw new ApiException(HttpStatus.CONFLICT, COSTING_MOVED_TO_CEO);
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private PricingCostingDto requireCosting(long costingId) {
        return costings.find(costingId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบการคำนวณต้นทุนนี้"));
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
