package th.co.glr.hr.config;

import java.nio.file.Path;
import java.util.Arrays;
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
     */
    static void validate(Environment environment) {
        if (!hasProfile(environment, "prod")) {
            return;
        }
        String problem = uploadsDirProblem(environment.getProperty("app.uploads-dir", ""));
        if (problem == null) {
            return;
        }
        if (hasProfile(environment, "demo")) {
            log.warn("Production readiness gap (demo profile, not hard-failing boot): {}. Any "
                + "disk-backed attachment upload will be lost on the next deploy.", problem);
            return;
        }
        throw new IllegalStateException(problem);
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

    private static boolean hasProfile(Environment environment, String profile) {
        return Arrays.asList(environment.getActiveProfiles()).contains(profile);
    }
}
