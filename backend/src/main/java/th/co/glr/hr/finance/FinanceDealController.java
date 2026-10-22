package th.co.glr.hr.finance;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.ticket.CommentRequest;
import th.co.glr.hr.ticket.RecordPaymentRequest;

/**
 * Finance-only deal read model and money actions for the account and ceo roles -- see
 * {@link FinanceDealService} for the authorisation rules and what the payload deliberately omits.
 * Every route returns {@code {deal: FinanceDealDto}}.
 */
@RestController
@RequestMapping("/api/finance/deals")
public class FinanceDealController {
    private final FinanceDealService service;
    private final SessionContext sessions;

    public FinanceDealController(FinanceDealService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    record NoteRequest(@Size(max = 2000) String note) {}

    record UpdateStageRequest(@NotBlank String stage, @Size(max = 2000) String note) {}

    @GetMapping("/{id}")
    Map<String, FinanceDealDto> get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("deal", service.get(id, user));
    }

    @PostMapping("/{id}/comments")
    Map<String, FinanceDealDto> comment(@PathVariable long id, @Valid @RequestBody CommentRequest request,
                                        HttpSession session) {
        return Map.of("deal", service.addComment(id, request, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/deposit-paid")
    Map<String, FinanceDealDto> confirmDepositPaid(@PathVariable long id, HttpSession session) {
        return Map.of("deal", service.confirmDepositPaid(id, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/final-payment")
    Map<String, FinanceDealDto> confirmFinalPayment(@PathVariable long id, HttpSession session) {
        return Map.of("deal", service.confirmFinalPayment(id, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/payments")
    Map<String, FinanceDealDto> recordPayment(@PathVariable long id, @Valid @RequestBody RecordPaymentRequest request,
                                              HttpSession session) {
        return Map.of("deal", service.recordPayment(id, request, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/close/confirm")
    Map<String, FinanceDealDto> confirmCloseReady(@PathVariable long id, HttpSession session) {
        return Map.of("deal", service.confirmCloseReady(id, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/close/revoke")
    Map<String, FinanceDealDto> revokeCloseConfirmation(@PathVariable long id,
                                                        @RequestBody(required = false) NoteRequest body,
                                                        HttpSession session) {
        return Map.of("deal", service.revokeCloseConfirmation(id, body == null ? null : body.note(),
            sessions.requireUser(session)));
    }

    @PostMapping("/{id}/stage")
    Map<String, FinanceDealDto> updateStage(@PathVariable long id, @Valid @RequestBody UpdateStageRequest request,
                                            HttpSession session) {
        return Map.of("deal", service.updateStage(id, request.stage(), request.note(), sessions.requireUser(session)));
    }
}
