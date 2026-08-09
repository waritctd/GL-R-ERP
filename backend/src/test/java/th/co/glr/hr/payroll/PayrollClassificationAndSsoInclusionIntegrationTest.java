package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Exercises the V95 payroll withholding classification + HR declaration schema against a real
 * PostgreSQL database (docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md).
 * Schema + model + repository only -- {@link PayrollCalculator} does not consult any of this yet
 * (next task). The Mockito-based unit tests never touch the SQL, so this is the only coverage of:
 *
 * <ul>
 *   <li>SSO-inclusion defaults seeding TRUE everywhere except DIRECTOR_REMUNERATION / NON_TAXABLE_INCOME
 *   <li>Tax-treatment classification round-tripping, with {@code null} preserved as "unclassified"
 *   <li>The SALARY-locked-to-REGULAR_REPROJECT CHECK constraint actually rejecting a bad write
 *   <li>The ninth special-pay slot (originally ค่าเช่าบ้าน at V95; the accountant's-workbook
 *       renumbering, handoff section 9d, later moved ค่าเช่าบ้าน to specialPay2 and made specialPay9
 *       เงินรางวัล/เงินช่วยเหลืออื่นๆ instead -- see PayrollComponent's javadoc for the current mapping.
 *       This test only exercises the SLOT persisting/reading back, not its label) persisting and
 *       reading back
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

    /**
     * P1 fix (Opus review, 2026-07-30): {@code EmployeeService#create} seeds SSO-inclusion defaults
     * only on the API create path. An employee inserted by raw SQL (uat's {@code V900}, {@code
     * db/migration-demo/V21}, any bulk import) never runs that call and used to compute SSO to
     * ฿0.00 with no error at all -- {@code PayrollRepository#findComponentSsoInclusionByEmployee}
     * returns nothing for such an employee, and the pre-fix call site collapsed that to {@code
     * Map.of()}, under which every component reads as excluded. Fixed at the read/resolution layer
     * ({@code PayrollService#ssoInclusionFor}), driven here through the real {@link PayrollService},
     * not the repository alone, because the fix lives at that layer.
     *
     * <p>Two employees, BOTH inserted by raw SQL with ZERO {@code hr.payroll_component_sso_inclusion}
     * rows -- neither {@code seedSsoInclusionDefaults} nor {@code seedSsoIncluded} (the test-only
     * partial-seed helper) is called for either. Same salary, so any inclusion difference between them
     * can only come from the director fee the second one also carries.
     *
     * <p>Finding 2 fix (fourth Opus review, 2026-07-30): both fixtures previously carried {@code
     * seedEmployee}'s shared hardcoded ฿30,000 salary, which alone already saturates the ฿17,500 SSO
     * wage-base ceiling ({@code PayrollCalculator#SSO_MAX_BASE}) -- a ฿20,000 director fee therefore
     * could not move {@code socialSecurity} whether it was included in the wage base or not, so proof
     * 2 below asserted an equality that would hold under EITHER a correct OR a broken default (it was
     * mutation-checked as passing with {@code defaultSsoIncluded} wrongly including {@code
     * DIRECTOR_REMUNERATION} -- the assertion could not fail). Both employees here now use a dedicated
     * ฿15,000 salary instead (below the ceiling, via {@link #seedEmployeeWithSalary} / the new
     * salary-taking overload of {@link #seedEmployeeWithDirectorFee}) -- unrelated to and independent
     * of {@code seedEmployee}'s shared ฿30,000 fixture, which several OTHER tests in this file still
     * depend on unchanged. With a ฿15,000 base: excluding the director fee leaves the wage base at
     * ฿15,000 (SSO = ฿750.00); wrongly including it pushes the wage base to {@code min(15000 + 20000,
     * 17500) = 17500} (SSO = ฿875.00) -- the two now genuinely diverge, giving proof 2 teeth.
     */
    @Test
    void anEmployeeInsertedByRawSqlWithNoSsoInclusionRowsStillGetsTheCompanyWideDefault() {
        BigDecimal salary = new BigDecimal("15000.00");
        long salaryOnly = seedEmployeeWithSalary("EMP-CLS-011", "จูน", "คลาส", salary);
        long salaryPlusDirectorFee = seedEmployeeWithDirectorFee("EMP-CLS-012", "กันต์", "คลาส", salary, new BigDecimal("20000.00"));
        // DIRECTOR_REMUNERATION is the only non-zero, non-SALARY component the second employee carries
        // this run -- classify it so the run is not blocked by the UNRELATED classification gate this
        // test is not exercising. SALARY needs no row (locked to REGULAR_REPROJECT).
        seedRegularTaxTreatment(salaryPlusDirectorFee, 2026, PayrollComponent.DIRECTOR_REMUNERATION);

        PayrollService payrollService = wireRealPayrollService();
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 3, 1), List.of()), hr());

        BigDecimal salaryOnlySso = lineFor(period, salaryOnly).socialSecurity();
        BigDecimal salaryPlusDirectorFeeSso = lineFor(period, salaryPlusDirectorFee).socialSecurity();

        // Proof 1: an employee with ZERO stored SSO-inclusion rows must not silently compute ฿0.00 --
        // the exact pre-fix failure mode (Map.of() -> every component excluded, including SALARY).
        assertThat(salaryOnlySso)
            .withFailMessage("an employee inserted by raw SQL with no SSO-inclusion rows at all must "
                + "still get the company-wide default (TRUE except DIRECTOR_REMUNERATION/"
                + "NON_TAXABLE_INCOME), not silently compute ฿0.00 social security")
            .isEqualByComparingTo("750.00");

        // Proof 2, WRONG-WAY-ROUND: the synthesized default must still EXCLUDE DIRECTOR_REMUNERATION,
        // exactly like PayrollRepository#seedSsoInclusionDefaults would have written. Both employees
        // share the identical ฿15,000 salary (below the ฿17,500 ceiling, unlike the old ฿30,000
        // fixture); adding a ฿20,000 director fee on top must not move the SSO figure AT ALL. A fix
        // that wrongly defaulted every absent component (including DIRECTOR_REMUNERATION) to included
        // would push the wage base to the ฿17,500 ceiling (SSO = ฿875.00) and fail exactly this
        // assertion while still passing proof 1 above.
        assertThat(salaryPlusDirectorFeeSso)
            .withFailMessage("DIRECTOR_REMUNERATION must stay excluded from the SSO wage base even "
                + "under the synthesized default -- a director fee must never inflate social security")
            .isEqualByComparingTo(salaryOnlySso);
    }

    private PayrollLineDto lineFor(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no line for employee " + employeeId));
    }

    private PayrollService wireRealPayrollService() {
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        return new PayrollService(
            repository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    // Finding 2 fix (fourth Opus review, 2026-07-30): salary is now an explicit parameter (was
    // hardcoded to ฿30,000, which saturates the ฿17,500 SSO ceiling on its own -- see the calling
    // test's javadoc). Only used by that one test, so widening the signature is safe.
    private long seedEmployeeWithDirectorFee(
        String code, String firstNameTh, String lastNameTh, BigDecimal salary, BigDecimal directorFee
    ) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, director_remuneration, is_active)
            VALUES (:code, :first, :last, :salary, :directorFee, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary, "directorFee", directorFee),
            Long.class);
    }

    // Finding 2 fix (fourth Opus review, 2026-07-30): a salary-parameterised sibling to the shared
    // seedEmployee() helper above, which hardcodes ฿30,000 and is depended on by several OTHER tests
    // in this file unchanged. Used only by
    // anEmployeeInsertedByRawSqlWithNoSsoInclusionRowsStillGetsTheCompanyWideDefault, to keep both of
    // that test's fixtures below the ฿17,500 SSO ceiling.
    private long seedEmployeeWithSalary(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
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
            // F7 correction (Opus review, 2026-07-30): label text is decorative only here -- this
            // fixture's assertions check key()/amount(), never label() -- but "ค่าเช่าบ้าน" was V95's
            // original slot-9 label before the accountant's-workbook renumbering (handoff section 9d)
            // moved it to specialPay2 and made specialPay9 เงินรางวัล/เงินช่วยเหลืออื่นๆ instead.
            new PayrollSpecialPayDto("specialPay9", "พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)", specialPay9));
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
