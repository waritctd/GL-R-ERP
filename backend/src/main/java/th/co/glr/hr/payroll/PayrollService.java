package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.export.KBankPctExporter;
import th.co.glr.hr.payroll.export.PayrollExportFile;
import th.co.glr.hr.payroll.export.PayrollExportKind;
import th.co.glr.hr.payroll.export.PayrollExportRow;
import th.co.glr.hr.payroll.export.Pnd1Exporter;
import th.co.glr.hr.payroll.export.SsoExporter;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculation;
import th.co.glr.hr.payroll.PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.TaxAllowanceBulkUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.TaxAllowanceListResponse;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedBulkUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedListResponse;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest;

@Service
public class PayrollService {
    private static final Set<String> PAYROLL_VIEW_ROLES = Set.of("hr", "ceo");
    private static final Set<String> PAYROLL_EDIT_ROLES = Set.of("hr");
    private static final Logger AUDIT = LoggerFactory.getLogger("th.co.glr.hr.audit");

    private final PayrollRepository payrollRepository;
    private final PayrollCalculator payrollCalculator;
    private final CommissionService commissionService;
    private final AuditService auditService;
    private final PayslipRenderer payslipRenderer;
    // Leave -> payroll dependency on the leave package. #suggestedInputs (2026-07-23) uses it
    // read-only to overlay leave-derived unpaidLeaveDays/pendingUnpaidLeaveCorrectionDays.
    // #preview/#process (2026-07-23, AUTO-REFUND) additionally read + (on process only) resolve
    // hr.leave_payroll_correction rows to auto-apply the cancel-after-close refund -- see
    // #calculateLine and #process below.
    private final LeaveRepository leaveRepository;
    private final KBankPctExporter kbankExporter;
    private final Pnd1Exporter pnd1Exporter;
    private final SsoExporter ssoExporter;
    private final AppProperties appProperties;

    public PayrollService(
        PayrollRepository payrollRepository,
        PayrollCalculator payrollCalculator,
        CommissionService commissionService,
        AuditService auditService,
        PayslipRenderer payslipRenderer,
        LeaveRepository leaveRepository,
        KBankPctExporter kbankExporter,
        Pnd1Exporter pnd1Exporter,
        SsoExporter ssoExporter,
        AppProperties appProperties
    ) {
        this.payrollRepository = payrollRepository;
        this.payrollCalculator = payrollCalculator;
        this.commissionService = commissionService;
        this.auditService = auditService;
        this.payslipRenderer = payslipRenderer;
        this.leaveRepository = leaveRepository;
        this.kbankExporter = kbankExporter;
        this.pnd1Exporter = pnd1Exporter;
        this.ssoExporter = ssoExporter;
        this.appProperties = appProperties;
    }

    public PayrollPeriodDto currentOrPreview(LocalDate payrollMonth, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        LocalDate month = normalizeMonth(payrollMonth);
        return payrollRepository.findPeriodByMonth(month)
            .map(period -> {
                auditPayrollAccess("VIEW_PAYROLL_PERIOD", actor, period,
                    "base_salary,gross_earnings,deductions,net_pay,bank_account");
                return period;
            })
            .orElseGet(() -> preview(month, List.of(), actor));
    }

    /**
     * Special-pay carry-forward (2026-07-23): read-only suggestions to pre-fill a brand-new monthly
     * payroll run from each employee's most-recent PRIOR processed {@code payroll_line}. Does NOT feed
     * {@link #preview(ProcessPayrollRequest, UserPrincipal)} or {@link #process}, which keep taking
     * explicit inputs exactly as before — the frontend reads this endpoint separately and pre-fills
     * form fields HR can still edit/override. There is no "omitted means carry" ambiguity to resolve
     * here: the carry-forward step happens entirely client-side, before HR submits, and whatever value
     * is in the field when HR hits Preview/Process — carried, edited, or explicitly cleared to 0 — is
     * what goes into {@code inputs} and gets calculated/stored, unchanged from today's behaviour.
     *
     * <p>Leave -&gt; payroll unpaid-day deduction (2026-07-23): also overlays, per employee, this
     * month's leave-derived {@code unpaidLeaveDays} ({@link
     * LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}) and any unresolved
     * cancel-after-close {@code pendingUnpaidLeaveCorrectionDays} ({@link
     * LeaveRepository#findPendingPayrollCorrectionsByEmployee}). Purely additive to the special-pay
     * carry-forward rows above: an employee with leave-derived figures but no prior processed
     * payroll_line still gets a row ({@link PayrollCarryForwardDtos.SuggestedInputRow#empty}). Like
     * the rest of this method, this NEVER feeds {@code preview()}/{@code process()} directly -- the
     * frontend pre-fills the unpaidLeaveDays form field from it, and HR can still override.
     */
    public PayrollCarryForwardDtos.SuggestedInputsResponse suggestedInputs(LocalDate payrollMonth, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        LocalDate month = normalizeMonth(payrollMonth);

        Map<Long, PayrollCarryForwardDtos.SuggestedInputRow> byEmployee = new LinkedHashMap<>();
        payrollRepository.findCarryForwardSuggestions(month)
            .forEach(row -> byEmployee.put(row.employeeId(), row));

        Map<Long, BigDecimal> unpaidLeaveDaysByEmployee = leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(month);
        Map<Long, BigDecimal> pendingCorrectionsByEmployee = leaveRepository.findPendingPayrollCorrectionsByEmployee();

        Set<Long> employeeIds = new LinkedHashSet<>(byEmployee.keySet());
        employeeIds.addAll(unpaidLeaveDaysByEmployee.keySet());
        employeeIds.addAll(pendingCorrectionsByEmployee.keySet());

        List<PayrollCarryForwardDtos.SuggestedInputRow> merged = employeeIds.stream()
            .map(employeeId -> {
                PayrollCarryForwardDtos.SuggestedInputRow base = byEmployee.getOrDefault(
                    employeeId, PayrollCarryForwardDtos.SuggestedInputRow.empty(employeeId));
                return new PayrollCarryForwardDtos.SuggestedInputRow(
                    employeeId,
                    base.specialPay1(), base.specialPay2(), base.specialPay3(), base.specialPay4(), base.specialPay5(),
                    base.specialPay6(), base.specialPay7(), base.specialPay8(), base.specialPay9(),
                    base.mealAllowance(),
                    base.nonTaxableIncome(), base.studentLoanDeduction(), base.legalExecutionDeduction(),
                    unpaidLeaveDaysByEmployee.getOrDefault(employeeId, BigDecimal.ZERO),
                    pendingCorrectionsByEmployee.getOrDefault(employeeId, BigDecimal.ZERO),
                    // Carry the per-run withholding override typed last run (nullable; the leave overlay
                    // above never touches it).
                    base.withholdingTaxOverride()
                );
            })
            .toList();

        return new PayrollCarryForwardDtos.SuggestedInputsResponse(month, merged);
    }

    public PayrollPeriodDto preview(ProcessPayrollRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return preview(normalizeMonth(request.payrollMonth()), safeInputs(request.inputs()), actor);
    }

    @Transactional
    public PayrollPeriodDto process(ProcessPayrollRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        LocalDate month = normalizeMonth(request.payrollMonth());
        PayrollPeriodDto preview = preview(month, safeInputs(request.inputs()), actor);
        long periodId = payrollRepository.saveProcessedPeriod(month, actor.employeeId(), preview.lines());
        // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): the lines just saved already have
        // the refund baked in (computed by #preview above, in this same transaction). Now mark the
        // hr.leave_payroll_correction rows that refund came from as resolved by THIS period -- see
        // LeaveRepository#resolvePendingCorrections for exactly how that stays consistent with the
        // read (same WHERE-shape, same transaction) and idempotent across re-processing this month.
        leaveRepository.resolvePendingCorrections(periodId);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Payroll period was not saved"));
        auditPayrollAccess("PROCESS_PAYROLL", actor, period,
            "base_salary,gross_earnings,deductions,net_pay");
        auditService.record(actor, "PROCESS_PAYROLL", "payroll_period", periodId, null, period);
        return period;
    }

    /**
     * Generate one of the three statutory payroll text files (KBank PCT, PND1, SSO สปส.1-10) for a
     * processed period. HR/CEO only; reads PDPA-restricted PII for PND1/SSO, so every call is audited
     * with the specific fields exposed. {@code effectiveDate} is the HR-picked transfer/pay date;
     * null falls back to the configured default day (the 26th) of the payroll month.
     */
    public PayrollExportFile export(PayrollExportKind kind, long periodId, LocalDate effectiveDate, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll period not found"));
        LocalDate payrollMonth = period.payrollMonth();
        LocalDate payDate = resolveEffectiveDate(effectiveDate, payrollMonth);
        List<PayrollExportRow> rows = payrollRepository.findExportRows(periodId);
        AppProperties.Employer employer = appProperties.getPayroll().getEmployer();

        byte[] content;
        String auditFields;
        switch (kind) {
            case KBANK -> {
                content = kbankExporter.export(rows, employer, payDate);
                auditFields = "bank_account,net_pay";
            }
            case PND1 -> {
                content = pnd1Exporter.export(rows, employer, payrollMonth, payDate);
                auditFields = "national_id,tax_id,gross_taxable_income,withholding_tax";
            }
            case SSO -> {
                content = ssoExporter.export(rows, employer, payrollMonth, payDate);
                auditFields = "social_security_no,sso_wage_base,social_security";
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported export kind");
        }
        auditPayrollAccess("EXPORT_PAYROLL_" + kind.name(), actor, period, auditFields);
        return new PayrollExportFile(kind, kind.fileName(payDate), content);
    }

    /** HR-picked date, else the configured default transfer day (26th) clamped to the month length. */
    private LocalDate resolveEffectiveDate(LocalDate effectiveDate, LocalDate payrollMonth) {
        if (effectiveDate != null) {
            return effectiveDate;
        }
        int day = Math.min(
            appProperties.getPayroll().getEmployer().getDefaultTransferDay(),
            payrollMonth.lengthOfMonth());
        return payrollMonth.withDayOfMonth(Math.max(day, 1));
    }

    public byte[] payslipPdf(long periodId, long lineId, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll period not found"));
        PayrollLineDto line = period.lines().stream()
            .filter(item -> item.id() != null && item.id() == lineId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll line not found"));
        byte[] pdf = payslipRenderer.toPdf(line, period);
        auditPayrollLineAccess("VIEW_PAYSLIP_PDF", actor, period, line,
            "earnings,sso,tax,deductions,net_pay,bank_account");
        auditService.record(actor, "VIEW_PAYSLIP_PDF", "payroll_line", line.id(), null, auditPayload(period, line));
        return pdf;
    }

    public byte[] ownPayslipPdf(long periodId, UserPrincipal actor) {
        if (actor == null || actor.employeeId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll period not found"));
        PayrollLineDto line = period.lines().stream()
            .filter(item -> item.employeeId() == actor.employeeId())
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payslip not found for this payroll period"));
        byte[] pdf = payslipRenderer.toPdf(line, period);
        auditPayrollLineAccess("VIEW_OWN_PAYSLIP_PDF", actor, period, line,
            "earnings,sso,tax,deductions,net_pay,bank_account");
        auditService.record(actor, "VIEW_OWN_PAYSLIP_PDF", "payroll_line", line.id(), null, auditPayload(period, line));
        return pdf;
    }

    private PayrollPeriodDto preview(LocalDate payrollMonth, List<PayrollEmployeeInputRequest> inputs, UserPrincipal actor) {
        Map<Long, PayrollEmployeeInputRequest> inputByEmployee = inputs.stream()
            .collect(Collectors.toMap(PayrollEmployeeInputRequest::employeeId, Function.identity(), (left, right) -> right));
        List<PayrollEmployeeSnapshot> employees = payrollRepository.findActiveEmployees();
        Map<Long, BigDecimal> overtimeByEmployee = payrollRepository.findApprovedOvertimePayByEmployee(payrollMonth);
        Map<Long, BigDecimal> commissionByEmployee = commissionPayByEmployee(payrollMonth);
        Map<Long, PayrollYearToDate> yearToDateByEmployee = payrollRepository.findYearToDateByEmployee(payrollMonth);
        // C1: the standing tax-allowance declaration for this payroll's tax year is the BASE. Any
        // field the request body supplies for an employee (non-null) is an in-run correction and wins
        // over the stored value -- stored = standing declaration, body = this-run override.
        Map<Long, PayrollTaxAllowanceInput> storedAllowancesByEmployee =
            payrollRepository.findTaxAllowancesByEmployee(payrollMonth);
        // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): existingPeriodId is null the first
        // time this month is previewed/processed, and non-null on a re-preview/re-process of a month
        // that already has a period row (PREVIEW-only rows from currentOrPreview's persistence path
        // do not exist -- only saveProcessedPeriod creates one -- so in practice this is non-null
        // exactly when this month has been PROCESSED before). Passing it into
        // findRefundableUnpaidDaysByEmployee gives back any correction that same period previously
        // resolved, so a re-run recomputes the correct total instead of losing or double-counting it
        // -- see that method's doc for the full idempotency argument, and PayrollService#process for
        // the paired resolve call.
        Long existingPeriodId = payrollRepository.findPeriodByMonth(payrollMonth).map(PayrollPeriodDto::id).orElse(null);
        Map<Long, BigDecimal> leaveRefundDaysByEmployee = leaveRepository.findRefundableUnpaidDaysByEmployee(existingPeriodId);

        // Task 2 (2026-07-29): per-employee, per-component tax treatment + SSO inclusion, replacing
        // the hardcoded single-limb split. See PayrollCalculator#calculateClassified and
        // docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md.
        int taxYear = payrollMonth.getYear();
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> treatmentsByEmployee =
            payrollRepository.findComponentTaxTreatmentsByEmployee(taxYear);
        Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee =
            payrollRepository.findComponentSsoInclusionByEmployee(taxYear);

        List<PayrollLineDto> lines = employees.stream()
            .map(employee -> calculateLine(
                employee,
                inputByEmployee.get(employee.employeeId()),
                overtimeByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                commissionByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                yearToDateByEmployee.getOrDefault(employee.employeeId(), PayrollYearToDate.empty()),
                storedAllowancesByEmployee.get(employee.employeeId()),
                leaveRefundDaysByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                payrollMonth,
                // Genuine conflict (rebase, 2026-07-29), not a mechanical merge: branch 117's
                // remainingPayPeriods/taxpayerAge fed the OLD single-limb payrollCalculator.calculate()
                // path; branch 118 (task 2) repoints calculateLine onto calculateClassified exclusively
                // (see PayrollClassifiedCalculationDtos' class javadoc), which has no use for either --
                // it derives monthsRemaining from payrollMonthValue directly and does not apply the V93
                // elderly/disabled exemption (exemptIncome/assessAnnualTax are calculate()-only). Kept
                // as-is rather than threaded through calculateClassified/PayrollClassifiedCalculationInput,
                // which would be inventing behaviour fa69e4fa does not implement; flagged as a known gap
                // in docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md,
                // "Progress -- task 4: rebase report + F1-F7 fixes" section, known risks (F4 correction,
                // Opus review 2026-07-30: that section did not exist when this comment was written;
                // it now does).
                treatmentsFor(employee.employeeId(), treatmentsByEmployee,
                    hasBeenOnboardedForPayroll(employee.employeeId(), ssoInclusionByEmployee)),
                ssoInclusionFor(employee.employeeId(), ssoInclusionByEmployee)
            ))
            .sorted(Comparator.comparing(PayrollLineDto::employeeCode))
            .toList();
        PayrollPeriodDto period = new PayrollPeriodDto(
            null,
            payrollMonth,
            payrollMonth,
            payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()),
            payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()),
            "PREVIEW",
            OffsetDateTime.now(),
            actor.employeeId(),
            lines.size(),
            sum(lines, PayrollLineDto::grossEarnings),
            sum(lines, PayrollLineDto::totalDeductions),
            sum(lines, PayrollLineDto::netPay),
            sum(lines, PayrollLineDto::socialSecurity),
            sum(lines, PayrollLineDto::withholdingTax),
            lines
        );
        auditPayrollAccess("PREVIEW_PAYROLL", actor, period,
            "base_salary,gross_earnings,deductions,net_pay");
        return period;
    }

    /**
     * P0 fix (Opus review, 2026-07-30): the signal {@link #treatmentsFor} needs to tell "an employee a
     * real onboarding path touched" apart from "an employee that merely has an incidental, unrelated
     * SSO row" -- see that method's own javadoc for the regression that made a cruder {@code
     * containsKey} check insufficient. A real onboarding path ({@code
     * PayrollRepository#seedSsoInclusionDefaults}, called from V96's backfill, {@code
     * EmployeeService#create}, and this task's own P1 fix) always writes a row for EVERY {@link
     * PayrollComponent} in one pass, so it always includes several components other than {@code
     * SALARY}. Checking for at least one non-SALARY component is therefore robust to exactly which
     * components got seeded (V96 predates {@code MEAL_ALLOWANCE}/{@code PER_DIEM_TAXABLE}, so an
     * employee it touched has 16 rows, not the current 18 -- an exact count would wrongly treat that
     * employee as "not onboarded") while still rejecting a single incidental SALARY-only row, which
     * several tests in this suite seed purely so their OWN unrelated {@code socialSecurity} assertion
     * comes out non-zero (see e.g. {@code PayrollClassifiedEngineIntegrationTest#seedEmployee}).
     *
     * <p><b>Finding 1 fix (fourth Opus review, 2026-07-30): the polarity of the "no SSO rows at all"
     * case was backwards.</b> This method used to {@code return false} the instant {@code
     * ssoInclusion == null} -- i.e. an employee with ZERO rows in BOTH matrices was treated as "not
     * onboarded" and got no synthesized default, 409ing the run. That is exactly backwards: V96 (the
     * SSO backfill) and V100 (the tax-treatment backfill) both {@code CROSS JOIN hr.employee} with no
     * {@code WHERE}, and {@code EmployeeService#create} seeds both matrices in the SAME transaction --
     * no real deployment path ever produces "SSO rows present, treatment rows absent" without ALSO
     * producing the reverse (both present) or "both absent" first. "Both absent" is the population
     * this safety net exists for in the first place: {@code db/migration-uat/V900} inserts 93
     * employees by raw {@code INSERT INTO hr.employee}, and on a freshly rebuilt uat Flyway runs V96/
     * V97/V100 (all merged-location, ordered by version number) BEFORE V900, reaching none of those
     * 93 rows -- {@code V902} then seeds approved overtime for them, so the payroll page's own initial
     * GET carries a non-zero component for every one of them with no HR input at all. The previous
     * logic refused exactly this population the fix's own stated goal was written to cover. Zero rows
     * in BOTH matrices is therefore unambiguously "never onboarded by any path" and now synthesizes,
     * same as the pre-existing non-SALARY-row case below (which stays exactly as it was, still
     * covering {@link PayrollClassificationReachabilityIntegrationTest}'s employee -- seeded through
     * {@code seedSsoInclusionDefaults} alone, so it has SSO rows but zero treatment rows). The one
     * state that must still NOT synthesize -- an SSO map that exists but carries only the incidental
     * SALARY-only convenience row several tests seed (see {@code
     * PayrollClassifiedEngineIntegrationTest#seedEmployee}) -- is unaffected: {@code ssoInclusion} is
     * non-null there, so this branch is not taken, and the {@code anyMatch} check below still returns
     * {@code false} for it exactly as before.
     */
    private static boolean hasBeenOnboardedForPayroll(
        long employeeId, Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee
    ) {
        Map<PayrollComponent, Boolean> ssoInclusion = ssoInclusionByEmployee.get(employeeId);
        if (ssoInclusion == null) {
            return true;
        }
        return ssoInclusion.keySet().stream().anyMatch(component -> component != PayrollComponent.SALARY);
    }

    /**
     * P0 fix (Opus review, 2026-07-30), layer 3 -- the read-time safety net {@link
     * PayrollClassificationReachabilityIntegrationTest
     * #payrollRunsForAnEmployeeConfiguredOnlyThroughThePathsAProductionDeploymentActuallyHas} demands.
     * Layers 1 (V100's migration backfill) and 2 ({@code EmployeeService#create}'s new call to {@link
     * PayrollRepository#seedComponentTaxTreatmentDefaults}) only reach an employee that either
     * existed when V100 ran or was created through the API. That reachability test's employee is
     * neither: it is inserted by raw SQL mid-test, mimicking an employee whose classification rows
     * were never written by ANY path (uat's {@code V900}, {@code db/migration-demo}'s seeds, any bulk
     * import -- the identical gap {@link #ssoInclusionFor} already closes for SSO inclusion). Without
     * this layer such an employee has ZERO stored {@code hr.payroll_component_tax_treatment} rows and
     * {@link PayrollCalculator#calculateClassified}'s classification gate 409s the entire run the
     * moment they carry a single non-zero special pay -- production-realistic (handoff section 9d:
     * employee 10012's real ฿407 พิเศษ 1), not a contrived edge case.
     *
     * <p><b>Why this does not weaken "HR sets every line, no silent default" (handoff section 1):</b>
     * that rule governs the EXPLICIT "not yet classified" state -- a row that EXISTS with {@code
     * tax_treatment = NULL}, which the handoff distinguishes deliberately from a row that is simply
     * ABSENT (see {@link PayrollRepository#findComponentTaxTreatmentsByEmployee}'s own javadoc on the
     * distinction). This method only ever synthesizes a default when the employee has NO ROW AT ALL --
     * the map lookup itself returns {@code null}, meaning classification has never been touched for
     * this employee through any path. The instant an employee has even ONE stored row (including an
     * explicit present-and-null "deliberately left unclassified" row from the matrix screen, or every
     * test in this suite that seeds a deliberately PARTIAL classification via {@code
     * AbstractPostgresIntegrationTest#seedRegularTaxTreatment}), this method returns that map
     * completely unchanged -- explicit HR intent, once expressed for an employee at all, is never
     * second-guessed. This mirrors {@link #ssoInclusionFor}'s identical reasoning for the sibling
     * matrix exactly.
     *
     * <p><b>{@code hasBeenOnboarded} -- found necessary by a regression this method's first version
     * caused, not designed up front:</b> an unconditional "zero rows -&gt; synthesize" rule collided
     * head-on with the ALREADY-GREEN {@code
     * PayrollClassifiedEngineIntegrationTest#unclassifiedNonZeroComponentRejectsTheRunNamingEmployeeAndComponent}
     * -- the test that pins down "HR sets every line, no silent default" in the first place. That
     * test's employee ALSO has zero tax-treatment rows (it seeds literally nothing before previewing),
     * and legitimately expects the 409. Worse, a first attempt at a distinguishing signal
     * (merely {@code ssoInclusionByEmployee.containsKey(employeeId)}) ALSO failed against that same
     * test: this file's own {@code seedEmployee} helper -- unrelated test convenience, nothing to do
     * with onboarding -- seeds exactly one incidental SSO row (SALARY only, via {@code
     * AbstractPostgresIntegrationTest#seedSsoIncluded}) for every employee it creates, so "has ANY SSO
     * row" was true for that test too. See {@link #hasBeenOnboardedForPayroll} for the signal that
     * actually distinguishes "a real onboarding path touched this employee's SSO matrix" (every
     * component, including several non-SALARY ones) from "a test fixture happens to carry one
     * unrelated SALARY-only row" -- the reachability test's employee has the former (it calls {@code
     * seedSsoInclusionDefaults} directly), the blocking test's employee has neither.
     *
     * <p>Defaults come from {@link PayrollRepository#defaultTaxTreatment}, the SAME safe-default logic
     * {@code V100} and {@code seedComponentTaxTreatmentDefaults} both use, so all three layers can
     * never independently drift onto different defaults for the same component.
     */
    private static Map<PayrollComponent, PayrollTaxTreatment> treatmentsFor(
        long employeeId,
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> treatmentsByEmployee,
        boolean hasBeenOnboarded
    ) {
        Map<PayrollComponent, PayrollTaxTreatment> stored = treatmentsByEmployee.get(employeeId);
        if (stored != null || !hasBeenOnboarded) {
            return stored == null ? Map.of() : stored;
        }
        Map<PayrollComponent, PayrollTaxTreatment> defaults = new EnumMap<>(PayrollComponent.class);
        for (PayrollComponent component : PayrollComponent.values()) {
            if (component == PayrollComponent.SALARY || component == PayrollComponent.NON_TAXABLE_INCOME) {
                continue;
            }
            defaults.put(component, PayrollRepository.defaultTaxTreatment(component));
        }
        return defaults;
    }

    /**
     * P1 fix (Opus review, 2026-07-30): {@code EmployeeService#create} seeds SSO-inclusion defaults
     * only on the API create path. An employee inserted by raw SQL afterwards (uat's {@code V900},
     * {@code db/migration-demo/V21}, any bulk import) never runs that call and ends up with ZERO
     * stored {@code hr.payroll_component_sso_inclusion} rows -- which used to fall through to {@code
     * Map.of()} at the call site below (every component absent, and {@link
     * PayrollClassifiedCalculationDtos.PayrollClassifiedCalculationInput#ssoIncluded} treats absent as
     * excluded), so the SSO wage base silently computed to ฿0.00 with no error at all.
     *
     * <p>Fixed at this READ/RESOLUTION layer rather than chasing every writer (the seed call itself,
     * every demo/uat seed migration, any future bulk import): an employee with genuinely NO stored
     * rows gets the same company-wide default {@link PayrollRepository#seedSsoInclusionDefaults} would
     * have written ({@link PayrollRepository#defaultSsoIncluded}, factored out so both paths can never
     * independently drift). An employee with AT LEAST ONE stored row -- including every test in this
     * suite that seeds a deliberately PARTIAL inclusion matrix via {@code
     * AbstractPostgresIntegrationTest#seedSsoIncluded} to pin one specific component's inclusion or
     * exclusion -- is returned completely unchanged: this only fills the gap for an employee nothing
     * has ever touched, it does not change what "absent within an existing map" means.
     *
     * <p>Unlike {@link #treatmentsFor}, this one has no {@code hasBeenOnboarded} gate: SSO inclusion
     * has no "explicitly declined to classify" state to protect (a boolean has only two values, either
     * of which is a real answer), so there is no equivalent regression to guard against here.
     */
    private static Map<PayrollComponent, Boolean> ssoInclusionFor(
        long employeeId, Map<Long, Map<PayrollComponent, Boolean>> ssoInclusionByEmployee
    ) {
        Map<PayrollComponent, Boolean> stored = ssoInclusionByEmployee.get(employeeId);
        if (stored != null) {
            return stored;
        }
        Map<PayrollComponent, Boolean> defaults = new EnumMap<>(PayrollComponent.class);
        for (PayrollComponent component : PayrollComponent.values()) {
            defaults.put(component, PayrollRepository.defaultSsoIncluded(component));
        }
        return defaults;
    }

    private PayrollLineDto calculateLine(
        PayrollEmployeeSnapshot employee,
        PayrollEmployeeInputRequest input,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        PayrollYearToDate yearToDate,
        PayrollTaxAllowanceInput storedAllowances,
        BigDecimal leaveRefundDays,
        LocalDate payrollMonth,
        Map<PayrollComponent, PayrollTaxTreatment> componentTaxTreatments,
        Map<PayrollComponent, Boolean> componentSsoInclusion
    ) {
        // Withholding-tax override precedence (2026-07-24, V88): a per-run HR-typed value wins; else
        // the employee's standing override; else null (= compute normally). NULL is meaningful at every
        // level -- a 0 override (withhold nothing) is honoured and must not collapse to "no override",
        // so this is a plain null check, never a truthiness/sign test. The per-run TYPED value is what
        // gets persisted on the line (below) so it can carry forward; the standing value is NOT stored
        // on the line -- it re-applies from the employee record every run.
        BigDecimal perRunWithholdingOverride = input == null ? null : input.withholdingTaxOverride();
        BigDecimal effectiveWithholdingOverride = perRunWithholdingOverride != null
            ? perRunWithholdingOverride
            : employee.withholdingTaxOverride();

        // F2 fix (Opus review, 2026-07-30): the frontend used to expose per-diem AMOUNT inputs with no
        // basis selector, and this method passed input.perDiemBasis() straight through to the INSERT
        // unvalidated -- so Preview always succeeded (it never writes a row) but Process 500'd on
        // V97's chk_payroll_line_per_diem_basis_present CHECK constraint the moment HR typed a
        // per-diem amount without a basis. Validate here, before that INSERT is ever reached, and fail
        // with a clear Thai-language 400 naming the employee and the missing basis instead of a bare
        // DataIntegrityViolationException turning into a 500.
        BigDecimal perDiemExemptAmount = input == null ? BigDecimal.ZERO : safe(input.perDiemExempt());
        BigDecimal perDiemTaxableAmount = input == null ? BigDecimal.ZERO : safe(input.perDiemTaxable());
        if (perDiemExemptAmount.add(perDiemTaxableAmount).signum() > 0
            && (input == null || input.perDiemBasis() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "พนักงาน " + employee.employeeCode() + " " + employee.employeeName()
                    + " มีการจ่ายเบี้ยเลี้ยง (เบี้ยเลี้ยง ตจว/ตปท) แต่ไม่ได้ระบุฐานตามมาตรา 42"
                    + " (เหมาจ่ายตามอัตราราชการ มาตรา 42(2) หรือจ่ายจริงตามหน้าที่ มาตรา 42(1))"
                    + " กรุณาเลือกฐานก่อนประมวลผลเงินเดือน");
        }

        // ลูกค้าคืนสินค้า earned/unearned flag (handoff section 6): not yet earned reduces the
        // commission earning itself, PRE-TAX -- netted into COMMISSION_PAY here, before it ever
        // reaches PayrollCalculator, so the calculator only ever sees a single already-net commission
        // figure and applies the deduction a second time only for the already-earned clawback case.
        boolean customerReturnAlreadyEarned = input != null && Boolean.TRUE.equals(input.customerReturnAlreadyEarned());
        BigDecimal customerReturnDeduction = input == null ? BigDecimal.ZERO : safe(input.customerReturnDeduction());
        BigDecimal effectiveCommissionPay = commissionPay == null ? BigDecimal.ZERO : commissionPay;
        if (!customerReturnAlreadyEarned && customerReturnDeduction.signum() > 0) {
            effectiveCommissionPay = effectiveCommissionPay.subtract(customerReturnDeduction).max(BigDecimal.ZERO);
        }

        List<BigDecimal> specialPays = input == null ? List.of() : input.specialPays();
        Map<PayrollComponent, BigDecimal> componentAmounts = new EnumMap<>(PayrollComponent.class);
        componentAmounts.put(PayrollComponent.SALARY, employee.baseSalary());
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_1, amountAt(specialPays, 0));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_2, amountAt(specialPays, 1));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_3, amountAt(specialPays, 2));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_4, amountAt(specialPays, 3));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_5, amountAt(specialPays, 4));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_6, amountAt(specialPays, 5));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_7, amountAt(specialPays, 6));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_8, amountAt(specialPays, 7));
        componentAmounts.put(PayrollComponent.SPECIAL_PAY_9, amountAt(specialPays, 8));
        componentAmounts.put(PayrollComponent.OVERTIME_PAY, overtimePay == null ? BigDecimal.ZERO : overtimePay);
        componentAmounts.put(PayrollComponent.COMMISSION_PAY, effectiveCommissionPay);
        componentAmounts.put(PayrollComponent.MEAL_ALLOWANCE, input == null ? BigDecimal.ZERO : safe(input.mealAllowance()));
        componentAmounts.put(PayrollComponent.PER_DIEM_TAXABLE, input == null ? BigDecimal.ZERO : safe(input.perDiemTaxable()));
        componentAmounts.put(PayrollComponent.BONUS_PAY, input == null ? BigDecimal.ZERO : safe(input.bonusPay()));
        componentAmounts.put(PayrollComponent.OTHER_ONE_OFF_PAY, input == null ? BigDecimal.ZERO : safe(input.otherOneOffPay()));
        componentAmounts.put(PayrollComponent.DIRECTOR_REMUNERATION, employee.directorRemuneration());
        // The มาตรา 42 exempt slice of เบี้ยเลี้ยง joins NON_TAXABLE_INCOME rather than getting its own
        // component: it is outside the tax base AND the ประกันสังคม wage base, which is exactly what
        // NON_TAXABLE_INCOME already means. Giving it a component would oblige HR to classify a ป.96
        // treatment for money that is never taxed. It stays visible as its own figure on the line and
        // the payslip -- only the tax/SSO arithmetic pools it here.
        componentAmounts.put(PayrollComponent.NON_TAXABLE_INCOME,
            input == null ? BigDecimal.ZERO : safe(input.nonTaxableIncome()).add(safe(input.perDiemExempt())));

        PayrollClassifiedCalculation calculation = payrollCalculator.calculateClassified(new PayrollClassifiedCalculationInput(
            employee.employeeId(),
            employee.employeeCode() + " " + employee.employeeName(),
            componentAmounts,
            componentTaxTreatments,
            componentSsoInclusion,
            input == null ? BigDecimal.ZERO : input.unpaidLeaveDays(),
            leaveRefundDays == null ? BigDecimal.ZERO : leaveRefundDays,
            input == null ? BigDecimal.ZERO : input.studentLoanDeduction(),
            input == null ? BigDecimal.ZERO : input.otherPretaxDeduction(),
            input == null ? BigDecimal.ZERO : input.otherPostTaxDeductions(),
            // หักตามใบเตือน (handoff section 6): POST-TAX only now.
            input == null ? BigDecimal.ZERO : input.warningLetterDeduction(),
            customerReturnDeduction,
            customerReturnAlreadyEarned,
            input == null ? BigDecimal.ZERO : safe(input.legalExecutionDeduction()),
            input == null ? null : input.garnishmentType(),
            mergeAllowances(storedAllowances, input),
            yearToDate,
            payrollMonth.getMonthValue(),
            // Rebase consequence (2026-07-29): retirementAllowance's taxYear parameter (117) must reach
            // calculateClassified too (see PayrollClassifiedCalculationDtos#taxYear javadoc). Derived
            // the same way the outer preview()/process() methods already derive it for the tax-
            // treatment/SSO-inclusion lookups above.
            payrollMonth.getYear(),
            effectiveWithholdingOverride
        ));
        return new PayrollLineDto(
            null,
            employee.employeeId(),
            employee.employeeCode(),
            employee.employeeName(),
            employee.departmentName(),
            employee.bankName(),
            employee.bankAccount(),
            calculation.baseSalary(),
            calculation.dailyRate(),
            calculation.hourlyRate(),
            specialPayDtos(calculation.specialPays()),
            calculation.specialPayTotal(),
            calculation.overtimePay(),
            calculation.commissionPay(),
            calculation.grossEarnings(),
            calculation.nonTaxableIncome(),
            calculation.unpaidLeaveDays(),
            calculation.unpaidLeaveDeduction(),
            calculation.grossTaxableIncome(),
            calculation.ssoWageBase(),
            calculation.socialSecurity(),
            calculation.projectedAnnualIncome(),
            calculation.taxExpenseDeduction(),
            calculation.taxAllowanceTotal(),
            calculation.taxableAnnualIncome(),
            calculation.annualTax(),
            calculation.withholdingTax(),
            calculation.studentLoanDeduction(),
            calculation.legalExecutionDeduction(),
            calculation.otherPostTaxDeductions(),
            calculation.totalDeductions(),
            calculation.netPay(),
            calculation.calculationNote(),
            calculation.directorRemuneration(),
            calculation.warningLetterDeduction(),
            calculation.customerReturnDeduction(),
            calculation.otherPretaxDeduction(),
            calculation.leaveRefundDays(),
            calculation.leaveDeductionRefund(),
            // Persist the PER-RUN typed override only (nullable). Deliberately NOT the resolved
            // effective override: the standing employee value re-applies from the employee record every
            // run, so storing it on the line and carrying it forward would double-apply it. When HR
            // relied on the standing value this stays null and next month's carry-forward is empty --
            // exactly the desired "standing keeps applying, per-run field starts blank" behaviour.
            perRunWithholdingOverride,
            // ป.96/2543 limbs (V92, regular/variable 2-limb): calculateLine now runs exclusively through
            // calculateClassified (V96, regular/known/cumulative 3-limb -- see PayrollClassifiedCalculation
            // below), which has no concept of this older 2-limb split at all. Backfilled the same way
            // PayrollLineDto's own 40-arg legacy constructor already backfills the NEW limb fields for a
            // caller with no limb concept: everything attributed to the regular limb (grossTaxableIncome /
            // withholdingTax in full), nothing to the variable limb. These four columns are therefore
            // vestigial for every line processed from this rebase forward -- kept only so historical rows
            // written by the pre-task-2 engine still round-trip through this record's shape.
            calculation.grossTaxableIncome(),
            BigDecimal.ZERO,
            calculation.withholdingTax(),
            BigDecimal.ZERO,
            calculation.bonusPay(),
            calculation.otherOneOffPay(),
            // F3 fix (Opus review, 2026-07-30): this used to be hardcoded to ZERO on every processed
            // line -- see calculateClassified's own comment on excessWithheldToDate for the computation
            // and why it now flows through. Kept persisting it so PayslipRenderer's over-withholding
            // notice (line 158) and the ภ.ง.ด.1 working-paper figure spec section 8 requires are both
            // reachable again.
            calculation.excessWithheldToDate(),
            calculation.taxableIncomeRegularLimb(),
            calculation.taxableIncomeKnownLimb(),
            calculation.taxableIncomeCumulativeLimb(),
            calculation.withholdingTaxRegularLimb(),
            calculation.withholdingTaxCumulativeLimb(),
            calculation.customerReturnAlreadyEarned(),
            calculation.garnishmentType().name(),
            calculation.mealAllowance(),
            // perDiemExempt/perDiemBasis never reach the calculator: perDiemExempt is folded into
            // NON_TAXABLE_INCOME above (it has no ป.96 treatment to classify -- see PayrollComponent's
            // javadoc), and perDiemBasis is pure metadata with no arithmetic role at all. Both are
            // passed straight through from the request so they are still persisted on the line.
            input == null ? BigDecimal.ZERO : safe(input.perDiemExempt()),
            calculation.perDiemTaxable(),
            input == null || input.perDiemBasis() == null ? null : input.perDiemBasis().name(),
            // D1 fix (fourth reachability audit, 2026-07-30, V101): the raw request value, ALWAYS --
            // regardless of customerReturnAlreadyEarned -- so the form field (and the checkbox that
            // gates on it being > 0) round-trip after a reload. Deliberately NOT `customerReturnDeduction`
            // above, which is calculateClassified's POST-TAX bookkeeping figure and stays 0 in the
            // unearned path (the amount is instead netted pre-tax out of commissionPay, see this
            // method's own `effectiveCommissionPay` computation above).
            customerReturnDeduction
        );
    }

    private BigDecimal amountAt(List<BigDecimal> values, int index) {
        BigDecimal value = values == null || values.size() <= index ? null : values.get(index);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * จำนวนคราวที่ต้องจ่าย remaining in this tax year, INCLUDING this period — คำชี้แจง ภ.ง.ด.1 ข้อ 2.1.
     *
     * <p>Monthly payroll, so this is {@code 13 - month}: 12 in January, 1 in December. A mid-year
     * JOINER needs no special handling — their year-to-date is empty, so January..March simply never
     * enter the projection and an April start naturally projects over 9 periods, which is the RD's own
     * worked example in ข้อ 2.1.
     *
     * <p>A mid-year LEAVER is NOT handled, so ข้อ 2.10's final-period true-up does not happen. It WAS
     * implemented against {@code hr.resignation} and then removed (2026-07-29): the owner confirms
     * resignations are not recorded in this platform at all — they live in another system — so nothing
     * ever populates that table, and {@code findActiveEmployees} filters on {@code is_active} anyway,
     * which drops a leaver out of payroll before any of it could apply. Code that cannot execute is
     * worse than an acknowledged gap, because it reads as a compliance feature that works. ข้อ 2.10 is
     * on the known-gaps list pending resignation data from the other platform.
     */
    private int remainingPayPeriods(LocalDate payrollMonth) {
        return Math.max(1, 13 - payrollMonth.getMonthValue());
    }

    /**
     * C1: merges the standing stored declaration with this run's request body, field by field. A
     * non-null field on the request is an explicit in-run correction and wins; a null field falls back
     * to the stored standing declaration.
     */
    private PayrollTaxAllowanceInput mergeAllowances(PayrollTaxAllowanceInput stored, PayrollEmployeeInputRequest input) {
        PayrollTaxAllowanceInput base = stored == null ? PayrollTaxAllowanceInput.empty() : stored;
        if (input == null) {
            return base;
        }
        return new PayrollTaxAllowanceInput(
            firstNonNull(input.spouseAllowance(), base.spouseAllowance()),
            firstNonNull(input.childAllowance(), base.childAllowance()),
            firstNonNull(input.parentCareAllowance(), base.parentCareAllowance()),
            firstNonNull(input.disabledCareAllowance(), base.disabledCareAllowance()),
            firstNonNull(input.maternityAllowance(), base.maternityAllowance()),
            firstNonNull(input.lifeInsuranceAllowance(), base.lifeInsuranceAllowance()),
            firstNonNull(input.healthInsuranceAllowance(), base.healthInsuranceAllowance()),
            firstNonNull(input.parentHealthInsuranceAllowance(), base.parentHealthInsuranceAllowance()),
            firstNonNull(input.rmfAllowance(), base.rmfAllowance()),
            firstNonNull(input.ssfAllowance(), base.ssfAllowance()),
            firstNonNull(input.pensionInsuranceAllowance(), base.pensionInsuranceAllowance()),
            firstNonNull(input.thaiEsgAllowance(), base.thaiEsgAllowance()),
            firstNonNull(input.homeLoanInterestAllowance(), base.homeLoanInterestAllowance()),
            firstNonNull(input.educationDonation(), base.educationDonation()),
            firstNonNull(input.generalDonation(), base.generalDonation()),
            firstNonNull(input.politicalDonation(), base.politicalDonation()),
            // Head counts come from the stored ล.ย.01 declaration -- standing facts about the
            // employee's household, recorded with evidence, not something a payroll operator retypes.
            //
            // EXCEPT when the run body supplies an AMOUNT the stored declaration cannot support. The
            // amount and the count travel separately, so an employee with no stored row for whom HR
            // types childAllowance = 60,000 would otherwise meet a cap of zero and lose the whole
            // allowance silently. Deriving a count from the supplied amount in exactly that case is the
            // same decision taken in PayrollTaxAllowanceInput's legacy constructor and V93's backfill:
            // the amount is the only evidence there is, and reading it as an overstatement would delete
            // a real declaration.
            headCountFor(base.childCount(), input.childAllowance(), "30000"),
            base.childCountDouble(),
            headCountFor(base.disabledCareCount(), input.disabledCareAllowance(), "60000"),
            base.disabilityCardHolder(),
            input.parentCareCount() != null ? input.parentCareCount() : (base.parentCareCount() == null ? 0 : base.parentCareCount())
        );
    }

    /**
     * The head count to apply when the stored ล.ย.01 declaration has NONE and this run supplies an
     * amount: the smallest count that would permit that amount. A stored count always wins outright.
     *
     * <p>Deliberately narrow, and narrowed twice. It exists for exactly one case — an employee with no
     * stored declaration for whom HR types an allowance in the run body, who would otherwise meet a
     * cap of zero and lose it silently. It must NOT do more than that:
     *
     * <ul>
     *   <li>An earlier version took {@code max(declaredCount, impliedCount)} so a STALE count would be
     *       raised too. Review showed that destroyed the cap entirely: the amount fell back to the
     *       STORED figure when the run body omitted the field, so the count was raised to cover the
     *       very amount it exists to constrain, and a ฿300,000 declaration against one child was
     *       allowed in full. Direction of error was UNDER-withholding.</li>
     *   <li>So a stale or too-small count now CLAMPS, and {@code PayrollCalculator#clampedAllowanceNote}
     *       says so on the payslip. Clamp-and-warn is the correct answer to a declaration the head
     *       count cannot support; silently raising the cap is not.</li>
     * </ul>
     *
     * <p>{@code runBodyAmount} must be the request-body value ONLY, never the stored one — falling
     * back to stored is precisely the hole described above.
     */
    private int headCountFor(int storedCount, BigDecimal runBodyAmount, String perHead) {
        if (storedCount > 0 || runBodyAmount == null || runBodyAmount.signum() <= 0) {
            return storedCount;
        }
        return runBodyAmount
            .divide(new BigDecimal(perHead), 0, java.math.RoundingMode.CEILING)
            .intValueExact();
    }

    /**
     * The employee's age in the payroll month's TAX YEAR, for the ยกเว้นเงินได้ 190,000 available from
     * 65 (กฎกระทรวง ฉบับที่ 126).
     *
     * <p>Measured at 31 December of the tax year, not at the payroll month: the exemption belongs to
     * the tax year as a whole, so someone turning 65 in November is 65 for that year's withholding
     * from January. Returns 0 when the date of birth is unknown, which the calculator treats as
     * "do not grant the exemption on an assumption".
     */
    private int taxpayerAge(LocalDate dateOfBirth, LocalDate payrollMonth) {
        if (dateOfBirth == null) {
            return 0;
        }
        return Math.max(0, payrollMonth.getYear() - dateOfBirth.getYear());
    }

    private BigDecimal firstNonNull(BigDecimal requested, BigDecimal stored) {
        if (requested != null) {
            return requested;
        }
        return stored == null ? BigDecimal.ZERO : stored;
    }

    // ---- C1 / C2: HR-typed standing declarations, view broader than edit ----------------------

    public TaxAllowanceListResponse getTaxAllowances(int taxYear, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return new TaxAllowanceListResponse(taxYear, payrollRepository.findTaxAllowanceRows(taxYear));
    }

    @Transactional
    public TaxAllowanceListResponse upsertTaxAllowances(int taxYear, TaxAllowanceBulkUpsertRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        List<EmployeeTaxAllowanceUpsertRequest> items = request == null || request.items() == null
            ? List.of()
            : request.items();
        payrollRepository.upsertTaxAllowances(taxYear, items, actor.employeeId());
        TaxAllowanceListResponse result = new TaxAllowanceListResponse(taxYear, payrollRepository.findTaxAllowanceRows(taxYear));
        auditService.record(actor, "UPSERT_TAX_ALLOWANCES", "employee_tax_allowance", null,
            null, Map.of("taxYear", taxYear, "employeeIds", employeeIdsOf(items)));
        return result;
    }

    public YtdSeedListResponse getYtdSeed(int taxYear, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return new YtdSeedListResponse(taxYear, payrollRepository.findYtdSeedRows(taxYear));
    }

    @Transactional
    public YtdSeedListResponse upsertYtdSeed(int taxYear, YtdSeedBulkUpsertRequest request, UserPrincipal actor) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        List<YtdSeedUpsertRequest> items = request == null || request.items() == null ? List.of() : request.items();
        payrollRepository.upsertYtdSeed(taxYear, items, actor.employeeId());
        YtdSeedListResponse result = new YtdSeedListResponse(taxYear, payrollRepository.findYtdSeedRows(taxYear));
        auditService.record(actor, "UPSERT_PAYROLL_YTD_SEED", "payroll_year_to_date_seed", null,
            null, Map.of("taxYear", taxYear, "employeeIds", ytdEmployeeIdsOf(items)));
        return result;
    }

    // ---- P0 fix (Opus review, 2026-07-30): tax-treatment matrix HTTP surface -------------------
    //
    // PayrollRepository#upsertComponentTaxTreatment existed since task 1 with zero callers in
    // src/main -- nothing could ever write hr.payroll_component_tax_treatment on a real deployment,
    // so PayrollCalculator#calculateClassified's classification gate (correct on its own terms) 409s
    // every run for any employee with a non-zero, non-SALARY component. Same view/edit split as the
    // C1/C2 declarations above (HR+CEO view, HR-only edit).

    public PayrollClassificationDtos.TaxTreatmentListResponse getComponentTaxTreatments(int taxYear, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        return componentTaxTreatmentsSnapshot(taxYear);
    }

    /**
     * Minor fix (fourth reachability audit, 2026-07-30): {@code @Valid} on {@code PayrollController
     * #putComponentTaxTreatments}'s {@code @RequestBody List<ComponentTaxTreatmentUpsertRequest>} is
     * INERT -- Spring's {@code SpringValidatorAdapter} does not cascade {@code @Valid} onto the
     * ELEMENTS of a bare {@code List} request body, only onto fields of an enclosing object. So
     * {@code ComponentTaxTreatmentUpsertRequest}'s own {@code @NotNull employeeId}/{@code component}
     * never actually fire, and a malformed body (a null {@code employeeId}, say) reaches this method
     * and the repository unvalidated -- the frontend always populates both, so this is not a
     * reachability blocker in practice, but a malformed client would previously hit an unclear
     * {@code DataIntegrityViolationException} (or worse) instead of a clean 400.
     *
     * <p>Not fixed by wrapping the list in a new record: {@code
     * PayrollClassificationReachabilityIntegrationTest#hrHasSomeHttpWayToClassifyAComponentBeforeTheEngineDemandsIt}
     * reflects on {@code PayrollController}'s methods for a parameter whose generic type name
     * literally contains {@code ComponentTaxTreatmentUpsertRequest} -- a wrapper type's name would
     * not, and that test is kept verbatim per instruction. Validated explicitly here instead, at the
     * same layer {@link #upsertComponentTaxTreatments}'s sibling validation (F2's per-diem-basis
     * check) already uses.
     */
    @Transactional
    public PayrollClassificationDtos.TaxTreatmentListResponse upsertComponentTaxTreatments(
        int taxYear, List<ComponentTaxTreatmentUpsertRequest> items, UserPrincipal actor
    ) {
        requireRole(actor, PAYROLL_EDIT_ROLES);
        List<ComponentTaxTreatmentUpsertRequest> safeItems = items == null ? List.of() : items;
        for (int index = 0; index < safeItems.size(); index += 1) {
            ComponentTaxTreatmentUpsertRequest item = safeItems.get(index);
            if (item == null || item.employeeId() == null || item.component() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "รายการที่ " + (index + 1) + " ของการจัดประเภทภาษีหัก ณ ที่จ่ายไม่ถูกต้อง: "
                        + "ต้องระบุ employeeId และ component เสมอ");
            }
        }
        payrollRepository.upsertComponentTaxTreatment(taxYear, safeItems, actor.employeeId());
        PayrollClassificationDtos.TaxTreatmentListResponse result = componentTaxTreatmentsSnapshot(taxYear);
        auditService.record(actor, "UPSERT_PAYROLL_COMPONENT_TAX_TREATMENT", "payroll_component_tax_treatment", null,
            null, Map.of("taxYear", taxYear, "employeeIds",
                safeItems.stream().map(ComponentTaxTreatmentUpsertRequest::employeeId).distinct().toList()));
        return result;
    }

    private PayrollClassificationDtos.TaxTreatmentListResponse componentTaxTreatmentsSnapshot(int taxYear) {
        List<PayrollEmployeeSnapshot> employees = payrollRepository.findActiveEmployees();
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> byEmployee =
            payrollRepository.findComponentTaxTreatmentsByEmployee(taxYear);
        List<PayrollClassificationDtos.TaxTreatmentMatrixRow> items = employees.stream()
            .map(employee -> new PayrollClassificationDtos.TaxTreatmentMatrixRow(
                employee.employeeId(),
                employee.employeeCode(),
                employee.employeeName(),
                byEmployee.getOrDefault(employee.employeeId(), Map.of())))
            .toList();
        return new PayrollClassificationDtos.TaxTreatmentListResponse(taxYear, items);
    }

    private List<Long> employeeIdsOf(List<EmployeeTaxAllowanceUpsertRequest> items) {
        return items.stream().map(EmployeeTaxAllowanceUpsertRequest::employeeId).toList();
    }

    private List<Long> ytdEmployeeIdsOf(List<YtdSeedUpsertRequest> items) {
        return items.stream().map(YtdSeedUpsertRequest::employeeId).toList();
    }

    /**
     * Commission-payroll weighted-base + manual-entries fix (2026-07-23): delegates to {@link
     * CommissionService#payrollCommissionTotalsByEmployee}, the exact same weighted-tier +
     * approved-manual-entries aggregation {@link CommissionService#payrollReadySummary} uses for
     * what HR sees on the payroll-ready screen -- the two paths now share one implementation and
     * can never diverge again.
     *
     * <p>This used to reimplement the tier/VAT math independently here, two bugs deep: (1) it
     * summed each APPROVED record's already-2dp-rounded {@code commissionableBase} UNWEIGHTED
     * instead of the weighted, full-precision monthly tier base, underpaying a rep with a
     * 2x/3x-weighted receipt (owner-reconciled "jennet" case: weighted 67,849.23 vs. the old
     * unweighted 67,390.34); and (2) it excluded approved manual entries (ADJUSTMENT/MANAGER/
     * STOCK_BONUS/INCENTIVE, V84) from the payroll figure entirely, even though real payroll pays
     * tier commission PLUS those manual entries (same "jennet" case with a 15,000 INCENTIVE added:
     * 82,849.23). See {@code PayrollCommissionWeightedBaseIntegrationTest} for the real-DB
     * regression coverage of both.
     */
    private Map<Long, BigDecimal> commissionPayByEmployee(LocalDate payrollMonth) {
        return commissionService.payrollCommissionTotalsByEmployee(payrollMonth);
    }

    /**
     * Slot labels, ALIGNED TO THE ACCOUNTANT'S WORKBOOK (2026-07-29, owner decision).
     *
     * <p>`2026.xlsx` numbers these differently from the system's original labels: it carries
     * ค่าเช่าบ้าน as พิเศษ 2, which shifted every later number by one. The names always matched; only
     * the numbers disagreed, so the system's slot 6 (คอมมิชชั่น) was the workbook's พิเศษ 7 and every
     * reconciliation needed a translation table in someone's head.
     *
     * <p>The first decision was to leave the system alone and have the accountant renumber, because
     * renumbering would have redefined 149 processed rows across five filed months. The owner then
     * confirmed **nothing has ever been processed or paid from this ERP** — all five runs were tests,
     * now VOIDed — so that argument collapsed and the decision was reversed. With every period VOID
     * this is a pure relabel: no stored figure moves and no filed return exists to be affected.
     *
     * <p>⚠️ Branch 117 hardcodes commission at slot 6. It is slot 7 now. See the handoff's cross-branch
     * break note — merging 117 without updating that constant puts commission in the wrong ป.96 limb
     * and annualises ค่า GPRS in its place, silently.
     */
    private List<PayrollSpecialPayDto> specialPayDtos(List<BigDecimal> specialPays) {
        return List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1 (ค่าครองชีพ)", specialPays.get(0)),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2 (ค่าเช่าบ้าน)", specialPays.get(1)),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3 (เบี้ยเลี้ยงประจำ)", specialPays.get(2)),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4 (ค่าตำแหน่ง)", specialPays.get(3)),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5 (เบี้ยขยันประจำ)", specialPays.get(4)),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6 (ค่า GPRS)", specialPays.get(5)),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7 (คอมมิชชั่น)", specialPays.get(6)),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8 (ทำได้ตาม KPI)", specialPays.get(7)),
            new PayrollSpecialPayDto("specialPay9", "พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)", specialPays.get(8))
        );
    }

    private List<PayrollEmployeeInputRequest> safeInputs(List<PayrollEmployeeInputRequest> inputs) {
        return inputs == null ? List.of() : inputs;
    }

    private LocalDate normalizeMonth(LocalDate payrollMonth) {
        if (payrollMonth == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "payrollMonth is required");
        }
        return payrollMonth.withDayOfMonth(1);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (actor == null || !allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private BigDecimal sum(List<PayrollLineDto> lines, MoneyExtractor extractor) {
        return lines.stream().map(extractor::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void auditPayrollAccess(String action, UserPrincipal actor, PayrollPeriodDto period, String fields) {
        String targetEmployeeIds = period.lines().stream()
            .map(PayrollLineDto::employeeId)
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        AUDIT.info(
            "sensitive_data_access action={} actorId={} actorEmail=\"{}\" payrollPeriodId={} payrollMonth={} targetEmployeeIds=\"{}\" resultCount={} fields=\"{}\"",
            action,
            actor.id(),
            actor.email(),
            period.id(),
            period.payrollMonth(),
            targetEmployeeIds,
            period.lines().size(),
            fields);
    }

    private void auditPayrollLineAccess(String action, UserPrincipal actor, PayrollPeriodDto period, PayrollLineDto line, String fields) {
        AUDIT.info(
            "sensitive_data_access action={} actorId={} actorEmail=\"{}\" payrollPeriodId={} payrollMonth={} targetEmployeeIds=\"{}\" resultCount={} fields=\"{}\"",
            action,
            actor.id(),
            actor.email(),
            period.id(),
            period.payrollMonth(),
            line.employeeId(),
            1,
            fields);
    }

    private Map<String, Object> auditPayload(PayrollPeriodDto period, PayrollLineDto line) {
        return Map.of(
            "periodId", period.id(),
            "payrollMonth", period.payrollMonth(),
            "lineId", line.id(),
            "employeeId", line.employeeId());
    }

    private interface MoneyExtractor {
        BigDecimal value(PayrollLineDto line);
    }
}
