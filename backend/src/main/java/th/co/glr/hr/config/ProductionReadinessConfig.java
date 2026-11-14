package th.co.glr.hr.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ProductionReadinessConfig {
    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessConfig.class);

    @Bean
    ApplicationRunner validateProductionReadiness(Environment environment) {
        return args -> validate(environment);
    }

    /** render.yaml runs the Render service as prod,demo (see its comment on SPRING_FLYWAY_LOCATIONS for why demo
     * stays on there). These always WARN, in both real prod and prod,demo, and never throw. */
    static void validate(Environment environment) {
        if (!hasProfile(environment, "prod")) {
            return;
        }
        List<String> required = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        collectProblems(environment, required, degraded);
        applyPolicy(required, degraded, hasProfile(environment, "demo"));
    }

    private static void collectProblems(Environment environment, List<String> required, List<String> degraded) {
        addIfPresent(required, uploadsDirProblem(property(environment, "app.uploads-dir")));

        // Payroll employer registration constants (AppProperties.Employer): blank by default so nothing bogus reaches
        // a real bank/tax/SSO filing by accident (see that class's javadoc).
        addIfPresent(degraded, blankProblem(property(environment, "app.payroll.employer.company-name-th"),
            "APP_PAYROLL_COMPANY_NAME_TH",
            "KBank and SSO statutory exports will be refused (see PayrollService#export)"));
        addIfPresent(degraded, blankProblem(property(environment, "app.payroll.employer.company-tax-id"),
            "APP_PAYROLL_COMPANY_TAX_ID",
            "PND1 statutory exports will be refused (see PayrollService#export)"));
        addIfPresent(degraded, blankProblem(property(environment, "app.payroll.employer.kbank-debit-account"),
            "APP_PAYROLL_KBANK_DEBIT_ACCOUNT",
            "KBank statutory exports will be refused (see PayrollService#export)"));
        addIfPresent(degraded, blankProblem(property(environment, "app.payroll.employer.sso-employer-account"),
            "APP_PAYROLL_SSO_EMPLOYER_ACCOUNT",
            "SSO statutory exports will be refused (see PayrollService#export)"));

        // app.mail.*: REQUIRED (not DEGRADED) when the active provider actually reaches real inboxes. A missing
        // app-base-url would put a dead/wrong-tenant link in every notification and payslip email
        // (PayslipDistributionService included) rather than merely failing to send.
        String mailProvider = property(environment, "app.mail.provider");
        boolean mailProviderSendsRealMail = "resend".equals(mailProvider) || "smtp".equals(mailProvider);
        if (mailProviderSendsRealMail) {
            addIfPresent(required, blankProblem(property(environment, "app.mail.from"),
                "APP_MAIL_FROM",
                "app.mail.provider=" + mailProvider + " would otherwise fall back to the Resend "
                    + "sandbox sender, which cannot deliver to a real recipient (see ResendMailer)"));
            addIfPresent(required, blankProblem(property(environment, "app.mail.app-base-url"),
                "APP_MAIL_APP_BASE_URL",
                "every link in every notification/payslip email would point at the UAT demo host"));
            // #782: app.mail.override-to exists so a test/UAT deployment can redirect every outbound email to one inbox
            // (see OverrideRedirectingMailer).
            addIfPresent(required, mailOverrideActiveProblem(property(environment, "app.mail.override-to")));
        }
        // Resend-only: SmtpMailer authenticates via app.mail.smtp.* instead, and already hard-fails its own
        // constructor when app.mail.smtp.host is blank (see that class) -- an independent guard this method does not
        // need to duplicate.
        if ("resend".equals(mailProvider)) {
            addIfPresent(required, blankProblem(property(environment, "app.mail.resend-api-key"),
                "APP_MAIL_RESEND_API_KEY",
                "app.mail.provider=resend requires a Resend API key (see ResendMailer)"));
        }

        // BOT issues a separate key per API -- no fallback between them (AppProperties.Bot).
        addIfPresent(degraded, blankProblem(property(environment, "app.bot.fx-api-token"),
            "BOT_FX_API_TOKEN",
            "automatic FX rate fetching will be skipped (see BotFxFetchService#fetchDailyRates)"));
        addIfPresent(degraded, blankProblem(property(environment, "app.bot.holiday-api-token"),
            "BOT_HOLIDAY_API_TOKEN",
            "automatic holiday fetching will be skipped (see BotHolidayFetchService#scheduledFetch)"));
    }

    /** Applies the hard-fail-vs-warn policy to an already-collected problem list. */
    static void applyPolicy(List<String> required, List<String> degraded, boolean isDemo) {
        if (!degraded.isEmpty()) {
            log.warn("Production readiness gap(s) (not hard-failing boot; feature(s) run "
                + "unconfigured): {}", String.join(" | ", degraded));
        }
        if (required.isEmpty()) {
            return;
        }
        String message = String.join(" | ", required);
        if (isDemo) {
            log.warn("Production readiness gap(s) (demo profile, not hard-failing boot): {}. Any "
                + "disk-backed attachment upload will be lost on the next deploy.", message);
            return;
        }
        throw new IllegalStateException(message);
    }

    private static void addIfPresent(List<String> problems, String problem) {
        if (problem != null) {
            problems.add(problem);
        }
    }

    /** Environment#getProperty never returns null against a real Spring Environment, but a test-mocked Environment
     * returns null for any unstubbed key regardless of the default argument. */
    private static String property(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        return value == null ? "" : value;
    }

    private static String uploadsDirProblem(String uploadsDir) {
        if (uploadsDir.isBlank()) {
            return "APP_UPLOADS_DIR must be set for the prod profile";
        }
        if (!Path.of(uploadsDir).isAbsolute()) {
            return "APP_UPLOADS_DIR must be an absolute persistent path for the prod profile";
        }
        return uploadsDirWritabilityProblem(Path.of(uploadsDir));
    }

    /** Set-and-absolute is not the same as USABLE, and the gap between them is not academic. A guard that can only
     * see the SPELLING of a path is not a guard. REQUIRED severity, matching the two checks above: hard-fails a
     * real on-prem prod boot and WARNs under prod,demo (Render). */
    private static String uploadsDirWritabilityProblem(Path uploadsDir) {
        Path probe = null;
        try {
            Files.createDirectories(uploadsDir);
            probe = Files.createTempFile(uploadsDir, "readiness-", ".probe");
            return null;
        } catch (IOException | SecurityException exception) {
            // Catch filesystem failures narrowly so programming errors are not reported as an unwritable uploads
            // directory.
            return "APP_UPLOADS_DIR (" + uploadsDir + ") is not writable by the runtime user — "
                + "every file upload will fail: " + exception;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException exception) {
                    // A probe file we could not remove is one stray zero-byte file in the uploads dir. Reporting it as a readiness
                    // problem would be a lie -- the write itself succeeded, which is the whole question this method exists to
                    // answer.
                    log.warn("Could not remove the uploads-dir readiness probe file: {}", probe, exception);
                }
            }
        }
    }

    private static String blankProblem(String value, String envVar, String consequence) {
        if (value.isBlank()) {
            return envVar + " is not set — " + consequence;
        }
        return null;
    }

    /** The inverse shape of #blankProblem: this property is a problem when it IS set, not when it's blank. */
    private static String mailOverrideActiveProblem(String overrideTo) {
        if (!overrideTo.isBlank()) {
            return "APP_MAIL_OVERRIDE_TO must not be set for the prod profile — it would silently "
                + "redirect every outbound email (real employee notifications and real factory-quote "
                + "mail alike) to one inbox instead of its real recipient (see OverrideRedirectingMailer)";
        }
        return null;
    }

    private static boolean hasProfile(Environment environment, String profile) {
        return Arrays.asList(environment.getActiveProfiles()).contains(profile);
    }
}
