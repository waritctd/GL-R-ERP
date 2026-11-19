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
import th.co.glr.hr.common.ApiException;
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
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** REVIEWER-ADDED coverage for V95 (branch feat/payroll-classification-and-hr-declarations), written against a
 * real PostgreSQL database. The implementer's PayrollClassificationAndSsoInclusionIntegrationTest asserts the
 * SALARY lock only against a wrong non-null treatment (EXTRA_KNOWN_FREQUENCY). */
class PayrollClassificationReviewIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(th.co.glr.hr.notification.NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        payrollService = new PayrollService(
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

    /** companion coverage for PayrollPartialClassificationCliffReviewTest. That test proves an ABSENT component
     * still gets a synthesized default after the "partial classification cliff" fix. */
    @Test
    void aComponentExplicitlyLeftUnclassifiedStillBlocksAfterTheOtherComponentsGetSynthesizedDefaults() {
        long employee = seedEmployee("EMP-REV-010", "จี", "รีวิว");

        // HR opens the matrix, looks at SPECIAL_PAY_1, and explicitly leaves it "ยังไม่จัดประเภท" -- the frontend
        // submits this as taxTreatment: null, which the repository stores as a present row with a NULL value (see
        // ComponentTaxTreatmentUpsertRequest's own javadoc).
        repository.upsertComponentTaxTreatment(2026, List.of(
            new ComponentTaxTreatmentUpsertRequest(employee, PayrollComponent.SPECIAL_PAY_1, null)
        ), employee);

        assertThat(repository.findComponentTaxTreatmentsByEmployee(2026).get(employee))
            .as("the row must exist and be present-and-null, not absent")
            .containsKey(PayrollComponent.SPECIAL_PAY_1)
            .containsEntry(PayrollComponent.SPECIAL_PAY_1, null);

        PayrollEmployeeInputRequest input = specialPay1Of(employee, new BigDecimal("1500.00"));

        assertThatThrownBy(() -> payrollService.preview(
                new ProcessPayrollRequest(LocalDate.of(2026, 3, 1), List.of(input)), hr()))
            .as("a component HR explicitly left unclassified must still 409 -- the merge that fills "
                + "in defaults for components HR never touched must not also paper over one HR "
                + "deliberately declined to classify")
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT))
            .hasMessageContaining("SPECIAL_PAY_1")
            .hasMessageContaining("EMP-REV-010");
    }

    private PayrollEmployeeInputRequest specialPay1Of(long employeeId, BigDecimal specialPay1) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1, zero, zero, zero, zero, zero, zero, zero, zero, // specialPay1-9
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
