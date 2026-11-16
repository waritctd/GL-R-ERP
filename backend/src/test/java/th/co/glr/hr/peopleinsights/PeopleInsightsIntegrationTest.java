package th.co.glr.hr.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * GET /api/people-insights through the REAL controller, service, repository and Postgres: the scope rules (who may
 * see which team) and the aggregation SQL (month bucketing, Bangkok dates, APPROVED-only) only mean something
 * against real rows. The org below is synthetic and public-safe: two teams, one with a co-head, a deep reporting
 * line, a detached line, an inactive report and a time-exempt employee.
 */
class PeopleInsightsIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String URL = "/api/people-insights";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 08:30-17:30, grace 15 for everyone (the same schedule the day rule's table uses). */
    private static final th.co.glr.hr.attendance.schedule.WorkScheduleResolver FIXED_SCHEDULE = (employee, division, department, date) ->
        new th.co.glr.hr.attendance.schedule.WorkSchedule(ZoneId.of("Asia/Bangkok"), java.time.LocalTime.of(8, 30), java.time.LocalTime.of(17, 30), 15,
            Set.of(java.time.DayOfWeek.values()));

    private MockMvc mvc;
    private final java.util.Map<String, Long> id = new java.util.HashMap<>();
    private long alphaTeam;
    private long betaTeam;

    @BeforeEach
    void wire() {
        mvc = MockMvcBuilders.standaloneSetup(
                new PeopleInsightsController(new PeopleInsightsService(new PeopleInsightsRepository(jdbc), FIXED_SCHEDULE), new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        id.put("ceo", emp("T-CEO", "ซีอี", null, true));
        id.put("hr", emp("T-HR", "เอชอาร์", id.get("ceo"), true));
        // Alpha: heads A1 + A2 (co-heads). A1 line: a1x (time-exempt), a1y -> a1y1 (deep), a1d (detached) -> a1d1, a1z (inactive).
        id.put("a1", emp("T-A1", "เอหนึ่ง", id.get("ceo"), true));
        id.put("a2", emp("T-A2", "เอสอง", id.get("ceo"), true));
        id.put("a1x", emp("T-A1X", "เอเอ็กซ์", id.get("a1"), true));
        id.put("a1y", emp("T-A1Y", "เอวาย", id.get("a1"), true));
        id.put("a1y1", emp("T-A1Y1", "เอวายหนึ่ง", id.get("a1y"), true));
        id.put("a1d", emp("T-A1D", "เอดี", id.get("a1"), true));
        id.put("a1d1", emp("T-A1D1", "เอดีหนึ่ง", id.get("a1d"), true));
        id.put("a1z", emp("T-A1Z", "เอแซด", id.get("a1"), false));
        id.put("a2x", emp("T-A2X", "เอสองเอ็กซ์", id.get("a2"), true));
        // Beta: head B1, one report.
        id.put("b1", emp("T-B1", "บีหนึ่ง", id.get("ceo"), true));
        id.put("b1x", emp("T-B1X", "บีเอ็กซ์", id.get("b1"), true));
        id.put("plain", emp("T-PLAIN", "ธรรมดา", id.get("ceo"), true));
        // Inserted Alpha first but sorted second, so the order assertion cannot pass by insertion order.
        alphaTeam = team("ทีมอัลฟา", 2);
        betaTeam = team("ทีมเบตา", 1);
        head(alphaTeam, "a1");
        head(alphaTeam, "a2");
        head(betaTeam, "b1");
        setting("a1x", true, false);
        setting("a1d", false, true);
    }

    // 1 ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void ceoAndHrGetEveryActiveEmployeeForScopeAllAndInactiveAreExcluded() throws Exception {
        for (String role : List.of("ceo", "hr")) {
            Set<Long> people = personIds(call(role, "all", 2026));
            assertThat(people).containsExactlyInAnyOrderElementsOf(allActive());
            assertThat(people).doesNotContain(id.get("a1z"));
        }
    }

    // 2 ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void aTeamHeadGetsOnlyTheirTeamAndACoHeadGetsTheOtherHeadsReportsToo() throws Exception {
        Set<Long> expected = ids("a1x", "a1y", "a1y1", "a2x");
        for (String head : List.of("a1", "a2")) {
            Map<String, Object> body = call(head, null, 2026);
            assertThat(personIds(body)).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(personIds(body)).doesNotContain(id.get("a1"), id.get("a2")); // heads are not members
            assertThat(scopeOf(body)).isEqualTo(String.valueOf(alphaTeam));
        }
    }

    // 3 ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void cannotReachWhatIsNotYours() throws Exception {
        asUser("a1", "employee", "all", 2026).andExpect(status().isForbidden());
        asUser("a1", "employee", String.valueOf(betaTeam), 2026).andExpect(status().isForbidden());
        asUser("plain", "employee", null, 2026).andExpect(status().isForbidden());
        asUser("plain", "employee", "all", 2026).andExpect(status().isForbidden());
        asUser("b1", "employee", String.valueOf(alphaTeam), 2026).andExpect(status().isForbidden());
    }

    @Test
    void aDetachedLineIsOutOfEveryTeamButStillInTheWholeCompany() throws Exception {
        Set<Long> team = personIds(call("a1", null, 2026));
        assertThat(team).doesNotContain(id.get("a1d"), id.get("a1d1"));
        assertThat(personIds(call("ceo", "all", 2026))).contains(id.get("a1d"), id.get("a1d1"));
    }

    // 4 ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void leaveCountsApprovedOnlyGroupedByMonthAndTypeWithLateFiledAndUnpaid() throws Exception {
        long e = id.get("a1y");
        long other = id.get("hr");
        // March 2026: Mon 02, Wed 04, Fri 20 sick; two personal.
        leave(e, "SICK", "2026-03-02", 2, "APPROVED", 2, 0, e, "2026-03-05T10:00:00+07:00", null);   // late (filed after start)
        leave(e, "SICK", "2026-03-04", 1, "APPROVED", 1, 0, e, "2026-03-04T18:00:00Z", null);        // 01:00 Bangkok on the 5th: late only in Bangkok time
        leave(e, "SICK", "2026-03-20", 1, "APPROVED", 1, 0, e, "2026-03-19T10:00:00+07:00", null);   // on time
        leave(e, "PERSONAL", "2026-03-10", 1, "APPROVED", 0, 1, other, "2026-03-12T10:00:00+07:00", null); // filed by someone else
        leave(e, "PERSONAL", "2026-03-12", 1, "APPROVED", 1, 0, e, "2026-03-13T10:00:00+07:00", "ระบบ");   // system note
        leave(e, "PERSONAL", "2026-03-14", 1, "APPROVED", 1, 0, e, "2026-03-16T10:00:00+07:00", null);   // self-filed after the day: counted
        for (String notCounted : List.of("SUBMITTED", "REJECTED", "CANCELLED")) {
            leave(e, "SICK", "2026-03-23", 5, notCounted, 0, 0, e, "2026-03-22T10:00:00+07:00", null);
        }
        leave(e, "SICK", "2025-12-31", 3, "APPROVED", 3, 0, e, "2025-12-30T10:00:00+07:00", null);   // other years
        leave(e, "SICK", "2027-01-04", 3, "APPROVED", 3, 0, e, "2027-01-03T10:00:00+07:00", null);
        leave(e, "SICK", "2026-04-03", 1.5, "APPROVED", 1.5, 0, e, "2026-04-02T10:00:00+07:00", null); // Friday

        List<Map<String, Object>> rows = list(call("ceo", "all", 2026), "leave").stream()
            .filter(r -> num(r, "employeeId") == e).toList();

        assertThat(rows).hasSize(3);
        Map<String, Object> marSick = row(rows, "2026-03", "SICK");
        assertThat(dbl(marSick, "days")).isEqualTo(4.0);
        assertThat(num(marSick, "requests")).isEqualTo(3);
        assertThat(dbl(marSick, "sickEdgeDays")).isEqualTo(3.0); // Mon 2 days + Fri 1 day; the Wednesday one is not an edge
        assertThat(num(marSick, "lateFiled")).isEqualTo(0); // sick leave is normally filed after the day: never "late"
        assertThat(dbl(marSick, "unpaidDays")).isEqualTo(0.0);
        Map<String, Object> marPersonal = row(rows, "2026-03", "PERSONAL");
        assertThat(dbl(marPersonal, "days")).isEqualTo(3.0);
        assertThat(num(marPersonal, "requests")).isEqualTo(3);
        assertThat(num(marPersonal, "lateFiled")).isEqualTo(1); // only the self-filed, note-less one
        assertThat(dbl(marPersonal, "unpaidDays")).isEqualTo(1.0);
        Map<String, Object> aprSick = row(rows, "2026-04", "SICK");
        assertThat(dbl(aprSick, "days")).isEqualTo(1.5);
        assertThat(dbl(aprSick, "sickEdgeDays")).isEqualTo(1.5);

        // The leave types that appear carry their yearly quota from hr.leave_type.
        Map<String, Object> sick = list(call("ceo", "all", 2026), "leaveTypes").stream()
            .filter(t -> "SICK".equals(t.get("code"))).findFirst().orElseThrow();
        assertThat(sick.get("nameTh")).isEqualTo("ลาป่วย");
        assertThat(dbl(sick, "annualQuotaDays")).isEqualTo(30.0);
    }

    // 5 ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void attendanceFiguresAreCorrectAndATimeExemptEmployeeHasNoneButKeepsLeave() throws Exception {
        long e = id.get("a1y");
        long exempt = id.get("a1x");
        day(e, "2026-03-02", "2026-03-02T08:00:00+07:00", "2026-03-02T17:00:00+07:00", 0, 0);
        day(e, "2026-03-03", "2026-03-03T08:30:00+07:00", "2026-03-03T17:00:00+07:00", 20, 0);
        day(e, "2026-03-04", "2026-03-04T09:00:00+07:00", null, 45, 0);                       // no check-out
        day(e, "2026-03-05", "2026-03-05T14:00:00+07:00", "2026-03-05T17:30:00+07:00", 0, 30);   // afternoon in: no avg; left early
        day(e, "2026-03-06", null, null, 0, 0);                                                  // no scan
        day(e, "2025-12-30", "2025-12-30T08:00:00+07:00", "2025-12-30T17:00:00+07:00", 10, 0);   // other year
        day(e, "2026-04-10", "2026-04-10T08:00:00+07:00", "2026-04-10T17:00:00+07:00", 0, 0);   // later month, also the latest day
        day(exempt, "2026-03-02", "2026-03-02T10:00:00+07:00", "2026-03-02T17:00:00+07:00", 60, 0);
        leave(exempt, "VACATION", "2026-03-16", 1, "APPROVED", 1, 0, exempt, "2026-03-10T10:00:00+07:00", null);

        Map<String, Object> body = call("ceo", "all", 2026);
        List<Map<String, Object>> mine = list(body, "attendance").stream().filter(r -> num(r, "employeeId") == e).toList();
        assertThat(mine).hasSize(2);
        Map<String, Object> march = mine.stream().filter(r -> "2026-03".equals(r.get("month"))).findFirst().orElseThrow();
        assertThat(num(march, "scanDays")).isEqualTo(4);
        assertThat(num(march, "lateDays")).isEqualTo(2);
        assertThat(num(march, "lateMinutes")).isEqualTo(65);
        assertThat(num(march, "noCheckOut")).isEqualTo(1);
        assertThat(num(march, "earlyLeave")).isEqualTo(1);
        assertThat(num(march, "morningScanDays")).isEqualTo(3);
        assertThat(dbl(march, "avgCheckInMinutes")).isEqualTo(510.0); // (08:00 + 08:30 + 09:00) / 3; the 14:00 scan is ignored
        assertThat(body.get("asOf")).isEqualTo("2026-04-10");

        assertThat(list(body, "attendance").stream().filter(r -> num(r, "employeeId") == exempt)).isEmpty();
        Map<String, Object> exemptPerson = list(body, "people").stream().filter(p -> num(p, "employeeId") == exempt).findFirst().orElseThrow();
        assertThat(exemptPerson.get("timeExempt")).isEqualTo(true);
        assertThat(list(body, "leave").stream().filter(r -> num(r, "employeeId") == exempt)).hasSize(1);
    }

    // 6 ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void scopesListsAllPlusEveryTeamInSortOrderForCeoAndOnlyOwnTeamsForAHead() throws Exception {
        Map<String, Object> ceo = call("ceo", null, 2026);
        List<Map<String, Object>> scopes = list(ceo, "scopes");
        assertThat(scopes.stream().map(s -> s.get("id")).toList())
            .containsExactly("all", String.valueOf(betaTeam), String.valueOf(alphaTeam));
        assertThat(scopes.get(0).get("label")).isEqualTo("ทั้งบริษัท");
        assertThat(scopes.get(1).get("label")).isEqualTo("ทีมเบตา");
        assertThat(scopeOf(ceo)).isEqualTo("all"); // defaults to the first allowed scope

        List<Map<String, Object>> headScopes = list(call("a1", null, 2026), "scopes");
        assertThat(headScopes.stream().map(s -> s.get("id")).toList()).containsExactly(String.valueOf(alphaTeam));
    }

    @Test
    void teamsCompareOnlyInsideTheWholeCompanyScope() throws Exception {
        List<Map<String, Object>> teams = list(call("ceo", "all", 2026), "teams");
        assertThat(teams.stream().map(t -> t.get("id")).toList()).containsExactly(String.valueOf(betaTeam), String.valueOf(alphaTeam));
        Map<String, Object> alpha = teams.get(1);
        assertThat(alpha.get("label")).isEqualTo("ทีมอัลฟา");
        assertThat(alpha.get("headNicknames")).asList().containsExactlyInAnyOrder("เอหนึ่ง", "เอสอง");
        assertThat(longs(alpha.get("memberIds"))).containsExactlyInAnyOrderElementsOf(ids("a1x", "a1y", "a1y1", "a2x"));

        assertThat(list(call("ceo", String.valueOf(alphaTeam), 2026), "teams")).isEmpty();
        assertThat(list(call("a1", null, 2026), "teams")).isEmpty();
    }

    @Test
    void yearDefaultsToTheCurrentBangkokYear() throws Exception {
        Map<String, Object> body = call("ceo", "all", null);
        assertThat(num(body, "year")).isEqualTo(LocalDate.now(ZoneId.of("Asia/Bangkok")).getYear());
    }


    // 7 ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void quotaUsageFollowsTheQuotaYearNotTheStartDate() throws Exception {
        long e = id.get("a1y");
        // 30 Dec 2025 - 2 Jan 2026, 4 days: 2 in 2025, 2 in 2026. In 2026's quotaUsage (2 days) and its January leave share (2 days, no request: it started in 2025).
        long span = leaveSpan(e, "SICK", "2025-12-30", "2026-01-02", 4, "APPROVED", 4, 0, e, "2025-12-29T10:00:00+07:00", null);
        quotaYear(span, 2025, 2);
        quotaYear(span, 2026, 2);
        // Starts 2 Jan 2026 but charged to quota year 2025 (carried over): in 2026's leave, NOT in 2026's quotaUsage.
        long carried = leave(e, "PERSONAL", "2026-01-02", 1, "APPROVED", 1, 0, e, "2026-01-01T10:00:00+07:00", null);
        quotaYear(carried, 2025, 1);
        // A type that reaches 2026 only through its quota year (starts 31 Dec 2025): still needs its quota in leaveTypes.
        quotaYear(leave(e, "VACATION", "2025-12-31", 1, "APPROVED", 1, 0, e, "2025-12-30T10:00:00+07:00", null), 2026, 1);
        // Plain 2026 request, plus a SUBMITTED one that must not count.
        quotaYear(leave(e, "SICK", "2026-02-09", 1.5, "APPROVED", 1.5, 0, e, "2026-02-08T10:00:00+07:00", null), 2026, 1.5);
        quotaYear(leave(e, "SICK", "2026-02-16", 5, "SUBMITTED", 0, 0, e, "2026-02-15T10:00:00+07:00", null), 2026, 5);

        Map<String, Object> body = call("ceo", "all", 2026);
        List<Map<String, Object>> usage = list(body, "quotaUsage").stream().filter(r -> num(r, "employeeId") == e).toList();
        assertThat(usage.stream().map(r -> r.get("leaveTypeCode") + "=" + dbl(r, "days")).toList())
            .containsExactlyInAnyOrder("SICK=3.5", "VACATION=1.0"); // SICK: 2 spilled in + 1.5
        assertThat(list(body, "leaveTypes").stream().map(r -> r.get("code")).toList()).contains("VACATION");
        List<Map<String, Object>> leaveRows = list(body, "leave").stream().filter(r -> num(r, "employeeId") == e).toList();
        assertThat(leaveRows.stream().map(r -> r.get("month") + r.get("leaveTypeCode").toString()).toList())
            .containsExactlyInAnyOrder("2026-01PERSONAL", "2026-01SICK", "2026-02SICK");

        // A team head sees quotaUsage only for their own team.
        assertThat(list(call("b1", null, 2026), "quotaUsage")).isEmpty();
    }

    @Test
    void averageCheckInIsWeightableByMorningScansOnly() throws Exception {
        long e = id.get("a1y");
        day(e, "2026-05-04", "2026-05-04T08:00:00+07:00", "2026-05-04T17:00:00+07:00", 0, 0);   // month A: 1 morning, 19 afternoon
        for (int d = 5; d <= 22; d++) {
            String date = String.format("2026-05-%02d", d);
            day(e, date, date + "T13:00:00+07:00", date + "T17:00:00+07:00", 0, 0);
        }
        day(e, "2026-05-25", "2026-05-25T13:00:00+07:00", "2026-05-25T17:00:00+07:00", 0, 0);
        for (int d = 1; d <= 10; d++) {
            String date = String.format("2026-06-%02d", d);
            day(e, date, date + "T09:00:00+07:00", date + "T17:00:00+07:00", 0, 0);
        }

        List<Map<String, Object>> mine = list(call("ceo", "all", 2026), "attendance").stream().filter(r -> num(r, "employeeId") == e).toList();
        Map<String, Object> may = mine.stream().filter(r -> "2026-05".equals(r.get("month"))).findFirst().orElseThrow();
        Map<String, Object> june = mine.stream().filter(r -> "2026-06".equals(r.get("month"))).findFirst().orElseThrow();
        assertThat(num(may, "scanDays")).isEqualTo(20);
        assertThat(num(may, "morningScanDays")).isEqualTo(1);
        assertThat(num(june, "morningScanDays")).isEqualTo(10);
    }

    // fix 1 ──────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void daysCoveredByApprovedLeaveAreNeverLateOrEarlyLeaveButOtherLeaveStatesDoNotExcuse() throws Exception {
        long e = id.get("a1y");
        day(e, "2026-03-02", "2026-03-02T13:00:00+07:00", "2026-03-02T17:00:00+07:00", 255, 0);   // approved morning leave: excused
        leaveSpan(e, "PERSONAL", "2026-03-02", "2026-03-02", 0.5, "APPROVED", 0.5, 0, e, "2026-03-01T10:00:00+07:00", null);
        day(e, "2026-03-03", "2026-03-03T13:00:00+07:00", "2026-03-03T17:00:00+07:00", 255, 0);   // rejected leave: still late
        leaveSpan(e, "PERSONAL", "2026-03-03", "2026-03-03", 0.5, "REJECTED", 0, 0, e, "2026-03-02T10:00:00+07:00", null);
        day(e, "2026-03-04", "2026-03-04T13:00:00+07:00", "2026-03-04T17:00:00+07:00", 255, 0);   // submitted leave: still late
        leaveSpan(e, "PERSONAL", "2026-03-04", "2026-03-04", 0.5, "SUBMITTED", 0, 0, e, "2026-03-03T10:00:00+07:00", null);
        day(e, "2026-03-05", "2026-03-05T08:00:00+07:00", "2026-03-05T14:00:00+07:00", 0, 180);   // approved afternoon leave: early leave excused
        leaveSpan(e, "PERSONAL", "2026-03-05", "2026-03-05", 0.5, "APPROVED", 0.5, 0, e, "2026-03-04T10:00:00+07:00", null);
        day(e, "2026-03-06", "2026-03-06T08:00:00+07:00", "2026-03-06T14:00:00+07:00", 0, 180);   // no leave: early leave counts
        day(e, "2026-03-09", "2026-03-09T09:00:00+07:00", "2026-03-09T17:00:00+07:00", 30, 0);    // inside a multi-day approved leave: excused
        leaveSpan(e, "VACATION", "2026-03-08", "2026-03-10", 2, "APPROVED", 2, 0, e, "2026-03-01T10:00:00+07:00", null);

        Map<String, Object> march = list(call("ceo", "all", 2026), "attendance").stream()
            .filter(r -> num(r, "employeeId") == e && "2026-03".equals(r.get("month"))).findFirst().orElseThrow();
        assertThat(num(march, "scanDays")).isEqualTo(6); // excused days still count as scans
        assertThat(num(march, "lateDays")).isEqualTo(2); // only the rejected and submitted days
        assertThat(num(march, "lateMinutes")).isEqualTo(510);
        assertThat(num(march, "earlyLeave")).isEqualTo(1); // only 6 Mar
        assertThat(num(march, "morningScanDays")).isEqualTo(3); // 5, 6 and 9 Mar; the 13:00 scans are afternoon
    }

    // D1 wiring ──────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void partialLeaveOnlyExcusesTheMinutesItCoversAndALeaveThatIsNotApprovedExcusesNothing() throws Exception {
        long e = id.get("a1y");
        // Row k: a timed leave Mon 13:30 -> Wed 12:30. Tuesday is wholly covered; Wednesday is covered until 12:30 (+ the break).
        long span = leaveSpan(e, "VACATION", "2026-03-09", "2026-03-11", 2, "APPROVED", 2, 0, e, "2026-03-01T10:00:00+07:00", null);
        timed(span, "13:30", "12:30");
        day(e, "2026-03-10", "2026-03-10T10:00:00+07:00", "2026-03-10T17:30:00+07:00", 90, 0);
        day(e, "2026-03-11", "2026-03-11T13:25:00+07:00", "2026-03-11T17:30:00+07:00", 295, 0);
        // Row j: the same morning leave, REJECTED: still late for all of it.
        long rejected = leaveSpan(e, "PERSONAL", "2026-03-16", "2026-03-16", 0.5, "REJECTED", 0, 0, e, "2026-03-15T10:00:00+07:00", null);
        timed(rejected, "08:30", "12:30");
        day(e, "2026-03-16", "2026-03-16T13:00:00+07:00", "2026-03-16T17:30:00+07:00", 270, 0);
        // Row d: approved morning leave, arrives an hour after the break: late only by that hour.
        timed(leaveSpan(e, "PERSONAL", "2026-03-17", "2026-03-17", 0.5, "APPROVED", 0.5, 0, e, "2026-03-15T10:00:00+07:00", null), "08:30", "12:30");
        day(e, "2026-03-17", "2026-03-17T14:30:00+07:00", "2026-03-17T17:30:00+07:00", 360, 0);
        // Row i: approved afternoon leave, but left before it began: still an early leave.
        timed(leaveSpan(e, "PERSONAL", "2026-03-18", "2026-03-18", 0.5, "APPROVED", 0.5, 0, e, "2026-03-15T10:00:00+07:00", null), "13:30", "17:30");
        day(e, "2026-03-18", "2026-03-18T08:30:00+07:00", "2026-03-18T11:30:00+07:00", 0, 360);

        Map<String, Object> march = list(call("ceo", "all", 2026), "attendance").stream()
            .filter(r -> num(r, "employeeId") == e && "2026-03".equals(r.get("month"))).findFirst().orElseThrow();
        assertThat(num(march, "scanDays")).isEqualTo(5);
        assertThat(num(march, "lateDays")).isEqualTo(2);      // 16 Mar (270) and 17 Mar (60)
        assertThat(num(march, "lateMinutes")).isEqualTo(330);
        assertThat(num(march, "earlyLeave")).isEqualTo(1);    // 18 Mar
    }

    @Test
    void aLeaveDayIsJudgedAgainstTheScheduleAssignedToTheEmployeesDivisionNotTheCompanyDefault() throws Exception {
        long division = jdbc.queryForObject("INSERT INTO hr.division (source_code, name_th, is_active) VALUES ('T9', 'ฝ่ายทดสอบ', TRUE) RETURNING division_id", Map.of(), Long.class);
        long e = emp("T-DIV9", "ดีไนน์", id.get("ceo"), true);
        jdbc.update("UPDATE hr.employee SET division_id = :d WHERE employee_id = :e", Map.of("d", division, "e", e));
        long schedule = jdbc.queryForObject("""
            INSERT INTO hr.work_schedule (code, name_th, work_start, work_end, grace_minutes)
            VALUES ('T_0900', 'ทดสอบ 09:00-18:00', TIME '09:00', TIME '18:00', 15) RETURNING work_schedule_id
            """, Map.of(), Long.class);
        jdbc.update("INSERT INTO hr.work_schedule_day (work_schedule_id, day_of_week) SELECT :s, d FROM generate_series(1, 7) d", Map.of("s", schedule));
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from)
            VALUES ('DIVISION', :d, :s, DATE '2024-01-01')
            """, Map.of("d", division, "s", schedule));
        // Approved leave 09:00-11:00, arrives 11:05: 5 uncovered minutes after the assigned 09:00 start, within its grace of 15.
        // Against the company 08:30 start the same arrival leaves 35 uncovered minutes, so a dropped division id makes it late.
        timed(leaveSpan(e, "PERSONAL", "2026-03-02", "2026-03-02", 0.25, "APPROVED", 0.25, 0, e, "2026-03-01T10:00:00+07:00", null), "09:00", "11:00");
        day(e, "2026-03-02", "2026-03-02T11:05:00+07:00", "2026-03-02T18:00:00+07:00", 125, 0);

        // The app's own resolver (assignment tiers over the company-wide fallback), not the fixed one the other cases inject.
        var properties = new th.co.glr.hr.config.AppProperties();
        var resolver = new th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver(
            new th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository(jdbc, properties),
            new th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver(properties), properties);
        mvc = MockMvcBuilders.standaloneSetup(new PeopleInsightsController(new PeopleInsightsService(new PeopleInsightsRepository(jdbc), resolver), new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        Map<String, Object> body = call("ceo", "all", 2026);

        Map<String, Object> march = list(body, "attendance").stream()
            .filter(r -> num(r, "employeeId") == e && "2026-03".equals(r.get("month"))).findFirst().orElseThrow();
        assertThat(num(march, "scanDays")).isEqualTo(1);
        assertThat(num(march, "lateDays")).isZero();
    }

    // fix 2 ──────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void peopleWhoLeftDuringTheYearStayInThatYearsFiguresAndAreMarkedInactive() throws Exception {
        long z = id.get("a1z");
        leave(z, "SICK", "2026-03-10", 1, "APPROVED", 1, 0, z, "2026-03-10T10:00:00+07:00", null);
        long left = emp("T-LEFT", "ลาออก", id.get("ceo"), false); // attendance only, outside any team
        day(left, "2026-03-11", "2026-03-11T08:00:00+07:00", "2026-03-11T17:00:00+07:00", 0, 0);
        long gone = emp("T-GONE", "หายไป", id.get("ceo"), false); // inactive, nothing in 2026
        leave(gone, "SICK", "2026-03-10", 1, "REJECTED", 0, 0, gone, "2026-03-10T10:00:00+07:00", null);
        leave(gone, "SICK", "2025-03-10", 1, "APPROVED", 1, 0, gone, "2025-03-10T10:00:00+07:00", null);

        Map<String, Object> all = call("ceo", "all", 2026);
        Map<String, Object> zPerson = list(all, "people").stream().filter(p -> num(p, "employeeId") == z).findFirst().orElseThrow();
        assertThat(zPerson.get("active")).isEqualTo(false);
        assertThat(list(all, "people").stream().filter(p -> num(p, "employeeId") == id.get("a1y")).findFirst().orElseThrow().get("active")).isEqualTo(true);
        assertThat(personIds(all)).contains(left).doesNotContain(gone);
        assertThat(list(all, "leave").stream().filter(r -> num(r, "employeeId") == z).map(r -> r.get("month")).toList()).containsExactly("2026-03");
        assertThat(list(all, "attendance").stream().filter(r -> num(r, "employeeId") == left)).hasSize(1);
        assertThat(personIds(call("ceo", "all", 2025))).contains(gone).doesNotContain(z);

        assertThat(personIds(call("a1", null, 2026))).contains(z).doesNotContain(left); // inside a1's line, not the other inactive
        assertThat(personIds(call("a2", null, 2026))).contains(z); // co-heads share the line
        assertThat(personIds(call("b1", null, 2026))).doesNotContain(z); // outside b1's line
        assertThat(longs(list(all, "teams").stream().filter(t -> t.get("id").equals(String.valueOf(alphaTeam))).findFirst().orElseThrow().get("memberIds")))
            .contains(z);
    }

    // fix 3 ──────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void leaveSpanningMonthsIsSplitByCalendarDaysAndOnlyTheStartMonthCountsTheRequest() throws Exception {
        long e = id.get("a1y");
        // 20 Apr - 14 May: 25 calendar days, 11 in April and 14 in May. 20 days and 5 unpaid days split 8.8/11.2 and 2.2/2.8.
        leaveSpan(e, "VACATION", "2026-04-20", "2026-05-14", 20, "APPROVED", 15, 5, e, "2026-04-10T10:00:00+07:00", null);
        // 30 Dec 2025 - 3 Jan 2026: 5 calendar days, 3 in 2026. 4 days -> 2.4 in January; the request itself belongs to 2025.
        leaveSpan(e, "SICK", "2025-12-30", "2026-01-03", 4, "APPROVED", 4, 0, e, "2025-12-31T10:00:00+07:00", null);
        // 30 Dec 2026 - 2 Jan 2027: 4 calendar days, 2 in 2026. 3 days -> 1.5 in December. Starts on a Wednesday: not a sick edge.
        leaveSpan(e, "SICK", "2026-12-30", "2027-01-02", 3, "APPROVED", 3, 0, e, "2027-01-05T10:00:00+07:00", null);
        // 3 days over 3 calendar days across a month end: 1.0 + 2.0 exactly, remainder on the last month.
        leaveSpan(e, "PERSONAL", "2026-06-29", "2026-07-01", 1, "APPROVED", 1, 0, e, "2026-06-20T10:00:00+07:00", null);

        List<Map<String, Object>> rows = list(call("ceo", "all", 2026), "leave").stream().filter(r -> num(r, "employeeId") == e).toList();
        Map<String, Object> apr = row(rows, "2026-04", "VACATION");
        Map<String, Object> may = row(rows, "2026-05", "VACATION");
        assertThat(dbl(apr, "days")).isEqualTo(8.8);
        assertThat(dbl(may, "days")).isEqualTo(11.2);
        assertThat(dbl(apr, "unpaidDays")).isEqualTo(2.2);
        assertThat(dbl(may, "unpaidDays")).isEqualTo(2.8);
        assertThat(num(apr, "requests")).isEqualTo(1);
        assertThat(num(may, "requests")).isEqualTo(0);

        Map<String, Object> jan = row(rows, "2026-01", "SICK");
        assertThat(dbl(jan, "days")).isEqualTo(2.4);
        assertThat(num(jan, "requests")).isEqualTo(0);
        assertThat(dbl(jan, "sickEdgeDays")).isEqualTo(0.0);
        Map<String, Object> dec = row(rows, "2026-12", "SICK");
        assertThat(dbl(dec, "days")).isEqualTo(1.5);
        assertThat(num(dec, "requests")).isEqualTo(1);
        assertThat(rows.stream().filter(r -> "2027-01".equals(r.get("month")))).isEmpty();

        Map<String, Object> jun = row(rows, "2026-06", "PERSONAL");
        Map<String, Object> jul = row(rows, "2026-07", "PERSONAL");
        assertThat(dbl(jun, "days") + dbl(jul, "days")).isEqualTo(1.0);
        assertThat(dbl(jun, "days")).isEqualTo(0.67);
        assertThat(dbl(jul, "days")).isEqualTo(0.33);
    }

    // fix 4 ──────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void yearsRunFromTheEarliestDataYearAtLeast2024UpToNextYearAndStrayRowsDoNotWidenThem() throws Exception {
        long e = id.get("a1y");
        int current = LocalDate.now(ZoneId.of("Asia/Bangkok")).getYear();
        leave(e, "SICK", "2025-05-06", 1, "APPROVED", 1, 0, e, "2025-05-06T10:00:00+07:00", null);
        leave(e, "SICK", "2022-05-06", 1, "REJECTED", 0, 0, e, "2022-05-06T10:00:00+07:00", null); // not approved: no year
        assertThat(yearsOf(call("ceo", "all", 2026))).containsExactlyElementsOf(new java.util.TreeSet<>(List.of(2025, current))); // only approved data counts; the current year is always there

        day(e, "2000-01-03", "2000-01-03T08:00:00+07:00", "2000-01-03T17:00:00+07:00", 0, 0);   // stray attendance far in the past
        leave(e, "SICK", "2099-01-05", 1, "APPROVED", 1, 0, e, "2099-01-05T10:00:00+07:00", null); // stray leave far in the future
        List<Integer> years = yearsOf(call("ceo", "all", 2026));
        assertThat(years).doesNotContain(2000, 2099).isSorted().doesNotHaveDuplicates();
        assertThat(years.get(0)).isEqualTo(2024);
        assertThat(years.get(years.size() - 1)).isEqualTo(current + 1);
        assertThat(years).contains(current);
    }

    private static List<Integer> yearsOf(Map<String, Object> body) {
        return ((List<?>) body.get("years")).stream().map(o -> ((Number) o).intValue()).toList();
    }

    // fix 7 ──────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test
    void everyTeamsMembersAreCorrectInOneWholeCompanyResponse() throws Exception {
        List<Map<String, Object>> teams = list(call("ceo", "all", 2026), "teams");
        Map<String, Map<String, Object>> byId = teams.stream().collect(Collectors.toMap(t -> (String) t.get("id"), t -> t));
        assertThat(longs(byId.get(String.valueOf(betaTeam)).get("memberIds"))).containsExactlyInAnyOrderElementsOf(ids("b1x"));
        assertThat(longs(byId.get(String.valueOf(alphaTeam)).get("memberIds")))
            .containsExactlyInAnyOrderElementsOf(ids("a1x", "a1y", "a1y1", "a2x"));
    }

    // helpers ────────────────────────────────────────────────────────────────────────────────────────────────────
    private org.springframework.test.web.servlet.ResultActions asUser(String who, String role, String scope, Integer year) throws Exception {
        MockHttpSession s = new MockHttpSession();
        long employeeId = id.get(who);
        s.setAttribute(SessionContext.SESSION_USER_KEY, new UserPrincipal(employeeId, who + "@glr.co.th", who, role, employeeId,
            true, LocalDate.of(2020, 1, 1), false, null, false));
        var request = get(URL).session(s);
        if (scope != null) request = request.param("scope", scope);
        if (year != null) request = request.param("year", String.valueOf(year));
        return mvc.perform(request);
    }

    /** A signed-in caller with this role; ceo/hr are the employee rows of the same name, a head is their own employee row. */
    private Map<String, Object> call(String who, String scope, Integer year) throws Exception {
        String role = who.equals("ceo") || who.equals("hr") ? who : "employee";
        String body = asUser(who, role, scope, year).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readValue(body, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    private static Set<Long> personIds(Map<String, Object> body) {
        return list(body, "people").stream().map(p -> num(p, "employeeId")).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static String scopeOf(Map<String, Object> body) {
        return (String) ((Map<String, Object>) body.get("scope")).get("id");
    }

    private static Set<Long> longs(Object raw) {
        return ((List<?>) raw).stream().map(o -> ((Number) o).longValue()).collect(Collectors.toSet());
    }

    private static long num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    private static double dbl(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String month, String type) {
        return rows.stream().filter(r -> month.equals(r.get("month")) && type.equals(r.get("leaveTypeCode"))).findFirst().orElseThrow();
    }

    private Set<Long> ids(String... keys) {
        return java.util.Arrays.stream(keys).map(id::get).collect(Collectors.toSet());
    }

    private Set<Long> allActive() {
        return new java.util.HashSet<>(jdbc.queryForList("SELECT employee_id FROM hr.employee WHERE is_active", Map.of(), Long.class));
    }

    private long emp(String code, String nick, Long reportsTo, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th, nickname, reports_to_employee_id,
                                     hire_date, is_active)
            VALUES (:code, :code, :nick, 'ทดสอบ', :nick, :reportsTo, :hire, :active) RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("nick", nick).addValue("reportsTo", reportsTo)
                .addValue("hire", LocalDate.of(2020, 1, 1)).addValue("active", active), Long.class);
    }

    private long team(String name, int sort) {
        return jdbc.queryForObject("INSERT INTO hr.insight_team (name_th, sort_order) VALUES (:n, :s) RETURNING team_id",
            Map.of("n", name, "s", sort), Long.class);
    }

    private void head(long team, String who) {
        jdbc.update("INSERT INTO hr.insight_team_head (team_id, employee_id) VALUES (:t, :e)", Map.of("t", team, "e", id.get(who)));
    }

    private void setting(String who, boolean timeExempt, boolean detached) {
        jdbc.update("INSERT INTO hr.insight_employee_setting (employee_id, time_exempt, detached_line) VALUES (:e, :t, :d)",
            Map.of("e", id.get(who), "t", timeExempt, "d", detached));
    }

    private long leave(long employee, String type, String start, double days, String status, double paid, double unpaid,
                       long requestedBy, String requestedAt, String systemNote) {
        return leaveSpan(employee, type, start, start, days, status, paid, unpaid, requestedBy, requestedAt, systemNote);
    }

    private long leaveSpan(long employee, String type, String start, String end, double days, String status, double paid, double unpaid,
                           long requestedBy, String requestedAt, String systemNote) {
        return jdbc.queryForObject("""
            INSERT INTO hr.leave_request (employee_id, leave_type_code, start_date, end_date, total_days, quota_year, reason,
                status, paid_days, unpaid_days, quota_remaining_before, quota_remaining_after, requested_by_id, requested_at,
                system_note)
            VALUES (:e, :type, CAST(:start AS date), CAST(:end AS date), :days, CAST(substr(:start, 1, 4) AS smallint), 'ทดสอบ',
                :status, :paid, :unpaid, 10, 9, :by, CAST(:at AS timestamptz), :note)
            RETURNING leave_request_id
            """, new MapSqlParameterSource().addValue("e", employee).addValue("type", type).addValue("start", start)
                .addValue("end", end).addValue("days", days).addValue("status", status).addValue("paid", status.equals("APPROVED") ? paid : 0)
                .addValue("unpaid", status.equals("APPROVED") ? unpaid : 0).addValue("by", requestedBy)
                .addValue("at", requestedAt).addValue("note", systemNote), Long.class);
    }

    /** Makes an inserted request a timed one: start_time belongs to start_date, end_time to end_date. */
    private void timed(long leaveRequestId, String startTime, String endTime) {
        jdbc.update("UPDATE hr.leave_request SET start_time = CAST(:s AS time), end_time = CAST(:e AS time) WHERE leave_request_id = :id",
            Map.of("s", startTime, "e", endTime, "id", leaveRequestId));
    }

    private void quotaYear(long leaveRequestId, int year, double days) {
        jdbc.update("""
            INSERT INTO hr.leave_request_quota_year (leave_request_id, quota_year, total_days, paid_days, unpaid_days,
                quota_remaining_before, quota_remaining_after, own_quota_days)
            VALUES (:id, :y, :d, :d, 0, 10, 10 - :d, :d)
            """, Map.of("id", leaveRequestId, "y", year, "d", days));
    }

    private void day(long employee, String date, String checkIn, String checkOut, int lateMinutes, int earlyLeaveMinutes) {
        jdbc.update("""
            INSERT INTO hr.attendance_daily (employee_id, work_date, site_code, check_in, check_out, late_minutes,
                early_leave_minutes, punch_count)
            VALUES (:e, CAST(:d AS date), 'SHOWROOM', CAST(:in AS timestamptz), CAST(:out AS timestamptz), :late, :early, 1)
            """, new MapSqlParameterSource().addValue("e", employee).addValue("d", date).addValue("in", checkIn)
                .addValue("out", checkOut).addValue("late", lateMinutes).addValue("early", earlyLeaveMinutes));
    }
}
