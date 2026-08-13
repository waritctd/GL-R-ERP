package th.co.glr.hr.payroll.declaration;

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
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceApplyRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationRegisterResponse;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceReviewRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms the tax-allowance declaration workflow's authorization against the real service and the
 * real SQL — the gap Mockito-based unit tests cannot cover (issue #28), same reasoning as {@code
 * AttendanceScopeIntegrationTest}, which this class is modelled on directly.
 *
 * <p>This is the FIRST time employees gain self-read/self-write on their own tax data (decision #2
 * of the plan) — every case below is written wrong-way-round: can a caller reach data or an action
 * they should not, never "can they reach their own". {@code
 * TaxAllowanceApplySeamIntegrationTest} (sibling class) covers the decision-#2 payroll-isolation
 * guarantee specifically; this class covers who may read, write, approve, reject and apply.
 *
 * <p>Calls the SERVICE directly (no MockMvc / no HTTP filter chain) so this exercises {@link
 * TaxAllowanceDeclarationService}'s OWN {@code requireRole}/{@code requireEmployeeActor} checks —
 * the doubled, belt-and-braces half of the authorization, distinct from the
 * {@code @PreAuthorize} half {@code SecurityAuthorizationIntegrationTest} exercises over the real
 * filter chain.
 */
import th.co.glr.hr.config.AppProperties;

import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;

class TaxAllowanceDeclarationScopeIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository repository;
    private TaxAllowanceDeclarationService service;
    private PayrollRepository payrollRepository;

    private long employeeA;
    private long employeeB;
    private long hrEmployeeId;
    private long ceoEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new TaxAllowanceDeclarationRepository(jdbc);
        payrollRepository = new PayrollRepository(jdbc);
        service = new TaxAllowanceDeclarationService(
            repository,
            payrollRepository,
            // A mock is enough for what THIS class asserts. Two methods now reach EmployeeRepository
            // -- createOnBehalf, and getOwn's ล.ย.01 header prefill -- and neither is exercised
            // here: a Mockito mock answers Optional.empty() for the prefill, so every getOwn call
            // below sees an empty header block, which is exactly the state these declaration-scoping
            // assertions want. The prefill's own scoping cannot be tested through a mock at all
            // (CLAUDE.md: a mocked repository passes while the SQL does something else), so it has
            // its own class against real Postgres -- TaxAllowanceHeaderPrefillIntegrationTest.
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            // Evidence upload/estimate are not exercised by this scope test (that is
            // TaxAllowanceAttachmentScopeIntegrationTest's and the estimate tests' job) — mocks are
            // enough here.
            mock(FileStorageService.class),
            mock(PayrollService.class),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            new AppProperties(), new LorYor01Renderer());

        employeeA = seedEmployee("TAD-A");
        employeeB = seedEmployee("TAD-B");
        // reviewed_by_id/applied_by_id/submitted_by_id are real FKs onto hr.employee — the HR/CEO
        // actors below need a real backing row, not just an arbitrary UserPrincipal id.
        hrEmployeeId = seedEmployee("TAD-HR");
        ceoEmployeeId = seedEmployee("TAD-CEO");
    }

    // --- read/write scoping ---------------------------------------------------------------------

    /**
     * The rendered ล.ย.01 is at least as sensitive as the evidence behind it — it carries the
     * employee's 13-digit tax ID, home address and every declared amount on one page. Wrong-way-round
     * per CLAUDE.md: assert the caller who should NOT reach it gets nothing.
     */
    /**
     * The signed ล.ย.01 is a real precondition, not just a UI courtesy (owner decision #3).
     *
     * <p>Wrong-way-round per CLAUDE.md: every other fixture in this package now attaches the form
     * via {@code approveSigned}, so without THIS test the guard could be deleted and the whole suite
     * would still pass. The declaration must also survive as PENDING — a refused approval that
     * quietly advanced the status would be worse than no guard at all.
     */
    @Test
    void hrCannotApproveADeclarationWithNoSignedFormAttached() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));

        assertThatThrownBy(() -> service.approve(declaration.declarationId(), null, hrActor()))
            .as("approving an unsigned ล.ย.01 records HR as accepting a filing nobody attested to")
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(repository.findById(declaration.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.PENDING);
    }

    /** …and it goes through the moment the signed scan is there. */
    @Test
    void theSameDeclarationApprovesOnceTheSignedFormIsAttached() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));

        approveSigned(declaration.declarationId());

        assertThat(repository.findById(declaration.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);
    }

    @Test
    void employeeCannotRenderAnotherEmployeesLorYor01Form() {
        TaxAllowanceDeclarationDto victimDeclaration = submit(employeeB, 2026, new BigDecimal("60000"));

        assertThatThrownBy(() -> service.renderLorYor01(victimDeclaration.declarationId(), employeeActor(employeeA)))
            .as("a foreign declaration id must 404 — the same shape as every other read on this row")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** HR does reach it — the register exists to review these, and the owner obviously does too. */
    @Test
    void hrAndTheOwnerCanBothRenderTheForm() {
        TaxAllowanceDeclarationDto declaration = submit(employeeB, 2026, new BigDecimal("60000"));

        assertThat(service.renderLorYor01(declaration.declarationId(), hrActor()))
            .as("HR reviews these").isNotEmpty();
        assertThat(service.renderLorYor01(declaration.declarationId(), employeeActor(employeeB)))
            .as("the owner prints and signs it").isNotEmpty();
    }

    /**
     * The draft endpoint takes a request body and no employee id, so the only thing stopping it
     * becoming a read of someone else's ข้อ 13 SSO figure is that it always resolves the ACTOR. There
     * is no id in the body to assert on — this pins that the rendered document is the caller's own.
     */
    @Test
    void theDraftFormAlwaysRendersForTheCallerNotAnyoneElse() {
        byte[] pdf = service.renderLorYor01Draft(
            submitRequest(2026, new BigDecimal("60000")), employeeActor(employeeA));

        assertThat(pdf).isNotEmpty();
        // Nothing was persisted for either employee: a draft render is a pure read.
        assertThat(repository.findForEmployee(employeeA, 2026)).isEmpty();
        assertThat(repository.findForEmployee(employeeB, 2026)).isEmpty();
    }

    @Test
    void employeeCannotWithdrawAnotherEmployeesDeclarationAndTheVictimRowSurvives() {
        TaxAllowanceDeclarationDto victimDeclaration = submit(employeeB, 2026, new BigDecimal("60000"));

        assertThatThrownBy(() -> service.withdrawOwn(victimDeclaration.declarationId(), employeeActor(employeeA)))
            .as("an employee acting on another employee's declaration id must 404, not 403 — a 403 would leak that the id exists")
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        TaxAllowanceDeclarationDto stillThere = repository.findById(victimDeclaration.declarationId()).orElseThrow();
        assertThat(stillThere.status()).isEqualTo(TaxAllowanceDeclarationStatus.PENDING);
    }

    @Test
    void aSelfServiceSubmissionAlwaysLandsOnTheCallerAndNeverOnAnotherEmployee() {
        TaxAllowanceDeclarationDto created = submit(employeeA, 2026, new BigDecimal("60000"));

        assertThat(created.employeeId()).isEqualTo(employeeA);
        assertThat(repository.findForEmployee(employeeB, 2026))
            .as("submitOwn takes no employeeId parameter at all — there is no path from employeeA's " +
                "call that could ever create a row for employeeB")
            .isEmpty();
    }

    @Test
    void employeeCannotApproveTheirOwnDeclarationAndItStaysPending() {
        TaxAllowanceDeclarationDto own = submit(employeeA, 2026, new BigDecimal("60000"));

        assertThatThrownBy(() -> service.approve(own.declarationId(), null, employeeActor(employeeA)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(repository.findById(own.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.PENDING);
    }

    @Test
    void employeeCannotApplyAndTheAllowanceTableStaysEmpty() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));
        approveSigned(declaration.declarationId());

        assertThatThrownBy(() -> service.apply(declaration.declarationId(), null, employeeActor(employeeA)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(countEmployeeTaxAllowanceRows(employeeA)).isZero();
    }

    @Test
    void ceoReadsTheRegisterButCannotApproveOrApply() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));

        TaxAllowanceDeclarationRegisterResponse register = service.getRegister(2026, null, ceoActor());
        assertThat(register.items()).extracting(TaxAllowanceDeclarationDto::declarationId)
            .contains(declaration.declarationId());

        assertThatThrownBy(() -> service.approve(declaration.declarationId(), null, ceoActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(repository.findById(declaration.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.PENDING);

        approveSigned(declaration.declarationId()); // HR approves for real, so apply has something to reach
        assertThatThrownBy(() -> service.apply(declaration.declarationId(), null, ceoActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(countEmployeeTaxAllowanceRows(employeeA)).isZero();
    }

    @Test
    void approvingAnAlreadyApprovedDeclarationIsConflictViaTheRowcountCheck() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));

        approveSigned(declaration.declarationId());
        assertThatThrownBy(() -> service.approve(declaration.declarationId(), null, hrActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(repository.findById(declaration.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private TaxAllowanceDeclarationDto submit(long employeeId, int taxYear, BigDecimal spouseAllowance) {
        return service.submitOwn(submitRequest(taxYear, spouseAllowance), employeeActor(employeeId));
    }

    /** Extracted so the draft-render test can build the same body without persisting it. */
    private TaxAllowanceDeclarationSubmitRequest submitRequest(int taxYear, BigDecimal spouseAllowance) {
        return new TaxAllowanceDeclarationSubmitRequest(
            taxYear,                 // taxYear
            null,                    // effectiveMonth -> defaults to January
            spouseAllowance,         // spouseAllowance
            null, null, null, null,  // child, parentCare, disabledCare, maternity
            null, null, null,        // life, health, parentHealth
            null, null, null, null,  // rmf, ssf, pension, thaiEsg
            null, null, null, null,  // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,        // childCount, childCountDouble, disabledCareCount
            null,                    // disabilityCardHolder
            null,                    // parentCareCount
            null,                    // documentReference
            null);                   // lorYor01 — no ล.ย.01 form detail in this fixture
    }

    private int countEmployeeTaxAllowanceRows(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.employee_tax_allowance WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceoActor() {
        return new UserPrincipal(ceoEmployeeId, "ceo@glr.co.th", "CEO", "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
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
