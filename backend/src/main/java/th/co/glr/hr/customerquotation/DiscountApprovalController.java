package th.co.glr.hr.customerquotation;

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
import th.co.glr.hr.customerquotation.DiscountApprovalDtos.DiscountApprovalDto;
import th.co.glr.hr.customerquotation.DiscountApprovalRequests.RejectDiscountApprovalRequest;

/**
 * Endpoints for the CEO discount-approval workflow (Phase 2). Mirrors {@code
 * CustomerQuotationController}'s own envelope convention ({@code {items: [...]}} /
 * {@code {approval: ...}}) so the frontend's {@code apiRequest} unwrapping stays uniform.
 */
@RestController
@RequestMapping("/api")
public class DiscountApprovalController {
    private final DiscountApprovalService approvals;
    private final SessionContext sessions;

    public DiscountApprovalController(DiscountApprovalService approvals, SessionContext sessions) {
        this.approvals = approvals;
        this.sessions = sessions;
    }

    @GetMapping("/customer-quotations/{quotationId}/discount-approvals")
    Map<String, List<DiscountApprovalDto>> listForQuotation(@PathVariable long quotationId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", approvals.listForQuotation(quotationId, user));
    }

    @GetMapping("/discount-approvals/pending")
    Map<String, List<DiscountApprovalDto>> listPending(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", approvals.listPending(user));
    }

    @PostMapping("/discount-approvals/{id}/approve")
    Map<String, DiscountApprovalDto> approve(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("approval", approvals.approve(id, user));
    }

    @PostMapping("/discount-approvals/{id}/reject")
    Map<String, DiscountApprovalDto> reject(@PathVariable long id,
                                            @RequestBody(required = false) RejectDiscountApprovalRequest request,
                                            HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        RejectDiscountApprovalRequest body = request != null ? request : new RejectDiscountApprovalRequest(null);
        return Map.of("approval", approvals.reject(id, body, user));
    }
}
