package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises the V95 payroll withholding classification + HR declaration schema against a real
 * PostgreSQL database (docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md).
 * Schema + model + repository only -- {@link PayrollCalculator} does not consult any of this yet
 * (next task). The Mockito-based unit tests never touch the SQL, so this is the only coverage of:
 *
 * <ul>
 *   <li>SSO-inclusion defaults seeding TRUE everywhere except DIRECTOR_REMUNERATION / NON_TAXABLE_INCOME
 *   <li>Tax-treatment classification round-tripping, with {@code null} preserved as "unclassified"
 *   <li>The SALARY-locked-to-REGULAR_REPROJECT CHECK constraint actually rejecting a bad write
 *   <li>The ninth special-pay slot (พิเศษ 9 -- ค่าเช่าบ้าน) persisting and reading back
 *   <li>Declaration verification status + deadline round-tripping
 * </ul>
 */
class PayrollClassificationAndSsoInclusionIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
    }

    // ---- SSO inclusion defaults ------------------------------------------------------------

    @Test
    void seedsSsoInclusionDefaultsTrueEverywhereExceptDirectorRemunerationAndNonTaxableIncome() {
        long alice = seedEmployee("EMP-CLS-001", "อลิสา", "คลาส");

        repository.seedSsoInclusionDefaults(alice, 2026, alice);

        Map<Long, Map<PayrollComponent, Boolean>> byEmployee = repository.findComponentSsoInclusionByEmployee(2026);
        Map<PayrollComponent, Boolean> aliceInclusion = byEmployee.get(alice);

        assertThat(aliceInclusion).hasSize(PayrollComponent.values().length);
        for (PayrollComponent component : PayrollComponent.values()) {
            boolean expected = component != PayrollComponent.DIRECTOR_REMUNERATION
                && component != PayrollComponent.NON_TAXABLE_INCOME;
            assertThat(aliceInclusion.get(component))
                .as("default SSO inclusion for %s", component)
                .isEqualTo(expected);
        }
        assertThat(aliceInclusion.get(PayrollComponent.DIRECTOR_REMUNERATION)).isFalse();
        assertThat(aliceInclusion.get(PayrollComponent.NON_TAXABLE_INCOME)).isFalse();
        assertThat(aliceInclusion.get(PayrollComponent.SALARY)).isTrue();
        assertThat(aliceInclusion.get(PayrollComponent.COMMISSION_PAY)).isTrue();
    }

    @Test
    void seedingSsoInclusionDefaultsNeverOverwritesAnHrEditMadeAfterTheFirstSeed() {
        long bob = seedEmployee("EMP-CLS-002", "บ๊อบ", "คลาส");
        repository.seedSsoInclusionDefaults(bob, 2026, bob);

        // Employee 10080-style outlier (handoff section 5): HR ticks DIRECTOR_REMUNERATION IN for
        // this employee, overriding the default.
        repository.upsertComponentSsoInclusion(2026, List.of(
            new ComponentSsoInclusionUpsertRequest(bob, PayrollComponent.DIRECTOR_REMUNERATION, true)
        ), bob);

        // Re-seeding (e.g. a later run of the same seed step) must NOT clobber the HR edit.
        repository.seedSsoInclusionDefaults(bob, 2026, bob);

        Map<PayrollComponent, Boolean> bobInclusion = repository.findComponentSsoInclusionByEmployee(2026).get(bob);
        assertThat(bobInclusion.get(PayrollComponent.DIRECTOR_REMUNERATION)).isTrue();
    }

    // ---- Tax-treatment classification ------------------------------------------------------

    @Test
    void classificationRoundTripsAndNullIsPreservedAsUnclassifiedRatherThanCoerced() {
        long carol = seedEmployee("EMP-CLS-003", "แครอล", "คลาส");

        // SPECIAL_PAY_9 (ค่าเช่าบ้าน) classified as regular for Carol -- she draws it every month.
        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(carol, PayrollComponent.SPECIAL_PAY_9, PayrollTaxTreatment.REGULAR_REPROJECT),
            // OVERTIME_PAY has a row but is explicitly left unclassified (null), NOT defaulted.
            new ComponentTaxTreatmentUpsertRequest(carol, PayrollComponent.OVERTIME_PAY, null)
        ), carol);

        Map<PayrollComponent, PayrollTaxTreatment> carolTreatments =
            repository.findComponentTaxTreatmentsByEmployee(2026).get(carol);

        assertThat(carolTreatments.get(PayrollComponent.SPECIAL_PAY_9))
            .isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);
        // A row exists (it was upserted) but with a null treatment -- the map must carry the null,
        // not silently omit the key or coerce it to some default.
        assertThat(carolTreatments).containsKey(PayrollComponent.OVERTIME_PAY);
        assertThat(carolTreatments.get(PayrollComponent.OVERTIME_PAY)).isNull();
        // A component never upserted at all has no row -- absent from the map entirely.
        assertThat(carolTreatments).doesNotContainKey(PayrollComponent.SPECIAL_PAY_1);

        // Re-classify SPECIAL_PAY_9 back to unclassified (null) -- ON CONFLICT must overwrite.
        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(carol, PayrollComponent.SPECIAL_PAY_9, null)
        ), carol);
        Map<PayrollComponent, PayrollTaxTreatment> afterReset =
            repository.findComponentTaxTreatmentsByEmployee(2026).get(carol);
        assertThat(afterReset.get(PayrollComponent.SPECIAL_PAY_9)).isNull();
    }

    @Test
    void classificationIsScopedByTaxYearAndByEmployee() {
        long dan = seedEmployee("EMP-CLS-004", "แดน", "คลาส");
        long erin = seedEmployee("EMP-CLS-005", "อีริน", "คลาส");

        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(dan, PayrollComponent.SPECIAL_PAY_6, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL),
            new ComponentTaxTreatmentUpsertRequest(erin, PayrollComponent.SPECIAL_PAY_6, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), dan);
        // A row for tax year 2027, must not leak into a 2026 read.
        repository.upsertComponentTaxTreatment(2027, List.of(
            new ComponentTaxTreatmentUpsertRequest(dan, PayrollComponent.SPECIAL_PAY_6, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), dan);

        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> byEmployee2026 =
            repository.findComponentTaxTreatmentsByEmployee(2026);

        assertThat(byEmployee2026.get(dan).get(PayrollComponent.SPECIAL_PAY_6))
            .isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);
        assertThat(byEmployee2026.get(erin).get(PayrollComponent.SPECIAL_PAY_6))
            .isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
    }

    @Test
    void salaryIsRejectedWithAnyTreatmentOtherThanRegularReproject() {
        long frank = seedEmployee("EMP-CLS-006", "แฟรงค์", "คลาส");

        // Owner, 2026-07-29: เงินเดือน is LOCKED to REGULAR_REPROJECT. The CHECK constraint
        // (chk_pctt_salary_locked_to_regular_reproject, V95) must reject this write outright --
        // not silently accept and store the wrong treatment.
        assertThatThrownBy(() -> repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(frank, PayrollComponent.SALARY, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), frank)).isInstanceOf(DataIntegrityViolationException.class);

        // The correct treatment is accepted.
        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(frank, PayrollComponent.SALARY, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), frank);
        assertThat(repository.findComponentTaxTreatmentsByEmployee(2026).get(frank).get(PayrollComponent.SALARY))
            .isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);
    }

    // ---- พิเศษ 9 -- ค่าเช่าบ้าน ---------------------------------------------------------------

    @Test
    void theNinthSpecialPaySlotPersistsAndReadsBackThroughSaveProcessedPeriodAndFindLines() {
        long grace = seedEmployee("EMP-CLS-007", "เกรซ", "คลาส");
        LocalDate month = LocalDate.of(2026, 8, 1);

        PayrollLineDto line = lineWithNinthSpecialPay(grace, "EMP-CLS-007", "เกรซ คลาส", new BigDecimal("3500.00"));
        long periodId = repository.saveProcessedPeriod(month, grace, List.of(line));

        PayrollPeriodDto readBack = repository.findPeriodById(periodId).orElseThrow();
        PayrollLineDto storedLine = readBack.lines().get(0);

        assertThat(storedLine.specialPays()).hasSize(9);
        PayrollSpecialPayDto specialPay9 = storedLine.specialPays().get(8);
        assertThat(specialPay9.key()).isEqualTo("specialPay9");
        assertThat(specialPay9.amount()).isEqualByComparingTo("3500.00");

        // findLines (the join used by the payroll run view) must read the same value back too.
        List<PayrollLineDto> lines = repository.findLines(periodId);
        assertThat(lines.get(0).specialPays().get(8).amount()).isEqualByComparingTo("3500.00");
    }

    private PayrollLineDto lineWithNinthSpecialPay(long employeeId, String code, String name, BigDecimal specialPay9) {
        List<PayrollSpecialPayDto> specialPays = List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay9", "พิเศษ 9 (ค่าเช่าบ้าน)", specialPay9));
        BigDecimal gross = new BigDecimal("30000.00").add(specialPay9);
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            new BigDecimal("30000.00"),    // baseSalary
            new BigDecimal("1000.0000"),   // dailyRate
            new BigDecimal("125.0000"),    // hourlyRate
            specialPays,
            specialPay9,                    // specialPayTotal (only slot 9 non-zero here)
            BigDecimal.ZERO,                // overtimePay
            BigDecimal.ZERO,                // commissionPay
            gross,                           // grossEarnings
            BigDecimal.ZERO,                // nonTaxableIncome
            BigDecimal.ZERO,                // unpaidLeaveDays
            BigDecimal.ZERO,                // unpaidLeaveDeduction
            gross,                           // grossTaxableIncome
            new BigDecimal("30000.00"),     // ssoWageBase
            new BigDecimal("750.00"),       // socialSecurity
            BigDecimal.ZERO,                // projectedAnnualIncome
            BigDecimal.ZERO,                // taxExpenseDeduction
            BigDecimal.ZERO,                // taxAllowanceTotal
            BigDecimal.ZERO,                // taxableAnnualIncome
            BigDecimal.ZERO,                // annualTax
            BigDecimal.ZERO,                // withholdingTax
            BigDecimal.ZERO,                // studentLoanDeduction
            BigDecimal.ZERO,                // legalExecutionDeduction
            BigDecimal.ZERO,                // otherPostTaxDeductions
            new BigDecimal("750.00"),       // totalDeductions
            gross.subtract(new BigDecimal("750.00")), // netPay
            "specialPay9 round-trip " + code,
            BigDecimal.ZERO,                // directorRemuneration
            BigDecimal.ZERO,                // warningLetterDeduction
            BigDecimal.ZERO,                // customerReturnDeduction
            BigDecimal.ZERO);               // otherPretaxDeduction
    }

    // ---- Declaration verification -----------------------------------------------------------

    @Test
    void verificationStatusRoundTripsAndTheDeadlineIsStored() {
        long henry = seedEmployee("EMP-CLS-008", "เฮนรี่", "คลาส");
        var declaration = new EmployeeTaxAllowanceUpsertRequest(
            henry,
            new BigDecimal("60000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO);
        repository.upsertTaxAllowances(2026, List.of(declaration), henry);

        // Fresh declaration: GRANDFATHERED_UNVERIFIED by default (the column's NOT NULL DEFAULT),
        // nothing verified yet -- production has zero rows in this table, so there is nothing to
        // grandfather today, but the default still governs every row entered from here on.
        EmployeeTaxAllowanceDto freshRow = onlyRow(repository.findTaxAllowanceRows(2026));
        assertThat(freshRow.verificationStatus()).isEqualTo("GRANDFATHERED_UNVERIFIED");
        assertThat(freshRow.verifiedById()).isNull();
        assertThat(freshRow.verifiedAt()).isNull();
        assertThat(freshRow.verificationDeadline()).isNull();

        // The service layer computes and stores a deadline (60 days / two payroll cut-offs after
        // launch, whichever is later -- handoff section 3). This repository method only persists it.
        LocalDate deadline = LocalDate.of(2026, 9, 27);
        repository.setTaxAllowanceVerificationDeadline(henry, 2026, deadline);
        EmployeeTaxAllowanceDto withDeadline = onlyRow(repository.findTaxAllowanceRows(2026));
        assertThat(withDeadline.verificationDeadline()).isEqualTo(deadline);
        // Setting the deadline alone must not touch verification status.
        assertThat(withDeadline.verificationStatus()).isEqualTo("GRANDFATHERED_UNVERIFIED");

        // HR verifies against supporting documents.
        long hr = seedEmployee("EMP-CLS-009", "เอชอาร์", "ผู้ตรวจ");
        repository.markTaxAllowanceVerified(henry, 2026, hr);
        EmployeeTaxAllowanceDto verifiedRow = onlyRow(repository.findTaxAllowanceRows(2026));
        assertThat(verifiedRow.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(verifiedRow.verifiedById()).isEqualTo(hr);
        assertThat(verifiedRow.verifiedAt()).isNotNull();
        // The deadline set earlier survives the verification write.
        assertThat(verifiedRow.verificationDeadline()).isEqualTo(deadline);
    }

    @Test
    void expiringAnUnverifiedDeclarationNeverRetroAltersAlreadyStoredAllowanceAmounts() {
        long ivy = seedEmployee("EMP-CLS-010", "ไอวี่", "คลาส");
        var declaration = new EmployeeTaxAllowanceUpsertRequest(
            ivy,
            new BigDecimal("60000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO);
        repository.upsertTaxAllowances(2026, List.of(declaration), ivy);

        repository.expireTaxAllowanceVerification(ivy, 2026);

        EmployeeTaxAllowanceDto expiredRow = onlyRow(repository.findTaxAllowanceRows(2026));
        assertThat(expiredRow.verificationStatus()).isEqualTo("EXPIRED_UNVERIFIED");
        // The declared amount itself is untouched -- whether it still APPLIES to withholding is a
        // service-layer (PayrollCalculator, next task) decision, never a mutation of what was
        // declared.
        assertThat(expiredRow.allowances().spouseAllowance()).isEqualByComparingTo("60000.00");
    }

    private EmployeeTaxAllowanceDto onlyRow(List<EmployeeTaxAllowanceDto> rows) {
        assertThat(rows).hasSize(1);
        return rows.get(0);
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
}
