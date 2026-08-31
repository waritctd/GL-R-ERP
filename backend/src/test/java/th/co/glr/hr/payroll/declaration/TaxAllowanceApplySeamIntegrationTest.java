package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollCalculator;
import th.co.glr.hr.payroll.PayrollComponent;
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
 * Pins the APPROVAL seam — through the REAL {@code PayrollRepository#findTaxAllowancesByEmployee}
 * SQL and the REAL {@code PayrollService#preview}, never a unit-test stand-in.
 *
 * <p><b>Decision #2 was reversed by the owner on 2026-08-31.</b> It used to read "a declaration must
 * not affect payroll until HR explicitly applies it", and this file pinned that: approval moved
 * nothing, a separate per-employee Apply (with its own งวดเดือน) did. The rule now is that HR's
 * APPROVAL is go-live, for the declaration's whole tax year. The tests below were rewritten in that
 * direction rather than deleted, because the half that still matters is unchanged — SUBMITTING must
 * still move nothing, and the parent table must still end VERIFIED with exactly one row.
 *
 * <p>Also pins the single most likely way this feature ships a silent tax error (plan doc, "the
 * DISTINCT ON expiry trap"): {@code findTaxAllowancesByEmployee} is {@code SELECT DISTINCT ON
 * (employee_id) ... ORDER BY employee_id, effective_month DESC}, so expiring only the LATEST dated
 * row would make it silently fall back to an OLDER dated row for the same year. That is why {@code
 * expireTaxAllowanceVerification(employeeId, taxYear)} takes no {@code effective_month} — it expires
 * every dated row for the year at once.
 */
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;

import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;

class TaxAllowanceApplySeamIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository declarationRepository;
    private TaxAllowanceDeclarationService service;
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    private long employeeId;
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

        service = new TaxAllowanceDeclarationService(
            declarationRepository,
            payrollRepository,
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            // Evidence upload is not exercised here — a mock is enough.
            mock(FileStorageService.class),
            // The REAL PayrollService, wired above — this class's byte-for-byte pinning test needs
            // estimateAllowanceEffect to run the SAME calculator instance #preview does.
            payrollService,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            // Not under test here — this class is about the apply() seam / byte-for-byte pinning.
            mock(NotificationService.class),
            new AppProperties(), new LorYor01Renderer());

        employeeId = seedEmployee("SEAM-EMP", new BigDecimal("50000.00"));
        hrEmployeeId = seedEmployee("SEAM-HR", new BigDecimal("50000.00"));
        seedRegularTaxTreatment(employeeId, 2026, PayrollComponent.SPECIAL_PAY_1);
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
    }

    @Test
    void aPendingDeclarationNeverAppearsInFindTaxAllowancesByEmployee() {
        submit(new BigDecimal("60000"));

        Map<Long, th.co.glr.hr.payroll.PayrollTaxAllowanceInput> resolved =
            payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 6, 1));

        assertThat(resolved).doesNotContainKey(employeeId);
    }

    /** The reversed decision #2: approval alone is go-live, with no second Apply call anywhere. */
    @Test
    void approvingPutsItInTheParentTableAsVerifiedWithNoSeparateApply() {
        TaxAllowanceDeclarationDto declaration = submit(new BigDecimal("60000"));
        approveSigned(declaration.declarationId());

        TaxAllowanceDeclarationDto approved = declarationRepository.findById(declaration.declarationId()).orElseThrow();
        assertThat(approved.status()).isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);
        assertThat(approved.appliedAt()).as("approve() promotes in its own transaction now").isNotNull();
        assertThat(approved.appliedEffectiveMonth())
            .as("whole tax year, never a chosen month")
            .isEqualTo(1);
        assertThat(approved.expiresOn()).isEqualTo(LocalDate.of(2026, 12, 31));

        // A second promotion is refused -- the applied_at IS NULL guard in markApplied.
        assertThatThrownBy(() -> service.apply(declaration.declarationId(), hrActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(countEmployeeTaxAllowanceRows(employeeId)).isEqualTo(1);
        String verificationStatus = jdbc.queryForObject(
            "SELECT verification_status FROM hr.employee_tax_allowance WHERE employee_id = :id",
            Map.of("id", employeeId), String.class);
        assertThat(verificationStatus).isEqualTo("VERIFIED");

        Map<Long, th.co.glr.hr.payroll.PayrollTaxAllowanceInput> resolved =
            payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 6, 1));
        assertThat(resolved).containsKey(employeeId);
        assertThat(resolved.get(employeeId).spouseAllowance()).isEqualByComparingTo("60000.00");
    }

    /**
     * Regression guard for the reset-on-overwrite fix (PayrollRepository#upsertTaxAllowances,
     * 2026-08-08): that fix makes upsertTaxAllowances itself downgrade an already-VERIFIED row back
     * to GRANDFATHERED_UNVERIFIED when its content changes -- exactly the ON CONFLICT DO UPDATE
     * branch a SECOND apply() over the same employee/year/effective-month drives. apply() must still
     * end VERIFIED regardless, because it calls markTaxAllowanceVerified +
     * setTaxAllowanceVerificationDeadline in the SAME transaction right after upsertTaxAllowances --
     * this pins that ordering never regresses.
     */
    @Test
    void approvingASecondDeclarationOverAnAlreadyVerifiedRowStillEndsVerified() {
        TaxAllowanceDeclarationDto first = submit(new BigDecimal("60000"));
        approveSigned(first.declarationId());
        assertThat(verificationStatusOf(employeeId)).isEqualTo("VERIFIED");

        // A second declaration for the SAME employee/year, with a DIFFERENT amount -- its promotion
        // drives upsertTaxAllowances down the ON CONFLICT DO UPDATE branch over a row that is
        // currently VERIFIED, the exact interaction the reset-on-overwrite fix touches.
        TaxAllowanceDeclarationDto second = submit(new BigDecimal("90000"));
        approveSigned(second.declarationId());

        assertThat(countEmployeeTaxAllowanceRows(employeeId))
            .as("still one row for this employee/year -- ON CONFLICT overwrote, not duplicated")
            .isEqualTo(1);
        assertThat(verificationStatusOf(employeeId))
            .as("promotion must still end VERIFIED even though upsertTaxAllowances resets it mid-transaction")
            .isEqualTo("VERIFIED");
        Long verifiedBy = jdbc.queryForObject(
            "SELECT verified_by_id FROM hr.employee_tax_allowance WHERE employee_id = :id",
            Map.of("id", employeeId), Long.class);
        assertThat(verifiedBy).isEqualTo(hrEmployeeId);
        OffsetDateTime verifiedAt = jdbc.queryForObject(
            "SELECT verified_at FROM hr.employee_tax_allowance WHERE employee_id = :id",
            Map.of("id", employeeId), OffsetDateTime.class);
        assertThat(verifiedAt).isNotNull();

        Map<Long, th.co.glr.hr.payroll.PayrollTaxAllowanceInput> resolved =
            payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 6, 1));
        assertThat(resolved.get(employeeId).spouseAllowance())
            .as("the second declaration's NEW figure is what's actually live, not a stale skipped one")
            .isEqualByComparingTo("90000.00");
    }

    /**
     * The PROCESSED-month guard is GONE, and this pins that deliberately rather than leaving its
     * absence to be re-added by someone reading the old comment. It refused a promotion whose target
     * month was already {@code PROCESSED}, which made sense while HR picked the month. The whole-year
     * rule makes the target ALWAYS January, so keeping it would refuse every approval from February
     * onward — it would not protect a filed month, it would break the feature for eleven of twelve.
     *
     * <p>Nothing here retro-alters January: the seeded PROCESSED period's own {@code hr.payroll_line}
     * rows are never touched by any statement the promotion runs.
     */
    @Test
    void approvingIsNotBlockedByAnAlreadyProcessedMonth() {
        seedProcessedPeriod(LocalDate.of(2026, 1, 1));
        TaxAllowanceDeclarationDto declaration = submit(new BigDecimal("60000"));

        approveSigned(declaration.declarationId());

        assertThat(countEmployeeTaxAllowanceRows(employeeId)).isEqualTo(1);
        assertThat(declarationRepository.findById(declaration.declarationId()).orElseThrow().appliedAt())
            .isNotNull();
    }

    /**
     * THE trap the whole-year rule introduces, and the reason {@code
     * PayrollRepository#deleteMidYearTaxAllowances} exists. {@code findTaxAllowancesByEmployee} is
     * {@code ORDER BY effective_month DESC}: a whole-year row at month 1 LOSES to any surviving
     * mid-year row. Without the delete, this employee's September payroll would keep computing on
     * the ฿30,000 July row while the register showed the ฿90,000 declaration as applied — a silent
     * wrong tax, invisible from every screen.
     *
     * <p>Written wrong-way-round on purpose: the assertion that matters is that the OLD figure is
     * gone, not that the new one is present.
     */
    @Test
    void approvingClearsAStaleMidYearRowInsteadOfLosingToItOnEffectiveMonthDesc() {
        jdbc.update("""
            INSERT INTO hr.employee_tax_allowance
                (employee_id, tax_year, effective_month, spouse_allowance, verification_status)
            VALUES (:id, 2026, 7, 30000, 'VERIFIED')
            """, Map.of("id", employeeId));
        assertThat(payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 9, 1))
            .get(employeeId).spouseAllowance())
            .as("precondition: the stale July row is what September resolves to today")
            .isEqualByComparingTo("30000.00");

        approveSigned(submit(new BigDecimal("90000")).declarationId());

        assertThat(countEmployeeTaxAllowanceRows(employeeId))
            .as("one row per employee/tax-year -- the July row is gone, not shadowed")
            .isEqualTo(1);
        assertThat(payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 9, 1))
            .get(employeeId).spouseAllowance())
            .as("September must NOT still resolve the superseded July figure")
            .isEqualByComparingTo("90000.00");
        // ...and every month of the year now resolves it, which is what "ทั้งปีภาษี" means.
        assertThat(payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 2, 1))
            .get(employeeId).spouseAllowance())
            .isEqualByComparingTo("90000.00");
    }

    /**
     * The backlog drain {@link TaxAllowanceDeclarationService#apply} still exists for: a row approved
     * BEFORE the ruling, when approval did not promote. That state can no longer be produced through
     * the service, so it is built through the repository — the same two calls the pre-ruling
     * {@code approve} made.
     */
    @Test
    void applyStillDrainsADeclarationApprovedBeforeTheRulingWithNoMonthArgument() {
        TaxAllowanceDeclarationDto declaration = submit(new BigDecimal("60000"));
        declarationRepository.approve(declaration.declarationId(), hrEmployeeId, "pre-ruling approval");
        assertThat(declarationRepository.findById(declaration.declarationId()).orElseThrow().appliedAt())
            .as("precondition: APPROVED with applied_at still NULL")
            .isNull();
        assertThat(countEmployeeTaxAllowanceRows(employeeId)).isZero();

        service.apply(declaration.declarationId(), hrActor());

        assertThat(countEmployeeTaxAllowanceRows(employeeId)).isEqualTo(1);
        assertThat(verificationStatusOf(employeeId)).isEqualTo("VERIFIED");
        assertThat(declarationRepository.findById(declaration.declarationId()).orElseThrow()
            .appliedEffectiveMonth()).isEqualTo(1);
    }

    /**
     * THE trap. Two VERIFIED dated rows (months 1 and 7) directly in {@code
     * hr.employee_tax_allowance} — the parent table, seeded straight via SQL to isolate the
     * repository's own SQL from the declaration workflow above. {@code
     * expireTaxAllowanceVerification} must flip BOTH to {@code EXPIRED_UNVERIFIED}, or the July
     * query would silently fall back to the January row instead of returning nothing.
     */
    @Test
    void expiringDoesNotFallBackToAnOlderDatedRowForTheSameYear() {
        jdbc.update("""
            INSERT INTO hr.employee_tax_allowance
                (employee_id, tax_year, effective_month, spouse_allowance, verification_status)
            VALUES (:id, 2026, 1, 60000, 'VERIFIED')
            """, Map.of("id", employeeId));
        jdbc.update("""
            INSERT INTO hr.employee_tax_allowance
                (employee_id, tax_year, effective_month, spouse_allowance, verification_status)
            VALUES (:id, 2026, 7, 60000, 'VERIFIED')
            """, Map.of("id", employeeId));

        // Sanity: before expiry, July resolves the July row (latest on-or-before July).
        assertThat(payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 7, 1)))
            .containsKey(employeeId);

        payrollRepository.expireTaxAllowanceVerification(employeeId, 2026);

        assertThat(payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 7, 1)))
            .as("must NOT fall back to the January row now that both are EXPIRED_UNVERIFIED")
            .doesNotContainKey(employeeId);
        // The DEFECT this pins: expiring only the July row would leave January VERIFIED, and July's
        // query (ORDER BY effective_month DESC, excluding only EXPIRED_UNVERIFIED) would silently
        // resolve January instead of nothing.
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.employee_tax_allowance WHERE employee_id = :id AND verification_status = 'EXPIRED_UNVERIFIED'",
            Map.of("id", employeeId), Integer.class))
            .isEqualTo(2);
    }

    @Test
    void approvingANewerDeclarationSupersedesThePreviousAndHistorySurvives() {
        TaxAllowanceDeclarationDto first = submit(new BigDecimal("60000"));
        approveSigned(first.declarationId());

        // A second declaration for the same employee/year — allowed once the first is no longer
        // PENDING (uq_tad_one_pending_per_employee_year only blocks a SECOND concurrent PENDING).
        TaxAllowanceDeclarationDto second = submit(new BigDecimal("90000"));
        approveSigned(second.declarationId());

        TaxAllowanceDeclarationDto firstAfter = declarationRepository.findById(first.declarationId()).orElseThrow();
        assertThat(firstAfter.status()).isEqualTo(TaxAllowanceDeclarationStatus.SUPERSEDED);
        assertThat(firstAfter.supersededById()).isEqualTo(second.declarationId());

        TaxAllowanceDeclarationDto secondAfter = declarationRepository.findById(second.declarationId()).orElseThrow();
        assertThat(secondAfter.status()).isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);

        // History survives — both rows still exist, findForEmployee still returns both.
        assertThat(declarationRepository.findForEmployee(employeeId, 2026))
            .extracting(TaxAllowanceDeclarationDto::declarationId)
            .contains(first.declarationId(), second.declarationId());
    }

    @Test
    void aFullPayrollPreviewIsUnchangedBySubmittingAndThenMovesOnApproval() {
        PayrollLineDto before = lineFor(payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrPrincipal()));

        TaxAllowanceDeclarationDto declaration = submit(new BigDecimal("60000"));
        PayrollLineDto afterSubmit = lineFor(payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrPrincipal()));
        assertThat(afterSubmit.taxAllowanceTotal())
            .as("submitting must not move payroll at all")
            .isEqualByComparingTo(before.taxAllowanceTotal());
        assertThat(afterSubmit.withholdingTax()).isEqualByComparingTo(before.withholdingTax());

        approveSigned(declaration.declarationId());
        PayrollLineDto afterApprove = lineFor(payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrPrincipal()));
        assertThat(afterApprove.taxAllowanceTotal())
            .as("approving IS go-live since 2026-08-31 — June must now see the ฿60,000")
            .isEqualByComparingTo(before.taxAllowanceTotal().add(new BigDecimal("60000.00")));
        assertThat(afterApprove.withholdingTax())
            .as("and less allowance-free income means strictly less withholding")
            .isLessThan(before.withholdingTax());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private TaxAllowanceDeclarationDto submit(BigDecimal spouseAllowance) {
        TaxAllowanceDeclarationSubmitRequest request = new TaxAllowanceDeclarationSubmitRequest(
            2026,                     // taxYear
            spouseAllowance,          // spouseAllowance
            null, null, null, null,   // child, parentCare, disabledCare, maternity
            null, null, null,         // life, health, parentHealth
            null, null, null, null,   // rmf, ssf, pension, thaiEsg
            null, null, null, null,   // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,         // childCount, childCountDouble, disabledCareCount
            null,                     // disabilityCardHolder
            null,                     // parentCareCount
            null,                    // documentReference
            null);                   // lorYor01 — no ล.ย.01 form detail in this fixture
        return service.submitOwn(request, employeeActor());
    }

    private void seedProcessedPeriod(LocalDate month) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (:month, :month, :monthEnd, :monthEnd, 'PROCESSED')
            """,
            Map.of("month", month, "monthEnd", month.withDayOfMonth(month.lengthOfMonth())));
    }

    private int countEmployeeTaxAllowanceRows(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.employee_tax_allowance WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    private String verificationStatusOf(long employeeId) {
        return jdbc.queryForObject(
            "SELECT verification_status FROM hr.employee_tax_allowance WHERE employee_id = :id",
            Map.of("id", employeeId), String.class);
    }

    private PayrollLineDto lineFor(PayrollPeriodDto period) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private long seedEmployee(String code, BigDecimal salary) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, current_salary, is_active) VALUES (:code, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "salary", salary), Long.class);
    }

    private UserPrincipal employeeActor() {
        return new UserPrincipal(employeeId, "seam@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrPrincipal() {
        return hrActor();
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
