package th.co.glr.hr.pricingcosting;

import java.util.UUID;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

@Service
public class PricingCostingService {
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> RAW_COSTING_ROLES = Set.of("import", "ceo");
    // Step 3 (CEO Selling Price Decision, "submitted costing is immutable... actually true"):
    // READY_FOR_CEO_REVIEW is deliberately EXCLUDED here, unlike before — Import used to be able
    // to silently reopen a SUBMITTED costing any time the request sat at READY_FOR_CEO_REVIEW,
    // which made a "submitted costing is immutable" claim false. COSTING_REVISION_REQUIRED is
    // the one replacement: it is only reachable via PricingDecisionService.returnToImport (the
    // CEO's own action), so createDraft can no longer bypass the CEO. See
    // PricingRequestStatus's ALLOWED map for the corresponding state-machine change.
    private static final Set<String> COSTING_CREATE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_REVISION_REQUIRED);

    private final PricingCostingRepository costings;
    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final NotificationRepository notifications;
    private final LandedCostCalculator landedCosts;

    /**
     * Spring's constructor: takes the shared {@link LandedCostCalculator} bean.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PricingCostingService(PricingCostingRepository costings, PricingRequestRepository pricingRequests,
                                 TicketRepository tickets, NotificationRepository notifications,
                                 LandedCostCalculator landedCosts) {
        this.costings = costings;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.notifications = notifications;
        this.landedCosts = landedCosts;
    }

    /**
     * Convenience constructor kept for the hand-wired integration tests (which build services with
     * {@code new} and have no Spring context — see AbstractPostgresIntegrationTest). Signature is
     * unchanged from before the LandedCostCalculator extraction, so those 10 test classes keep
     * compiling untouched; it just assembles the calculator itself.
     */
    public PricingCostingService(PricingCostingRepository costings, PricingRequestRepository pricingRequests,
                                 FactoryQuoteRepository factoryQuotes, TicketRepository tickets,
                                 FxRateRepository fxRates, PriceCalcConfigRepository priceConfigs,
                                 FactoryConfigRepository factoryConfigs, NotificationRepository notifications) {
        this(costings, pricingRequests, tickets, notifications,
            new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates, priceConfigs, factoryConfigs));
    }

    @Transactional
    public PricingCostingDto createDraft(long pricingRequestId, CreateCostingRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if (!COSTING_CREATE_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ยังไม่พร้อมสำหรับการคำนวณต้นทุน");
        }
        requireActiveDeal(summary.ticketId());
        landedCosts.resolveSources(summary);
        String clientRequestId = validateClientRequestId(request.clientRequestId());
        PricingCostingRepository.CreateDraftResult created =
            costings.createDraft(pricingRequestId, request.note(), clientRequestId, actor.id());
        long costingId = created.costingId();
        if (!created.created()) {
            PricingCostingDto existing = requireCosting(costingId);
            if (existing.pricingRequestId() != pricingRequestId) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "clientRequestId นี้ถูกใช้ไปแล้วกับคำขอราคาอื่น");
            }
            return existing;
        }
        // V140: costing no longer has a status of its own. Starting one settles the request into
        // AWAITING_FACTORY_RESPONSE (เจรจาราคากับโรงงาน) when it is not already there — i.e. when
        // Import creates a costing straight from IMPORT_REVIEWING, or reopens one the CEO returned
        // (COSTING_REVISION_REQUIRED). Both edges are in PricingRequestStatus.ALLOWED.
        if (!PricingRequestStatus.AWAITING_FACTORY_RESPONSE.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), summary.status(),
                PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
            }
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_COSTING_STARTED, summary.status(),
            PricingRequestStatus.AWAITING_FACTORY_RESPONSE, "Costing draft created");
        notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_STARTED,
            "คำขอราคา " + summary.requestCode() + " เริ่มร่างต้นทุน");
        return requireCosting(costingId);
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

    @Transactional
    public PricingCostingDto recalculate(long costingId, RecalculateCostingRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingCostingDto costing = requireCosting(costingId);
        if (PricingCostingStatus.SUBMITTED.equals(costing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "การคำนวณต้นทุนที่ส่งไปแล้วไม่สามารถแก้ไขได้");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(costing.pricingRequestId());
        if (!PricingRequestStatus.AWAITING_FACTORY_RESPONSE.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาต้องอยู่ในสถานะเจรจาราคากับโรงงาน ก่อนจึงจะคำนวณใหม่ได้");
        }
        requireActiveDeal(summary.ticketId());
        LandedCostCalculator.CalculationResult result = landedCosts.calculate(summary);
        costings.replaceItems(costingId, result.items());
        int rows = costings.markCalculated(costingId, result.total(), request.note());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่สามารถคำนวณต้นทุนใหม่ได้ในสถานะปัจจุบัน");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_COSTING_CALCULATED, summary.status(), summary.status(),
            "Costing recalculated");
        if (PricingCostingStatus.DRAFT.equals(costing.status())) {
            notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_CALCULATED,
                "คำขอราคา " + summary.requestCode() + " คำนวณต้นทุนแล้ว");
        }
        return requireCosting(costingId);
    }

    @Transactional
    public PricingCostingDto submit(long costingId, SubmitCostingRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingCostingDto costing = requireCosting(costingId);
        if (costing.stale()) {
            throw new ApiException(HttpStatus.CONFLICT, "ข้อมูลต้นทุนล้าสมัยแล้ว ต้องคำนวณใหม่ก่อนส่ง");
        }
        if (!PricingCostingStatus.CALCULATED.equals(costing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ส่งได้เฉพาะการคำนวณต้นทุนที่คำนวณเสร็จแล้วเท่านั้น");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(costing.pricingRequestId());
        if (!PricingRequestStatus.AWAITING_FACTORY_RESPONSE.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาต้องอยู่ในสถานะเจรจาราคากับโรงงาน ก่อนจึงจะส่งให้ CEO ได้");
        }
        requireActiveDeal(summary.ticketId());
        LandedCostCalculator.CalculationResult result = landedCosts.calculate(summary);
        costings.replaceItems(costingId, result.items());
        int rows = costings.submit(costingId, actor.id(), result.total(), request.note());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "การคำนวณต้นทุนถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
            PricingRequestStatus.READY_FOR_CEO_REVIEW, null, null);
        if (transitioned == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        addEvent(summary, actor, PricingRequestEventKind.PRICING_COSTING_SUBMITTED,
            PricingRequestStatus.AWAITING_FACTORY_RESPONSE, PricingRequestStatus.READY_FOR_CEO_REVIEW,
            "Costing submitted to CEO");
        notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_SUBMITTED,
            "คำขอราคา " + summary.requestCode() + " ส่งต้นทุนให้ CEO แล้ว");
        return requireCosting(costingId);
    }

    private String validateClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID ที่ถูกต้อง");
        }
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private PricingCostingDto requireCosting(long costingId) {
        return costings.find(costingId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบการคำนวณต้นทุนนี้"));
    }

    private void requireActiveDeal(long ticketId) {
        TicketSummaryDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
        if (!DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลต้นทางต้องอยู่ในสถานะ ACTIVE");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private void addEvent(PricingRequestSummaryDto summary, UserPrincipal actor, String kind,
                          String fromStatus, String toStatus, String message) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(), kind, fromStatus, toStatus,
            message, null);
    }

    private void notifyCeo(PricingRequestSummaryDto summary, String type, String message) {
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(), type, message);
    }
}
