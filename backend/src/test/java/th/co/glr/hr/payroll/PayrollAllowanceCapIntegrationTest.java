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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
     * A run-body entry must leave the per-head cap alone.
     *
     * <p>WAS a defect pin: headCountFor resolved the amount through firstNonNull to the STORED
     * ฿300,000, derived 10 heads from it, and allowed the lot — the cap computed from the figure it
     * constrains, triggered by any unrelated run-body field, silent, in the direction of
     * UNDER-withholding. Net effect was ฿270,000 of unlawful ค่าลดหย่อน. Now fixed: a count is derived
     * only from a non-null RUN-BODY amount and only when no stored count exists, so this asserts the
     * cap holds and the discarded amount is named on the payslip.
     */
class PayrollAllowanceCapIntegrationTest extends AbstractPostgresIntegrationTest {
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
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties());
    }

    /**
     * A stored ล.ย.01 declaring ONE child and ฿300,000 of ค่าลดหย่อนบุตร. The law allows ฿30,000 for
     * one child (฿60,000 only for a second-or-later child born from พ.ศ. 2561, which a first child
     * cannot be), and {@code PayrollCalculator#childAllowanceCap} computes exactly that.
     *
     * <p>With NO run-body entry the cap binds and ฿30,000 is allowed — {@code mergeAllowances}
     * returns the stored declaration untouched.
     *
     * <p>With a run-body entry that says nothing whatsoever about allowances (only a ฿500 พิเศษ 1,
     * which the payroll UI pre-fills by default), {@code firstNonNull(input.childAllowance(),
     * base.childAllowance())} resolves to the STORED ฿300,000, {@code headCountFor} raises the count
     * from 1 to {@code CEILING(300000/30000) = 10}, and the whole ฿300,000 is allowed. No warning
     * fires either: after the raise {@code declared == cap}, so {@code appendClampWarning} returns
     * early.
     *
     * <p>Net effect: ฿270,000 of unlawful ค่าลดหย่อน, switched on by an unrelated field, silently.
     */
    @Test
    @DisplayName("a run-body entry must not disturb the per-head บุตร cap")
    void aRunBodyEntryMustNotDisturbThePerHeadChildAllowanceCap() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("CAP-001", "เกินเพดาน", "บุตร", new BigDecimal("200000.00"));
        // Post-118 rebase: onlyASpecialPay() below puts ฿500 in SPECIAL_PAY_1, which
        // calculateClassified now REJECTS unless HR has classified it (the gate this branch added).
        // seedSsoIncluded is needed too -- a raw SQL-inserted employee (bypassing EmployeeService) gets
        // no SSO-inclusion rows at all, so without this the 10,500 SSO allowance this test expects is
        // silently zero.
        seedRegularTaxTreatment(employeeId, 2026, PayrollComponent.SPECIAL_PAY_1);
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        jdbc.update("""
            INSERT INTO hr.employee_tax_allowance
                (employee_id, tax_year, child_allowance, child_count, effective_month)
            VALUES (:id, 2026, 300000, 1, 1)
            """, Map.of("id", employeeId));

        PayrollLineDto withoutRunBody = lineFor(payrollService.preview(
            new ProcessPayrollRequest(month, List.of()), hr()), employeeId);
        PayrollLineDto withRunBody = lineFor(payrollService.preview(
            new ProcessPayrollRequest(month, List.of(onlyASpecialPay(employeeId))), hr()), employeeId);

        BigDecimal personalPlusSso = new BigDecimal("70500.00"); // 60,000 personal + 10,500 SSO

        assertThat(withoutRunBody.taxAllowanceTotal())
            .as("no run-body entry: the per-head cap binds, as V93 intends")
            .isEqualByComparingTo(personalPlusSso.add(new BigDecimal("30000.00")));

        // REGRESSION GUARD (converted 2026-07-29). This began as a defect pin written by review: an
        // unrelated run-body entry used to make the ONE-child cap stop binding, because headCountFor
        // fell back to the STORED amount and raised the head count until it covered it. The cap was
        // being computed from the very amount it exists to constrain, and the error ran toward
        // UNDER-withholding. headCountFor now derives ONLY from a non-null run-body amount, and only
        // when there is no stored count at all.
        assertThat(withRunBody.taxAllowanceTotal())
            .as("an unrelated run-body entry must not disturb the per-head cap")
            .isEqualByComparingTo(personalPlusSso.add(new BigDecimal("30000.00")));

        assertThat(withRunBody.taxAllowanceTotal())
            .as("the run body must not change the allowance at all here")
            .isEqualByComparingTo(withoutRunBody.taxAllowanceTotal());

        assertThat(withRunBody.calculationNote())
            .as("270,000 discarded by the cap must be named on the payslip, not swallowed")
            .contains("WARNING")
            .contains("ค่าลดหย่อนบุตร");

        // The run body adds 500 of พิเศษ 1, which is income, so withholding legitimately RISES.
        // What must not happen is the 270,000 of unlawful allowance pushing it DOWN — that was the
        // defect's signature.
        assertThat(withRunBody.withholdingTax())
            .as("an extra 500 of income must not somehow reduce the tax")
            .isGreaterThanOrEqualTo(withoutRunBody.withholdingTax());
    }

    /** The identical hole on ค่าอุปการะคนพิการ (฿60,000 per head). */
    @Test
    @DisplayName("a run-body entry must not disturb the per-head อุปการะคนพิการ cap")
    void aRunBodyEntryMustNotDisturbThePerHeadDisabledCareCap() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("CAP-002", "เกินเพดาน", "คนพิการ", new BigDecimal("200000.00"));
        // See the sibling test above for why both seed calls are needed post-118.
        seedRegularTaxTreatment(employeeId, 2026, PayrollComponent.SPECIAL_PAY_1);
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        jdbc.update("""
            INSERT INTO hr.employee_tax_allowance
                (employee_id, tax_year, disabled_care_allowance, disabled_care_count, effective_month)
            VALUES (:id, 2026, 600000, 1, 1)
            """, Map.of("id", employeeId));

        PayrollLineDto withoutRunBody = lineFor(payrollService.preview(
            new ProcessPayrollRequest(month, List.of()), hr()), employeeId);
        PayrollLineDto withRunBody = lineFor(payrollService.preview(
            new ProcessPayrollRequest(month, List.of(onlyASpecialPay(employeeId))), hr()), employeeId);

        BigDecimal personalPlusSso = new BigDecimal("70500.00");
        assertThat(withoutRunBody.taxAllowanceTotal())
            .isEqualByComparingTo(personalPlusSso.add(new BigDecimal("60000.00")));
        assertThat(withRunBody.taxAllowanceTotal())
            .as("600,000 declared against a count of ONE must clamp to the 60,000 the count allows")
            .isEqualByComparingTo(personalPlusSso.add(new BigDecimal("60000.00")));
    }

    // --- helpers ------------------------------------------------------------

    /** Exactly what the payroll UI submits for an untouched employee on a fresh run: a ฿500 พิเศษ 1. */
    private PayrollEmployeeInputRequest onlyASpecialPay(long employeeId) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            new BigDecimal("500.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            // Every allowance field NULL — the payroll UI has no inputs for them at all.
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null,
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
