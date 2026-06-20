package th.co.glr.hr.config;

import java.net.URI;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {
    @Bean
    WebMvcConfigurer corsConfigurer(AppProperties properties) {
        List<String> allowedOrigins = sanitizeOrigins(properties.getCors().getAllowedOrigins());
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOrigins(allowedOrigins.toArray(String[]::new))
                    .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("Content-Type", "X-XSRF-TOKEN")
                    .allowCredentials(true)
                    .maxAge(3600);
            }
        };
    }

    private List<String> sanitizeOrigins(List<String> origins) {
        List<String> sanitized = origins == null ? List.of() : origins.stream()
            .map(origin -> origin == null ? "" : origin.trim())
            .filter(origin -> !origin.isBlank())
            .distinct()
            .toList();
        if (sanitized.isEmpty()) {
            throw new IllegalStateException("At least one CORS origin must be configured");
        }
        for (String origin : sanitized) {
            if ("*".equals(origin)) {
                throw new IllegalStateException("Wildcard CORS origins are not allowed when credentials are enabled");
            }
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
                throw new IllegalStateException("CORS origins must be absolute http(s) URLs");
            }
        }
        return sanitized;
    }
}
