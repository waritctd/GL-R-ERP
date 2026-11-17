package th.co.glr.hr.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.resend.core.exception.ResendException;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;

/**
 * The failure-classification table (adversarial review round 3, with the round-3 rulings applied), each failure
 * built the way the mailers really surface it:
 * <ul>
 *   <li>Resend: an HTTP error response is {@code MailSendException(msg, ResendException(status, body))}
 *       (ResendMailer); a transport error is the SDK's bare {@code RuntimeException(IOException)} from OkHttp.</li>
 *   <li>SMTP: SmtpMailer wraps whatever Spring's JavaMailSenderImpl throws; a connect failure there is Spring
 *       {@code MailSendException("Mail server connection failed", MailConnectException)} with the per-message
 *       exception set to the same MailConnectException; Angus wraps the socket error in SocketConnectException.</li>
 * </ul>
 * FAILED = certainly not accepted, retried by the plain retry. UNKNOWN = may have been accepted, no outcome,
 * re-sent only through the warned opt-in.
 */
class MailFailureTableTest {

    private static final boolean FAILED = true;
    private static final boolean UNKNOWN = false;

    @Test
    void theClassificationTable() {
        Map<String, Object[]> table = new LinkedHashMap<>();
        table.put("Resend 401 invalid key", row(resendHttp(401), FAILED));
        table.put("Resend 403", row(resendHttp(403), FAILED));
        table.put("Resend 422", row(resendHttp(422), FAILED));
        table.put("Resend 429 after retries", row(resendHttp(429), FAILED));
        table.put("Resend 503 unavailable", row(resendHttp(503), FAILED));
        table.put("Resend 500", row(resendHttp(500), UNKNOWN));
        table.put("Resend 502 gateway", row(resendHttp(502), UNKNOWN));
        table.put("Resend 504 gateway timeout", row(resendHttp(504), UNKNOWN));
        table.put("Resend DNS failure", row(resendIo(new UnknownHostException("api.example")), FAILED));
        table.put("Resend connection refused", row(resendIo(new ConnectException("Failed to connect")), FAILED));
        table.put("Resend CONNECT timeout", row(resendIo(connectTimeout()), FAILED));
        table.put("Resend TLS handshake failure", row(resendIo(new SSLHandshakeException("PKIX path")), FAILED));
        table.put("Resend READ timeout", row(resendIo(new SocketTimeoutException("timeout")), UNKNOWN));
        table.put("SMTP DNS failure", row(smtpConnect(new UnknownHostException("smtp.example")), FAILED));
        table.put("SMTP connection refused", row(smtpConnect(new ConnectException("Connection refused")), FAILED));
        table.put("SMTP CONNECT timeout", row(smtpConnect(connectTimeout()), FAILED));
        // Angus raises MailConnectException only when the connection could not be opened, whatever the socket said.
        table.put("SMTP network unreachable at connect", row(smtpConnect(
            new java.net.SocketException("Network is unreachable")), FAILED));
        table.put("SMTP STARTTLS failure", row(smtpStartTls(new SSLHandshakeException("handshake")), FAILED));
        table.put("SMTP auth failure", row(new MailSendException("SMTP HTML send failed",
            new MailAuthenticationException("535")), FAILED));
        table.put("SMTP READ timeout during DATA", row(new MailSendException("SMTP HTML send failed",
            new org.springframework.mail.MailSendException(Map.of(new Object(),
                new MessagingException("IO during DATA", new SocketTimeoutException("Read timed out"))))), UNKNOWN));

        // Round 4: FAILED needs positive proof.
        table.put("Resend 409 concurrent idempotent request", row(resendHttp(409), UNKNOWN));
        table.put("Resend 409 invalid idempotent request", row(new MailSendException("Resend send failed (status=409)",
            new ResendException(409, "{\"name\":\"invalid_idempotent_request\",\"message\":\"x\"}")), UNKNOWN));
        table.put("Resend 408 request timeout", row(resendHttp(408), UNKNOWN));
        table.put("Resend 400", row(resendHttp(400), FAILED));
        table.put("Resend 404", row(resendHttp(404), FAILED));
        table.put("Resend TLS peer not verified (handshake phase)",
            row(resendIo(new javax.net.ssl.SSLPeerUnverifiedException("Hostname not verified")), FAILED));
        // OkHttp retries a failed call by itself and throws the LAST failure with the earlier ones attached
        // (Util.withSuppressed). The earlier one may be a request that was written and processed, so whatever the
        // last failure looks like, the result is unknowable.
        table.put("OkHttp retry: stale connection, then connection refused",
            row(okhttpRetryThenConnectFails(new ConnectException("Failed to connect to api.example")), UNKNOWN));
        table.put("OkHttp retry: stale connection, then DNS failure",
            row(okhttpRetryThenConnectFails(new UnknownHostException("api.example")), UNKNOWN));
        table.put("OkHttp retry: stale connection, then TLS handshake failure",
            row(okhttpRetryThenConnectFails(new SSLHandshakeException("handshake")), UNKNOWN));
        table.put("OkHttp retry: stale connection, then connect timeout",
            row(okhttpRetryThenConnectFails(connectTimeout()), UNKNOWN));
        table.put("OkHttp retry: stale connection, then TLS peer not verified", row(okhttpRetryThenConnectFails(
            new javax.net.ssl.SSLPeerUnverifiedException("Hostname not verified")), UNKNOWN));
        IOException refusedDeepInTheChain = new ConnectException("Failed to connect");
        refusedDeepInTheChain.addSuppressed(new IOException("earlier attempt"));
        table.put("An earlier failure attached deeper in the chain", row(new MailSendException("wrapped",
            new IllegalStateException("middle", refusedDeepInTheChain)), UNKNOWN));
        IOException earlierBelowARefusal = new IOException("socket closed");
        earlierBelowARefusal.addSuppressed(new IOException("earlier attempt"));
        table.put("A refusal whose own cause carries an earlier failure", row(new MailSendException("x",
            new MailAuthenticationException("535", earlierBelowARefusal)), UNKNOWN));
        Throwable smtpWithEarlier = smtpConnect(new ConnectException("Connection refused"));
        smtpWithEarlier.addSuppressed(new IOException("earlier"));
        table.put("SMTP refusal with an earlier failure attached", row(smtpWithEarlier, UNKNOWN));

        java.util.List<String> misclassified = new java.util.ArrayList<>();
        table.forEach((name, row) -> {
            if (MailFailures.certainlyNotAccepted((Throwable) row[0]) != (boolean) row[1]) {
                misclassified.add(name + " (expected " + ((boolean) row[1] ? "FAILED" : "UNKNOWN") + ")");
            }
        });
        assertThat(misclassified).as("rows classified wrongly").isEmpty();
    }

    // ── The same distinctions on REAL sockets, so the connect-vs-read rule is not a guess about stack shapes ──────

    @Test
    void aRealReadTimeoutFromOkHttpIsUnknown() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket accepted = silent.accept()) {
                    Thread.sleep(3000);
                } catch (Exception ignored) {
                    // the client has timed out
                }
            });
            acceptor.start();
            OkHttpClient client = new OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build();
            Exception failure = callAsTheResendSdkDoes(client, "http://127.0.0.1:" + silent.getLocalPort() + "/emails");
            assertThat(failure.getCause()).isInstanceOf(SocketTimeoutException.class);
            assertThat(MailFailures.certainlyNotAccepted(failure)).isFalse();
        }
    }

    @Test
    void aRealTlsHandshakeFailureFromOkHttpIsFailed() throws Exception {
        try (ServerSocket plain = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket accepted = plain.accept(); OutputStream out = accepted.getOutputStream()) {
                    out.write("220 not a TLS server\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // the client has given up
                }
            });
            acceptor.start();
            OkHttpClient client = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS).build();
            Exception failure = callAsTheResendSdkDoes(client, "https://127.0.0.1:" + plain.getLocalPort() + "/emails");
            assertThat(failure.getCause()).isInstanceOf(javax.net.ssl.SSLException.class);
            assertThat(MailFailures.certainlyNotAccepted(failure)).isTrue();
        }
    }

    @Test
    void aRealConnectTimeoutIsFailed_whereTheNetworkDropsPackets() {
        // TEST-NET-1 (RFC 5737) is never routed; most networks drop the SYN, giving a genuine JDK connect timeout.
        // Where the network answers "unreachable" instead, this test has nothing to observe and is skipped.
        SocketTimeoutException timeout = null;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("192.0.2.1", 9), 300);
        } catch (SocketTimeoutException expected) {
            timeout = expected;
        } catch (IOException other) {
            assumeTrue(false, "network answered " + other + " instead of dropping the SYN");
        }
        assumeTrue(timeout != null, "connected unexpectedly");
        assertThat(MailFailures.certainlyNotAccepted(new RuntimeException(timeout))).isTrue();
        assertThat(MailFailures.certainlyNotAccepted(smtpConnect(timeout))).isTrue();
    }

    // ── helpers: the shapes each mailer really produces ──────────────────────────────────────────────────────

    private static Object[] row(Throwable failure, boolean certainlyNotAccepted) {
        return new Object[] {failure, certainlyNotAccepted};
    }

    private static Throwable resendHttp(int status) {
        return new MailSendException("Resend send failed (status=" + status + ")",
            new ResendException(status, "{\"name\":\"error\",\"message\":\"x\"}"));
    }

    private static Throwable resendIo(IOException io) {
        return new RuntimeException(io);
    }

    /** OkHttp 4.12 RetryAndFollowUpInterceptor: attempt 1 fails on a stale pooled connection after the request was
     * written; the retry's connect fails; the thrown exception is the connect failure with attempt 1's failure
     * attached. The Resend SDK wraps it in RuntimeException(IOException). (Adversarial review round 4.) */
    private static Throwable okhttpRetryThenConnectFails(IOException connectFailure) {
        connectFailure.addSuppressed(new IOException("unexpected end of stream on https://api.example/..."));
        return new RuntimeException(connectFailure);
    }

    private static Throwable smtpConnect(IOException socketError) {
        MailConnectException connect = new MailConnectException(
            new SocketConnectException("Using SocketFactory", socketError, "smtp.example", 587, 10_000));
        return new MailSendException("SMTP HTML send failed",
            new org.springframework.mail.MailSendException("Mail server connection failed", connect));
    }

    /** Angus upgrades the socket with STARTTLS during connect and wraps a handshake failure like this. */
    private static Throwable smtpStartTls(IOException handshake) {
        return new MailSendException("SMTP HTML send failed",
            new org.springframework.mail.MailSendException("Mail server connection failed",
                new MessagingException("Could not convert socket to TLS", handshake)));
    }

    /** What the JDK's socket throws for a connect timeout: the type, message and the frame it comes from. */
    private static SocketTimeoutException connectTimeout() {
        SocketTimeoutException timeout = new SocketTimeoutException("Connect timed out");
        timeout.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("sun.nio.ch.NioSocketImpl", "timedFinishConnect", "NioSocketImpl.java", 546),
            new StackTraceElement("sun.nio.ch.NioSocketImpl", "connect", "NioSocketImpl.java", 592),
            new StackTraceElement("java.net.Socket", "connect", "Socket.java", 751),
        });
        return timeout;
    }

    /** The Resend SDK (4.13) runs OkHttp's call and wraps an IOException as {@code new RuntimeException(e)}. */
    private static Exception callAsTheResendSdkDoes(OkHttpClient client, String url) {
        try (okhttp3.Response ignored = client.newCall(new Request.Builder().url(url).build()).execute()) {
            throw new AssertionError("the call unexpectedly succeeded");
        } catch (IOException io) {
            return new RuntimeException(io);
        }
    }
}
