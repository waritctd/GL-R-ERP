package th.co.glr.hr.mail;

/** Unchecked wrapper for a transport failure, so callers aren't forced to handle checked types. */
public class MailSendException extends RuntimeException {
    public MailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
