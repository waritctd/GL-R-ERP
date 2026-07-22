package th.co.glr.hr.orderconfirmation;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.orderconfirmation.OrderConfirmationDtos.OrderConfirmationResultDto;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;

/**
 * Step 6 endpoints, both keyed by pricing request id (mirroring
 * {@code CustomerQuotationController}'s own {@code /pricing-requests/{id}/...} create route) —
 * see {@link OrderConfirmationService}'s class Javadoc for why this lives as its own small
 * orchestration class rather than on {@code PricingRequestController}/{@code
 * DepositNoticeController}.
 */
@RestController
@RequestMapping("/api")
public class OrderConfirmationController {
    private final OrderConfirmationService orderConfirmation;
    private final SessionContext sessions;

    public OrderConfirmationController(OrderConfirmationService orderConfirmation, SessionContext sessions) {
        this.orderConfirmation = orderConfirmation;
        this.sessions = sessions;
    }

    @PostMapping("/pricing-requests/{id}/confirm-order")
    Map<String, OrderConfirmationResultDto> confirmOrder(@PathVariable long id,
                                                          @RequestBody(required = false) ConfirmOrderRequest request,
                                                          HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        OrderConfirmationResultDto result = orderConfirmation.confirmOrder(
            id, request != null ? request : new ConfirmOrderRequest(null), user);
        return Map.of("result", result);
    }

    @PostMapping("/pricing-requests/{id}/deposit-notice")
    Map<String, DepositNoticeDto> createDepositNoticeFromQuotation(
        @PathVariable long id,
        @Valid @RequestBody(required = false) CreateDepositNoticeFromQuotationRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        DepositNoticeDto depositNotice = orderConfirmation.createDepositNoticeFromQuotation(
            id, request != null ? request : new CreateDepositNoticeFromQuotationRequest(null), user);
        return Map.of("depositNotice", depositNotice);
    }
}
