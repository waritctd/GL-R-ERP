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

/**
 * THE AUTHZ EVIDENCE for {@code GET /api/special-money/{id}/approval-preview}, modelled on {@link
 * SpecialMoneyScopeIntegrationTest} and {@link SpecialMoneyApproveDeadEndIntegrationTest}: the REAL
 * {@link SpecialMoneyService}, the REAL {@link SpecialMoneyRepository} and the REAL
 * {@link SpecialMoneyPolicyEvaluator} against a real Postgres, with only {@link AuditService} and
 * {@link NotificationService} stubbed.
 *
 * <p>Two things this class exists to pin, per CLAUDE.md's "Permission changes must ship evidence":
 *
 * <ul>
 *   <li><b>Wrong-way-round first.</b> The owning employee, hr, a ฝ่าย manager, and a caller with no
 *       employee record at all must every one of them be refused -- {@link
 *       SpecialMoneyService#approvalPreview} is CEO-only, exactly like {@link
 *       SpecialMoneyService#approve}.
 *   <li><b>Preview == guard.</b> {@link SpecialMoneyService#approvalPreview} and {@code
 *       SpecialMoneyService#ceoApproveFrom} both read their ceiling from the same private {@code
 *       computeApprovalCeiling}, so for each of the three cap shapes {@link
 *       SpecialMoneyApproveDeadEndIntegrationTest} demonstrates, this class asserts the previewed
 *       {@code eligibleAmount} is the EXACT number {@code approve} accepts with no reason and
 *       refuses one satang above.
 * </ul>
 */
class SpecialMoneyApprovalPreviewIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /** Same seeded figures {@link SpecialMoneyApproveDeadEndIntegrationTest} pins -- see V66. */
    private static final BigDecimal PREPROBATION_KIT_TOTAL = new BigDecimal("1960.00");
    private static final BigDecimal FUNERAL_FIXED_CAP = new BigDecimal("5000.00");

    private static final String ALREADY_DECIDED_MESSAGE = "คำขอเงินพิเศษนี้ได้รับการพิจารณาไปแล้ว";

    private SpecialMoneyService service;
    private SpecialMoneyRepository repository;

    private long ceoEmployeeId;
    private long hrEmployeeId;
    private long managerEmployeeId;

    /** As {@link SpecialMoneyApproveDeadEndIntegrationTest}: a single SALES department row so
     * UNIFORM_PREPROBATION_KIT eligibility resolves. */
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

    // -------------------------------------------------------------------
    // Wrong-way-round: only the CEO may preview.
    // -------------------------------------------------------------------

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

    @Test
    void missingRequestIs404() {
        assertThatThrownBy(() -> service.approvalPreview(999_999L, ceo()))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void alreadyDecidedRequestIs409() {
        long employeeId = insertStandardEligibleEmployee("APP01");
        long requestId = submitRequest(
            "AID_FUNERAL", employeeId, new BigDecimal("3000"),
            LocalDate.of(2026, 7, 1), null, Map.of("relation", "parent"));
        attachEvidence(requestId);
        service.approve(requestId, new ReviewSpecialMoneyRequest(null, null, null), ceo());
        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("APPROVED");

        assertThatThrownBy(() -> service.approvalPreview(requestId, ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessage(ALREADY_DECIDED_MESSAGE)
            .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------
    // Preview == guard: UNIFORM_PREPROBATION_KIT (fixed kit total, over-request).
    // -------------------------------------------------------------------

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

        // A fresh, identically-eligible row for the over-by-0.01 refusal, so approving the first
        // at exactly its own previewed ceiling cannot change the second's.
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

    // -------------------------------------------------------------------
    // Preview == guard: MEDICAL, second claim after the first ate most of the cap.
    // -------------------------------------------------------------------

    @Test
    void secondMedicalClaimPreviewMatchesApproveGuardExactly() {
        long employeeId = insertStandardEligibleEmployee("MED01");
        LocalDate receiptDate = LocalDate.now(BUSINESS_ZONE).minusDays(5);

        long firstRequestId = submitRequest(
            "MEDICAL", employeeId, new BigDecimal("2000"), receiptDate, receiptDate, Map.of());
        long secondRequestId = submitRequest(
            "MEDICAL", employeeId, new BigDecimal("2000"), receiptDate, receiptDate, Map.of());
        // A third, identically-eligible claim so the over-by-0.01 refusal below is checked against
        // the SAME usage context as the previewed 1000 -- before secondRequestId's own approval
        // would otherwise shrink it further.
        long thirdRequestId = submitRequest(
            "MEDICAL", employeeId, new BigDecimal("2000"), receiptDate, receiptDate, Map.of());
        attachEvidence(firstRequestId);
        attachEvidence(secondRequestId);
        attachEvidence(thirdRequestId);

        // Nothing approved yet against this employee's ฿3,000 annual MEDICAL cap.
        service.approve(firstRequestId, new ReviewSpecialMoneyRequest(null, null, null), ceo());
        assertThat(repository.findById(firstRequestId).orElseThrow().status()).isEqualTo("APPROVED");

        // ฿2,000 already used, so the remaining ceiling for either untouched claim is ฿1,000.
        SpecialMoneyApprovalPreviewDto secondPreview = service.approvalPreview(secondRequestId, ceo());
        assertThat(secondPreview.requestedAmount()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(secondPreview.eligibleAmount()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(service.approvalPreview(thirdRequestId, ceo()).eligibleAmount())
            .isEqualByComparingTo(new BigDecimal("1000"));

        // One satang over ฿1,000, no reason, on the THIRD row -- refused. Checked BEFORE the second
        // row is touched, so this reads the same ceiling that was just previewed.
        BigDecimal oneSatangOver = secondPreview.eligibleAmount().add(new BigDecimal("0.01"));
        assertThatThrownBy(() -> service.approve(
                thirdRequestId, new ReviewSpecialMoneyRequest(null, oneSatangOver, null), ceo()))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(thirdRequestId).orElseThrow().status()).isEqualTo("SUBMITTED");

        // Exactly the previewed ฿1,000, no reason -- succeeds, on the second row.
        service.approve(
            secondRequestId, new ReviewSpecialMoneyRequest(null, secondPreview.eligibleAmount(), null), ceo());
        assertThat(repository.findById(secondRequestId).orElseThrow().status()).isEqualTo("APPROVED");
        assertThat(repository.findById(secondRequestId).orElseThrow().approvedAmount())
            .isEqualByComparingTo(new BigDecimal("1000"));
    }

    // -------------------------------------------------------------------
    // Preview == guard: AID_FUNERAL, flat cap with an unclamped over-cap submission.
    // -------------------------------------------------------------------

    @Test
    void aidFuneralOverCapPreviewMatchesApproveGuardExactly() {
        long employeeId = insertStandardEligibleEmployee("AID01");
        long requestId = submitRequest(
            "AID_FUNERAL", employeeId, new BigDecimal("8000"),
            LocalDate.of(2026, 7, 1), null, Map.of("relation", "parent"));
        attachEvidence(requestId);

        SpecialMoneyApprovalPreviewDto preview = service.approvalPreview(requestId, ceo());
        assertThat(preview.requestedAmount()).isEqualByComparingTo(new BigDecimal("8000"));
        assertThat(preview.eligibleAmount()).isEqualByComparingTo(FUNERAL_FIXED_CAP);

        long overRequestId = submitRequest(
            "AID_FUNERAL", insertStandardEligibleEmployee("AID02"), new BigDecimal("8000"),
            LocalDate.of(2026, 7, 1), null, Map.of("relation", "parent"));
        attachEvidence(overRequestId);
        assertThat(service.approvalPreview(overRequestId, ceo()).eligibleAmount())
            .isEqualByComparingTo(FUNERAL_FIXED_CAP);

        service.approve(requestId, new ReviewSpecialMoneyRequest(null, preview.eligibleAmount(), null), ceo());
        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("APPROVED");
        assertThat(repository.findById(requestId).orElseThrow().approvedAmount())
            .isEqualByComparingTo(FUNERAL_FIXED_CAP);

        BigDecimal oneSatangOver = preview.eligibleAmount().add(new BigDecimal("0.01"));
        assertThatThrownBy(() -> service.approve(
                overRequestId, new ReviewSpecialMoneyRequest(null, oneSatangOver, null), ceo()))
            .isInstanceOf(ApiException.class)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(overRequestId).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    // -------------------------------------------------------------------
    // Preview == guard: a legacy MANAGER_APPROVED row (pre-manager-stage-removal), not just SUBMITTED.
    // -------------------------------------------------------------------

    /**
     * {@link #approvalPreview} must work on a {@code MANAGER_APPROVED} row exactly as it does on a
     * {@code SUBMITTED} one -- {@link SpecialMoneyService}'s own class Javadoc and {@link
     * #ceoApproveFrom}'s Javadoc both describe {@code MANAGER_APPROVED} as reachable only by rows
     * written before welfare's manager stage was removed, cleared through the SAME ceiling logic.
     * Modelled on {@code SpecialMoneyScopeIntegrationTest#ceoCanStillClearALegacyManagerApprovedRow}
     * and its {@code parkInLegacyManagerApprovedState} helper (duplicated below rather than shared,
     * since it belongs to that test's own private fixture).
     */
    @Test
    void previewsALegacyManagerApprovedRowExactlyLikeApproveEnforces() {
        long employeeId = insertStandardEligibleEmployee("LEG01");
        long requestId = submitRequest(
            "AID_FUNERAL", employeeId, new BigDecimal("8000"),
            LocalDate.of(2026, 7, 1), null, Map.of("relation", "parent"));
        attachEvidence(requestId);
        parkInLegacyManagerApprovedState(requestId);
        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("MANAGER_APPROVED");

        SpecialMoneyApprovalPreviewDto preview = service.approvalPreview(requestId, ceo());
        assertThat(preview.requestedAmount()).isEqualByComparingTo(new BigDecimal("8000"));
        assertThat(preview.eligibleAmount()).isEqualByComparingTo(FUNERAL_FIXED_CAP);

        // The ceiling previewed on the legacy row must equal what approving THAT SAME legacy row
        // (via ceoApproveFrom's `from == MANAGER_APPROVED` branch) actually enforces.
        service.approve(requestId, new ReviewSpecialMoneyRequest(null, preview.eligibleAmount(), null), ceo());
        SpecialMoneyRequestDto approved = repository.findById(requestId).orElseThrow();
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedAmount()).isEqualByComparingTo(FUNERAL_FIXED_CAP);
    }

    // --- helpers ----------------------------------------------------------

    /** As {@code SpecialMoneyScopeIntegrationTest#parkInLegacyManagerApprovedState}: forces a row
     * into the state welfare's now-removed manager stage used to leave rows in, since nothing can
     * reach it through the service any more. {@code managerEmployeeId} stands in for whichever
     * ผู้จัดการ would have approved it under the old flow -- the column is display-only now (see
     * {@code SpecialMoneyRequestDto#managerEmployeeId}'s own comment), so which employee id lands
     * there does not affect this test. */
    private void parkInLegacyManagerApprovedState(long requestId) {
        jdbc.update("""
            UPDATE hr.special_money_request
               SET status = 'MANAGER_APPROVED',
                   manager_approved_by = :managerId,
                   manager_approved_at = now()
             WHERE special_money_request_id = :id
            """, Map.of("id", requestId, "managerId", managerEmployeeId));
    }

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

    /** Eligible for {@code UNIFORM_PREPROBATION_KIT}'s PREPROBATION_SALES_SUPPORT rule -- see
     * {@link SpecialMoneyApproveDeadEndIntegrationTest#insertPreprobationEligibleEmployee}. */
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

    /** A ฝ่าย manager -- welfare has no manager stage at all, so this must be refused exactly like
     * any other non-CEO caller (see {@link SpecialMoneyService}'s class Javadoc). */
    private UserPrincipal manager() {
        return new UserPrincipal(3L, "mgr@glr.co.th", "mgr", "employee", managerEmployeeId, true,
            LocalDate.now(), false, null, true);
    }

    /** The employee whose claim it is -- may read/cancel/attach their own row, but may not preview
     * their own approval ceiling; only the CEO may. */
    private UserPrincipal employeeOwner(long employeeId) {
        return new UserPrincipal(4L, "owner@glr.co.th", "owner", "employee", employeeId, true,
            LocalDate.now(), false, null, false);
    }

    /** An account not yet linked to an employee row at all. {@link
     * SpecialMoneyService#approvalPreview} never calls {@code requireEmployeeId} -- it is read-only
     * and does not need an actor employee id -- so this hits the same CEO-only role gate as any
     * other non-ceo caller, rather than the "account not linked" 400 {@code approve()} throws. */
    private UserPrincipal noEmployeeRecord() {
        return new UserPrincipal(5L, "unlinked@glr.co.th", "unlinked", "employee", null, true,
            LocalDate.now(), false, null, false);
    }
}
