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
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** SpecialMoneyService's annual welfare caps (MEDICAL's ฿ balance, UNIFORM_ANNUAL's once-a-year count) used to
 * be keyed on event_date -- employee-supplied and unbounded (SubmitSpecialMoneyHttpRequest marks it @NotNull
 * only; V66 has no future-date constraint). */
class SpecialMoneyCapYearIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private SpecialMoneyService service;
    private SpecialMoneyRepository repository;
    private long employeeId;
    private long ceoEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
        AppProperties appProperties = new AppProperties();
        appProperties.getSpecialMoney().setPayrollCutoffDay(31);
        service = new SpecialMoneyService(
            repository,
            new SpecialMoneyPolicyEvaluator(),
            mock(AuditService.class),
            mock(NotificationService.class),
            appProperties, new CeoApproverRepository(jdbc));
        employeeId = insertEmployee("SMR-CAP");
        ceoEmployeeId = insertEmployee("SMR-CEO");
    }

    // MEDICAL -- ฿3,000/yr cap (V66 seed), exploited via a future event_date

    @Test
    void secondMedicalClaimWithFutureEventDateIsRefusedAtSubmit() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // First claim: an honest, current-year claim for the FULL ฿3,000 cap. Approved.
        long firstId = submitAndApproveMedical(today, today.minusDays(5), new BigDecimal("3000"));
        assertThat(statusOf(firstId)).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(firstId)).isEqualByComparingTo("3000");

        // Second claim: the task's concrete repro. event_date pushed a year into the future; receiptDate stays
        // honestly recent (so the one-month receipt window still passes).
        SubmitSpecialMoneyRequest second = medicalRequest(
            today.plusYears(1).withMonth(6).withDayOfMonth(1), today.minusDays(3), new BigDecimal("3000"));

        assertThatThrownBy(() -> service.submit("MEDICAL", second, employeeOwner()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("เกินวงเงินคงเหลือ");

        // Assert the STORED state, not just the exception: no second row was created, and this year's usage is still
        // exactly what the first claim used.
        assertThat(requestCountFor(employeeId, "MEDICAL")).isEqualTo(1);
        SpecialMoneyUsageDto usage = service.usage(employeeId, today.getYear(), ceo());
        assertThat(usage.approvedAmountThisYearByType().get("MEDICAL")).isEqualByComparingTo("3000");
    }

    @Test
    void secondMedicalClaimSubmittedBeforeTheFirstIsDecidedIsCaughtAtApproval() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // Both submitted while NEITHER is decided: MEDICAL's submit-time gate only sums APPROVED amounts, so two
        // concurrent in-flight claims both see ฿0 used and both get created.
        SpecialMoneyRequestDto first = service.submit(
            "MEDICAL", medicalRequest(today, today.minusDays(5), new BigDecimal("3000")), employeeOwner());
        SpecialMoneyRequestDto second = service.submit(
            "MEDICAL",
            medicalRequest(today.plusYears(1).withMonth(6).withDayOfMonth(1), today.minusDays(3), new BigDecimal("3000")),
            employeeOwner());
        attachEvidence(first.id());
        attachEvidence(second.id());

        service.approve(first.id(), null, ceo());
        assertThat(statusOf(first.id())).isEqualTo("APPROVED");

        // Under the bug, ceoApproveFrom keyed the recheck on existing.eventDate().getYear() (next year), found ฿0 used
        // THERE, and approved silently with no override demanded.
        assertThatThrownBy(() -> service.approve(second.id(), null, ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("เหตุผล");
        assertThat(statusOf(second.id())).isEqualTo("SUBMITTED");
        assertThat(approvedAmountOf(second.id())).isNull();

        // ...and it is a real gate, not a blanket refusal: supplying the reason the message demands lets the CEO make
        // an informed, ON-THE-RECORD override instead of a silent double-pay.
        ReviewSpecialMoneyRequest withReason =
            new ReviewSpecialMoneyRequest(null, new BigDecimal("3000"), "Owner-approved exception");
        service.approve(second.id(), withReason, ceo());
        assertThat(statusOf(second.id())).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(second.id())).isEqualByComparingTo("3000");
        assertThat(capOverrideReasonOf(second.id())).isEqualTo("Owner-approved exception");
    }

    // helpers

    private long submitAndApproveMedical(LocalDate eventDate, LocalDate receiptDate, BigDecimal amount) {
        SpecialMoneyRequestDto created =
            service.submit("MEDICAL", medicalRequest(eventDate, receiptDate, amount), employeeOwner());
        attachEvidence(created.id());
        service.approve(created.id(), null, ceo());
        return created.id();
    }

    private SubmitSpecialMoneyRequest medicalRequest(LocalDate eventDate, LocalDate receiptDate, BigDecimal amount) {
        return new SubmitSpecialMoneyRequest(
            employeeId, eventDate, null, receiptDate, BigDecimal.ONE, amount, "Medical claim", Map.of());
    }

    private void attachEvidence(long requestId) {
        jdbc.update("""
            INSERT INTO hr.special_money_request_attachment
                (special_money_request_id, file_name, storage_path, mime_type, size_bytes)
            VALUES (:id, 'evidence.pdf', '/tmp/test/evidence.pdf', 'application/pdf', 1024)
            """, Map.of("id", requestId));
    }

    private String statusOf(long requestId) {
        return repository.findById(requestId).orElseThrow().status();
    }

    private BigDecimal approvedAmountOf(long requestId) {
        return repository.findById(requestId).orElseThrow().approvedAmount();
    }

    private String capOverrideReasonOf(long requestId) {
        return repository.findById(requestId).orElseThrow().capOverrideReason();
    }

    private long requestCountFor(long employeeId, String requestType) {
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.special_money_request
             WHERE employee_id = :employeeId AND request_type = :requestType
            """, Map.of("employeeId", employeeId, "requestType", requestType), Long.class);
        return count == null ? 0 : count;
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, hire_date, is_active)
            VALUES (:code, 'ทดสอบ', :code, :hireDate, TRUE)
            RETURNING employee_id
            """, Map.of("code", code, "hireDate", LocalDate.of(2015, 1, 1)), Long.class);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(
            1L, "ceo@glr.co.th", "ceo", "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employeeOwner() {
        return new UserPrincipal(
            2L, "owner@glr.co.th", "owner", "employee", employeeId, true, LocalDate.now(), false, null, false);
    }
}
