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

/** Business logic for the written-consent record (issue #376, task 4). A recordable field, NOT an enforcement
 * gate -- nothing here, and nothing in PayrollCalculator, reads consent_on_file to decide whether a deduction
 * runs. */
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
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลพนักงาน");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
