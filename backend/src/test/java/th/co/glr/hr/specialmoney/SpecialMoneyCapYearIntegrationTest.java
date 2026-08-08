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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * P0 fix (fix/welfare-cap-year-bypass): {@code SpecialMoneyService}'s annual welfare caps
 * (MEDICAL's ฿ balance, UNIFORM_ANNUAL's once-a-year count) used to be keyed on {@code event_date}
 * -- employee-supplied and unbounded ({@link SubmitSpecialMoneyHttpRequest} marks it {@code
 * @NotNull} only; V66 has no future-date constraint). Filing a request with {@code event_date} in
 * a year nothing had been approved against yet always produced "฿0 used so far", defeating the cap
 * regardless of how much had actually been approved and paid in the calendar year the money
 * actually lands in (see {@code SpecialMoneyService#usageYear}'s Javadoc for the full fix).
 *
 * <p>Wires the REAL {@link SpecialMoneyService}, the REAL {@link SpecialMoneyRepository} and the
 * REAL {@link SpecialMoneyPolicyEvaluator} against a real Postgres database -- same pattern as
 * {@code SpecialMoneyScopeIntegrationTest} -- because the bug lived partly in {@code
 * SpecialMoneyRepository}'s SQL: a Mockito-stubbed repository would happily "pass" while the SQL
 * underneath still summed the wrong column. Every case here is written the WRONG way round per
 * CLAUDE.md: can a caller who manipulates {@code event_date} still extract more money or more
 * claims than the annual cap allows -- and every assertion reads back the STORED row (status,
 * {@code approvedAmount}, {@code capOverrideReason}), never just whether a call threw.
 *
 * <p>{@code payrollCutoffDay} is pinned to 31 so {@code payrollMonth} always falls in the same
 * month (and therefore the same year) as "today", regardless of which day of the month this suite
 * happens to run on -- mirrors {@code SpecialMoneyServiceTest}'s same defensive pattern.
 */
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
            appProperties);
        employeeId = insertEmployee("SMR-CAP");
        ceoEmployeeId = insertEmployee("SMR-CEO");
    }

    // -----------------------------------------------------------------------
    // MEDICAL -- ฿3,000/yr cap (V66 seed), exploited via a future event_date
    // -----------------------------------------------------------------------

    @Test
    void secondMedicalClaimWithFutureEventDateIsRefusedAtSubmit() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // First claim: an honest, current-year claim for the FULL ฿3,000 cap. Approved.
        long firstId = submitAndApproveMedical(today, today.minusDays(5), new BigDecimal("3000"));
        assertThat(statusOf(firstId)).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(firstId)).isEqualByComparingTo("3000");

        // Second claim: the task's concrete repro. event_date pushed a year into the future;
        // receiptDate stays honestly recent (so the one-month receipt window still passes).
        // Under the bug, usageYear followed event_date's year, findUsage(emp, nextYear) always
        // came back ฿0 used there, and this sailed through for a SECOND ฿3,000 in the same
        // calendar year's payroll.
        SubmitSpecialMoneyRequest second = medicalRequest(
            today.plusYears(1).withMonth(6).withDayOfMonth(1), today.minusDays(3), new BigDecimal("3000"));

        assertThatThrownBy(() -> service.submit("MEDICAL", second, employeeOwner()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("เกินวงเงินคงเหลือ");

        // Assert the STORED state, not just the exception: no second row was created, and this
        // year's usage is still exactly what the first claim used.
        assertThat(requestCountFor(employeeId, "MEDICAL")).isEqualTo(1);
        SpecialMoneyUsageDto usage = service.usage(employeeId, today.getYear(), ceo());
        assertThat(usage.approvedAmountThisYearByType().get("MEDICAL")).isEqualByComparingTo("3000");
    }

    @Test
    void secondMedicalClaimSubmittedBeforeTheFirstIsDecidedIsCaughtAtApproval() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // Both submitted while NEITHER is decided: MEDICAL's submit-time gate only sums APPROVED
        // amounts, so two concurrent in-flight claims both see ฿0 used and both get created. The
        // APPROVAL-time recheck (SpecialMoneyService.java:190 before the fix) is what has to catch
        // the second one -- this is the test that specifically exercises that call site, since the
        // submit-time gate alone (covered above) cannot: both claims pass it.
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

        // Under the bug, ceoApproveFrom keyed the recheck on existing.eventDate().getYear() (next
        // year), found ฿0 used THERE, and approved silently with no override demanded. Fixed, it
        // keys on THIS approval's own payrollMonth (this year), sees the ฿3,000 the first claim
        // already used, and refuses without a written reason.
        assertThatThrownBy(() -> service.approve(second.id(), null, ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("เหตุผล");
        assertThat(statusOf(second.id())).isEqualTo("SUBMITTED");
        assertThat(approvedAmountOf(second.id())).isNull();

        // ...and it is a real gate, not a blanket refusal: supplying the reason the message demands
        // lets the CEO make an informed, ON-THE-RECORD override instead of a silent double-pay.
        ReviewSpecialMoneyRequest withReason =
            new ReviewSpecialMoneyRequest(null, new BigDecimal("3000"), "Owner-approved exception");
        service.approve(second.id(), withReason, ceo());
        assertThat(statusOf(second.id())).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(second.id())).isEqualByComparingTo("3000");
        assertThat(capOverrideReasonOf(second.id())).isEqualTo("Owner-approved exception");
    }

    // -----------------------------------------------------------------------
    // UNIFORM_ANNUAL -- once/yr guard, exploited via a future event_date
    // -----------------------------------------------------------------------

    @Test
    void secondUniformAnnualClaimWithFutureEventDateIsRefused() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int thisYear = today.getYear();
        long firstId = submitAndApproveUniform(LocalDate.of(thisYear, 6, 10));
        assertThat(statusOf(firstId)).isEqualTo("APPROVED");

        // Same trick as MEDICAL: event_date in NEXT year's June still satisfies "month == JUNE",
        // and under the bug also satisfied the once-per-year COUNT, since that count was keyed on
        // event_date's year too (SpecialMoneyRepository.java:140).
        SubmitSpecialMoneyRequest second = uniformRequest(LocalDate.of(thisYear + 1, 6, 10));

        assertThatThrownBy(() -> service.submit("UNIFORM_ANNUAL", second, employeeOwner()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ปีละ 1 ครั้ง");
        assertThat(requestCountFor(employeeId, "UNIFORM_ANNUAL")).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // The FIRST claim carries the mismatched date, not the second -- proves the
    // SpecialMoneyRepository SQL column choice is load-bearing, not just SpecialMoneyService's
    // choice of which year to ask for. The two cases above submit an honest first claim and an
    // adversarial second one; every date on that honest first claim (event_date, payrollMonth,
    // requestedAt) lands in the SAME year, so reverting EITHER findUsage SQL column back to
    // event_date is invisible to them -- the honest row is still found under its own event_date,
    // by coincidence. These reverse which claim is adversarial to close that gap.
    // -----------------------------------------------------------------------

    @Test
    void firstMedicalClaimFiledWithAForwardEventDateStillConsumesTheYearItIsActuallyApprovedIn() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // Filed and approved TODAY (so payrollMonth is THIS year and pays via THIS year's payroll),
        // but its OWN event_date claims next year. evaluateMedical never reads event_date, so
        // nothing in the evaluator blocks this -- it is exactly the shape SubmitSpecialMoneyHttpRequest
        // leaves open on purpose (see its Javadoc).
        long firstId = submitAndApproveMedical(
            today.plusYears(1).withMonth(6).withDayOfMonth(1), today.minusDays(5), new BigDecimal("3000"));
        assertThat(statusOf(firstId)).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(firstId)).isEqualByComparingTo("3000");

        // Second claim is now the HONEST one: event_date = today, matching the year its own payroll
        // will actually land in. If findUsage's amount query keyed on event_date, the first row's
        // ฿3,000 (event_date next year) would be invisible to a "how much used THIS year" query,
        // and this would sail through for a second ฿3,000 paid in the SAME calendar year's payroll.
        SubmitSpecialMoneyRequest second = medicalRequest(today, today.minusDays(3), new BigDecimal("3000"));

        assertThatThrownBy(() -> service.submit("MEDICAL", second, employeeOwner()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("เกินวงเงินคงเหลือ");

        assertThat(requestCountFor(employeeId, "MEDICAL")).isEqualTo(1);
        SpecialMoneyUsageDto usage = service.usage(employeeId, today.getYear(), ceo());
        assertThat(usage.approvedAmountThisYearByType().get("MEDICAL")).isEqualByComparingTo("3000");
    }

    @Test
    void firstUniformAnnualClaimFiledWithAForwardEventDateStillConsumesTheYearItIsActuallySubmittedIn() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int thisYear = today.getYear();
        // event_date NEXT year's June -- "month == JUNE" never checks the year, so this passes the
        // claim-window rule -- but submitted (and approved) TODAY, so requestedAt is THIS year.
        long firstId = submitAndApproveUniform(LocalDate.of(thisYear + 1, 6, 10));
        assertThat(statusOf(firstId)).isEqualTo("APPROVED");

        // Second claim is the honest one: event_date THIS year's June. If the count query keyed on
        // event_date, the first row (event_date next year) would be invisible to a "filed THIS
        // year" count, and a second uniform claim would be allowed in the same real year.
        SubmitSpecialMoneyRequest second = uniformRequest(LocalDate.of(thisYear, 6, 10));

        assertThatThrownBy(() -> service.submit("UNIFORM_ANNUAL", second, employeeOwner()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ปีละ 1 ครั้ง");
        assertThat(requestCountFor(employeeId, "UNIFORM_ANNUAL")).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Legitimate backdating and normal in-cap claims must keep working
    // -----------------------------------------------------------------------

    @Test
    void backdatedMedicalReceiptWithinTheOneMonthWindowStillSucceeds() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // A receipt from a few weeks ago, filed today -- the ordinary "I was busy, filing late"
        // case the welfare document's one-month receipt window exists to allow. Must not be
        // refused just because the fix stopped trusting employee-supplied dates for the cap YEAR.
        long id = submitAndApproveMedical(today.minusDays(20), today.minusDays(20), new BigDecimal("1200"));

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(id)).isEqualByComparingTo("1200");
    }

    @Test
    void normalWithinCapMedicalClaimStillSucceeds() {
        // The fix must not turn into "deny everything": a fresh employee filing a single,
        // ordinary, within-cap claim still goes straight through.
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        long id = submitAndApproveMedical(today, today.minusDays(1), new BigDecimal("1500"));

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(id)).isEqualByComparingTo("1500");
        SpecialMoneyUsageDto usage = service.usage(employeeId, today.getYear(), ceo());
        assertThat(usage.approvedAmountThisYearByType().get("MEDICAL")).isEqualByComparingTo("1500");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private long submitAndApproveMedical(LocalDate eventDate, LocalDate receiptDate, BigDecimal amount) {
        SpecialMoneyRequestDto created =
            service.submit("MEDICAL", medicalRequest(eventDate, receiptDate, amount), employeeOwner());
        attachEvidence(created.id());
        service.approve(created.id(), null, ceo());
        return created.id();
    }

    private long submitAndApproveUniform(LocalDate eventDate) {
        SpecialMoneyRequestDto created = service.submit("UNIFORM_ANNUAL", uniformRequest(eventDate), employeeOwner());
        attachEvidence(created.id());
        service.approve(created.id(), null, ceo());
        return created.id();
    }

    private SubmitSpecialMoneyRequest medicalRequest(LocalDate eventDate, LocalDate receiptDate, BigDecimal amount) {
        return new SubmitSpecialMoneyRequest(
            employeeId, eventDate, null, receiptDate, BigDecimal.ONE, amount, "Medical claim", Map.of());
    }

    /** TAILORED mode: no receipt-month constraint, just event_date's month (June) and piece count. */
    private SubmitSpecialMoneyRequest uniformRequest(LocalDate eventDate) {
        return new SubmitSpecialMoneyRequest(
            employeeId, eventDate, null, null, BigDecimal.ONE, new BigDecimal("1300"), "Annual uniform",
            Map.of("uniformMode", "TAILORED", "shirtCount", "2", "trouserCount", "2"));
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
