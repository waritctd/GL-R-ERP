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
import th.co.glr.hr.auth.SessionSecurityFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionSecurityFilter sessionSecurityFilter) throws Exception {
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
                .anyRequest().authenticated())
            .addFilterBefore(sessionSecurityFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
