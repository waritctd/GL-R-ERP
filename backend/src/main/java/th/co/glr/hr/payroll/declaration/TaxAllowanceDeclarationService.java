package th.co.glr.hr.payroll.declaration;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.payroll.PayrollAllowanceEstimateResult;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import java.io.IOException;
import java.math.BigDecimal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01Details;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormAssembler;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.MyTaxAllowanceDeclarationsResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceApplyRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDownload;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceAttachmentDto;
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

    // Real MIME allowlist (2026-08-01 evidence PR) -- AttachmentController#upload passes Set.of(),
    // which disables type checking entirely; deliberately NOT copying that here. ล.ย.01 evidence is
    // a scanned/photographed certificate or receipt, never anything else.
    private static final Set<String> EVIDENCE_MIME_TYPES =
        Set.of("application/pdf", "image/jpeg", "image/png");

    // V135 (feat/tax-allowance-sections): mirrors TAX_ALLOWANCE_GROUPS' five `key`s in
    // frontend/src/features/taxAllowance/taxAllowanceSchema.js. No shared enum exists between the
    // frontend and backend for this grouping -- unlike TaxAllowanceCapEntry's `category` strings,
    // which TaxAllowanceCapCatalog owns authoritatively, the five-section grouping is a UI
    // information-architecture concept with no backend equivalent to reuse. Keep both lists in sync
    // by hand if a section is ever added, renamed, or removed.
    /**
     * One key per ข้อ of แบบ ล.ย.01, plus {@code signed_form} for the signed scan the employee
     * returns before HR will accept the filing.
     *
     * <p>Replaces the five invented category keys (family/insurance/savings/housing/donation) that
     * predate the form restructure. Mirrored BY HAND in {@code mockApi.js}'s
     * {@code TAX_ALLOWANCE_SECTION_KEYS} and in {@code LOR_YOR_01_SECTIONS} — nothing enforces the
     * three stay in step, so a key added to the form and not here uploads cleanly under mocks and
     * 400s against this service. ข้อ 11 and ข้อ 13 are absent deliberately: neither is fillable, so
     * neither can carry evidence.
     */
    /** The bucket holding the signed, scanned form — the one attachment approval depends on. */
    static final String SIGNED_FORM_SECTION_KEY = "signed_form";

    private static final Set<String> EVIDENCE_SECTION_KEYS =
        Set.of("item3", "item4", "item5", "item6", "item7", "item8", "item9", "item10",
            "item12", "item14", "item15", "signed_form");

    private final TaxAllowanceDeclarationRepository repository;
    private final PayrollRepository payrollRepository;
    private final EmployeeRepository employeeRepository;
    private final TaxAllowanceCapCatalog capCatalog;
    private final AuditService auditService;
    private final FileStorageService fileStorage;
    private final PayrollService payrollService;
    private final NotificationRepository notifications;
    /**
     * วัน/เดือน/ปี ที่แจ้งรายการ is a Thai calendar date on a Thai tax form, so it must be derived in
     * Bangkok. Bare {@code LocalDate.now()} (as used elsewhere in this class) reads the JVM default,
     * which on a UTC CI box is the PREVIOUS day between 17:00 and 23:59 Bangkok — a filing dated a
     * day early. Same constant and same reasoning as OvertimeService.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TaxAllowanceDeclarationService.class);

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final AppProperties appProperties;
    private final LorYor01Renderer lorYor01Renderer;

    public TaxAllowanceDeclarationService(
        TaxAllowanceDeclarationRepository repository,
        PayrollRepository payrollRepository,
        EmployeeRepository employeeRepository,
        TaxAllowanceCapCatalog capCatalog,
        AuditService auditService,
        FileStorageService fileStorage,
        PayrollService payrollService,
        NotificationRepository notifications,
        AppProperties appProperties,
        LorYor01Renderer lorYor01Renderer
    ) {
        this.repository = repository;
        this.payrollRepository = payrollRepository;
        this.employeeRepository = employeeRepository;
        this.capCatalog = capCatalog;
        this.auditService = auditService;
        this.fileStorage = fileStorage;
        this.payrollService = payrollService;
        this.notifications = notifications;
        this.appProperties = appProperties;
        this.lorYor01Renderer = lorYor01Renderer;
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุปีภาษี");
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
            request.documentReference(), employeeId, false, request.lorYor01());
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้"));
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุรหัสพนักงานและปีภาษี");
        }
        if (!employeeRepository.exists(request.employeeId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบข้อมูลพนักงาน");
        }
        long employeeId = request.employeeId();
        int taxYear = request.taxYear();
        int effectiveMonth = normalizeEffectiveMonth(request.effectiveMonth());

        // Clear the way: an employee-submitted PENDING row would otherwise collide with
        // uq_tad_one_pending_per_employee_year. HR's on-behalf action takes precedence.
        repository.withdrawAnyPending(employeeId, taxYear);

        PayrollTaxAllowanceInput allowances = toAllowances(request);
        long id = repository.insert(employeeId, taxYear, effectiveMonth, allowances,
            request.documentReference(), actor.employeeId(), true, request.lorYor01());

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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้"));
        if (existing.status() != TaxAllowanceDeclarationStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ได้รับการพิจารณาไปแล้ว");
        }
        requireSignedForm(declarationId);
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
        notifyOwner(existing.employeeId(), "TAX_ALLOWANCE_APPROVED",
            "แบบแจ้ง ล.ย.01 ได้รับการอนุมัติ",
            "ฝ่ายบุคคลอนุมัติแบบแจ้งค่าลดหย่อนภาษีปี " + existing.taxYear() + " แล้ว");
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้"));
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
        // The reason travels in the notification body: it is the only thing that tells the employee
        // what to change, and re-opening the page to find it is exactly the round trip this avoids.
        notifyOwner(existing.employeeId(), "TAX_ALLOWANCE_REJECTED",
            "แบบแจ้ง ล.ย.01 ถูกปฏิเสธ",
            "ฝ่ายบุคคลปฏิเสธแบบแจ้งค่าลดหย่อนภาษีปี " + existing.taxYear() + ": " + reviewerNote);
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้"));
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

        // Open question (plan doc, "expires_on default"), resolved here (2026-08-01, the yearly-
        // expiry PR): year-end of the declaration's own tax year. Shared verbatim between the
        // DECLARATION's own expires_on (read by TaxAllowanceExpiryWorker's sweep, via
        // findExpirySweepCandidates) and the PARENT table's verification_deadline (read by nothing
        // yet at THIS moment, but restored identically by #reverify) -- one LocalDate computed once
        // so the two can never independently drift.
        LocalDate expiresOn = LocalDate.of(existing.taxYear(), 12, 31);
        int flagged = repository.markApplied(declarationId, actor.employeeId(), appliedEffectiveMonth, expiresOn);
        if (flagged == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ถูกนำไปใช้แล้ว หรือยังไม่ได้รับการอนุมัติ");
        }

        EmployeeTaxAllowanceUpsertRequest upsertRequest = toUpsertRequest(existing, appliedEffectiveMonth);
        payrollRepository.upsertTaxAllowances(existing.taxYear(), List.of(upsertRequest), actor.employeeId());
        payrollRepository.markTaxAllowanceVerified(existing.employeeId(), existing.taxYear(), actor.employeeId());
        payrollRepository.setTaxAllowanceVerificationDeadline(existing.employeeId(), existing.taxYear(), expiresOn);

        TaxAllowanceDeclarationDto updated = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after apply"));
        auditService.record(actor, "APPLY_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, updated);
        return updated;
    }

    // ---- Caps metadata (decision #1: never hardcode caps in the UI) -----------------------

    public TaxAllowanceCapsResponse getCaps(int taxYear, UserPrincipal actor) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "กรุณาเข้าสู่ระบบก่อนใช้งาน");
        }
        return new TaxAllowanceCapsResponse(taxYear, capCatalog.capsFor(taxYear));
    }

    // ---- Yearly expiry (decision #10) ------------------------------------------------------

    /**
     * The sweep {@link TaxAllowanceExpiryWorker}'s {@code @Scheduled} method triggers. ALL the
     * logic lives here (not on the worker) so tests drive it directly with no scheduler involved —
     * see {@code CustomerQuotationService#expireOverdueQuotations} for the identical split this
     * mirrors.
     *
     * <p>THE trap (plan doc): for each candidate this calls {@code
     * PayrollRepository#expireTaxAllowanceVerification(employeeId, taxYear)}, which flips EVERY
     * dated row on the parent table for that employee/tax-year — never just the one row this
     * declaration happens to reference. Expiring only the latest dated row would make {@code
     * findTaxAllowancesByEmployee}'s {@code DISTINCT ON ... ORDER BY effective_month DESC} silently
     * fall back to an OLDER still-VERIFIED row instead of returning nothing.
     *
     * <p>Never retro-alters an already-filed month: this only flips read-time gating columns
     * ({@code hr.tax_allowance_declaration.status}/{@code hr.employee_tax_allowance
     * .verification_status}), which {@code PayrollService#preview} resolves FRESH on every call —
     * an already-{@code PROCESSED} period's persisted {@code hr.payroll_line} rows are never
     * touched by any statement in this method.
     *
     * <p>Idempotent by construction: {@link TaxAllowanceDeclarationRepository#expireApplied} only
     * matches a row that is still {@code APPROVED AND applied_at IS NOT NULL}, so a second sweep
     * over an already-expired row (which {@link TaxAllowanceDeclarationRepository
     * #findExpirySweepCandidates} would not even select again, since it too filters on {@code
     * status = 'APPROVED'}) is a safe no-op rather than a duplicate transition.
     *
     * <p>No {@link AuditService} call — a scheduled system sweep has no {@link UserPrincipal}
     * actor, matching {@code CustomerQuotationService#expireOverdueQuotations}'s own choice not to
     * force one.
     *
     * @return the number of declarations flipped to EXPIRED, for the worker to log and tests to
     *     assert against without a second query.
     */
    @Transactional
    public int expireOverdueVerifications() {
        List<TaxAllowanceDeclarationRepository.ExpirySweepCandidate> candidates =
            repository.findExpirySweepCandidates(LocalDate.now());
        int expiredCount = 0;
        for (TaxAllowanceDeclarationRepository.ExpirySweepCandidate candidate : candidates) {
            int rows = repository.expireApplied(candidate.declarationId());
            if (rows > 0) {
                payrollRepository.expireTaxAllowanceVerification(candidate.employeeId(), candidate.taxYear());
                // Notified per row, inside the `rows > 0` guard: a candidate whose conditional
                // UPDATE matched nothing (a concurrent sweep already flipped it) must not produce a
                // second notification for the same expiry.
                notifyOwner(candidate.employeeId(), "TAX_ALLOWANCE_EXPIRED",
                    "แบบแจ้ง ล.ย.01 หมดอายุ",
                    "แบบแจ้งค่าลดหย่อนภาษีปี " + candidate.taxYear()
                        + " หมดอายุแล้ว กรุณายื่นฉบับใหม่เพื่อคงสิทธิลดหย่อน");
                expiredCount++;
            }
        }
        return expiredCount;
    }

    /**
     * HR re-verifies an EXPIRED declaration against supporting documents: EXPIRED -> APPROVED, a
     * fresh {@code expires_on}/{@code verification_deadline}. The mirror of {@link
     * #expireOverdueVerifications}: {@code payrollRepository.markTaxAllowanceVerified} restores the
     * WHOLE year's lineage on the parent table (every dated row, same as expiry flips every dated
     * row) — never just the row {@code declarationId} references.
     *
     * <p>New deadline policy: twelve months from today. This is a provisional choice (the plan
     * doc's own "expires_on default" open question was never settled by the owner) — flagged in the
     * PR body for confirmation, not silently assumed correct.
     */
    @Transactional
    public TaxAllowanceDeclarationDto reverify(long declarationId, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        TaxAllowanceDeclarationDto existing = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้"));
        if (existing.status() != TaxAllowanceDeclarationStatus.EXPIRED) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องเป็นรายการที่หมดอายุแล้วเท่านั้นจึงจะยืนยันใหม่ได้");
        }
        LocalDate newExpiresOn = LocalDate.now().plusYears(1);
        int rows = repository.reverify(declarationId, actor.employeeId(), newExpiresOn);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องเป็นรายการที่หมดอายุแล้วเท่านั้นจึงจะยืนยันใหม่ได้");
        }
        payrollRepository.markTaxAllowanceVerified(existing.employeeId(), existing.taxYear(), actor.employeeId());
        payrollRepository.setTaxAllowanceVerificationDeadline(existing.employeeId(), existing.taxYear(), newExpiresOn);

        TaxAllowanceDeclarationDto updated = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after reverify"));
        auditService.record(actor, "REVERIFY_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, updated);
        return updated;
    }

    // ---- Tax-effect estimate (decision #4) -------------------------------------------------

    /**
     * "What this saves me" — decision #4. NO {@code employeeId} parameter exists anywhere on this
     * method or the HTTP endpoint above it: the employee whose situation is being estimated is
     * ALWAYS {@code actor.employeeId()}, the strongest form of the self-scoping guard (copy of
     * {@code PayrollService#ownPayslipPdf}'s idiom). Reuses {@link TaxAllowanceDeclarationSubmitRequest}
     * verbatim as the request shape — the same 16 allowances + counts a real submission takes,
     * since an estimate is "what would this submission do to my tax", not a different shape.
     *
     * <p>All arithmetic happens in {@code PayrollService#estimateAllowanceEffect}, which runs the
     * REAL {@code PayrollCalculator} twice — never reimplemented here or in the frontend.
     */
    public PayrollAllowanceEstimateResult estimateOwn(TaxAllowanceDeclarationSubmitRequest request, UserPrincipal actor) {
        requireEmployeeActor(actor);
        if (request == null || request.taxYear() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุปีภาษี");
        }
        int taxYear = request.taxYear();
        int effectiveMonth = normalizeEffectiveMonth(request.effectiveMonth());
        PayrollTaxAllowanceInput proposedAllowances = toAllowances(request);
        return payrollService.estimateAllowanceEffect(actor.employeeId(), taxYear, effectiveMonth, proposedAllowances, actor);
    }

    // ---- Evidence attachments (decision #5: owning employee + HR, server-enforced) ---------
    //
    // The download gate is the security-critical part of this whole feature. Deliberately NOT
    // AttachmentController#requireAttachmentAccess's shape — that short-circuits for the uploader
    // (actor.id() == dto.uploadedBy()), so an HR user who uploaded on an employee's behalf would
    // keep permanent access even after losing the "hr" role. #requireOwnerOrHr below is re-resolved
    // from the PARENT declaration's CURRENT employee_id/role check on EVERY call, including
    // download, with no such bypass. CEO is deliberately excluded, even though CEO can read the
    // declaration's AMOUNTS via #getRegister — the amounts are a payroll figure; evidence is a
    // personal medical/insurance/family document. 404, not 403, on every failure here (never leaks
    // whether an attachment id exists to a caller with no business seeing it).

    @Transactional
    public TaxAllowanceAttachmentDto uploadAttachment(
        long declarationId, MultipartFile file, String sectionKey, UserPrincipal actor
    ) {
        requireOwnerOrHr(declarationId, actor);
        String normalizedSectionKey = normalizeSectionKey(sectionKey);
        // V134 storage-durability fix: this evidence file goes straight to the database now -- see
        // FileStorageService#storeInDatabase's javadoc.
        FileStorageService.StoredContent stored =
            fileStorage.storeInDatabase("tax-allowance-declaration", declarationId, file, EVIDENCE_MIME_TYPES);
        // uploaded_by is actor.employeeId(), NOT actor.id() -- the column FKs hr.employee, and for
        // an HR-on-behalf upload actor.employeeId() is HR's own employee row, correctly distinct
        // from the declaration's beneficiary employee_id.
        TaxAllowanceAttachmentDto attachment = repository.saveAttachmentWithContent(declarationId, stored.fileName(),
            stored.storageKey(), stored.mimeType(), stored.fileSize(), actor.employeeId(), normalizedSectionKey,
            stored.content());
        auditService.record(actor, "UPLOAD_TAX_ALLOWANCE_ATTACHMENT", "tax_allowance_declaration",
            declarationId, null, attachment);
        return attachment;
    }

    /**
     * Blank/whitespace-only ({@code @RequestParam(required = false)} on the controller means an
     * omitted form field arrives as {@code null}, but an EMPTY one arrives as {@code ""}) normalizes
     * to {@code null} ("general/uncategorized", V135's own nullable-by-design choice — see that
     * migration's header). Anything else must be one of {@link #EVIDENCE_SECTION_KEYS} or the
     * upload is rejected outright, rather than silently storing an unrecognised tag the frontend's
     * per-section filter would never match.
     */
    private String normalizeSectionKey(String sectionKey) {
        if (sectionKey == null || sectionKey.isBlank()) {
            return null;
        }
        if (!EVIDENCE_SECTION_KEYS.contains(sectionKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sectionKey ไม่ถูกต้อง");
        }
        return sectionKey;
    }

    public List<TaxAllowanceAttachmentDto> listAttachments(long declarationId, UserPrincipal actor) {
        requireOwnerOrHr(declarationId, actor);
        return repository.findAttachments(declarationId);
    }

    /**
     * Resolves an attachment id, RE-CHECKING access against its parent declaration right here —
     * this method is called on every single download, so there is no cached/short-circuited
     * decision from upload time to go stale.
     */
    public TaxAllowanceAttachmentDownload getAttachmentForDownload(long attachmentId, UserPrincipal actor) {
        TaxAllowanceAttachmentDto attachment = repository.findAttachment(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบนี้"));
        requireOwnerOrHr(attachment.declarationId(), actor);
        if (attachment.deletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไฟล์นี้ถูกลบแล้ว");
        }
        TaxAllowanceDeclarationRepository.AttachmentFileLocation location =
            repository.findAttachmentFileLocation(attachmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบนี้"));
        // V134 storage-durability fix: availability is checked only AFTER the 404/ownership/
        // tombstone checks above, matching LeaveService#resolveAttachmentForDownload's ordering --
        // see that method's javadoc for why the order itself is a security property.
        if (!bytesAvailable(location)) {
            throw new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อฝ่ายบุคคล");
        }
        return new TaxAllowanceAttachmentDownload(attachment, location.filePath(), location.storageState());
    }

    /** Mirrors {@code LeaveService#bytesAvailable} -- see that method's javadoc. */
    private boolean bytesAvailable(TaxAllowanceDeclarationRepository.AttachmentFileLocation location) {
        return switch (location.storageState()) {
            case "DATABASE" -> true;
            case "DISK_LEGACY" -> fileStorage.existsOnDisk(location.filePath());
            default -> false;
        };
    }

    /** Tombstone only — copy of {@code FactoryQuoteService#deleteAttachment}'s shape, never a hard delete. */
    @Transactional
    public void deleteAttachment(long attachmentId, String reason, UserPrincipal actor) {
        TaxAllowanceAttachmentDto attachment = repository.findAttachment(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบนี้"));
        requireOwnerOrHr(attachment.declarationId(), actor);
        if (attachment.deletedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ไฟล์นี้ถูกลบไปแล้ว");
        }
        int rows = repository.tombstoneAttachment(attachmentId, actor.employeeId(), reason);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ไฟล์นี้ถูกลบไปแล้ว");
        }
        auditService.record(actor, "DELETE_TAX_ALLOWANCE_ATTACHMENT", "tax_allowance_declaration",
            attachment.declarationId(), attachment, null);
    }

    /**
     * The one check every evidence method above funnels through. Re-resolves {@code declaration}
     * FRESH from the repository every call — never trusts a value computed earlier in the same
     * request, let alone a cached one from upload time. 404 (not 403) on failure, matching every
     * other self-scoped check in this class.
     */
    private TaxAllowanceDeclarationDto requireOwnerOrHr(long declarationId, UserPrincipal actor) {
        TaxAllowanceDeclarationDto declaration = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้"));
        boolean isOwner = actor != null && actor.employeeId() != null && actor.employeeId() == declaration.employeeId();
        boolean isHr = actor != null && "hr".equals(actor.role());
        if (!isOwner && !isHr) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้");
        }
        return declaration;
    }

    // ---- helpers ----------------------------------------------------------------------------

    /**
     * Notifies the declaration's OWNER — never the acting reviewer. Every caller below passes the
     * declaration's own {@code employeeId}, not {@code actor.employeeId()}: HR approving on behalf
     * of someone must not send itself the notice.
     *
     * <p>Uses the generic {@link NotificationRepository#insert} rather than {@code notifyEmployee}/
     * {@code notifyEmployeeForPricingRequest}: those hardcode a ticket/pricing-request link and
     * resolve their title through the ticket-scoped {@code TICKET_EVENT_TITLES} map, neither of
     * which fits ล.ย.01. The link is the employee's own declaration page.
     *
     * <p>Joins the caller's transaction, like {@link AuditService#record} — an approve that rolls
     * back must not leave a notification claiming it happened.
     */
    private void notifyOwner(long ownerEmployeeId, String type, String title, String message) {
        notifications.insert(ownerEmployeeId, type, title, message, "/tax-allowance");
    }

    private void requireEmployeeActor(UserPrincipal actor) {
        if (actor == null || actor.employeeId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /**
     * แบบ ล.ย.01 must be signed before HR can approve it (owner decision #3, 2026-08-08).
     *
     * <p>The printed form ends in "ลงชื่อ...ผู้มีเงินได้", and that signature is what makes the
     * declaration a statement the employee is accountable for. Approving an unsigned one records HR
     * as having accepted a filing nobody attested to.
     *
     * <p>The employee-facing page already refuses to submit without the scan, but that is a
     * courtesy and not a control: the submit endpoint takes JSON and cannot see whether a file
     * exists, and a caller bypassing the UI is not hypothetical. This is where it holds.
     *
     * <p><b>Checked at APPROVE, not at submit — and it has to be.</b> The attachment is uploaded
     * against a declarationId, which does not exist until the submit has already succeeded, so a
     * submit-time gate would be unsatisfiable by construction.
     *
     * <p>Ordered AFTER the role and status checks so those keep their existing failure modes: a
     * non-HR caller still gets 403 and an already-decided declaration still gets its own conflict,
     * rather than either being masked by a complaint about a missing file.
     *
     * <p>Deleted attachments do not count — {@code findAttachments} excludes tombstoned rows — so
     * withdrawing the signed scan withdraws the basis for approving.
     */
    private void requireSignedForm(long declarationId) {
        boolean signed = repository.findAttachments(declarationId).stream()
            .anyMatch(attachment -> SIGNED_FORM_SECTION_KEY.equals(attachment.sectionKey()));
        if (!signed) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยังอนุมัติไม่ได้ — ต้องมีแบบ ล.ย.01 ที่ผู้มีเงินได้ลงนามแล้วแนบไว้ก่อน");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (actor == null || !allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private int normalizeEffectiveMonth(Integer effectiveMonth) {
        int resolved = effectiveMonth == null ? 1 : effectiveMonth;
        validateMonth(resolved);
        return resolved;
    }

    private void validateMonth(int month) {
        if (month < 1 || month > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "effectiveMonth ต้องอยู่ระหว่าง 1 ถึง 12");
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

    // ---- แบบ ล.ย.01 PDF -----------------------------------------------------------------------

    /**
     * The official ล.ย.01 for a SAVED declaration, as a flattened PDF. Owner or HR only — same gate
     * as the evidence files, so a declaration's rendered form is never more widely readable than the
     * documents behind it.
     */
    public byte[] renderLorYor01(long declarationId, UserPrincipal actor) {
        TaxAllowanceDeclarationDto declaration = requireOwnerOrHr(declarationId, actor);
        LocalDate declaredOn = declaration.submittedAt() == null
            ? LocalDate.now(BUSINESS_ZONE)
            : declaration.submittedAt().atZoneSameInstant(BUSINESS_ZONE).toLocalDate();
        return render(declaration.employeeId(), declaration.taxYear(), declaration.allowances(),
            declaration.lorYor01(), declaredOn);
    }

    /**
     * The official ล.ย.01 for an UNSAVED draft.
     *
     * <p>This exists because of the order the paper process runs in: the employee has to sign the
     * form before HR will accept it, so they need the filled PDF while the declaration is still
     * being typed and has no id yet. Mirrors the {@code /declarations/me/estimate} idiom — a request
     * body in, a result out, nothing persisted.
     *
     * <p>Always rendered for the ACTOR's own employee id, never a target in the body, so this cannot
     * become a way to read someone else's ข้อ 13 figure.
     */
    public byte[] renderLorYor01Draft(TaxAllowanceDeclarationSubmitRequest request, UserPrincipal actor) {
        requireEmployeeActor(actor);
        long employeeId = actor.employeeId();
        int taxYear = request.taxYear() == null ? LocalDate.now(BUSINESS_ZONE).getYear() : request.taxYear();
        return render(employeeId, taxYear, toAllowances(request), request.lorYor01(),
            LocalDate.now(BUSINESS_ZONE));
    }

    private byte[] render(long employeeId, int taxYear, PayrollTaxAllowanceInput allowances,
                          LorYor01Details form, LocalDate declaredOn) {
        // ข้อ 13 is derived from what payroll actually recorded, never from the request — see
        // PayrollRepository#sumSocialSecurityForTaxYear.
        BigDecimal socialSecurity = payrollRepository.sumSocialSecurityForTaxYear(employeeId, taxYear);
        LorYor01FormData data = LorYor01FormAssembler.assemble(
            allowances, form,
            appProperties.getPayroll().getEmployer().getCompanyNameTh(),
            socialSecurity, declaredOn);
        try {
            return lorYor01Renderer.render(data);
        } catch (IOException e) {
            // ApiException carries no cause; log the real one rather than swallowing the stack.
            LOG.error("Failed to render ล.ย.01 for employee {} tax year {}", employeeId, taxYear, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "สร้างไฟล์ PDF แบบ ล.ย.01 ไม่สำเร็จ");
        }
    }
}
