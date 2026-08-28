package th.co.glr.hr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import th.co.glr.hr.auth.MustChangePasswordFilter;
import th.co.glr.hr.auth.SessionSecurityFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SessionSecurityFilter sessionSecurityFilter,
                                            MustChangePasswordFilter mustChangePasswordFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()          // CORS preflight (MVC CORS still enforces origins)
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()      // no session yet; CSRF-exempt already
                .requestMatchers(HttpMethod.POST, "/api/attendance/punch").permitAll()// device X-GLR-Agent-Token; no session
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll() // LB/probe health only; no other actuator endpoint
                // Scoped to /brand/** deliberately, NOT the whole /api/public/** namespace: a
                // wildcard there would silently make every future controller placed under
                // /api/public anonymous, which is how an accidental data leak gets introduced by
                // someone who never read this line. Widen it only for another asset that genuinely
                // must be fetched cookie-less.
                .requestMatchers(HttpMethod.GET, "/api/public/brand/**").permitAll()   // logo embedded in notification email; fetched by mail clients / Gmail's image proxy with no session
                // OpenAPI docs (/v3/api-docs, /swagger-ui) are intentionally NOT allowlisted: they
                // fall under default-deny below, so reading the contract / enumerating endpoints
                // requires an authenticated session rather than being anonymously accessible.
                .anyRequest().authenticated())
            .addFilterBefore(sessionSecurityFilter, AnonymousAuthenticationFilter.class)
            // Must sit AFTER sessionSecurityFilter so the principal is already resolved, and
            // inside this chain (not the plain servlet chain) so StrictHttpFirewall has already
            // normalised the path its allowlist matches on. See MustChangePasswordFilter.
            .addFilterAfter(mustChangePasswordFilter, SessionSecurityFilter.class);
        return http.build();
    }
}
