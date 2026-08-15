package th.co.glr.hr.notification;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

// Notifications are a side effect of an already-audited business action (leave submit, ticket
// approve, etc.) — they intentionally carry NO AuditService calls. AuditService.record()
// participates in the caller's transaction and rolls it back on failure; that guarantee belongs to
// the business mutation, not to a best-effort in-app/email ping. It would also flood the audit log
// (one row per notification, incl. "mark all read" fan-out) with low-value entries.
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
        validate(employeeId, type, subject, body);
        String cleanLink = trimmedOrNull(link);
        long id = notifications.insert(employeeId, type.trim(), subject.trim(), body.trim(), cleanLink);
        NotificationDto created = notifications.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Notification was not saved"));
        if (sendEmail) {
            // Deliberately NOT gated on the employee having an address: app.mail.override-to can
            // still redirect this email to a test inbox even when there is no address on file, and
            // only NotificationEmailService (which owns that config) knows whether an override is
            // configured. Gating on the address here, the way findEmployeeEmail() used to, made the
            // override unreachable for exactly the employees it exists to rescue - do not
            // reintroduce that gate. The only thing checked here is whether the employee row exists.
            notifications.findEmployeeRecipient(employeeId)
                .ifPresentOrElse(
                    recipient -> sendEmailAfterCommit(
                        employeeId, recipient.email(), recipient.name(), subject.trim(), body.trim(), cleanLink),
                    () -> log.info("Notification email skipped: employee={} not found", employeeId));
        }
        return created;
    }

    @Transactional
    public void markRead(long notificationId, UserPrincipal actor) {
        int updated = notifications.markRead(notificationId, actor.id());
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบการแจ้งเตือนนี้");
        }
    }

    // Behaviour unchanged — the deferral moved verbatim into AfterCommit so the sales-pipeline mail
    // router shares one implementation with this path instead of copying it.
    private void sendEmailAfterCommit(long employeeId, String to, String recipientName, String subject, String body,
                                      String link) {
        AfterCommit.run(() -> emailService.send(employeeId, to, recipientName, subject, body, link));
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
