package th.co.glr.hr.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps Flyway eager even when {@code spring.main.lazy-initialization=true} (prod profile).
 *
 * <p>Global lazy initialization defers every bean until first use. That speeds up startup so the
 * Tomcat port opens in time for Render's port scan, but it would also defer the Flyway migration
 * initializer — meaning the schema might not be migrated until the first request, or not at all.
 * This filter excludes Flyway's beans from lazy init so migrations run during startup, before the
 * app accepts traffic, exactly as they do without lazy init.
 */
@Configuration
public class FlywayEagerInitConfig {

    @Bean
    LazyInitializationExcludeFilter flywayEagerInitExcludeFilter() {
        return LazyInitializationExcludeFilter.forBeanTypes(Flyway.class, FlywayMigrationInitializer.class);
    }
}
