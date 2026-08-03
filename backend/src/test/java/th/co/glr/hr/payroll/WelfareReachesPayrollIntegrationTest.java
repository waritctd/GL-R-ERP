package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
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
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * V128: approved สวัสดิการ reaching payroll, end to end, against real Postgres.
 *
 * <p>Modelled on {@code RetroactiveOvertimeReachesPayrollIntegrationTest} — the same shape of claim:
 * money approved in one module has to arrive, correctly, in another. V66 built the welfare model in
 * 2018 and said outright that approved amounts do NOT flow into payroll; nothing consumed
 * {@code hr.special_money_request} until now, and welfare was keyed in by hand.
 *
 * <p>Every assertion reads the DATABASE or the computed line, not just a status code — the failure
 * mode that matters here is a figure that is silently wrong, not a request that visibly errors.
 */
class WelfareReachesPayrollIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal SALARY = new BigDecimal("30000.00");
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 9, 1);

    private PayrollService payrollService;
    private PayrollRepository payrollRepository;
    private long division;
    private long staff;

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
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));

        division = insertDivision("SLS", "ฝ่ายขาย");
        staff = insertEmployee("S001");
    }

    /**
     * The headline case: ฿5,000 of approved funeral aid for this payroll month must appear on the
     * line, be included in gross, and persist to its own column.
     */
    @Test
    void approvedWelfareReachesTheLineAndGross() {
        insertApprovedWelfare(staff, "AID_FUNERAL", new BigDecimal("5000.00"), PAYROLL_MONTH);

        PayrollLineDto line = previewLineFor(staff);

        assertThat(line.welfarePay()).isEqualByComparingTo("5000.00");
        // Gross must actually carry it -- a figure shown on the line but missing from gross is
        // money the employee is told about and never paid.
        assertThat(line.grossEarnings()).isEqualByComparingTo(SALARY.add(new BigDecimal("5000.00")));
    }

    /**
     * Only APPROVED money is paid.
     *
     * <p>This fixture deliberately gives the non-approved rows a {@code payroll_month}, which the
     * services CANNOT currently produce -- the month is assigned only at CEO approval, in the same
     * statement that sets the status. That is exactly why it is written this way: with a NULL month
     * (the realistic shape) the month filter alone excludes those rows, so the test passes whether
     * or not the status filter exists, and mutation-checking proved it did. The status filter is
     * defence-in-depth against a state no module produces TODAY; pinning it here keeps "only
     * approved money is paid" true on its own terms instead of by borrowing another module's
     * invariant.
     */
    @Test
    void onlyApprovedClaimsArePaid() {
        insertApprovedWelfare(staff, "AID_FUNERAL", new BigDecimal("5000.00"), PAYROLL_MONTH);
        insertWelfareWithMonthAndStatus(staff, new BigDecimal("3000.00"), PAYROLL_MONTH, "SUBMITTED");
        insertWelfareWithMonthAndStatus(staff, new BigDecimal("2000.00"), PAYROLL_MONTH, "REJECTED");
        insertWelfareWithMonthAndStatus(staff, new BigDecimal("1000.00"), PAYROLL_MONTH, "CANCELLED");

        assertThat(previewLineFor(staff).welfarePay()).isEqualByComparingTo("5000.00");
    }

    @Test
    void welfareApprovedForAnotherMonthIsNotPaidInThisOne() {
        insertApprovedWelfare(staff, "AID_WEDDING", new BigDecimal("5000.00"), PAYROLL_MONTH.plusMonths(1));

        assertThat(previewLineFor(staff).welfarePay()).isEqualByComparingTo("0.00");
    }

    /**
     * The cutover guard. Welfare used to be keyed into พิเศษ 9 by hand; now it is automatic, so a
     * month carrying both figures would pay it twice — invisibly, because both look like ordinary
     * earnings on the payslip.
     */
    @Test
    void bothAutoWelfareAndAManualSpecialPay9IsRefused() {
        insertApprovedWelfare(staff, "AID_FUNERAL", new BigDecimal("5000.00"), PAYROLL_MONTH);

        assertThatThrownBy(() -> previewWithSpecialPay9(staff, new BigDecimal("5000.00")))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("จ่ายซ้ำ")
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aManualSpecialPay9AloneIsStillAccepted() {
        // พิเศษ 9 is a general เงินรางวัล slot, not a welfare-only one. Refusing it outright would
        // break every legitimate use; the guard must fire only on the genuine collision.
        PayrollLineDto line = previewWithSpecialPay9(staff, new BigDecimal("1500.00"));

        assertThat(line.welfarePay()).isEqualByComparingTo("0.00");
        assertThat(line.grossEarnings()).isEqualByComparingTo(SALARY.add(new BigDecimal("1500.00")));
    }

    @Test
    void welfareIsOutsideTheSsoWageBase() {
        insertApprovedWelfare(staff, "AID_FUNERAL", new BigDecimal("5000.00"), PAYROLL_MONTH);

        // ค่าจ้าง under พ.ร.บ.ประกันสังคม ม.5 is money paid in return for WORK. Welfare is not, so it
        // must not inflate the contribution base -- V128 seeds is_included = FALSE and the
        // calculator's SSO loop honours it.
        PayrollLineDto withWelfare = previewLineFor(staff);
        BigDecimal ssoBaseWithWelfare = withWelfare.ssoWageBase();

        jdbc.update("DELETE FROM hr.special_money_request", Map.of());
        BigDecimal ssoBaseWithout = previewLineFor(staff).ssoWageBase();

        assertThat(ssoBaseWithWelfare).isEqualByComparingTo(ssoBaseWithout);
    }

    @Test
    void processingStampsThePeriodThatPaidTheClaim() {
        long requestId = insertApprovedWelfare(staff, "AID_FUNERAL", new BigDecimal("5000.00"), PAYROLL_MONTH);
        assertThat(includedPeriodOf(requestId)).isNull();

        payrollService.process(new ProcessPayrollRequest(PAYROLL_MONTH, List.of()), hrActor());

        // included_period_id has existed since V66 and was never written, because nothing consumed
        // these rows. Without it there is no way to answer "which payslip paid this claim".
        assertThat(includedPeriodOf(requestId)).isNotNull();
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private PayrollLineDto previewLineFor(long employeeId) {
        return payrollService.preview(new ProcessPayrollRequest(PAYROLL_MONTH, List.of()), hrActor()).lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private PayrollLineDto previewWithSpecialPay9(long employeeId, BigDecimal amount) {
        BigDecimal zero = BigDecimal.ZERO;
        PayrollEmployeeInputRequest input = new PayrollEmployeeInputRequest(
            employeeId,
            zero, zero, zero, zero, zero, zero, zero, zero, amount, // specialPay1-9
            zero, // nonTaxableIncome
            zero, // unpaidLeaveDays
            zero, // studentLoanDeduction
            zero, // legalExecutionDeduction
            zero, // otherPostTaxDeductions
            zero, zero, zero, zero, zero, // spouse..maternity
            zero, zero, zero, zero, zero, // life..ssf
            zero, zero, zero, zero, zero, zero, // pension..political
            zero, // warningLetterDeduction
            zero, // customerReturnDeduction
            zero, // otherPretaxDeduction
            null, // withholdingTaxOverride
            zero, // mealAllowance
            zero, // perDiemExempt
            zero, // perDiemTaxable
            null, // perDiemBasis
            zero, // bonusPay
            zero, // otherOneOffPay
            false, // customerReturnAlreadyEarned
            null, // garnishmentType
            null // parentCareCount
        );
        return payrollService.preview(new ProcessPayrollRequest(PAYROLL_MONTH, List.of(input)), hrActor()).lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private Long includedPeriodOf(long requestId) {
        return jdbc.queryForObject(
            "SELECT included_period_id FROM hr.special_money_request WHERE special_money_request_id = :id",
            Map.of("id", requestId), Long.class);
    }

    private long insertApprovedWelfare(long employeeId, String type, BigDecimal amount, LocalDate payrollMonth) {
        return insertWelfare(employeeId, type, amount, payrollMonth, "APPROVED");
    }

    /**
     * A non-approved row that nonetheless carries a payroll_month and an approved_amount -- the
     * shape a future void/revert path could create, and the only shape that can tell the status
     * filter apart from the month filter. See onlyApprovedClaimsArePaid's javadoc.
     */
    private long insertWelfareWithMonthAndStatus(
            long employeeId, BigDecimal amount, LocalDate payrollMonth, String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("amount", amount);
        params.put("payrollMonth", payrollMonth);
        params.put("status", status);
        return jdbc.queryForObject("""
            INSERT INTO hr.special_money_request
                (employee_id, request_type, event_date, requested_amount, approved_amount,
                 payroll_bucket, policy_version, reason, status, payroll_month)
            VALUES (:employeeId, 'MEDICAL', DATE '2026-09-05', :amount, :amount,
                 'NON_TAXABLE', 1, 'test', :status, :payrollMonth)
            RETURNING special_money_request_id
            """, params, Long.class);
    }

    private long insertWelfare(
            long employeeId, String type, BigDecimal amount, LocalDate payrollMonth, String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("type", type);
        params.put("amount", amount);
        // Only an APPROVED row carries a payroll_month in production (it is assigned at CEO
        // approval); mirror that so a non-approved fixture cannot pass for the wrong reason.
        params.put("payrollMonth", "APPROVED".equals(status) ? payrollMonth : null);
        params.put("approvedAmount", "APPROVED".equals(status) ? amount : null);
        params.put("status", status);
        return jdbc.queryForObject("""
            INSERT INTO hr.special_money_request
                (employee_id, request_type, event_date, requested_amount, approved_amount,
                 payroll_bucket, policy_version, reason, status, payroll_month)
            VALUES (:employeeId, :type, DATE '2026-09-05', :amount, :approvedAmount,
                 'AID', 1, 'test', :status, :payrollMonth)
            RETURNING special_money_request_id
            """, params, Long.class);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", staff, true,
            LocalDate.now(), false, null, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", division);
        params.put("salary", SALARY);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, current_salary, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :salary, DATE '2020-01-01', TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
