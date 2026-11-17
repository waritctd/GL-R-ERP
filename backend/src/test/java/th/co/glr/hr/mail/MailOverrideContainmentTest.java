package th.co.glr.hr.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.notification.NotificationEmailService;

/** Issue #782's concrete regression pin: drives TODAY's real sender - NotificationEmailService (which
 * hand-rolled its own override check, twice) - through a REAL OverrideRedirectingMailer, and asserts
 * wrong-way-round that the caller-supplied address never reaches the terminal transport. */
class MailOverrideContainmentTest {
    private static final String OVERRIDE_TO = "qa-inbox@example.com";
    private static final String REAL_EMPLOYEE_ADDRESS = "real-employee@company.example";

    private final Mailer terminalTransport = mock(Mailer.class);
    private final Mailer mailer = new OverrideRedirectingMailer(terminalTransport, OVERRIDE_TO);

    @Test
    void notificationEmailServiceSendNeverReachesTheRealEmployeeAddress() {
        NotificationEmailService service = notificationEmailService();

        service.send(7L, REAL_EMPLOYEE_ADDRESS, "สมชาย", "Leave submitted", "your leave was submitted", "/leave/1");

        verify(terminalTransport, never()).sendHtml(eq(REAL_EMPLOYEE_ADDRESS), anyString(), anyString(), anyString(), anyList());
        verify(terminalTransport).sendHtml(eq(OVERRIDE_TO), anyString(),
            argThat(html -> html.contains(REAL_EMPLOYEE_ADDRESS)),
            argThat(text -> text.contains(REAL_EMPLOYEE_ADDRESS)),
            anyList());
    }

    @Test
    void notificationEmailServiceSendWithAttachmentNeverReachesTheRealEmployeeAddress() {
        NotificationEmailService service = notificationEmailService();

        service.sendWithAttachment(REAL_EMPLOYEE_ADDRESS, "Payslip", "Attached", "payslip.pdf", "%PDF".getBytes());

        verify(terminalTransport, never()).sendWithAttachment(eq(REAL_EMPLOYEE_ADDRESS), anyString(), anyString(), anyString(), any());
        verify(terminalTransport).sendWithAttachment(eq(OVERRIDE_TO), eq("Payslip"),
            argThat(body -> body.contains(REAL_EMPLOYEE_ADDRESS)), eq("payslip.pdf"), any());
    }

    // Same as notificationEmailServiceSendNeverReachesTheRealEmployeeAddress, but for the employee that
    // NotificationService fans a notification out to despite having NO address on file at all - the exact
    // production incident NotificationServiceOverrideConfiguredTest pins at the NotificationService layer.
    @Test
    void notificationEmailServiceStillRedirectsWhenTheEmployeeHasNoAddressOnFile() {
        NotificationEmailService service = notificationEmailService();

        service.send(7L, null, "สมชาย", "Leave submitted", "your leave was submitted", "/leave/1");

        verify(terminalTransport).sendHtml(eq(OVERRIDE_TO), anyString(),
            argThat(html -> html.contains("no address on file")),
            argThat(text -> text.contains("no address on file")),
            anyList());
    }

    /** The THIRD constructor arg mirrors production wiring: NotificationEmailService and
     * MailOverrideBeanPostProcessor both read app.mail.override-to from the same Spring property, so a real
     * deployment can never have one configured and not the other. */
    private NotificationEmailService notificationEmailService() {
        return new NotificationEmailService(mailer, new BrandAssets(), OVERRIDE_TO, "", "https://portal.example");
    }
}
