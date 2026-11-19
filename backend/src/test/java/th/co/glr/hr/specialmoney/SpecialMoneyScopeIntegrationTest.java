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
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** THE AUTHZ EVIDENCE for special-money requests, modelled directly on
 * th.co.glr.hr.attendance.AttendanceScopeIntegrationTest. Section 11 is the confidentiality rule (owner, 2026-08-10):
 * welfare is confidential to each employee, so a ฝ่าย manager sees only their OWN requests -- not their team's. */
class SpecialMoneyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private SpecialMoneyService service;
    private SpecialMoneyRepository repository;

    private long salesDivision;
    private long factoryDivision;
    private long orphanDivision;
    private long salesManagerEmployeeId;
    private long salesStaffEmployeeId;
    private long factoryStaffEmployeeId;
    private long orphanStaffEmployeeId;
    private long hrEmployeeId;
    private long ceoEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
        service = new SpecialMoneyService(
            repository,
            new SpecialMoneyPolicyEvaluator(),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(), new CeoApproverRepository(jdbc));

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        factoryDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        // ฝ่ายขาย has a real ผู้จัดการ, so its staff keep the two-stage manager -> CEO route and the cases below still
        // exercise the manager gate rather than the manager-less shortcut.
        salesManagerEmployeeId = insertEmployee("M001", salesDivision, null, "ผู้จัดการฝ่ายขาย");
        salesStaffEmployeeId = insertEmployee("S001", salesDivision, salesManagerEmployeeId);
        // ฝ่ายโรงงาน needs its own ผู้จัดการ too.
        insertEmployee("M002", factoryDivision, null, "ผู้จัดการฝ่ายโรงงาน");
        factoryStaffEmployeeId = insertEmployee("F001", factoryDivision, null);
        // ฝ่ายซ่อมบำรุง has NO ผู้จัดการ at all -- the manager-less case the CEO shortcut exists for.
        orphanDivision = insertDivision("MNT", "ฝ่ายซ่อมบำรุง");
        orphanStaffEmployeeId = insertEmployee("O001", orphanDivision, null);
        // HR's own employee record deliberately has no reports-to/division-manager link to anyone else in this fixture
        // -- if it did, managesEmployee() could accidentally succeed via that relation instead of via an (absent) HR
        // carve-out, masking exactly the bug this class exists to catch.
        hrEmployeeId = insertEmployee("HR001", null, null);
        ceoEmployeeId = insertEmployee("CEO001", null, null);
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

    // --- 2. approve() is CEO-only, for every employee ------------------------

    @Test
    void noManagerCanApproveAWelfareRequestInsideOrOutsideTheirOwnDivision() {
        long ownDivision = submitFuneralRequest(salesStaffEmployeeId);
        long otherDivision = submitFuneralRequest(factoryStaffEmployeeId);

        // Welfare has no manager stage at all, so a ผู้จัดการ is refused even for their OWN team -- the case that
        // would slip through if the CEO-only rule were implemented as a scope check rather than a role check.
        for (long requestId : new long[] {ownDivision, otherDivision}) {
            assertThatThrownBy(() -> service.approve(requestId, null, salesManager()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CEO");
            assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
            assertThat(approvedAmountOf(requestId)).isNull();
        }
    }

    // --- 8. the CEO approves in ONE stage, for every shape of employee -------

    @Test
    void ceoApprovesStraightFromSubmittedForEveryEmployee() {
        // Three shapes that used to route differently: staff under a ผู้จัดการ, staff in a ฝ่าย with no ผู้จัดการ at
        // all, and a ผู้จัดการ's own request. Welfare must treat all three identically -- one CEO stage, no manager
        // stage anywhere.
        for (long employeeId : new long[] {
                salesStaffEmployeeId, orphanStaffEmployeeId, salesManagerEmployeeId}) {
            long requestId = submitFuneralRequest(employeeId);
            attachEvidence(requestId);

            service.approve(requestId, null, ceo());

            assertThat(statusOf(requestId)).isEqualTo("APPROVED");
            assertThat(approvedAmountOf(requestId)).isEqualByComparingTo(new BigDecimal("5000"));
            // NULL because no manager reviewed it. Payroll and audit read these columns, so a forged manager stamp here
            // would be a lie in the record, not a cosmetic detail.
            assertThat(managerApprovedByOf(requestId)).isNull();
            assertThat(ceoApprovedByOf(requestId)).isNotNull();
        }
    }

    @Test
    void ceoCannotApproveAnEvidenceRequiredTypeWithNothingAttached() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        // เงินช่วยเหลืองานศพ requires ใบมรณบัตร. Until 2026-08 there was no upload endpoint at all, so this money was
        // always approved with an empty document trail.
        assertThatThrownBy(() -> service.approve(requestId, null, ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("เอกสารหลักฐาน");
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
        assertThat(approvedAmountOf(requestId)).isNull();

        // ...and goes through once the evidence exists, so the gate is about evidence and not a blanket refusal.
        attachEvidence(requestId);
        service.approve(requestId, null, ceo());
        assertThat(statusOf(requestId)).isEqualTo("APPROVED");
    }

    // Managers must not read another employee's welfare claims, including members of their own division.

    /** ...and the employee themselves is of course unaffected. */
    @Test
    void theEmployeeStillSeesTheirOwnWelfareRequest() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        assertThat(service.list(
                employee(salesStaffEmployeeId, salesDivision),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null))
            .extracting(SpecialMoneyRequestDto::id)
            .containsExactly(requestId);
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

    /** Attaches a row to hr.special_money_request_attachment directly. AID_FUNERAL is an evidence-required type, so
     * approval now refuses without one -- these tests are about the authorization rules, and the evidence gate has
     * its own coverage. */
    private void attachEvidence(long requestId) {
        jdbc.update("""
            INSERT INTO hr.special_money_request_attachment
                (special_money_request_id, file_name, storage_path, mime_type, size_bytes)
            VALUES (:id, 'mor-ana-bat.pdf', '/tmp/test/mor-ana-bat.pdf', 'application/pdf', 1024)
            """, Map.of("id", requestId));
    }

    /** Read straight from the table, not the DTO: the columns are the thing payroll and audit read. */
    private Long managerApprovedByOf(long requestId) {
        return jdbc.queryForObject(
            "SELECT manager_approved_by FROM hr.special_money_request WHERE special_money_request_id = :id",
            Map.of("id", requestId), Long.class);
    }

    private Long ceoApprovedByOf(long requestId) {
        return jdbc.queryForObject(
            "SELECT ceo_approved_by FROM hr.special_money_request WHERE special_money_request_id = :id",
            Map.of("id", requestId), Long.class);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", hrEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    /** The CEO must carry a real employeeId: approve() resolves the actor before any gate runs, so a null one
     * throws "account not linked to an employee" first. */
    private UserPrincipal ceo() {
        return new UserPrincipal(2L, "ceo@glr.co.th", "ceo", "ceo", ceoEmployeeId, true,
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
        return insertEmployee(code, divisionId, reportsToEmployeeId, null);
    }

    /** positionNameTh is what makes an employee a ผู้จัดการ in the database, which is what
     * ManagerApproverRepository reads. Leaving it null models a non-manager. */
    private long insertEmployee(String code, Long divisionId, Long reportsToEmployeeId, String positionNameTh) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsToEmployeeId);
        params.put("hireDate", LocalDate.of(2015, 1, 1));
        params.put("positionId", positionNameTh == null ? null : insertPosition(code, positionNameTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, position_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :positionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private long insertPosition(String code, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Long.class);
    }
}
