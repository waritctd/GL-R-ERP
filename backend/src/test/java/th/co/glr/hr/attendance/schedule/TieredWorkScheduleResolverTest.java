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

/**
 * Pure unit tests for {@link TieredWorkScheduleResolver}'s precedence and effective-dating rules —
 * no Spring, no database. {@link WorkScheduleAssignmentRepository} is mocked here on purpose: it is
 * a thin SQL read (join + array_agg), and the SQL side of it is what
 * {@link WorkScheduleAssignmentRepositoryIntegrationTest} exists to prove against real Postgres —
 * Mockito cannot reach that half. This file proves the other half: given a set of assignments, does
 * {@link TieredWorkScheduleResolver} pick the right one.
 */
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
        return new TieredWorkScheduleResolver(
            repository, new CompanyWideWorkScheduleResolver(new AppProperties()));
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
    void departmentAssignmentBeatsDivisionWhenNoEmployeeRowExists() {
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.DEPARTMENT, DEPARTMENT_ID, DEPARTMENT_SCHEDULE, LocalDate.of(2024, 10, 1), null),
            assignment(ScopeType.DIVISION, DIVISION_ID, DIVISION_SCHEDULE, LocalDate.of(2024, 10, 1), null)
        );

        WorkSchedule resolved = resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);

        assertThat(resolved).isEqualTo(DEPARTMENT_SCHEDULE);
    }

    @Test
    void divisionAssignmentAppliesWhenNoEmployeeOrDepartmentRowExists() {
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.DIVISION, DIVISION_ID, DIVISION_SCHEDULE, LocalDate.of(2024, 10, 1), null)
        );

        WorkSchedule resolved = resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);

        assertThat(resolved).isEqualTo(DIVISION_SCHEDULE);
    }

    @Test
    void fallsBackToCompanyWideWhenNothingIsAssigned() {
        TieredWorkScheduleResolver resolver = resolverWith();

        WorkSchedule resolved = resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);

        assertThat(resolved).isEqualTo(fallbackSchedule());
    }

    /**
     * An assignment for someone ELSE's employee/department/division must never leak onto this
     * caller — each scope must be matched by id, not merely by scope type existing in the table.
     */
    @Test
    void assignmentsForOtherScopeIdsAreIgnored() {
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.EMPLOYEE, EMPLOYEE_ID + 1, EMPLOYEE_SCHEDULE, LocalDate.of(2024, 10, 1), null),
            assignment(ScopeType.DEPARTMENT, DEPARTMENT_ID + 1, DEPARTMENT_SCHEDULE, LocalDate.of(2024, 10, 1), null),
            assignment(ScopeType.DIVISION, DIVISION_ID + 1, DIVISION_SCHEDULE, LocalDate.of(2024, 10, 1), null)
        );

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

    @Test
    void aWorkDateAfterEffectiveToResolvesTheFallbackNotTheAssignment() {
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.DIVISION, DIVISION_ID, DIVISION_SCHEDULE,
                LocalDate.of(2024, 10, 1), LocalDate.of(2025, 12, 31))
        );

        WorkSchedule resolved = resolver.resolve(
            EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, LocalDate.of(2026, 1, 1));

        assertThat(resolved).isEqualTo(fallbackSchedule());
    }

    @Test
    void aWorkDateInsideTheEffectiveWindowResolvesTheAssignment() {
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.DIVISION, DIVISION_ID, DIVISION_SCHEDULE,
                LocalDate.of(2024, 10, 1), LocalDate.of(2025, 12, 31))
        );

        WorkSchedule resolved = resolver.resolve(
            EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, LocalDate.of(2025, 6, 1));

        assertThat(resolved).isEqualTo(DIVISION_SCHEDULE);
    }

    /**
     * A schedule change must not silently rewrite history: an OLD assignment that ended and a NEW
     * one that started later must each own only their own window, even for the same scope id.
     */
    @Test
    void aSupersededAssignmentDoesNotApplyAfterItsReplacementTakesOver() {
        WorkSchedule oldSchedule = schedule(8, 0, 17, 0);
        WorkSchedule newSchedule = schedule(9, 0, 18, 0);
        TieredWorkScheduleResolver resolver = resolverWith(
            assignment(ScopeType.DIVISION, DIVISION_ID, oldSchedule,
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 9, 30)),
            assignment(ScopeType.DIVISION, DIVISION_ID, newSchedule,
                LocalDate.of(2024, 10, 1), null)
        );

        assertThat(resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, LocalDate.of(2023, 5, 1)))
            .isEqualTo(oldSchedule);
        assertThat(resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, LocalDate.of(2024, 11, 1)))
            .isEqualTo(newSchedule);
    }

    /**
     * The schema does not stop two rows existing for the same scope with genuinely OVERLAPPING
     * effective ranges (both {@code effective_to IS NULL}, both already started) — unlike the
     * supersession case above, here BOTH rows cover {@code workDate} at once. Resolution must be
     * last-write-wins: {@link WorkScheduleAssignmentRepository#findAllAssignments()}'s contract is
     * to return newest {@code effective_from} first, and this resolver deliberately does no
     * re-sorting of its own — it trusts list order (see this class's own javadoc). This test locks
     * in that "trust list order" behaviour: the mocked repository hands back the list in exactly the
     * contractual order (newest first), and the assertion targets the SCHEDULE the newer row owns,
     * not merely "whatever came first" — so a resolver that started comparing dates itself (instead
     * of trusting order) would still need to produce this same answer to pass.
     */
    @Test
    void ofTwoOverlappingAssignmentsForTheSameScopeTheNewerEffectiveFromWins() {
        WorkSchedule olderSchedule = schedule(8, 0, 17, 0);
        WorkSchedule newerSchedule = schedule(9, 0, 18, 0);
        ScheduleAssignment older = assignment(
            ScopeType.DIVISION, DIVISION_ID, olderSchedule, LocalDate.of(2024, 10, 1), null);
        ScheduleAssignment newer = assignment(
            ScopeType.DIVISION, DIVISION_ID, newerSchedule, LocalDate.of(2025, 6, 1), null);
        // Contractual order from the repository: newest effective_from first.
        TieredWorkScheduleResolver resolver = resolverWith(newer, older);

        WorkSchedule resolved = resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);

        assertThat(resolved).isEqualTo(newerSchedule);
    }

    // --- caching ------------------------------------------------------------------------------

    /**
     * The repository must be asked for the assignment set at most once per resolver instance —
     * that is the whole point of caching (see {@link WorkScheduleAssignmentRepository}'s javadoc):
     * without it, a range recalculation issuing one {@code resolve} call per employee-day would
     * turn back into one query per employee-day.
     */
    @Test
    void theAssignmentTableIsLoadedAtMostOncePerResolverInstance() {
        WorkScheduleAssignmentRepository repository = mock(WorkScheduleAssignmentRepository.class);
        when(repository.findAllAssignments()).thenReturn(List.of(
            assignment(ScopeType.DIVISION, DIVISION_ID, DIVISION_SCHEDULE, LocalDate.of(2024, 10, 1), null)
        ));
        TieredWorkScheduleResolver resolver = new TieredWorkScheduleResolver(
            repository, new CompanyWideWorkScheduleResolver(new AppProperties()));

        resolver.resolve(EMPLOYEE_ID, DIVISION_ID, DEPARTMENT_ID, SOME_DATE);
        resolver.resolve(EMPLOYEE_ID + 1, DIVISION_ID, DEPARTMENT_ID, SOME_DATE.plusDays(1));
        resolver.resolve(EMPLOYEE_ID + 2, DIVISION_ID, DEPARTMENT_ID, SOME_DATE.plusDays(2));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).findAllAssignments();
    }
}
