package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.export.PayrollExportFile;
import th.co.glr.hr.payroll.export.PayrollExportKind;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Owner requirement (2026-07-30): the detailed payroll xlsx export must never trust whatever is
 * sitting in a VOID period's {@code hr.payroll_line} row -- a real, current example is 2026-07,
 * {@code period_id=1}, {@code status='VOID'}, whose rows carry HR's typed special-pay inputs
 * behind stale/zero computed tax and net-pay figures. This test reproduces exactly that shape (a
 * genuinely processed period, then corrupted computed columns + flipped to VOID via direct SQL --
 * simulating whatever produced July's real row) and proves {@link PayrollService#export} recomputes
 * through the live {@link PayrollService} preview path rather than reading the corrupted figures
 * back out. Driven through the real {@link PayrollService}/{@link PayrollCalculator}/real Postgres,
 * not Mockito -- the point under test is that the corrupted SQL row is NOT what comes back.
 */
class PayrollDetailExportVoidRecomputeIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate MONTH = LocalDate.of(2026, 3, 1);

    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = mock(CommissionService.class);
        org.mockito.Mockito.when(commissionService.payrollCommissionTotalsByEmployee(org.mockito.ArgumentMatchers.any()))
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
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class)));
    }

    @Test
    void voidPeriodExportReflectsARealRecomputeNotTheCorruptedStoredFigures() throws Exception {
        long employeeId = seedEmployee("VOID-001", "โมฆะ", "ทดสอบ", new BigDecimal("30000.00"));
        PayrollEmployeeInputRequest input = specialPay1InputOf(employeeId, new BigDecimal("1000.00"));

        // A genuine PROCESSED run -- this is the CORRECT answer we'll check survives the corruption.
        payrollService.process(new ProcessPayrollRequest(MONTH, List.of(input)), hr());
        long periodId = payrollRepository.findPeriodByMonth(MONTH)
            .map(PayrollPeriodDto::id)
            .orElseThrow(() -> new AssertionError("no period persisted for " + MONTH));
        PayrollLineDto correct = payrollRepository.findLines(periodId).stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();
        BigDecimal correctNetPay = correct.netPay();

        // Corrupt the computed columns (simulating whatever left July's real row stale/zero) while
        // leaving the HR-typed input column (special_pay_1) intact, then flip the period to VOID --
        // exactly July 2026's real shape per the owner's brief.
        jdbc.update("""
            UPDATE hr.payroll_line
               SET net_amount = 0, gross_taxable_income = 0, withholding_tax = 0, social_security = 0
             WHERE period_id = :periodId AND employee_id = :employeeId
            """, Map.of("periodId", periodId, "employeeId", employeeId));
        jdbc.update("UPDATE hr.payroll_period SET status = 'VOID' WHERE period_id = :periodId",
            Map.of("periodId", periodId));

        PayrollExportFile file = payrollService.export(
            PayrollExportKind.PAYROLL_DETAIL, periodId, LocalDate.of(2026, 3, 26), hr());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            int codeCol = -1;
            int netPayCol = -1;
            int specialPay1Col = -1;
            for (Cell cell : header) {
                if ("รหัสพนักงาน".equals(cell.getStringCellValue())) codeCol = cell.getColumnIndex();
                if ("คงเหลือจ่ายจริง (A-B-C+D)".equals(cell.getStringCellValue())) netPayCol = cell.getColumnIndex();
                if ("พิเศษ 1 (ค่าครองชีพ)".equals(cell.getStringCellValue())) specialPay1Col = cell.getColumnIndex();
            }
            Row dataRow = sheet.getRow(1);
            assertThat(dataRow.getCell(codeCol).getStringCellValue()).isEqualTo("VOID-001");
            assertThat(BigDecimal.valueOf(dataRow.getCell(netPayCol).getNumericCellValue()))
                .as("must equal the ORIGINAL correct net pay, NOT the corrupted 0 sitting in the VOID row")
                .isEqualByComparingTo(correctNetPay);
            assertThat(dataRow.getCell(netPayCol).getNumericCellValue())
                .as("the corrupted stored value was literally 0 -- the recompute must not equal it")
                .isNotEqualTo(0.0);
            assertThat(dataRow.getCell(specialPay1Col).getNumericCellValue())
                .as("HR's typed special-pay input must survive the reconstruct-and-recompute")
                .isEqualTo(1000.00);
        }
    }

    private PayrollEmployeeInputRequest specialPay1InputOf(long employeeId, BigDecimal specialPay1) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1, zero, zero, zero, zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero, zero,
            zero, zero, zero, null,
            zero, zero, zero, null,
            zero, zero,
            false, null, null
        );
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
