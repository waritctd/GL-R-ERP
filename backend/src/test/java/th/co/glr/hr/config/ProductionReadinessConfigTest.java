package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Before this fix, {@code hasProfile(environment, "demo")} short-circuited {@link
 * ProductionReadinessConfig#validate} with a bare {@code return} -- silently, no log line -- which
 * is why Render (which runs {@code prod,demo}, per render.yaml) has had no disk and an unset {@code
 * APP_UPLOADS_DIR} for as long as the guard existed, without ever tripping it. These cases pin the
 * new behavior: real prod (no demo) still hard-fails unchanged; prod+demo now WARNS instead of
 * silently passing.
 *
 * <p>2026-08: {@code #validate} now also collects several DEGRADED-classified properties (payroll
 * employer fields, mail credentials, BOT tokens) -- see {@link #environment} for why the six
 * original tests below still pass unchanged, and the block below them for the new coverage of that
 * extension.
 */
class ProductionReadinessConfigTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void attachAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(ProductionReadinessConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    void realProdWithoutDemoStillHardFailsOnBlankUploadsDir() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_UPLOADS_DIR must be set");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void realProdWithoutDemoStillHardFailsOnRelativeUploadsDir() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("./uploads");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("absolute persistent path");
    }

    @Test
    void realProdWithoutDemoPassesSilentlyOnAbsoluteUploadsDir() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void prodPlusDemoNoLongerSilentlyPassesOnBlankUploadsDir_itWarnsInstead() {
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list)
            .as("the Render (prod+demo) case must be visible in logs, not silent")
            .hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
            .contains("APP_UPLOADS_DIR must be set");
    }

    @Test
    void prodPlusDemoWithAGoodUploadsDirLogsNothing() {
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void nonProdProfileIsUntouched() {
        Environment environment = environment("test");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    // ---- 2026-08: collect-every-problem + REQUIRED/DEGRADED severity coverage ----------------

    /**
     * Today exactly one property is classified REQUIRED ({@code app.uploads-dir}), so "multiple
     * REQUIRED problems get listed together in one throw" cannot be produced through {@link
     * ProductionReadinessConfig#validate}'s real property classification -- that's exactly why
     * {@link ProductionReadinessConfig#applyPolicy} is package-private: it lets this test drive the
     * aggregation policy directly with a synthetic list, independent of how many properties happen
     * to be REQUIRED today.
     */
    @Test
    void multipleRequiredProblemsAreAllListedInOneThrow() {
        assertThatThrownBy(() -> ProductionReadinessConfig.applyPolicy(
                List.of("Problem A must be set", "Problem B must be set"), List.of(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Problem A must be set")
            .hasMessageContaining("Problem B must be set");
    }

    @Test
    void degradedOnlyGapWarnsAndDoesNotThrowInRealProd() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");
        when(environment.getProperty("spring.mail.username", "")).thenReturn(""); // the one DEGRADED gap

        ProductionReadinessConfig.validate(environment); // must not throw

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("MAIL_USERNAME is not set");
    }

    @Test
    void degradedGapsDoNotSuppressARequiredThrow() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(""); // REQUIRED problem
        when(environment.getProperty("app.bot.fx-api-token", "")).thenReturn(""); // DEGRADED problem, too

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_UPLOADS_DIR must be set");
        // The REQUIRED throw must not swallow the DEGRADED gap's own WARN -- the two severities are
        // independent facts about the environment, so both still get their say.
        assertThat(appender.list)
            .as("a REQUIRED throw must not suppress a DEGRADED gap's own WARN")
            .hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("BOT_FX_API_TOKEN is not set");
    }

    /**
     * The six original tests above were all written back when {@code app.uploads-dir} was the only
     * property {@link ProductionReadinessConfig#validate} ever read. Now that it also reads eight
     * DEGRADED-classified properties, a plain {@code mock(Environment.class)} would return null for
     * every one of them here (Mockito's default answer for an unstubbed String-returning call,
     * regardless of the "default" argument the real {@code Environment} would honour) -- #validate
     * treats null the same as blank/unset (see its {@code property} helper), so without this,
     * EVERY real-prod/prod+demo test above would trip eight unrelated DEGRADED WARNs and fail their
     * {@code appender.list}-emptiness assertions. Pre-stub every such key to a non-blank dummy value
     * here, via {@code lenient()} since not every test cares about every key; each test's own later
     * {@code app.uploads-dir} stub (and the two new tests' own single-key overrides above) still
     * wins over these generic defaults -- Mockito resolves a mock invocation against the
     * most-recently-registered matching stub.
     */
    private static Environment environment(String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        lenient().when(environment.getProperty("app.payroll.employer.company-name-th", ""))
            .thenReturn("บริษัท ทดสอบ จำกัด");
        lenient().when(environment.getProperty("app.payroll.employer.company-tax-id", ""))
            .thenReturn("0105542026329");
        lenient().when(environment.getProperty("app.payroll.employer.kbank-debit-account", ""))
            .thenReturn("6001010598");
        lenient().when(environment.getProperty("app.payroll.employer.sso-employer-account", ""))
            .thenReturn("0000000000");
        lenient().when(environment.getProperty("spring.mail.username", "")).thenReturn("noreply@glr.co.th");
        lenient().when(environment.getProperty("spring.mail.password", "")).thenReturn("app-password");
        lenient().when(environment.getProperty("app.bot.fx-api-token", "")).thenReturn("fx-token");
        lenient().when(environment.getProperty("app.bot.holiday-api-token", "")).thenReturn("holiday-token");
        return environment;
    }
}
