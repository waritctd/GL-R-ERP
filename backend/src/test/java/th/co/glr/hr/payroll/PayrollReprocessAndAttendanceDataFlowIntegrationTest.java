package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** P9 + P11: two behavioural proofs of PayrollService#process/PayrollService#preview against real Postgres that
 * a Mockito-based unit test cannot reach. */
class PayrollReprocessAndAttendanceDataFlowIntegrationTest extends AbstractPostgresIntegrationTest {
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
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        // Leave -> payroll unpaid-day deduction (2026-07-23): mechanical constructor-arity fix -- PayrollService
        // gained a LeaveRepository dependency for #suggestedInputs, unrelated to what this test exercises.
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
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    // ---- P9: re-processing a month replaces lines, it does not duplicate the period -------------

    /** Processes March 2026 twice through the real service: once with no per-run inputs (gross = base salary =
     * 30,000.00), then again with a 5,000.00 specialPay1 for the same employee (gross = 35,000.00). FOR UPDATE,
     * advisory lock, or serializable isolation. */
    @Test
    void reprocessingTheSameMonthThroughTheServiceReplacesLinesRatherThanDuplicating() {
        long employeeId = seedEmployee("REPROC-001", "รีโปรเซส", "ทดสอบ", new BigDecimal("30000.00"));
        LocalDate month = LocalDate.of(2026, 3, 1);

        PayrollPeriodDto firstRun = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        assertThat(periodRowCountForMonth(month)).isEqualTo(1);
        assertThat(firstRun.lineCount()).isEqualTo(1);
        assertThat(firstRun.lines().get(0).grossEarnings()).isEqualByComparingTo("30000.00");

        PayrollPeriodDto secondRun = payrollService.process(
            new ProcessPayrollRequest(month, List.of(inputWithSpecialPay1(employeeId, new BigDecimal("5000.00")))),
            hr());

        // Same period row reused (unique on payroll_month), not a second row for the same month.
        assertThat(secondRun.id()).isEqualTo(firstRun.id());
        assertThat(periodRowCountForMonth(month)).isEqualTo(1);

        // Exactly one line for the employee, reflecting the SECOND run's figures.
        assertThat(secondRun.lineCount()).isEqualTo(1);
        assertThat(lineRowCountForPeriod(secondRun.id())).isEqualTo(1);
        assertThat(secondRun.lines().get(0).grossEarnings()).isEqualByComparingTo("35000.00");

        // Re-reading straight from the DB (not the service's return value) confirms the same thing.
        PayrollPeriodDto rereadFromDb = payrollRepository.findPeriodByMonth(month).orElseThrow();
        assertThat(rereadFromDb.id()).isEqualTo(firstRun.id());
        assertThat(rereadFromDb.lineCount()).isEqualTo(1);
        assertThat(rereadFromDb.lines().get(0).grossEarnings()).isEqualByComparingTo("35000.00");
    }

    // ---- P11: §76 -- real lateness/early-leave attendance data never moves payroll pay -----------

    /** Verifies real late/early attendance punches leave payroll preview amounts unchanged. */
    @Test
    void realLatenessAndEarlyLeavePunchesNeverAffectThePayrollPreview() {
        long employeeId = seedEmployee("S76-001", "มาสาย", "ทดสอบ", new BigDecimal("30000.00"));
        LocalDate month = LocalDate.of(2026, 1, 1);

        PayrollLineDto beforePunches = previewLineFor(month, employeeId);

        // Scheduled 08:00-17:00; these punches are ~2h late in and ~2h early out, several days running.
        insertLatePunch(employeeId, month.withDayOfMonth(5));
        insertLatePunch(employeeId, month.withDayOfMonth(6));
        insertLatePunch(employeeId, month.withDayOfMonth(7));

        PayrollLineDto afterPunches = previewLineFor(month, employeeId);

        assertThat(afterPunches.grossEarnings()).isEqualByComparingTo(beforePunches.grossEarnings());
        assertThat(afterPunches.grossTaxableIncome()).isEqualByComparingTo(beforePunches.grossTaxableIncome());
        assertThat(afterPunches.socialSecurity()).isEqualByComparingTo(beforePunches.socialSecurity());
        assertThat(afterPunches.withholdingTax()).isEqualByComparingTo(beforePunches.withholdingTax());
        assertThat(afterPunches.totalDeductions()).isEqualByComparingTo(beforePunches.totalDeductions());
        assertThat(afterPunches.netPay()).isEqualByComparingTo(beforePunches.netPay());
        assertThat(afterPunches.unpaidLeaveDeduction()).isEqualByComparingTo(beforePunches.unpaidLeaveDeduction());
    }

    private void insertLatePunch(long employeeId, LocalDate workDate) {
        OffsetDateTime lateIn = workDate.atTime(10, 0).atOffset(ZoneOffset.ofHours(7));
        OffsetDateTime earlyOut = workDate.atTime(15, 0).atOffset(ZoneOffset.ofHours(7));
        insertPunch(employeeId, "S76-001", lateIn, workDate);
        insertPunch(employeeId, "S76-001", earlyOut, workDate);
    }

    private void insertPunch(long employeeId, String badgeCode, OffsetDateTime at, LocalDate workDate) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date, employee_id)
            VALUES ('SHOWROOM', :badge, :at, :workDate, :employeeId)
            """, Map.of("badge", badgeCode, "at", at, "workDate", workDate, "employeeId", employeeId));
    }

    // --- helpers ------------------------------------------------------------

    private PayrollLineDto previewLineFor(LocalDate payrollMonth, long employeeId) {
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(payrollMonth, List.of()), hr());
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private PayrollEmployeeInputRequest inputWithSpecialPay1(long employeeId, BigDecimal specialPay1) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay2-4
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay5-7
            BigDecimal.ZERO, // specialPay8
            BigDecimal.ZERO, // specialPay9
            BigDecimal.ZERO, // nonTaxableIncome
            BigDecimal.ZERO, // unpaidLeaveDays
            BigDecimal.ZERO, // studentLoanDeduction
            BigDecimal.ZERO, // legalExecutionDeduction
            BigDecimal.ZERO, // otherPostTaxDeductions
            BigDecimal.ZERO, // spouseAllowance
            BigDecimal.ZERO, // childAllowance
            BigDecimal.ZERO, // parentCareAllowance
            BigDecimal.ZERO, // disabledCareAllowance
            BigDecimal.ZERO, // maternityAllowance
            BigDecimal.ZERO, // lifeInsuranceAllowance
            BigDecimal.ZERO, // healthInsuranceAllowance
            BigDecimal.ZERO, // parentHealthInsuranceAllowance
            BigDecimal.ZERO, // rmfAllowance
            BigDecimal.ZERO, // ssfAllowance
            BigDecimal.ZERO, // pensionInsuranceAllowance
            BigDecimal.ZERO, // thaiEsgAllowance
            BigDecimal.ZERO, // homeLoanInterestAllowance
            BigDecimal.ZERO, // educationDonation
            BigDecimal.ZERO, // generalDonation
            BigDecimal.ZERO, // politicalDonation
            BigDecimal.ZERO, // warningLetterDeduction
            BigDecimal.ZERO, // customerReturnDeduction
            BigDecimal.ZERO, // otherPretaxDeduction
            null); // withholdingTaxOverride (none)
    }

    private int periodRowCountForMonth(LocalDate payrollMonth) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.payroll_period WHERE payroll_month = :month",
            Map.of("month", payrollMonth), Integer.class);
        return count == null ? 0 : count;
    }

    private int lineRowCountForPeriod(long periodId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.payroll_line WHERE period_id = :periodId",
            Map.of("periodId", periodId), Integer.class);
        return count == null ? 0 : count;
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
        // Task 2: this file is about reprocessing/attendance-data flow, not classification -- classify every
        // non-salary component REGULAR_REPROJECT and seed the real production SSO-inclusion defaults, so whichever
        // special-pay slot a given test fills in never trips the new unclassified-component blocker.
        seedRegularTaxTreatment(employeeId, 2026,
            PayrollComponent.SPECIAL_PAY_1, PayrollComponent.SPECIAL_PAY_2, PayrollComponent.SPECIAL_PAY_3,
            PayrollComponent.SPECIAL_PAY_4, PayrollComponent.SPECIAL_PAY_5, PayrollComponent.SPECIAL_PAY_6,
            PayrollComponent.SPECIAL_PAY_7, PayrollComponent.SPECIAL_PAY_8, PayrollComponent.SPECIAL_PAY_9,
            PayrollComponent.OVERTIME_PAY, PayrollComponent.COMMISSION_PAY, PayrollComponent.BONUS_PAY,
            PayrollComponent.OTHER_ONE_OFF_PAY, PayrollComponent.DIRECTOR_REMUNERATION);
        new PayrollRepository(jdbc).seedSsoInclusionDefaults(employeeId, 2026, employeeId);
        return employeeId;
    }
}
