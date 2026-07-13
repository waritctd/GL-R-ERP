package th.co.glr.hr.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring Boot 4 defaults to Jackson 3 and stopped auto-configuring a classic
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean. Several services here
 * (audit trail JSON, ticket item snapshots, attendance raw payloads, the login
 * rate-limit filter) inject that Jackson 2 type directly, so it's provided explicitly.
 */
@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
