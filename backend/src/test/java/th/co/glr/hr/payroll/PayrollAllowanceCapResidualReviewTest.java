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

/**
 * REVIEWER-authored, FOURTH pass. Real Postgres, real {@link PayrollService}, real
 * {@link PayrollRepository}.
 *
 * <p>The third pass found that {@code headCountFor} let the declared ค่าลดหย่อนบุตร amount set its
 * own cap, and the fourth pass narrowed it to fire only when the stored count is zero AND the amount
 * came from the run body. That closes the STORED route. These tests pin what is left of the RUN-BODY
 * route, which the narrowing keeps open by design — so that the residual is a recorded decision
 * rather than something a fifth reader has to rediscover.
 *
 * <p><b>Re-verified after the 118 classification/SSO-inclusion rebase (2026-07-29):</b> both tests
 * here failed post-rebase, but NOT because the run-body route closed. {@code
 * PayrollService#headCountFor} is untouched by branch 118 and still derives a count from the run-body
 * amount whenever the stored count is zero, so {@link #aRunBodyChildAllowanceStillSetsItsOwnCap()}
 * still pins the SAME open residual, unchanged, with its original ฿370,500 expectation. The actual
 * causes were incidental to 118: (1) a raw SQL-inserted employee gets no SSO-inclusion rows outside
 * {@code EmployeeService#create}, so the ฿10,500 SSO allowance both tests assume was silently zero
 * until {@code seedSsoIncluded} was added; and (2) {@code PayrollCalculator#calculateClassified} —
 * the only engine {@code PayrollService} calls since task 2 — never wired in {@code
 * clampedAllowanceNote}, so the WARNING {@link #theIdenticalAmountOnAStoredDeclarationClamps()}
 * checks for was missing from every payslip regardless of this residual; fixed at the source
 * ({@code PayrollCalculator#calculateClassified}'s {@code calculationNote} assembly). Do not read a
 * green suite here as evidence the run-body cap was ever closed — it was not.
 */
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

    /**
     * RESIDUAL. No stored ล.ย.01 at all, and a payroll operator types ฿300,000 of ค่าลดหย่อนบุตร into
     * the run body. {@code headCountFor} derives {@code CEILING(300000/30000) = 10} heads from that
     * very figure, so the per-head cap resolves to exactly ฿300,000 and binds on nothing.
     *
     * <p>V93's stated purpose is that บุตร stops being "a free-typed baht amount the engine trusted
     * without limit". On this path it still is one. It is also SILENT: after the derivation
     * {@code declared == cap}, so {@code appendClampWarning} returns early and the payslip's
     * calculation note says nothing. The direction of error is UNDER-withholding.
     */
    @Test
    @DisplayName("RESIDUAL: a run-body บุตร amount still sets its own cap, with no warning")
    void aRunBodyChildAllowanceStillSetsItsOwnCap() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("CAPR-001", "ไม่มี", "ลย01", new BigDecimal("200000.00"));
        // Post-118 rebase: a raw SQL-inserted employee gets no SSO-inclusion rows (that seeding is an
        // EmployeeService#create side effect this test's direct INSERT bypasses), so without this the
        // 10,500 SSO allowance personalPlusSso assumes below is silently zero. Unrelated to the
        // residual this test pins -- the per-head cap math is untouched by this call.
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

    /**
     * The counterpart that makes the residual concrete: the SAME ฿300,000, declared properly on a
     * ล.ย.01 row for one child, is clamped to ฿30,000 and warned about. Two employees with identical
     * declared figures get allowances that differ by ฿270,000 depending only on whether the
     * declaration was recorded or typed into the run.
     */
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
