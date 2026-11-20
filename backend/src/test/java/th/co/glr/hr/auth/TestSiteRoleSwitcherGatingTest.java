package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The switcher endpoints exist only behind an explicit flag, and never on the prod profile. */
class TestSiteRoleSwitcherGatingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(TestSiteRoleSwitcher.class, () -> mock(TestSiteRoleSwitcher.class))
        .withBean(AuthService.class, () -> mock(AuthService.class))
        .withBean(SessionContext.class, () -> mock(SessionContext.class))
        .withUserConfiguration(TestSiteRoleSwitcherController.class);

    @Test
    void flagOn_withProdProfile_refusesToStart() {
        runner.withPropertyValues("app.test-site.role-switcher.enabled=true", "spring.profiles.active=prod")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void flagOn_withDemoProfile_hasEndpoints() {
        runner.withPropertyValues("app.test-site.role-switcher.enabled=true", "spring.profiles.active=demo")
            .run(context -> assertThat(context).hasSingleBean(TestSiteRoleSwitcherController.class));
    }
}
