package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.mail.Mailer;

// NOTE ON MOCK LIFETIME: NotificationRepository/Mailer below are Mockito mocks wired as Spring @Bean
// singletons. resetMocks() below clears both before every test so a verify()/verifyNoInteractions() in one
// test can never be made to pass or fail by what a DIFFERENT test happened to do first.
@SpringJUnitConfig(NotificationServiceTest.TestConfig.class)
class NotificationServiceTest {

    @jakarta.annotation.Resource
    private NotificationService service;

    @jakarta.annotation.Resource
    private NotificationRepository notifications;

    @jakarta.annotation.Resource
    private Mailer mailer;

    @jakarta.annotation.Resource
    private NotificationEmailService emailService;

    @BeforeEach
    void resetMocks() {
        reset(notifications, mailer);
    }

    @Test
    void markReadRejectsMissingOrUnownedNotification() {
        UserPrincipal actor = new UserPrincipal(
            7L, "employee@glr.co.th", "Employee", "employee", 7L,
            true, java.time.LocalDate.of(2026, 1, 1), false, null, false);
        when(notifications.markRead(42L, 7L)).thenReturn(0);

        assertThatThrownBy(() -> service.markRead(42L, actor))
            .isInstanceOf(ApiException.class)
            .hasMessage("ไม่พบการแจ้งเตือนนี้");
    }

    @Test
    void markReadSucceedsWhenRepositoryUpdatesOwnedNotification() {
        UserPrincipal actor = new UserPrincipal(
            7L, "employee@glr.co.th", "Employee", "employee", 7L,
            true, java.time.LocalDate.of(2026, 1, 1), false, null, false);
        when(notifications.markRead(42L, 7L)).thenReturn(1);

        service.markRead(42L, actor);

        verify(notifications).markRead(42L, 7L);
    }

    @Configuration
    @EnableAsync
    static class TestConfig {
        @Bean
        NotificationService notificationService(NotificationRepository notifications,
                                                NotificationEmailService emailService) {
            return new NotificationService(notifications, emailService);
        }

        @Bean
        NotificationEmailService notificationEmailService(Mailer mailer, BrandAssets brandAssets) {
            return new NotificationEmailService(mailer, brandAssets, "", "", "https://portal.example");
        }

        @Bean
        BrandAssets brandAssets() {
            return new BrandAssets();
        }

        @Bean
        NotificationRepository notificationRepository() {
            return mock(NotificationRepository.class);
        }

        @Bean
        Mailer mailer() {
            return mock(Mailer.class);
        }

        @Bean(name = "taskExecutor")
        Executor taskExecutor() {
            return new SyncTaskExecutor();
        }
    }
}

// A SEPARATE top-level test class (package-private, like the one above - Java allows more than one per file as
// long as at most one is public), not a JUnit @Nested inner class.
@SpringJUnitConfig(NotificationServiceOverrideConfiguredTest.TestConfig.class)
class NotificationServiceOverrideConfiguredTest {

    @jakarta.annotation.Resource
    private NotificationService service;

    @jakarta.annotation.Resource
    private NotificationRepository notifications;

    @jakarta.annotation.Resource
    private Mailer mailer;

    @BeforeEach
    void resetMocks() {
        reset(notifications, mailer);
    }

    @Configuration
    @EnableAsync
    static class TestConfig {
        @Bean
        NotificationService notificationService(NotificationRepository notifications,
                                                NotificationEmailService emailService) {
            return new NotificationService(notifications, emailService);
        }

        @Bean
        NotificationEmailService notificationEmailService(Mailer mailer, BrandAssets brandAssets) {
            return new NotificationEmailService(mailer, brandAssets, "tester@example.com", "",
                "https://portal.example");
        }

        @Bean
        BrandAssets brandAssets() {
            return new BrandAssets();
        }

        @Bean
        NotificationRepository notificationRepository() {
            return mock(NotificationRepository.class);
        }

        @Bean
        Mailer mailer() {
            return mock(Mailer.class);
        }

        @Bean(name = "taskExecutor")
        Executor taskExecutor() {
            return new SyncTaskExecutor();
        }
    }
}
