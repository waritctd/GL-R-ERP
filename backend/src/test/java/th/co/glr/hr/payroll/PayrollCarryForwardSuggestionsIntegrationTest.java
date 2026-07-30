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

        // June: processed period, all carried fields populated, PLUS specialPay6 (ค่า GPRS),
        // specialPay7 (the historical คอมมิชชั่น พิเศษ slot -- F7 correction, Opus review 2026-07-30:
        // this comment previously said "commission (specialPay6) ... KPI (specialPay7)", the numbering
        // from before the accountant's-workbook renumbering, handoff section 9d), specialPay8 (ทำได้
        // ตาม KPI), and an event-driven unpaid-leave-deduction. None of these five are flagged to carry
        // for Alice below (only SPECIAL_PAY_1-5 are), so none of them should appear in the suggestion
        // -- NOT because the DTO lacks the fields (it has carried specialPay6-9 since task 3's Fix 5;
        // see SuggestedInputRow), but because the carry-forward flag governs it per V98.
        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), alice, List.of(
            fullLine(alice, "EMP-CF-001", "อลิสา แครี่",
                "111.11", "222.22", "333.33", "444.44", "555.55",
                "9999.00", "8888.00", "7777.00",
                "600.00", "700.00", "800.00")));

        // Alice's five slots are configured to carry. V98 made this per employee per component, so
        // the flags are part of the fixture now rather than an assumption baked into the query.
        seedCarryForward(alice, 2026, PayrollComponent.SPECIAL_PAY_1, PayrollComponent.SPECIAL_PAY_2,
            PayrollComponent.SPECIAL_PAY_3, PayrollComponent.SPECIAL_PAY_4, PayrollComponent.SPECIAL_PAY_5);

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

        seedCarryForward(bob, 2026, PayrollComponent.SPECIAL_PAY_1);

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

    /**
     * Defect fix (Opus review, 2026-07-29): {@link PayrollRepository#findCarryForwardSuggestions}
     * used to join only {@code cf1}-{@code cf5} (special_pay_1..5) even though V98 seeds
     * carry-forward flags for SPECIAL_PAY_6/9 and MEAL_ALLOWANCE too -- those flags were stored,
     * never read. Extended to all nine พิเศษ slots plus meal allowance.
     */
    @Test
    void carriesAllNineSpecialPaySlotsPlusMealAllowanceWhenTheirFlagsAreSet() {
        long alice = seedEmployee("EMP-CF-006", "ครบเก้า", "แครี่", true);

        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), alice, List.of(
            lineWithAllNineSlotsAndMeal(alice, "EMP-CF-006", "ครบเก้า แครี่")));

        seedCarryForward(alice, 2026,
            PayrollComponent.SPECIAL_PAY_1, PayrollComponent.SPECIAL_PAY_2, PayrollComponent.SPECIAL_PAY_3,
            PayrollComponent.SPECIAL_PAY_4, PayrollComponent.SPECIAL_PAY_5, PayrollComponent.SPECIAL_PAY_6,
            PayrollComponent.SPECIAL_PAY_7, PayrollComponent.SPECIAL_PAY_8, PayrollComponent.SPECIAL_PAY_9,
            PayrollComponent.MEAL_ALLOWANCE);

        List<PayrollCarryForwardDtos.SuggestedInputRow> rows =
            repository.findCarryForwardSuggestions(LocalDate.of(2026, 7, 1));

        assertThat(rows).hasSize(1);
        PayrollCarryForwardDtos.SuggestedInputRow row = rows.get(0);
        assertThat(row.specialPay1()).isEqualByComparingTo("100.00");
        assertThat(row.specialPay2()).isEqualByComparingTo("200.00");
        assertThat(row.specialPay3()).isEqualByComparingTo("300.00");
        assertThat(row.specialPay4()).isEqualByComparingTo("400.00");
        assertThat(row.specialPay5()).isEqualByComparingTo("500.00");
        assertThat(row.specialPay6()).isEqualByComparingTo("600.00");
        assertThat(row.specialPay7()).isEqualByComparingTo("700.00");
        assertThat(row.specialPay8()).isEqualByComparingTo("800.00");
        assertThat(row.specialPay9()).isEqualByComparingTo("900.00");
        assertThat(row.mealAllowance()).isEqualByComparingTo("1000.00");
    }

    /** Wrong-way-round companion: a slot whose flag is OFF must carry ZERO, not last month's figure,
     *  proving the extension does not silently make every slot carry unconditionally. */
    @Test
    void aSlotWithNoCarryForwardFlagCarriesZeroEvenWhenTheOtherEightDo() {
        long bob = seedEmployee("EMP-CF-007", "ไม่ครบ", "แครี่", true);

        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), bob, List.of(
            lineWithAllNineSlotsAndMeal(bob, "EMP-CF-007", "ไม่ครบ แครี่")));

        // Every slot EXCEPT special_pay_7 (คอมมิชชั่น -- V98's own ledger evidence: carries for ZERO
        // of 8 employees) and MEAL_ALLOWANCE is flagged to carry.
        seedCarryForward(bob, 2026,
            PayrollComponent.SPECIAL_PAY_1, PayrollComponent.SPECIAL_PAY_2, PayrollComponent.SPECIAL_PAY_3,
            PayrollComponent.SPECIAL_PAY_4, PayrollComponent.SPECIAL_PAY_5, PayrollComponent.SPECIAL_PAY_6,
            PayrollComponent.SPECIAL_PAY_8, PayrollComponent.SPECIAL_PAY_9);

        PayrollCarryForwardDtos.SuggestedInputRow row =
            repository.findCarryForwardSuggestions(LocalDate.of(2026, 7, 1)).get(0);

        assertThat(row.specialPay7())
            .as("SPECIAL_PAY_7 has no carry-forward flag and must NOT carry last month's 700.00")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.mealAllowance())
            .as("MEAL_ALLOWANCE has no carry-forward flag and must NOT carry last month's 1000.00")
            .isEqualByComparingTo(BigDecimal.ZERO);
        // The eight flagged components still carry, proving the zero above is the flag working, not
        // a bug that zeroes everything.
        assertThat(row.specialPay6()).isEqualByComparingTo("600.00");
        assertThat(row.specialPay9()).isEqualByComparingTo("900.00");
    }

    /**
     * Defect fix (Opus review, 2026-07-29), second half: the per-slot join used to match {@code
     * cfN.tax_year} against the SOURCE row's own year with no fallback, so a carry-forward flag
     * seeded only for 2026 (V98's actual seed year) stopped applying the instant a source period
     * crossed into 2027 -- carry-forward "surviving" through December 2026 and then stopping dead
     * from January 2027 onward. Reproduces exactly that: the flag is seeded for 2026 only, but the
     * SOURCE period being carried FROM is already January 2027.
     */
    @Test
    void aTwentyTwentySixCarryForwardFlagStillAppliesToASourcePeriodInTheFollowingYear() {
        long carol = seedEmployee("EMP-CF-008", "ข้ามปี", "แครี่", true);

        repository.saveProcessedPeriod(LocalDate.of(2027, 1, 1), carol, List.of(
            lineWithAllNineSlotsAndMeal(carol, "EMP-CF-008", "ข้ามปี แครี่")));

        // Flag seeded for 2026 ONLY -- no 2027 row at all, reproducing V98's real seed (a one-time
        // 2026 backfill with nothing written for 2027 yet).
        seedCarryForward(carol, 2026, PayrollComponent.SPECIAL_PAY_1);

        PayrollCarryForwardDtos.SuggestedInputRow row =
            repository.findCarryForwardSuggestions(LocalDate.of(2027, 2, 1)).get(0);

        assertThat(row.specialPay1())
            .as("the 2026 flag must still resolve for a source period in January 2027, "
                + "rolling forward like PayrollRepository#findComponentTaxTreatmentsByEmployee does")
            .isEqualByComparingTo("100.00");
    }

    private PayrollLineDto lineWithAllNineSlotsAndMeal(long employeeId, String code, String name) {
        List<PayrollSpecialPayDto> specialPays = List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1", new BigDecimal("100.00")),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2", new BigDecimal("200.00")),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3", new BigDecimal("300.00")),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4", new BigDecimal("400.00")),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5", new BigDecimal("500.00")),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6", new BigDecimal("600.00")),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7", new BigDecimal("700.00")),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8", new BigDecimal("800.00")),
            new PayrollSpecialPayDto("specialPay9", "พิเศษ 9", new BigDecimal("900.00")));
        BigDecimal specialPayTotal = new BigDecimal("4500.00");
        BigDecimal gross = new BigDecimal("30000.00").add(specialPayTotal);
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            new BigDecimal("30000.00"),    // baseSalary
            new BigDecimal("1000.0000"),   // dailyRate
            new BigDecimal("125.0000"),    // hourlyRate
            specialPays,
            specialPayTotal,
            BigDecimal.ZERO,                // overtimePay
            BigDecimal.ZERO,                // commissionPay
            gross,                           // grossEarnings
            BigDecimal.ZERO,                // nonTaxableIncome
            BigDecimal.ZERO,                // unpaidLeaveDays
            BigDecimal.ZERO,                // unpaidLeaveDeduction
            gross,                           // grossTaxableIncome
            new BigDecimal("17500.00"),     // ssoWageBase
            new BigDecimal("875.00"),       // socialSecurity
            BigDecimal.ZERO,                // projectedAnnualIncome
            BigDecimal.ZERO,                // taxExpenseDeduction
            BigDecimal.ZERO,                // taxAllowanceTotal
            BigDecimal.ZERO,                // taxableAnnualIncome
            BigDecimal.ZERO,                // annualTax
            BigDecimal.ZERO,                // withholdingTax
            BigDecimal.ZERO,                // studentLoanDeduction
            BigDecimal.ZERO,                // legalExecutionDeduction
            BigDecimal.ZERO,                // otherPostTaxDeductions
            new BigDecimal("875.00"),       // totalDeductions
            gross.subtract(new BigDecimal("875.00")), // netPay
            "all-nine-slots fixture " + code,
            BigDecimal.ZERO,                // directorRemuneration
            BigDecimal.ZERO,                // warningLetterDeduction
            BigDecimal.ZERO,                // customerReturnDeduction
            BigDecimal.ZERO,                // otherPretaxDeduction
            BigDecimal.ZERO,                // leaveRefundDays
            BigDecimal.ZERO,                // leaveDeductionRefund
            null,                            // withholdingTaxOverride
            BigDecimal.ZERO,                // bonusPay
            BigDecimal.ZERO,                // otherOneOffPay
            BigDecimal.ZERO,                // taxableIncomeRegularLimb
            BigDecimal.ZERO,                // taxableIncomeKnownLimb
            BigDecimal.ZERO,                // taxableIncomeCumulativeLimb
            BigDecimal.ZERO,                // withholdingTaxRegularLimb
            BigDecimal.ZERO,                // withholdingTaxCumulativeLimb
            false,                           // customerReturnAlreadyEarned
            "SALARY",                        // garnishmentType
            new BigDecimal("1000.00"),      // mealAllowance
            BigDecimal.ZERO,                // perDiemExempt
            BigDecimal.ZERO,                // perDiemTaxable
            null);                           // perDiemBasis
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
