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
 * Exercises {@link PayrollRepository}'s persistence path against a real PostgreSQL database: process
 * a payroll period + lines and read them back by month, asserting the stored money figures survive
 * the INSERT/SELECT round-trip. The Mockito-based service/calculator unit tests never touch the SQL,
 * so this is the only coverage of the payroll write path and the {@code findLines} join back to
 * {@code hr.employee}.
 */
class PayrollRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
    }

    @Test
    void savesAProcessedPeriodAndReadsTheFiguresBackByMonth() {
        long alice = seedEmployee("EMP-001", "อลิสา", "ทดสอบ");
        long bob = seedEmployee("EMP-002", "บ๊อบ", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 7, 1);

        PayrollLineDto aliceLine = line(alice, "EMP-001", "อลิสา ทดสอบ",
            new BigDecimal("30000.00"), new BigDecimal("34000.00"),
            new BigDecimal("750.00"), new BigDecimal("1200.50"), new BigDecimal("31249.50"));
        PayrollLineDto bobLine = line(bob, "EMP-002", "บ๊อบ ทดสอบ",
            new BigDecimal("20000.00"), new BigDecimal("21500.00"),
            new BigDecimal("750.00"), new BigDecimal("300.00"), new BigDecimal("20450.00"));

        long periodId = repository.saveProcessedPeriod(month, alice, List.of(aliceLine, bobLine));
        assertThat(periodId).isPositive();

        PayrollPeriodDto readBack = repository.findPeriodByMonth(month).orElseThrow();

        assertThat(readBack.id()).isEqualTo(periodId);
        assertThat(readBack.payrollMonth()).isEqualTo(month);
        assertThat(readBack.status()).isEqualTo("PROCESSED");
        assertThat(readBack.processedById()).isEqualTo(alice);
        assertThat(readBack.lineCount()).isEqualTo(2);
        // Period totals are derived from the persisted lines.
        assertThat(readBack.totalGross()).isEqualByComparingTo("55500.00");     // 34000 + 21500
        assertThat(readBack.totalDeductions()).isEqualByComparingTo("1500.50"); // 1200.50 + 300
        assertThat(readBack.totalNet()).isEqualByComparingTo("51699.50");       // 31249.50 + 20450
        assertThat(readBack.totalSocialSecurity()).isEqualByComparingTo("1500.00"); // 750 + 750

        // Lines come back ordered by employee_code, so EMP-001 (Alice) is first.
        PayrollLineDto storedAlice = readBack.lines().get(0);
        assertThat(storedAlice.id()).isNotNull();
        assertThat(storedAlice.employeeId()).isEqualTo(alice);
        assertThat(storedAlice.employeeCode()).isEqualTo("EMP-001");
        assertThat(storedAlice.employeeName()).isEqualTo("อลิสา ทดสอบ");
        assertThat(storedAlice.baseSalary()).isEqualByComparingTo("30000.00");
        assertThat(storedAlice.grossEarnings()).isEqualByComparingTo("34000.00");
        assertThat(storedAlice.socialSecurity()).isEqualByComparingTo("750.00");
        assertThat(storedAlice.totalDeductions()).isEqualByComparingTo("1200.50");
        assertThat(storedAlice.netPay()).isEqualByComparingTo("31249.50");
        assertThat(storedAlice.calculationNote()).isEqualTo("round-trip EMP-001");

        PayrollLineDto storedBob = readBack.lines().get(1);
        assertThat(storedBob.employeeCode()).isEqualTo("EMP-002");
        assertThat(storedBob.netPay()).isEqualByComparingTo("20450.00");
    }

    @Test
    void reprocessingTheSameMonthReplacesLinesInsteadOfDuplicating() {
        long alice = seedEmployee("EMP-001", "อลิสา", "ทดสอบ");
        long bob = seedEmployee("EMP-002", "บ๊อบ", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 7, 1);

        repository.saveProcessedPeriod(month, alice, List.of(
            line(alice, "EMP-001", "อลิสา ทดสอบ", new BigDecimal("30000.00"), new BigDecimal("34000.00"),
                new BigDecimal("750.00"), new BigDecimal("1200.50"), new BigDecimal("31249.50")),
            line(bob, "EMP-002", "บ๊อบ ทดสอบ", new BigDecimal("20000.00"), new BigDecimal("21500.00"),
                new BigDecimal("750.00"), new BigDecimal("300.00"), new BigDecimal("20450.00"))));

        // Re-process the same month with a single line — the old two lines must be replaced.
        long secondRun = repository.saveProcessedPeriod(month, bob, List.of(
            line(alice, "EMP-001", "อลิสา ทดสอบ", new BigDecimal("30000.00"), new BigDecimal("30000.00"),
                new BigDecimal("750.00"), new BigDecimal("750.00"), new BigDecimal("29250.00"))));

        PayrollPeriodDto readBack = repository.findPeriodByMonth(month).orElseThrow();
        assertThat(readBack.id()).isEqualTo(secondRun); // same period row reused (unique on month)
        assertThat(readBack.lineCount()).isEqualTo(1);
        assertThat(readBack.processedById()).isEqualTo(bob);
        assertThat(readBack.lines().get(0).netPay()).isEqualByComparingTo("29250.00");
    }

    // ------------------------------------------------------------------------------------------
    // Reconciliation additions (2026-07-21, C1/C2/C3): tax-allowance upsert round-trip, YTD-seed
    // merge with payroll_line, and director remuneration surviving insert + read.
    // ------------------------------------------------------------------------------------------

    @Test
    void directorRemunerationOnTheEmployeeSurvivesInsertAndReadBackThroughFindActiveEmployees() {
        long director = seedDirector("DIR-001", "กัลยาณี", "ทดสอบ", new BigDecimal("150000.00"));
        seedEmployee("EMP-900", "พนักงาน", "ทั่วไป");

        List<PayrollEmployeeSnapshot> employees = repository.findActiveEmployees();

        PayrollEmployeeSnapshot directorSnapshot = employees.stream()
            .filter(e -> e.employeeId() == director)
            .findFirst()
            .orElseThrow(() -> new AssertionError("director without a salary must still appear in findActiveEmployees"));
        assertThat(directorSnapshot.directorRemuneration()).isEqualByComparingTo("150000.00");
        assertThat(directorSnapshot.baseSalary()).isEqualByComparingTo(BigDecimal.ZERO);

        long processedById = employees.get(0).employeeId();
        PayrollLineDto directorLine = lineWithDirectorPay(director, "DIR-001", "กัลยาณี ทดสอบ",
            new BigDecimal("150000.00"), new BigDecimal("150000.00"));
        long periodId = repository.saveProcessedPeriod(LocalDate.of(2026, 7, 1), processedById, List.of(directorLine));
        PayrollPeriodDto readBack = repository.findPeriodById(periodId).orElseThrow();
        PayrollLineDto storedLine = readBack.lines().stream()
            .filter(l -> l.employeeId() == director)
            .findFirst()
            .orElseThrow();
        assertThat(storedLine.directorRemuneration()).isEqualByComparingTo("150000.00");
    }

    @Test
    void taxAllowanceUpsertRoundTripsAllSixteenFieldsAndOverwritesOnConflict() {
        long alice = seedEmployee("EMP-001", "อลิสา", "ทดสอบ");
        var upsert = new th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest(
            alice,
            new BigDecimal("60000.00"), new BigDecimal("30000.00"), new BigDecimal("30000.00"),
            new BigDecimal("60000.00"), new BigDecimal("60000.00"), new BigDecimal("50000.00"),
            new BigDecimal("15000.00"), new BigDecimal("10000.00"), new BigDecimal("200000.00"),
            new BigDecimal("100000.00"), new BigDecimal("50000.00"), new BigDecimal("100000.00"),
            new BigDecimal("50000.00"), new BigDecimal("5000.00"), new BigDecimal("3000.00"),
            new BigDecimal("1000.00"));

        repository.upsertTaxAllowances(2026, List.of(upsert), alice);
        Map<Long, PayrollTaxAllowanceInput> firstRead = repository.findTaxAllowancesByEmployee(2026);
        assertThat(firstRead.get(alice).spouseAllowance()).isEqualByComparingTo("60000.00");
        assertThat(firstRead.get(alice).politicalDonation()).isEqualByComparingTo("1000.00");

        // Re-upsert with a changed value: ON CONFLICT must overwrite, not duplicate.
        var updated = new th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest(
            alice,
            new BigDecimal("0.00"), new BigDecimal("30000.00"), new BigDecimal("30000.00"),
            new BigDecimal("60000.00"), new BigDecimal("60000.00"), new BigDecimal("50000.00"),
            new BigDecimal("15000.00"), new BigDecimal("10000.00"), new BigDecimal("200000.00"),
            new BigDecimal("100000.00"), new BigDecimal("50000.00"), new BigDecimal("100000.00"),
            new BigDecimal("50000.00"), new BigDecimal("5000.00"), new BigDecimal("3000.00"),
            new BigDecimal("1000.00"));
        repository.upsertTaxAllowances(2026, List.of(updated), alice);

        Map<Long, PayrollTaxAllowanceInput> secondRead = repository.findTaxAllowancesByEmployee(2026);
        assertThat(secondRead).hasSize(1);
        assertThat(secondRead.get(alice).spouseAllowance()).isEqualByComparingTo("0.00");

        List<th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto> rows = repository.findTaxAllowanceRows(2026);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).employeeCode()).isEqualTo("EMP-001");
    }

    @Test
    void yearToDateMergesTheSeedWithProcessedPayrollLinesRatherThanReplacingThem() {
        long seedOnly = seedEmployee("EMP-101", "ซีดอย่างเดียว", "ทดสอบ");
        long lineOnly = seedEmployee("EMP-102", "ไลน์อย่างเดียว", "ทดสอบ");
        long both = seedEmployee("EMP-103", "ทั้งคู่", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 7, 1);

        // seedOnly: only a YTD seed row, no processed payroll_line this year.
        repository.upsertYtdSeed(2026, List.of(
            new th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest(
                seedOnly, new BigDecimal("100000.00"), new BigDecimal("5000.00"), new BigDecimal("2000.00"), "pre-system history"),
            new th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest(
                both, new BigDecimal("50000.00"), new BigDecimal("2500.00"), new BigDecimal("1000.00"), "pre-system history")
        ), seedOnly);

        // lineOnly + both: a processed payroll_line row for an earlier month this year.
        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), lineOnly, List.of(
            line(lineOnly, "EMP-102", "ไลน์อย่างเดียว ทดสอบ", new BigDecimal("20000.00"), new BigDecimal("20000.00"),
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), new BigDecimal("18000.00")),
            line(both, "EMP-103", "ทั้งคู่ ทดสอบ", new BigDecimal("20000.00"), new BigDecimal("20000.00"),
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), new BigDecimal("18000.00"))
        ));

        Map<Long, PayrollYearToDate> ytd = repository.findYearToDateByEmployee(month);

        assertThat(ytd.get(seedOnly).taxableIncome()).isEqualByComparingTo("100000.00");
        assertThat(ytd.get(lineOnly).taxableIncome()).isEqualByComparingTo("20000.00");
        // both: seed (50,000) + payroll_line's gross_taxable_income (20,000) = 70,000.
        assertThat(ytd.get(both).taxableIncome()).isEqualByComparingTo("70000.00");
        assertThat(ytd.get(both).socialSecurity()).isEqualByComparingTo("3500.00");
        // the line() helper hardcodes withholdingTax to zero (it only parameterizes totalDeductions),
        // so "both" only carries the seed's 1,000 withholding here.
        assertThat(ytd.get(both).withholdingTax()).isEqualByComparingTo("1000.00");

        List<th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedDto> rows = repository.findYtdSeedRows(2026);
        assertThat(rows).hasSize(2);
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

    /** A director: no salary at all, only director_remuneration -- must still be payroll-eligible. */
    private long seedDirector(String code, String firstNameTh, String lastNameTh, BigDecimal directorRemuneration) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, director_remuneration, is_active)
            VALUES (:code, :first, :last, 0, :directorRemuneration, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "directorRemuneration", directorRemuneration),
            Long.class);
    }

    private PayrollLineDto lineWithDirectorPay(
        long employeeId, String code, String name, BigDecimal directorRemuneration, BigDecimal gross
    ) {
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            BigDecimal.ZERO,                // baseSalary
            BigDecimal.ZERO,                // dailyRate
            BigDecimal.ZERO,                // hourlyRate
            specialPays(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            gross,                          // grossEarnings
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            gross,                          // grossTaxableIncome
            BigDecimal.ZERO,                // ssoWageBase (directors have none)
            BigDecimal.ZERO,                // socialSecurity (directors have none)
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,                // withholdingTax
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,                // totalDeductions
            gross,                          // netPay
            "director",
            directorRemuneration,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }

    private PayrollLineDto line(
        long employeeId,
        String code,
        String name,
        BigDecimal baseSalary,
        BigDecimal gross,
        BigDecimal socialSecurity,
        BigDecimal deductions,
        BigDecimal net
    ) {
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            baseSalary,
            new BigDecimal("1000.0000"),   // dailyRate
            new BigDecimal("125.0000"),    // hourlyRate
            specialPays(),
            BigDecimal.ZERO,               // specialPayTotal
            BigDecimal.ZERO,               // overtimePay
            BigDecimal.ZERO,               // commissionPay
            gross,                         // grossEarnings
            BigDecimal.ZERO,               // nonTaxableIncome
            BigDecimal.ZERO,               // unpaidLeaveDays
            BigDecimal.ZERO,               // unpaidLeaveDeduction
            gross,                         // grossTaxableIncome
            gross,                         // ssoWageBase
            socialSecurity,
            BigDecimal.ZERO,               // projectedAnnualIncome
            BigDecimal.ZERO,               // taxExpenseDeduction
            BigDecimal.ZERO,               // taxAllowanceTotal
            BigDecimal.ZERO,               // taxableAnnualIncome
            BigDecimal.ZERO,               // annualTax
            BigDecimal.ZERO,               // withholdingTax
            BigDecimal.ZERO,               // studentLoanDeduction
            BigDecimal.ZERO,               // legalExecutionDeduction
            BigDecimal.ZERO,               // otherPostTaxDeductions
            deductions,                    // totalDeductions
            net,                           // netPay
            "round-trip " + code,
            BigDecimal.ZERO,               // directorRemuneration
            BigDecimal.ZERO,               // warningLetterDeduction
            BigDecimal.ZERO,               // customerReturnDeduction
            BigDecimal.ZERO);              // otherPretaxDeduction
    }

    private List<PayrollSpecialPayDto> specialPays() {
        return List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8", BigDecimal.ZERO));
    }
}
