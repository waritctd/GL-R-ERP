package th.co.glr.hr.payroll.declaration;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeDto;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.EmployeeRepository.LorYor01HeaderSource;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollAllowanceEstimateResult;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import java.io.IOException;
import java.math.BigDecimal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01AddressPayload;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01Details;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01HeaderPrefill;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormAssembler;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.MyTaxAllowanceDeclarationsResponse;
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
 * <p><b>Writes NO SQL against {@code hr.employee_tax_allowance} directly.</b> {@code
 * #promoteToPayrollAllowances} is the ONLY method that touches the parent table, and it does so
 * exclusively through {@link PayrollRepository}: {@code deleteMidYearTaxAllowances},
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

    /**
     * A ล.ย.01 declares the employee's allowances for a WHOLE TAX YEAR (owner ruling 2026-08-31).
     * There is no "in force from month N" any more: HR's approval makes the declaration effective
     * for every payroll month of its {@code tax_year}, so every row this service writes -- both
     * {@code hr.tax_allowance_declaration.effective_month} and the {@code effective_month} half of
     * {@code hr.employee_tax_allowance}'s primary key -- is dated 1.
     *
     * <p><b>The column stays; only the choice goes.</b> {@code hr.employee_tax_allowance}'s PK is
     * {@code (employee_id, tax_year, effective_month)} (V93) and rows written before this ruling
     * still hold months 2-12, so {@code PayrollRepository#findTaxAllowancesByEmployee}'s {@code
     * effective_month <= :month ORDER BY effective_month DESC} resolution is deliberately left
     * alone -- re-running an already-filed month still reproduces the figures it was filed on.
     * What that resolution WOULD do to a fresh whole-year row is the trap {@link
     * #promoteToPayrollAllowances} exists to close: month 1 loses the {@code DESC} ordering to any
     * surviving month-7 row, so the new declaration would be silently ignored from July onward.
     */
    static final int WHOLE_YEAR_EFFECTIVE_MONTH = 1;

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
    /** The HR role fan-out only — see {@link #notifyOwner} for why the employee-facing path below
     * uses {@link #notificationService} instead. */
    private final NotificationRepository notifications;
    private final NotificationService notificationService;
    /**
     * วัน/เดือน/ปี ที่แจ้งรายการ is a Thai calendar date on a Thai tax form, so it must be derived in
     * Bangkok. Bare {@code LocalDate.now()} (as used elsewhere in this class) reads the JVM default,
     * which on a UTC CI box is the PREVIOUS day between 17:00 and 23:59 Bangkok — a filing dated a
     * day early. Same constant and same reasoning as OvertimeService.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TaxAllowanceDeclarationService.class);

    /**
     * The shared {@code hr.audit} sink, same logger name {@code EmployeeService} writes its own
     * {@code sensitive_data_access} lines to — one stream to grep for every read of
     * {@code hr_restricted}, rather than one per feature. Distinct from {@code auditService}, which
     * writes structured business events to the database; this is the PII-access trail.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("th.co.glr.hr.audit");

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
        NotificationService notificationService,
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
        this.notificationService = notificationService;
        this.appProperties = appProperties;
        this.lorYor01Renderer = lorYor01Renderer;
    }

    // ---- Employee self-service ------------------------------------------------------------

    public MyTaxAllowanceDeclarationsResponse getOwn(int taxYear, UserPrincipal actor) {
        requireEmployeeActor(actor);
        List<TaxAllowanceDeclarationDto> items = repository.findForEmployee(actor.employeeId(), taxYear);
        auditService.record(actor, "VIEW_OWN_TAX_ALLOWANCE_DECLARATIONS", "tax_allowance_declaration", null,
            null, java.util.Map.of("taxYear", taxYear, "count", items.size()));
        return new MyTaxAllowanceDeclarationsResponse(taxYear, items, headerPrefill(actor));
    }

    /**
     * Owner decision #4's read half: seed the ล.ย.01 header from the employee master so nobody
     * retypes a 13-digit tax ID and a thirteen-part address on every filing.
     *
     * <p><b>The employee is {@code actor.employeeId()} and there is no other way to name one.</b>
     * {@code GET /declarations/me} takes a {@code year} and nothing else, so there is no
     * caller-supplied id for a forged request to smuggle — the same discipline as
     * {@link #renderLorYor01Draft}. {@link EmployeeRepository#findLorYor01HeaderSource}'s own
     * {@code WHERE} clause is what enforces it in SQL.
     *
     * <p>The tax ID is a restricted-schema read, so it gets the same {@code sensitive_data_access}
     * audit line {@code EmployeeService#get} emits for HR's read of the same column — a
     * self-service path is not a reason to make an access to {@code hr_restricted} invisible. Logged
     * only when a value was actually returned: an audit trail that records reads which disclosed
     * nothing trains its readers to skim it.
     *
     * <p>Never throws. A prefill is a convenience, and an employee whose master row is incomplete
     * (or missing entirely — an account not yet linked to an employee is already refused above by
     * {@code requireEmployeeActor}) must still be able to open the form and type the header by hand.
     */
    private LorYor01HeaderPrefill headerPrefill(UserPrincipal actor) {
        LorYor01HeaderSource source = employeeRepository
            .findLorYor01HeaderSource(actor.employeeId())
            .orElse(null);
        if (source == null) {
            return LorYor01HeaderPrefill.empty();
        }
        if (source.taxId() != null && !source.taxId().isBlank()) {
            AUDIT.info(
                "sensitive_data_access action=PREFILL_OWN_LOR_YOR_01_HEADER actorId={} actorEmail=\"{}\""
                    + " targetEmployeeId={} fields=\"restricted_pii.tax_id\"",
                actor.id(), actor.email(), actor.employeeId());
        }
        return new LorYor01HeaderPrefill(
            blankToNull(source.taxId()),
            blankToNull(source.firstNameTh()),
            blankToNull(source.lastNameTh()),
            maritalStateFromMaster(source.maritalStatus()),
            new LorYor01AddressPayload(
                blankToNull(source.building()), blankToNull(source.roomNo()),
                blankToNull(source.floor()), blankToNull(source.village()),
                blankToNull(source.houseNo()), blankToNull(source.moo()),
                blankToNull(source.soi()), blankToNull(source.junction()),
                blankToNull(source.road()), blankToNull(source.subDistrict()),
                blankToNull(source.district()), blankToNull(source.province()),
                blankToNull(source.postalCode())));
    }

    /**
     * {@code hr.employee.marital_status} -> ข้อ 1's enum. The exact inverse of
     * {@link #maritalStatusForMaster}, and it must stay that way: prefill then approve then prefill
     * again has to be a fixed point, or every re-filing would silently flip the employee's สถานภาพ.
     *
     * <p>Only the two values that mapping can produce are recognised. That column is
     * {@code VARCHAR(30)} with no CHECK constraint, so it can hold anything a decade of HR data
     * entry put there; an unrecognised value leaves ข้อ 1 un-ticked for the employee to answer
     * rather than guessing a legal status on their behalf. {@code WIDOWED} and
     * {@code DIED_DURING_YEAR} are unmapped in BOTH directions — see {@link #maritalStatusForMaster}
     * for why.
     */
    private String maritalStateFromMaster(String maritalStatus) {
        if (maritalStatus == null) {
            return null;
        }
        return switch (maritalStatus.trim()) {
            case "โสด" -> "SINGLE";
            case "สมรส" -> "MARRIED";
            default -> null;
        };
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

        // uq_tad_one_pending_per_employee_year is a hard DB constraint; check first for a clean 409
        // rather than a raw constraint-violation 500.
        if (repository.existsPending(employeeId, taxYear)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีแบบแจ้งค่าลดหย่อนที่รอการอนุมัติสำหรับปีนี้อยู่แล้ว กรุณายกเลิกรายการเดิมก่อนยื่นใหม่");
        }

        PayrollTaxAllowanceInput allowances = toAllowances(request);
        long id = repository.insert(employeeId, taxYear, allowances,
            request.documentReference(), employeeId, false, request.lorYor01());
        TaxAllowanceDeclarationDto created = repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after insert"));
        auditService.record(actor, "SUBMIT_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration", id, null, created);
        notifyHrOfSubmission(created);
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

        // Clear the way: an employee-submitted PENDING row would otherwise collide with
        // uq_tad_one_pending_per_employee_year. HR's on-behalf action takes precedence.
        repository.withdrawAnyPending(employeeId, taxYear);

        PayrollTaxAllowanceInput allowances = toAllowances(request);
        long id = repository.insert(employeeId, taxYear, allowances,
            request.documentReference(), actor.employeeId(), true, request.lorYor01());

        // Auto-approve, same supersede-first ordering as #approve below.
        repository.supersedeApproved(employeeId, taxYear, id);
        int rows = repository.approve(id, actor.employeeId(), "สร้างและอนุมัติโดยฝ่ายบุคคลในนามพนักงาน");
        if (rows == 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to auto-approve on-behalf declaration");
        }
        // Approval IS go-live (owner ruling 2026-08-31) -- an on-behalf row that stopped at
        // APPROVED here would have been the one path that still needed a second ใช้กับเงินเดือน
        // click, which is exactly the two-step flow that ruling removed.
        promoteToPayrollAllowances(id, actor);
        TaxAllowanceDeclarationDto created = repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after insert"));
        auditService.record(actor, "CREATE_TAX_ALLOWANCE_DECLARATION_ON_BEHALF", "tax_allowance_declaration",
            id, null, created);
        // Audit finding (2026-08-31, notification sweep): this path never notified the employee at
        // all -- it produces an APPROVED declaration (decision #9 is "for staff who never log in",
        // but that describes portal access, not email; email is a separate channel and still worth
        // sending). Reuses TAX_ALLOWANCE_APPROVED rather than a new type: the fact that matters to
        // the employee (their declaration IS approved) is identical to a regular #approve, so the
        // frontend's existing type->icon mapping already renders this correctly with no touch. The
        // message text is the only thing that differs, to stay accurate about who acted.
        notifyOwner(employeeId, "TAX_ALLOWANCE_APPROVED",
            "แบบแจ้ง ล.ย.01 ได้รับการอนุมัติ",
            "ฝ่ายบุคคลสร้างและอนุมัติแบบแจ้งค่าลดหย่อนภาษีปี " + taxYear + " ให้คุณแล้ว");
        return created;
    }

    /**
     * PENDING -> APPROVED, <b>and live on payroll in the same transaction</b>. Supersedes any other
     * APPROVED declaration for the same employee/tax-year FIRST — {@code
     * uq_tad_one_approved_per_employee_year} is not deferrable, so approving without superseding
     * first would 500 on the constraint instead of cleanly retiring the old row (decision #7: "a new
     * submission supersedes the previous once approved").
     *
     * <p><b>Approval is go-live</b> (owner ruling 2026-08-31). {@link #promoteToPayrollAllowances}
     * used to be a separate HR action ({@code POST .../apply}, with its own งวดเดือน picker); it now
     * runs here, so an approved ล.ย.01 reduces withholding for its whole tax year with no second
     * click and no month to choose. {@link #apply} survives only to clear the pre-ruling backlog of
     * rows that are APPROVED with {@code applied_at IS NULL} — nothing this method produces can
     * land in that state.
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
        promoteHeaderToEmployeeMaster(existing, actor);
        promoteToPayrollAllowances(declarationId, actor);

        TaxAllowanceDeclarationDto updated = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after approve"));
        auditService.record(actor, "APPROVE_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, updated);
        notifyOwner(existing.employeeId(), "TAX_ALLOWANCE_APPROVED",
            "แบบแจ้ง ล.ย.01 ได้รับการอนุมัติ",
            "ฝ่ายบุคคลอนุมัติแบบแจ้งค่าลดหย่อนภาษีปี " + existing.taxYear()
                + " แล้ว มีผลกับการหักภาษี ณ ที่จ่ายตลอดทั้งปีภาษีนี้");
        return updated;
    }

    /**
     * Owner decision #4: the ล.ย.01 header prefills from HR records and is editable, and the
     * employee's corrections reach the master DB <b>only after HR confirms</b>. This is that write.
     *
     * <p><b>Why {@code approve} and not {@code apply}</b> (the plan doc suggested apply; this is a
     * deliberate departure, stated for review):
     * <ol>
     *   <li>"After HR confirms" IS {@code approve}. {@code apply} is a later, separate step about
     *       payroll <i>effective dating</i>.</li>
     *   <li>{@code apply} refuses with 409 when the target month is already {@code PROCESSED}. That
     *       is the right answer for allowance amounts and the wrong one for an address: a
     *       master-data correction has nothing to do with which payroll month is open.</li>
     *   <li>{@code apply} may never happen. A declaration can be approved and then superseded before
     *       anyone applies it, and the confirmed header would be lost.</li>
     *   <li>{@code approve} already calls {@link #requireSignedForm}, so this only ever promotes a
     *       header the employee physically signed. {@code apply} adds no such attestation.</li>
     * </ol>
     *
     * <p>Runs inside {@code approve}'s transaction: the approval and the master write land together
     * or not at all.
     *
     * <p><b>Scope is address + สถานภาพ + tax ID — never the legal name.</b> {@code declaredFirstName}
     * / {@code declaredLastName} are printed on the form and stored on the declaration, but a tax
     * form is not a name-change instrument; renaming an employee stays a deliberate HR action.
     *
     * <p>The write targets {@code existing.employeeId()} — the declaration's OWNER — never anything
     * the caller supplies, so an HR actor cannot redirect it at a third party or at themselves.
     */
    private void promoteHeaderToEmployeeMaster(TaxAllowanceDeclarationDto existing, UserPrincipal actor) {
        LorYor01Details header = existing.lorYor01();
        if (header == null) {
            return;
        }
        long employeeId = existing.employeeId();

        LorYor01AddressPayload address = header.address();
        if (address != null) {
            employeeRepository.upsertCurrentAddressFromDeclaration(
                employeeId,
                blankToNull(address.houseNo()), blankToNull(address.building()),
                blankToNull(address.roomNo()), blankToNull(address.floor()),
                blankToNull(address.village()), blankToNull(address.moo()),
                blankToNull(address.soi()), blankToNull(address.junction()),
                blankToNull(address.road()), blankToNull(address.subDistrict()),
                blankToNull(address.district()), blankToNull(address.province()),
                blankToNull(address.postalCode()));
        }
        employeeRepository.upsertTaxIdFromDeclaration(employeeId, blankToNull(header.taxpayerId()));
        employeeRepository.updateMaritalStatusFromDeclaration(
            employeeId, maritalStatusForMaster(header.maritalState()));

        auditService.record(actor, "WRITE_BACK_TAX_ALLOWANCE_HEADER", "employee", employeeId,
            null, header);
    }

    /**
     * ข้อ 1's enum -> the vocabulary {@code hr.employee.marital_status} actually holds.
     *
     * <p>That column is {@code VARCHAR(30)} with <b>no CHECK constraint and no enum anywhere in the
     * backend</b>; the only values attested in this repository are the Thai words its fixtures use
     * ({@code โสด} / {@code สมรส} in {@code demoData.js}, {@code mockApi.js},
     * {@code EmployeeDetailPage.test.jsx}). Writing the declaration's {@code SINGLE} /
     * {@code MARRIED} token straight through would introduce a second vocabulary into a column
     * nothing validates, and the employee screens would start showing English next to everyone
     * else's Thai.
     *
     * <p><b>Only the two unambiguous states are mapped.</b> {@code WIDOWED} and
     * {@code DIED_DURING_YEAR} return null — the master is left alone — for two reasons: the Thai
     * word for widowed appears nowhere in this repository, so any value chosen here would be
     * invented rather than matched; and {@code DIED_DURING_YEAR}
     * ("คู่สมรสถึงแก่ความตายระหว่างปีภาษี") is a statement about the tax year, not a standing HR
     * marital status. HR sets those by hand in the employee editor.
     *
     * <p>Re-check this mapping against what production's {@code marital_status} column really holds
     * before extending it; it could not be queried when this was written.
     */
    private String maritalStatusForMaster(String maritalState) {
        if (maritalState == null) {
            return null;
        }
        return switch (maritalState) {
            case "SINGLE" -> "โสด";
            case "MARRIED" -> "สมรส";
            default -> null;
        };
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
     * Clears the PRE-RULING BACKLOG: a declaration that was approved back when approval and go-live
     * were two separate HR actions, and whose second click never came ({@code status = 'APPROVED'
     * AND applied_at IS NULL} — the register's ยังไม่ใช้กับเงินเดือน queue). Since 2026-08-31
     * {@link #approve} promotes in its own transaction, so nothing new ever lands in that state and
     * this endpoint is a one-way drain, not part of the flow.
     *
     * <p>It takes no งวดเดือน. The month picker it used to carry is the thing the ruling removed.
     */
    @Transactional
    public TaxAllowanceDeclarationDto apply(long declarationId, UserPrincipal actor) {
        requireRole(actor, EDIT_ROLES);
        TaxAllowanceDeclarationDto existing = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบแบบแจ้งค่าลดหย่อนนี้"));
        if (existing.status() != TaxAllowanceDeclarationStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องได้รับการอนุมัติก่อนจึงจะนำไปใช้ได้");
        }
        if (existing.appliedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ถูกนำไปใช้แล้ว");
        }
        promoteToPayrollAllowances(declarationId, actor);

        TaxAllowanceDeclarationDto updated = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found after apply"));
        auditService.record(actor, "APPLY_TAX_ALLOWANCE_DECLARATION", "tax_allowance_declaration",
            declarationId, existing, updated);
        return updated;
    }

    /**
     * Promotes an APPROVED declaration into {@code hr.employee_tax_allowance} for the WHOLE tax
     * year. Called by {@link #approve} (the live path), {@link #createOnBehalf}, and {@link #apply}
     * (the backlog drain); it is the ONLY place in this class that writes the parent table, and it
     * does so exclusively through {@link PayrollRepository}. Order of operations matters:
     *
     * <ol>
     *   <li>Conditionally flag the DECLARATION as applied ({@code applied_at IS NULL} in the WHERE
     *       clause) BEFORE writing the allowance table — this is what makes a concurrent
     *       double-apply 409 on the second caller without needing an {@code @Version} column: the
     *       second racer's conditional UPDATE simply matches zero rows.</li>
     *   <li>Delete this employee/tax-year's mid-year rows — see the trap below.</li>
     *   <li>Only then promote, via the three {@link PayrollRepository} methods.</li>
     * </ol>
     *
     * <p><b>THE trap, and why step 2 exists.</b> {@code
     * PayrollRepository#findTaxAllowancesByEmployee} resolves {@code DISTINCT ON (employee_id) ...
     * WHERE effective_month <= :month ORDER BY effective_month DESC} — the LATEST dated row wins.
     * A whole-year row is dated month 1, which loses that ordering to any surviving mid-year row:
     * promote a 2026 declaration over an employee who already has a month-7 row and July through
     * December would keep computing on the SUPERSEDED figures, silently, with the register showing
     * the new declaration as applied. Deleting the mid-year rows for this employee/tax-year is what
     * makes "one approved ล.ย.01 = one row = the whole year" true in the table rather than just in
     * the UI. Scoped to ONE employee and ONE tax year, and only inside a deliberate HR promotion —
     * other years and other employees are never touched.
     *
     * <p><b>No PROCESSED-month guard.</b> This method used to refuse with 409 when the target
     * month's {@code hr.payroll_period} was already {@code PROCESSED}, which made sense while HR
     * picked the month: it stopped a deliberate back-date into a month already filed on ภ.ง.ด.1.
     * Under the ruling the target month is ALWAYS January, so that guard would refuse every
     * promotion from February onward — it would not protect anything, it would make the feature
     * unusable for eleven months of the year. Removing it does not retro-alter a filed month:
     * nothing here writes {@code hr.payroll_line}, and a PROCESSED period keeps its persisted
     * figures until someone re-processes that month, which is a separate deliberate HR action.
     */
    private void promoteToPayrollAllowances(long declarationId, UserPrincipal actor) {
        TaxAllowanceDeclarationDto declaration = repository.findById(declarationId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Declaration not found before apply"));

        // Open question (plan doc, "expires_on default"), resolved here (2026-08-01, the yearly-
        // expiry PR): year-end of the declaration's own tax year. Shared verbatim between the
        // DECLARATION's own expires_on (read by TaxAllowanceExpiryWorker's sweep, via
        // findExpirySweepCandidates) and the PARENT table's verification_deadline -- one LocalDate
        // computed once so the two can never independently drift.
        LocalDate expiresOn = LocalDate.of(declaration.taxYear(), 12, 31);
        int flagged = repository.markApplied(declarationId, actor.employeeId(), expiresOn);
        if (flagged == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้ถูกนำไปใช้แล้ว หรือยังไม่ได้รับการอนุมัติ");
        }

        payrollRepository.deleteMidYearTaxAllowances(declaration.employeeId(), declaration.taxYear());
        EmployeeTaxAllowanceUpsertRequest upsertRequest = toUpsertRequest(declaration, WHOLE_YEAR_EFFECTIVE_MONTH);
        payrollRepository.upsertTaxAllowances(declaration.taxYear(), List.of(upsertRequest), actor.employeeId());
        payrollRepository.markTaxAllowanceVerified(declaration.employeeId(), declaration.taxYear(), actor.employeeId());
        payrollRepository.setTaxAllowanceVerificationDeadline(declaration.employeeId(), declaration.taxYear(), expiresOn);
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
        // Audit finding (2026-08-31, notification sweep): the mirror image of #expireOverdueVerifications
        // notifying on EXPIRED never had its own reverse notification -- an employee whose allowance
        // lapsed and was told so would otherwise never be told it came back. Reuses
        // TAX_ALLOWANCE_APPROVED for the same reason createOnBehalf does above: the declaration IS
        // approved again, and the frontend needs no new type to render that correctly.
        notifyOwner(existing.employeeId(), "TAX_ALLOWANCE_APPROVED",
            "แบบแจ้ง ล.ย.01 ได้รับการอนุมัติ",
            "แบบแจ้งค่าลดหย่อนภาษีปี " + existing.taxYear()
                + " ที่เคยหมดอายุ ได้รับการยืนยันใหม่จากฝ่ายบุคคลแล้ว สิทธิลดหย่อนของคุณกลับมาใช้งานได้ตามปกติ");
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
        PayrollTaxAllowanceInput proposedAllowances = toAllowances(request);
        // Simulates January, unchanged from before the whole-year ruling: `effectiveMonth` was
        // optional on the estimate request and defaulted to 1, which is what the form's blank
        // "มีผลตั้งแต่งวดเดือน" select sent on every estimate the UI has ever made.
        return payrollService.estimateAllowanceEffect(
            actor.employeeId(), taxYear, WHOLE_YEAR_EFFECTIVE_MONTH, proposedAllowances, actor);
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
     * <p>Uses {@link NotificationService#notify}, the same call shape leave / overtime / welfare /
     * attendance-correction already use — {@code sendEmail=true} unconditionally, matching every
     * other call site of that method in this codebase. This replaced a bare {@code
     * NotificationRepository#insert} call (2026-08-31): that path never reached the mail layer at
     * all, so every ล.ย.01 event was in-app only regardless of the recipient's inbox. Deliberately
     * NOT {@code NotificationRepository}'s ticket-scoped {@code notifyEmployee}/{@code
     * notifyEmployeeForPricingRequest} either: those hardcode a ticket/pricing-request link and
     * resolve their title through the ticket-scoped {@code TICKET_EVENT_TITLES} map, neither of
     * which fits ล.ย.01. The link is the employee's own declaration page.
     *
     * <p>Joins the caller's transaction, like {@link AuditService#record}: {@code
     * NotificationService#notify} is itself {@code @Transactional}, and calling it from inside an
     * already-{@code @Transactional} method here makes it join that transaction rather than open a
     * second one (Spring's default {@code REQUIRED} propagation) — an approve that rolls back must
     * not leave a notification, or a queued email, claiming it happened.
     */
    private void notifyOwner(long ownerEmployeeId, String type, String title, String message) {
        notificationService.notify(ownerEmployeeId, type, title, message, "/tax-allowance", true);
    }

    /**
     * Notifies HR that a declaration is waiting in their queue — the submit-side gap this class had
     * before 2026-08-31 (an employee filed and HR was told nothing; the {@code notifyOwner} gap above
     * was the decision side of the same class of defect). Fires from exactly one place: {@link
     * #submitOwn}. {@link #createOnBehalf} also calls {@code repository.insert}, but never leaves a
     * PENDING row for anyone to see — it supersedes/approves within the SAME transaction, so nobody
     * is waiting and this must not fire there too.
     *
     * <p>Goes through {@link NotificationRepository#notifyHrAt}, the same {@code "hr"} division
     * fan-out {@code ProfileRequestService} uses (added #860) — not {@link #notificationService},
     * which only ever addresses one employee and has no role fan-out.
     *
     * <p>The "who filed" text is composed the same way {@code ProfileRequestService.titleAndName}
     * does: a Thai title glued directly onto {@code nameTh()} with no space, safe because {@code
     * EmployeeRepository#fullName} already puts exactly one space between first and last name. Falls
     * back to the declaration's own (title-less) {@code employeeName}/{@code employeeCode} — already
     * resolved by {@code repository.findById}'s own join — on the practically-unreachable case where
     * the fresh {@link EmployeeRepository} lookup comes back empty (the FK on {@code
     * hr.tax_allowance_declaration.employee_id} makes a vanished row a can't-happen, same reasoning
     * {@code ProfileRequestService#submittedMessage} documents for its own fallback).
     */
    private void notifyHrOfSubmission(TaxAllowanceDeclarationDto declaration) {
        String who = employeeRepository.findEmployeeSummaryById(declaration.employeeId())
            .map(employee -> titleAndName(employee) + " (" + employee.code() + ")")
            .orElseGet(() -> declaration.employeeName() + " (" + declaration.employeeCode() + ")");
        notifications.notifyHrAt("TAX_ALLOWANCE_SUBMITTED",
            who + " ยื่นแบบ ล.ย.01 ปีภาษี " + declaration.taxYear(),
            "/tax-allowance-review");
    }

    /** Copy of {@code ProfileRequestService.titleAndName} — see that method's Javadoc. */
    private static String titleAndName(EmployeeDto employee) {
        String title = employee.titleTh();
        return (title == null ? "" : title) + employee.nameTh();
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
            request.parentCareCount() == null ? 0 : request.parentCareCount(),
            // ข้อ 9 lives on the ล.ย.01 sub-payload, not at the top level of the request, because it
            // is a form item rather than one of the pre-V137 allowance fields. Pulled across here so
            // the /estimate preview withholds on the same figure the applied declaration will.
            lorYor01Zero(request.lorYor01())
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
            request.parentCareCount() == null ? 0 : request.parentCareCount(),
            lorYor01Zero(request.lorYor01())
        );
    }

    private java.math.BigDecimal zero(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    /** ข้อ 9 off the ล.ย.01 sub-payload; the whole sub-payload is optional, hence the null guard. */
    private java.math.BigDecimal lorYor01Zero(LorYor01Details lorYor01) {
        return lorYor01 == null ? java.math.BigDecimal.ZERO : zero(lorYor01.providentFundAllowance());
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
            appliedEffectiveMonth, dto.documentReference(),
            // ข้อ 9 (V137): promoted from the declaration into employee_tax_allowance, which is what
            // PayrollRepository#findTaxAllowancesByEmployee reads on every run.
            allowances.providentFundAllowance()
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
