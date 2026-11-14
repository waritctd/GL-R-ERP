package th.co.glr.hr.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps Flyway eager even when spring.main.lazy-initialization=true (prod profile). Global lazy initialization
 * defers every bean until first use. This filter excludes Flyway's beans from lazy init so migrations run
 * during startup, before the app accepts traffic, exactly as they do without lazy init. */
@Configuration
public class FlywayEagerInitConfig {

    @Bean
    LazyInitializationExcludeFilter flywayEagerInitExcludeFilter() {
        return LazyInitializationExcludeFilter.forBeanTypes(Flyway.class, FlywayMigrationInitializer.class);
    }
}
