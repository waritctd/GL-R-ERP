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
                                            MustChangePasswordFilter mustChangePasswordFilter,
                                            th.co.glr.hr.auth.ViewAsReadOnlyFilter viewAsReadOnlyFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()          // CORS preflight (MVC CORS still enforces origins)
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()      // no session yet; CSRF-exempt already Self-service forgot-password (ลืมรหัสผ่าน): both legs run before the
                // caller has any session - a client landing fresh on /forgot-password, or arriving via an emailed
                // /reset-password?token=... link, has made no prior /api/** call and holds no session cookie yet.
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/attendance/punch").permitAll()// device X-GLR-Agent-Token; no session
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll() // Allow anonymous health probes and brand assets only; never open the entire public API namespace.
                .requestMatchers(HttpMethod.GET, "/api/public/brand/**").permitAll()   // Email logos need anonymous access; OpenAPI documentation still requires authentication.
                .anyRequest().authenticated())
            .addFilterBefore(sessionSecurityFilter, AnonymousAuthenticationFilter.class)
            // Must sit AFTER sessionSecurityFilter so the principal is already resolved, and inside this chain (not the
            // plain servlet chain) so StrictHttpFirewall has already normalised the path its allowlist matches on. See
            // MustChangePasswordFilter.
            .addFilterAfter(mustChangePasswordFilter, SessionSecurityFilter.class)
            .addFilterAfter(viewAsReadOnlyFilter, MustChangePasswordFilter.class);
        return http.build();
    }
}
