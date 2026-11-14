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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());

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
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());

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

    /** Drives ProductionReadinessConfig#applyPolicy directly with a synthetic problem list, independent of how
     * ProductionReadinessConfig#validate classifies any real property -- that's exactly why applyPolicy is
     * package-private. */
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

    @Test
    void degradedGapsDoNotSuppressARequiredThrow() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(""); // REQUIRED problem
        when(environment.getProperty("app.bot.fx-api-token", "")).thenReturn(""); // DEGRADED problem, too

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_UPLOADS_DIR must be set");
        // The REQUIRED throw must not swallow the DEGRADED gap's own WARN -- the two severities are independent facts
        // about the environment, so both still get their say.
        assertThat(appender.list)
            .as("a REQUIRED throw must not suppress a DEGRADED gap's own WARN")
            .hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("BOT_FX_API_TOKEN is not set");
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

    @Test
    void smtpProviderRequiresFromAndAppBaseUrlButNotAResendApiKey() {
        // provider=smtp authenticates via app.mail.smtp.* (SmtpMailer's own constructor already hard-fails on a blank
        // app.mail.smtp.host) -- app.mail.resend-api-key is meaningless here and must not be demanded.
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("smtp");
        when(environment.getProperty("app.mail.from", "")).thenReturn("");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_MAIL_FROM is not set")
            .hasMessageContaining("APP_MAIL_APP_BASE_URL is not set")
            .hasMessageNotContaining("APP_MAIL_RESEND_API_KEY");
    }

    @Test
    void logProviderDoesNotRequireAnyMailProperty() {
        // provider=log (the repo-wide default) never contacts a real inbox, so a blank From/base-url/API key must not
        // block boot -- dev/CI has no mail credentials at all.
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("log");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void unsetProviderDefaultsToLogAndDoesNotRequireAnyMailProperty() {
        // The property() helper treats a Mockito mock's unstubbed null the same as "absent" (see its own Javadoc) --
        // this is the same path a real, unconfigured deployment takes, where application.yml's
        // ${APP_MAIL_PROVIDER:log} default applies.
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void resendProviderWithEveryMailPropertySetPassesSilently() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("hr@glr.co.th");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("re_live_abc123");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void demoProfileWarnsRatherThanThrowsOnAMissingMailFrom() {
        // Matches the existing app.uploads-dir precedent: this Render service runs prod,demo, so a REQUIRED gap there
        // WARNs at boot instead of crash-looping the showcase; real on-prem prod still hard-fails on the identical
        // gap, per resendProviderRequiresFromAppBaseUrlAndApiKey above.
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("re_live_abc123");

        ProductionReadinessConfig.validate(environment); // must not throw

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("APP_MAIL_FROM is not set");
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

    @Test
    void smtpProviderUnderRealProdHardFailsWhenOverrideToIsSet() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("smtp");
        when(environment.getProperty("app.mail.from", "")).thenReturn("hr@glr.co.th");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.override-to", "")).thenReturn("qa-inbox@example.com");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_MAIL_OVERRIDE_TO must not be set");
    }

    @Test
    void prodPlusDemoWarnsRatherThanThrowsWhenOverrideToIsSet() {
        // Same policy as demoProfileWarnsRatherThanThrowsOnAMissingMailFrom above: the Render showcase must not
        // crash-loop, but the gap must still be visible in the logs.
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("hr@glr.co.th");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("re_live_abc123");
        when(environment.getProperty("app.mail.override-to", "")).thenReturn("qa-inbox@example.com");

        ProductionReadinessConfig.validate(environment); // must not throw

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("APP_MAIL_OVERRIDE_TO must not be set");
    }

    @Test
    void logProviderIgnoresOverrideToEvenUnderRealProd() {
        // provider=log never contacts a real inbox (see LogMailer) - a stray override-to left set is inert there, not
        // dangerous, and must not block boot. Mirrors logProviderDoesNotRequireAnyMailProperty above for the same
        // reason.
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("log");
        when(environment.getProperty("app.mail.override-to", "")).thenReturn("qa-inbox@example.com");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void resendProviderUnderRealProdPassesSilentlyWhenOverrideToIsBlank() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("hr@glr.co.th");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("re_live_abc123");
        when(environment.getProperty("app.mail.override-to", "")).thenReturn("");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    /** The six original tests above were all written back when app.uploads-dir was the only property
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

    // ---- 2026-08-31: set-and-absolute is not the same as USABLE -------------------------------

    /** A directory whose PARENT is a regular file. This one cannot. */
    private Path uncreatableDir() throws IOException {
        Path blocker = writableUploadsDir.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        return blocker.resolve("uploads");
    }

    @Test
    void realProdHardFailsWhenTheUploadsDirIsAbsoluteButCannotBeWritten() throws IOException {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(uncreatableDir().toString());

        // The production failure this exists to catch: /var/data/uploads on Render is absolute and non-blank, so it
        // cleared both existing checks and then failed every upload at runtime.
        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_UPLOADS_DIR")
            .hasMessageContaining("is not writable");
    }

    @Test
    void prodPlusDemoWarnsWhenTheUploadsDirIsAbsoluteButCannotBeWritten() throws IOException {
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(uncreatableDir().toString());

        ProductionReadinessConfig.validate(environment);

        // Render runs prod,demo -- so this is the branch that would actually have fired, and it must reach the boot
        // log rather than crash-looping the live service.
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("is not writable");
    }

    /** The check must not require the directory to exist ALREADY -- FileStorageService#store creates it on first
     * use, so demanding it up front would report a problem where there is none (a fresh volume, a first boot). It
     * creates the path itself and reports nothing. */
    @Test
    void anAbsentButCreatableUploadsDirIsNotAProblemAndIsCreated() {
        Path notYetThere = writableUploadsDir.resolve("nested/uploads");
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(notYetThere.toString());

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
        assertThat(notYetThere).exists();
    }

    /** The probe must not leave its own scratch file behind in a real uploads directory. */
    @Test
    void theWritabilityProbeCleansUpAfterItself() throws IOException {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn(writableUploadsDir.toString());

        ProductionReadinessConfig.validate(environment);

        try (var entries = Files.list(writableUploadsDir)) {
            assertThat(entries).isEmpty();
        }
    }
}
