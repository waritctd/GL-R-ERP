package th.co.glr.hr.payroll.obligation;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentListResponse;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentUpsertRequest;

/**
 * HTTP surface for the written-consent record (issue #376). HR/CEO view, HR edit -- same idiom as
 * {@link DeductionObligationController}. No employee-facing route: this is HR's own bookkeeping of
 * what it holds on file, not something an employee reports about themselves.
 */
@RestController
@RequestMapping("/api/payroll/deduction-consents")
public class DeductionWrittenConsentController {
    private final DeductionWrittenConsentService service;
    private final SessionContext sessions;

    public DeductionWrittenConsentController(DeductionWrittenConsentService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','CEO')")
    public DeductionWrittenConsentListResponse list(
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) PayrollDeductionKind kind,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new DeductionWrittenConsentListResponse(service.list(employeeId, kind, user));
    }

    @PutMapping
    @PreAuthorize("hasRole('HR')")
    public DeductionWrittenConsentListResponse upsert(
        @Valid @RequestBody DeductionWrittenConsentUpsertRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new DeductionWrittenConsentListResponse(service.upsert(request, user));
    }
}
