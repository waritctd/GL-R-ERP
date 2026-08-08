package th.co.glr.hr.payroll.declaration;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.payroll.PayrollAllowanceEstimateResult;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.MyTaxAllowanceDeclarationsResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceApplyRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDto;
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

    /**
     * "What this saves me" (decision #4). NO employeeId anywhere — the body is the exact same
     * shape {@code POST .../declarations/me} takes, and the employee under estimate is always
     * {@code actor.employeeId()}. All arithmetic runs through the real {@code PayrollCalculator}
     * (see {@code PayrollService#estimateAllowanceEffect}) — never reimplemented here.
     */
    @PostMapping("/declarations/me/estimate")
    @PreAuthorize("isAuthenticated()")
    public PayrollAllowanceEstimateResult estimateOwn(
        @Valid @RequestBody TaxAllowanceDeclarationSubmitRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return service.estimateOwn(request, user);
    }

    // ---- Evidence attachments (decision #5) -------------------------------------------------
    //
    // Nested here (mirrors PricingRequestController's own POST/GET .../{id}/attachments); the
    // flat download/delete routes live on TaxAllowanceAttachmentController, since a flat
    // "/tax-allowance-attachments/{id}" resource does not fit under this controller's
    // "/api/payroll/tax-allowances" class-level prefix. Access for every one of these four
    // endpoints is enforced inside TaxAllowanceDeclarationService#requireOwnerOrHr — see that
    // method's own javadoc for why AttachmentController#requireAttachmentAccess's uploader
    // short-circuit is deliberately NOT copied.

    @PostMapping("/declarations/{id}/attachments")
    @PreAuthorize("isAuthenticated()")
    public Map<String, TaxAllowanceAttachmentDto> uploadAttachment(
        @PathVariable long id, @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) String sectionKey, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("attachment", service.uploadAttachment(id, file, sectionKey, user));
    }

    @GetMapping("/declarations/{id}/attachments")
    @PreAuthorize("isAuthenticated()")
    public Map<String, List<TaxAllowanceAttachmentDto>> listAttachments(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("items", service.listAttachments(id, user));
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

    /** Yearly expiry (decision #10), the mirror of the scheduled sweep: EXPIRED -> APPROVED, new deadline. */
    @PostMapping("/declarations/{id}/reverify")
    @PreAuthorize("hasRole('HR')")
    public TaxAllowanceDeclarationDto reverify(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.reverify(id, user);
    }

    // ---- Caps metadata (decision #1: never hardcode caps in the UI) -----------------------

    @GetMapping("/caps")
    @PreAuthorize("isAuthenticated()")
    public TaxAllowanceCapsResponse getCaps(@RequestParam int year, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return service.getCaps(year, user);
    }

    // ---- แบบ ล.ย.01 PDF ----------------------------------------------------------------------

    /**
     * The official ล.ย.01 for a saved declaration, flattened and ready to print. Owner or HR only —
     * enforced in the service, which re-resolves the declaration on every call.
     */
    @GetMapping("/declarations/{declarationId}/form.pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> lorYor01Pdf(@PathVariable long declarationId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return pdf(service.renderLorYor01(declarationId, user), "loryor01-" + declarationId + ".pdf");
    }

    /**
     * The official ล.ย.01 for an UNSAVED draft, so the employee can print and sign it before filing.
     *
     * <p>POST rather than GET because the whole draft travels in the body — same shape and same
     * reasoning as {@code /declarations/me/estimate} above. Nothing is persisted, and the form is
     * always rendered for {@code actor.employeeId()}, never a target named in the body.
     */
    @PostMapping("/declarations/me/form.pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> lorYor01DraftPdf(
        @Valid @RequestBody TaxAllowanceDeclarationSubmitRequest request, HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return pdf(service.renderLorYor01Draft(request, user), "loryor01-draft.pdf");
    }

    /**
     * Same hardening as the evidence download: {@code attachment} so a browser never renders it
     * inline, and {@code nosniff} so a mislabelled body cannot be re-interpreted as HTML.
     */
    private ResponseEntity<byte[]> pdf(byte[] body, String filename) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.APPLICATION_PDF)
            .body(body);
    }
}
