package th.co.glr.hr.payroll.obligation;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallDtos.PayrollDeductionShortfallListResponse;

/**
 * HTTP surface for the shortfall ledger (issue #376) -- HR/CEO only, read-only. See {@link
 * PayrollDeductionShortfallService}.
 */
@RestController
@RequestMapping("/api/payroll/deduction-shortfalls")
public class PayrollDeductionShortfallController {
    private final PayrollDeductionShortfallService service;
    private final SessionContext sessions;

    public PayrollDeductionShortfallController(PayrollDeductionShortfallService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','CEO')")
    public PayrollDeductionShortfallListResponse list(
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) PayrollDeductionKind kind,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new PayrollDeductionShortfallListResponse(service.list(employeeId, kind, user));
    }
}
