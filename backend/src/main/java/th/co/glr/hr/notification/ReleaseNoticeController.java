package th.co.glr.hr.notification;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Audience;
import th.co.glr.hr.notification.ReleaseNoticeDtos.EmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;

/**
 * "มีอะไรใหม่" release notice. All three endpoints are CEO/HR-only; the gate lives in
 * {@link ReleaseNoticeService} (not here) so the real-DB integration test runs the same check.
 *
 * <ul>
 *   <li>{@code POST /audience} — counts only, writes nothing (the admin confirm step).</li>
 *   <li>{@code POST /publish} — one bell row per targeted employee; idempotent; sends NO email.</li>
 *   <li>{@code POST /email} — the separate, explicit email send; idempotent per employee and release.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/release-notes")
public class ReleaseNoticeController {
    private final ReleaseNoticeService releaseNotices;
    private final SessionContext sessions;

    public ReleaseNoticeController(ReleaseNoticeService releaseNotices, SessionContext sessions) {
        this.releaseNotices = releaseNotices;
        this.sessions = sessions;
    }

    @PostMapping("/audience")
    Audience audience(@RequestBody Request request, HttpSession session) {
        return releaseNotices.audience(sessions.requireUser(session), request);
    }

    @PostMapping("/publish")
    PublishResult publish(@RequestBody Request request, HttpSession session) {
        return releaseNotices.publish(sessions.requireUser(session), request);
    }

    @PostMapping("/email")
    EmailResult email(@RequestBody Request request, HttpSession session) {
        return releaseNotices.email(sessions.requireUser(session), request);
    }
}
