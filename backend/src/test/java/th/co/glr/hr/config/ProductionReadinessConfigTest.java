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
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/** Before this fix, hasProfile short-circuited ProductionReadinessConfig#validate with a bare return --
 * silently, no log line -- which is why Render has had no disk and an unset APP_UPLOADS_DIR for as long as the
 * guard existed, without ever tripping it. */
class ProductionReadinessConfigTest {

    /** A REAL, writable directory for every "good uploads dir" case below. Swapping in a temp dir keeps each test's
     * original intent and makes "good" mean what the production check now means -- writable. */
    @TempDir
    Path writableUploadsDir;

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

    // ---- 2026-08: collect-every-problem + REQUIRED/DEGRADED severity coverage ----------------

    @Test
    void degradedOnlyGapWarnsAndDoesNotThrowInRealProd() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        // A DEGRADED gap: environment() below stubs a non-blank default for every property this method reads, so
        // overriding just this one back to blank isolates it the same way the pre-2026-08-Resend-port version of this
        // test isolated spring.mail.username.
        when(environment.getProperty("app.payroll.employer.company-name-th", "")).thenReturn("");

        ProductionReadinessConfig.validate(environment); // must not throw

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("APP_PAYROLL_COMPANY_NAME_TH is not set");
    }

    // app.mail.* REQUIRED-when-provider coverage ---------------- Owner decision this locks in: neither a
    // production From address nor a production portal URL is invented anywhere in this repo.

    @Test
    void resendProviderRequiresFromAppBaseUrlAndApiKey() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_MAIL_FROM is not set")
            .hasMessageContaining("APP_MAIL_APP_BASE_URL is not set")
            .hasMessageContaining("APP_MAIL_RESEND_API_KEY is not set");
        assertThat(appender.list).isEmpty();
    }

    // app.mail.override-to REQUIRED-when-set-under-prod coverage -------------------- Deliberate NEW behaviour,
    // not present before #782: app.mail.override-to exists to redirect every outbound email to one test inbox.

    @Test
    void resendProviderUnderRealProdHardFailsWhenOverrideToIsSet() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("hr@glr.co.th");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("re_live_abc123");
        when(environment.getProperty("app.mail.override-to", "")).thenReturn("qa-inbox@example.com");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_MAIL_OVERRIDE_TO must not be set")
            // The exception message is a fixed, reviewable string - it must not echo the configured inbox address into
            // logs/exceptions at boot time (see mailOverrideActiveProblem).
            .hasMessageNotContaining("qa-inbox@example.com");
        assertThat(appender.list).isEmpty();
    }

    /** The four tests above were all written back when app.uploads-dir was the only property
     * ProductionReadinessConfig#validate ever read. */
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
        lenient().when(environment.getProperty("app.bot.fx-api-token", "")).thenReturn("fx-token");
        lenient().when(environment.getProperty("app.bot.holiday-api-token", "")).thenReturn("holiday-token");
        return environment;
    }

}
