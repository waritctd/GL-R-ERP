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
 * employer fields, BOT tokens) -- see {@link #environment} for why the six original tests below
 * still pass unchanged, and the block below them for the new coverage of that extension.
 *
 * <p>2026-08 (Resend mail port): {@code app.mail.from} / {@code app.mail.app-base-url} /
 * {@code app.mail.resend-api-key} joined as REQUIRED, conditionally on {@code app.mail.provider} --
 * see the "app.mail.* REQUIRED-when-provider" block below. The MAIL_USERNAME/MAIL_PASSWORD DEGRADED
 * entries this class used to check are retired outright (not merely renamed): nothing reads
 * {@code spring.mail.username}/{@code spring.mail.password} once NotificationEmailService/
 * FactoryEmailService moved onto {@code th.co.glr.hr.mail.Mailer}.
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
     * Drives {@link ProductionReadinessConfig#applyPolicy} directly with a synthetic problem list,
     * independent of how {@link ProductionReadinessConfig#validate} classifies any real property --
     * that's exactly why {@code applyPolicy} is package-private. The real classification CAN produce
     * multiple REQUIRED problems in one throw now (see {@code resendProviderRequiresFromAppBaseUrlAndApiKey}
     * below, which does exactly that through {@code #validate} itself), but this synthetic-list test
     * still earns its place: it pins the aggregation policy in isolation from property classification.
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
        // A DEGRADED gap: environment() below stubs a non-blank default for every property this
        // method reads, so overriding just this one back to blank isolates it the same way the
        // pre-2026-08-Resend-port version of this test isolated spring.mail.username.
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
        // The REQUIRED throw must not swallow the DEGRADED gap's own WARN -- the two severities are
        // independent facts about the environment, so both still get their say.
        assertThat(appender.list)
            .as("a REQUIRED throw must not suppress a DEGRADED gap's own WARN")
            .hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("BOT_FX_API_TOKEN is not set");
    }

    // ---- app.mail.* REQUIRED-when-provider coverage (2026-08 Resend mail port) ----------------
    //
    // Owner decision this locks in: neither a production From address nor a production portal URL
    // is invented anywhere in this repo (see application.yml's app.mail.from/app-base-url comments
    // -- both default to blank, not a literal fallback). A deployment that sets
    // app.mail.provider=resend/smtp without also setting these must refuse to boot in real prod
    // rather than silently send from Resend's sandbox sender (which 403s on any real recipient) or
    // link every notification/payslip email at the wrong host.

    @Test
    void resendProviderRequiresFromAppBaseUrlAndApiKey() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");
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
        // provider=smtp authenticates via app.mail.smtp.* (SmtpMailer's own constructor already
        // hard-fails on a blank app.mail.smtp.host) -- app.mail.resend-api-key is meaningless here
        // and must not be demanded.
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");
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
        // provider=log (the repo-wide default) never contacts a real inbox, so a blank
        // From/base-url/API key must not block boot -- dev/CI has no mail credentials at all.
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");
        when(environment.getProperty("app.mail.provider", "")).thenReturn("log");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void unsetProviderDefaultsToLogAndDoesNotRequireAnyMailProperty() {
        // The property() helper treats a Mockito mock's unstubbed null the same as "absent" (see
        // its own Javadoc) -- this is the same path a real, unconfigured deployment takes, where
        // application.yml's ${APP_MAIL_PROVIDER:log} default applies.
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void resendProviderWithEveryMailPropertySetPassesSilently() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("hr@glr.co.th");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("re_live_abc123");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void demoProfileWarnsRatherThanThrowsOnAMissingMailFrom() {
        // Matches the existing app.uploads-dir precedent (see the six original tests above): this
        // Render service runs prod,demo (render.yaml), so a REQUIRED gap there WARNs at boot
        // instead of crash-looping the showcase; real on-prem prod (no demo profile) still
        // hard-fails on the identical gap, per resendProviderRequiresFromAppBaseUrlAndApiKey above.
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");
        when(environment.getProperty("app.mail.provider", "")).thenReturn("resend");
        when(environment.getProperty("app.mail.from", "")).thenReturn("");
        when(environment.getProperty("app.mail.app-base-url", "")).thenReturn("https://erp.glr.co.th");
        when(environment.getProperty("app.mail.resend-api-key", "")).thenReturn("re_live_abc123");

        ProductionReadinessConfig.validate(environment); // must not throw

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("APP_MAIL_FROM is not set");
    }

    /**
     * The six original tests above were all written back when {@code app.uploads-dir} was the only
     * property {@link ProductionReadinessConfig#validate} ever read. Now that it also reads six
     * DEGRADED-classified properties, a plain {@code mock(Environment.class)} would return null for
     * every one of them here (Mockito's default answer for an unstubbed String-returning call,
     * regardless of the "default" argument the real {@code Environment} would honour) -- #validate
     * treats null the same as blank/unset (see its {@code property} helper), so without this,
     * EVERY real-prod/prod+demo test above would trip unrelated DEGRADED WARNs and fail their
     * {@code appender.list}-emptiness assertions. Pre-stub every such key to a non-blank dummy value
     * here, via {@code lenient()} since not every test cares about every key; each test's own later
     * stubs (including the app.mail.* ones above) still win over these generic defaults --
     * Mockito resolves a mock invocation against the most-recently-registered matching stub.
     *
     * <p>Deliberately does NOT stub {@code app.mail.provider} (or any other {@code app.mail.*}
     * key): the unstubbed-null-becomes-blank behaviour described above already resolves it to
     * {@code ""}, which is neither {@code resend} nor {@code smtp} -- exactly application.yml's own
     * {@code ${APP_MAIL_PROVIDER:log}} default-to-safe behaviour -- so every test above that never
     * mentions {@code app.mail.*} is implicitly proving the "unconfigured deployment" case rather
     * than skipping it by accident.
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
        lenient().when(environment.getProperty("app.bot.fx-api-token", "")).thenReturn("fx-token");
        lenient().when(environment.getProperty("app.bot.holiday-api-token", "")).thenReturn("holiday-token");
        return environment;
    }
}
