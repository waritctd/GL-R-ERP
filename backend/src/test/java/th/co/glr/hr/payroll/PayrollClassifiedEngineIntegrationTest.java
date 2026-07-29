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
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Task 2 (2026-07-29): real-Postgres coverage of {@link PayrollCalculator#calculateClassified},
 * driven exclusively through the real {@link PayrollService#preview}/{@code #process} and {@link
 * PayrollRepository}, per CLAUDE.md's requirement for authz/business-logic changes ("a mocked
 * repository happily 'passes' while the SQL does something else"). See
 * docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md.
 *
 * <p>Covers: the three ป.96 treatments each producing the annualised figure the handoff specifies;
 * the unclassified-non-zero-component blocker firing (and a zero amount NOT firing it); the SSO
 * wage base honouring the per-component inclusion matrix; the parent-count allowance (per-head, not
 * flat); each garnishment payment type's cap; and a 12-month simulation converging to the correct
 * total annual liability.
 */
class PayrollClassifiedEngineIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final int TAX_YEAR = 2026;

    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class));
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.config.AppProperties());
    }

    // ---- Blocker: non-zero + unclassified rejects; zero + unclassified does not ------------------

    @Test
    void unclassifiedNonZeroComponentRejectsTheRunNamingEmployeeAndComponent() {
        long employeeId = seedEmployee("BLK-001", "บล็อก", "ทดสอบ", new BigDecimal("30000.00"));
        // SPECIAL_PAY_1 given a non-zero amount but never classified.
        PayrollEmployeeInputRequest input = input(employeeId, specialPays(new BigDecimal("1000.00")),
            BigDecimal.ZERO, null, null, BigDecimal.ZERO, null, null, false, null, null);

        assertThatThrownBy(() -> payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(input)), hr()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("SPECIAL_PAY_1")
            .hasMessageContaining("BLK-001");
    }

    @Test
    void zeroAmountComponentNeedsNoClassificationAndDoesNotBlockTheRun() {
        long employeeId = seedEmployee("BLK-002", "ไม่บล็อก", "ทดสอบ", new BigDecimal("30000.00"));
        // SPECIAL_PAY_1 present in the request but zero -- never classified, must not block.
        PayrollEmployeeInputRequest input = input(employeeId, specialPays(BigDecimal.ZERO),
            BigDecimal.ZERO, null, null, BigDecimal.ZERO, null, null, false, null, null);

        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(input)), hr());

        assertThat(period.lines()).hasSize(1);
    }

    // ---- REGULAR_REPROJECT (ข้อ 1(4)): annualise x monthsRemaining, recomputed each period --------

    @Test
    void regularReprojectAnnualisesSalaryToThePinnedFigure() {
        long employeeId = seedEmployee("REG-001", "ปกติ", "ทดสอบ", new BigDecimal("50000.00"));
        PayrollLineDto line = previewLineFor(LocalDate.of(2026, 1, 1), employeeId,
            input(employeeId, specialPays(BigDecimal.ZERO), BigDecimal.ZERO, null, null, BigDecimal.ZERO,
                null, null, false, null, null));

        // Hand trace: regularAnnualProjection = 0 + 50,000 x 12 = 600,000; taxExpenseDeduction caps at
        // 100,000; SSO allowance = min(10,500, 0 + 875x12) = 10,500; taxAllowanceTotal = 60,000 +
        // 10,500 = 70,500; taxableAnnualIncome = 600,000 - 100,000 - 70,500 = 429,500; annualTax =
        // 150,000@5% + 129,500@10% = 7,500 + 12,950 = 20,450; withholdingTax = 20,450 / 12 = 1,704.1666
        // -> HALF_UP 1,704.17.
        assertThat(line.projectedAnnualIncome()).isEqualByComparingTo("600000.00");
        assertThat(line.taxAllowanceTotal()).isEqualByComparingTo("70500.00");
        assertThat(line.taxableAnnualIncome()).isEqualByComparingTo("429500.00");
        assertThat(line.annualTax()).isEqualByComparingTo("20450.00");
        assertThat(line.withholdingTax()).isEqualByComparingTo("1704.17");
        assertThat(line.withholdingTaxRegularLimb()).isEqualByComparingTo("1704.17");
    }

    // ---- EXTRA_KNOWN_FREQUENCY (ข้อ 1(5)): x known count, taxed as the difference ------------------

    @Test
    void extraKnownFrequencyIsTaxedAsTheDifferenceBetweenAnnualTaxWithAndWithoutIt() {
        long employeeId = seedEmployee("KNOWN-001", "รู้จำนวน", "ทดสอบ", new BigDecimal("30000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.BONUS_PAY, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employeeId);

        PayrollLineDto line = previewLineFor(LocalDate.of(2026, 1, 1), employeeId,
            input(employeeId, specialPays(BigDecimal.ZERO), BigDecimal.ZERO, null, null, BigDecimal.ZERO,
                new BigDecimal("60000.00"), null, false, null, null));

        // Hand trace: regularAnnualProjection = 30,000 x 12 = 360,000; taxExpenseDeduction caps at
        // 100,000; SSO allowance = min(10,500, 875x12) = 10,500; allowance total = 70,500;
        // taxableRegularOnly = 360,000 - 100,000 - 70,500 = 189,500; annualTaxRegular =
        // (189,500-150,000)@5% = 1,975.00; withholdRegular = 1,975/12 = 164.5833 -> 164.58.
        // taxableWithKnown = 189,500 + 60,000 = 249,500; annualTaxWithKnown =
        // (249,500-150,000)@5% = 4,975.00; withholdKnown = 4,975 - 1,975 = 3,000.00 (withheld IN FULL
        // this period, no division, no reprojection -- it is a one-off, not a recurring wage).
        // Total withholding = 164.58 + 3,000.00 = 3,164.58.
        assertThat(line.withholdingTaxRegularLimb()).isEqualByComparingTo("164.58");
        assertThat(line.withholdingTax()).isEqualByComparingTo("3164.58");
        assertThat(line.taxableAnnualIncome()).isEqualByComparingTo("249500.00");
        assertThat(line.annualTax()).isEqualByComparingTo("4975.00");
    }

    // ---- EXTRA_CUMULATIVE_ACTUAL (ข้อ 1(6)): cumulative actual, less tax already withheld ----------

    @Test
    void extraCumulativeActualAccumulatesAndSubtractsTaxAlreadyWithheldAcrossTwoMonths() {
        long employeeId = seedEmployee("CUM-001", "สะสม", "ทดสอบ", new BigDecimal("30000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.SPECIAL_PAY_2, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL)
        ), employeeId);

        // Month 1 (January): special_pay_2 = 50,000, processed (not just previewed) so it feeds
        // month 2's year-to-date.
        PayrollEmployeeInputRequest januaryInput = input(employeeId,
            specialPays2(new BigDecimal("50000.00")), BigDecimal.ZERO, null, null, BigDecimal.ZERO,
            null, null, false, null, null);
        PayrollPeriodDto januaryPeriod = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(januaryInput)), hr());
        PayrollLineDto januaryLine = onlyLine(januaryPeriod, employeeId);

        // Hand trace (identical regular-limb baseline to the REGULAR_REPROJECT test above, since
        // salary is the same 30,000 -- taxableRegularOnly = 189,500, annualTaxRegular = 1,975.00,
        // withholdRegular = 164.58): taxableWithCumulative = 189,500 + 50,000 = 239,500;
        // annualTaxWithCumulative = (239,500-150,000)@5% = 4,475.00; withholdCumulative = 4,475 -
        // 1,975 - 0 (nothing withheld on this limb before) = 2,500.00. Total = 164.58 + 2,500.00 =
        // 2,664.58.
        assertThat(januaryLine.withholdingTaxCumulativeLimb()).isEqualByComparingTo("2500.00");
        assertThat(januaryLine.withholdingTax()).isEqualByComparingTo("2664.58");

        // Month 2 (February): special_pay_2 = 20,000 more (cumulative actual now 70,000 for the year).
        PayrollEmployeeInputRequest februaryInput = input(employeeId,
            specialPays2(new BigDecimal("20000.00")), BigDecimal.ZERO, null, null, BigDecimal.ZERO,
            null, null, false, null, null);
        PayrollPeriodDto februaryPeriod = payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 2, 1), List.of(februaryInput)), hr());
        PayrollLineDto februaryLine = onlyLine(februaryPeriod, employeeId);

        // Hand trace: regular limb reprojects to the SAME 360,000 (flat salary: YTD 30,000 + this
        // month 30,000 x 11 = 360,000), so taxableRegularOnly/annualTaxRegular are unchanged at
        // 189,500 / 1,975.00; withholdRegular = (1,975 - 164.58 already withheld) / 11 = 164.58.
        // taxableWithCumulative = 189,500 + 70,000 (YTD cumulative actual) = 259,500;
        // annualTaxWithCumulative = (259,500-150,000)@5% = 5,475.00; withholdCumulative = 5,475 -
        // 1,975 - 2,500 (already withheld on this limb in January) = 1,000.00. Total = 164.58 +
        // 1,000.00 = 1,164.58.
        assertThat(februaryLine.withholdingTaxCumulativeLimb()).isEqualByComparingTo("1000.00");
        assertThat(februaryLine.withholdingTax()).isEqualByComparingTo("1164.58");
    }

    // ---- SSO wage base honours the per-component inclusion matrix ---------------------------------

    @Test
    void ssoWageBaseHonoursThePerComponentInclusionFlagAndFlipsWhenItChanges() {
        long employeeId = seedEmployee("SSO-INC-001", "รวมสปส", "ทดสอบ", new BigDecimal("10000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.SPECIAL_PAY_3, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), employeeId);
        payrollRepository.upsertComponentSsoInclusion(TAX_YEAR, List.of(
            new ComponentSsoInclusionUpsertRequest(employeeId, PayrollComponent.SPECIAL_PAY_3, true)
        ), employeeId);

        PayrollEmployeeInputRequest withSpecialPay = input(employeeId,
            specialPays3(new BigDecimal("10000.00")), BigDecimal.ZERO, null, null, BigDecimal.ZERO,
            null, null, false, null, null);
        PayrollLineDto includedLine = previewLineFor(LocalDate.of(2026, 1, 1), employeeId, withSpecialPay);
        // Wage base 10,000 (salary) + 10,000 (special_pay_3, included) = 20,000, clamped at the
        // 17,500 ceiling -> 875.00.
        assertThat(includedLine.ssoWageBase()).isEqualByComparingTo("17500.00");
        assertThat(includedLine.socialSecurity()).isEqualByComparingTo("875.00");

        // Flip the inclusion flag off for SPECIAL_PAY_3 -- the SAME money, now excluded.
        payrollRepository.upsertComponentSsoInclusion(TAX_YEAR, List.of(
            new ComponentSsoInclusionUpsertRequest(employeeId, PayrollComponent.SPECIAL_PAY_3, false)
        ), employeeId);
        PayrollLineDto excludedLine = previewLineFor(LocalDate.of(2026, 1, 1), employeeId, withSpecialPay);
        // Wage base is salary only -- 10,000, no clamp needed -> 500.00.
        assertThat(excludedLine.ssoWageBase()).isEqualByComparingTo("10000.00");
        assertThat(excludedLine.socialSecurity()).isEqualByComparingTo("500.00");
    }

    // ---- Parent allowance: per qualifying head, not a flat cap -------------------------------------

    @Test
    void parentAllowanceIsThirtyThousandPerHeadNotAFlatCapAndWinsOverTheLegacyBahtField() {
        long employeeId = seedEmployee("PARENT-001", "ผู้ปกครอง", "ทดสอบ", new BigDecimal("40000.00"));
        LocalDate month = LocalDate.of(2026, 1, 1);
        PayrollEmployeeInputRequest baseline = input(employeeId, specialPays(BigDecimal.ZERO),
            BigDecimal.ZERO, null, null, BigDecimal.ZERO, null, null, false, null, null);

        PayrollLineDto noParents = previewLineFor(month, employeeId, baseline);

        // Declare 2 qualifying parents AND a legacy baht figure that would give the wrong answer if
        // it were used instead (999,999, capped at 120,000 under the OLD flat-cap rule) -- count must
        // win.
        payrollRepository.upsertTaxAllowances(TAX_YEAR, List.of(
            new EmployeeTaxAllowanceUpsertRequest(
                employeeId,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("999999.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 2)
        ), employeeId);
        PayrollLineDto twoParents = previewLineFor(month, employeeId, baseline);

        // Isolate the parent allowance's own contribution to taxAllowanceTotal -- everything else
        // (personal allowance, SSO allowance) is identical between the two lines.
        BigDecimal parentAllowanceContribution = twoParents.taxAllowanceTotal().subtract(noParents.taxAllowanceTotal());
        assertThat(parentAllowanceContribution)
            .withFailMessage("2 heads x 30,000 = 60,000 -- NOT the legacy baht field (999,999, capped 120,000)")
            .isEqualByComparingTo("60000.00");

        // 4 heads (the statutory maximum -- V95's CHECK enforces 0-4) hits exactly the 120,000 cap.
        payrollRepository.upsertTaxAllowances(TAX_YEAR, List.of(
            new EmployeeTaxAllowanceUpsertRequest(
                employeeId,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("999999.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 4)
        ), employeeId);
        PayrollLineDto fourParents = previewLineFor(month, employeeId, baseline);
        BigDecimal fourHeadContribution = fourParents.taxAllowanceTotal().subtract(noParents.taxAllowanceTotal());
        assertThat(fourHeadContribution).isEqualByComparingTo("120000.00");
    }

    // ---- Garnishment: cap by payment type -----------------------------------------------------------

    @Test
    void salaryGarnishmentCapsAtThirtyPercentAndMustLeaveTwentyThousand() {
        long employeeId = seedEmployee("GARN-SAL-001", "อายัดเงินเดือน", "ทดสอบ", new BigDecimal("100000.00"));
        PayrollEmployeeInputRequest input = input(employeeId, specialPays(BigDecimal.ZERO),
            BigDecimal.ZERO, null, new BigDecimal("50000.00"), BigDecimal.ZERO,
            null, null, false, PayrollGarnishmentType.SALARY, null);

        PayrollLineDto line = previewLineFor(LocalDate.of(2026, 1, 1), employeeId, input);

        BigDecimal capByRate = line.grossTaxableIncome().multiply(new BigDecimal("0.30"));
        BigDecimal netBeforeGarnishment = line.grossTaxableIncome()
            .subtract(line.socialSecurity()).subtract(line.withholdingTax())
            .subtract(line.studentLoanDeduction()).subtract(line.otherPostTaxDeductions())
            .subtract(line.warningLetterDeduction()).subtract(line.customerReturnDeduction());
        BigDecimal capByFloor = netBeforeGarnishment.subtract(new BigDecimal("20000.00")).max(BigDecimal.ZERO);
        BigDecimal expectedCap = capByRate.min(capByFloor).setScale(2);

        assertThat(line.legalExecutionDeduction()).isEqualByComparingTo(expectedCap);
        // The requested 50,000 exceeds the cap -- proves the cap actually bound, not just "whatever
        // was requested came through".
        assertThat(line.legalExecutionDeduction()).isLessThan(new BigDecimal("50000.00"));
    }

    @Test
    void bonusGarnishmentCapsAtFiftyPercentOfTheBonusPaidThisPeriod() {
        long employeeId = seedEmployee("GARN-BON-001", "อายัดโบนัส", "ทดสอบ", new BigDecimal("20000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.BONUS_PAY, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employeeId);
        PayrollEmployeeInputRequest input = input(employeeId, specialPays(BigDecimal.ZERO),
            BigDecimal.ZERO, null, new BigDecimal("15000.00"), BigDecimal.ZERO,
            new BigDecimal("20000.00"), null, false, PayrollGarnishmentType.BONUS, null);

        PayrollLineDto line = previewLineFor(LocalDate.of(2026, 1, 1), employeeId, input);

        // 50% of the 20,000 bonus = 10,000 -- less than the 15,000 requested, so the cap binds.
        assertThat(line.legalExecutionDeduction()).isEqualByComparingTo("10000.00");
        assertThat(line.garnishmentType()).isEqualTo("BONUS");
    }

    @Test
    void overtimeGarnishmentCapsAtThirtyPercentOfOvertimeAndDiligencePaidThisPeriod() {
        long employeeId = seedEmployee("GARN-OT-001", "อายัดโอที", "ทดสอบ", new BigDecimal("20000.00"));
        // OVERTIME_PAY only reaches payroll via approved hr.overtime_request rows -- not HR-typed on
        // the request -- so this test uses SPECIAL_PAY_5 (พิเศษ 5, เบี้ยขยันประจำ under the 2026-07-29
        // workbook realignment -- see PayrollComponent's javadoc / PayrollService#specialPayDtos, one
        // of the two components the handoff names for this garnishment type) instead, classified
        // REGULAR. SPECIAL_PAY_4 (พิเศษ 4, now ค่าตำแหน่ง -- a position allowance, NOT เบี้ยขยัน) is
        // ALSO given a large non-zero amount here and must be excluded from the cap base: this is the
        // regression pin for the off-by-one defect (Opus review, 2026-07-29) where the calculator
        // read SPECIAL_PAY_4 instead of SPECIAL_PAY_5, letting a position allowance inflate the
        // ป.วิ.พ. ม.302 เบี้ยขยัน/ค่าล่วงเวลา garnishment cap.
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.SPECIAL_PAY_4, PayrollTaxTreatment.REGULAR_REPROJECT),
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.SPECIAL_PAY_5, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), employeeId);
        PayrollEmployeeInputRequest input = input(employeeId,
            specialPays4And5(new BigDecimal("5000.00"), new BigDecimal("2000.00")),
            BigDecimal.ZERO, null, new BigDecimal("5000.00"), BigDecimal.ZERO,
            null, null, false, PayrollGarnishmentType.OVERTIME_OR_DILIGENCE, null);

        PayrollLineDto line = previewLineFor(LocalDate.of(2026, 1, 1), employeeId, input);

        // Lawful cap: 30% of OT(0) + special-pay-5/เบี้ยขยัน(2,000) = 600.00 -- less than the 5,000
        // requested, so the cap binds. Before the fix, the calculator read special-pay-4/ค่าตำแหน่ง
        // (5,000) instead and allowed 1,500.00 -- an unlawfully large garnishment against a payment
        // type ป.วิ.พ. ม.302 caps at 30%, and against the WRONG money.
        assertThat(line.legalExecutionDeduction()).isEqualByComparingTo("600.00");
    }

    @Test
    void severanceGarnishmentHasNoPercentageCapButMustLeaveThreeHundredThousand() {
        long employeeId = seedEmployee("GARN-SEV-001", "อายัดออกจากงาน", "ทดสอบ", new BigDecimal("500000.00"));
        PayrollEmployeeInputRequest input = input(employeeId, specialPays(BigDecimal.ZERO),
            BigDecimal.ZERO, null, new BigDecimal("1000000.00"), BigDecimal.ZERO,
            null, null, false, PayrollGarnishmentType.SEVERANCE, null);

        PayrollLineDto line = previewLineFor(LocalDate.of(2026, 1, 1), employeeId, input);

        BigDecimal netBeforeGarnishment = line.grossTaxableIncome()
            .subtract(line.socialSecurity()).subtract(line.withholdingTax())
            .subtract(line.studentLoanDeduction()).subtract(line.otherPostTaxDeductions())
            .subtract(line.warningLetterDeduction()).subtract(line.customerReturnDeduction());
        BigDecimal expectedCap = netBeforeGarnishment.subtract(new BigDecimal("300000.00")).max(BigDecimal.ZERO).setScale(2);

        assertThat(line.legalExecutionDeduction()).isEqualByComparingTo(expectedCap);
        // The huge 1,000,000 request proves the cap (not the request) bound.
        assertThat(line.legalExecutionDeduction()).isLessThan(new BigDecimal("1000000.00"));
        // The statutory floor actually held: at least 300,000 remained available before the deduction.
        assertThat(netBeforeGarnishment).isGreaterThanOrEqualTo(new BigDecimal("300000.00"));
    }

    // ---- 12-month simulation: totals to the correct annual liability -------------------------------

    /**
     * Engineered so the arithmetic is exactly checkable without re-deriving progressive-tax brackets
     * by hand every month: flat salary all year (so the REGULAR limb's annual projection is constant
     * once expense deduction saturates), a single EXTRA_KNOWN_FREQUENCY bonus in one month, and a
     * recurring EXTRA_CUMULATIVE_ACTUAL special-pay component. Salary alone (80,000 x 12 = 960,000)
     * already saturates the ฿100,000 expense-deduction cap from month 1, so neither limb's baseline
     * drifts month to month for reasons unrelated to the limb's own math.
     *
     * <p>Deliberately REGULAR + CUMULATIVE only, no EXTRA_KNOWN_FREQUENCY event in this scenario
     * (that limb is already covered on its own terms by {@link
     * #extraKnownFrequencyIsTaxedAsTheDifferenceBetweenAnnualTaxWithAndWithoutIt}). A known-frequency
     * payment is, by ป.96 ข้อ 1(5)'s own design, taxed and FULLY SETTLED the moment it is paid --
     * unlike the regular and cumulative limbs, nothing about it is carried into later months'
     * projections, so its marginal tax does not appear in ANY later month's {@code annualTax} field
     * (that field reports "what the year's ongoing liability projects to", not "everything ever
     * withheld"). Mixing a known-frequency event into a whole-year telescoping sum would therefore
     * require also tracking every settled known-limb amount separately -- correct, but a second
     * assertion this test does not need in order to prove the regular/cumulative telescoping
     * property, which is the property most exposed to month-to-month drift (SSO cap, expense-
     * deduction saturation) and therefore the one most worth a real 12-period regression.
     *
     * <p>Algebraic guarantee (see the task-2 handoff progress notes for the full derivation): with a
     * constant regular-limb annual tax figure AR every month (guaranteed here because salary is flat
     * and the ฿100,000 expense-deduction cap saturates from month 1 onward), the running total
     * withheld on the cumulative limb through period t always collapses to {@code
     * annualTaxWithCumulative(t) - annualTaxRegular(t)} regardless of intermediate drift, and the
     * regular limb's own reprojection method (unchanged from the pre-task-2 engine) always reconciles
     * exactly to whatever the FINAL month's annual figure is. So the year's total withheld must equal
     * December's own {@code annualTax} -- exactly what this test asserts, without needing to
     * independently recompute 12 months of tax brackets.
     */
    @Test
    void twelveMonthSimulationTotalsToTheCorrectAnnualLiability() {
        long employeeId = seedEmployee("SIM-12M-001", "จำลองสิบสองเดือน", "ทดสอบ", new BigDecimal("80000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.SPECIAL_PAY_5, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL)
        ), employeeId);

        BigDecimal sumOfMonthlyWithholding = BigDecimal.ZERO;
        PayrollLineDto decemberLine = null;
        for (int month = 1; month <= 12; month++) {
            BigDecimal cumulativeThisMonth = new BigDecimal("15000.00"); // recurs every month
            PayrollEmployeeInputRequest input = input(employeeId, specialPays5(cumulativeThisMonth),
                BigDecimal.ZERO, null, null, BigDecimal.ZERO, null, null, false, null, null);

            LocalDate payrollMonth = LocalDate.of(2026, month, 1);
            PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(payrollMonth, List.of(input)), hr());
            PayrollLineDto line = onlyLine(period, employeeId);
            sumOfMonthlyWithholding = sumOfMonthlyWithholding.add(line.withholdingTax());
            if (month == 12) {
                decemberLine = line;
            }
        }

        assertThat(decemberLine).isNotNull();
        assertThat(sumOfMonthlyWithholding)
            .withFailMessage("12 months of per-period withholding must telescope to December's own annualTax")
            .isEqualByComparingTo(decemberLine.annualTax());
        // Sanity: real money moved, this isn't a degenerate all-zero scenario.
        assertThat(sumOfMonthlyWithholding).isGreaterThan(new BigDecimal("10000.00"));
    }

    /**
     * REVIEW ADDITION (2026-07-29). {@link #twelveMonthSimulationTotalsToTheCorrectAnnualLiability}
     * asserts {@code sumOfMonthlyWithholding == decemberLine.annualTax()} -- the engine compared
     * against its OWN final-month figure. That is a telescoping/consistency property, not a
     * correctness property: an engine that mis-stacked every limb identically all year would still
     * satisfy it. It also deliberately omits {@code EXTRA_KNOWN_FREQUENCY} entirely, so the ONE limb
     * whose amount is never carried into a later month's projection has no whole-year regression at
     * all.
     *
     * <p>This test closes both gaps. It runs all twelve periods with a REGULAR salary plus a single
     * mid-year {@code EXTRA_KNOWN_FREQUENCY} bonus, and compares the year's total withholding against
     * a liability computed here BY HAND from the statute, never from any engine output:
     *
     * <pre>
     *   annual income      = 80,000 x 12 + 200,000 bonus            = 1,160,000
     *   expense deduction  = min(50%, 100,000 cap)                  =   100,000
     *   personal allowance                                          =    60,000
     *   SSO (17,500 ceiling x 5% = 875/mo, annual cap 10,500)       =    10,500
     *   net taxable        = 1,160,000 - 100,000 - 60,000 - 10,500  =   989,500
     *
     *   progressive tax on 989,500:
     *     150,001-300,000  @  5%  = 150,000 x 0.05                  =     7,500
     *     300,001-500,000  @ 10%  = 200,000 x 0.10                  =    20,000
     *     500,001-750,000  @ 15%  = 250,000 x 0.15                  =    37,500
     *     750,001-989,500  @ 20%  = 239,500 x 0.20                  =    47,900
     *                                                          total =   112,900
     * </pre>
     *
     * <p>The ป.96 stacking is what makes this come out exact: the month-6 bonus is taxed at its
     * marginal position on top of the FULL-YEAR regular projection (960,000), not on top of
     * year-to-date, so its ฿40,000 marginal charge plus the regular limb's ฿72,900 reconciliation is
     * the whole ฿112,900 and nothing is charged twice.
     *
     * <p>The second assertion pins the known limb's deliberate semantic: December's {@code annualTax}
     * reports ฿72,900 (the ongoing regular liability), NOT the ฿112,900 actually withheld, because a
     * settled ข้อ 1(5) payment is not carried into any later projection (see {@code V96} section 3).
     * That gap is by design; asserting it here means a future change that folds the known limb into
     * the year-end figure has to confront the double-counting question deliberately rather than
     * silently shifting real money.
     */
    @Test
    void twelveMonthSimulationWithAMidYearBonusMatchesAHandComputedAnnualLiability() {
        long employeeId = seedEmployee("SIM-12M-BONUS", "โบนัสกลางปี", "ทดสอบ", new BigDecimal("80000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(employeeId, PayrollComponent.BONUS_PAY, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY)
        ), employeeId);

        BigDecimal sumOfMonthlyWithholding = BigDecimal.ZERO;
        PayrollLineDto decemberLine = null;
        PayrollLineDto bonusMonthLine = null;
        for (int month = 1; month <= 12; month++) {
            BigDecimal bonusThisMonth = month == 6 ? new BigDecimal("200000.00") : BigDecimal.ZERO;
            PayrollEmployeeInputRequest input = input(employeeId, specialPays(BigDecimal.ZERO),
                BigDecimal.ZERO, null, null, BigDecimal.ZERO, bonusThisMonth, null, false, null, null);

            PayrollPeriodDto period = payrollService.process(
                new ProcessPayrollRequest(LocalDate.of(2026, month, 1), List.of(input)), hr());
            PayrollLineDto line = onlyLine(period, employeeId);
            sumOfMonthlyWithholding = sumOfMonthlyWithholding.add(line.withholdingTax());
            if (month == 6) {
                bonusMonthLine = line;
            }
            if (month == 12) {
                decemberLine = line;
            }
        }

        assertThat(sumOfMonthlyWithholding)
            .withFailMessage("12 months of withholding must equal the hand-computed ป.96 liability of 112,900.00, was %s",
                sumOfMonthlyWithholding)
            .isEqualByComparingTo("112900.00");

        // The bonus month carries exactly its marginal charge: tax(989,500) - tax(789,500) = 40,000.
        assertThat(bonusMonthLine).isNotNull();
        assertThat(bonusMonthLine.annualTax()).isEqualByComparingTo("112900.00");

        // Settled-and-not-carried: December projects the regular limb only.
        assertThat(decemberLine).isNotNull();
        assertThat(decemberLine.annualTax()).isEqualByComparingTo("72900.00");
    }

    /**
     * REVIEWER ADDITION (Opus review, 2026-07-30). The two whole-year simulations above each exercise
     * exactly ONE extra limb: {@link #twelveMonthSimulationTotalsToTheCorrectAnnualLiability} runs
     * REGULAR + {@code EXTRA_CUMULATIVE_ACTUAL} with no bonus, and {@link
     * #twelveMonthSimulationWithAMidYearBonusMatchesAHandComputedAnnualLiability} runs REGULAR +
     * {@code EXTRA_KNOWN_FREQUENCY} with no cumulative income. Neither runs BOTH, which is the
     * combination a real GL&amp;R sales employee actually has — the handoff names commission as the
     * archetypal {@code EXTRA_CUMULATIVE_ACTUAL} and bonus as the archetypal {@code
     * EXTRA_KNOWN_FREQUENCY}, and a rep who receives both in one tax year is the normal case, not an
     * exotic one.
     *
     * <p>The liability below is computed BY HAND from the statute, never from engine output:
     *
     * <pre>
     *   annual income = 80,000 x 12 + 200,000 bonus + 15,000 x 12 commission = 1,340,000
     *   expense deduction  = min(50%, 100,000 cap)                           =   100,000
     *   personal allowance                                                   =    60,000
     *   SSO (17,500 ceiling x 5% = 875/mo, annual cap 10,500)                =    10,500
     *   net taxable = 1,340,000 - 170,500                                    = 1,169,500
     *
     *   progressive tax on 1,169,500:
     *       150,001-  300,000 @  5% = 150,000 x 0.05 =  7,500
     *       300,001-  500,000 @ 10% = 200,000 x 0.10 = 20,000
     *       500,001-  750,000 @ 15% = 250,000 x 0.15 = 37,500
     *       750,001-1,000,000 @ 20% = 250,000 x 0.20 = 50,000
     *     1,000,001-1,169,500 @ 25% = 169,500 x 0.25 = 42,375
     *                                          total = 157,375
     * </pre>
     *
     * <p>{@code calculateClassified} stacks the KNOWN limb and the CUMULATIVE limb on the regular
     * limb INDEPENDENTLY — stage 2 taxes {@code netRegular + knownThisPeriod} and stage 3 taxes
     * {@code netWithKnown + cumulativeYtdTotal}, but {@code knownThisPeriod} is only ever THIS
     * period's amount and no {@code knownLimb*} field exists on {@link PayrollYearToDate} to carry a
     * settled bonus forward. So in every month after the bonus, stage 3 layers the cumulative total
     * onto a stack that no longer contains the bonus, and the two limbs consume the same tax brackets
     * twice.
     */
    @Test
    void twelveMonthSimulationWithBothABonusAndCumulativeCommissionMatchesTheHandComputedLiability() {
        long employeeId = seedEmployee("SIM-12M-BOTH", "โบนัสและคอมมิชชั่น", "ทดสอบ", new BigDecimal("80000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(
                employeeId, PayrollComponent.BONUS_PAY, PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY),
            new ComponentTaxTreatmentUpsertRequest(
                employeeId, PayrollComponent.SPECIAL_PAY_7, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL)
        ), employeeId);

        BigDecimal sumOfMonthlyWithholding = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            BigDecimal bonusThisMonth = month == 6 ? new BigDecimal("200000.00") : BigDecimal.ZERO;
            BigDecimal[] specialPays = new BigDecimal[]{
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("15000.00"), BigDecimal.ZERO, BigDecimal.ZERO};
            PayrollEmployeeInputRequest input = input(employeeId, specialPays,
                BigDecimal.ZERO, null, null, BigDecimal.ZERO, bonusThisMonth, null, false, null, null);

            PayrollPeriodDto period = payrollService.process(
                new ProcessPayrollRequest(LocalDate.of(2026, month, 1), List.of(input)), hr());
            sumOfMonthlyWithholding = sumOfMonthlyWithholding.add(onlyLine(period, employeeId).withholdingTax());
        }

        assertThat(sumOfMonthlyWithholding)
            .withFailMessage(
                "12 months of withholding must equal the hand-computed ป.96 liability of 157,375.00, was %s "
                    + "(a shortfall means the KNOWN and CUMULATIVE limbs are stacked on the regular limb "
                    + "independently and consume the same tax brackets twice)",
                sumOfMonthlyWithholding)
            .isEqualByComparingTo("157375.00");
    }

    // --- helpers ------------------------------------------------------------

    private PayrollLineDto previewLineFor(LocalDate payrollMonth, long employeeId, PayrollEmployeeInputRequest input) {
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(payrollMonth, List.of(input)), hr());
        return onlyLine(period, employeeId);
    }

    private PayrollLineDto onlyLine(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        long employeeId = jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
        seedSsoIncluded(employeeId, TAX_YEAR, PayrollComponent.SALARY);
        return employeeId;
    }

    private BigDecimal[] specialPays(BigDecimal specialPay1) {
        return new BigDecimal[]{specialPay1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private BigDecimal[] specialPays2(BigDecimal specialPay2) {
        return new BigDecimal[]{BigDecimal.ZERO, specialPay2, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private BigDecimal[] specialPays3(BigDecimal specialPay3) {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, specialPay3, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private BigDecimal[] specialPays4(BigDecimal specialPay4) {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, specialPay4, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private BigDecimal[] specialPays5(BigDecimal specialPay5) {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, specialPay5,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private BigDecimal[] specialPays4And5(BigDecimal specialPay4, BigDecimal specialPay5) {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, specialPay4, specialPay5,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    /** Full-fidelity request builder: every task-2 field explicit, everything else zeroed/nulled. */
    private PayrollEmployeeInputRequest input(
        long employeeId,
        BigDecimal[] specialPays9,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal garnishmentRequested,
        BigDecimal otherPostTaxDeductions,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        boolean customerReturnAlreadyEarned,
        PayrollGarnishmentType garnishmentType,
        Integer parentCareCount
    ) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPays9[0], specialPays9[1], specialPays9[2], specialPays9[3], specialPays9[4],
            specialPays9[5], specialPays9[6], specialPays9[7], specialPays9[8],
            safe(nonTaxableIncome),
            safe(unpaidLeaveDays),
            BigDecimal.ZERO, // studentLoanDeduction
            safe(garnishmentRequested), // legalExecutionDeduction (garnishment requested amount)
            safe(otherPostTaxDeductions),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // spouse..maternity
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // life..ssf
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // pension..political
            BigDecimal.ZERO, // warningLetterDeduction
            BigDecimal.ZERO, // customerReturnDeduction
            BigDecimal.ZERO, // otherPretaxDeduction
            null, // withholdingTaxOverride
            BigDecimal.ZERO, // mealAllowance (V97)
            BigDecimal.ZERO, // perDiemExempt (V97, มาตรา 42)
            BigDecimal.ZERO, // perDiemTaxable (V97)
            null,            // perDiemBasis — none paid, so no basis to record
            safe(bonusPay),
            safe(otherOneOffPay),
            customerReturnAlreadyEarned,
            garnishmentType,
            parentCareCount
        );
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
