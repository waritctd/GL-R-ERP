package th.co.glr.hr.payroll.declaration;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.MyTaxAllowanceDeclarationsResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceApplyRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceCapsResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationRegisterResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceOnBehalfRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceReviewRequest;

/**
 * HTTP surface for the tax-allowance self-declaration workflow (PR A). Base path is a sibling of
 * the existing {@code PayrollController#getTaxAllowances}/{@code #putTaxAllowances} (the legacy
 * bulk HR-typed editor, which stays live and untouched — see V105's header and risk #9 in the
 * plan). No mapping here collides with those: this controller only maps {@code /declarations/**}
 * and {@code /caps} under the same {@code /api/payroll/tax-allowances} prefix.
 *
 * <p>Every {@code @PreAuthorize} here is doubled by an equivalent {@code requireRole}/{@code
 * requireEmployeeActor} check in {@link TaxAllowanceDeclarationService} — copy of the {@code
 * PayrollController}/{@code PayrollService} idiom.
 */
@RestController
@RequestMapping("/api/payroll/tax-allowances")
public class TaxAllowanceDeclarationController {
    private final TaxAllowanceDeclarationService service;
    private final SessionContext sessions;

    public TaxAllowanceDeclarationController(TaxAllowanceDeclarationService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    // ---- Employee self-service: /me shape, no employeeId anywhere in the path or body ----------

    @GetMapping("/declarations/me")
    @PreAuthorize("isAuthenticated()")
    public MyTaxAllowanceDeclarationsResponse getOwn(@RequestParam int year, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.getOwn(year, user);
    }

    @PostMapping("/declarations/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TaxAllowanceDeclarationDto> submitOwn(
        @Valid @RequestBody TaxAllowanceDeclarationSubmitRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        TaxAllowanceDeclarationDto created = service.submitOwn(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Withdraw own PENDING declaration. 404 (not 403) on a foreign id — see the service javadoc. */
    @DeleteMapping("/declarations/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> withdrawOwn(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.withdrawOwn(id, user);
        return ResponseEntity.noContent().build();
    }

    // ---- HR/CEO register + HR mutations ----------------------------------------------------

    @GetMapping("/declarations")
    @PreAuthorize("hasAnyRole('HR','CEO')")
    public TaxAllowanceDeclarationRegisterResponse getRegister(
        @RequestParam(required = false) Integer year,
        @RequestParam(required = false) TaxAllowanceDeclarationStatus status,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return service.getRegister(year, status, user);
    }

    @PostMapping("/declarations/on-behalf")
    @PreAuthorize("hasRole('HR')")
    public ResponseEntity<TaxAllowanceDeclarationDto> createOnBehalf(
        @Valid @RequestBody TaxAllowanceOnBehalfRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        TaxAllowanceDeclarationDto created = service.createOnBehalf(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/declarations/{id}/approve")
    @PreAuthorize("hasRole('HR')")
    public TaxAllowanceDeclarationDto approve(
        @PathVariable long id, @RequestBody(required = false) TaxAllowanceReviewRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return service.approve(id, request, user);
    }

    @PostMapping("/declarations/{id}/reject")
    @PreAuthorize("hasRole('HR')")
    public TaxAllowanceDeclarationDto reject(
        @PathVariable long id, @RequestBody(required = false) TaxAllowanceReviewRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return service.reject(id, request, user);
    }

    @PostMapping("/declarations/{id}/apply")
    @PreAuthorize("hasRole('HR')")
    public TaxAllowanceDeclarationDto apply(
        @PathVariable long id, @RequestBody(required = false) TaxAllowanceApplyRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return service.apply(id, request, user);
    }

    // ---- Caps metadata (decision #1: never hardcode caps in the UI) -----------------------

    @GetMapping("/caps")
    @PreAuthorize("isAuthenticated()")
    public TaxAllowanceCapsResponse getCaps(@RequestParam int year, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.getCaps(year, user);
    }
}
