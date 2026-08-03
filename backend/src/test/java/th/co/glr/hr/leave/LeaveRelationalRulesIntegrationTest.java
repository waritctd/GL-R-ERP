package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * §5.3 relational rules (2026-08, ประกาศ "วันเวลาทำงาน และการหยุดงาน" eff. 1 ต.ค. 2567): real-Postgres
 * coverage of the three submit-time gates that each depend on data outside the request itself --
 * §5.3.2 department coverage, §5.3.3 contiguous PERSONAL/VACATION, §5.3.4 post-resignation. Mockito
 * cannot reach any of this: real {@code hr.resignation} rows, the real {@code
 * hr.work_schedule*}/{@code hr.holiday} tables (via the real {@link TieredWorkScheduleResolver}/
 * {@link DbHolidayCalendar}, not fakes), and real {@code hr.leave_request} rows written by other
 * employees are exactly what is under test -- see {@code LeaveServiceTest} for the Mockito-level
 * companion that isolates each gate's decision logic.
 *
 * <p>No {@code hr.work_schedule_assignment} rows exist for the ad-hoc departments this class
 * creates (V115 only seeds assignments for known production division/department codes -- see that
 * migration's own comment on a no-op INSERT against a fresh database), so every employee here
 * resolves to {@link CompanyWideWorkScheduleResolver}'s default schedule: Mon-Fri, per {@code
 * AppProperties}' own defaults. Every date below is chosen to land on a weekday for that reason.
 */
class LeaveRelationalRulesIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- comfortably past VACATION's 3-day and PERSONAL's
    // 1-day seeded advance notice for every date used below.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties appProperties = new AppProperties();
        WorkScheduleResolver scheduleResolver = new TieredWorkScheduleResolver(
            new WorkScheduleAssignmentRepository(jdbc, appProperties),
            new CompanyWideWorkScheduleResolver(appProperties),
            appProperties);
        HolidayCalendar holidayCalendar = new DbHolidayCalendar(jdbc);
        // Schedule/holiday-aware constructor (2026-08-03, #470) -- leaveRepository.workingDayPredicate
        // is what LeaveService now asks for "is this employee at work on this date", the same
        // collaborator #5.3.2/#5.3.3 rely on. See LeaveScheduleHolidayAwareIntegrationTest for the
        // reference wiring this mirrors.
        leaveRepository = new LeaveRepository(jdbc, scheduleResolver, holidayCalendar);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.3.4 post-resignation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void vacationIsRefusedForAResignedEmployeeAndAllowedForAnActiveOne() {
        long resignedEmployee = insertEmployee("RES-VAC-001", LocalDate.parse("2015-01-01"), null);
        insertResignation(resignedEmployee);
        long activeEmployee = insertEmployee("RES-VAC-002", LocalDate.parse("2015-01-01"), null);

        LeaveRequestDto resignedResult = leaveService.submit(
            submitRequest(resignedEmployee, "VACATION", "2026-07-13", "2026-07-13"), employee(resignedEmployee));
        LeaveRequestDto activeResult = leaveService.submit(
            submitRequest(activeEmployee, "VACATION", "2026-07-13", "2026-07-13"), employee(activeEmployee));

        assertThat(resignedResult.status()).isEqualTo("AUTO_REJECTED");
        assertThat(resignedResult.systemNoteCode()).isEqualTo("RESIGNATION_GATE");
        assertThat(resignedResult.systemNote()).isNotBlank();
        assertThat(activeResult.status()).isEqualTo("APPROVED");
    }

    @Test
    void personalIsAlsoRefusedForAResignedEmployeeAndAllowedForAnActiveOne() {
        long resignedEmployee = insertEmployee("RES-PERS-001", LocalDate.parse("2015-01-01"), null);
        insertResignation(resignedEmployee);
        long activeEmployee = insertEmployee("RES-PERS-002", LocalDate.parse("2015-01-01"), null);

        LeaveRequestDto resignedResult = leaveService.submit(
            submitRequest(resignedEmployee, "PERSONAL", "2026-07-13", "2026-07-13"), employee(resignedEmployee));
        LeaveRequestDto activeResult = leaveService.submit(
            submitRequest(activeEmployee, "PERSONAL", "2026-07-13", "2026-07-13"), employee(activeEmployee));

        assertThat(resignedResult.status()).isEqualTo("AUTO_REJECTED");
        assertThat(resignedResult.systemNoteCode()).isEqualTo("RESIGNATION_GATE");
        assertThat(activeResult.status()).isEqualTo("APPROVED");
    }

    @Test
    void sickLeaveIsUnaffectedByAResignationRow() {
        // §5.3.4 names only ลาพักร้อน/ลากิจ (VACATION/PERSONAL) -- SICK is untouched by this gate, even
        // for a resigned employee, proving the type-gate genuinely scopes to those two, not "any
        // leave" once a resignation exists.
        //
        // DECISION (2026-08-03, post-V124 correction): the ORIGINAL version of this test forced
        // AUTO_REJECTED via the old "SICK without a certificate always rejects" rule, which V124
        // replaced with a 3-occasion/month tolerance -- a single certificate-less request no longer
        // auto-rejects on its own, so the assertion (not this test's premise) went stale once V124
        // landed. The NAME is correct; the old body's specific rejection path is not. Rather than
        // assert the now-different outcome outright (a lone certificate-less SICK request for a
        // resigned employee is genuinely APPROVED under V124, which would also prove "unaffected" but
        // more weakly -- an approval has no systemNote to inspect at all), this keeps proving the
        // STRONGER, harder-to-fake claim the original test intended: even when a certificate-less SICK
        // request DOES get auto-rejected, for an unrelated SICK-specific reason, the rejection note is
        // never contaminated with resignation wording. Seeds 3 prior certificate-less SICK occasions
        // this month so the 4th is refused on the tolerance, not the (now non-existent for a first
        // occasion) blanket certificate rule.
        long resignedEmployee = insertEmployee("RES-SICK-001", LocalDate.parse("2015-01-01"), null);
        insertResignation(resignedEmployee);
        seedActiveRequest(resignedEmployee, "SICK", "2026-07-02", "2026-07-02", LeaveStatus.APPROVED);
        seedActiveRequest(resignedEmployee, "SICK", "2026-07-03", "2026-07-03", LeaveStatus.APPROVED);
        seedActiveRequest(resignedEmployee, "SICK", "2026-07-06", "2026-07-06", LeaveStatus.APPROVED);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(resignedEmployee, "SICK", "2026-07-13", "2026-07-13"), employee(resignedEmployee));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        // The stronger, code-based version of the original "not contaminated with resignation
        // wording" claim: not merely that the (now-Thai) system_note text happens not to contain the
        // word "resignation", but that the STRUCTURED reason is unambiguously the SICK tolerance gate,
        // never RESIGNATION_GATE.
        assertThat(result.systemNoteCode()).isEqualTo("SICK_NO_CERT_TOLERANCE_EXHAUSTED");
        assertThat(result.systemNoteCode()).isNotEqualTo("RESIGNATION_GATE");
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.3.3 contiguous PERSONAL/VACATION
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void vacationImmediatelyAfterAFridayPersonalRequestIsRejectedAcrossTheWeekend() {
        // §5.3.3 "ติดต่อกัน across a non-working day" DECISION, proven against real dates/real
        // TieredWorkScheduleResolver: Fri 2026-07-10 PERSONAL, then Mon 2026-07-13 VACATION -- an
        // ordinary Sat/Sun weekend between them, no scheduled workday -- must be REJECTED as
        // contiguous.
        long employeeId = insertEmployee("CONTIG-001", LocalDate.parse("2015-01-01"), null);
        seedActiveRequest(employeeId, "PERSONAL", "2026-07-10", "2026-07-10", LeaveStatus.APPROVED);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-13"), employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNoteCode()).isEqualTo("CONTIGUOUS_LEAVE_PAIR");
    }

    @Test
    void vacationWithARealWorkingDayGapAfterAPriorPersonalRequestIsAllowed() {
        // Wrong-way-round complement, same paired PERSONAL request shape: the VACATION request
        // instead starts a week later (Mon 2026-07-20), with a genuine Mon-Fri working week (13-17
        // July) in between -- must be ALLOWED, proving a real gap breaks contiguity.
        long employeeId = insertEmployee("CONTIG-002", LocalDate.parse("2015-01-01"), null);
        seedActiveRequest(employeeId, "PERSONAL", "2026-07-10", "2026-07-10", LeaveStatus.APPROVED);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-20", "2026-07-20"), employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void aPendingPersonalRequestBlocksAnAdjacentVacationRequestTheSameAsAnApprovedOne() {
        // "ติดต่อกัน" binds on a SUBMITTED (pending) request too, not only an APPROVED one -- same
        // SUBMITTED/APPROVED scope as every other §5 gate that reads hr.leave_request.status.
        long employeeId = insertEmployee("CONTIG-003", LocalDate.parse("2015-01-01"), null);
        seedActiveRequest(employeeId, "PERSONAL", "2026-07-10", "2026-07-10", LeaveStatus.SUBMITTED);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-13"), employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNoteCode()).isEqualTo("CONTIGUOUS_LEAVE_PAIR");
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.3.2 department coverage (owner-relaxed: no tenure qualifier)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void departmentCoverageBlocksTheLastRemainingEmployeeAndAllowsTheSamePairOnAFreeDay() {
        // Both sides on ONE fixture (same requester, same colleagues, same department) -- the
        // vacuous-fixture trap this rule is explicitly at risk of: a "blocked" assertion alone would
        // pass even if the colleague-lookup silently found nobody. A THREE-person department
        // (requester + 2 colleagues -- department size must be >= MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE
        // for the gate to apply at all, owner ruling 2026-08-03) with BOTH colleagues on leave the
        // same day: first call is blocked (one colleague PENDING, one APPROVED, both covering the
        // same day); second call, one week later where both colleagues are free, is allowed.
        long departmentId = insertDepartment("DEPT-COVER-001");
        long requester = insertEmployee("COVER-EMP-001", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueA = insertEmployee("COVER-EMP-002", LocalDate.parse("2015-01-01"), departmentId);
        long colleagueB = insertEmployee("COVER-EMP-003", LocalDate.parse("2015-01-01"), departmentId);
        seedActiveRequest(colleagueA, "VACATION", "2026-07-13", "2026-07-13", LeaveStatus.SUBMITTED);
        seedActiveRequest(colleagueB, "VACATION", "2026-07-13", "2026-07-13", LeaveStatus.APPROVED);

        LeaveRequestDto blockedResult = leaveService.submit(
            submitRequest(requester, "VACATION", "2026-07-13", "2026-07-13"), employee(requester));
        assertThat(blockedResult.status()).isEqualTo("AUTO_REJECTED");
        assertThat(blockedResult.systemNoteCode()).isEqualTo("DEPARTMENT_COVERAGE");

        LeaveRequestDto allowedResult = leaveService.submit(
            submitRequest(requester, "VACATION", "2026-07-20", "2026-07-20"), employee(requester));
        assertThat(allowedResult.status()).isEqualTo("APPROVED");
    }

    @Test
    void departmentsBelowTheSizeFloorAreExemptEvenWhenTheOnlyColleagueIsOnLeaveTheSameDay() {
        // §5.3.2 department-size-floor boundary (owner ruling, 2026-08-03), pinned against the real
        // repository: a TWO-person department (requester + 1 colleague, below
        // MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE = 3) is exempt entirely, even though the colleague is
        // on leave the very same day -- the exact "warehouse pair, one already off" scenario the
        // ruling exists for. The former one-person-only exemption (aOnePersonDepartmentIsExemptFromTheCoverageRule
        // below) is a special case of this, not a separate rule.
        long departmentId = insertDepartment("DEPT-COVER-PAIR-001");
        long requester = insertEmployee("COVER-PAIR-001", LocalDate.parse("2015-01-01"), departmentId);
        long colleague = insertEmployee("COVER-PAIR-002", LocalDate.parse("2015-01-01"), departmentId);
        seedActiveRequest(colleague, "VACATION", "2026-07-13", "2026-07-13", LeaveStatus.APPROVED);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(requester, "VACATION", "2026-07-13", "2026-07-13"), employee(requester));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void aOnePersonDepartmentIsExemptFromTheCoverageRule() {
        // The announcement's own reasoning for this relaxation: without it, a one-person department
        // could never take any leave at all. Also below the size-3 floor (see
        // #departmentsBelowTheSizeFloorAreExemptEvenWhenTheOnlyColleagueIsOnLeaveTheSameDay), which now
        // subsumes this case, but kept as its own test: a zero-colleague department is a distinct code
        // path (findActiveDepartmentColleagues returns empty) worth pinning on its own.
        long departmentId = insertDepartment("DEPT-SOLO-001");
        long soleEmployee = insertEmployee("SOLO-EMP-001", LocalDate.parse("2015-01-01"), departmentId);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(soleEmployee, "VACATION", "2026-07-13", "2026-07-13"), employee(soleEmployee));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void employeesWithNoDepartmentOnFileAreUnaffectedByTheCoverageRule() {
        // Defensive case: department_id is nullable on hr.employee. No department at all reads the
        // same as "nothing to check against", not a crash.
        long employeeId = insertEmployee("NODEPT-001", LocalDate.parse("2015-01-01"), null);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-13"), employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    // --- helpers ------------------------------------------------------------

    private SubmitLeaveRequest submitRequest(long employeeId, String leaveTypeCode, String startDate, String endDate) {
        return new SubmitLeaveRequest(employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), "Integration test leave");
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private long insertDepartment(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (name_th, is_active) VALUES (:name, TRUE) RETURNING department_id
            """, Map.of("name", nameTh), Long.class);
    }

    private long insertEmployee(String code, LocalDate hireDate, Long departmentId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, is_active, hire_date, department_id)
            VALUES (:code, :code, 'ทดสอบ', TRUE, :hireDate, :departmentId)
            RETURNING employee_id
            """, new MapSqlParameterSource()
            .addValue("code", code)
            .addValue("hireDate", hireDate)
            .addValue("departmentId", departmentId),
            Long.class);
    }

    /** {@code hr.resignation} (V1): one row per employee -- see LeaveService#resignationRuleOutcome. */
    private void insertResignation(long employeeId) {
        jdbc.update("""
            INSERT INTO hr.resignation (employee_id, recorded_date, resign_date)
            VALUES (:employeeId, :recordedDate, :resignDate)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("recordedDate", LocalDate.parse("2026-06-15"))
            .addValue("resignDate", LocalDate.parse("2026-08-15")));
    }

    /**
     * Seeds an "existing" leave request directly through {@link LeaveRepository#create} -- bypassing
     * every {@link LeaveService} gate, since the point is to construct pre-existing state for the
     * request UNDER TEST to react to, not to prove the seeded request's own submission was valid.
     */
    private void seedActiveRequest(long employeeId, String leaveTypeCode, String startDate, String endDate, LeaveStatus status) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        SubmitLeaveRequest seed = new SubmitLeaveRequest(employeeId, leaveTypeCode, start, end, "Seed fixture");
        leaveRepository.create(employeeId, employeeId, seed, new BigDecimal("1.00"), new BigDecimal("1.00"),
            BigDecimal.ZERO, start.getYear(), status, new BigDecimal("5.00"), new BigDecimal("4.00"),
            null, null, null, null, null, null);
    }
}
