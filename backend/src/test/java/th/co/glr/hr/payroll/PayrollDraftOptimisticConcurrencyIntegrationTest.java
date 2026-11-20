package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollRepository.DraftRowVersion;
import th.co.glr.hr.payroll.obligation.DeductionObligationRepository;
import th.co.glr.hr.payroll.obligation.DeductionObligationService;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Payroll draft optimistic concurrency: proves, against real Postgres through the real PayrollService AND
 * PayrollRepository, that the If-Match check on PUT /api/payroll/input-draft is real -- not decorative -- and
 * that PayrollRepository#lockInputDraftMonth's advisory lock is what makes it real. */
class PayrollDraftOptimisticConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {
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
            new AppProperties(),
            new DeductionObligationService(
                new DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new PayrollDeductionShortfallRepository(jdbc)));
    }

    // ---- Case 1: stale token refused, first writer's values survive ------------------------

    @Test
    void secondSaveWithAStaleTokenIsRefused_andTheFirstWritersValuesSurvive() {
        long employeeId = seedEmployee("OCC-001", "แรก", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        payrollService.saveInputDraft(request(month, employeeId, "111.00"), hr(), PayrollDraftETag.EMPTY);

        // Second caller never re-read after the first save -- still holds the ORIGINAL (now stale) empty-set token,
        // exactly like a second HR user who opened the page before the first save.
        assertThatThrownBy(() ->
            payrollService.saveInputDraft(request(month, employeeId, "222.00"), hr(), PayrollDraftETag.EMPTY))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Assert the STORED ROW CONTENT, not just that an exception was thrown -- the refused write must not have
        // landed even partially.
        List<PayrollEmployeeInputRequest> drafts = payrollRepository.findInputDrafts(month);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).specialPay1())
            .as("the first writer's value must survive a refused second write")
            .isEqualByComparingTo("111.00");
    }

    // ---- Case 4: genuine concurrency -- the reason a real DB is required --------------------

    /** Two HR sessions that both loaded the SAME empty draft set (both hold PayrollDraftETag#EMPTY) race to be the
     * first to save. */
    @RepeatedTest(20)
    void exactlyOneOfTwoConcurrentSavesWinsAndTheLoserIs409ed() throws Exception {
        long employeeId = seedEmployee("OCC-004", "แข่งขัน", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 8, 1);

        var txManager = new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new TransactionTemplate(txManager);

        Callable<BigDecimal> byFirstUser = () -> txTemplate.execute(status -> {
            payrollService.saveInputDraft(request(month, employeeId, "500.00"), hr(), PayrollDraftETag.EMPTY);
            return new BigDecimal("500.00");
        });
        Callable<BigDecimal> bySecondUser = () -> txTemplate.execute(status -> {
            payrollService.saveInputDraft(request(month, employeeId, "700.00"), hr(), PayrollDraftETag.EMPTY);
            return new BigDecimal("700.00");
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<BigDecimal> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<BigDecimal> f1 = executor.submit(byFirstUser);
            Future<BigDecimal> f2 = executor.submit(bySecondUser);
            for (Future<BigDecimal> f : List.of(f1, f2)) {
                try {
                    successes.add(f.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).as("exactly one racing save must win").hasSize(1);
        assertThat(failures).as("exactly one racing save must be refused").hasSize(1);
        assertThat(failures.get(0)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // The DB must hold EXACTLY the winner's value -- not the loser's, not a merge of both.
        List<PayrollEmployeeInputRequest> drafts = payrollRepository.findInputDrafts(month);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).specialPay1()).isEqualByComparingTo(successes.get(0));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private int versionFor(LocalDate month, long employeeId) {
        return versionFor(payrollRepository.findInputDraftVersions(month), employeeId);
    }

    private int versionFor(List<DraftRowVersion> versions, long employeeId) {
        return versions.stream()
            .filter(row -> row.employeeId() == employeeId)
            .map(DraftRowVersion::version)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no draft version row for employee " + employeeId));
    }

    private ProcessPayrollRequest request(LocalDate month, long employeeId, String specialPay1) {
        return new ProcessPayrollRequest(month, List.of(inputWithSpecialPay1(employeeId, new BigDecimal(specialPay1))));
    }

    private PayrollEmployeeInputRequest inputWithSpecialPay1(long employeeId, BigDecimal specialPay1) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO, // nonTaxableIncome
            BigDecimal.ZERO, // unpaidLeaveDays
            BigDecimal.ZERO, // studentLoanDeduction
            BigDecimal.ZERO, // legalExecutionDeduction
            BigDecimal.ZERO, // otherPostTaxDeductions
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, // warningLetterDeduction
            BigDecimal.ZERO, // customerReturnDeduction
            BigDecimal.ZERO, // otherPretaxDeduction
            null); // withholdingTaxOverride
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh) {
        return seedEmployee(code, firstNameTh, lastNameTh, new BigDecimal("30000.00"));
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
