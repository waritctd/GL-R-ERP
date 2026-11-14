package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Pins the promise that an employee's address logs them in however it is capitalised, and with whatever
 * whitespace survived the paste — through the real AuthService, the real EmployeeAuthRepository and its real
 * SQL, against real PostgreSQL. */
class LoginEmailNormalizationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "Str0ngPass!";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AuthService service;

    @BeforeEach
    void wireRealCollaborators() {
        EmployeeAuthRepository repo = new EmployeeAuthRepository(jdbc);
        AuditService audit = new AuditService(new AuditLogRepository(jdbc), new ObjectMapper().findAndRegisterModules());
        service = new AuthService(repo, encoder, audit, new ViewAsService(repo, audit, ""));
    }

    /** The shape the owner reported: the address is stored with capitals, exactly as HR typed it into the employee
     * form. Every casing the person might type must reach that row. */
    @Test
    void logsInAgainstAMixedCaseStoredAddressWhateverTheCallerTypes() {
        long id = insertEmployeeWithEmail("CASE-001", "Suneesllim.1977@gmail.com");

        assertThat(loginAs("Suneesllim.1977@gmail.com").user().id()).isEqualTo(id);
        assertThat(loginAs("suneesllim.1977@gmail.com").user().id()).isEqualTo(id);
        assertThat(loginAs("SUNEESLLIM.1977@GMAIL.COM").user().id()).isEqualTo(id);
        assertThat(loginAs("SuNeEsLlIm.1977@GmAiL.cOm").user().id()).isEqualTo(id);
    }

    /** The mirror image: stored lowercase, typed with capitals — e.g. by a phone keyboard. This one guards
     * LoginRequest's constructor, NOT the SQL, and the difference was found by mutation rather than assumed. */
    @Test
    void logsInAgainstALowercaseStoredAddressWhenTheCallerTypesCapitals() {
        long id = insertEmployeeWithEmail("CASE-002", "somchai.p@glr.co.th");

        assertThat(loginAs("Somchai.P@glr.co.th").user().id()).isEqualTo(id);
        assertThat(loginAs("SOMCHAI.P@GLR.CO.TH").user().id()).isEqualTo(id);
    }

    /** The half that was actually broken. AuthController#login is @Valid, so LoginRequest's @Email runs on the
     * constructed record — and Hibernate Validator's pattern admits no whitespace — while AuthService's .trim()
     * only ran afterwards. */
    @Test
    void logsInWhenTheAddressArrivesWithSurroundingWhitespace() {
        long id = insertEmployeeWithEmail("CASE-003", "warehouse.manager@glr.co.th");

        assertThat(loginAs("  warehouse.manager@glr.co.th").user().id()).isEqualTo(id);
        assertThat(loginAs("warehouse.manager@glr.co.th  ").user().id()).isEqualTo(id);
        assertThat(loginAs("  Warehouse.Manager@glr.co.th  ").user().id()).isEqualTo(id);
    }

    /** Asked the wrong way round. Everything above would also pass against a predicate that matched any row at all,
     * so this is the case that gives the others their meaning: a different address must be refused however it is
     * capitalised, and a near-miss must not be folded into a hit. */
    @Test
    void rejectsADifferentAddressNoMatterHowItIsCapitalised() {
        insertEmployeeWithEmail("CASE-004", "Suneesllim.1977@gmail.com");

        assertThatThrownBy(() -> loginAs("someone.else@gmail.com")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> loginAs("SOMEONE.ELSE@GMAIL.COM")).isInstanceOf(ApiException.class);
        // A prefix of the real address, not the address: LOWER() must not have become LIKE.
        assertThatThrownBy(() -> loginAs("Suneesllim.1977@gmail.co")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> loginAs("uneesllim.1977@gmail.com")).isInstanceOf(ApiException.class);
    }

    /** The password is emphatically NOT normalised alongside the email. Leading and trailing spaces are legitimate
     * password characters, and trimming them would silently lock out anyone whose password has one — a far worse
     * failure than the one being fixed. */
    @Test
    void doesNotTrimThePasswordWhileNormalisingTheEmail() {
        insertEmployeeWithEmail("CASE-005", "spacey@glr.co.th");

        assertThatThrownBy(() -> loginAs("spacey@glr.co.th", " " + PASSWORD))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> loginAs("spacey@glr.co.th", PASSWORD + " "))
            .isInstanceOf(ApiException.class);
    }

    private AuthResponse loginAs(String email) {
        return loginAs(email, PASSWORD);
    }

    private AuthResponse loginAs(String email, String password) {
        return service.login(new LoginRequest(email, password, null), new MockHttpServletRequest());
    }

    private long insertEmployeeWithEmail(String code, String email) {
        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, 'ฝ่ายบุคคล', TRUE) RETURNING division_id
            """, Map.of("code", code), Long.class);

        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("email", email);
        params.put("hash", encoder.encode(PASSWORD));
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     email, password_hash, must_change_password,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :email, :hash, FALSE, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
