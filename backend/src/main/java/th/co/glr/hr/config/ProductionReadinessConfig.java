package th.co.glr.hr.config;

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

    /**
     * render.yaml runs the Render service as {@code prod,demo} (see its comment on {@code
     * SPRING_FLYWAY_LOCATIONS} for why demo stays on there). Before this method's fix, {@code
     * hasProfile(environment, "demo")} short-circuited the WHOLE check with a bare {@code return}
     * -- silently, no log line at all -- which is exactly why Render has been running with no
     * disk and an unset {@code APP_UPLOADS_DIR} without anyone noticing: the one guard written to
     * catch this never ran on the one environment it needed to catch it on.
     *
     * <p>Real on-prem prod (no demo profile) still hard-fails on boot, unchanged -- fail fast
     * matters more there since it is the actual production system. The Render demo/showcase case
     * now WARNS instead of throwing, rather than silently passing: throwing would currently crash
     * Render's live service on every deploy (APP_UPLOADS_DIR is unset there today), which is a
     * bigger blast radius than this fix is meant to carry on its own -- but the gap must be
     * visible in the logs, not invisible, so the next person looking at durability doesn't have to
     * rediscover it from scratch.
     *
     * <p><b>2026-08 extension:</b> the checklist item "environment variables validated on
     * startup" was only ever true for {@code app.uploads-dir} -- everything else silently no-oped
     * or dropped data with no startup signal at all. This method now collects every known prod
     * config gap in one pass (instead of returning on the very first problem) and sorts each into
     * one of two severities, applied via {@link #applyPolicy}:
     * <ul>
     *   <li><b>REQUIRED</b> ({@code app.uploads-dir} always; {@code app.mail.from} /
     *       {@code app.mail.app-base-url} / {@code app.mail.resend-api-key} conditionally, when
     *       {@code app.mail.provider} is set to a transport that actually reaches real inboxes --
     *       see {@link #collectProblems}) -- no safe way to run without it, so it keeps the
     *       existing hard-fail-in-real-prod / WARN-in-{@code prod,demo} policy above, just extended
     *       to list every REQUIRED problem at once instead of only the first.
     *       <b>2026-08 (#782):</b> {@code app.mail.override-to} joined this list too, but inverted --
     *       it is a problem when SET (under the same real-mail-provider condition), not when blank;
     *       see {@link #mailOverrideActiveProblem}.</li>
     *   <li><b>DEGRADED</b> (payroll employer registration fields, BOT tokens) -- features that
     *       already have a documented no-op/silent-drop fallback elsewhere in the codebase (each
     *       message below names the concrete consequence and the class that owns it) and
     *       legitimately run unconfigured in some environments -- e.g. Render has never set the BOT
     *       tokens. These always WARN, in both real prod and {@code prod,demo}, and never throw. A
     *       DEGRADED gap must never suppress a REQUIRED throw, and a REQUIRED throw must never
     *       suppress a DEGRADED gap's own WARN -- the two severities are independent facts about
     *       the environment, so both always get their say.</li>
     * </ul>
     */
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

        // Payroll employer registration constants (AppProperties.Employer): blank by default so
        // nothing bogus reaches a real bank/tax/SSO filing by accident (see that class's javadoc).
        // Fix B (PayrollService#export) now also fail-closes at the point of use for KBANK/PND1/SSO
        // -- this WARN is the startup-time early signal that the later 503 will happen, not the
        // only guard against it.
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

        // app.mail.*: REQUIRED (not DEGRADED) when the active provider actually reaches real
        // inboxes. spring.mail.username/password (MAIL_USERNAME/MAIL_PASSWORD) used to be checked
        // here as a DEGRADED gap; retired outright, not merely moved, because nothing reads those
        // two properties any more -- NotificationEmailService/FactoryEmailService both moved onto
        // the th.co.glr.hr.mail.Mailer abstraction, and SmtpMailer (app.mail.provider=smtp) builds
        // its own JavaMailSenderImpl from app.mail.smtp.*, never spring.mail.*. A DEGRADED entry
        // that can never stop firing (because the property it names is permanently unset) is worse
        // than no entry: it trains whoever reads the boot log to skip past a WARN that can never
        // mean anything changed.
        //
        // app.mail.from / app.mail.app-base-url are REQUIRED for both resend and smtp:
        // application.yml's built-in fallbacks are Resend's SANDBOX sender (onboarding@resend.dev,
        // which 403s silently on any recipient outside the Resend account) and the UAT Vercel URL --
        // neither is a safe production default, and this repo does not know the real production
        // From address or portal URL, so the safe move is to refuse rather than guess. A missing
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
            // #782: app.mail.override-to exists so a test/UAT deployment can redirect every outbound
            // email to one inbox (see OverrideRedirectingMailer). A set override left active under
            // THIS profile - the one deployment mode that actually reaches real inboxes - would
            // silently redirect every real employee's notification and every real factory's quote
            // mail to one address instead, with nothing in the logs distinguishing that from normal
            // operation. Gated on mailProviderSendsRealMail like the two checks above: under
            // provider=log nothing is actually transmitted, so a stray override-to left set is inert,
            // not dangerous, and must not block boot.
            addIfPresent(required, mailOverrideActiveProblem(property(environment, "app.mail.override-to")));
        }
        // Resend-only: SmtpMailer authenticates via app.mail.smtp.* instead, and already hard-fails
        // its own constructor when app.mail.smtp.host is blank (see that class) -- an independent
        // guard this method does not need to duplicate.
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

    /**
     * Applies the hard-fail-vs-warn policy to an already-collected problem list. Package-private
     * (rather than folded into {@link #validate}) so {@code ProductionReadinessConfigTest} can
     * drive the aggregation/severity policy directly with a synthetic problem list, independent of
     * how many properties happen to be REQUIRED under any one real profile/provider combination --
     * the collect-then-decide split (and its "list every problem, never stop at the first" and
     * "the two severities never suppress each other" behaviour) is exactly the thing this 2026-08
     * change is meant to guarantee.
     */
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

    /**
     * {@code Environment#getProperty(key, "")} never returns null against a real Spring {@code
     * Environment} (an absent property resolves to the supplied default), but a test-mocked
     * {@code Environment} returns null for any unstubbed key regardless of the default argument --
     * treat that the same as "absent" rather than NPE, so an unrelated test's mock doesn't have to
     * stub every property this method reads.
     */
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
        return null;
    }

    private static String blankProblem(String value, String envVar, String consequence) {
        if (value.isBlank()) {
            return envVar + " is not set — " + consequence;
        }
        return null;
    }

    /** The inverse shape of {@link #blankProblem}: this property is a problem when it IS set, not
     * when it's blank. Deliberately does not echo the configured value into the exception/log
     * message - the message is a fixed, reviewable string either way, and there is no reason for a
     * boot-time error to carry a live inbox address through logs. */
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
