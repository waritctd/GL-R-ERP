package th.co.glr.hr.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Labels the springdoc-generated OpenAPI contract (served at /v3/api-docs, browsable via Swagger UI at
 * /swagger-ui.html) with basic API metadata. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI hrPortalOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("GL-R HR Portal API")
                .version(version())
                .description("REST API for the GL-R HR portal."));
    }

    /** The build's version (jar manifest Implementation-Version, written by the Spring Boot plugin); "dev" off a jar. */
    private static String version() {
        String version = OpenApiConfig.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
