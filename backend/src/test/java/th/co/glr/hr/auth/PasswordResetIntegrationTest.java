package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage for the self-service forgot-password flow
 * ({@code POST /api/auth/forgot-password} / {@code POST /api/auth/reset-password}), through the
 * real {@link PasswordResetService}, {@link PasswordResetTokenRepository} and
 * {@link EmployeeAuthRepository} — same shape as {@link LoginEmailNormalizationIntegrationTest}:
 * hand-wired real collaborators against a real database, no mocks.
 *
 * <p>The test cannot intercept a real email, so it captures the rendered mail via a
 * {@link CapturingMailer} (same double {@code DepositNoticeAccountNotificationIntegrationTest}
 * uses) and pulls the raw token back out of the emailed reset link — exactly what a real employee
 * would click through, minus the mail transport.
 */
class PasswordResetIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String ORIGINAL_PASSWORD = "OldStr0ngPass!";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)");

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private EmployeeAuthRepository employees;
    private PasswordResetTokenRepository tokenRepository;
    private CapturingMailer mailer;
    private PasswordResetService service;
    private AuthService authService;

    @BeforeEach
    void wireRealCollaborators() {
        employees = new EmployeeAuthRepository(jdbc);
        tokenRepository = new PasswordResetTokenRepository(jdbc);
        mailer = new CapturingMailer();
        NotificationEmailService emailService = new NotificationEmailService(
            mailer, new BrandAssets(), "", "", "https://portal.test.glr");
        AuditService auditService = new AuditService(new AuditLogRepository(jdbc), new ObjectMapper().findAndRegisterModules());
        service = new PasswordResetService(employees, tokenRepository, encoder, emailService, auditService);
        authService = new AuthService(employees, encoder, auditService);
    }

    @Test
    void happyPathIssuesATokenEmailsItAndTheRawTokenResetsThePassword() {
        long id = insertEmployeeWithEmail("RESET-001", "reset.happy@glr.co.th", "EMP001", ORIGINAL_PASSWORD);

        MessageResponse forgotResponse = service.forgotPassword(new ForgotPasswordRequest("reset.happy@glr.co.th"));
        assertThat(forgotResponse.message()).isNotBlank();

        // A token row exists for this employee.
        Integer tokenCount = jdbc.queryForObject(
            "SELECT count(*) FROM hr.password_reset_token WHERE employee_id = :id",
            Map.of("id", id), Integer.class);
        assertThat(tokenCount).isEqualTo(1);

        String rawToken = extractToken(mailer.lastSentTo("reset.happy@glr.co.th"));

        MessageResponse resetResponse = service.resetPassword(new ResetPasswordRequest(rawToken, "BrandNewPass1!"));
        assertThat(resetResponse.message()).isNotBlank();

        // password_hash changed and must_change_password stayed/became false (updatePassword's
        // contract - see EmployeeAuthRepository#updatePassword).
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT password_hash, must_change_password FROM hr.employee WHERE employee_id = :id",
            Map.of("id", id));
        assertThat(encoder.matches("BrandNewPass1!", (String) row.get("password_hash"))).isTrue();
        assertThat(row.get("must_change_password")).isEqualTo(false);

        // The new password logs in for real, through the real AuthService.
        AuthResponse loginResponse = authService.login(
            new LoginRequest("reset.happy@glr.co.th", "BrandNewPass1!", null), new MockHttpServletRequest());
        assertThat(loginResponse.user().id()).isEqualTo(id);
    }

    @Test
    void nonExistentEmailReturnsTheIdenticalResponseAsAnExistingOne() {
        insertEmployeeWithEmail("RESET-002", "reset.exists@glr.co.th", "EMP002", ORIGINAL_PASSWORD);

        MessageResponse existingResponse = service.forgotPassword(new ForgotPasswordRequest("reset.exists@glr.co.th"));
        MessageResponse unknownResponse = service.forgotPassword(new ForgotPasswordRequest("reset.nobody-here@glr.co.th"));

        // Wrong-way-round on purpose: the failure mode that matters is the response shape leaking
        // whether an address matched, not merely that both calls "succeed".
        assertThat(unknownResponse).isEqualTo(existingResponse);

        // And no token/email was produced for the address that does not exist.
        Integer tokenCount = jdbc.queryForObject(
            "SELECT count(*) FROM hr.password_reset_token t JOIN hr.employee e ON e.employee_id = t.employee_id "
                + "WHERE e.email = :email",
            Map.of("email", "reset.nobody-here@glr.co.th"), Integer.class);
        assertThat(tokenCount).isEqualTo(0);
    }

    @Test
    void expiredTokenIsRejected() {
        insertEmployeeWithEmail("RESET-003", "reset.expired@glr.co.th", "EMP003", ORIGINAL_PASSWORD);

        service.forgotPassword(new ForgotPasswordRequest("reset.expired@glr.co.th"));
        String rawToken = extractToken(mailer.lastSentTo("reset.expired@glr.co.th"));

        // Force the issued token into the past rather than waiting 30 real minutes or threading a
        // fixed Clock through - the row is the one thing that actually gates resetPassword.
        jdbc.update("UPDATE hr.password_reset_token SET expires_at = now() - interval '1 minute' "
            + "WHERE token_hash = :hash", Map.of("hash", PasswordResetTokenCodec.hash(rawToken)));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(rawToken, "BrandNewPass1!")))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
    }

    @Test
    void aTokenAlreadyUsedCannotBeUsedAgain() {
        insertEmployeeWithEmail("RESET-004", "reset.reused@glr.co.th", "EMP004", ORIGINAL_PASSWORD);

        service.forgotPassword(new ForgotPasswordRequest("reset.reused@glr.co.th"));
        String rawToken = extractToken(mailer.lastSentTo("reset.reused@glr.co.th"));

        service.resetPassword(new ResetPasswordRequest(rawToken, "FirstNewPass1!"));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(rawToken, "SecondNewPass1!")))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
    }

    /**
     * The race {@link #aTokenAlreadyUsedCannotBeUsedAgain} does NOT cover: that test reuses a
     * token strictly SEQUENTIALLY, which only proves {@code used_at} sticks after a commit — it
     * says nothing about two requests that both read the token as still-open BEFORE either writes.
     * Without {@link PasswordResetTokenRepository#markUsedIfUnused}'s conditional {@code UPDATE
     * ... WHERE used_at IS NULL}, that window (hundreds of milliseconds wide once a real BCrypt
     * hash sits between the read and the write) lets both callers pass and both overwrite {@code
     * password_hash} — a lost update with no error to either side.
     *
     * <p>{@code service} is hand-wired with {@code new} (see {@link #wireRealCollaborators}), so
     * {@code PasswordResetService#resetPassword}'s own {@code @Transactional} is INERT without a
     * Spring AOP proxy — same caveat {@code PayrollDraftOptimisticConcurrencyIntegrationTest}
     * documents. Each racing call is instead wrapped in its own explicit {@link TransactionTemplate}
     * bound to the same {@code DataSource} {@code jdbc} uses, so two GENUINELY concurrent Postgres
     * transactions race the same row — proving the atomicity comes from {@code
     * markUsedIfUnused}'s single conditional statement (true with or without the annotation), not
     * from an application-level lock this test would otherwise only be pretending to exercise.
     *
     * <p>{@code @RepeatedTest}: the race is only forced when both threads happen to read the
     * still-open row before either commits its consume — a genuine timing race, not a deterministic
     * ordering (same rationale {@code PayrollDraftOptimisticConcurrencyIntegrationTest} states for
     * its own {@code @RepeatedTest(20)}). Fewer repeats here since the window this specific race
     * needs is wide (a real BCrypt hash, not a comparatively fast Postgres round trip).
     */
    @RepeatedTest(10)
    void exactlyOneOfTwoConcurrentResetsWithTheSameTokenWinsAndTheLoserIsRejected() throws Exception {
        long id = insertEmployeeWithEmail("RESET-007", "reset.race@glr.co.th", "EMP007", ORIGINAL_PASSWORD);

        service.forgotPassword(new ForgotPasswordRequest("reset.race@glr.co.th"));
        String rawToken = extractToken(mailer.lastSentTo("reset.race@glr.co.th"));

        var txManager = new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new TransactionTemplate(txManager);

        Callable<String> byFirstCaller = () -> txTemplate.execute(status -> {
            service.resetPassword(new ResetPasswordRequest(rawToken, "FirstRacePass1!"));
            return "FirstRacePass1!";
        });
        Callable<String> bySecondCaller = () -> txTemplate.execute(status -> {
            service.resetPassword(new ResetPasswordRequest(rawToken, "SecondRacePass1!"));
            return "SecondRacePass1!";
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<String> f1 = executor.submit(byFirstCaller);
            Future<String> f2 = executor.submit(bySecondCaller);
            for (Future<String> f : List.of(f1, f2)) {
                try {
                    successes.add(f.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).as("exactly one racing reset must win").hasSize(1);
        assertThat(failures).as("exactly one racing reset must be rejected, not silently overwritten").hasSize(1);
        assertThat(failures.get(0)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // The stored hash must match EXACTLY the winner's password - never the loser's, and never
        // some artifact of both writes interleaving.
        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM hr.employee WHERE employee_id = :id", Map.of("id", id), String.class);
        assertThat(encoder.matches(successes.get(0), hash))
            .as("stored password_hash must be the winner's, not the loser's")
            .isTrue();

        // Exactly one row consumed the token - not zero (both rejected), not two (both consumed).
        Integer usedCount = jdbc.queryForObject(
            "SELECT count(*) FROM hr.password_reset_token WHERE employee_id = :id AND used_at IS NOT NULL",
            Map.of("id", id), Integer.class);
        assertThat(usedCount).isEqualTo(1);
    }

    @Test
    void newPasswordEqualToTheEmployeesOwnCodeIsRejectedWithTheSharedMessage() {
        // The code itself becomes the attempted new password, so it must clear ResetPasswordRequest's
        // own @Size(min = 8) - "EMP-00005" does.
        String employeeCode = "EMP-00005";
        insertEmployeeWithEmail("RESET-005", "reset.owncode@glr.co.th", employeeCode, ORIGINAL_PASSWORD);

        service.forgotPassword(new ForgotPasswordRequest("reset.owncode@glr.co.th"));
        String rawToken = extractToken(mailer.lastSentTo("reset.owncode@glr.co.th"));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(rawToken, employeeCode)))
            .isInstanceOf(ApiException.class)
            .hasMessage("รหัสผ่านใหม่ต้องไม่ใช่รหัสพนักงานของคุณ");
    }

    @Test
    void rapidRepeatedForgotPasswordRequestsIssueOnlyOneValidToken() {
        long id = insertEmployeeWithEmail("RESET-006", "reset.cooldown@glr.co.th", "EMP006", ORIGINAL_PASSWORD);

        service.forgotPassword(new ForgotPasswordRequest("reset.cooldown@glr.co.th"));
        service.forgotPassword(new ForgotPasswordRequest("reset.cooldown@glr.co.th"));

        // The cooldown guard must have skipped issuing (and emailing) a second token: exactly one
        // row exists for this employee, not two.
        Integer tokenCount = jdbc.queryForObject(
            "SELECT count(*) FROM hr.password_reset_token WHERE employee_id = :id",
            Map.of("id", id), Integer.class);
        assertThat(tokenCount).isEqualTo(1);

        long emailsToThisAddress = mailer.sent().stream()
            .filter(sent -> "reset.cooldown@glr.co.th".equals(sent.to()))
            .count();
        assertThat(emailsToThisAddress).isEqualTo(1);
    }

    private String extractToken(String textBody) {
        Matcher matcher = TOKEN_PATTERN.matcher(textBody);
        assertThat(matcher.find()).as("reset link with a token in the emailed body").isTrue();
        return matcher.group(1);
    }

    private long insertEmployeeWithEmail(String code, String email, String employeeCode, String password) {
        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, 'ฝ่ายบุคคล', TRUE) RETURNING division_id
            """, Map.of("code", code), Long.class);

        Map<String, Object> params = new HashMap<>();
        params.put("code", employeeCode);
        params.put("email", email);
        params.put("hash", encoder.encode(password));
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

    /**
     * Same double {@code DepositNoticeAccountNotificationIntegrationTest} uses: captures the full
     * rendered mail so the raw reset token can be pulled back out of the body, since the test has
     * no real mail transport to intercept.
     */
    private static final class CapturingMailer implements Mailer {
        private final List<SentMail> sent = new ArrayList<>();

        List<SentMail> sent() {
            return sent;
        }

        /** The text body of the most recent mail sent to {@code to}. */
        String lastSentTo(String to) {
            return sent.stream()
                .filter(mail -> to.equals(mail.to()))
                .reduce((first, second) -> second)
                .map(SentMail::textBody)
                .orElseThrow(() -> new AssertionError("No mail captured for " + to));
        }

        @Override
        public void send(String to, String subject, String body) {
            sent.add(new SentMail(to, subject, body));
        }

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody,
                             List<InlineImage> inlineImages) {
            sent.add(new SentMail(to, subject, textBody));
        }

        @Override
        public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
            sent.add(new SentMail(to, subject, body));
        }

        @Override
        public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
            sent.add(new SentMail(to, subject, body));
        }

        record SentMail(String to, String subject, String textBody) {}
    }
}
