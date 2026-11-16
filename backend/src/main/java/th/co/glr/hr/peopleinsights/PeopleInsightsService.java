package th.co.glr.hr.peopleinsights;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.AttendanceRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.LeaveRow;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Person;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Response;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Scope;
import th.co.glr.hr.peopleinsights.PeopleInsightsDtos.Team;
import th.co.glr.hr.peopleinsights.PeopleInsightsRepository.TeamRow;

/**
 * Leave + check-in analytics. ceo and hr see the whole company or any team; a team head (a row in
 * hr.insight_team_head) sees only their own team(s); everyone else is refused. The scope is decided here, from the
 * session user, never from the request: a requested scope the caller may not pick is a 403, not a filter.
 */
@Service
public class PeopleInsightsService {
    static final String ALL = "all";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final Set<String> FULL_ACCESS_ROLES = Set.of("ceo", "hr");

    private final PeopleInsightsRepository repository;

    public PeopleInsightsService(PeopleInsightsRepository repository) {
        this.repository = repository;
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
        List<Long> scopeIds = whole ? repository.activeEmployeeIds() : repository.teamMemberIds(Long.parseLong(served.id()));
        List<Person> people = repository.people(scopeIds);
        List<Long> judged = people.stream().filter(p -> !p.timeExempt()).map(Person::employeeId).toList();
        List<LeaveRow> leave = repository.leave(scopeIds, y);
        List<AttendanceRow> attendance = repository.attendance(judged, y);
        LocalDate asOf = repository.latestAttendanceDate();
        var quotaUsage = repository.quotaUsage(scopeIds, y);
        // A type can reach this year only through its quota year (a leave that starts in December), so both lists name it.
        Set<String> typeCodes = Stream.concat(leave.stream().map(LeaveRow::leaveTypeCode), quotaUsage.stream().map(q -> q.leaveTypeCode()))
            .collect(Collectors.toSet());
        return new Response(asOf == null ? null : asOf.toString(), y, allowed, served,
            repository.leaveTypes(typeCodes), people, leave, quotaUsage, attendance, whole ? comparableTeams(teams) : List.of());
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

    private List<Team> comparableTeams(List<TeamRow> teams) {
        Map<Long, List<String>> heads = repository.headNicknames();
        return teams.stream().map(t -> new Team(String.valueOf(t.id()), t.label(), heads.getOrDefault(t.id(), List.of()),
            repository.teamMemberIds(t.id()))).toList();
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }
}
