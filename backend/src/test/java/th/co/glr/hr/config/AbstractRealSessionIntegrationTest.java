package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Shared harness for the tests that ask what a REAL signed-in session can still do: the real
 * embedded Tomcat on a random port, real HTTP, real Postgres, the real {@code SecurityFilterChain}.
 *
 * <p>That is not thoroughness for its own sake. A session's lifetime is decided by the servlet
 * container's own validity check and expiry sweep, which {@code MockMvc}'s {@code MockHttpSession}
 * never runs, and both failure directions are silent: a "never" the container reads as "already
 * expired" logs the whole company out on every request, and an account re-check that is wired but
 * never reached leaves a dismissed employee signed in. Neither shows up anywhere but here.
 *
 * <p><b>The session store is Tomcat's own, in memory — NOT {@code hr.spring_session}.</b> The pom
 * still carries {@code spring-session-jdbc} and its comment still says sessions are externalized to
 * Postgres (#23), but that has not been true since the Spring Boot 4.1 upgrade (ec230864,
 * 2026-07-07): Boot 4 moved the session auto-configuration into {@code spring-boot-session-jdbc},
 * which this build does not depend on, so nothing wires Spring Session at all. The first draft of
 * these tests autowired {@code JdbcIndexedSessionRepository} and could not start — there is no
 * such bean — and production agrees: the newest row in {@code hr.spring_session} was last touched
 * on 2026-07-07. So the helpers below read and age a session where it actually lives
 * ({@link #stored}); "a session never expires" therefore means "until the backend restarts".
 * Restoring the database-backed store is a separate decision and no part of these tests.
 *
 * <p>Idleness is produced by AGEING the stored session ({@link #ageSession}) rather than sleeping,
 * and the container's background expiry sweep is run directly ({@link #runExpirySweep}) rather
 * than waited for.
 *
 * <p>Concrete subclasses carry their own {@code @SpringBootTest} (they differ in configuration) and
 * their own {@code @EnabledIf} — which JUnit does not inherit; see
 * {@code AbstractPostgresIntegrationTest}'s Javadoc.
 */
abstract class AbstractRealSessionIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    static final String PASSWORD = "S3curePassw0rd!";
    /** {@code GET /api/employees} is {@code requireAnyRole(user, "hr")} — 200 for hr, 403 otherwise. */
    static final String HR_ONLY = "/api/employees?size=1";
    static final String ME = "/api/auth/me";

    @LocalServerPort
    int port;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ServletWebServerApplicationContext server;

    // ── a signed-in browser ──────────────────────────────────────────────────────────────────

    /**
     * A browser's two cookies, held by hand. The session cookie is captured ONCE, at sign-in, and
     * deliberately never updated from later responses: when the server ends a session it also
     * tells the browser to drop the cookie, and a helper that obeyed would prove nothing about
     * what an OLD cookie — a forgotten device, a copied one — can still do. That is the question
     * these tests ask.
     */
    final class Browser {
        private final HttpClient http = HttpClient.newHttpClient();
        private String sessionCookie;
        private String xsrfToken;

        HttpResponse<String> signIn(String email) throws Exception {
            String body = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                if (pair.startsWith("GLR_HR_SESSION=")) sessionCookie = pair;
                if (pair.startsWith("XSRF-TOKEN=")) xsrfToken = pair.substring("XSRF-TOKEN=".length());
            }
            return response;
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(request(path).GET().build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        /** An unsafe method, so it carries the double-submit CSRF pair CsrfCookieFilter demands. */
        HttpResponse<String> post(String path) throws Exception {
            return http.send(request(path).header("X-XSRF-TOKEN", xsrfToken)
                .POST(BodyPublishers.noBody()).build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpRequest.Builder request(String path) {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(base() + path));
            StringBuilder cookies = new StringBuilder();
            if (sessionCookie != null) cookies.append(sessionCookie);
            if (xsrfToken != null) {
                cookies.append(cookies.isEmpty() ? "" : "; ").append("XSRF-TOKEN=").append(xsrfToken);
            }
            if (!cookies.isEmpty()) builder.header("Cookie", cookies.toString());
            return builder;
        }

        /** The container's session cookie carries the session id itself. */
        String sessionId() {
            return sessionCookie.substring("GLR_HR_SESSION=".length());
        }
    }

    String base() {
        return "http://127.0.0.1:" + port;
    }

    Browser signIn(Account account) throws Exception {
        Browser browser = new Browser();
        HttpResponse<String> response = browser.signIn(account.email());
        assertThat(response.statusCode()).as("sign-in: %s", response.body()).isEqualTo(200);
        assertThat(browser.sessionCookie).as("the sign-in response sets the session cookie").isNotNull();
        return browser;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    record Account(long id, String email) {}

    /** An active employee who can sign in, in the ฝ่าย whose {@code source_code} is
     * {@code divisionCode} — which is what {@code DivisionAccessPolicy#roleFor} derives the role
     * from ("HR" → hr, anything it does not recognise → employee). */
    Account employee(String divisionCode, String divisionName) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String email = "session-" + tag + "@glr.co.th";
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, email, is_active, password_hash, must_change_password, division_id)
            VALUES (:code, :email, TRUE, :hash, FALSE, :divisionId)
            RETURNING employee_id
            """,
            Map.of(
                "code", "SES-" + tag,
                "email", email,
                "hash", passwordEncoder.encode(PASSWORD),
                "divisionId", divisionId(divisionCode, divisionName)),
            Long.class);
        return new Account(id, email);
    }

    long divisionId(String code, String name) {
        var existing = jdbc.queryForList(
            "SELECT division_id FROM hr.division WHERE lower(source_code) = lower(:code) ORDER BY division_id LIMIT 1",
            Map.of("code", code), Long.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbc.queryForObject(
            "INSERT INTO hr.division (source_code, name_th, is_active) VALUES (:code, :name, TRUE) RETURNING division_id",
            Map.of("code", code, "name", name), Long.class);
    }

    void moveToDivision(Account account, String divisionCode, String divisionName) {
        jdbc.update("UPDATE hr.employee SET division_id = :divisionId WHERE employee_id = :id",
            Map.of("divisionId", divisionId(divisionCode, divisionName), "id", account.id()));
    }

    void setActive(Account account, boolean active) {
        jdbc.update("UPDATE hr.employee SET is_active = :active WHERE employee_id = :id",
            Map.of("active", active, "id", account.id()));
    }

    // ── the container's own session store ────────────────────────────────────────────────────

    private Manager sessionManager() {
        TomcatWebServer tomcat = (TomcatWebServer) server.getWebServer();
        Context context = (Context) tomcat.getTomcat().getHost().findChildren()[0];
        return context.getManager();
    }

    /** The live session behind this browser's cookie, or {@code null} once it has been ended. */
    StandardSession stored(Browser browser) throws Exception {
        return (StandardSession) sessionManager().findSession(browser.sessionId());
    }

    /**
     * Makes the stored session look as if its last request was {@code idle} ago — exactly what a
     * browser left alone produces. {@code StandardSession#setCreationTime} is the container's own
     * public setter for the three timestamps its idle check reads (creation, last access, this
     * access); it exists for session persistence and replication.
     *
     * <p>Re-applied until it sticks: the browser gets its response before the request thread has
     * finished, and Tomcat's {@code endAccess()} then stamps {@code thisAccessedTime = now}. On a
     * slow runner that stamp can land AFTER the ageing and silently undo it, so the next request
     * sees an idle time of zero (seen as "expected 401 but was 200" on CI, never locally). The
     * loop watches the timestamp it set and sets it again when a late {@code endAccess} moved it.
     */
    void ageSession(Browser browser, Duration idle) throws Exception {
        StandardSession session = stored(browser);
        assertThat(session).as("the session to age").isNotNull();
        long past = System.currentTimeMillis() - idle.toMillis();
        for (int attempt = 0; attempt < 100; attempt++) {
            session.setCreationTime(past);
            Thread.sleep(20);
            if (session.getThisAccessedTimeInternal() == past && session.getLastAccessedTimeInternal() == past) {
                return;
            }
        }
        throw new AssertionError("the session's access time kept being reset by an in-flight request");
    }

    /** Tomcat's background pass that throws expired sessions away, run now instead of on its timer. */
    void runExpirySweep() {
        ((ManagerBase) sessionManager()).processExpires();
    }
}
