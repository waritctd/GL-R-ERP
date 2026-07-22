package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises {@link PayrollRepository#findCarryForwardSuggestions} against a real PostgreSQL database
 * — the special-pay carry-forward feature (2026-07-23). New test class (does not touch the existing
 * {@code PayrollRepositoryIntegrationTest}, which a concurrent branch also edits).
 *
 * <p>Written as a NEW test class per the branch's collision-avoidance instructions, since
 * {@code feat/payroll-statutory-export-files} concurrently edits {@code PayrollRepositoryIntegrationTest}.
 */
class PayrollCarryForwardSuggestionsIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
    }

    @Test
    void returnsTheCarriedFieldsFromTheLatestPriorProcessedLineAndExcludesEventDrivenAndCommissionFields() {
        long alice = seedEmployee("EMP-CF-001", "อลิสา", "แครี่", true);

        // June: processed period, all carried fields populated, PLUS commission (specialPay6),
        // KPI (specialPay7), bonus (specialPay8) and an event-driven unpaid-leave-deduction, all of
        // which must NOT appear anywhere in the suggestion (the DTO simply has no such fields).
        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), alice, List.of(
            fullLine(alice, "EMP-CF-001", "อลิสา แครี่",
                "111.11", "222.22", "333.33", "444.44", "555.55",
                "9999.00", "8888.00", "7777.00",
                "600.00", "700.00", "800.00")));

        List<PayrollCarryForwardDtos.SuggestedInputRow> rows =
            repository.findCarryForwardSuggestions(LocalDate.of(2026, 7, 1));

        assertThat(rows).hasSize(1);
        PayrollCarryForwardDtos.SuggestedInputRow row = rows.get(0);
        assertThat(row.employeeId()).isEqualTo(alice);
        assertThat(row.specialPay1()).isEqualByComparingTo("111.11");
        assertThat(row.specialPay2()).isEqualByComparingTo("222.22");
        assertThat(row.specialPay3()).isEqualByComparingTo("333.33");
        assertThat(row.specialPay4()).isEqualByComparingTo("444.44");
        assertThat(row.specialPay5()).isEqualByComparingTo("555.55");
        assertThat(row.nonTaxableIncome()).isEqualByComparingTo("600.00");
        assertThat(row.studentLoanDeduction()).isEqualByComparingTo("700.00");
        assertThat(row.legalExecutionDeduction()).isEqualByComparingTo("800.00");
    }

    @Test
    void picksTheLatestPriorPeriodWhenSeveralExistAndIgnoresAnyPeriodOnOrAfterTheRequestedMonth() {
        long bob = seedEmployee("EMP-CF-002", "บ๊อบ", "แครี่", true);

        repository.saveProcessedPeriod(LocalDate.of(2026, 5, 1), bob,
            List.of(fullLine(bob, "EMP-CF-002", "บ๊อบ แครี่",
                "100.00", "0", "0", "0", "0", "0", "0", "0", "50.00", "0", "0")));
        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), bob,
            List.of(fullLine(bob, "EMP-CF-002", "บ๊อบ แครี่",
                "200.00", "0", "0", "0", "0", "0", "0", "0", "75.00", "0", "0")));
        // A period AT the requested month (or later) must never be treated as "prior".
        repository.saveProcessedPeriod(LocalDate.of(2026, 7, 1), bob,
            List.of(fullLine(bob, "EMP-CF-002", "บ๊อบ แครี่",
                "999.00", "0", "0", "0", "0", "0", "0", "0", "999.00", "0", "0")));

        List<PayrollCarryForwardDtos.SuggestedInputRow> rows =
            repository.findCarryForwardSuggestions(LocalDate.of(2026, 7, 1));

        assertThat(rows).hasSize(1);
        // June (the latest STRICTLY prior month), not May and not the same-month July line.
        assertThat(rows.get(0).specialPay1()).isEqualByComparingTo("200.00");
        assertThat(rows.get(0).nonTaxableIncome()).isEqualByComparingTo("75.00");
    }

    @Test
    void anEmployeeWithNoPriorProcessedLineHasNoSuggestion() {
        seedEmployee("EMP-CF-003", "ไม่มี", "ประวัติ", true);

        List<PayrollCarryForwardDtos.SuggestedInputRow> rows =
            repository.findCarryForwardSuggestions(LocalDate.of(2026, 7, 1));

        assertThat(rows).isEmpty();
    }

    @Test
    void aVoidedPriorPeriodIsExcluded() {
        long carol = seedEmployee("EMP-CF-004", "แครอล", "แครี่", true);

        long periodId = repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), carol,
            List.of(fullLine(carol, "EMP-CF-004", "แครอล แครี่",
                "321.00", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0")));
        jdbc.update("UPDATE hr.payroll_period SET status = 'VOID' WHERE period_id = :id",
            Map.of("id", periodId));

        List<PayrollCarryForwardDtos.SuggestedInputRow> rows =
            repository.findCarryForwardSuggestions(LocalDate.of(2026, 7, 1));

        assertThat(rows).isEmpty();
    }

    @Test
    void aTerminatedEmployeesPriorLineProducesNoSuggestion() {
        long dan = seedEmployee("EMP-CF-005", "แดน", "แครี่", false);

        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), dan,
            List.of(fullLine(dan, "EMP-CF-005", "แดน แครี่",
                "444.00", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0")));

        List<PayrollCarryForwardDtos.SuggestedInputRow> rows =
            repository.findCarryForwardSuggestions(LocalDate.of(2026, 7, 1));

        assertThat(rows).isEmpty();
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, boolean active) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, 30000, :active)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "active", active),
            Long.class);
    }

    private PayrollLineDto fullLine(
        long employeeId, String code, String name,
        String sp1, String sp2, String sp3, String sp4, String sp5,
        String sp6, String sp7, String sp8,
        String nonTaxableIncome, String studentLoanDeduction, String legalExecutionDeduction
    ) {
        List<PayrollSpecialPayDto> specialPays = List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1", new BigDecimal(sp1)),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2", new BigDecimal(sp2)),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3", new BigDecimal(sp3)),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4", new BigDecimal(sp4)),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5", new BigDecimal(sp5)),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6", new BigDecimal(sp6)),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7", new BigDecimal(sp7)),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8", new BigDecimal(sp8)));
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            new BigDecimal("30000.00"),    // baseSalary
            new BigDecimal("1000.0000"),   // dailyRate
            new BigDecimal("125.0000"),    // hourlyRate
            specialPays,
            BigDecimal.ZERO,                // specialPayTotal
            BigDecimal.ZERO,                // overtimePay
            new BigDecimal(sp6),            // commissionPay
            new BigDecimal("30000.00"),     // grossEarnings
            new BigDecimal(nonTaxableIncome),
            BigDecimal.ZERO,                // unpaidLeaveDays
            BigDecimal.ZERO,                // unpaidLeaveDeduction
            new BigDecimal("30000.00"),     // grossTaxableIncome
            new BigDecimal("30000.00"),     // ssoWageBase
            new BigDecimal("750.00"),       // socialSecurity
            BigDecimal.ZERO,                // projectedAnnualIncome
            BigDecimal.ZERO,                // taxExpenseDeduction
            BigDecimal.ZERO,                // taxAllowanceTotal
            BigDecimal.ZERO,                // taxableAnnualIncome
            BigDecimal.ZERO,                // annualTax
            BigDecimal.ZERO,                // withholdingTax
            new BigDecimal(studentLoanDeduction),
            new BigDecimal(legalExecutionDeduction),
            BigDecimal.ZERO,                // otherPostTaxDeductions
            BigDecimal.ZERO,                // totalDeductions
            new BigDecimal("29000.00"),     // netPay
            "carry-forward fixture " + code,
            BigDecimal.ZERO,                // directorRemuneration
            BigDecimal.ZERO,                // warningLetterDeduction
            BigDecimal.ZERO,                // customerReturnDeduction
            BigDecimal.ZERO);               // otherPretaxDeduction
    }
}
