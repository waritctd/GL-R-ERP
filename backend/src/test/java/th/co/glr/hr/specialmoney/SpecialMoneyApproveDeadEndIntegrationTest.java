package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** CHARACTERIZATION tests of CURRENT behaviour -- written to prove, against the real
 * SpecialMoneyService/SpecialMoneyRepository/SpecialMoneyPolicyEvaluator and a real Postgres, whether a
 * hypothesised production dead-end in welfare approval is actually reachable. */
class SpecialMoneyApproveDeadEndIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Same zone {@code SpecialMoneyService} evaluates "today" in -- see its {@code BUSINESS_ZONE}. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /** V66's seeded UNIFORM_PREPROBATION_KIT figures, unchanged since 2018-06-08 and with no effective_to: tshirt
     * 220 x3, trouser 300 x3, shoes 400 x1, no back-support belt. See V66__special_money_request_schema.sql. */
    private static final BigDecimal PREPROBATION_KIT_TOTAL = new BigDecimal("1960.00");

    /** V66's seeded {@code AID_FUNERAL} flat cap, unchanged since 2018-06-08. */
    private static final BigDecimal FUNERAL_FIXED_CAP = new BigDecimal("5000.00");

    private static final String CAP_OVERRIDE_REQUIRED_MESSAGE =
        "ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามนโยบายหรือเกินจำนวนที่พนักงานขอเบิก";

    private SpecialMoneyService service;
    private SpecialMoneyRepository repository;

    private long ceoEmployeeId;
    /** A single department row with source_code = 'SALES', on V130's seeded preprobation_kit_department_codes CSV
     * list ("SALES,SALES2"). */
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

        ceoEmployeeId = insertEmployee("CEO001", null, null, null);
        salesSupportDepartmentId = jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES ('SALES', 'ฝ่ายขายทดสอบ', TRUE) RETURNING department_id
            """, Map.of(), Long.class);
    }

    // Case 1: UNIFORM_PREPROBATION_KIT -- reachable via the UI.

    /** An eligible employee types an amount well above the fixed kit total; submit accepts it unclamped. An
     * empty-review approve -- once the CEO's only in-UI option -- then 400s and leaves the row stuck SUBMITTED. */
    @Test
    void preprobationKitOverRequestSubmitsCleanly_thenEmptyCeoReviewIsRefused_butApiWaysOutExist() {
        long salesSupportEmployeeId = insertPreprobationEligibleEmployee("KIT01");

        // --- submit: employee types 5000, well above the 1960 kit total. No violation is raised. ---
        long requestId = submitRequest(
            "UNIFORM_PREPROBATION_KIT", salesSupportEmployeeId, new BigDecimal("5000"),
            LocalDate.of(2026, 7, 1), null, Map.of());
        SpecialMoneyRequestDto submitted = repository.findById(requestId).orElseThrow();
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.requestedAmount()).isEqualByComparingTo(new BigDecimal("5000"));

        attachEvidence(requestId);

        // an empty review: approve({}) -- refused, row left untouched. Formerly the CEO's only in-UI action;
        // ApproveSpecialMoneyDialog.jsx no longer sends one.
        assertThatThrownBy(() -> service.approve(
                requestId, new ReviewSpecialMoneyRequest(null, null, null), ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessage(CAP_OVERRIDE_REQUIRED_MESSAGE)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("SUBMITTED");
        assertThat(repository.findById(requestId).orElseThrow().approvedAmount()).isNull();

        // way out #1 (same row -- the failed attempt above wrote nothing): approve at exactly the kit total, still no
        // reason needed, because that no longer exceeds the ceiling.
        service.approve(requestId, new ReviewSpecialMoneyRequest(null, PREPROBATION_KIT_TOTAL, null), ceo());
        SpecialMoneyRequestDto approvedAtKitTotal = repository.findById(requestId).orElseThrow();
        assertThat(approvedAtKitTotal.status()).isEqualTo("APPROVED");
        assertThat(approvedAtKitTotal.approvedAmount()).isEqualByComparingTo(PREPROBATION_KIT_TOTAL);

        // way out #2 (a fresh row, since the one above is already decided): a non-blank cap-override reason lets the
        // CEO approve the full 5000 the employee typed.
        long secondEmployeeId = insertPreprobationEligibleEmployee("KIT02");
        long secondRequestId = submitRequest(
            "UNIFORM_PREPROBATION_KIT", secondEmployeeId, new BigDecimal("5000"),
            LocalDate.of(2026, 7, 1), null, Map.of());
        attachEvidence(secondRequestId);

        service.approve(
            secondRequestId,
            new ReviewSpecialMoneyRequest(null, null, "อนุมัติเกินเพดานตามดุลยพินิจ CEO"),
            ceo());
        SpecialMoneyRequestDto approvedByOverride = repository.findById(secondRequestId).orElseThrow();
        assertThat(approvedByOverride.status()).isEqualTo("APPROVED");
        assertThat(approvedByOverride.approvedAmount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(approvedByOverride.capOverrideReason()).isEqualTo("อนุมัติเกินเพดานตามดุลยพินิจ CEO");
    }

    // Case 2: MEDICAL -- two pending claims that individually fit the cap, but not together.

    @Test
    void twoMedicalClaimsEachFitTheCapAlone_secondCeoApprovalDrifts_thenHasAnApiWayOut() {
        long employeeId = insertStandardEligibleEmployee("MED01");
        LocalDate receiptDate = LocalDate.now(BUSINESS_ZONE).minusDays(5);

        // Both submit cleanly: at submit time "used so far" only counts APPROVED rows, and nothing is approved yet --
        // so 2000 against a 3000 cap succeeds twice in a row.
        long firstRequestId = submitRequest(
            "MEDICAL", employeeId, new BigDecimal("2000"), receiptDate, receiptDate, Map.of());
        long secondRequestId = submitRequest(
            "MEDICAL", employeeId, new BigDecimal("2000"), receiptDate, receiptDate, Map.of());
        assertThat(repository.findById(firstRequestId).orElseThrow().status()).isEqualTo("SUBMITTED");
        assertThat(repository.findById(secondRequestId).orElseThrow().status()).isEqualTo("SUBMITTED");

        attachEvidence(firstRequestId);
        attachEvidence(secondRequestId);

        // The CEO approves the first with an empty review: nothing approved yet, so 2000 does not exceed the 3000
        // ceiling. Succeeds outright.
        service.approve(firstRequestId, new ReviewSpecialMoneyRequest(null, null, null), ceo());
        SpecialMoneyRequestDto firstApproved = repository.findById(firstRequestId).orElseThrow();
        assertThat(firstApproved.status()).isEqualTo("APPROVED");
        assertThat(firstApproved.approvedAmount()).isEqualByComparingTo(new BigDecimal("2000"));

        // The CEO approves the second, also with an empty review -- now 2000 already used, so the remaining balance is
        // 1000 and the second row's own 2000 exceeds it. Refused, row untouched.
        assertThatThrownBy(() -> service.approve(
                secondRequestId, new ReviewSpecialMoneyRequest(null, null, null), ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessage(CAP_OVERRIDE_REQUIRED_MESSAGE)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(secondRequestId).orElseThrow().status()).isEqualTo("SUBMITTED");
        assertThat(repository.findById(secondRequestId).orElseThrow().approvedAmount()).isNull();

        // Way out: the API lets the CEO approve exactly the 1000 remaining balance instead, still with no reason,
        // because that no longer exceeds the (now-drifted) ceiling.
        service.approve(secondRequestId, new ReviewSpecialMoneyRequest(null, new BigDecimal("1000"), null), ceo());
        SpecialMoneyRequestDto secondApproved = repository.findById(secondRequestId).orElseThrow();
        assertThat(secondApproved.status()).isEqualTo("APPROVED");
        assertThat(secondApproved.approvedAmount()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    // Case 3: FIXED_AID over-cap -- direct API only (the frontend hardcodes the cap).

    @Test
    void fixedAidOverCapSubmitsCleanly_thenEmptyCeoReviewIsRefused() {
        long employeeId = insertStandardEligibleEmployee("AID01");

        // AID_FUNERAL's cap rule (evaluateFixedAid) only checks the relation and, for wedding/ordination, a
        // once-per-lifetime count -- it never flags an over-cap amount. 8000 against a 5000 cap submits with no
        // violation, stored unclamped.
        long requestId = submitRequest(
            "AID_FUNERAL", employeeId, new BigDecimal("8000"),
            LocalDate.of(2026, 7, 1), null, Map.of("relation", "parent"));
        SpecialMoneyRequestDto submitted = repository.findById(requestId).orElseThrow();
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.requestedAmount()).isEqualByComparingTo(new BigDecimal("8000"));

        attachEvidence(requestId);

        assertThatThrownBy(() -> service.approve(
                requestId, new ReviewSpecialMoneyRequest(null, null, null), ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessage(CAP_OVERRIDE_REQUIRED_MESSAGE)
            .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("SUBMITTED");
        assertThat(repository.findById(requestId).orElseThrow().approvedAmount()).isNull();

        // Way out, for completeness: the fixed 5000 cap itself needs no reason.
        service.approve(requestId, new ReviewSpecialMoneyRequest(null, FUNERAL_FIXED_CAP, null), ceo());
        SpecialMoneyRequestDto approved = repository.findById(requestId).orElseThrow();
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedAmount()).isEqualByComparingTo(FUNERAL_FIXED_CAP);
    }

    // --- helpers ----------------------------------------------------------

    private long submitRequest(
            String type, long employeeId, BigDecimal requestedAmount,
            LocalDate eventDate, LocalDate receiptDate, Map<String, String> detail) {
        SpecialMoneyRequestDto created = service.submit(
            type,
            new SubmitSpecialMoneyRequest(
                employeeId, eventDate, null, receiptDate, BigDecimal.ONE, requestedAmount,
                "Characterization test claim", detail),
            employeeOwner(employeeId));
        return created.id();
    }

    /** As {@code SpecialMoneyScopeIntegrationTest#attachEvidence}. */
    private void attachEvidence(long requestId) {
        jdbc.update("""
            INSERT INTO hr.special_money_request_attachment
                (special_money_request_id, file_name, storage_path, mime_type, size_bytes)
            VALUES (:id, 'evidence.pdf', '/tmp/test/evidence.pdf', 'application/pdf', 1024)
            """, Map.of("id", requestId));
    }

    /** An employee eligible for UNIFORM_PREPROBATION_KIT's PREPROBATION_SALES_SUPPORT rule: hired more than the
     * required 7 days ago, in a department whose source_code is on V130's seeded preprobation_kit_department_codes
     * list ("SALES,SALES2"). */
    private long insertPreprobationEligibleEmployee(String code) {
        return insertEmployee(
            code, salesSupportDepartmentId, null, LocalDate.now(BUSINESS_ZONE).minusDays(30));
    }

    /** An employee eligible under the STANDARD probation rule (passed probation, active). */
    private long insertStandardEligibleEmployee(String code) {
        return insertEmployee(code, null, null, LocalDate.of(2015, 1, 1));
    }

    private long insertEmployee(String code, Long departmentId, Long positionId, LocalDate hireDate) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("departmentId", departmentId);
        params.put("positionId", positionId);
        params.put("hireDate", hireDate);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     department_id, position_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :departmentId, :positionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "ceo", "ceo", ceoEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    /** The employee submitting their own claim -- welfare has no submit-on-behalf any more. */
    private UserPrincipal employeeOwner(long employeeId) {
        return new UserPrincipal(2L, "owner@glr.co.th", "owner", "employee", employeeId, true,
            LocalDate.now(), false, null, false);
    }
}
