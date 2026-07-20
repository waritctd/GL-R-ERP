package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * THE AUTHZ EVIDENCE for special-money requests, modelled directly on {@code
 * th.co.glr.hr.attendance.AttendanceScopeIntegrationTest}. Wires the REAL {@link
 * SpecialMoneyService}, the REAL {@link SpecialMoneyRepository}, and the REAL {@link
 * SpecialMoneyPolicyEvaluator} against a real Postgres database -- only {@link AuditService} and
 * {@link NotificationService} are stubbed, since neither participates in the authorization
 * decision.
 *
 * <p>CLAUDE.md records issue #199 (mockApi.js let HR approve OT; the real service returns 403) and
 * PR #238 (mock-driven browser clicking reported as verified role scoping) as exactly the failure
 * mode this test exists to catch. Every case here is written the WRONG way round: can a caller
 * reach data or mutate a row they should not be able to -- and every assertion checks the database
 * itself (a re-read or a row count), not just the HTTP-shaped status code, since a service that
 * throws late after already writing would still "look" correct to a status-code-only assertion.
 */
class SpecialMoneyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private SpecialMoneyService service;
    private SpecialMoneyRepository repository;

    private long salesDivision;
    private long factoryDivision;
    private long salesManagerEmployeeId;
    private long salesStaffEmployeeId;
    private long factoryStaffEmployeeId;
    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
        service = new SpecialMoneyService(
            repository,
            new SpecialMoneyPolicyEvaluator(),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties());

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        factoryDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        salesManagerEmployeeId = insertEmployee("M001", salesDivision, null);
        salesStaffEmployeeId = insertEmployee("S001", salesDivision, salesManagerEmployeeId);
        factoryStaffEmployeeId = insertEmployee("F001", factoryDivision, null);
        // HR's own employee record deliberately has no reports-to/division-manager link to anyone
        // else in this fixture -- if it did, managesEmployee() could accidentally succeed via that
        // relation instead of via an (absent) HR carve-out, masking exactly the bug this class
        // exists to catch.
        hrEmployeeId = insertEmployee("HR001", null, null);
    }

    // --- 1. list() division scoping -----------------------------------------

    @Test
    void managerCannotListRequestsOutsideOwnDivision() {
        long requestId = submitFuneralRequest(factoryStaffEmployeeId);

        List<SpecialMoneyRequestDto> visible = service.list(
            salesManager(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);

        assertThat(visible).isEmpty();
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
    }

    // --- 2. approve() out-of-division ----------------------------------------

    @Test
    void managerCannotApproveRequestOfOutOfDivisionEmployee() {
        long requestId = submitFuneralRequest(factoryStaffEmployeeId);

        assertThatThrownBy(() -> service.approve(requestId, null, salesManager()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("manager");
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
        assertThat(approvedAmountOf(requestId)).isNull();
    }

    // --- 3. list() employee scoping ------------------------------------------

    @Test
    void employeeCannotSeeAnotherEmployeesRequest() {
        submitFuneralRequest(salesManagerEmployeeId);

        List<SpecialMoneyRequestDto> visible = service.list(
            employee(salesStaffEmployeeId, salesDivision),
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);

        assertThat(visible).isEmpty();
    }

    // --- 4. submit() on behalf of a non-report -------------------------------

    @Test
    void employeeCannotSubmitOnBehalfOfNonReport() {
        long countBefore = requestCountFor(factoryStaffEmployeeId);

        assertThatThrownBy(() -> service.submit(
                "AID_FUNERAL",
                funeralRequest(factoryStaffEmployeeId, LocalDate.of(2026, 7, 1)),
                employee(salesStaffEmployeeId, salesDivision)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("own");

        assertThat(requestCountFor(factoryStaffEmployeeId)).isEqualTo(countBefore);
    }

    // --- 5. hr cannot approve (issue #199 shape) -----------------------------

    @Test
    void hrCannotApprove() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        assertThatThrownBy(() -> service.approve(requestId, null, hr()))
            .isInstanceOf(ApiException.class);
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
    }

    // --- 6. hr cannot submit on behalf of an arbitrary employee --------------

    @Test
    void hrCannotSubmitOnBehalfOfArbitraryEmployee() {
        long countBefore = requestCountFor(salesStaffEmployeeId);

        assertThatThrownBy(() -> service.submit(
                "AID_FUNERAL",
                funeralRequest(salesStaffEmployeeId, LocalDate.of(2026, 7, 1)),
                hr()))
            .isInstanceOf(ApiException.class);

        assertThat(requestCountFor(salesStaffEmployeeId)).isEqualTo(countBefore);
    }

    // --- 7. manager cannot skip to the CEO stage on their own request --------

    @Test
    void managerCannotCeoApproveOwnManagerApprovedRequest() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);
        service.approve(requestId, null, salesManager());
        assertThat(statusOf(requestId)).isEqualTo("MANAGER_APPROVED");

        assertThatThrownBy(() -> service.approve(requestId, null, salesManager()))
            .isInstanceOf(ApiException.class);
        assertThat(statusOf(requestId)).isEqualTo("MANAGER_APPROVED");
    }

    // --- 8. ceo must not skip stage 1 ----------------------------------------

    @Test
    void ceoCannotApproveRequestStillInSubmittedState() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        // The CEO reaches the manager-approval branch of approve()'s dispatch (SUBMITTED ->
        // managerApprove), which the CEO principal fails just like anyone who is not the manager.
        assertThatThrownBy(() -> service.approve(requestId, null, ceo()))
            .isInstanceOf(ApiException.class);
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
        assertThat(approvedAmountOf(requestId)).isNull();
    }

    // --- 9. usage() quota scoping ---------------------------------------------

    @Test
    void employeeCannotReadAnotherEmployeesUsageQuota() {
        assertThatThrownBy(() -> service.usage(
                factoryStaffEmployeeId, 2026, employee(salesStaffEmployeeId, salesDivision)))
            .isInstanceOf(ApiException.class);
    }

    // --- 10. cancel() scoping ---------------------------------------------------

    @Test
    void employeeCannotCancelAnotherEmployeesRequest() {
        long requestId = submitFuneralRequest(salesManagerEmployeeId);

        assertThatThrownBy(() -> service.cancel(requestId, null, employee(salesStaffEmployeeId, salesDivision)))
            .isInstanceOf(ApiException.class);
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
    }

    // --- helpers --------------------------------------------------------------

    private long submitFuneralRequest(long employeeId) {
        SpecialMoneyRequestDto created = service.submit(
            "AID_FUNERAL",
            funeralRequest(employeeId, LocalDate.of(2026, 7, 1)),
            employeeOwner(employeeId));
        return created.id();
    }

    private SubmitSpecialMoneyRequest funeralRequest(long employeeId, LocalDate eventDate) {
        return new SubmitSpecialMoneyRequest(
            employeeId,
            eventDate,
            null,
            null,
            BigDecimal.ONE,
            new BigDecimal("5000"),
            "Funeral aid",
            Map.of("relation", "parent"));
    }

    private String statusOf(long requestId) {
        return repository.findById(requestId).orElseThrow().status();
    }

    private BigDecimal approvedAmountOf(long requestId) {
        return repository.findById(requestId).orElseThrow().approvedAmount();
    }

    private long requestCountFor(long employeeId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.special_money_request WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Long.class);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", hrEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(2L, "ceo@glr.co.th", "ceo", "ceo", null, true,
            LocalDate.now(), false, null, false);
    }

    private UserPrincipal salesManager() {
        return new UserPrincipal(3L, "mgr@glr.co.th", "mgr", "employee", salesManagerEmployeeId, true,
            LocalDate.now(), false, salesDivision, true);
    }

    private UserPrincipal employee(long employeeId, long divisionId) {
        return new UserPrincipal(4L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, divisionId, false);
    }

    /** The employee submitting their own request -- resolves the caller's own division via the seed data. */
    private UserPrincipal employeeOwner(long employeeId) {
        Long divisionId = employeeId == factoryStaffEmployeeId ? factoryDivision : salesDivision;
        return new UserPrincipal(5L, "owner@glr.co.th", "owner", "employee", employeeId, true,
            LocalDate.now(), false, divisionId, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId, Long reportsToEmployeeId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsToEmployeeId);
        params.put("hireDate", LocalDate.of(2015, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
