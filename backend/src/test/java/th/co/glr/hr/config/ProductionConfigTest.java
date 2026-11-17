package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

class ProductionConfigTest {
    private static final String LOCALHOST_FALLBACK_URL = "jdbc:postgresql://localhost:5432/hris";

    /** ConfigDataApplicationContextInitializer. No auto-configuration is registered on this runner (no
     * withConfiguration(...), no component scan), so refreshing it starts no DataSource, no Flyway, and no web
     * server -- it only ever loads and resolves property sources. */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> ConfigDataEnvironmentPostProcessor.applyTo(context.getEnvironment()))
        .withPropertyValues("spring.profiles.active=prod");

    @Test
    void prodDatasourceRequiresExplicitEnvironmentVariables() throws Exception {
        String yaml = new String(
            getClass().getClassLoader()
                .getResourceAsStream("application-prod.yml")
                .readAllBytes(),
            StandardCharsets.UTF_8);

        assertThat(yaml).contains("url: ${SPRING_DATASOURCE_URL}");
        assertThat(yaml).contains("username: ${SPRING_DATASOURCE_USERNAME}");
        assertThat(yaml).contains("password: ${SPRING_DATASOURCE_PASSWORD}");
        assertThat(yaml).doesNotContain("SPRING_DATASOURCE_URL:jdbc:postgresql://localhost");
        assertThat(yaml).doesNotContain("SPRING_DATASOURCE_USERNAME:postgres");
        assertThat(yaml).doesNotContain("SPRING_DATASOURCE_PASSWORD:postgres");
    }

    /** The test above only ever proves a comment could quote the right substring back -- this repo has already been
     * bitten by exactly that class of source-text guard. */
    @Test
    void prodProfileResolvesDatasourceUrlFromTheSuppliedEnvironmentVariable() {
        contextRunner
            .withPropertyValues("SPRING_DATASOURCE_URL=jdbc:postgresql://real-db-host:5432/realdb")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://real-db-host:5432/realdb");
            });
    }

    /** The dangerous direction: application.yml's base (no-profile) document DOES carry a localhost fallback for
     * local dev (${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/hris}). */
    @Test
    void prodProfileContributesNoLocalhostFallbackWhenTheEnvironmentVariableIsAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            String rawValue = firstRawPropertyValue(context.getEnvironment(), "spring.datasource.url");

            assertThat(rawValue)
                .as("application-prod.yml's own spring.datasource.url definition must carry no "
                    + "embedded default -- if this ever reads the base application.yml value "
                    + "instead, the prod profile has stopped shadowing it")
                .isEqualTo("${SPRING_DATASOURCE_URL}");
            assertThat(rawValue).isNotEqualTo(LOCALHOST_FALLBACK_URL);
        });
    }

    /** The raw (pre-placeholder-resolution) value for key from the highest-priority property source that defines it
     * at all -- mirrors the search Environment#getProperty itself does, minus the nested-placeholder resolution
     * step that throws when unresolvable. */
    private static String firstRawPropertyValue(ConfigurableEnvironment environment, String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
