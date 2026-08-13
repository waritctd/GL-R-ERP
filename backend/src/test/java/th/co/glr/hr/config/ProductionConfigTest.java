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

    /**
     * {@code ConfigDataApplicationContextInitializer} -- the usual one-liner for this kind of test
     * -- does not exist on this Spring Boot line; {@link ConfigDataEnvironmentPostProcessor#applyTo
     * (ConfigurableEnvironment)} is the exact single-argument entry point that class used to
     * delegate to, so an initializer lambda calling it directly reproduces the same behaviour. No
     * auto-configuration is registered on this runner (no {@code withConfiguration(...)}, no
     * component scan), so refreshing it starts no {@code DataSource}, no Flyway, and no web server
     * -- it only ever loads and resolves property sources.
     */
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

    /**
     * The test above only ever proves a comment could quote the right substring back -- this repo
     * has already been bitten by exactly that class of source-text guard. This one actually
     * resolves the property the way the running application would: through Spring's real
     * config-data machinery, under {@code spring.profiles.active=prod}, with the env var genuinely
     * supplied as a property.
     */
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

    /**
     * The dangerous direction: {@code application.yml}'s base (no-profile) document DOES carry a
     * localhost fallback for local dev ({@code
     * ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/hris}}). {@code
     * application-prod.yml}'s whole job is to shadow that, under the prod profile, with a
     * fallback-free definition ({@code ${SPRING_DATASOURCE_URL}}) so a real deploy that forgets to
     * set the env var fails loudly instead of silently talking to a Postgres that doesn't exist in
     * that environment. Calling {@code environment.getProperty("spring.datasource.url")} directly
     * would throw while trying to resolve the now-absent placeholder -- asserting that exception's
     * type would pin Spring's placeholder-resolution internals rather than the actual outcome this
     * test cares about, so this instead reads the RAW value straight off the winning property
     * source (i.e. before placeholder substitution is attempted), the same place {@code
     * Environment#getProperty} would look before it tries to resolve what it finds there.
     */
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

    /** The raw (pre-placeholder-resolution) value for {@code key} from the highest-priority
     * property source that defines it at all -- mirrors the search {@code Environment#getProperty}
     * itself does, minus the nested-placeholder resolution step that throws when unresolvable. */
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
