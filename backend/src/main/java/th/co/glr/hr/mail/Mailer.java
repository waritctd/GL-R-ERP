package th.co.glr.hr.mail;

/**
 * Transport-agnostic email sender. One implementation is active per deployment, selected by the
 * {@code app.mail.provider} property so the transport is a config switch, not per-environment code:
 * <ul>
 *   <li>{@code resend} ({@link ResendMailer}) - Resend HTTP API. Used on cloud hosts that block
 *       outbound SMTP (e.g. Render).</li>
 *   <li>{@code smtp} ({@link SmtpMailer}) - authenticated SMTP submission. Used on-prem, sending as
 *       the company mailbox ({@code app.mail.from}, e.g. job@glr.co.th) through the company mail host.</li>
 *   <li>{@code log} ({@link LogMailer}) - logs instead of sending. Default for dev/CI so the app
 *       boots and business flows run without any mail credentials.</li>
 * </ul>
 *
 * <p>Callers ({@link th.co.glr.hr.notification.NotificationEmailService},
 * {@link th.co.glr.hr.factory.FactoryEmailService}) depend on this interface, never a concrete
 * transport - swapping providers is a config change, not a code change.
 */
public interface Mailer {
    /**
     * @throws MailSendException if the underlying transport fails (network, auth, rejected
     *     recipient, etc.). Callers decide whether to swallow-and-log (best-effort notifications) or
     *     surface it (e.g. factory emails, which fail loudly today).
     */
    void send(String to, String subject, String body);

    /**
     * Sends one attachment with a plain-text email body.
     *
     * @throws MailSendException if the underlying transport fails.
     */
    void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes);
}
