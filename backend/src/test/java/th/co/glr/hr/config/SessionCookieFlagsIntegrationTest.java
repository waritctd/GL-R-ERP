package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Pure verification test (asserts current behavior, changes nothing): pins the session cookie
 * flags on the real {@code POST /api/auth/login} response over the real embedded servlet
 * container (needed because {@code server.servlet.session.cookie.*} — {@code HttpOnly},
 * {@code SameSite}, {@code Secure} — is applied by Spring Session's cookie serializer at the
 * servlet-container layer, which {@code MockMvc.webAppContextSetup} does not exercise; see
 * {@link SecurityHeadersFilterTest} / {@link th.co.glr.hr.auth.LoginRateLimitFilterTest} for the
 * filter-level header/rate-limit coverage this test intentionally does not duplicate).
 *
 * <p>Boots the real context on a random port and needs a real Postgres, resolved by
 * {@link PostgresTestSupport} (TEST_DB_URL override, else a throwaway Testcontainers Postgres);
 * skipped gracefully when neither is available, mirroring the other integration tests in this
 * package.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionCookieFlagsIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private static final String PASSWORD = "S3curePassw0rd!";

    @LocalServerPort
    private int port;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void loginResponseSetsHttpOnlyAndSameSiteLaxSessionCookie() throws Exception {
        String email = "nft-cookie-check+" + System.nanoTime() + "@glr.co.th";
        seedActiveEmployee(email);

        String requestBody = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
        // No CookieHandler attached, so raw Set-Cookie headers are visible on the response
        // instead of being consumed by an in-JVM cookie jar.
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        List<String> setCookieHeaders = response.headers().allValues("Set-Cookie");
        assertThat(setCookieHeaders).isNotEmpty();

        String sessionCookie = setCookieHeaders.stream()
            .filter(value -> value.startsWith("GLR_HR_SESSION="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No GLR_HR_SESSION cookie in Set-Cookie: " + setCookieHeaders));

        assertThat(sessionCookie).containsIgnoringCase("HttpOnly");
        assertThat(sessionCookie).containsIgnoringCase("SameSite=Lax");

        // server.servlet.session.cookie.secure defaults to true (application.yml); assert the
        // Secure attribute is present only when that property actually resolves true in this
        // context, so the test tracks configuration rather than hardcoding an assumption.
        boolean secureConfigured = secureCookieConfigured();
        if (secureConfigured) {
            assertThat(sessionCookie).containsIgnoringCase("Secure");
        } else {
            assertThat(sessionCookie).doesNotContainIgnoringCase("Secure");
        }
    }

    private boolean secureCookieConfigured() {
        String value = System.getenv("SERVER_SESSION_COOKIE_SECURE");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }

    private void seedActiveEmployee(String email) {
        jdbc.update("""
            INSERT INTO hr.employee (employee_code, email, is_active, password_hash, must_change_password)
            VALUES (:code, :email, TRUE, :hash, FALSE)
            """,
            Map.of(
                "code", "NFT-" + System.nanoTime(),
                "email", email,
                "hash", passwordEncoder.encode(PASSWORD)));
    }
}
