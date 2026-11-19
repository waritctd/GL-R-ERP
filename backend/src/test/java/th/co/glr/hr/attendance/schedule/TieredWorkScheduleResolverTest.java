package th.co.glr.hr.attendance.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.config.AppProperties;

/** Pure unit tests for TieredWorkScheduleResolver's precedence and effective-dating rules — no Spring, no
 * database. This file proves the other half: given a set of assignments, does TieredWorkScheduleResolver pick
 * the right one. */
class TieredWorkScheduleResolverTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate SOME_DATE = LocalDate.of(2026, 8, 5); // a Wednesday

    private static final long EMPLOYEE_ID = 10L;
    private static final long DEPARTMENT_ID = 20L;
    private static final long DIVISION_ID = 30L;

    private static final WorkSchedule EMPLOYEE_SCHEDULE = schedule(9, 0, 18, 0);
    private static final WorkSchedule DEPARTMENT_SCHEDULE = schedule(8, 0, 17, 0);
    private static final WorkSchedule DIVISION_SCHEDULE = schedule(7, 0, 16, 0);

    private static WorkSchedule schedule(int startHour, int startMin, int endHour, int endMin) {
        return new WorkSchedule(
            BANGKOK,
            LocalTime.of(startHour, startMin),
            LocalTime.of(endHour, endMin),
            5,
            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                   DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        );
    }

    private static WorkSchedule fallbackSchedule() {
        return new CompanyWideWorkScheduleResolver(new AppProperties())
            .resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);
    }

    private TieredWorkScheduleResolver resolverWith(ScheduleAssignment... assignments) {
        WorkScheduleAssignmentRepository repository = mock(WorkScheduleAssignmentRepository.class);
        when(repository.findAllAssignments()).thenReturn(List.of(assignments));
        // Default AppProperties -> 5-minute TTL, comfortably longer than any of these tests run, so
        // precedence/effective-dating tests below are not accidentally exercising cache expiry.
        return new TieredWorkScheduleResolver(
            repository, new CompanyWideWorkScheduleResolver(new AppProperties()), new AppProperties());
    }

    private static ScheduleAssignment assignment(
            ScopeType scope, long scopeId, WorkSchedule schedule, LocalDate from, LocalDate to) {
        return new ScheduleAssignment(scope, scopeId, from, to, schedule);
    }

    // --- precedence -----------------------------------------------------------------------------

    @Test
    void employeeAssignmentBeatsDepartmentAndDivision() {
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.EMPLOYEE, EMPLOYEE_ID, EMPLOYEE_SCHEDULE, LocalDate.of(2024, 10, 1), null),
            assignment(ScopeType.DEPARTMENT, DEPARTMENT_ID, DEPARTMENT_SCHEDULE, LocalDate.of(2024, 10, 1), null),
            assignment(ScopeType.DIVISION, DIVISION_ID, DIVISION_SCHEDULE, LocalDate.of(2024, 10, 1), null)
        );

        WorkSchedule resolved = resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);

        assertThat(resolved).isEqualTo(EMPLOYEE_SCHEDULE);
    }

    @Test
    void fallsBackToCompanyWideWhenNothingIsAssigned() {
        TieredWorkScheduleResolver resolver = resolverWith();

        WorkSchedule resolved = resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);

        assertThat(resolved).isEqualTo(fallbackSchedule());
    }

    // --- effective dating -------------------------------------------------------------------------

    @Test
    void aWorkDateBeforeEffectiveFromResolvesTheFallbackNotTheAssignment() {
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.DIVISION, DIVISION_ID, DIVISION_SCHEDULE, LocalDate.of(2024, 10, 1), null)
        );

        WorkSchedule resolved = resolver.resolve(
            EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, LocalDate.of(2024, 9, 30));

        assertThat(resolved).isEqualTo(fallbackSchedule());
    }

}
