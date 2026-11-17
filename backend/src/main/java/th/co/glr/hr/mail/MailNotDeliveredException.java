package th.co.glr.hr.mail;

/** Thrown before any transport call when the active Mailer does not deliver (Mailer#delivers is false, e.g. the log
 * provider). Nothing left the server, so a caller recording outcomes may treat it as certainly not accepted. */
public class MailNotDeliveredException extends MailSendException {
    public MailNotDeliveredException(String message) {
        super(message, null);
    }
}
