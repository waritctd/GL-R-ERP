package th.co.glr.hr.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.notification.NotificationEmailService;

/**
 * Issue #782's concrete regression pin: drives TODAY's real senders - {@link FactoryEmailService}
 * (which had NO override handling at all before this fix) and {@link NotificationEmailService} (which
 * hand-rolled its own, twice) - through a REAL {@link OverrideRedirectingMailer}, and asserts
 * wrong-way-round that the caller-supplied address never reaches the terminal transport.
 *
 * <p>This is deliberately in the {@code th.co.glr.hr.mail} package rather than split across
 * {@code factory}/{@code notification}: it is a cross-cutting property of the mail subsystem, not of
 * either sender, and package-private access to {@link OverrideRedirectingMailer} makes that the
 * natural home. It is also deliberately NOT the test that proves a FUTURE sender is safe - that is
 * {@link OverrideRedirectingMailerTest}, which exhausts every {@link Mailer} method rather than
 * enumerating callers. This class exists so the fix is proven against real, current call sites too,
 * not only against the shared collaborator in the abstract.
 */
class MailOverrideContainmentTest {
    private static final String OVERRIDE_TO = "qa-inbox@example.com";
    private static final String REAL_FACTORY_ADDRESS = "real-factory@supplier.example";
    private static final String REAL_EMPLOYEE_ADDRESS = "real-employee@company.example";

    private final Mailer terminalTransport = mock(Mailer.class);
    private final Mailer mailer = new OverrideRedirectingMailer(terminalTransport, OVERRIDE_TO);

    @Test
    void factoryEmailServiceSendNeverReachesTheRealFactoryAddress() {
        FactoryEmailService factoryEmailService = new FactoryEmailService(mailer);

        factoryEmailService.send(1L, "Acme Supplies", REAL_FACTORY_ADDRESS, "RFQ PR-1", "please quote");

        verify(terminalTransport, never()).send(eq(REAL_FACTORY_ADDRESS), anyString(), anyString());
        verify(terminalTransport).send(eq(OVERRIDE_TO), eq("RFQ PR-1"),
            argThat(body -> body.contains("please quote") && body.contains(REAL_FACTORY_ADDRESS)));
    }

    @Test
    void factoryEmailServiceSendWithAttachmentsNeverReachesTheRealFactoryAddress(@TempDir Path tempDir) throws Exception {
        Path attachmentFile = tempDir.resolve("drawing.pdf");
        Files.write(attachmentFile, "%PDF-fake".getBytes());
        var attachment = new FactoryEmailService.EmailAttachment("drawing.pdf", attachmentFile.toString(), "application/pdf");
        FactoryEmailService factoryEmailService = new FactoryEmailService(mailer);

        factoryEmailService.send(2L, "Acme Supplies", REAL_FACTORY_ADDRESS, "RFQ PR-2 with drawing",
            "please quote", List.of(attachment));

        verify(terminalTransport, never()).sendWithAttachments(eq(REAL_FACTORY_ADDRESS), anyString(), anyString(), anyList());
        verify(terminalTransport).sendWithAttachments(
            eq(OVERRIDE_TO),
            eq("RFQ PR-2 with drawing"),
            argThat(body -> body.contains("please quote") && body.contains(REAL_FACTORY_ADDRESS)),
            argThat(attachments -> attachments.size() == 1 && attachments.get(0).filename().equals("drawing.pdf")));
    }

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

    // Same as notificationEmailServiceSendNeverReachesTheRealEmployeeAddress, but for the employee
    // that NotificationService fans a notification out to despite having NO address on file at all -
    // the exact production incident NotificationServiceOverrideConfiguredTest (in the notification
    // package) pins at the NotificationService layer. Reproduced here too because that is the layer
    // this class is about: proving the ADDRESS SWAP itself - as opposed to "does NotificationEmailService
    // still attempt the send" - now happens in OverrideRedirectingMailer.
    @Test
    void notificationEmailServiceStillRedirectsWhenTheEmployeeHasNoAddressOnFile() {
        NotificationEmailService service = notificationEmailService();

        service.send(7L, null, "สมชาย", "Leave submitted", "your leave was submitted", "/leave/1");

        verify(terminalTransport).sendHtml(eq(OVERRIDE_TO), anyString(),
            argThat(html -> html.contains("no address on file")),
            argThat(text -> text.contains("no address on file")),
            anyList());
    }

    /**
     * The THIRD constructor arg mirrors production wiring: {@code NotificationEmailService} and
     * {@link MailOverrideBeanPostProcessor} both read {@code app.mail.override-to} from the same
     * Spring property, so a real deployment can never have one configured and not the other. Passing
     * {@link #OVERRIDE_TO} here (matching the {@link #mailer} field above) keeps that invariant true
     * in this hand-wired test too - {@code NotificationEmailService} needs to know an override is
     * active so it does not skip a no-address send before ever reaching the Mailer (see that class's
     * {@code overrideConfigured} field Javadoc); it plays no part in WHICH address the send lands on,
     * which is exactly what every test above is pinning.
     */
    private NotificationEmailService notificationEmailService() {
        return new NotificationEmailService(mailer, new BrandAssets(), OVERRIDE_TO, "", "https://portal.example");
    }
}
