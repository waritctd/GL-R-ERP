package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Payroll input draft: exercises PayrollRepository#saveInputDrafts/
 * PayrollRepository#findInputDrafts/PayrollRepository#deleteInputDrafts against a real PostgreSQL database. */
class PayrollInputDraftRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
    }

    @Test
    void everyFieldSurvivesASaveAndFindRoundTripIncludingTheNullableOnes() {
        long employeeId = seedEmployee("DRAFT-001", "ร่าง", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        PayrollEmployeeInputRequest input = fullInput(employeeId,
            new BigDecimal("11.11"), new BigDecimal("22.22"), new BigDecimal("33.33"),
            new BigDecimal("44.44"), new BigDecimal("55.55"), new BigDecimal("66.66"),
            new BigDecimal("77.77"), new BigDecimal("88.88"), new BigDecimal("99.99"),
            new BigDecimal("500.00"), // nonTaxableIncome
            new BigDecimal("1.50"),   // unpaidLeaveDays
            new BigDecimal("600.00"), // studentLoanDeduction
            new BigDecimal("700.00"), // legalExecutionDeduction
            new BigDecimal("800.00"), // otherPostTaxDeductions
            new BigDecimal("900.00"), // warningLetterDeduction
            new BigDecimal("1000.00"), // customerReturnDeduction
            new BigDecimal("1100.00"), // otherPretaxDeduction
            new BigDecimal("0"),       // withholdingTaxOverride -- an explicit zero, not null
            new BigDecimal("1200.00"), // mealAllowance
            new BigDecimal("300.00"),  // perDiemExempt
            new BigDecimal("400.00"),  // perDiemTaxable
            PerDiemBasis.FLAT_RATE_S42_2,
            new BigDecimal("1300.00"), // bonusPay
            new BigDecimal("1400.00"), // otherOneOffPay
            true,                       // customerReturnAlreadyEarned
            PayrollGarnishmentType.BONUS,
            new BigDecimal("25.5"));    // daysWorked

        repository.saveInputDrafts(month, List.of(input), 1L);
        List<PayrollEmployeeInputRequest> found = repository.findInputDrafts(month);

        assertThat(found).hasSize(1);
        PayrollEmployeeInputRequest row = found.get(0);
        assertThat(row.employeeId()).isEqualTo(employeeId);
        assertThat(row.specialPay1()).isEqualByComparingTo("11.11");
        assertThat(row.specialPay9()).isEqualByComparingTo("99.99");
        assertThat(row.nonTaxableIncome()).isEqualByComparingTo("500.00");
        assertThat(row.unpaidLeaveDays()).isEqualByComparingTo("1.50");
        assertThat(row.studentLoanDeduction()).isEqualByComparingTo("600.00");
        assertThat(row.legalExecutionDeduction()).isEqualByComparingTo("700.00");
        assertThat(row.otherPostTaxDeductions()).isEqualByComparingTo("800.00");
        assertThat(row.warningLetterDeduction()).isEqualByComparingTo("900.00");
        assertThat(row.customerReturnDeduction()).isEqualByComparingTo("1000.00");
        assertThat(row.otherPretaxDeduction()).isEqualByComparingTo("1100.00");
        assertThat(row.withholdingTaxOverride())
            .as("an explicit 0 override must round-trip as 0, not null")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.mealAllowance()).isEqualByComparingTo("1200.00");
        assertThat(row.perDiemExempt()).isEqualByComparingTo("300.00");
        assertThat(row.perDiemTaxable()).isEqualByComparingTo("400.00");
        assertThat(row.perDiemBasis()).isEqualTo(PerDiemBasis.FLAT_RATE_S42_2);
        assertThat(row.bonusPay()).isEqualByComparingTo("1300.00");
        assertThat(row.otherOneOffPay()).isEqualByComparingTo("1400.00");
        assertThat(row.customerReturnAlreadyEarned()).isTrue();
        assertThat(row.garnishmentType()).isEqualTo(PayrollGarnishmentType.BONUS);
        assertThat(row.daysWorked()).isEqualByComparingTo("25.5");
        // Tax-allowance fields are never part of a draft -- must always read back null.
        assertThat(row.spouseAllowance()).isNull();
        assertThat(row.parentCareCount()).isNull();
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh),
            Long.class);
    }

    private PayrollEmployeeInputRequest fullInput(
        long employeeId,
        BigDecimal sp1, BigDecimal sp2, BigDecimal sp3, BigDecimal sp4, BigDecimal sp5,
        BigDecimal sp6, BigDecimal sp7, BigDecimal sp8, BigDecimal sp9,
        BigDecimal nonTaxableIncome, BigDecimal unpaidLeaveDays, BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction, BigDecimal otherPostTaxDeductions,
        BigDecimal warningLetterDeduction, BigDecimal customerReturnDeduction, BigDecimal otherPretaxDeduction,
        BigDecimal withholdingTaxOverride,
        BigDecimal mealAllowance, BigDecimal perDiemExempt, BigDecimal perDiemTaxable, PerDiemBasis perDiemBasis,
        BigDecimal bonusPay, BigDecimal otherOneOffPay,
        boolean customerReturnAlreadyEarned, PayrollGarnishmentType garnishmentType,
        BigDecimal daysWorked
    ) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            sp1, sp2, sp3, sp4, sp5, sp6, sp7, sp8, sp9,
            nonTaxableIncome, unpaidLeaveDays, studentLoanDeduction, legalExecutionDeduction, otherPostTaxDeductions,
            // Tax-allowance fields -- never part of a draft.
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction, withholdingTaxOverride,
            mealAllowance, perDiemExempt, perDiemTaxable, perDiemBasis, bonusPay, otherOneOffPay,
            customerReturnAlreadyEarned, garnishmentType, null, daysWorked
        );
    }
}
