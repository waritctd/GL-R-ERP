package th.co.glr.hr.notification;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final NotificationEmailService emailService;
    private final AuditService auditService;

    public NotificationService(NotificationRepository notifications,
                               NotificationEmailService emailService,
                               AuditService auditService) {
        this.notifications = notifications;
        this.emailService = emailService;
        this.auditService = auditService;
    }

    public List<NotificationDto> list(long employeeId) {
        return notifications.findByEmployeeId(employeeId);
    }

    @Transactional
    public NotificationDto notify(long employeeId, String type, String subject, String body,
                                  String link, boolean sendEmail) {
        validate(employeeId, type, subject, body);
        long id = notifications.insert(employeeId, type.trim(), subject.trim(), body.trim(), trimmedOrNull(link));
        NotificationDto created = notifications.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Notification was not saved"));
        auditService.record(null, "CREATE_NOTIFICATION", "notification", id, null, created);
        if (sendEmail) {
            notifications.findEmployeeEmail(employeeId)
                .ifPresentOrElse(
                    to -> sendEmailAfterCommit(employeeId, to, subject.trim(), body.trim()),
                    () -> log.info("Notification email skipped: employee={} has no email", employeeId));
        }
        return created;
    }

    @Transactional
    public void markRead(long notificationId, UserPrincipal actor) {
        int updated = notifications.markRead(notificationId, actor.id());
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        auditService.record(actor, "MARK_NOTIFICATION_READ", "notification", notificationId,
            null, Map.of("read", true));
    }

    private void sendEmailAfterCommit(long employeeId, String to, String subject, String body) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            emailService.send(employeeId, to, subject, body);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailService.send(employeeId, to, subject, body);
            }
        });
    }

    private void validate(long employeeId, String type, String subject, String body) {
        if (employeeId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Employee is required");
        }
        if (isBlank(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Notification type is required");
        }
        if (isBlank(subject)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Notification subject is required");
        }
        if (isBlank(body)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Notification message is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
