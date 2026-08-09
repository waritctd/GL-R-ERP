package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Real-DB proof that the commission "earning window" (ข้อ 11's ยอดรับเงินรวม "ในแต่ละเดือน") is
 * bound to the CALENDAR month, not to any 25th/26th-style cycle — see the reasoning and the
 * three-concept table (Earning window / Documentation cutoff / Payment date) in
 * this branch's PR body. Owner REJECTED shifting this to
 * a 26th–25th window (2026-07-30): doing so would move receipts between tier brackets and across
 * the ฿50,000 monthly floor and change what people are paid for identical sales — see {@link
 * CommissionMonthlyEarningWindowTest} for that consequence, worked through the calculator
 * directly.
 *
 * <p>This class drives the real {@link CommissionService#submit} (the same path a real
 * sales_manager/CEO/account submission takes) for receipts dated the 1st, 25th, 26th, and last
 * day of August, and proves every one of them lands in the SAME {@code payroll_month} bucket
 * (September) — the 25th/26th is not a boundary anywhere in this code path. It also proves the
 * boundary that DOES exist is exactly the calendar month edge (31 Jul vs 1 Aug bucket
 * differently).
 */
class CommissionMonthlyEarningWindowIntegrationTest extends AbstractPostgresIntegrationTest {
    private CommissionService commissionService;
    private EmployeeRepository employees;
    private UserPrincipal managerActor;

    private void wireService() {
        CommissionRepository commissions = new CommissionRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            new CommissionCalculator(),
            new FileStorageService("/tmp/glr-commission-window-test-uploads"),
            org.mockito.Mockito.mock(AuditService.class),
            org.mockito.Mockito.mock(NotificationService.class),
            org.mockito.Mockito.mock(TicketRepository.class),
            new AttachmentRepository(jdbc), new CeoApproverRepository(jdbc));
        long managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย หน้าต่าง", "sm-window@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
    }

    @Test
    void receiptsOnFirst_25th_26th_andLastDayOfAugust_allBucketIntoTheSameSeptemberPayrollMonth() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย เดือนเดียวกัน", "same-month@glr.co.th", "SA", "แผนกขาย");

        CommissionRecord day1 = submitReceipt(salesRepId, LocalDate.of(2026, 8, 1), new BigDecimal("10000.00"));
        CommissionRecord day25 = submitReceipt(salesRepId, LocalDate.of(2026, 8, 25), new BigDecimal("10000.00"));
        CommissionRecord day26 = submitReceipt(salesRepId, LocalDate.of(2026, 8, 26), new BigDecimal("10000.00"));
        CommissionRecord dayLast = submitReceipt(salesRepId, LocalDate.of(2026, 8, 31), new BigDecimal("10000.00"));

        LocalDate september = LocalDate.of(2026, 9, 1);
        assertThat(day1.payrollMonth())
            .as("1 Aug must bucket with the rest of calendar August")
            .isEqualTo(september);
        assertThat(day25.payrollMonth())
            .as("25 Aug is NOT a window boundary in this system — same bucket as 1/26/31 Aug")
            .isEqualTo(september);
        assertThat(day26.payrollMonth())
            .as("26 Aug is NOT a window boundary in this system — same bucket as 1/25/31 Aug")
            .isEqualTo(september);
        assertThat(dayLast.payrollMonth())
            .as("31 Aug (last day) must bucket with the rest of calendar August")
            .isEqualTo(september);
    }

    @Test
    void julyThirtyFirst_andAugustFirst_bucketIntoDifferentPayrollMonths_theBoundaryIsTheCalendarMonthEdge() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ขอบเดือน", "month-edge@glr.co.th", "SA", "แผนกขาย");

        CommissionRecord july31 = submitReceipt(salesRepId, LocalDate.of(2026, 7, 31), new BigDecimal("10000.00"));
        CommissionRecord aug1 = submitReceipt(salesRepId, LocalDate.of(2026, 8, 1), new BigDecimal("10000.00"));

        assertThat(july31.payrollMonth())
            .as("31 Jul belongs to calendar July, paid with August's payroll")
            .isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(aug1.payrollMonth())
            .as("1 Aug belongs to calendar August, paid with September's payroll — ONE DAY after "
                + "31 Jul, but a different payroll_month, because the boundary is the calendar "
                + "month edge (midnight 31 Jul/1 Aug), not the 25th/26th")
            .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(july31.payrollMonth()).isNotEqualTo(aug1.payrollMonth());
    }

    @Test
    void augustReceiptsOnEitherSideOf25th26th_combineIntoOneMonthlyTierBase_notTwo() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย รวมยอด", "combined-base@glr.co.th", "SA", "แผนกขาย");
        // The owner's worked example, submitted as two real receipts either side of where a
        // 26th/25th window would have split them.
        submitReceipt(salesRepId, LocalDate.of(2026, 8, 20), new BigDecimal("240000.00"));
        submitReceipt(salesRepId, LocalDate.of(2026, 8, 28), new BigDecimal("30000.00"));

        BigDecimal weightedBase = new CommissionRepository(jdbc)
            .sumActiveWeightedActualReceived(salesRepId, LocalDate.of(2026, 9, 1));

        // Both receipts landed in the SAME payroll_month bucket (September) and sum together —
        // a shifted 26th/25th window would instead have split them into two smaller totals (see
        // CommissionMonthlyEarningWindowTest for the resulting commission difference).
        assertThat(weightedBase).isEqualByComparingTo(new BigDecimal("270000.00"));
    }

    private CommissionRecord submitReceipt(long salesRepId, LocalDate invoiceDate, BigDecimal grossAmount) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-WINDOW-" + UUID.randomUUID(), invoiceDate, grossAmount,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        return commissionService.submit(request, invoiceFile(), managerActor);
    }

    private MultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
