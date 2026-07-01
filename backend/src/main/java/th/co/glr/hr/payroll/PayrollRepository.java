package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class PayrollRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PayrollRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PayrollEmployeeSnapshot> findActiveEmployees() {
        return jdbc.query("""
            SELECT e.employee_id,
                   e.employee_code,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
                   dep.name_th AS department_name,
                   bank.name_th AS bank_name,
                   ba.account_no AS bank_account,
                   COALESCE(e.current_salary, 0) AS base_salary
              FROM hr.employee e
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
              LEFT JOIN hr.employee_bank_account ba ON ba.employee_id = e.employee_id
              LEFT JOIN hr.bank bank ON bank.bank_id = ba.bank_id
             WHERE e.is_active = TRUE
               AND COALESCE(e.current_salary, 0) > 0
             ORDER BY e.employee_code
            """, Map.of(), (rs, rowNum) -> new PayrollEmployeeSnapshot(
                rs.getLong("employee_id"),
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                rs.getString("department_name"),
                rs.getString("bank_name"),
                rs.getString("bank_account"),
                rs.getBigDecimal("base_salary")
            ));
    }

    public Map<Long, BigDecimal> findApprovedOvertimePayByEmployee(LocalDate payrollMonth) {
        return jdbc.query("""
            SELECT ot.employee_id,
                   COALESCE(SUM((ot.payable_minutes::numeric / 60)
                       * ((COALESCE(e.current_salary, 0) / 30 / 8) * ot.pay_rate_multiplier)), 0) AS overtime_pay
              FROM hr.overtime_request ot
              JOIN hr.employee e ON e.employee_id = ot.employee_id
             WHERE ot.status = 'APPROVED'
               AND ot.payroll_month = :payrollMonth
             GROUP BY ot.employee_id
            """,
            Map.of("payrollMonth", payrollMonth),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), money(rs.getBigDecimal("overtime_pay"))))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<Long, PayrollYearToDate> findYearToDateByEmployee(LocalDate payrollMonth) {
        return jdbc.query("""
            SELECT pl.employee_id,
                   COALESCE(SUM(pl.gross_taxable_income), 0) AS taxable_income,
                   COALESCE(SUM(pl.social_security), 0) AS social_security,
                   COALESCE(SUM(pl.withholding_tax), 0) AS withholding_tax
              FROM hr.payroll_line pl
              JOIN hr.payroll_period pp ON pp.period_id = pl.period_id
             WHERE pp.payroll_month >= date_trunc('year', :payrollMonth::date)::date
               AND pp.payroll_month < :payrollMonth
               AND pp.status <> 'VOID'
             GROUP BY pl.employee_id
            """,
            Map.of("payrollMonth", payrollMonth),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), new PayrollYearToDate(
                money(rs.getBigDecimal("taxable_income")),
                money(rs.getBigDecimal("social_security")),
                money(rs.getBigDecimal("withholding_tax"))
            )))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Optional<PayrollPeriodDto> findPeriodByMonth(LocalDate payrollMonth) {
        return findPeriod("""
            SELECT period_id, payroll_month, period_start, period_end, pay_date, status, processed_at, processed_by_id
              FROM hr.payroll_period
             WHERE payroll_month = :payrollMonth
            """, new MapSqlParameterSource("payrollMonth", payrollMonth));
    }

    public Optional<PayrollPeriodDto> findPeriodById(long periodId) {
        return findPeriod("""
            SELECT period_id, payroll_month, period_start, period_end, pay_date, status, processed_at, processed_by_id
              FROM hr.payroll_period
             WHERE period_id = :periodId
            """, new MapSqlParameterSource("periodId", periodId));
    }

    public long saveProcessedPeriod(LocalDate payrollMonth, Long processedById, List<PayrollLineDto> lines) {
        long periodId = findPeriodId(payrollMonth).orElseGet(() -> insertPeriod(payrollMonth));
        jdbc.update("DELETE FROM hr.payroll_line WHERE period_id = :periodId", Map.of("periodId", periodId));
        jdbc.update("""
            UPDATE hr.payroll_period
               SET period_start = :periodStart,
                   period_end = :periodEnd,
                   pay_date = :payDate,
                   status = 'PROCESSED',
                   processed_at = now(),
                   processed_by_id = :processedById
             WHERE period_id = :periodId
            """,
            new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("periodStart", payrollMonth)
                .addValue("periodEnd", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()))
                .addValue("payDate", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()))
                .addValue("processedById", processedById));
        for (PayrollLineDto line : lines) {
            insertLine(periodId, line);
        }
        return periodId;
    }

    public List<PayrollLineDto> findLines(long periodId) {
        return jdbc.query("""
            SELECT pl.line_id, pl.employee_id, e.employee_code,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
                   dep.name_th AS department_name,
                   bank.name_th AS bank_name,
                   ba.account_no AS bank_account,
                   pl.base_salary, pl.daily_rate, pl.hourly_rate,
                   pl.special_pay_1, pl.special_pay_2, pl.special_pay_3, pl.special_pay_4,
                   pl.special_pay_5, pl.special_pay_6, pl.special_pay_7, pl.special_pay_8,
                   pl.special_pay_total, pl.overtime_pay, pl.commission_pay,
                   pl.gross_amount, pl.unpaid_leave_days, pl.unpaid_leave_deduction,
                   pl.gross_taxable_income, pl.sso_wage_base, pl.social_security,
                   pl.projected_annual_income, pl.tax_expense_deduction, pl.tax_allowance_total,
                   pl.taxable_annual_income, pl.annual_tax, pl.withholding_tax,
                   pl.student_loan_deduction, pl.legal_execution_deduction,
                   pl.other_post_tax_deductions, pl.deductions, pl.net_amount,
                   pl.calculation_note
              FROM hr.payroll_line pl
              JOIN hr.employee e ON e.employee_id = pl.employee_id
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
              LEFT JOIN hr.employee_bank_account ba ON ba.employee_id = e.employee_id
              LEFT JOIN hr.bank bank ON bank.bank_id = ba.bank_id
             WHERE pl.period_id = :periodId
             ORDER BY e.employee_code
            """,
            Map.of("periodId", periodId),
            (rs, rowNum) -> mapLine(rs));
    }

    private Optional<PayrollPeriodDto> findPeriod(String sql, MapSqlParameterSource params) {
        try {
            PayrollPeriodHeader header = jdbc.queryForObject(sql, params, (rs, rowNum) -> new PayrollPeriodHeader(
                rs.getLong("period_id"),
                rs.getObject("payroll_month", LocalDate.class),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getObject("pay_date", LocalDate.class),
                rs.getString("status"),
                rs.getObject("processed_at", OffsetDateTime.class),
                nullableLong(rs, "processed_by_id")
            ));
            if (header == null) {
                return Optional.empty();
            }
            List<PayrollLineDto> lines = findLines(header.periodId());
            return Optional.of(toPeriod(header, lines));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Optional<Long> findPeriodId(LocalDate payrollMonth) {
        try {
            Long id = jdbc.queryForObject("""
                SELECT period_id FROM hr.payroll_period WHERE payroll_month = :payrollMonth
                """, Map.of("payrollMonth", payrollMonth), Long.class);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private long insertPeriod(LocalDate payrollMonth) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (:payrollMonth, :periodStart, :periodEnd, :payDate, 'PROCESSED')
            """,
            new MapSqlParameterSource()
                .addValue("payrollMonth", payrollMonth)
                .addValue("periodStart", payrollMonth)
                .addValue("periodEnd", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()))
                .addValue("payDate", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth())),
            keyHolder,
            new String[]{"period_id"});
        return keyHolder.getKey().longValue();
    }

    private void insertLine(long periodId, PayrollLineDto line) {
        jdbc.update("""
            INSERT INTO hr.payroll_line (
                period_id, employee_id, base_salary, daily_rate, hourly_rate,
                special_pay_1, special_pay_2, special_pay_3, special_pay_4,
                special_pay_5, special_pay_6, special_pay_7, special_pay_8,
                special_pay_total, overtime_pay, commission_pay, gross_amount,
                unpaid_leave_days, unpaid_leave_deduction, gross_taxable_income,
                sso_wage_base, social_security, projected_annual_income,
                tax_expense_deduction, tax_allowance_total, taxable_annual_income,
                annual_tax, withholding_tax, student_loan_deduction,
                legal_execution_deduction, other_post_tax_deductions, deductions,
                net_amount, calculation_note
            )
            VALUES (
                :periodId, :employeeId, :baseSalary, :dailyRate, :hourlyRate,
                :specialPay1, :specialPay2, :specialPay3, :specialPay4,
                :specialPay5, :specialPay6, :specialPay7, :specialPay8,
                :specialPayTotal, :overtimePay, :commissionPay, :grossAmount,
                :unpaidLeaveDays, :unpaidLeaveDeduction, :grossTaxableIncome,
                :ssoWageBase, :socialSecurity, :projectedAnnualIncome,
                :taxExpenseDeduction, :taxAllowanceTotal, :taxableAnnualIncome,
                :annualTax, :withholdingTax, :studentLoanDeduction,
                :legalExecutionDeduction, :otherPostTaxDeductions, :deductions,
                :netAmount, :calculationNote
            )
            """,
            new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("employeeId", line.employeeId())
                .addValue("baseSalary", line.baseSalary())
                .addValue("dailyRate", line.dailyRate())
                .addValue("hourlyRate", line.hourlyRate())
                .addValue("specialPay1", amount(line.specialPays(), 0))
                .addValue("specialPay2", amount(line.specialPays(), 1))
                .addValue("specialPay3", amount(line.specialPays(), 2))
                .addValue("specialPay4", amount(line.specialPays(), 3))
                .addValue("specialPay5", amount(line.specialPays(), 4))
                .addValue("specialPay6", amount(line.specialPays(), 5))
                .addValue("specialPay7", amount(line.specialPays(), 6))
                .addValue("specialPay8", amount(line.specialPays(), 7))
                .addValue("specialPayTotal", line.specialPayTotal())
                .addValue("overtimePay", line.overtimePay())
                .addValue("commissionPay", line.commissionPay())
                .addValue("grossAmount", line.grossEarnings())
                .addValue("unpaidLeaveDays", line.unpaidLeaveDays())
                .addValue("unpaidLeaveDeduction", line.unpaidLeaveDeduction())
                .addValue("grossTaxableIncome", line.grossTaxableIncome())
                .addValue("ssoWageBase", line.ssoWageBase())
                .addValue("socialSecurity", line.socialSecurity())
                .addValue("projectedAnnualIncome", line.projectedAnnualIncome())
                .addValue("taxExpenseDeduction", line.taxExpenseDeduction())
                .addValue("taxAllowanceTotal", line.taxAllowanceTotal())
                .addValue("taxableAnnualIncome", line.taxableAnnualIncome())
                .addValue("annualTax", line.annualTax())
                .addValue("withholdingTax", line.withholdingTax())
                .addValue("studentLoanDeduction", line.studentLoanDeduction())
                .addValue("legalExecutionDeduction", line.legalExecutionDeduction())
                .addValue("otherPostTaxDeductions", line.otherPostTaxDeductions())
                .addValue("deductions", line.totalDeductions())
                .addValue("netAmount", line.netPay())
                .addValue("calculationNote", line.calculationNote()));
    }

    private PayrollPeriodDto toPeriod(PayrollPeriodHeader header, List<PayrollLineDto> lines) {
        return new PayrollPeriodDto(
            header.periodId(),
            header.payrollMonth(),
            header.periodStart(),
            header.periodEnd(),
            header.payDate(),
            header.status(),
            header.processedAt(),
            header.processedById(),
            lines.size(),
            sum(lines, PayrollLineDto::grossEarnings),
            sum(lines, PayrollLineDto::totalDeductions),
            sum(lines, PayrollLineDto::netPay),
            sum(lines, PayrollLineDto::socialSecurity),
            sum(lines, PayrollLineDto::withholdingTax),
            lines
        );
    }

    private PayrollLineDto mapLine(ResultSet rs) throws SQLException {
        return new PayrollLineDto(
            rs.getLong("line_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getString("department_name"),
            rs.getString("bank_name"),
            rs.getString("bank_account"),
            money(rs.getBigDecimal("base_salary")),
            rs.getBigDecimal("daily_rate"),
            rs.getBigDecimal("hourly_rate"),
            specialPays(rs),
            money(rs.getBigDecimal("special_pay_total")),
            money(rs.getBigDecimal("overtime_pay")),
            money(rs.getBigDecimal("commission_pay")),
            money(rs.getBigDecimal("gross_amount")),
            money(rs.getBigDecimal("unpaid_leave_days")),
            money(rs.getBigDecimal("unpaid_leave_deduction")),
            money(rs.getBigDecimal("gross_taxable_income")),
            money(rs.getBigDecimal("sso_wage_base")),
            money(rs.getBigDecimal("social_security")),
            money(rs.getBigDecimal("projected_annual_income")),
            money(rs.getBigDecimal("tax_expense_deduction")),
            money(rs.getBigDecimal("tax_allowance_total")),
            money(rs.getBigDecimal("taxable_annual_income")),
            money(rs.getBigDecimal("annual_tax")),
            money(rs.getBigDecimal("withholding_tax")),
            money(rs.getBigDecimal("student_loan_deduction")),
            money(rs.getBigDecimal("legal_execution_deduction")),
            money(rs.getBigDecimal("other_post_tax_deductions")),
            money(rs.getBigDecimal("deductions")),
            money(rs.getBigDecimal("net_amount")),
            rs.getString("calculation_note")
        );
    }

    private List<PayrollSpecialPayDto> specialPays(ResultSet rs) throws SQLException {
        return List.of(
            specialPay("specialPay1", "เงินพิเศษ 1", rs.getBigDecimal("special_pay_1")),
            specialPay("specialPay2", "เงินพิเศษ 2", rs.getBigDecimal("special_pay_2")),
            specialPay("specialPay3", "เงินพิเศษ 3", rs.getBigDecimal("special_pay_3")),
            specialPay("specialPay4", "เงินพิเศษ 4", rs.getBigDecimal("special_pay_4")),
            specialPay("specialPay5", "เงินพิเศษ 5", rs.getBigDecimal("special_pay_5")),
            specialPay("specialPay6", "เงินพิเศษ 6", rs.getBigDecimal("special_pay_6")),
            specialPay("specialPay7", "เงินพิเศษ 7", rs.getBigDecimal("special_pay_7")),
            specialPay("specialPay8", "เงินพิเศษ 8", rs.getBigDecimal("special_pay_8"))
        );
    }

    private PayrollSpecialPayDto specialPay(String key, String label, BigDecimal amount) {
        return new PayrollSpecialPayDto(key, label, money(amount));
    }

    private BigDecimal amount(List<PayrollSpecialPayDto> values, int index) {
        return values == null || values.size() <= index ? BigDecimal.ZERO : values.get(index).amount();
    }

    private BigDecimal sum(List<PayrollLineDto> lines, MoneyExtractor extractor) {
        return lines.stream().map(extractor::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record PayrollPeriodHeader(
        long periodId,
        LocalDate payrollMonth,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate payDate,
        String status,
        OffsetDateTime processedAt,
        Long processedById
    ) {}

    private interface MoneyExtractor {
        BigDecimal value(PayrollLineDto line);
    }
}
