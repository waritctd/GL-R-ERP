package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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
    }
}
