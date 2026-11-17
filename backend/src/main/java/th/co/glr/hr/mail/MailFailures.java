package th.co.glr.hr.mail;

import com.resend.core.exception.ResendException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;

/**
 * Tells a send failure that PROVES the provider did not accept the mail from one where that is unknowable. A caller
 * that records per-person outcomes (the release notice) may record only the first kind as a failure to be retried;
 * the second kind may already be in the recipient's inbox, so retrying it silently could send a second copy.
 *
 * <p><b>Certainly not accepted</b> (anywhere in the cause chain, including the per-message exceptions Spring collects
 * in its own {@code MailSendException}):
 * <ul>
 *   <li>{@link MailNotDeliveredException}: the active Mailer does not deliver, nothing was attempted;</li>
 *   <li>Resend: a {@link ResendException} carrying an HTTP status — the API answered with an error (4xx, 5xx, or 429
 *       after the retries); the SDK raises it only from a response it received;</li>
 *   <li>never connected: {@link ConnectException}, {@link UnknownHostException} (both transports);</li>
 *   <li>SMTP: {@link MailAuthenticationException} (refused at login), {@link MailPreparationException} (the message
 *       was never built), {@code jakarta.mail.SendFailedException} (the server refused the envelope or recipients).</li>
 * </ul>
 * <p><b>Unknowable</b>: everything else — in particular the Resend SDK's bare {@code RuntimeException} around an OkHttp
 * {@code IOException} (read timeout, connection reset, {@code InterruptedIOException} on a shutdown interrupt), a
 * {@code ResendException} without a status, an SMTP {@code MessagingException} or socket error during DATA, and any
 * exception this class does not recognise. When in doubt it answers "unknowable", the side that cannot double-send.
 */
public final class MailFailures {

    private MailFailures() {
    }

    public static boolean certainlyNotAccepted(Throwable failure) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (failure != null) {
            pending.add(failure);
        }
        while (!pending.isEmpty()) {
            Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (isRefusal(current)) {
                return true;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            if (current instanceof org.springframework.mail.MailSendException springSend) {
                Collections.addAll(pending, springSend.getMessageExceptions());
            }
        }
        return false;
    }

    private static boolean isRefusal(Throwable failure) {
        return failure instanceof MailNotDeliveredException
            || (failure instanceof ResendException resend && resend.getStatusCode() != null)
            || failure instanceof ConnectException
            || failure instanceof UnknownHostException
            || failure instanceof MailAuthenticationException
            || failure instanceof MailPreparationException
            || failure instanceof jakarta.mail.SendFailedException;
    }
}
