package th.co.glr.hr.auth;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * One-time-per-row transition: BCrypts each active employee's {@code employee_code} as a
 * TEMPORARY login password for rows that have no password hash yet. {@code must_change_password}
 * stays TRUE (its column default), so the user is forced to set a real password on next login
 * and the predictable temporary value cannot persist.
 *
 * <p>Runs on every startup but is a no-op once all rows have a hash (the query filters on
 * {@code password_hash IS NULL}), and the update itself is guarded by {@code IS NULL} so it can
 * never clobber a password a user has already chosen.
 *
 * <p>Disabled by default (the low-entropy employee_code as an initial password is guessable before
 * first login). Opt in for local dev only via {@code app.auth.seed-employee-code-passwords=true};
 * prod and demo never seed. Secure onboarding uses the HR reset-password endpoint instead.
 */
@Component
@ConditionalOnProperty(name = "app.auth.seed-employee-code-passwords", havingValue = "true")
public class PasswordBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PasswordBackfillRunner.class);

    private final EmployeeAuthRepository employees;
    private final PasswordEncoder passwordEncoder;

    public PasswordBackfillRunner(EmployeeAuthRepository employees, PasswordEncoder passwordEncoder) {
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<Long, String> codes = employees.findActiveCodesNeedingPassword();
        if (codes.isEmpty()) {
            return;
        }
        codes.forEach((employeeId, employeeCode) ->
            employees.backfillTemporaryPassword(employeeId, passwordEncoder.encode(employeeCode)));
        log.info("Seeded temporary password hashes for {} employee(s); each must change password on next login.",
            codes.size());
    }
}
