package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.peopleinsights.PeopleInsightsRepository;
import th.co.glr.hr.peopleinsights.PeopleInsightsService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The menu flag end to end: the real AuthService, the real PeopleInsightsService and a real database. A plain
 * employee (neither hr nor ceo) sees the People Insights menu exactly when they head a team; hr and ceo are decided by
 * role before any lookup, so they could not prove the lookup is wired. */
class AuthServiceTeamHeadMenuIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "Str0ngPass!";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private AuthService service;
    private long employeeId;

    @BeforeEach
    void wire() {
        EmployeeAuthRepository repo = new EmployeeAuthRepository(jdbc);
        AuditService audit = new AuditService(new AuditLogRepository(jdbc), new ObjectMapper().findAndRegisterModules());
        PeopleInsightsService insights = new PeopleInsightsService(new PeopleInsightsRepository(jdbc),
            (id, division, department, date) -> new WorkSchedule(ZoneId.of("Asia/Bangkok"), LocalTime.of(8, 30), LocalTime.of(17, 30), 15,
                Set.of(DayOfWeek.MONDAY)));
        service = new AuthService(repo, encoder, audit, new ViewAsService(repo, audit, ""), insights);
        employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th, email, password_hash,
                                     must_change_password, hire_date, is_active)
            VALUES ('PI-HEAD', 'PI-HEAD', 'ทดสอบ', 'หัวหน้า', 'pi.head@glr.co.th', :hash, FALSE, :hire, TRUE)
            RETURNING employee_id
            """, Map.of("hash", encoder.encode(PASSWORD), "hire", LocalDate.of(2020, 1, 1)), Long.class);
    }

    @Test
    void anEmployeeSeesThePeopleInsightsMenuOnlyOnceTheyHeadATeam() {
        AuthResponse before = login();
        assertThat(before.user().role()).isEqualTo("employee");
        assertThat(before.canViewPeopleInsights()).isFalse();

        long team = jdbc.queryForObject("INSERT INTO hr.insight_team (name_th, sort_order) VALUES ('ทีมทดสอบ', 1) RETURNING team_id",
            Map.of(), Long.class);
        jdbc.update("INSERT INTO hr.insight_team_head (team_id, employee_id) VALUES (:t, :e)", Map.of("t", team, "e", employeeId));

        assertThat(login().canViewPeopleInsights()).isTrue();
    }

    private AuthResponse login() {
        return service.login(new LoginRequest("pi.head@glr.co.th", PASSWORD, null), new MockHttpServletRequest());
    }
}
