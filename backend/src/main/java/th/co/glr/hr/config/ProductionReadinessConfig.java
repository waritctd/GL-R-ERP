package th.co.glr.hr.config;

import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ProductionReadinessConfig {

    @Bean
    ApplicationRunner validateProductionReadiness(Environment environment) {
        return args -> validate(environment);
    }

    static void validate(Environment environment) {
        if (!hasProfile(environment, "prod") || hasProfile(environment, "demo")) {
            return;
        }
        String uploadsDir = environment.getProperty("app.uploads-dir", "");
        if (uploadsDir.isBlank()) {
            throw new IllegalStateException("APP_UPLOADS_DIR must be set for the prod profile");
        }
        Path uploadsPath = Path.of(uploadsDir);
        if (!uploadsPath.isAbsolute()) {
            throw new IllegalStateException("APP_UPLOADS_DIR must be an absolute persistent path for the prod profile");
        }
    }

    private static boolean hasProfile(Environment environment, String profile) {
        return Arrays.asList(environment.getActiveProfiles()).contains(profile);
    }
}
