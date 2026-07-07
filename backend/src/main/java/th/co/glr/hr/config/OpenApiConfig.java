package th.co.glr.hr.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Labels the springdoc-generated OpenAPI contract (served at {@code /v3/api-docs}, browsable via
 * Swagger UI at {@code /swagger-ui.html}) with basic API metadata.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI hrPortalOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("GL-R HR Portal API")
                .version("0.1.0")
                .description("REST API for the GL-R HR portal (v0.1.0 stabilization)."));
    }
}
