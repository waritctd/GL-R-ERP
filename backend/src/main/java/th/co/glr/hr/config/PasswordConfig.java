package th.co.glr.hr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt strength 10 (Spring default). Postgres-side hashes are never produced,
        // so verification is always against BCryptPasswordEncoder-generated hashes.
        return new BCryptPasswordEncoder();
    }
}
