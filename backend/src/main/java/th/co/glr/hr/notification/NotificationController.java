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
    private final NotificationService notifications;
    private final SessionContext sessions;

    public NotificationController(NotificationService notifications, SessionContext sessions) {
        this.notifications = notifications;
        this.sessions = sessions;
    }

    @GetMapping
    List<NotificationDto> list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return notifications.list(user.id());
    }

    @PatchMapping("/{id}/read")
    ResponseEntity<Void> markRead(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        notifications.markRead(id, user);
        return ResponseEntity.noContent().build();
    }
}
