package th.co.glr.hr.notification;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

// Notifications are a side effect of an already-audited business action (leave submit, ticket approve, etc.) —
// they intentionally carry NO AuditService calls. It would also flood the audit log (one row per notification,
// incl. "mark all read" fan-out) with low-value entries.
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final NotificationEmailService emailService;

    public NotificationService(NotificationRepository notifications,
                               NotificationEmailService emailService) {
        this.notifications = notifications;
        this.emailService = emailService;
    }

    public List<NotificationDto> list(long employeeId) {
        return notifications.findByEmployeeId(employeeId);
    }

    @Transactional
    public NotificationDto notify(long employeeId, String type, String subject, String body,
                                  String link, boolean sendEmail) {
        return notifyWithEmailBody(employeeId, type, subject, body, link, sendEmail ? body : null);
    }

    /** The bell shows {@code body}; when {@code emailBody} is non-null it is also emailed, so the email can say more. */
    @Transactional
    public NotificationDto notifyWithEmailBody(long employeeId, String type, String subject, String body,
                                               String link, String emailBody) {
        return notifyWithEmailBody(employeeId, type, subject, body, link, emailBody, List.of()).dto();
    }

    /** Like {@link #notify} (email = body) and BCCs {@code bcc} on the email; {@code emailQueued} says whether an
     * email was queued for this recipient, so a caller can BCC only the first one that really goes out. */
    @Transactional
    public Sent notify(long employeeId, String type, String subject, String body, String link, List<String> bcc) {
        return notifyWithEmailBody(employeeId, type, subject, body, link, body, bcc);
    }

    public record Sent(NotificationDto dto, boolean emailQueued) {}

    private Sent notifyWithEmailBody(long employeeId, String type, String subject, String body,
                                     String link, String emailBody, List<String> bcc) {
        validate(employeeId, type, subject, body);
        boolean sendEmail = emailBody != null && !emailBody.isBlank();
        String cleanLink = trimmedOrNull(link);
        long id = notifications.insert(employeeId, type.trim(), subject.trim(), body.trim(), cleanLink);
        NotificationDto created = notifications.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Notification was not saved"));
        boolean queued = false;
        if (sendEmail) {
            // Deliberately NOT gated on the employee having an address: app.mail.override-to can still redirect this email
            // to a test inbox even when there is no address on file, and only NotificationEmailService (which owns that
            // config) knows whether an override is configured.
            var recipient = notifications.findEmployeeRecipient(employeeId);
            if (recipient.isPresent()) {
                var r = recipient.get();
                sendEmailAfterCommit(employeeId, r.email(), r.name(), subject.trim(), emailBody.trim(), cleanLink, bcc);
                queued = r.email() != null && !r.email().isBlank();
            } else {
                log.info("Notification email skipped: employee={} not found", employeeId);
            }
        }
        return new Sent(created, queued);
    }

    @Transactional
    public void markRead(long notificationId, UserPrincipal actor) {
        int updated = notifications.markRead(notificationId, actor.id());
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบการแจ้งเตือนนี้");
        }
    }

    // Behaviour unchanged — the deferral moved verbatim into AfterCommit so the sales-pipeline mail router shares
    // one implementation with this path instead of copying it.
    private void sendEmailAfterCommit(long employeeId, String to, String recipientName, String subject, String body,
                                      String link, List<String> bcc) {
        AfterCommit.run(() -> emailService.send(employeeId, to, recipientName, subject, body, link, bcc));
    }

    private void validate(long employeeId, String type, String subject, String body) {
        if (employeeId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุพนักงาน");
        }
        if (isBlank(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุประเภทการแจ้งเตือน");
        }
        if (isBlank(subject)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุหัวข้อการแจ้งเตือน");
        }
        if (isBlank(body)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุข้อความแจ้งเตือน");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
