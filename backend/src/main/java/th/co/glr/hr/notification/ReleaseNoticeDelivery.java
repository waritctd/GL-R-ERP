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

/**
 * Sends one release-notice run's mails and records each person's OUTCOME, after the run's attempt rows have
 * committed (ReleaseNoticeService hands the batch over in an after-commit hook).
 *
 * <p>One async task per run, sending one mail after another: a run is a few dozen mails, and sending them in
 * sequence keeps the provider's per-second rate limit out of the picture (the 16 Nov run fired all of them at
 * once on the shared pool).
 *
 * <p>For each person: the provider call either returns ({@code RELEASE_NOTE_EMAIL_SENT}) or throws
 * ({@code RELEASE_NOTE_EMAIL_FAILED}, with a short reason whose email addresses are masked). Both rows are
 * append-only audit rows tied to the attempt by its token, written in their own transaction
 * ({@code REQUIRES_NEW}, so it also commits when this runs synchronously inside an after-commit hook). If the
 * process dies before that row commits, or the row cannot be written, the attempt has NO outcome and is reported
 * "sending" and later "unconfirmed" — never "sent", and never re-sent without an explicit opt-in (see
 * ReleaseNoticeRepository#emailStates).
 */
@Component
public class ReleaseNoticeDelivery {
    private static final Logger log = LoggerFactory.getLogger(ReleaseNoticeDelivery.class);
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+");
    private static final int MAX_REASON = 300;

    private final NotificationEmailService emailService;
    private final AuditService audit;
    private final TransactionTemplate ownTransaction;

    public ReleaseNoticeDelivery(NotificationEmailService emailService, AuditService audit,
                                 PlatformTransactionManager transactionManager) {
        this.emailService = emailService;
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async
    public void deliver(UserPrincipal actor, String releaseId, List<Delivery> deliveries) {
        for (Delivery delivery : deliveries) {
            deliverOne(actor, releaseId, delivery);
        }
    }

    private void deliverOne(UserPrincipal actor, String releaseId, Delivery delivery) {
        String outcome;
        String reason = null;
        try {
            emailService.sendReleaseNotice(delivery.to().email(), delivery.to().name(), delivery.email());
            outcome = ReleaseNoticeRepository.SENT_ACTION;
        } catch (RuntimeException exception) {
            outcome = ReleaseNoticeRepository.FAILED_ACTION;
            reason = reason(exception);
            log.error("Release notice email failed: release={} employee={} reason={}",
                releaseId, delivery.employeeId(), reason);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("releaseId", releaseId);
        after.put("attempt", delivery.attempt());
        if (reason != null) {
            after.put("reason", reason);
        }
        String action = outcome;
        try {
            ownTransaction.executeWithoutResult(status -> audit.record(actor, action,
                ReleaseNoticeRepository.EMAILED_ENTITY, delivery.employeeId(), null, after));
        } catch (RuntimeException exception) {
            // Left without an outcome: reported "sending", then "unconfirmed" — never "sent", never auto-resent.
            log.error("Release notice email outcome NOT recorded: release={} employee={} outcome={} error={}",
                releaseId, delivery.employeeId(), action, exception.getMessage());
        }
    }

    /** The provider's message, one line, addresses masked, capped: enough for CEO/HR and an operator to tell a
     * bad key from a bad address, without copying an address into the audit log. */
    static String reason(Throwable exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String masked = EMAIL_ADDRESS.matcher(message.replaceAll("\\s+", " ").trim()).replaceAll("<address>");
        return masked.length() > MAX_REASON ? masked.substring(0, MAX_REASON) : masked;
    }

    /** One person's mail of one attempt; {@code attempt} is the token on that person's attempt row. */
    public record Delivery(long employeeId, String attempt, EmailRecipient to, ReleaseNoticeEmail email) {
    }
}
