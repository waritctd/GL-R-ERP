package th.co.glr.hr.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.mail.MailFailures;

/**
 * Sends one release-notice run's mails and records each person's progress, after the run's attempt rows have
 * committed (ReleaseNoticeService hands the batch over once its transaction has returned).
 *
 * <p>One async task per run, sending one mail after another: a run is a few dozen mails, and sending them in
 * sequence keeps the provider's per-second rate limit out of the picture.
 *
 * <p>Per person, in this order:
 * <ol>
 *   <li><b>Start.</b> In its own transaction, under the SAME advisory lock the email actions hold, check that this
 *       attempt is still the person's latest one (after any reset) and append {@code RELEASE_NOTE_EMAIL_STARTED}.
 *       If a newer attempt exists (a retry reached this person while this batch waited in the queue), this one is
 *       superseded and nothing is sent. If the start row cannot be written, nothing is sent either: an attempt
 *       with no start row is provably unsent, and after the in-flight window it is retried like a failure.</li>
 *   <li><b>Send.</b> The provider call either returns ({@code RELEASE_NOTE_EMAIL_SENT}) or throws. A failure that
 *       proves the provider did not accept the mail (th.co.glr.hr.mail.MailFailures: an error response, never
 *       connected, refused at login or envelope, or a Mailer that does not deliver) is
 *       {@code RELEASE_NOTE_EMAIL_FAILED}, with a short reason, addresses masked. Any other failure (interrupt,
 *       timeout, reset after the request was written, anything unrecognised) records NO outcome: the mail may have
 *       been accepted, so it reads "sending", then "ไม่ทราบผล", and is re-sent only through the warned opt-in.</li>
 * </ol>
 * Every row is append-only and tied to the attempt by its token. A crash between the start row and the outcome
 * leaves "started, no outcome": "sending" while the start is recent, then "unconfirmed" — never "sent", never
 * re-sent without an explicit opt-in (ReleaseNoticeRepository#emailStates).
 *
 * <p>Shutdown: the loop checks the thread's interrupt flag before each person. Once interrupted (a deploy's
 * {@code shutdownNow}), it sends nothing more and records everyone not yet started as FAILED "interrupted", so
 * they are retried rather than left "sending".
 */
@Component
public class ReleaseNoticeDelivery {
    private static final Logger log = LoggerFactory.getLogger(ReleaseNoticeDelivery.class);
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+");
    private static final int MAX_REASON = 300;
    /** Reason on the FAILED rows written when the executor refuses the batch (ReleaseNoticeService#handOff). */
    static final String NOT_QUEUED = "could not queue";
    static final String INTERRUPTED = "interrupted";

    private final NotificationEmailService emailService;
    private final AuditService audit;
    private final ReleaseNoticeRepository releaseNotices;
    private final TransactionTemplate ownTransaction;
    /** Test seam only (no-op in production): runs inside the start transaction, after the supersede check and before
     * the STARTED row, so a concurrency test can hold a send exactly where an unlocked check would race. */
    java.util.function.Consumer<Delivery> beforeStartRow = delivery -> { };

    public ReleaseNoticeDelivery(NotificationEmailService emailService, AuditService audit,
                                 ReleaseNoticeRepository releaseNotices, PlatformTransactionManager transactionManager) {
        this.emailService = emailService;
        this.audit = audit;
        this.releaseNotices = releaseNotices;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async
    public void deliver(UserPrincipal actor, String releaseId, List<Delivery> deliveries) {
        for (int i = 0; i < deliveries.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Release notice run interrupted: release={} not sent={}", releaseId, deliveries.size() - i);
                recordFailed(actor, releaseId, deliveries.subList(i, deliveries.size()), INTERRUPTED);
                return;
            }
            deliverOne(actor, releaseId, deliveries.get(i));
        }
    }

    /** Appends a FAILED outcome for each delivery, e.g. when the batch never reached the sender. Each row commits
     * on its own, so one failing write does not stop the others. */
    public void recordFailed(UserPrincipal actor, String releaseId, List<Delivery> deliveries, String reason) {
        for (Delivery delivery : deliveries) {
            recordOutcome(actor, releaseId, delivery, ReleaseNoticeRepository.FAILED_ACTION, reason);
        }
    }

    private void deliverOne(UserPrincipal actor, String releaseId, Delivery delivery) {
        if (!start(actor, releaseId, delivery)) {
            return;
        }
        String outcome;
        String reason;
        try {
            emailService.sendReleaseNotice(delivery.to().email(), delivery.to().name(), delivery.email(),
                delivery.attempt());
            outcome = ReleaseNoticeRepository.SENT_ACTION;
            reason = null;
        } catch (RuntimeException exception) {
            reason = reason(exception);
            if (!MailFailures.certainlyNotAccepted(exception)) {
                // The call had begun and its result is unknowable (interrupt, timeout, reset after the request was
                // written, anything unrecognised): the provider may have accepted it. No outcome is recorded, so it
                // reads "sending", then "ไม่ทราบผล", and only the warned opt-in sends it again.
                log.error("Release notice email outcome UNKNOWN, left unrecorded: release={} employee={} error={}",
                    releaseId, delivery.employeeId(), reason);
                return;
            }
            outcome = ReleaseNoticeRepository.FAILED_ACTION;
            log.error("Release notice email failed: release={} employee={} reason={}",
                releaseId, delivery.employeeId(), reason);
        }
        recordOutcome(actor, releaseId, delivery, outcome, reason);
    }

    /** True when this attempt may be sent now: it is still the latest one and its start row is committed. */
    private boolean start(UserPrincipal actor, String releaseId, Delivery delivery) {
        try {
            Boolean started = uninterrupted(() -> ownTransaction.execute(status -> {
                releaseNotices.lock("email:" + releaseId);
                String latest = releaseNotices.latestAttempt(releaseId, delivery.employeeId());
                if (!delivery.attempt().equals(latest)) {
                    return false;
                }
                beforeStartRow.accept(delivery);
                audit.record(actor, ReleaseNoticeRepository.STARTED_ACTION, ReleaseNoticeRepository.EMAILED_ENTITY,
                    delivery.employeeId(), null, Map.of("releaseId", releaseId, "attempt", delivery.attempt()));
                return true;
            }));
            if (!Boolean.TRUE.equals(started)) {
                log.info("Release notice email skipped, superseded by a newer attempt: release={} employee={}",
                    releaseId, delivery.employeeId());
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            // Not started, so provably not sent: after the in-flight window it is retried like a failure.
            log.error("Release notice email not started (start row not written): release={} employee={} error={}",
                releaseId, delivery.employeeId(), exception.getMessage());
            return false;
        }
    }

    private void recordOutcome(UserPrincipal actor, String releaseId, Delivery delivery, String action, String reason) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("releaseId", releaseId);
        after.put("attempt", delivery.attempt());
        if (reason != null) {
            after.put("reason", reason);
        }
        try {
            uninterrupted(() -> ownTransaction.execute(status -> {
                audit.record(actor, action, ReleaseNoticeRepository.EMAILED_ENTITY, delivery.employeeId(), null, after);
                return null;
            }));
        } catch (RuntimeException exception) {
            // Left without an outcome: "sending", then "unconfirmed" — never "sent", never auto-resent.
            log.error("Release notice email outcome NOT recorded: release={} employee={} outcome={} error={}",
                releaseId, delivery.employeeId(), action, exception.getMessage());
        }
    }

    /** Runs a database write with the interrupt flag cleared (a pool may refuse an interrupted thread), then
     * restores it, so an interrupted run can still record what it did and did not send. */
    private static <T> T uninterrupted(java.util.function.Supplier<T> write) {
        boolean interrupted = Thread.interrupted();
        try {
            return write.get();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The provider's message, one line, addresses masked, capped: enough for CEO/HR and an operator to tell a
     * bad key from a bad address, without copying an address into the audit log. */
    static String reason(Throwable exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String masked = maskAddresses(message.replaceAll("\\s+", " ").trim());
        return masked.length() > MAX_REASON ? masked.substring(0, MAX_REASON) : masked;
    }

    /** Replaces every email address in {@code text} with {@code <address>}; null stays null. */
    static String maskAddresses(String text) {
        return text == null ? null : EMAIL_ADDRESS.matcher(text).replaceAll("<address>");
    }

    /** One person's mail of one attempt; {@code attempt} is the token on that person's attempt row. */
    public record Delivery(long employeeId, String attempt, EmailRecipient to, ReleaseNoticeEmail email) {
    }
}
