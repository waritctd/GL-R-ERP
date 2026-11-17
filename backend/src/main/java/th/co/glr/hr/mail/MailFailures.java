package th.co.glr.hr.mail;

import com.resend.core.exception.ResendException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
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
 *   <li>Resend: a {@link ResendException} carrying an HTTP status other than 500, 502 and 504 — the API answered
 *       with an error (any 4xx including 429 after the retries, or 503); the SDK raises it only from a response it
 *       received. 500/502/504 are unknowable: an edge error can follow an accepted mail;</li>
 *   <li>never connected: {@link ConnectException}, {@link NoRouteToHostException}, {@link UnknownHostException},
 *       a connect-phase {@link SocketTimeoutException}, Angus' MailConnectException (both transports);</li>
 *   <li>TLS failed during the handshake, before any request was written;</li>
 *   <li>SMTP: {@link MailAuthenticationException} (refused at login), {@link MailPreparationException} (the message
 *       was never built), {@code jakarta.mail.SendFailedException} (the server refused the envelope or recipients).</li>
 * </ul>
 * <p><b>Unknowable</b>: everything else — in particular the Resend SDK's bare {@code RuntimeException} around an OkHttp
 * {@code IOException} (read timeout, connection reset, {@code InterruptedIOException} on a shutdown interrupt), a
 * {@code ResendException} without a status or with 500/502/504, an SMTP {@code MessagingException} or socket error during DATA, and any
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
            || (failure instanceof ResendException resend && isRefusingStatus(resend.getStatusCode()))
            || failure instanceof ConnectException
            || failure instanceof NoRouteToHostException
            || failure instanceof UnknownHostException
            || isConnectTimeout(failure)
            || isTlsHandshakeFailure(failure)
            || isSmtpConnectFailure(failure)
            || failure instanceof MailAuthenticationException
            || failure instanceof MailPreparationException
            || failure instanceof jakarta.mail.SendFailedException;
    }

    /** An HTTP error answer refuses the mail, EXCEPT 500, 502 and 504: a gateway or edge error can follow a mail the
     * backend already accepted (round 3 ruling). Every 4xx (401, 403, 422, 429 after the retries …) and 503 refuse. */
    private static boolean isRefusingStatus(Integer status) {
        return status != null && status != 500 && status != 502 && status != 504;
    }

    /**
     * A socket timeout raised while CONNECTING: nothing can have been written. The JDK uses the same type and nearly
     * the same text for a connect and a read timeout, and OkHttp rethrows the JDK's exception unwrapped, so the
     * connect phase is recognised by where it was thrown — {@code sun.nio.ch.NioSocketImpl#timedFinishConnect} under
     * {@code java.net.Socket#connect} — not by its message. A read timeout (JDK "Read timed out", or OkHttp/Okio's
     * "timeout") has no such frame and stays unknowable.
     */
    private static boolean isConnectTimeout(Throwable failure) {
        if (!(failure instanceof SocketTimeoutException)) {
            return false;
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            boolean jdkConnect = "sun.nio.ch.NioSocketImpl".equals(frame.getClassName())
                && "timedFinishConnect".equals(frame.getMethodName());
            boolean socketConnect = "java.net.Socket".equals(frame.getClassName())
                && "connect".equals(frame.getMethodName());
            if (jdkConnect || socketConnect) {
                return true;
            }
        }
        return false;
    }

    /** TLS failed before any application data: a failed handshake (OkHttp hands the request to the socket only after
     * the handshake and the host check; SMTP STARTTLS runs before MAIL FROM). A bare SSLException counts only when it
     * was raised inside {@code startHandshake}, e.g. "Unsupported or unrecognized SSL message" from a non-TLS peer. */
    private static boolean isTlsHandshakeFailure(Throwable failure) {
        if (failure instanceof SSLHandshakeException || failure instanceof SSLPeerUnverifiedException) {
            return true;
        }
        if (!(failure instanceof SSLException)) {
            return false;
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            if ("startHandshake".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /** Angus Mail throws MailConnectException only when the connection to the SMTP server could not be opened. Matched
     * by name so the mail package needs no compile dependency on the Angus implementation. */
    private static boolean isSmtpConnectFailure(Throwable failure) {
        String name = failure.getClass().getName();
        return "org.eclipse.angus.mail.util.MailConnectException".equals(name)
            || "com.sun.mail.util.MailConnectException".equals(name);
    }
}
