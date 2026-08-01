package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Defect fix (Opus review, 2026-07-29): {@link PayrollService#specialPayDtos} (consulted when a
 * period is PREVIEWED) and {@link PayrollRepository}'s private {@code specialPays(ResultSet)}
 * (consulted when a PROCESSED period is READ BACK from {@code hr.payroll_line}) each hardcode their
 * own copy of the พิเศษ slot -> Thai label mapping — the database stores only the nine numeric
 * amounts, never a label. When the 2026-07-29 workbook realignment (V95/V97, see {@code
 * PayrollService#specialPayDtos}'s javadoc) moved {@code PayrollService}'s labels to the new
 * numbering, {@code PayrollRepository}'s copy was left on the OLD numbering: an employee's payslip
 * PDF and every HR screen printed the wrong Thai label for every slot from 2 through 9 on any
 * ALREADY-PROCESSED period, while a fresh PREVIEW (which never round-trips through the database)
 * showed the correct label for the exact same money.
 *
 * <p>This test proves the two never drift apart again by comparing the labels a PREVIEW returns
 * against the labels the SAME period returns after being PROCESSED and read back — driven entirely
 * through the real {@link PayrollService} and {@link PayrollRepository}, per CLAUDE.md.
 */
class PayrollSlotLabelAlignmentIntegrationTest extends AbstractPostgresIntegrationTest {
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
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class)));
    }

    @Test
    void previewLabelsAndPostProcessReadBackLabelsAgreeForEveryOfTheNineSlots() {
        long employeeId = seedEmployee("LABEL-001", "ป้าย", "ทดสอบ", new BigDecimal("30000.00"));
        LocalDate month = LocalDate.of(2026, 4, 1);

        // Amounts are irrelevant to this test (specialPayDtos/specialPays always emit all nine
        // labels regardless of amount) -- zero is fine and needs no tax-treatment classification.
        List<PayrollSpecialPayDto> previewLabels = onlyLine(
            payrollService.preview(new ProcessPayrollRequest(month, List.of()), hr()), employeeId)
            .specialPays();

        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        List<PayrollSpecialPayDto> readBackLabels = onlyLine(
            payrollRepository.findPeriodByMonth(month).orElseThrow(), employeeId)
            .specialPays();

        assertThat(previewLabels).hasSize(9);
        assertThat(readBackLabels).hasSize(9);
        for (int slot = 0; slot < 9; slot++) {
            PayrollSpecialPayDto previewed = previewLabels.get(slot);
            PayrollSpecialPayDto readBack = readBackLabels.get(slot);
            assertThat(readBack.key())
                .as("slot %d key must match between preview and read-back", slot + 1)
                .isEqualTo(previewed.key());
            assertThat(readBack.label())
                .as("slot %d (%s) label must match between preview and read-back -- "
                    + "preview said \"%s\", read-back said \"%s\"",
                    slot + 1, previewed.key(), previewed.label(), readBack.label())
                .isEqualTo(previewed.label());
        }

        // Pin the actual accountant's-workbook labels, not just internal agreement, so a future
        // edit that moves BOTH copies together in the same wrong direction still fails here.
        assertThat(readBackLabels.get(0).label()).isEqualTo("พิเศษ 1 (ค่าครองชีพ)");
        assertThat(readBackLabels.get(1).label()).isEqualTo("พิเศษ 2 (ค่าเช่าบ้าน)");
        assertThat(readBackLabels.get(2).label()).isEqualTo("พิเศษ 3 (เบี้ยเลี้ยงประจำ)");
        assertThat(readBackLabels.get(3).label()).isEqualTo("พิเศษ 4 (ค่าตำแหน่ง)");
        assertThat(readBackLabels.get(4).label()).isEqualTo("พิเศษ 5 (เบี้ยขยันประจำ)");
        assertThat(readBackLabels.get(5).label()).isEqualTo("พิเศษ 6 (ค่า GPRS)");
        assertThat(readBackLabels.get(6).label()).isEqualTo("พิเศษ 7 (คอมมิชชั่น)");
        assertThat(readBackLabels.get(7).label()).isEqualTo("พิเศษ 8 (ทำได้ตาม KPI)");
        assertThat(readBackLabels.get(8).label()).isEqualTo("พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)");
    }

    // --- helpers ------------------------------------------------------------

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
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            java.util.Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }
}
