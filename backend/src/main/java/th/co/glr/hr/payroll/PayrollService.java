package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRecord;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.TierConfig;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;

@Service
public class PayrollService {
    private static final Set<String> PAYROLL_ROLES = Set.of("hr", "admin");
    private static final Logger AUDIT = LoggerFactory.getLogger("th.co.glr.hr.audit");
    private static final ZoneId PAYROLL_ZONE = ZoneId.of("Asia/Bangkok");

    private final PayrollRepository payrollRepository;
    private final PayrollCalculator payrollCalculator;
    private final CommissionRepository commissionRepository;
    private final CommissionCalculator commissionCalculator;
    private final AppProperties properties;

    public PayrollService(
        PayrollRepository payrollRepository,
        PayrollCalculator payrollCalculator,
        CommissionRepository commissionRepository,
        CommissionCalculator commissionCalculator,
        AppProperties properties
    ) {
        this.payrollRepository = payrollRepository;
        this.payrollCalculator = payrollCalculator;
        this.commissionRepository = commissionRepository;
        this.commissionCalculator = commissionCalculator;
        this.properties = properties;
    }

    public PayrollPeriodDto currentOrPreview(LocalDate payrollMonth, UserPrincipal actor) {
        requirePayrollRole(actor);
        LocalDate month = normalizeMonth(payrollMonth);
        return payrollRepository.findPeriodByMonth(month)
            .map(period -> {
                auditPayrollAccess("VIEW_PAYROLL_PERIOD", actor, period,
                    "base_salary,gross_earnings,deductions,net_pay,bank_account");
                return period;
            })
            .orElseGet(() -> preview(month, List.of(), actor));
    }

    public PayrollPeriodDto preview(ProcessPayrollRequest request, UserPrincipal actor) {
        requirePayrollRole(actor);
        return preview(normalizeMonth(request.payrollMonth()), safeInputs(request.inputs()), actor);
    }

    @Transactional
    public PayrollPeriodDto process(ProcessPayrollRequest request, UserPrincipal actor) {
        requirePayrollRole(actor);
        LocalDate month = normalizeMonth(request.payrollMonth());
        PayrollPeriodDto preview = preview(month, safeInputs(request.inputs()), actor);
        long periodId = payrollRepository.saveProcessedPeriod(month, actor.employeeId(), preview.lines());
        PayrollPeriodDto period = payrollRepository.findPeriodById(periodId)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Payroll period was not saved"));
        auditPayrollAccess("PROCESS_PAYROLL", actor, period,
            "base_salary,gross_earnings,deductions,net_pay");
        return period;
    }

    public String bankExport(long periodId, UserPrincipal actor) {
        requirePayrollRole(actor);
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

    private PayrollPeriodDto preview(LocalDate payrollMonth, List<PayrollEmployeeInputRequest> inputs, UserPrincipal actor) {
        Map<Long, PayrollEmployeeInputRequest> inputByEmployee = inputs.stream()
            .collect(Collectors.toMap(PayrollEmployeeInputRequest::employeeId, Function.identity(), (left, right) -> right));
        List<PayrollEmployeeSnapshot> employees = payrollRepository.findActiveEmployees();
        Map<Long, BigDecimal> overtimeByEmployee = payrollRepository.findApprovedOvertimePayByEmployee(payrollMonth);
        Map<Long, BigDecimal> commissionByEmployee = commissionPayByEmployee(payrollMonth);
        Map<Long, PayrollYearToDate> yearToDateByEmployee = payrollRepository.findYearToDateByEmployee(payrollMonth);
        Map<Long, BigDecimal> autoUnpaidLeaveByEmployee = payrollRepository.findAutoUnpaidLeaveDaysByEmployee(
            payrollMonth, LocalDate.now(PAYROLL_ZONE), workdayNumbers());

        List<PayrollLineDto> lines = employees.stream()
            .map(employee -> calculateLine(
                employee,
                inputByEmployee.get(employee.employeeId()),
                overtimeByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                commissionByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
                yearToDateByEmployee.getOrDefault(employee.employeeId(), PayrollYearToDate.empty()),
                autoUnpaidLeaveByEmployee.getOrDefault(employee.employeeId(), BigDecimal.ZERO),
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
        BigDecimal autoUnpaidLeaveDays,
        LocalDate payrollMonth
    ) {
        // Prefill unpaid-leave days from attendance/leave when HR hasn't entered an override for this
        // employee; an explicit input (any manual adjustment row) takes precedence so HR stays in control.
        PayrollCalculation calculation = payrollCalculator.calculate(new PayrollCalculationInput(
            employee.baseSalary(),
            input == null ? List.of() : input.specialPays(),
            overtimePay,
            commissionPay,
            input == null ? BigDecimal.ZERO : input.nonTaxableIncome(),
            input == null ? autoUnpaidLeaveDays : input.unpaidLeaveDays(),
            input == null ? BigDecimal.ZERO : input.studentLoanDeduction(),
            input == null ? BigDecimal.ZERO : input.legalExecutionDeduction(),
            input == null ? BigDecimal.ZERO : input.otherPostTaxDeductions(),
            input == null ? PayrollTaxAllowanceInput.empty() : input.taxAllowances(),
            yearToDate,
            payrollMonth.getMonthValue()
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
            calculation.calculationNote()
        );
    }

    private Map<Long, BigDecimal> commissionPayByEmployee(LocalDate payrollMonth) {
        List<CommissionRecord> records = commissionRepository.findApprovedRecordsByMonth(payrollMonth);
        List<TierConfig> tiers = commissionRepository.findTiers();
        List<TierConfig> safeTiers = tiers.isEmpty() ? TierConfig.defaults() : tiers;
        return records.stream()
            .collect(Collectors.groupingBy(
                CommissionRecord::salesRepId,
                Collectors.mapping(CommissionRecord::commissionableBase, Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))
            ))
            .entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> commissionCalculator.progressiveCommission(entry.getValue(), safeTiers)));
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

    /** Configured workdays as ISO day-of-week numbers (Mon=1 … Sun=7) for absence detection. */
    private List<Integer> workdayNumbers() {
        return properties.getAttendance().getWorkdays().stream()
            .map(day -> DayOfWeek.valueOf(day.trim().toUpperCase()).getValue())
            .distinct()
            .toList();
    }

    private LocalDate normalizeMonth(LocalDate payrollMonth) {
        if (payrollMonth == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "payrollMonth is required");
        }
        return payrollMonth.withDayOfMonth(1);
    }

    private void requirePayrollRole(UserPrincipal actor) {
        if (actor == null || !PAYROLL_ROLES.contains(actor.role())) {
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

    private interface MoneyExtractor {
        BigDecimal value(PayrollLineDto line);
    }
}
