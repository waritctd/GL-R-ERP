package th.co.glr.hr.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.ApplicationRoles;
import th.co.glr.hr.user.AppUserRepository;

/**
 * Creates a single real login on startup so a fresh production database is usable without the
 * demo seed's fake accounts. Controlled entirely by env vars:
 *
 * <pre>
 *   APP_BOOTSTRAP_ENABLED=true
 *   APP_BOOTSTRAP_EMAIL=hr@yourco.com
 *   APP_BOOTSTRAP_PASSWORD=&lt;strong password&gt;
 *   APP_BOOTSTRAP_ROLE=hr        # default
 * </pre>
 *
 * <p>If a user with that email already exists, its password and role are synced from the env vars.
 * New users are linked to the first available employee so the HR dashboard (which loads the
 * actor's own employee record) has data to render. Disable it again after the account is correct.
 */
@Component
public class BootstrapUserSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapUserSeeder.class);

    private final AppProperties properties;
    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public BootstrapUserSeeder(AppProperties properties, JdbcTemplate jdbc, AppUserRepository users) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.users = users;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap cfg = properties.getBootstrap();
        if (!cfg.isEnabled()) {
            return;
        }

        String email = cfg.getEmail() == null ? null : cfg.getEmail().trim();
        String password = cfg.getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("Bootstrap user enabled but APP_BOOTSTRAP_EMAIL/PASSWORD is missing; skipping.");
            return;
        }

        String role;
        try {
            role = ApplicationRoles.requireAllowed(cfg.getRole() == null || cfg.getRole().isBlank() ? "hr" : cfg.getRole());
        } catch (IllegalArgumentException exception) {
            log.warn("Bootstrap user enabled with unsupported APP_BOOTSTRAP_ROLE; skipping.");
            return;
        }

        var existingUser = users.findByEmail(email);
        if (existingUser.isPresent()) {
            long userId = existingUser.get().id();
            users.updatePasswordAndEnable(userId, passwordEncoder.encode(password));
            users.assignSingleRole(userId, role);
            log.info("Synced bootstrap user {} with role '{}'.", email, role);
            return;
        }

        Long employeeId = jdbc.query(
            "SELECT employee_id FROM hr.employee ORDER BY employee_id LIMIT 1",
            rs -> rs.next() ? rs.getLong(1) : null);
        if (employeeId == null) {
            log.warn("No employee rows found; creating bootstrap user {} without an employee link. "
                + "Dashboards that load the actor's own employee record may not work until one is linked.", email);
        }

        long userId = users.insertDemoUser(employeeId, email, passwordEncoder.encode(password));
        users.assignSingleRole(userId, role);
        log.info("Created bootstrap user {} with role '{}' (employeeId={}).", email, role, employeeId);
    }
}
