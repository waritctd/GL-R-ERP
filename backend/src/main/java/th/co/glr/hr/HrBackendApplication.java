package th.co.glr.hr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableScheduling lives in SchedulingConfig (@Profile("!test")) so scheduled workers do not run
// in @SpringBootTest contexts and race integration tests sharing the same database.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class HrBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrBackendApplication.class, args);
    }
}
