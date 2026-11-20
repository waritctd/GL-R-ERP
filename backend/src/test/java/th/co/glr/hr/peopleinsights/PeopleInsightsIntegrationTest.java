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
