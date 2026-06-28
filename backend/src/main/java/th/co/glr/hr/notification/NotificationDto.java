package th.co.glr.hr.notification;

import java.time.Instant;

public record NotificationDto(
    long id,
    long employeeId,
    Long ticketId,
    String ticketCode,
    String type,
    String message,
    boolean read,
    Instant createdAt
) {}
