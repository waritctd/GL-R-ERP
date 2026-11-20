package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Verifies approval-preview access and policy results through the real welfare service and PostgreSQL. */
class SpecialMoneyApprovalPreviewIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /** Same seeded figures {@link SpecialMoneyApproveDeadEndIntegrationTest} pins -- see V66. */
    private static final BigDecimal PREPROBATION_KIT_TOTAL = new BigDecimal("1960.00");

    private SpecialMoneyService service;
    private SpecialMoneyRepository repository;

    private long ceoEmployeeId;
    private long hrEmployeeId;
    private long managerEmployeeId;

    /** As SpecialMoneyApproveDeadEndIntegrationTest: a single SALES department row so UNIFORM_PREPROBATION_KIT
     * eligibility resolves. */
    private long salesSupportDepartmentId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
        service = new SpecialMoneyService(
            repository,
            new SpecialMoneyPolicyEvaluator(),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(), new CeoApproverRepository(jdbc));

        ceoEmployeeId = insertEmployee("CEO01", null, null);
        hrEmployeeId = insertEmployee("HR01", null, null);
        managerEmployeeId = insertEmployee("MGR01", null, null);
        salesSupportDepartmentId = jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES ('SALES', 'ฝ่ายขายทดสอบ', TRUE) RETURNING department_id
            """, Map.of(), Long.class);
    }

    // Wrong-way-round: only the CEO may preview.

    @Test
    void owningEmployeeHrManagerAndNoEmployeeRecordAllGet403() {
        long employeeId = insertStandardEligibleEmployee("EMP01");
        long requestId = submitRequest(
            "AID_FUNERAL", employeeId, new BigDecimal("8000"),
            LocalDate.of(2026, 7, 1), null, Map.of("relation", "parent"));

        for (UserPrincipal caller : List.of(
                employeeOwner(employeeId), hr(), manager(), noEmployeeRecord())) {
            assertThatThrownBy(() -> service.approvalPreview(requestId, caller))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }
        // Refused reads must not have side effects: the row itself is untouched.
        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    // Preview == guard: UNIFORM_PREPROBATION_KIT (fixed kit total, over-request).

    @Test
    void kitPreviewMatchesApproveGuardExactly() {
        long employeeId = insertPreprobationEligibleEmployee("KIT01");
        long requestId = submitRequest(
            "UNIFORM_PREPROBATION_KIT", employeeId, new BigDecimal("5000"),
            LocalDate.of(2026, 7, 1), null, Map.of());
        attachEvidence(requestId);

        SpecialMoneyApprovalPreviewDto preview = service.approvalPreview(requestId, ceo());
        assertThat(preview.requestedAmount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(preview.eligibleAmount()).isEqualByComparingTo(PREPROBATION_KIT_TOTAL);
        assertThat(preview.payrollMonth()).isNotNull();

        // A fresh, identically-eligible row for the over-by-0.01 refusal, so approving the first at exactly its own
        // previewed ceiling cannot change the second's.
        long overRequestId = submitRequest(
            "UNIFORM_PREPROBATION_KIT", insertPreprobationEligibleEmployee("KIT02"),
            new BigDecimal("5000"), LocalDate.of(2026, 7, 1), null, Map.of());
        attachEvidence(overRequestId);
        assertThat(service.approvalPreview(overRequestId, ceo()).eligibleAmount())
            .isEqualByComparingTo(PREPROBATION_KIT_TOTAL);

        // Exactly the previewed ceiling, no reason -- succeeds.
        service.approve(requestId, new ReviewSpecialMoneyRequest(null, preview.eligibleAmount(), null), ceo());
        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("APPROVED");
        assertThat(repository.findById(requestId).orElseThrow().approvedAmount())
            .isEqualByComparingTo(PREPROBATION_KIT_TOTAL);

        // One satang over the previewed ceiling, no reason -- refused.
        BigDecimal oneSatangOver = preview.eligibleAmount().add(new BigDecimal("0.01"));
        assertThatThrownBy(() -> service.approve(
                overRequestId, new ReviewSpecialMoneyRequest(null, oneSatangOver, null), ceo()))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(overRequestId).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    // --- helpers ----------------------------------------------------------

    private long submitRequest(
            String type, long employeeId, BigDecimal requestedAmount,
            LocalDate eventDate, LocalDate receiptDate, Map<String, String> detail) {
        SpecialMoneyRequestDto created = service.submit(
            type,
            new SubmitSpecialMoneyRequest(
                employeeId, eventDate, null, receiptDate, BigDecimal.ONE, requestedAmount,
                "Approval-preview test claim", detail),
            employeeOwner(employeeId));
        return created.id();
    }

    /** As {@link SpecialMoneyApproveDeadEndIntegrationTest#attachEvidence}. */
    private void attachEvidence(long requestId) {
        jdbc.update("""
            INSERT INTO hr.special_money_request_attachment
                (special_money_request_id, file_name, storage_path, mime_type, size_bytes)
            VALUES (:id, 'evidence.pdf', '/tmp/test/evidence.pdf', 'application/pdf', 1024)
            """, Map.of("id", requestId));
    }

    /** Eligible for UNIFORM_PREPROBATION_KIT's PREPROBATION_SALES_SUPPORT rule -- see
     * SpecialMoneyApproveDeadEndIntegrationTest#insertPreprobationEligibleEmployee. */
    private long insertPreprobationEligibleEmployee(String code) {
        return insertEmployee(code, salesSupportDepartmentId, LocalDate.now(BUSINESS_ZONE).minusDays(30));
    }

    /** Eligible under the standard probation rule (passed probation, active). */
    private long insertStandardEligibleEmployee(String code) {
        return insertEmployee(code, null, LocalDate.of(2015, 1, 1));
    }

    private long insertEmployee(String code, Long departmentId, LocalDate hireDate) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("departmentId", departmentId);
        params.put("hireDate", hireDate);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     department_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :departmentId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "ceo", "ceo", ceoEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(2L, "hr@glr.co.th", "hr", "hr", hrEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    /** A ฝ่าย manager -- welfare has no manager stage at all, so this must be refused exactly like any other
     * non-CEO caller (see SpecialMoneyService's class Javadoc). */
    private UserPrincipal manager() {
        return new UserPrincipal(3L, "mgr@glr.co.th", "mgr", "employee", managerEmployeeId, true,
            LocalDate.now(), false, null, true);
    }

    /** The employee whose claim it is -- may read/cancel/attach their own row, but may not preview their own
     * approval ceiling; only the CEO may. */
    private UserPrincipal employeeOwner(long employeeId) {
        return new UserPrincipal(4L, "owner@glr.co.th", "owner", "employee", employeeId, true,
            LocalDate.now(), false, null, false);
    }

    /** An account not yet linked to an employee row at all. */
    private UserPrincipal noEmployeeRecord() {
        return new UserPrincipal(5L, "unlinked@glr.co.th", "unlinked", "employee", null, true,
            LocalDate.now(), false, null, false);
    }
}
