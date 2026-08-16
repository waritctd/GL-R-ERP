package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
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
// singletons. Spring caches the ApplicationContext across every @Test method that shares this exact
// @SpringJUnitConfig, so both mocks are the SAME object for every test in this class, and Mockito's
// invocation history on them accumulates across tests unless reset. resetMocks() below clears both
// before every test so a verify()/verifyNoInteractions() in one test can never be made to pass or
// fail by what a DIFFERENT test happened to do first.
//
// Confirmed live, not just by reasoning: before this reset existed,
// markReadSucceedsWhenRepositoryUpdatesOwnedNotification's verify(notifications).markRead(42L, 7L)
// only stayed green because surefire happened to run it BEFORE
// markReadRejectsMissingOrUnownedNotification re-invoked that exact same mock call with the same
// args - order-dependent, not actually pinned by either test. The surefire report
// (target/surefire-reports/TEST-th.co.glr.hr.notification.NotificationServiceTest.xml) lists
// markReadSucceeds... before markReadRejects..., which is what let it slip through.
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
        when(notifications.findEmployeeRecipient(7L))
            .thenReturn(Optional.of(new EmailRecipient("employee@glr.co.th", "สมชาย ใจดี")));

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
        // The dead-code defect this locks in: NotificationService used to call the email service's
        // 4-arg overload, so recipientName/link never reached the email (always the generic greeting,
        // never a portal link) even though notify() receives both. Assert BOTH the name-driven
        // greeting AND the link actually reach the mailer - either dropped is the regression. The
        // 5th argument (inline images) is always the GL&R logo - see NotificationEmailServiceTest for
        // the dedicated coverage of that; here it's just asserted present so this call keeps matching
        // the real 5-arg Mailer.sendHtml signature.
        verify(mailer).sendHtml(
            eq("employee@glr.co.th"),
            eq("[GL&R HR] Leave submitted"),
            argThat(html -> html.contains("เรียน คุณสมชาย ใจดี,")
                && html.contains("Your leave request was submitted.")
                && html.contains("https://portal.example/leave/1")
                && html.contains("ระบบบริหารงานบุคคล GL&amp;R")),
            argThat(text -> text.contains("เรียน คุณสมชาย ใจดี,")
                && text.contains("Your leave request was submitted.")
                && text.contains("https://portal.example/leave/1")
                && text.contains("ระบบบริหารงานบุคคล GL&R")),
            argThat(images -> images.size() == 1 && "glr-logo".equals(images.get(0).contentId())));
    }

    // This TestConfig has overrideTo="" (blank, matching prod/demo) - pins that prod/demo behaviour
    // does NOT change by this fix: an employee who exists but has no address on file still gets no
    // email when no override is configured. See NotificationServiceOverrideConfiguredTest for the
    // overrideTo-set case, which is where the actual regression lived (the bug this whole change
    // fixes: app.mail.override-to never got a chance to run for an addressless employee, because
    // NotificationService used to gate on the address itself before NotificationEmailService, which
    // owns override-to, ever saw the recipient).
    @Test
    void notifySkipsEmailWhenEmployeeHasNoEmailAndNoOverrideConfigured() {
        NotificationDto saved = new NotificationDto(
            43L, 7L, null, null, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1", false,
            Instant.parse("2026-07-08T00:00:00Z"));
        when(notifications.insert(7L, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1")).thenReturn(43L);
        when(notifications.findById(43L)).thenReturn(Optional.of(saved));
        when(notifications.findEmployeeRecipient(7L))
            .thenReturn(Optional.of(new EmailRecipient(null, "สมชาย ใจดี")));

        service.notify(
            7L,
            "LEAVE_SUBMITTED",
            "Leave submitted",
            "Your leave request was submitted.",
            "/leave/1",
            true);

        verifyNoInteractions(mailer);
    }

    // The only case findEmployeeRecipient() still returns empty for: no employee row at all. That
    // must skip email under every override configuration, since there is no employee id left to
    // notify about. See NotificationServiceOverrideConfiguredTest for the overrideTo-set variant of
    // this same case - override-to can rescue an addressless employee, but it cannot fabricate a
    // recipient for an id nobody recognises.
    @Test
    void notifySkipsEmailWhenEmployeeRowIsMissing() {
        NotificationDto saved = new NotificationDto(
            44L, 9L, null, null, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1", false,
            Instant.parse("2026-07-08T00:00:00Z"));
        when(notifications.insert(9L, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1")).thenReturn(44L);
        when(notifications.findById(44L)).thenReturn(Optional.of(saved));
        when(notifications.findEmployeeRecipient(9L)).thenReturn(Optional.empty());

        service.notify(
            9L,
            "LEAVE_SUBMITTED",
            "Leave submitted",
            "Your leave request was submitted.",
            "/leave/1",
            true);

        verifyNoInteractions(mailer);
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

// A SEPARATE top-level test class (package-private, like the one above - Java allows more than one
// per file as long as at most one is public), not a JUnit @Nested inner class. This is deliberate:
// Spring's @NestedTestConfiguration default (INHERIT) analogises a @Nested class's own context
// configuration to a subclass re-declaring @ContextConfiguration in a superclass hierarchy, which
// does not obviously guarantee this class's overrideTo="tester@example.com" TestConfig fully
// replaces (rather than merges with or is shadowed by) NotificationServiceTest's overrideTo="".
// Getting that ambiguity wrong would silently run these "override configured" tests against the
// blank-override context instead - exactly the kind of vacuous-but-green test this bug fix cannot
// afford. Two independent top-level classes, each with its own @SpringJUnitConfig, sidestep the
// question entirely: each is unambiguously its own context-cache key.
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

    // THE regression test for this bug. Before the fix, NotificationService.notify() called
    // findEmployeeEmail(), which returned Optional.empty() for exactly this employee (row exists, no
    // address on file) - so NotificationEmailService.send(), and therefore app.mail.override-to, was
    // never reached at all, despite being configured. Observed on UAT 2026-08-09: one welfare submit
    // fanned out 5 notifications, and the 3 recipients with no email on file were silently dropped
    // despite APP_MAIL_OVERRIDE_TO being set.
    //
    // #782 UPDATE: this test used to assert mailer.sendHtml received "tester@example.com" - the
    // override ADDRESS - and a body containing "no email on file", because NotificationEmailService
    // used to compute both itself (the swap, and a redirect note that happened to name the reason).
    // Neither happens in this class any more (see its overrideConfigured Javadoc): recipient
    // redirection AND the distinguishing note now live entirely in
    // th.co.glr.hr.mail.OverrideRedirectingMailer, which wraps the REAL Mailer bean in production but
    // is NOT present in this test's hand-wired TestConfig (mailer() below is a bare mock) - so `to` is
    // asserted null here, and the body is asserted to be the ORDINARY template with no note of any
    // kind, exactly what a no-override send would also produce. What this test still proves is the
    // half that is genuinely still this layer's: an addressless employee is NOT skipped when an
    // override is configured (attempts the send instead). The address-swap itself is proven end-to-end
    // against a real decorator in
    // th.co.glr.hr.mail.MailOverrideContainmentTest#notificationEmailServiceStillRedirectsWhenTheEmployeeHasNoAddressOnFile.
    // Losing either half silently would be exactly the kind of regression this file exists to catch,
    // so neither is dropped - each now lives at the layer that actually owns it.
    @Test
    void notifyStillAttemptsEmailWhenEmployeeHasNoEmailOnFileAndOverrideIsConfigured() {
        NotificationDto saved = new NotificationDto(
            42L, 7L, null, null, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1", false,
            Instant.parse("2026-07-08T00:00:00Z"));
        when(notifications.insert(7L, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1")).thenReturn(42L);
        when(notifications.findById(42L)).thenReturn(Optional.of(saved));
        when(notifications.findEmployeeRecipient(7L))
            .thenReturn(Optional.of(new EmailRecipient(null, "สมชาย ใจดี")));

        service.notify(
            7L,
            "LEAVE_SUBMITTED",
            "Leave submitted",
            "Your leave request was submitted.",
            "/leave/1",
            true);

        verify(mailer).sendHtml(
            isNull(),
            eq("[GL&R HR] Leave submitted"),
            argThat(html -> html.contains("เรียน คุณสมชาย ใจดี,")
                && html.contains("Your leave request was submitted.")
                && !html.contains("Redirected for testing")),
            argThat(text -> text.contains("เรียน คุณสมชาย ใจดี,")
                && text.contains("Your leave request was submitted.")
                && !text.contains("Redirected for testing")),
            argThat(images -> images.size() == 1 && "glr-logo".equals(images.get(0).contentId())));
    }

    // Even with an override configured, a MISSING employee row must still skip email - override-to
    // can rescue an employee that exists but has no address, not fabricate a recipient for an id
    // nobody recognises. Mirrors NotificationServiceTest.notifySkipsEmailWhenEmployeeRowIsMissing
    // under the opposite override configuration; both must agree.
    @Test
    void notifySkipsEmailWhenEmployeeRowIsMissing() {
        NotificationDto saved = new NotificationDto(
            45L, 9L, null, null, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1", false,
            Instant.parse("2026-07-08T00:00:00Z"));
        when(notifications.insert(9L, "LEAVE_SUBMITTED", "Leave submitted",
            "Your leave request was submitted.", "/leave/1")).thenReturn(45L);
        when(notifications.findById(45L)).thenReturn(Optional.of(saved));
        when(notifications.findEmployeeRecipient(9L)).thenReturn(Optional.empty());

        service.notify(
            9L,
            "LEAVE_SUBMITTED",
            "Leave submitted",
            "Your leave request was submitted.",
            "/leave/1",
            true);

        verifyNoInteractions(mailer);
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
