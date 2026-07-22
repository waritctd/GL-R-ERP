package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
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

    public PayrollService(
        PayrollRepository payrollRepository,
        PayrollCalculator payrollCalculator,
        CommissionService commissionService,
        AuditService auditService,
        PayslipRenderer payslipRenderer
    ) {
        this.payrollRepository = payrollRepository;
        this.payrollCalculator = payrollCalculator;
        this.commissionService = commissionService;
        this.auditService = auditService;
        this.payslipRenderer = payslipRenderer;
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
     */
    public PayrollCarryForwardDtos.SuggestedInputsResponse suggestedInputs(LocalDate payrollMonth, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        LocalDate month = normalizeMonth(payrollMonth);
        return new PayrollCarryForwardDtos.SuggestedInputsResponse(
            month, payrollRepository.findCarryForwardSuggestions(month));
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
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Payroll period was not saved"));
        auditPayrollAccess("PROCESS_PAYROLL", actor, period,
            "base_salary,gross_earnings,deductions,net_pay");
        auditService.record(actor, "PROCESS_PAYROLL", "payroll_period", periodId, null, period);
        return period;
    }

    public String bankExport(long periodId, UserPrincipal actor) {
        requireRole(actor, PAYROLL_VIEW_ROLES);
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payroll period not found"));
        StringBuilder builder = new StringBuilder();
        builder.append("GLR_PAYROLL|")
            .append(period.payrollMonth())
            .append('|')
            .append(period.lineCount())
            .append('|')
            .append(period.totalNet())
            .append('\n');
        for (PayrollLineDto line : period.lines()) {
            builder.append(nullToBlank(line.bankAccount())).append('|')
                .append(line.employeeCode()).append('|')
                .append(line.employeeName()).append('|')
                .append(line.netPay()).append('\n');
        }
        auditPayrollAccess("EXPORT_PAYROLL_BANK_FILE", actor, period, "bank_account,net_pay");
        return builder.toString();
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
            payrollRepository.findTaxAllowancesByEmployee(payrollMonth.getYear());

        List<PayrollLineDto> lines = employees.stream()
            .map(employee -> calculateLine(
                employee,
                inputByEmployee.get(employee.employeeId()),
                overtimeByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                commissionByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                yearToDateByEmployee.getOrDefault(employee.employeeId(), PayrollYearToDate.empty()),
                storedAllowancesByEmployee.get(employee.employeeId()),
                payrollMonth
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

    private PayrollLineDto calculateLine(
        PayrollEmployeeSnapshot employee,
        PayrollEmployeeInputRequest input,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        PayrollYearToDate yearToDate,
        PayrollTaxAllowanceInput storedAllowances,
        LocalDate payrollMonth
    ) {
        PayrollCalculation calculation = payrollCalculator.calculate(new PayrollCalculationInput(
            employee.baseSalary(),
            input == null ? List.of() : input.specialPays(),
            overtimePay,
            commissionPay,
            input == null ? BigDecimal.ZERO : input.nonTaxableIncome(),
            input == null ? BigDecimal.ZERO : input.unpaidLeaveDays(),
            input == null ? BigDecimal.ZERO : input.studentLoanDeduction(),
            input == null ? BigDecimal.ZERO : input.legalExecutionDeduction(),
            input == null ? BigDecimal.ZERO : input.otherPostTaxDeductions(),
            mergeAllowances(storedAllowances, input),
            yearToDate,
            payrollMonth.getMonthValue(),
            employee.directorRemuneration(),
            input == null ? BigDecimal.ZERO : input.warningLetterDeduction(),
            input == null ? BigDecimal.ZERO : input.customerReturnDeduction(),
            input == null ? BigDecimal.ZERO : input.otherPretaxDeduction()
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
            calculation.otherPretaxDeduction()
        );
    }

    /**
     * C1: merges the standing stored declaration with this run's request body, field by field. A
     * non-null field on the request is an explicit in-run correction and wins; a null field falls back
     * to the stored value (or zero if nothing is stored yet). This is deliberately per-field rather
     * than "any input present replaces everything" -- HR should be able to correct e.g. just this
     * month's donation figure without having to retype the other 15 allowances.
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
            firstNonNull(input.politicalDonation(), base.politicalDonation())
        );
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

    private List<PayrollSpecialPayDto> specialPayDtos(List<BigDecimal> specialPays) {
        return List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1 (ค่าครองชีพ)", specialPays.get(0)),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2 (เบี้ยเลี้ยงประจำ)", specialPays.get(1)),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3 (ค่าตำแหน่ง)", specialPays.get(2)),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4 (เบี้ยขยันประจำ)", specialPays.get(3)),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5 (ค่า GPRS)", specialPays.get(4)),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6 (คอมมิชชั่น)", specialPays.get(5)),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7 (ทำได้ตาม KPI)", specialPays.get(6)),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)", specialPays.get(7))
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

    private String nullToBlank(String value) {
        return value == null ? "" : value;
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
