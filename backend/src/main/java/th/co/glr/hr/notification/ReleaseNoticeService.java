package th.co.glr.hr.notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Audience;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Change;
import th.co.glr.hr.notification.ReleaseNoticeDtos.EmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.EmailStatus;
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishWithEmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;
import th.co.glr.hr.notification.ReleaseNoticeRepository.EmailState;

/**
 * Publishes a "มีอะไรใหม่" release notice to the bell and emails it — in one press ({@link #publishWithEmail}) — and
 * retries the email for whoever has not received it ({@link #email}).
 *
 * <p><b>Who is targeted.</b> Every ACTIVE employee whose role, derived exactly as at login by
 * {@link DivisionAccessPolicy#roleFor}, appears in at least one change's audience. The role is computed in Java
 * over {@link EmployeeAuthRepository#findAllActive()} rather than re-expressed as SQL, so this cannot drift from
 * the role the employee actually signs in with (including the can_create_quotation → sales rule). A reader's mail
 * lists only the changes their role is in ({@link ReleaseNoticePolicy#changesFor}); a role with none gets no mail.
 *
 * <p><b>Gate.</b> CEO and HR only ({@link ReleaseNoticePolicy#canManage}), enforced HERE, in the service, at the
 * top of every public method, so the real-DB integration test exercises the same check production runs. A refused
 * caller gets 403 before anything is read or written.
 *
 * <p><b>At most once per person, without a migration.</b> Publish skips employees who already have this release's
 * bell row. Email sends only to people whose latest attempt is absent or FAILED
 * ({@link ReleaseNoticeRepository#emailStates}): never to SENT, never to SENDING, and to UNCONFIRMED only when the
 * caller explicitly opts in. Each action takes a per-release advisory lock first, so two concurrent clicks
 * serialize. The attempt row commits with the transaction and the mails are handed to the async sender only after
 * commit, so a rolled-back call sends nothing.
 *
 * <p><b>Email never blocks the bell.</b> {@link #publishWithEmail} commits the bell rows in one transaction and
 * runs the email stage in a second one; an exception there is reported in the result, not thrown, and the bell
 * rows stay.
 */
@Service
public class ReleaseNoticeService {
    private static final Logger log = LoggerFactory.getLogger(ReleaseNoticeService.class);
    /** How long an attempt with no outcome may still be in the sender's hands (one sequential run of a few dozen
     * mails, each with the provider's own 429 back-off, takes minutes at most). Past it, it is UNCONFIRMED. */
    static final Duration IN_FLIGHT_WINDOW = Duration.ofMinutes(30);

    private final EmployeeAuthRepository employees;
    private final NotificationRepository notifications;
    private final ReleaseNoticeRepository releaseNotices;
    private final ReleaseNoticeDelivery delivery;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Duration inFlightWindow;

    @Autowired
    public ReleaseNoticeService(EmployeeAuthRepository employees, NotificationRepository notifications,
                                ReleaseNoticeRepository releaseNotices, ReleaseNoticeDelivery delivery,
                                AuditService audit, PlatformTransactionManager transactionManager) {
        this(employees, notifications, releaseNotices, delivery, audit, transactionManager, IN_FLIGHT_WINDOW);
    }

    ReleaseNoticeService(EmployeeAuthRepository employees, NotificationRepository notifications,
                         ReleaseNoticeRepository releaseNotices, ReleaseNoticeDelivery delivery,
                         AuditService audit, PlatformTransactionManager transactionManager,
                         Duration inFlightWindow) {
        this.employees = employees;
        this.notifications = notifications;
        this.releaseNotices = releaseNotices;
        this.delivery = delivery;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.inFlightWindow = inFlightWindow;
    }

    /** What publish and email would each still do, and where every targeted person's email stands — the admin
     * control's status and confirm-step numbers. Writes nothing. */
    @Transactional(readOnly = true)
    public Audience audience(UserPrincipal actor, Request request) {
        requireManager(actor);
        Request release = ReleaseNoticePolicy.normalize(request);
        List<Target> targets = targets(release);
        Set<Long> notified = releaseNotices.notifiedEmployeeIds(ReleaseNoticePolicy.link(release.releaseId()));
        Map<Long, EmailState> states = releaseNotices.emailStates(release.releaseId(), inFlightWindow);

        int alreadyNotified = 0;
        int sent = 0;
        int failed = 0;
        int sending = 0;
        int unconfirmed = 0;
        int notYetEmailed = 0;
        List<String> withoutEmailNames = new ArrayList<>();
        List<String> failedNames = new ArrayList<>();
        List<String> unconfirmedNames = new ArrayList<>();
        for (Target target : targets) {
            if (notified.contains(target.employeeId())) {
                alreadyNotified++;
            }
            EmailState state = states.get(target.employeeId());
            if (state == EmailState.SENT) {
                sent++;
            } else if (state == EmailState.SENDING) {
                sending++;
            } else if (state == EmailState.UNCONFIRMED) {
                unconfirmed++;
                unconfirmedNames.add(target.name());
            } else if (addressOf(target.employeeId()).isEmpty()) {
                withoutEmailNames.add(target.name());
            } else if (state == EmailState.FAILED) {
                failed++;
                failedNames.add(target.name());
            } else {
                notYetEmailed++;
            }
        }
        EmailStatus email = new EmailStatus(sent, failed, sending, unconfirmed, notYetEmailed,
            withoutEmailNames.size(), List.copyOf(withoutEmailNames), List.copyOf(failedNames),
            List.copyOf(unconfirmedNames));
        return new Audience(targets.size(), alreadyNotified, targets.size() - alreadyNotified,
            sent + sending + unconfirmed, failed + notYetEmailed, withoutEmailNames.size(), email);
    }

    /** Inserts one bell row per targeted employee who does not already have one. Never sends email. Kept for the
     * frontend bundle deployed before {@link #publishWithEmail}; the current UI does not call it. */
    @Transactional
    public PublishResult publish(UserPrincipal actor, Request request) {
        requireManager(actor);
        return publishBell(actor, ReleaseNoticePolicy.normalize(request));
    }

    /**
     * One press: the bell rows (committed first, on their own), then the email to everyone targeted who has not
     * received it (second transaction). Re-pressing it never re-mails anyone SENT, SENDING or UNCONFIRMED; it does
     * retry FAILED ones and reaches people who joined or gained an address since. An email-stage error does not
     * undo or hide the bell rows: the result carries {@code email == null} and a Thai {@code emailError}.
     * {@code resendUnconfirmed} is ignored here: re-sending an unconfirmed mail is only ever the explicit retry.
     */
    public PublishWithEmailResult publishWithEmail(UserPrincipal actor, Request request) {
        requireManager(actor);
        Request release = ReleaseNoticePolicy.normalize(request);
        PublishResult bell = transaction.execute(status -> publishBell(actor, release));
        EmailResult email = null;
        String emailError = null;
        try {
            email = handOff(actor, release, transaction.execute(status -> prepareEmail(actor, release, false)));
            emailError = email.error();
        } catch (RuntimeException exception) {
            log.error("Release notice published but its email stage failed: release={} error={}",
                release.releaseId(), exception.getMessage(), exception);
            emailError = "เริ่มส่งอีเมลไม่สำเร็จ";
        }
        return new PublishWithEmailResult(bell.targeted(), bell.inserted(), bell.alreadyNotified(), email, emailError);
    }

    /** The retry / "not yet emailed" action: emails each targeted employee with an address whose latest attempt is
     * absent or FAILED, plus — only when {@code resendUnconfirmed} is true — those UNCONFIRMED. Never publishes. */
    public EmailResult email(UserPrincipal actor, Request request) {
        requireManager(actor);
        Request release = ReleaseNoticePolicy.normalize(request);
        boolean resend = Boolean.TRUE.equals(release.resendUnconfirmed());
        return handOff(actor, release, transaction.execute(status -> prepareEmail(actor, release, resend)));
    }

    private PublishResult publishBell(UserPrincipal actor, Request release) {
        releaseNotices.lock("publish:" + release.releaseId());
        String link = ReleaseNoticePolicy.link(release.releaseId());
        List<Target> targets = targets(release);
        Set<Long> notified = releaseNotices.notifiedEmployeeIds(link);

        String title = ReleaseNoticePolicy.bellTitle(release.title());
        int inserted = 0;
        for (Target target : targets) {
            if (notified.contains(target.employeeId())) {
                continue;
            }
            notifications.insert(target.employeeId(), ReleaseNoticePolicy.NOTIFICATION_TYPE, title,
                ReleaseNoticePolicy.bellMessage(release.title(), target.titles()), link);
            inserted++;
        }
        if (inserted > 0) {
            audit.record(actor, "PUBLISH_RELEASE_NOTE", "release_note", null, null,
                Map.of("releaseId", release.releaseId(), "inserted", inserted));
        }
        return new PublishResult(targets.size(), inserted, targets.size() - inserted);
    }

    /** Inside the email transaction, under the per-release lock: decides who is due and writes their attempt rows.
     * Sends nothing; the caller hands the batch over only after this transaction has committed. */
    private PreparedEmail prepareEmail(UserPrincipal actor, Request release, boolean resendUnconfirmed) {
        releaseNotices.lock("email:" + release.releaseId());
        List<Target> targets = targets(release);
        Map<Long, EmailState> states = releaseNotices.emailStates(release.releaseId(), inFlightWindow);

        List<ReleaseNoticeDelivery.Delivery> deliveries = new ArrayList<>();
        int alreadyEmailed = 0;
        int withoutEmail = 0;
        for (Target target : targets) {
            EmailState state = states.get(target.employeeId());
            boolean due = state == null || state == EmailState.FAILED
                || (resendUnconfirmed && state == EmailState.UNCONFIRMED);
            if (!due) {
                alreadyEmailed++;
                continue;
            }
            Optional<EmailRecipient> recipient = addressOf(target.employeeId());
            if (recipient.isEmpty()) {
                // Not recorded: once an address is on file, the next call reaches them.
                withoutEmail++;
                continue;
            }
            String attempt = UUID.randomUUID().toString();
            audit.record(actor, ReleaseNoticeRepository.EMAILED_ACTION, ReleaseNoticeRepository.EMAILED_ENTITY,
                target.employeeId(), null, Map.of("releaseId", release.releaseId(), "attempt", attempt));
            deliveries.add(new ReleaseNoticeDelivery.Delivery(target.employeeId(), attempt, recipient.get(),
                ReleaseNoticePolicy.email(release, target.changes())));
        }
        return new PreparedEmail(new EmailResult(targets.size(), deliveries.size(), alreadyEmailed, withoutEmail, 0,
            null), List.copyOf(deliveries));
    }

    /**
     * After the attempt rows have committed: hands the batch to the async sender. If the hand-off is refused (the
     * executor is shutting down or full) or throws, nothing was sent, so every attempt of the batch gets a FAILED
     * outcome ("could not queue") — retryable by the plain retry, no opt-in — and the result says so with its
     * count instead of throwing a 500 after rows have committed.
     */
    private EmailResult handOff(UserPrincipal actor, Request release, PreparedEmail prepared) {
        if (prepared.batch().isEmpty()) {
            return prepared.result();
        }
        try {
            delivery.deliver(actor, release.releaseId(), prepared.batch());
            return prepared.result();
        } catch (RuntimeException exception) {
            log.error("Release notice email batch could not be queued: release={} people={} error={}",
                release.releaseId(), prepared.batch().size(), exception.getMessage());
            delivery.recordFailed(actor, release.releaseId(), prepared.batch(), ReleaseNoticeDelivery.NOT_QUEUED);
            EmailResult result = prepared.result();
            return new EmailResult(result.targeted(), 0, result.alreadyEmailed(), result.withoutEmail(),
                prepared.batch().size(), "ระบบส่งอีเมลไม่รับงานในตอนนี้ ยังไม่มีใครได้รับอีเมลรอบนี้");
        }
    }

    private record PreparedEmail(EmailResult result, List<ReleaseNoticeDelivery.Delivery> batch) {
    }

    private List<Target> targets(Request release) {
        return employees.findAllActive().stream()
            .map(employee -> new Target(employee.employeeId(), employee.name(),
                ReleaseNoticePolicy.changesFor(DivisionAccessPolicy.roleFor(employee), release.changes())))
            .filter(target -> !target.changes().isEmpty())
            .toList();
    }

    /** The employee's mail destination when they have an address on file; empty otherwise. */
    private Optional<EmailRecipient> addressOf(long employeeId) {
        return notifications.findEmployeeRecipient(employeeId)
            .filter(recipient -> recipient.email() != null && !recipient.email().isBlank());
    }

    private static void requireManager(UserPrincipal actor) {
        if (actor == null || !ReleaseNoticePolicy.canManage(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private record Target(long employeeId, String name, List<Change> changes) {
        List<String> titles() {
            return changes.stream().map(Change::title).toList();
        }
    }
}
