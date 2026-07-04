package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DashboardServiceSpringWiringTest {
    @Test
    void springCanInstantiateDashboardServiceWithProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DashboardRepository.class, () -> mock(DashboardRepository.class));
            context.register(DashboardService.class);
            context.refresh();

            assertThat(context.getBean(DashboardService.class)).isNotNull();
        }
    }
}
