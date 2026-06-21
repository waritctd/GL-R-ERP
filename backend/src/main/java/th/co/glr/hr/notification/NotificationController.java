package th.co.glr.hr.notification;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationRepository notifications;
    private final SessionContext sessions;

    public NotificationController(NotificationRepository notifications, SessionContext sessions) {
        this.notifications = notifications;
        this.sessions = sessions;
    }

    @GetMapping
    List<NotificationDto> list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return notifications.findByEmployeeId(user.id());
    }

    @PatchMapping("/{id}/read")
    ResponseEntity<Void> markRead(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        notifications.markRead(id, user.id());
        return ResponseEntity.noContent().build();
    }
}
