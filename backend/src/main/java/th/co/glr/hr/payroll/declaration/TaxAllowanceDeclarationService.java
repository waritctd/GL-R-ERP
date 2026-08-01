package th.co.glr.hr.payroll.declaration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.MyTaxAllowanceDeclarationsResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceApplyRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceCapsResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationRegisterResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceOnBehalfRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceReviewRequest;

/**
 * Service layer for the tax-allowance self-declaration workflow (PR A). Every mutating method
 * double-checks its role (the controller's {@code @PreAuthorize} is not the only gate — see
 * {@code PayrollService} for the same idiom) and audits via {@link AuditService}, which
 * deliberately joins the caller's transaction.
 *
 * <p><b>Writes NO SQL against {@code hr.employee_tax_allowance} directly.</b> {@link #apply} is the
 * ONLY method that touches the parent table, and it does so exclusively through three dormant
 * {@link PayrollRepository} methods that had zero callers before this PR:
 * {@code upsertTaxAllowances}, {@code markTaxAllowanceVerified}, {@code
 * setTaxAllowanceVerificationDeadline}. {@code PayrollCalculator.java} and
 * {@code PayrollRepository#findTaxAllowancesByEmployee}'s SQL are untouched by this entire class —
 * see {@code V105}'s header comment for why that separation is the point of this feature.
 */
@Service
public class TaxAllowanceDeclarationService {
    // View: HR + CEO (mirrors PayrollService's PAYROLL_VIEW_ROLES for the register). Edit
    // (approve/reject/apply/on-behalf): HR only.
    private static final Set<String> REGISTER_VIEW_ROLES = Set.of("hr", "ceo");
    private static final Set<String> EDIT_ROLES = Set.of("hr");

    private final TaxAllowanceDeclarationRepository repository;
    private final PayrollRepository payrollRepository;
    private final EmployeeRepository employeeRepository;
    private final TaxAllowanceCapCatalog capCatalog;
    private final AuditService auditService;

    public TaxAllowanceDeclarationService(
        TaxAllowanceDeclarationRepository repository,
        PayrollRepository payrollRepository,
        EmployeeRepository employeeRepository,
        TaxAllowanceCapCatalog capCatalog,
        AuditService auditService
    ) {
        this.repository = repository;
        this.payrollRepository = payrollRepository;
        this.employeeRepository = employeeRepository;
        this.capCatalog = capCatalog;
        this.auditService = auditService;
    }

    // ---- Employee self-service ------------------------------------------------------------

    public MyTaxAllowanceDeclarationsResponse getOwn(int taxYear, UserPrincipal actor) {
        requireEmployeeActor(actor);
        List<TaxAllowanceDeclarationDto> items = repository.findForEmployee(actor.employeeId(), taxYear);
        auditService.record(actor, "VIEW_OWN_TAX_ALLOWANCE_DECLARATIONS", "tax_allowance_declaration", null,
            null, java.util.Map.of("taxYear", taxYear, "count", items.size()));
        return new MyTaxAllowanceDeclarationsResponse(taxYear, items);
    }

    /**
     * Creates a new PENDING declaration for the CALLER. {@code employeeId} is never read from the
     * request body — there is no such field on {@link TaxAllowanceDeclarationSubmitRequest} — only
     * {@code actor.employeeId()}, copying {@code PayrollService#ownPayslipPdf}'s idiom.
     */
    @Transactional
    public TaxAllowanceDeclarationDto submitOwn(TaxAllowanceDeclarationSubmitRequest request, UserPrincipal actor) {
        requireEmployeeActor(actor);
        if (request == null || request.taxYear() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "taxYear is required");
        }
        long employeeId = actor.employeeId();
        int taxYear = request.taxYear();
        int effectiveMonth = normalizeEffectiveMonth(request.effectiveMonth());

        // uq_tad_one_pending_per_employee_year is a hard DB constraint; check first for a clean 409
        // rather than a raw constraint-violation 500.
        if (repository.existsPending(employeeId, taxYear)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีแบบแจ้งค่าลดหย่อนที่รอการอนุมัติสำหรับปีนี้อยู่แล้ว กรุณายกเลิกรายการเดิมก่อนยื่นใหม่");
        }

        PayrollTaxAllowanceInput allowances = toAllowances(request);
        long id = repository.insert(employeeId, taxYear, effectiveMonth, allowances,
            request.documentReference(), employeeId, false);
        TaxAllowanceDeclarationDto created = repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after insert"));
        auditService.record(actor, "SUBMIT_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration", id, null, created);
        return created;
    }

    /**
     * Withdraws the caller's own PENDING declaration. 404 (not 403) if the id belongs to someone
     * else or does not exist — a 403 would leak whether the id exists at all, the same reasoning
     * {@code PayrollService#ownPayslipPdf} already applies.
     */
    @Transactional
    public void withdrawOwn(long declarationId, UserPrincipal actor) {
        requireEmployeeActor(actor);
        TaxAllowanceDeclarationDto existing = repository.findById(declarationId)
            .filter(dto -> dto.employeeId() == actor.employeeId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Declaration not found"));
        if (existing.status() != TaxAllowanceDeclarationStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "เฉพาะรายการที่รออนุมัติเท่านั้นที่ยกเลิกได้");
        }
        int rows = repository.withdrawPending(declarationId, actor.employeeId());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "เฉพาะรายการที่รออนุมัติเท่านั้นที่ยกเลิกได้");
        }
        auditService.record(actor, "WITHDRAW_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, null);
    }

    // ---- HR/CEO register + HR mutations ---------------------------------------------------

    public TaxAllowanceDeclarationRegisterResponse getRegister(
        Integer taxYear, TaxAllowanceDeclarationStatus status, UserPrincipal actor
    ) {
        requireRole(actor, REGISTER_VIEW_ROLES);
        List<TaxAllowanceDeclarationDto> items = repository.findRegister(taxYear, status);
        return new TaxAllowanceDeclarationRegisterResponse(items);
    }

    /** Decision #9: HR creates + auto-approves in one action, for staff who never log in. */
    @Transactional
    public TaxAllowanceDeclarationDto createOnBehalf(TaxAllowanceOnBehalfRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        if (request == null || request.employeeId() == null || request.taxYear() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "employeeId and taxYear are required");
        }
        if (!employeeRepository.exists(request.employeeId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Employee not found");
        }
        long employeeId = request.employeeId();
        int taxYear = request.taxYear();
        int effectiveMonth = normalizeEffectiveMonth(request.effectiveMonth());

        // Clear the way: an employee-submitted PENDING row would otherwise collide with
        // uq_tad_one_pending_per_employee_year. HR's on-behalf action takes precedence.
        repository.withdrawAnyPending(employeeId, taxYear);

        PayrollTaxAllowanceInput allowances = toAllowances(request);
        long id = repository.insert(employeeId, taxYear, effectiveMonth, allowances,
            request.documentReference(), actor.employeeId(), true);

        // Auto-approve, same supersede-first ordering as #approve below.
        repository.supersedeApproved(employeeId, taxYear, id);
        int rows = repository.approve(id, actor.employeeId(), "สร้างและอนุมัติโดยฝ่ายบุคคลในนามพนักงาน");
        if (rows == 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to auto-approve on-behalf declaration");
        }
        TaxAllowanceDeclarationDto created = repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after insert"));
        auditService.record(actor, "CREATE_TAX_ALLOWANCE_DECLARATION_ON_BEHALF", "tax_allowance_declaration",
            id, null, created);
        return created;
    }

    /**
     * PENDING -> APPROVED. Supersedes any other APPROVED declaration for the same employee/tax-year
     * FIRST, in this same transaction — {@code uq_tad_one_approved_per_employee_year} is not
     * deferrable, so approving without superseding first would 500 on the constraint instead of
     * cleanly retiring the old row (decision #7: "a new submission supersedes the previous once
     * approved").
     */
    @Transactional
    public TaxAllowanceDeclarationDto approve(long declarationId, TaxAllowanceReviewRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        TaxAllowanceDeclarationDto existing = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Declaration not found"));
        if (existing.status() != TaxAllowanceDeclarationStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ได้รับการพิจารณาไปแล้ว");
        }
        repository.supersedeApproved(existing.employeeId(), existing.taxYear(), declarationId);
        String reviewerNote = request == null ? null : blankToNull(request.reviewerNote());
        int rows = repository.approve(declarationId, actor.employeeId(), reviewerNote);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ได้รับการพิจารณาไปแล้ว");
        }
        TaxAllowanceDeclarationDto updated = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after approve"));
        auditService.record(actor, "APPROVE_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, updated);
        return updated;
    }

    /** PENDING -> REJECTED. A reason is mandatory (decision #6 / {@code chk_tad_rejected_has_reason}). */
    @Transactional
    public TaxAllowanceDeclarationDto reject(long declarationId, TaxAllowanceReviewRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        String reviewerNote = request == null ? null : blankToNull(request.reviewerNote());
        if (reviewerNote == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลในการปฏิเสธ");
        }
        TaxAllowanceDeclarationDto existing = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Declaration not found"));
        if (existing.status() != TaxAllowanceDeclarationStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ได้รับการพิจารณาไปแล้ว");
        }
        int rows = repository.reject(declarationId, actor.employeeId(), reviewerNote);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ได้รับการพิจารณาไปแล้ว");
        }
        TaxAllowanceDeclarationDto updated = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after reject"));
        auditService.record(actor, "REJECT_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, updated);
        return updated;
    }

    /**
     * Promotes an APPROVED declaration into {@code hr.employee_tax_allowance} (decision #3: go-live
     * is per-employee Apply). Order of operations matters:
     *
     * <ol>
     *   <li>Refuse an already-{@code PROCESSED} month (read-only check) — re-running it would change
     *       a figure already filed on ภ.ง.ด.1.</li>
     *   <li>Conditionally flag the DECLARATION as applied ({@code applied_at IS NULL} in the WHERE
     *       clause) BEFORE writing the allowance table — this is what makes a concurrent double-apply
     *       409 on the second caller without needing an {@code @Version} column: the second racer's
     *       conditional UPDATE simply matches zero rows.</li>
     *   <li>Only then promote into {@code hr.employee_tax_allowance}, via the three dormant methods.</li>
     * </ol>
     */
    @Transactional
    public TaxAllowanceDeclarationDto apply(long declarationId, TaxAllowanceApplyRequest request, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        TaxAllowanceDeclarationDto existing = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Declaration not found"));
        if (existing.status() != TaxAllowanceDeclarationStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องได้รับการอนุมัติก่อนจึงจะนำไปใช้ได้");
        }
        if (existing.appliedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ถูกนำไปใช้แล้ว");
        }
        int appliedEffectiveMonth = request != null && request.effectiveMonth() != null
            ? request.effectiveMonth()
            : existing.effectiveMonth();
        validateMonth(appliedEffectiveMonth);

        LocalDate periodMonth = LocalDate.of(existing.taxYear(), appliedEffectiveMonth, 1);
        Optional<PayrollPeriodDto> period = payrollRepository.findPeriodByMonth(periodMonth);
        if (period.isPresent() && "PROCESSED".equals(period.get().status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เดือน " + periodMonth + " ได้ประมวลผลเงินเดือนไปแล้ว ไม่สามารถย้อนแก้ค่าลดหย่อนได้");
        }

        int flagged = repository.markApplied(declarationId, actor.employeeId(), appliedEffectiveMonth);
        if (flagged == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ถูกนำไปใช้แล้ว หรือยังไม่ได้รับการอนุมัติ");
        }

        EmployeeTaxAllowanceUpsertRequest upsertRequest = toUpsertRequest(existing, appliedEffectiveMonth);
        payrollRepository.upsertTaxAllowances(existing.taxYear(), List.of(upsertRequest), actor.employeeId());
        payrollRepository.markTaxAllowanceVerified(existing.employeeId(), existing.taxYear(), actor.employeeId());
        // Open question (plan doc, "expires_on default"): year-end of the tax year, pending the
        // config-knob decision a later PR is expected to make. Harmless now — nothing reads this
        // deadline except the not-yet-built expiry job; #findTaxAllowancesByEmployee's own gate is
        // verification_status, never this date.
        payrollRepository.setTaxAllowanceVerificationDeadline(
            existing.employeeId(), existing.taxYear(), LocalDate.of(existing.taxYear(), 12, 31));

        TaxAllowanceDeclarationDto updated = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after apply"));
        auditService.record(actor, "APPLY_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, updated);
        return updated;
    }

    // ---- Caps metadata (decision #1: never hardcode caps in the UI) -----------------------

    public TaxAllowanceCapsResponse getCaps(int taxYear, UserPrincipal actor) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return new TaxAllowanceCapsResponse(taxYear, capCatalog.capsFor(taxYear));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void requireEmployeeActor(UserPrincipal actor) {
        if (actor == null || actor.employeeId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (actor == null || !allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private int normalizeEffectiveMonth(Integer effectiveMonth) {
        int resolved = effectiveMonth == null ? 1 : effectiveMonth;
        validateMonth(resolved);
        return resolved;
    }

    private void validateMonth(int month) {
        if (month < 1 || month > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "effectiveMonth must be between 1 and 12");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private PayrollTaxAllowanceInput toAllowances(TaxAllowanceDeclarationSubmitRequest request) {
        return new PayrollTaxAllowanceInput(
            zero(request.spouseAllowance()), zero(request.childAllowance()), zero(request.parentCareAllowance()),
            zero(request.disabledCareAllowance()), zero(request.maternityAllowance()), zero(request.lifeInsuranceAllowance()),
            zero(request.healthInsuranceAllowance()), zero(request.parentHealthInsuranceAllowance()), zero(request.rmfAllowance()),
            zero(request.ssfAllowance()), zero(request.pensionInsuranceAllowance()), zero(request.thaiEsgAllowance()),
            zero(request.homeLoanInterestAllowance()), zero(request.educationDonation()), zero(request.generalDonation()),
            zero(request.politicalDonation()),
            zeroInt(request.childCount()), zeroInt(request.childCountDouble()), zeroInt(request.disabledCareCount()),
            Boolean.TRUE.equals(request.disabilityCardHolder()),
            request.parentCareCount() == null ? 0 : request.parentCareCount()
        );
    }

    private PayrollTaxAllowanceInput toAllowances(TaxAllowanceOnBehalfRequest request) {
        return new PayrollTaxAllowanceInput(
            zero(request.spouseAllowance()), zero(request.childAllowance()), zero(request.parentCareAllowance()),
            zero(request.disabledCareAllowance()), zero(request.maternityAllowance()), zero(request.lifeInsuranceAllowance()),
            zero(request.healthInsuranceAllowance()), zero(request.parentHealthInsuranceAllowance()), zero(request.rmfAllowance()),
            zero(request.ssfAllowance()), zero(request.pensionInsuranceAllowance()), zero(request.thaiEsgAllowance()),
            zero(request.homeLoanInterestAllowance()), zero(request.educationDonation()), zero(request.generalDonation()),
            zero(request.politicalDonation()),
            zeroInt(request.childCount()), zeroInt(request.childCountDouble()), zeroInt(request.disabledCareCount()),
            Boolean.TRUE.equals(request.disabilityCardHolder()),
            request.parentCareCount() == null ? 0 : request.parentCareCount()
        );
    }

    private java.math.BigDecimal zero(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    private int zeroInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** Straight field-for-field copy from a declaration onto the parent table's upsert shape. */
    private EmployeeTaxAllowanceUpsertRequest toUpsertRequest(TaxAllowanceDeclarationDto dto, int appliedEffectiveMonth) {
        PayrollTaxAllowanceInput allowances = dto.allowances();
        return new EmployeeTaxAllowanceUpsertRequest(
            dto.employeeId(),
            allowances.spouseAllowance(), allowances.childAllowance(), allowances.parentCareAllowance(),
            allowances.disabledCareAllowance(), allowances.maternityAllowance(), allowances.lifeInsuranceAllowance(),
            allowances.healthInsuranceAllowance(), allowances.parentHealthInsuranceAllowance(), allowances.rmfAllowance(),
            allowances.ssfAllowance(), allowances.pensionInsuranceAllowance(), allowances.thaiEsgAllowance(),
            allowances.homeLoanInterestAllowance(), allowances.educationDonation(), allowances.generalDonation(),
            allowances.politicalDonation(),
            allowances.childCount(), allowances.childCountDouble(), allowances.disabledCareCount(),
            allowances.disabilityCardHolder(), allowances.parentCareCount(),
            appliedEffectiveMonth, dto.documentReference()
        );
    }
}
