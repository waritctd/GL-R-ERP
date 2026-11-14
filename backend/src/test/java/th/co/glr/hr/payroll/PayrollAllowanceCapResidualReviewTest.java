package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/** REVIEWER-authored, FOURTH pass. The third pass found that headCountFor let the declared ค่าลดหย่อนบุตร
 * amount set its own cap, and the fourth pass narrowed it to fire only when the stored count is zero AND the
 * amount came from the run body. */
class PayrollAllowanceCapResidualReviewTest extends AbstractPostgresIntegrationTest {
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

    /** Documents the residual case where a request-body child allowance derives its own head-count cap without a
     * warning. */
    @Test
    @DisplayName("RESIDUAL: a run-body บุตร amount still sets its own cap, with no warning")
    void aRunBodyChildAllowanceStillSetsItsOwnCap() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("CAPR-001", "ไม่มี", "ลย01", new BigDecimal("200000.00"));
        // Post-118 rebase: a raw SQL-inserted employee gets no SSO-inclusion rows (that seeding is an
        // EmployeeService#create side effect this test's direct INSERT bypasses), so without this the 10,500 SSO
        // allowance personalPlusSso assumes below is silently zero.
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        // No hr.employee_tax_allowance row whatsoever.

        PayrollLineDto line = lineFor(payrollService.preview(
            new ProcessPayrollRequest(month, List.of(withChildAllowance(employeeId, "300000.00"))),
            hr()), employeeId);

        BigDecimal personalPlusSso = new BigDecimal("70500.00"); // 60,000 personal + 10,500 SSO

        assertThat(line.taxAllowanceTotal())
            .withFailMessage(
                "RESIDUAL PINNED: the whole run-body ฿300,000 was allowed (total %s). The per-head "
                + "cap was computed from the amount it exists to constrain. If this now reads "
                + "%s the run-body route has been closed too and this pin should be replaced.",
                line.taxAllowanceTotal(), personalPlusSso.add(new BigDecimal("30000.00")))
            .isEqualByComparingTo(personalPlusSso.add(new BigDecimal("300000.00")));

        assertThat(line.calculationNote())
            .as("and nothing on the payslip says an uncounted ฿300,000 was accepted")
            .doesNotContain("WARNING");
    }

    /** The counterpart that makes the residual concrete: the SAME ฿300,000, declared properly on a ล.ย.01 row for
     * one child, is clamped to ฿30,000 and warned about. */
    @Test
    @DisplayName("RESIDUAL contrast: the identical amount on a stored ล.ย.01 clamps to 30,000")
    void theIdenticalAmountOnAStoredDeclarationClamps() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("CAPR-002", "มี", "ลย01", new BigDecimal("200000.00"));
        // See the sibling test above for why this is needed post-118.
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        jdbc.update("""
            INSERT INTO hr.employee_tax_allowance
                (employee_id, tax_year, child_allowance, child_count, effective_month)
            VALUES (:id, 2026, 300000, 1, 1)
            """, Map.of("id", employeeId));

        PayrollLineDto line = lineFor(payrollService.preview(
            new ProcessPayrollRequest(month, List.of()), hr()), employeeId);

        assertThat(line.taxAllowanceTotal())
            .isEqualByComparingTo(new BigDecimal("70500.00").add(new BigDecimal("30000.00")));
        assertThat(line.calculationNote()).contains("WARNING").contains("ค่าลดหย่อนบุตร");
    }

    // --- helpers ------------------------------------------------------------

    private PayrollEmployeeInputRequest withChildAllowance(long employeeId, String amount) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, new BigDecimal(amount), null, null, null, null, null, null, null, null, null,
            null, null, null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null);
    }

    private PayrollLineDto lineFor(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }
}
