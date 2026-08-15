package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollCalculator;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.PayslipRenderer;
import th.co.glr.hr.payroll.ProcessPayrollRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Yearly expiry (decision #10) — driven directly against {@code TaxAllowanceDeclarationService
 * #expireOverdueVerifications} (never the {@code @Scheduled} worker, per {@code
 * QuotationExpiryWorker}'s own house pattern of keeping all logic testable without a timer).
 *
 * <p>Pins THE trap from the plan doc a second time (PR A's {@code TaxAllowanceApplySeamIntegrationTest
 * #expiringDoesNotFallBackToAnOlderDatedRowForTheSameYear} already covers the raw repository method
 * in isolation; this class covers the SWEEP that calls it end to end) and the two guarantees the
 * task spec calls out explicitly: idempotency, and never retro-altering an already-processed
 * month's persisted {@code hr.payroll_line} row.
 */
import th.co.glr.hr.config.AppProperties;

import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;

class TaxAllowanceExpiryIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository declarationRepository;
    private TaxAllowanceDeclarationService service;
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        declarationRepository = new TaxAllowanceDeclarationRepository(jdbc);
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
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));

        service = new TaxAllowanceDeclarationService(
            declarationRepository,
            payrollRepository,
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            mock(FileStorageService.class),
            payrollService,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            new AppProperties(), new LorYor01Renderer());

        hrEmployeeId = seedEmployee("EXP-HR", new BigDecimal("50000.00"));
    }

    @Test
    void sweepExpiresAnOverdueApplicationOnBothTablesAndIsIdempotent() {
        long employeeId = seedEmployee("EXP-A", new BigDecimal("50000.00"));
        // taxYear 2020: apply()'s expires_on default (31 Dec of the declaration's own tax year) is
        // therefore 2020-12-31 -- safely in the past regardless of when this test actually runs.
        long declarationId = submitApprovedAndApplied(employeeId, 2020, 1, new BigDecimal("60000"));

        int expiredCount = service.expireOverdueVerifications();
        assertThat(expiredCount).isEqualTo(1);

        TaxAllowanceDeclarationDto declaration = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(declaration.status()).isEqualTo(TaxAllowanceDeclarationStatus.EXPIRED);
        assertThat(declaration.expiredAt()).isNotNull();

        String verificationStatus = jdbc.queryForObject(
            "SELECT verification_status FROM hr.employee_tax_allowance WHERE employee_id = :id AND tax_year = 2020",
            Map.of("id", employeeId), String.class);
        assertThat(verificationStatus)
            .as("the sweep must flip the PARENT table too, via expireTaxAllowanceVerification -- "
                + "not just the staging declaration's own status")
            .isEqualTo("EXPIRED_UNVERIFIED");

        // Idempotent: a second sweep run must not re-expire the same row, error, or move the count.
        int secondRunCount = service.expireOverdueVerifications();
        assertThat(secondRunCount).as("nothing left eligible -- the row is already EXPIRED").isZero();
        TaxAllowanceDeclarationDto afterSecondRun = declarationRepository.findById(declarationId).orElseThrow();
        assertThat(afterSecondRun.expiredAt())
            .as("expired_at must not be re-stamped by a second sweep")
            .isEqualTo(declaration.expiredAt());
    }

    @Test
    void sweepDoesNotTouchAnAlreadyProcessedMonthForAnUnrelatedEmployee() {
        // employeeToExpire: an overdue applied declaration the sweep SHOULD act on.
        long employeeToExpire = seedEmployee("EXP-B", new BigDecimal("50000.00"));
        submitApprovedAndApplied(employeeToExpire, 2020, 1, new BigDecimal("60000"));

        // employeeProcessed: a COMPLETELY different employee whose June 2026 payroll has already
        // been processed for real, with its own standing (non-expiring, non-declaration-backed)
        // allowance context. The sweep must never reach into hr.payroll_line at all.
        long employeeProcessed = seedEmployee("EXP-C", new BigDecimal("45000.00"));
        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrActor());
        PayrollLineDto before = reloadedLineFor(LocalDate.of(2026, 6, 1), employeeProcessed);

        service.expireOverdueVerifications();

        PayrollLineDto after = reloadedLineFor(LocalDate.of(2026, 6, 1), employeeProcessed);
        assertThat(after.withholdingTax())
            .as("an already-filed month's withholding tax must never be retro-altered by the sweep")
            .isEqualByComparingTo(before.withholdingTax());
        assertThat(after.taxAllowanceTotal()).isEqualByComparingTo(before.taxAllowanceTotal());
        assertThat(after.netPay()).isEqualByComparingTo(before.netPay());

        PayrollPeriodDto period = payrollRepository.findPeriodByMonth(LocalDate.of(2026, 6, 1)).orElseThrow();
        assertThat(period.status()).isEqualTo("PROCESSED");
    }

    /** The mirror of expiry: EXPIRED -> APPROVED restores the whole year's lineage on the parent table. */
    @Test
    void reverifyRestoresBothTablesAndPayrollPicksTheAllowanceUpAgain() {
        long employeeId = seedEmployee("EXP-D", new BigDecimal("50000.00"));
        long declarationId = submitApprovedAndApplied(employeeId, 2020, 1, new BigDecimal("60000"));
        service.expireOverdueVerifications();
        assertThat(declarationRepository.findById(declarationId).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.EXPIRED);
        assertThat(payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2020, 6, 1)))
            .doesNotContainKey(employeeId);

        TaxAllowanceDeclarationDto reverified = service.reverify(declarationId, hrActor());

        assertThat(reverified.status()).isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);
        assertThat(reverified.expiredAt()).isNull();
        assertThat(payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2020, 6, 1)))
            .as("re-verification must restore the WHOLE year's lineage on the parent table")
            .containsKey(employeeId);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private long submitApprovedAndApplied(long employeeId, int taxYear, int effectiveMonth, BigDecimal spouseAllowance) {
        TaxAllowanceDeclarationSubmitRequest request = new TaxAllowanceDeclarationSubmitRequest(
            taxYear, effectiveMonth, spouseAllowance,
            null, null, null, null,
            null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null, null,
            null,
            null,
            null,
            null);                   // lorYor01 — no ล.ย.01 form detail in this fixture
        TaxAllowanceDeclarationDto declaration = service.submitOwn(request, employeeActor(employeeId));
        approveSigned(declaration.declarationId());
        service.apply(declaration.declarationId(), null, hrActor());
        return declaration.declarationId();
    }

    private PayrollLineDto reloadedLineFor(LocalDate month, long employeeId) {
        long periodId = payrollRepository.findPeriodByMonth(month)
            .map(PayrollPeriodDto::id)
            .orElseThrow(() -> new AssertionError("no period persisted for " + month));
        return payrollRepository.findLines(periodId).stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no persisted line for employee " + employeeId + " / " + month));
    }

    private long seedEmployee(String code, BigDecimal salary) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, current_salary, is_active) VALUES (:code, :salary, TRUE) RETURNING employee_id",
            Map.of("code", code, "salary", salary), Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }

    /**
     * Approves the way HR now has to: the signed ล.ย.01 must be attached first (owner decision #3).
     * The failure cases below deliberately do NOT use this — they assert on the role and status
     * checks, which both run before the signed-form check and so are unaffected by it.
     */
    private void approveSigned(long declarationId) {
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);
        service.approve(declarationId, null, hrActor());
    }
}
