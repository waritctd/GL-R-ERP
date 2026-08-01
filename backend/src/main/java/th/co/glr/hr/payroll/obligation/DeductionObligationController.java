package th.co.glr.hr.payroll.obligation;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationListResponse;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationOverrideRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationProgressDto;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationUpdateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.MyDeductionObligationsResponse;

/**
 * HTTP surface for the deduction-obligation record + remittance ledger (issue #373). Every
 * {@code @PreAuthorize} here is doubled by an equivalent {@code requireRole}/
 * {@code requireEmployeeActor} check in {@link DeductionObligationService} — copy of the
 * {@code TaxAllowanceDeclarationController}/{@code Service} idiom.
 *
 * <p>Deliberately NO employee-facing mutation endpoint anywhere in this class — decision 4: the
 * dispute route for both กยศ and กรมบังคับคดี is to the authority, never to HR, so there is nothing
 * for an employee to edit here. {@code GET .../me} is the only employee-facing route.
 */
@RestController
@RequestMapping("/api/payroll/deduction-obligations")
public class DeductionObligationController {
    private final DeductionObligationService service;
    private final SessionContext sessions;

    public DeductionObligationController(DeductionObligationService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    // ---- Employee self-service: read-only, /me shape, no obligation id anywhere ---------------

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public MyDeductionObligationsResponse getOwn(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new MyDeductionObligationsResponse(service.getOwn(user));
    }

    // ---- HR/CEO register + progress -------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','CEO')")
    public DeductionObligationListResponse list(
        @RequestParam(required = false) Long employeeId,
        @RequestParam(required = false) DeductionObligationKind kind,
        @RequestParam(required = false) DeductionObligationStatus status,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new DeductionObligationListResponse(service.list(employeeId, kind, status, user));
    }

    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAnyRole('HR','CEO')")
    public DeductionObligationProgressDto progress(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.progress(id, user);
    }

    // ---- HR mutations -----------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasRole('HR')")
    public ResponseEntity<DeductionObligationDto> create(
        @Valid @RequestBody DeductionObligationCreateRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        DeductionObligationDto created = service.create(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('HR')")
    public DeductionObligationDto update(
        @PathVariable long id, @Valid @RequestBody DeductionObligationUpdateRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return service.update(id, request, user);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasRole('HR')")
    public DeductionObligationDto stop(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.stop(id, user);
    }

    /** Decision 3's "prominent, HR must confirm" state — see the service javadoc. */
    @PostMapping("/{id}/acknowledge-completion")
    @PreAuthorize("hasRole('HR')")
    public DeductionObligationDto acknowledgeCompletion(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.acknowledgeCompletion(id, user);
    }

    /** Decision 3's required mitigation — the only way a deduction resumes past the instructed total. */
    @PostMapping("/{id}/override-continue")
    @PreAuthorize("hasRole('HR')")
    public DeductionObligationDto overrideContinue(
        @PathVariable long id, @Valid @RequestBody DeductionObligationOverrideRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return service.overrideContinue(id, request, user);
    }

    @PostMapping("/{id}/clear-override")
    @PreAuthorize("hasRole('HR')")
    public DeductionObligationDto clearOverride(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.clearOverrideContinue(id, user);
    }
}
