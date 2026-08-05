package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Before this fix, {@code hasProfile(environment, "demo")} short-circuited {@link
 * ProductionReadinessConfig#validate} with a bare {@code return} -- silently, no log line -- which
 * is why Render (which runs {@code prod,demo}, per render.yaml) has had no disk and an unset {@code
 * APP_UPLOADS_DIR} for as long as the guard existed, without ever tripping it. These cases pin the
 * new behavior: real prod (no demo) still hard-fails unchanged; prod+demo now WARNS instead of
 * silently passing.
 */
class ProductionReadinessConfigTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void attachAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(ProductionReadinessConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    void realProdWithoutDemoStillHardFailsOnBlankUploadsDir() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APP_UPLOADS_DIR must be set");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void realProdWithoutDemoStillHardFailsOnRelativeUploadsDir() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("./uploads");

        assertThatThrownBy(() -> ProductionReadinessConfig.validate(environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("absolute persistent path");
    }

    @Test
    void realProdWithoutDemoPassesSilentlyOnAbsoluteUploadsDir() {
        Environment environment = environment("prod");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void prodPlusDemoNoLongerSilentlyPassesOnBlankUploadsDir_itWarnsInstead() {
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list)
            .as("the Render (prod+demo) case must be visible in logs, not silent")
            .hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
            .contains("APP_UPLOADS_DIR must be set");
    }

    @Test
    void prodPlusDemoWithAGoodUploadsDirLogsNothing() {
        Environment environment = environment("prod", "demo");
        when(environment.getProperty("app.uploads-dir", "")).thenReturn("/var/lib/glr-hr/uploads");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void nonProdProfileIsUntouched() {
        Environment environment = environment("test");

        ProductionReadinessConfig.validate(environment);

        assertThat(appender.list).isEmpty();
    }

    private static Environment environment(String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return environment;
    }
}
