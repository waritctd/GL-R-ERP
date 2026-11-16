package th.co.glr.hr.peopleinsights;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.AttendanceRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.LeaveRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Person;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Response;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Scope;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Team;
import th.co.glr.hr.peopleinsights.LeaveDayRule.Outcome;
import th.co.glr.hr.peopleinsights.LeaveDayRule.Span;
import th.co.glr.hr.peopleinsights.PeopleInsightsRepository.LeaveDayRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsRepository.TeamRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsRepository.YearBounds;

/**
 * Leave + check-in analytics. ceo and hr see the whole company or any team; a team head (a row in
 * hr.insight_team_head) sees only their own team(s); everyone else is refused. The scope is decided here, from the
 * session user, never from the request: a requested scope the caller may not pick is a 403, not a filter.
 */
@Service
public class PeopleInsightsService {
    static final String ALL = "all";
    private static final int FIRST_YEAR = 2024;
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final Set<String> FULL_ACCESS_ROLES = Set.of("ceo", "hr");

    private final PeopleInsightsRepository repository;
    private final WorkScheduleResolver scheduleResolver;

    public PeopleInsightsService(PeopleInsightsRepository repository, WorkScheduleResolver scheduleResolver) {
        this.repository = repository;
        this.scheduleResolver = scheduleResolver;
    }

    public boolean mayView(UserPrincipal user) {
        return FULL_ACCESS_ROLES.contains(user.role()) || (user.employeeId() != null && repository.isTeamHead(user.employeeId()));
    }

    public Response get(UserPrincipal user, Integer year, String requestedScope) {
        List<TeamRow> teams = repository.teams();
        List<Scope> allowed = allowedScopes(user, teams);
        if (allowed.isEmpty()) throw forbidden();
        Scope served = requestedScope == null ? allowed.get(0)
            : allowed.stream().filter(s -> s.id().equals(requestedScope)).findFirst().orElseThrow(PeopleInsightsService::forbidden);
        int y = year != null ? year : LocalDate.now(BANGKOK).getYear();
        if (y < 2000 || y > 2100) throw new ApiException(HttpStatus.BAD_REQUEST, "ปีไม่ถูกต้อง");

        boolean whole = ALL.equals(served.id());
        Map<Long, List<Long>> members = repository.teamMemberIds(y);
        List<Long> scopeIds = whole ? repository.companyEmployeeIds(y) : members.getOrDefault(Long.parseLong(served.id()), List.of());
        List<Person> people = repository.people(scopeIds);
        List<Long> judged = people.stream().filter(p -> !p.timeExempt()).map(Person::employeeId).toList();
        List<LeaveRow> leave = repository.leave(scopeIds, y);
        List<AttendanceRow> attendance = withLeaveDays(repository.attendance(judged, y), repository.leaveDays(judged, y));
        LocalDate asOf = repository.latestAttendanceDate();
        var quotaUsage = repository.quotaUsage(scopeIds, y);
        // A type can reach this year only through its quota year (a leave that starts in December), so both lists name it.
        Set<String> typeCodes = Stream.concat(leave.stream().map(LeaveRow::leaveTypeCode), quotaUsage.stream().map(q -> q.leaveTypeCode()))
            .collect(Collectors.toSet());
        return new Response(asOf == null ? null : asOf.toString(), y, years(), allowed, served,
            repository.leaveTypes(typeCodes), people, leave, quotaUsage, attendance, whole ? comparableTeams(teams, members) : List.of());
    }

    private List<Scope> allowedScopes(UserPrincipal user, List<TeamRow> teams) {
        List<Scope> out = new ArrayList<>();
        if (FULL_ACCESS_ROLES.contains(user.role())) {
            out.add(new Scope(ALL, "ทั้งบริษัท"));
            teams.forEach(t -> out.add(new Scope(String.valueOf(t.id()), t.label())));
        } else if (user.employeeId() != null) {
            Set<Long> headed = Set.copyOf(repository.teamIdsHeadedBy(user.employeeId()));
            teams.stream().filter(t -> headed.contains(t.id())).forEach(t -> out.add(new Scope(String.valueOf(t.id()), t.label())));
        }
        return out;
    }

    /** Every year from the earliest data (not before 2024) to the latest (not after next year), and always this year. */
    private List<Integer> years() {
        int current = LocalDate.now(BANGKOK).getYear();
        TreeSet<Integer> years = new TreeSet<>(Set.of(current));
        YearBounds bounds = repository.dataYearBounds();
        if (bounds != null) {
            for (int y = Math.max(bounds.first(), FIRST_YEAR); y <= Math.min(bounds.last(), current + 1); y++) years.add(y);
        }
        return List.copyOf(years);
    }

    /** Adds back the late and early-leave figures of days touched by approved leave, as LeaveDayRule decides them. */
    private List<AttendanceRow> withLeaveDays(List<AttendanceRow> rows, List<LeaveDayRow> leaveDays) {
        record Day(long employeeId, LocalDate date) {}
        Map<Day, List<LeaveDayRow>> byDay = new LinkedHashMap<>();
        leaveDays.forEach(r -> byDay.computeIfAbsent(new Day(r.employeeId(), r.workDate()), k -> new ArrayList<>()).add(r));
        Map<String, int[]> added = new HashMap<>(); // employee|month -> late days, late minutes, early leaves
        byDay.forEach((day, covering) -> {
            LeaveDayRow first = covering.get(0);
            WorkSchedule schedule = scheduleResolver.resolve(day.employeeId(), first.divisionId(), first.departmentId(), day.date());
            List<Span> spans = covering.stream().map(r -> LeaveDayRule.spanOn(day.date(), r.leaveStart(), r.leaveEnd(), r.startTime(), r.endTime())).toList();
            Outcome o = LeaveDayRule.apply(schedule, spans, localTime(first.checkIn(), schedule), localTime(first.checkOut(), schedule),
                first.lateMinutes(), first.earlyLeaveMinutes());
            int[] sum = added.computeIfAbsent(day.employeeId() + "|" + day.date().toString().substring(0, 7), k -> new int[3]);
            if (o.lateMinutes() > 0) { sum[0]++; sum[1] += o.lateMinutes(); }
            if (o.early()) sum[2]++;
        });
        return rows.stream().map(r -> {
            int[] a = added.get(r.employeeId() + "|" + r.month());
            return a == null ? r : new AttendanceRow(r.employeeId(), r.month(), r.scanDays(), r.lateDays() + a[0], r.lateMinutes() + a[1],
                r.noCheckOut(), r.earlyLeave() + a[2], r.morningScanDays(), r.avgCheckInMinutes());
        }).toList();
    }

    private static LocalTime localTime(OffsetDateTime at, WorkSchedule schedule) {
        return at == null ? null : at.atZoneSameInstant(schedule.zone()).toLocalTime();
    }

    private List<Team> comparableTeams(List<TeamRow> teams, Map<Long, List<Long>> members) {
        Map<Long, List<String>> heads = repository.headNicknames();
        return teams.stream().map(t -> new Team(String.valueOf(t.id()), t.label(), heads.getOrDefault(t.id(), List.of()),
            members.getOrDefault(t.id(), List.of()))).toList();
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }
}
