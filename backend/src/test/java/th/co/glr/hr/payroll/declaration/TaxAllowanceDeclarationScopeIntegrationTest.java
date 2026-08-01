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
            // createOnBehalf (the only method that calls EmployeeRepository) is not exercised by
            // this scope test — a mock is enough here.
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            // Evidence upload/estimate are not exercised by this scope test (that is
            // TaxAllowanceAttachmentScopeIntegrationTest's and the estimate tests' job) — mocks are
            // enough here.
            mock(FileStorageService.class),
            mock(PayrollService.class));

        employeeA = seedEmployee("TAD-A");
        employeeB = seedEmployee("TAD-B");
        // reviewed_by_id/applied_by_id/submitted_by_id are real FKs onto hr.employee — the HR/CEO
        // actors below need a real backing row, not just an arbitrary UserPrincipal id.
        hrEmployeeId = seedEmployee("TAD-HR");
        ceoEmployeeId = seedEmployee("TAD-CEO");
    }

    // --- read/write scoping ---------------------------------------------------------------------

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
        service.approve(declaration.declarationId(), null, hrActor());

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

        service.approve(declaration.declarationId(), null, hrActor()); // HR approves for real, so apply has something to reach
        assertThatThrownBy(() -> service.apply(declaration.declarationId(), null, ceoActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(countEmployeeTaxAllowanceRows(employeeA)).isZero();
    }

    @Test
    void approvingAnAlreadyApprovedDeclarationIsConflictViaTheRowcountCheck() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));

        service.approve(declaration.declarationId(), null, hrActor());
        assertThatThrownBy(() -> service.approve(declaration.declarationId(), null, hrActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(repository.findById(declaration.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private TaxAllowanceDeclarationDto submit(long employeeId, int taxYear, BigDecimal spouseAllowance) {
        TaxAllowanceDeclarationSubmitRequest request = new TaxAllowanceDeclarationSubmitRequest(
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
            null);                   // documentReference
        return service.submitOwn(request, employeeActor(employeeId));
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
}
