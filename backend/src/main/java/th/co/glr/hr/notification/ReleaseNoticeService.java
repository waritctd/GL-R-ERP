package th.co.glr.hr.notification;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Audience;
import th.co.glr.hr.notification.ReleaseNoticeDtos.EmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;

/**
 * Publishes a "มีอะไรใหม่" release notice to the bell, and — as a SEPARATE, explicit action — emails it.
 *
 * <p><b>Who is targeted.</b> Every ACTIVE employee whose role, derived exactly as at login by
 * {@link DivisionAccessPolicy#roleFor}, appears in at least one change's audience. The role is computed in Java
 * over {@link EmployeeAuthRepository#findAllActive()} rather than re-expressed as SQL, so this cannot drift from
 * the role the employee actually signs in with (including the can_create_quotation → sales rule).
 *
 * <p><b>Gate.</b> CEO and HR only ({@link ReleaseNoticePolicy#canManage}), enforced HERE, in the service, so the
 * real-DB integration test exercises the same check production runs. A refused caller gets 403 before anything
 * is read or written.
 *
 * <p><b>Idempotency, without a migration.</b> Publish skips employees who already have this release's
 * {@code RELEASE_NOTE} row; email skips employees with a {@code RELEASE_NOTE_EMAILED} audit row for this release.
 * Each action takes a per-release advisory lock first, so two concurrent clicks serialize instead of both seeing
 * "not yet done". Email is at-most-once: the audit row commits with the transaction and the mail is handed to
 * the (async, failure-logging) mail service only after commit, so a rolled-back call sends nothing and a failed
 * send is never retried automatically.
 */
@Service
public class ReleaseNoticeService {
    private final EmployeeAuthRepository employees;
    private final NotificationRepository notifications;
    private final ReleaseNoticeRepository releaseNotices;
    private final NotificationEmailService emailService;
    private final AuditService audit;

    public ReleaseNoticeService(EmployeeAuthRepository employees, NotificationRepository notifications,
                                ReleaseNoticeRepository releaseNotices, NotificationEmailService emailService,
                                AuditService audit) {
        this.employees = employees;
        this.notifications = notifications;
        this.releaseNotices = releaseNotices;
        this.emailService = emailService;
        this.audit = audit;
    }

    /** What publish and email would each still do — the admin control's confirm-step numbers. Writes nothing. */
    @Transactional(readOnly = true)
    public Audience audience(UserPrincipal actor, Request request) {
        requireManager(actor);
        Request release = ReleaseNoticePolicy.normalize(request);
        List<Target> targets = targets(release);
        Set<Long> notified = releaseNotices.notifiedEmployeeIds(ReleaseNoticePolicy.link(release.releaseId()));
        Set<Long> emailed = releaseNotices.emailedEmployeeIds(release.releaseId());

        int alreadyNotified = 0;
        int alreadyEmailed = 0;
        int pendingEmail = 0;
        int withoutEmail = 0;
        for (Target target : targets) {
            if (notified.contains(target.employeeId())) {
                alreadyNotified++;
            }
            if (emailed.contains(target.employeeId())) {
                alreadyEmailed++;
            } else if (addressOf(target.employeeId()).isPresent()) {
                pendingEmail++;
            } else {
                withoutEmail++;
            }
        }
        return new Audience(targets.size(), alreadyNotified, targets.size() - alreadyNotified,
            alreadyEmailed, pendingEmail, withoutEmail);
    }

    /** Inserts one bell row per targeted employee who does not already have one. Never sends email. */
    @Transactional
    public PublishResult publish(UserPrincipal actor, Request request) {
        requireManager(actor);
        Request release = ReleaseNoticePolicy.normalize(request);
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

    /** Emails each targeted employee who has an address and has not been emailed this release. Separate from
     * {@link #publish} on purpose: publishing must never send email. */
    @Transactional
    public EmailResult email(UserPrincipal actor, Request request) {
        requireManager(actor);
        Request release = ReleaseNoticePolicy.normalize(request);
        releaseNotices.lock("email:" + release.releaseId());
        String link = ReleaseNoticePolicy.link(release.releaseId());
        String subject = ReleaseNoticePolicy.emailSubject(release.title());
        List<Target> targets = targets(release);
        Set<Long> emailed = releaseNotices.emailedEmployeeIds(release.releaseId());

        int sent = 0;
        int alreadyEmailed = 0;
        int withoutEmail = 0;
        for (Target target : targets) {
            if (emailed.contains(target.employeeId())) {
                alreadyEmailed++;
                continue;
            }
            Optional<EmailRecipient> recipient = addressOf(target.employeeId());
            if (recipient.isEmpty()) {
                // Not recorded: once an address is on file, the next call reaches them.
                withoutEmail++;
                continue;
            }
            audit.record(actor, ReleaseNoticeRepository.EMAILED_ACTION, ReleaseNoticeRepository.EMAILED_ENTITY,
                target.employeeId(), null, Map.of("releaseId", release.releaseId()));
            String body = ReleaseNoticePolicy.emailBody(target.titles());
            long employeeId = target.employeeId();
            EmailRecipient to = recipient.get();
            AfterCommit.run(() -> emailService.send(employeeId, to.email(), to.name(), subject, body, link));
            sent++;
        }
        return new EmailResult(targets.size(), sent, alreadyEmailed, withoutEmail);
    }

    private List<Target> targets(Request release) {
        return employees.findAllActive().stream()
            .map(employee -> new Target(employee.employeeId(),
                ReleaseNoticePolicy.titlesFor(DivisionAccessPolicy.roleFor(employee), release.changes())))
            .filter(target -> !target.titles().isEmpty())
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

    private record Target(long employeeId, List<String> titles) {
    }
}
