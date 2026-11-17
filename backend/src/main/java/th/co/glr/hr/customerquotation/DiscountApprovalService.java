package th.co.glr.hr.customerquotation;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customerquotation.DiscountApprovalDtos.DiscountApprovalDto;
import th.co.glr.hr.customerquotation.DiscountApprovalRequests.RejectDiscountApprovalRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;

/** Phase 2 of the discount-approval work (owner ruling 2026-08-16): the CEO-facing half — approve/reject a
 * pending discount request, and the two read surfaces (a quotation's own current per-line status; the CEO's
 * cross-quotation queue). */
@Service
public class DiscountApprovalService {
    private static final Set<String> CEO_ROLES = Set.of("ceo");

    private final DiscountApprovalRepository approvals;
    private final CustomerQuotationService quotations;
    private final PricingRequestRepository pricingRequests;
    private final NotificationRepository notifications;

    public DiscountApprovalService(DiscountApprovalRepository approvals, CustomerQuotationService quotations,
                                   PricingRequestRepository pricingRequests, NotificationRepository notifications) {
        this.approvals = approvals;
        this.quotations = quotations;
        this.pricingRequests = pricingRequests;
        this.notifications = notifications;
    }

    /** Current per-line approval status for one quotation — the same document actor is already allowed to view.
     * Throws exactly as CustomerQuotationService#get would (404/403) if they are not. */
    public List<DiscountApprovalDto> listForQuotation(long quotationId, UserPrincipal actor) {
        quotations.get(quotationId, actor);
        return approvals.findCurrentByQuotationId(quotationId);
    }

    /** The CEO's own cross-quotation queue of everything currently awaiting a decision. */
    public List<DiscountApprovalDto> listPending(UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        return approvals.findPending();
    }

    @Transactional
    public DiscountApprovalDto approve(long discountApprovalId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        DiscountApprovalDto approval = requireApproval(discountApprovalId);
        requirePending(approval);

        int rows = approvals.approve(discountApprovalId, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขออนุมัติส่วนลดนี้ถูกตัดสินใจไปแล้วโดยผู้ใช้อื่น");
        }
        DiscountApprovalDto approved = requireApproval(discountApprovalId);
        PricingRequestSummaryDto summary = requirePricingRequest(approved.pricingRequestId());
        String message = "CEO อนุมัติส่วนลดรายการที่ " + approved.quotationItemId() + " ในใบเสนอราคา "
            + approved.quotationNumber() + " ที่ราคา " + approved.approvedFinalUnitPrice();
        addEvent(summary, actor, PricingRequestEventKind.DISCOUNT_APPROVED, message);
        // Notify the requesting sales rep too, mirroring PricingDecisionService.approve's own "tell the person who
        // asked" pattern — not explicitly required by the task brief, but keeps this decision consistent with every
        // other CEO decision in the pricing chain.
        notifications.notifyEmployeeForPricingRequest(approved.requestedBy(), summary.id(),
            PricingRequestEventKind.DISCOUNT_APPROVED, message);
        return approved;
    }

    @Transactional
    public DiscountApprovalDto reject(long discountApprovalId, RejectDiscountApprovalRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลในการปฏิเสธส่วนลด");
        }
        DiscountApprovalDto approval = requireApproval(discountApprovalId);
        requirePending(approval);

        int rows = approvals.reject(discountApprovalId, actor.id(), request.reason().trim());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขออนุมัติส่วนลดนี้ถูกตัดสินใจไปแล้วโดยผู้ใช้อื่น");
        }
        DiscountApprovalDto rejected = requireApproval(discountApprovalId);
        PricingRequestSummaryDto summary = requirePricingRequest(rejected.pricingRequestId());
        String message = "CEO ปฏิเสธส่วนลดรายการที่ " + rejected.quotationItemId() + " ในใบเสนอราคา "
            + rejected.quotationNumber() + ": " + rejected.rejectionReason();
        addEvent(summary, actor, PricingRequestEventKind.DISCOUNT_REJECTED, message);
        // Rejection leaving the quotation blocked (task brief) is enforced by CustomerQuotationService#issue's own
        // gate, not here — this call only records the decision and reason and tells Sales about it so they know to
        // revise the price or drop the discount and re-request.
        notifications.notifyEmployeeForPricingRequest(rejected.requestedBy(), summary.id(),
            PricingRequestEventKind.DISCOUNT_REJECTED, message);
        return rejected;
    }

    private void requirePending(DiscountApprovalDto approval) {
        if (!"PENDING".equals(approval.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขออนุมัติส่วนลดนี้ถูกตัดสินใจไปแล้ว (" + approval.status() + ")");
        }
    }

    private void addEvent(PricingRequestSummaryDto summary, UserPrincipal actor, String kind, String message) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(), kind,
            summary.status(), summary.status(), message, null);
    }

    private DiscountApprovalDto requireApproval(long discountApprovalId) {
        return approvals.findById(discountApprovalId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขออนุมัติส่วนลดนี้"));
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
