package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * §5 leave-rules-as-data (V116): real-Postgres coverage of the per-leave-type rule columns and
 * their enforcement in {@link LeaveService#submit} -- the SQL (V116's seeded {@code hr.leave_type}
 * rows, the {@code chk_leave_type_*} constraints, and above all {@code
 * ux_leave_once_per_employment}) is exactly what Mockito-based {@code LeaveServiceTest} cannot
 * reach; see that class for the gate-by-gate unit coverage (Mockito can fake any {@link
 * LeaveTypeDto}, but not real calendar math or a real unique index).
 */
class LeaveTypeRuleIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- matches the fixed clock used by the other leave
    // integration tests, so every date below is comfortably past every seeded advance-notice value
    // (SICK/MATERNITY/MILITARY/LEAVE_WITHOUT_PAY=0, PERSONAL=1, VACATION=3) unless a test is
    // specifically about notice.
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
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    @Test
    void aNinetyEightDayMaternityRequestSplitsIntoFortyFivePaidAndFiftyThreeUnpaidDays() {
        long employeeId = insertEmployee("MAT-001", LocalDate.parse("2015-01-01"));

        // Mon 2026-01-05 .. Wed 2026-05-20: exactly 98 working weekdays (verified independently of
        // LeaveDayMath). MATERNITY's 98-day quota fully covers the request, but its 45-day
        // paid_days_cap (V116) is what actually determines the split -- this is the gate the
        // MATERNITY row exists to prove.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "MATERNITY", "2026-01-05", "2026-05-20"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("98.00");
        assertThat(result.paidDays()).isEqualByComparingTo("45.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("53.00");
    }

    @Test
    void ordinationLeaveWithinTheFifteenDayPaidCapIsFullyPaid() {
        // Wrong-way-round complement to the maternity test: a SHORT ordination request (10 of the
        // 60-day quota, under the 15-day paid cap) must be entirely paid -- the cap must not bind
        // when it doesn't need to.
        long employeeId = insertEmployee("ORD-CAP-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "ORDINATION", "2026-07-13", "2026-07-24"), // 10 working days
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("10.00");
        assertThat(result.paidDays()).isEqualByComparingTo("10.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void vacationIsRefusedBelowTheTwelveMonthServiceFloor() {
        // Hired 2026-06-01, requesting VACATION starting 2026-07-13: well under 12 months.
        long employeeId = insertEmployee("VAC-NEW-001", LocalDate.parse("2026-06-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("month(s) of completed service");
        assertThat(result.paidDays()).isEqualByComparingTo("0.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void vacationIsGrantedAtExactlyTwelveMonthsOfService() {
        // Wrong-way-round complement: hired exactly 12 completed months before the request start
        // date must be ELIGIBLE ("at least 12 months", not "more than 12").
        long employeeId = insertEmployee("VAC-EXACT-001", LocalDate.parse("2025-07-13"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void vacationIsRefusedWhenTheEmployeeHasNoHireDateOnFile() {
        // DECISION (V116): a NULL hire_date does NOT silently pass a min-service gate. This is the
        // real-DB proof that LeaveRepository#findHireDate's NULL mapping (rs.getObject returning
        // null -> Optional.empty()) actually reaches LeaveService's fail-closed branch -- a
        // Mockito-mocked repository could return Optional.empty() "correctly" even if the real SQL
        // NULL-handling were broken.
        long employeeId = insertEmployeeWithNoHireDate("VAC-NOHIRE-001");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("hire date is not on file");
    }

    @Test
    void personalLeaveIsRefusedWhenItSpansMoreThanThreeConsecutiveDays() {
        long employeeId = insertEmployee("PERSONAL-LONG-001", LocalDate.parse("2015-01-01"));

        // Mon 2026-07-13 .. Thu 2026-07-16: a 4-calendar-day span, one more than PERSONAL's 3-day
        // cap (§5.2).
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-16"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("consecutive day(s)");
    }

    @Test
    void personalLeaveIsGrantedAtExactlyThreeConsecutiveDays() {
        long employeeId = insertEmployee("PERSONAL-EXACT-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-15"), // 3 calendar days
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("3.00");
        assertThat(result.paidDays()).isEqualByComparingTo("3.00");
    }

    @Test
    void personalLeaveIsRefusedWithLessThanOneWorkingDayOfNotice() {
        // FIXED_NOW is Wed 2026-07-01 09:00 Bangkok; PERSONAL requires 1 day of notice. Requesting
        // leave for 2026-07-01 itself (same day, zero notice) must be refused.
        long employeeId = insertEmployee("PERSONAL-NOTICE-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-01", "2026-07-01"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("at least 1 day(s)");
    }

    @Test
    void ordinationCanBeUsedOnceThenIsRefusedForAnySubsequentRequestJavaLevel() {
        long employeeId = insertEmployee("ORD-ONCE-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto first = leaveService.submit(
            submitRequest(employeeId, "ORDINATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));
        assertThat(first.status()).isEqualTo("APPROVED");

        // A second ORDINATION request, in a LATER quota year even -- once-per-employment is NOT
        // per-year -- must be refused by the Java-level check (hasOutstandingOrGrantedRequest).
        LeaveRequestDto second = leaveService.submit(
            submitRequest(employeeId, "ORDINATION", "2026-08-10", "2026-08-11"),
            employee(employeeId));

        assertThat(second.status()).isEqualTo("AUTO_REJECTED");
        assertThat(second.systemNote()).contains("once during your employment");
    }

    @Test
    void aDifferentEmployeesFirstOrdinationRequestIsUnaffectedByAnotherEmployeesGrant() {
        // Wrong-way-round complement: the once-per-employment guard is scoped PER employee, not
        // global -- proves the SQL predicate includes employee_id, not just leave_type_code.
        long employeeA = insertEmployee("ORD-A-001", LocalDate.parse("2015-01-01"));
        long employeeB = insertEmployee("ORD-B-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto forA = leaveService.submit(
            submitRequest(employeeA, "ORDINATION", "2026-07-13", "2026-07-14"),
            employee(employeeA));
        assertThat(forA.status()).isEqualTo("APPROVED");

        LeaveRequestDto forB = leaveService.submit(
            submitRequest(employeeB, "ORDINATION", "2026-07-13", "2026-07-14"),
            employee(employeeB));
        assertThat(forB.status()).isEqualTo("APPROVED");
    }

    @Test
    void theDatabaseItselfRefusesASecondLiveOrdinationClaimEvenBypassingTheJavaCheck() {
        // The test above proves the NORMAL path (LeaveService's Java-level pre-check). This proves
        // the OTHER half the task calls for: that ux_leave_once_per_employment (V116) is a REAL
        // constraint, not just documentation -- by calling LeaveRepository#create directly, TWICE,
        // bypassing LeaveService#autoRejectNote entirely (the same way two concurrent requests would
        // both pass the Java check before either commits). If this constraint were ever dropped or
        // its WHERE clause narrowed, this is the test that would catch it; LeaveServiceTest's
        // DuplicateKeyException-catch test only proves the CATCH works, not that the INDEX exists.
        long employeeId = insertEmployee("ORD-RACE-001", LocalDate.parse("2015-01-01"));

        long firstId = createOrdinationRequestDirectly(employeeId, "2026-07-13", "2026-07-14", LeaveStatus.SUBMITTED);
        assertThat(firstId).isPositive();

        assertThatThrownBy(() ->
            createOrdinationRequestDirectly(employeeId, "2026-08-10", "2026-08-11", LeaveStatus.SUBMITTED))
            .isInstanceOf(DataAccessException.class);
    }

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

    private long insertEmployeeWithNoHireDate(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, NULL)
            RETURNING employee_id
            """, Map.of("code", code), Long.class);
    }

    /**
     * Calls {@link LeaveRepository#create} directly, bypassing every {@link LeaveService} check --
     * the point is to prove {@code ux_leave_once_per_employment} fires on its own, not that
     * LeaveService's Java-level guard works (that is covered elsewhere).
     */
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
