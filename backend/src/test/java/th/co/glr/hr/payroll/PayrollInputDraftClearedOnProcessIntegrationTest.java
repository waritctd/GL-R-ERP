package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Payroll input draft (2026-07-30): a stale draft must never resurrect over real submitted values
 * once a month is actually processed -- {@link PayrollService#process} clears
 * {@code hr.payroll_input_draft} for that month in the same transaction it saves the processed
 * {@code payroll_line} rows (see {@link PayrollRepository#deleteInputDrafts}). Driven through the
 * real {@link PayrollService} + real Postgres, same wiring as {@code PayrollDailyRateIntegrationTest}
 * -- a mocked repository would "pass" this regardless of whether the delete SQL, and its placement
 * inside the transaction, actually do anything.
 */
class PayrollInputDraftClearedOnProcessIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = mock(CommissionService.class);
        when(commissionService.payrollCommissionTotalsByEmployee(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Map.of());
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

    @Test
    void processingAMonthDeletesTheDraftSavedForThatMonth() {
        long employeeId = seedEmployee("DRAFT-CLR-001", "ล้างร่าง", "เมื่อประมวลผล");
        LocalDate month = LocalDate.of(2026, 8, 1);

        payrollRepository.saveInputDrafts(month, List.of(specialPay1Draft(employeeId, "1234.00")), 1L);
        assertThat(payrollRepository.findInputDrafts(month)).hasSize(1);

        payrollService.process(new ProcessPayrollRequest(month, List.of(specialPay1Input(employeeId, "1234.00"))), hr());

        assertThat(payrollRepository.findInputDrafts(month))
            .as("the draft for the now-processed month must be gone")
            .isEmpty();
    }

    @Test
    void processingOneMonthDoesNotTouchAnotherMonthsDraft() {
        long employeeId = seedEmployee("DRAFT-CLR-002", "เดือนอื่น", "ไม่ถูกลบ");
        LocalDate august = LocalDate.of(2026, 8, 1);
        LocalDate september = LocalDate.of(2026, 9, 1);

        payrollRepository.saveInputDrafts(august, List.of(specialPay1Draft(employeeId, "111.00")), 1L);
        payrollRepository.saveInputDrafts(september, List.of(specialPay1Draft(employeeId, "222.00")), 1L);

        payrollService.process(new ProcessPayrollRequest(august, List.of(specialPay1Input(employeeId, "111.00"))), hr());

        assertThat(payrollRepository.findInputDrafts(august)).isEmpty();
        assertThat(payrollRepository.findInputDrafts(september))
            .as("september's draft is a different month and must survive")
            .hasSize(1);
    }

    private PayrollEmployeeInputRequest specialPay1Draft(long employeeId, String specialPay1) {
        return specialPay1Input(employeeId, specialPay1);
    }

    private PayrollEmployeeInputRequest specialPay1Input(long employeeId, String specialPay1) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            new BigDecimal(specialPay1), zero, zero, zero, zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            zero, zero, zero, null,
            zero, zero, zero, null, zero, zero,
            false, null, null, null
        );
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
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
