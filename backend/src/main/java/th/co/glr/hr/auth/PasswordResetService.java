package th.co.glr.hr.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationEmailService;

/**
 * Self-service "forgot password" (ลืมรหัสผ่าน) — lets an employee reset their own password via an
 * emailed single-use link, without HR intervention. Split out of {@link AuthService} rather than
 * added to it: this is a distinct concern (token issuance/consumption, email dispatch, an
 * anti-spam cooldown) with its own repository ({@link PasswordResetTokenRepository}), and
 * {@code AuthService} is already sizeable — the same granularity choice this codebase already
 * makes between e.g. {@code EmployeeService} and {@code AuthService} rather than folding
 * everything auth-adjacent into one class.
 *
 * <p><b>Never reveals whether an address matched an employee.</b> {@link #forgotPassword} always
 * returns the identical {@link MessageResponse}, whether the address exists, belongs to an
 * inactive employee, has no email on file, or is mid-cooldown — the branch is entirely internal
 * and none of it reaches the response. Contrast {@link #resetPassword}, which IS safe to be
 * specific in: a "this token is invalid or expired" message reveals nothing about whether any
 * account exists, only whether the caller's own token is currently valid.
 *
 * <p><b>Reachable only for employees with an email on file</b> ({@code hr.employee.email} is
 * nullable — V158's own comment records roughly half of prod's employees have none). That is an
 * accepted, intentional gap: the existing HR admin reset ({@code POST
 * /api/employees/{id}/reset-password}, {@link EmployeeAuthRepository#setTemporaryPassword})
 * remains the fallback for everyone else.
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** How long an issued token remains redeemable. */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    /**
     * Minimal anti-spam guard: at most one token issued (and one email sent) per employee per
     * this window. This is NOT rate limiting in the general sense — it is per-employee only, has
     * no IP dimension, and does not throttle a caller who spreads requests across many different
     * (real or guessed) addresses. Global/IP-based rate limiting is out of scope for this change
     * and a known follow-up.
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    private static final String GENERIC_SENT_MESSAGE =
        "หากอีเมลนี้มีอยู่ในระบบ เราได้ส่งลิงก์สำหรับตั้งรหัสผ่านใหม่ไปให้แล้ว กรุณาตรวจสอบกล่องอีเมลของคุณ";
    private static final String INVALID_OR_EXPIRED_TOKEN =
        "ลิงก์สำหรับตั้งรหัสผ่านใหม่ไม่ถูกต้องหรือหมดอายุแล้ว กรุณาขอลิงก์ใหม่อีกครั้ง";
    private static final String RESET_SUCCESS_MESSAGE = "ตั้งรหัสผ่านใหม่เรียบร้อยแล้ว กรุณาเข้าสู่ระบบด้วยรหัสผ่านใหม่ของคุณ";

    private final EmployeeAuthRepository employees;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final NotificationEmailService notificationEmailService;
    private final AuditService auditService;
    private final Clock clock;

    // @Autowired is required here, not optional: with two constructors and no annotation, Spring
    // cannot infer which one to autowire and falls back to a no-arg constructor that does not
    // exist, failing every bean's instantiation at context startup. Same requirement
    // DashboardService's identical two-constructor pattern documents.
    @Autowired
    public PasswordResetService(EmployeeAuthRepository employees, PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder, NotificationEmailService notificationEmailService,
                                AuditService auditService) {
        // Mirrors the DashboardService(repository, Clock) / BotHolidayFetchService pattern already
        // used in this codebase: the Spring-visible constructor pins a real system Clock rather
        // than requiring a Clock bean nothing else needs, and tests reach the package-private
        // all-args constructor below to inject a fixed one.
        this(employees, tokens, passwordEncoder, notificationEmailService, auditService, Clock.systemUTC());
    }

    /** Test-only seam: lets a test pin {@code clock} so cooldown/expiry math is deterministic. */
    PasswordResetService(EmployeeAuthRepository employees, PasswordResetTokenRepository tokens,
                         PasswordEncoder passwordEncoder, NotificationEmailService notificationEmailService,
                         AuditService auditService, Clock clock) {
        this.employees = employees;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.notificationEmailService = notificationEmailService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        employees.findByEmail(request.email()).ifPresent(this::issueTokenIfEligible);
        // Same response whether or not anything above actually happened - see class Javadoc.
        return new MessageResponse(GENERIC_SENT_MESSAGE);
    }

    private void issueTokenIfEligible(EmployeeLoginRecord employee) {
        if (!employee.active() || !hasText(employee.email())) {
            // An inactive employee, or (shouldn't happen for a row found BY email, but checked
            // anyway) one with no address to send to. Falls straight through to the same generic
            // response the caller already gets - see forgotPassword.
            return;
        }

        Instant now = clock.instant();
        boolean withinCooldown = tokens.mostRecentOpenTokenCreatedAt(employee.employeeId())
            .map(createdAt -> createdAt.plus(COOLDOWN).isAfter(now))
            .orElse(false);
        if (withinCooldown) {
            log.info("forgot-password cooldown active for employee {}; not issuing another token",
                employee.employeeId());
            return;
        }

        // At most one live token per employee: an earlier emailed link stops working the moment a
        // newer one is requested, rather than leaving several simultaneously valid.
        tokens.invalidateOutstanding(employee.employeeId());

        String rawToken = PasswordResetTokenCodec.generateRawToken();
        tokens.create(employee.employeeId(), PasswordResetTokenCodec.hash(rawToken), now.plus(TOKEN_TTL));

        notificationEmailService.send(
            employee.employeeId(),
            employee.email(),
            employee.name(),
            "ตั้งรหัสผ่านใหม่",
            "คุณได้ขอรีเซ็ตรหัสผ่านสำหรับบัญชี GL&R HR Portal ของคุณ ลิงก์ด้านล่างนี้จะหมดอายุใน 30 นาที "
                + "หากคุณไม่ได้เป็นผู้ขอ กรุณาเพิกเฉยต่ออีเมลฉบับนี้ - รหัสผ่านของคุณจะไม่มีการเปลี่ยนแปลงใดๆ",
            "/reset-password?token=" + rawToken);
    }

    public MessageResponse resetPassword(ResetPasswordRequest request) {
        String tokenHash = PasswordResetTokenCodec.hash(request.token());
        PasswordResetTokenRepository.OpenToken openToken = tokens.findValidByTokenHash(tokenHash)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, INVALID_OR_EXPIRED_TOKEN));

        // Re-look-up rather than trust the token row alone: an employee deactivated after the
        // token was issued must not be able to set a new password with it.
        EmployeeLoginRecord employee = employees.findByEmployeeId(openToken.employeeId())
            .filter(EmployeeLoginRecord::active)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, INVALID_OR_EXPIRED_TOKEN));

        // Same rule AuthService#changePassword applies, shared rather than duplicated - see that
        // method's sibling call. The "must not equal the CURRENT password" rule does not carry
        // over: a self-service reset via a verified emailed link has no current password to
        // compare against, since the caller never supplies one.
        AuthService.rejectEmployeeCodeAsNewPassword(request.newPassword(), employee);

        employees.updatePassword(employee.employeeId(), passwordEncoder.encode(request.newPassword()));
        // Marked used only after the write succeeds, so a failure between the two leaves the token
        // still redeemable rather than burning it on a reset that never actually happened.
        tokens.markUsed(openToken.id());

        recordPasswordResetAudit(employee);

        return new MessageResponse(RESET_SUCCESS_MESSAGE);
    }

    /**
     * Records the completed reset in {@code hr.audit_log}, same shape and same swallow-on-failure
     * discipline as {@code AuthService#recordAuthEvent} (see that method's Javadoc): by the time
     * this runs the new password hash is already committed, so letting an audit-table problem
     * propagate would tell the employee their reset failed when it did not.
     */
    private void recordPasswordResetAudit(EmployeeLoginRecord employee) {
        try {
            UserPrincipal principal = new UserPrincipal(
                employee.employeeId(),
                employee.email(),
                employee.name(),
                DivisionAccessPolicy.roleFor(employee),
                employee.employeeId(),
                employee.active(),
                employee.createdAt(),
                false,
                employee.divisionId(),
                DivisionAccessPolicy.isManager(employee));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("role", principal.role());
            auditService.record(principal, "PASSWORD_RESET", "employee", principal.id(), null, details);
        } catch (RuntimeException e) {
            log.warn("Could not record the PASSWORD_RESET audit row for employee {}", employee.employeeId(), e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
