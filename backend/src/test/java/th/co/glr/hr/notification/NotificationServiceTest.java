package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.mail.Mailer;

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

    @Test
    void notifyWritesInAppRowAndAttemptsEmailSynchronouslyInTest() {
        NotificationDto saved = new NotificationDto(
            42L,
            7L,
            null,
            null,
            "LEAVE_SUBMITTED",
            "Leave submitted",
            "Your leave request was submitted.",
            "/leave/1",
            false,
            Instant.parse("2026-07-08T00:00:00Z"));
        when(notifications.insert(7L, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1")).thenReturn(42L);
        when(notifications.findById(42L)).thenReturn(Optional.of(saved));
        when(notifications.findEmployeeEmail(7L)).thenReturn(Optional.of("employee@glr.co.th"));

        NotificationDto result = service.notify(
            7L,
            "LEAVE_SUBMITTED",
            "Leave submitted",
            "Your leave request was submitted.",
            "/leave/1",
            true);

        assertThat(result).isEqualTo(saved);
        verify(notifications).insert(7L, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1");
        verify(mailer).send(
            eq("employee@glr.co.th"),
            eq("[GL&R HR] Leave submitted"),
            argThat(body -> body.contains("เรียน ท่านผู้ใช้งาน,")
                && body.contains("Your leave request was submitted.")
                && body.contains("ระบบบริหารงานบุคคล GL&R")));
    }

    @Test
    void sendWithAttachmentDelegatesToConfiguredMailer() {
        emailService.sendWithAttachment(
            "employee@glr.co.th",
            "Payslip",
            "Attached",
            "payslip.pdf",
            "%PDF".getBytes());

        verify(mailer).sendWithAttachment(
            eq("employee@glr.co.th"),
            eq("Payslip"),
            eq("Attached"),
            eq("payslip.pdf"),
            aryEq("%PDF".getBytes()));
    }

    @Test
    void markReadRejectsMissingOrUnownedNotification() {
        UserPrincipal actor = new UserPrincipal(
            7L, "employee@glr.co.th", "Employee", "employee", 7L,
            true, java.time.LocalDate.of(2026, 1, 1), false, null, false);
        when(notifications.markRead(42L, 7L)).thenReturn(0);

        assertThatThrownBy(() -> service.markRead(42L, actor))
            .isInstanceOf(ApiException.class)
            .hasMessage("Notification not found");
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
        NotificationEmailService notificationEmailService(Mailer mailer) {
            return new NotificationEmailService(mailer, "", "", "https://portal.example");
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
