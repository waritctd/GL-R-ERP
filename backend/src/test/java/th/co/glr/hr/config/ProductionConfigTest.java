package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigTest {
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
        assertThat(yaml).contains("uploads-dir: ${APP_UPLOADS_DIR}");
        assertThat(yaml).contains("springdoc:");
        assertThat(yaml).doesNotContain("classpath:db/migration-demo");
    }

    @Test
    void prodProfileRequiresAbsolutePersistentUploadsDirectory() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.profiles.active", "prod")
            .withProperty("app.uploads-dir", "./uploads");
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_UPLOADS_DIR");
    }

    @Test
    void demoProfileCanUseLocalUploadsDirectory() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.profiles.active", "prod,demo")
            .withProperty("app.uploads-dir", "./uploads");
        environment.setActiveProfiles("prod", "demo");

        ProductionReadinessConfig.validate(environment);
    }
}
