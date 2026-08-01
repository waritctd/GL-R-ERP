package th.co.glr.hr.payroll.obligation;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentDto;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentUpsertRequest;

/**
 * Business logic for the written-consent record (issue #376, task 4). <b>A recordable field, NOT an
 * enforcement gate</b> -- nothing here, and nothing in {@code PayrollCalculator}, reads {@code
 * consent_on_file} to decide whether a deduction runs. It exists solely to give HR a place to start
 * answering the issue's own open question ("which deductions actually have written employee consent
 * on file?") without pretending the answer is already known.
 *
 * <p>Restricted to the four deduction kinds that are HR-typed catch-alls rather than a statutory
 * figure or a court order -- see {@code hr.deduction_written_consent}'s CHECK constraint (V107) and
 * {@link th.co.glr.hr.payroll.PayrollDeductionClassification} for why {@code WITHHOLDING_TAX}/{@code
 * SOCIAL_SECURITY}/{@code STUDENT_LOAN} (item (1), no consent question) and {@code
 * LEGAL_EXECUTION_GARNISHMENT} (compelled by court order regardless of consent) are excluded.
 */
@Service
public class DeductionWrittenConsentService {
    private static final Set<String> VIEW_ROLES = Set.of("hr", "ceo");
    private static final Set<String> EDIT_ROLES = Set.of("hr");
    private static final Set<PayrollDeductionKind> CONSENT_APPLICABLE_KINDS = Set.of(
        PayrollDeductionKind.WARNING_LETTER,
        PayrollDeductionKind.CUSTOMER_RETURN,
        PayrollDeductionKind.OTHER_PRETAX,
        PayrollDeductionKind.OTHER_POST_TAX
    );

    private final DeductionWrittenConsentRepository repository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    public DeductionWrittenConsentService(
        DeductionWrittenConsentRepository repository,
        EmployeeRepository employeeRepository,
        AuditService auditService
    ) {
        this.repository = repository;
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
    }

    public List<DeductionWrittenConsentDto> list(Long employeeId, PayrollDeductionKind kind, UserPrincipal actor) {
        requireRole(actor, VIEW_ROLES);
        return repository.findAll(employeeId, kind);
    }

    public List<DeductionWrittenConsentDto> upsert(DeductionWrittenConsentUpsertRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        if (!CONSENT_APPLICABLE_KINDS.contains(request.deductionKind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ประเภทการหัก " + request.deductionKind()
                    + " ไม่ใช่ประเภทที่ต้องมีหนังสือยินยอม (item (1) หรือคำสั่งศาล/บังคับคดี ไม่ต้องขอความยินยอม)");
        }
        if (!employeeRepository.exists(request.employeeId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Employee not found");
        }
        List<DeductionWrittenConsentDto> before = repository.findAll(request.employeeId(), request.deductionKind());
        repository.upsert(request, actor.employeeId());
        List<DeductionWrittenConsentDto> after = repository.findAll(request.employeeId(), request.deductionKind());
        auditService.record(actor, "UPSERT_DEDUCTION_WRITTEN_CONSENT", "deduction_written_consent",
            request.employeeId(), before, after);
        return after;
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (actor == null || !allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }
}
