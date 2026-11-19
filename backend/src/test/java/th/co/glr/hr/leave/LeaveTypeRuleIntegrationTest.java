package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** §5 leave-rules-as-data: real-Postgres coverage of the per-leave-type rule columns and their enforcement in
 * LeaveService#submit -- the SQL is exactly what Mockito-based LeaveServiceTest cannot reach; see that class
 * for the gate-by-gate unit coverage. */
class LeaveTypeRuleIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- matches the fixed clock used by the other leave integration tests, so
    // every date below is comfortably past every seeded advance-notice value
    // (SICK/MATERNITY/MILITARY/LEAVE_WITHOUT_PAY=0, PERSONAL=1, VACATION=3) unless a test is specifically about notice.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    @Test
    void aNinetyEightDayMaternityRequestSplitsIntoFortyFivePaidAndFiftyThreeUnpaidDays() {
        long employeeId = insertEmployee("MAT-001", LocalDate.parse("2015-01-01"));

        // Mon 2026-01-05 .. Sun 2026-04-12: exactly 98 CALENDAR days (verified independently of
        // LeaveDayMath: 27 remaining days of January + 28 of February + 31 of March + 12 of April =
        // 98), INCLUDING every Saturday/Sunday inside the range -- §5.4 MATERNITY calendar-day
        // counting. Counted as WORKING days this range would be roughly 70, not 98.
        // MATERNITY's 98-day quota fully covers the
        // request, but its 45-day paid_days_cap (bounding CALENDAR days) is what actually
        // determines the split -- this is the gate the MATERNITY row exists to prove.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "MATERNITY", "2026-01-05", "2026-04-12"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.totalDays()).isEqualByComparingTo("98.00");
        assertThat(result.paidDays()).isEqualByComparingTo("45.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("53.00");
        // Review fix regression guard: quotaRemainingAfter tracks QUOTA consumption (98-day request against a 98-day
        // quota -> 0 remaining), NOT the paid-cap-narrowed paidDays figure.
        assertThat(result.quotaRemainingAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    void aMaternityRequestCountsCalendarDaysButAnIdenticalSickRequestCountsOnlyWorkingDays() {
        // §5.4 MATERNITY calendar-day counting: the vacuous-fixture guard, through the REAL
        // repository/SQL (LeaveServiceTest's Mockito-level companion proves the same thing
        // against a faked LeaveTypeDto). Mon 2026-07-13 .. Sun 2026-07-19 -- a full week, both
        // weekend days included -- must count 7 for MATERNITY (every day) but only 5 for SICK
        // (Mon-Fri only).
        //
        // The SICK side is submitted without an attachment. Per §5.1 this is the employee's
        // FIRST certificate-less occasion this month, tolerated (seeded 3/month), so it is APPROVED
        // -- irrelevant either way to what this test proves, since totalDays is computed BEFORE the
        // auto-reject gates run and stored unconditionally on every submission (LeaveService#submit).
        long maternityEmployeeId = insertEmployee("MAT-CAL-001", LocalDate.parse("2015-01-01"));
        long sickEmployeeId = insertEmployee("SICK-CAL-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto maternityResult = leaveService.submit(
            submitRequest(maternityEmployeeId, "MATERNITY", "2026-07-13", "2026-07-19"),
            employee(maternityEmployeeId));
        LeaveRequestDto sickResult = leaveService.submit(
            submitRequest(sickEmployeeId, "SICK", "2026-07-13", "2026-07-19"),
            employee(sickEmployeeId));

        assertThat(maternityResult.status()).isEqualTo("SUBMITTED");
        assertThat(maternityResult.totalDays()).isEqualByComparingTo("7.00");
        assertThat(sickResult.status()).isEqualTo("SUBMITTED");
        assertThat(sickResult.totalDays()).isEqualByComparingTo("5.00");
        // The critical negative assertion: the two must NOT be equal on this identical date range.
        assertThat(sickResult.totalDays()).isNotEqualByComparingTo(maternityResult.totalDays());
    }

    @Test
    void vacationQuotaIsProratedUnderOneYearOfServiceAndFullAfterOneYear() {
        // §5.3's parenthetical grants a PRO-RATED quota to an employee under a
        // year of service, not an outright refusal -- min_service_months=12 must not act as a
        // hard eligibility floor for VACATION. Both sides are asserted in ONE
        // test, same request shape, so it cannot pass by only ever constructing the easy (>1 year)
        // case.
        long underOneYear = insertEmployee("VAC-PRORATE-001", LocalDate.parse("2026-01-13")); // 6 months before the request below
        long overOneYear = insertEmployee("VAC-PRORATE-002", LocalDate.parse("2015-01-01"));

        // 6 completed months of service -> prorated quota = 6.00 * 6/12 = 3.00 (rounded to the
        // nearest 0.5 day -- see LeaveService#employeeAnnualQuota's Javadoc for the formula and its
        // interpretation caveat). Mon 2026-07-13 .. Thu 2026-07-16 is 4 working days: exactly 3 of
        // them paid, 1 unpaid -- this pins the 3.00 prorated figure exactly (not zero, which an
        // outright refusal would give, and not the full 6.00 that skipping pro-ration would
        // wrongly grant from day one).
        LeaveRequestDto underOneYearResult = leaveService.submit(
            submitRequest(underOneYear, "VACATION", "2026-07-13", "2026-07-16"),
            employee(underOneYear));
        assertThat(underOneYearResult.status()).isEqualTo("SUBMITTED");
        assertThat(underOneYearResult.totalDays()).isEqualByComparingTo("4.00");
        assertThat(underOneYearResult.paidDays()).isEqualByComparingTo("3.00");
        assertThat(underOneYearResult.unpaidDays()).isEqualByComparingTo("1.00");
        assertThat(underOneYearResult.quotaRemainingAfter()).isEqualByComparingTo("0.00");

        // Wrong-way-round complement, IDENTICAL request shape: an employee comfortably past a year of service gets the
        // FULL 6.00-day quota, so all 4 working days are paid.
        LeaveRequestDto overOneYearResult = leaveService.submit(
            submitRequest(overOneYear, "VACATION", "2026-07-13", "2026-07-16"),
            employee(overOneYear));
        assertThat(overOneYearResult.status()).isEqualTo("SUBMITTED");
        assertThat(overOneYearResult.paidDays()).isEqualByComparingTo("4.00");
        assertThat(overOneYearResult.unpaidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void theDatabaseItselfRefusesASecondLiveOrdinationClaimEvenBypassingTheJavaCheck() {
        // The test above proves the NORMAL path (LeaveService's Java-level pre-check). If this constraint were ever
        // dropped or its WHERE clause narrowed, this is the test that would catch it; LeaveServiceTest's
        // DuplicateKeyException-catch test only proves the CATCH works, not that the INDEX exists.
        long employeeId = insertEmployee("ORD-RACE-001", LocalDate.parse("2015-01-01"));

        long firstId = createOrdinationRequestDirectly(employeeId, "2026-07-13", "2026-07-14", LeaveStatus.SUBMITTED);
        assertThat(firstId).isPositive();

        assertThatThrownBy(() ->
            createOrdinationRequestDirectly(employeeId, "2026-08-10", "2026-08-11", LeaveStatus.SUBMITTED))
            .isInstanceOf(DataAccessException.class);
    }

    // §5.2 leave purpose + wedding cap (V125). Real-Postgres coverage of purpose_code's CHECK constraint and the
    // wedding-leave cap enforcement in LeaveService#autoRejectNote -- the SQL (chk_leave_request_purpose_code) is
    // exactly what LeaveServiceTest's Mockito-level companion cannot reach.

    // --- helpers ------------------------------------------------------------

    private SubmitLeaveRequest submitRequest(long employeeId, String leaveTypeCode, String startDate, String endDate) {
        return new SubmitLeaveRequest(employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), "Integration test leave");
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private long insertEmployee(String code, LocalDate hireDate) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, :hireDate)
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("hireDate", hireDate), Long.class);
    }

    /** probation_days is genuinely nullable on hr.employee (V1) -- pass null to leave it NULL in the database and
     * exercise LeaveRepository#findProbationDays' real NULL-column mapping, not a mocked Optional.empty(). */
    private long insertEmployee(String code, LocalDate hireDate, Integer probationDays) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date, probation_days)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, :hireDate, :probationDays)
            RETURNING employee_id
            """, new MapSqlParameterSource()
            .addValue("code", code)
            .addValue("hireDate", hireDate)
            .addValue("probationDays", probationDays),
            Long.class);
    }

    /** Calls LeaveRepository#create directly, bypassing every LeaveService check -- the point is to prove
     * ux_leave_once_per_employment fires on its own, not that LeaveService's Java-level guard works (that is
     * covered elsewhere). */
    private long createOrdinationRequestDirectly(long employeeId, String startDate, String endDate, LeaveStatus status) {
        SubmitLeaveRequest request = submitRequest(employeeId, "ORDINATION", startDate, endDate);
        return leaveRepository.create(
            employeeId,
            employeeId,
            request,
            new BigDecimal("2.00"),
            new BigDecimal("2.00"),
            BigDecimal.ZERO,
            LocalDate.parse(startDate).getYear(),
            status,
            new BigDecimal("60.00"),
            new BigDecimal("58.00"),
            null,
            null, null, null, null, null
        );
    }
}
