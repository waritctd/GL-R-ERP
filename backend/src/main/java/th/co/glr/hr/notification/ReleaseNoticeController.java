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
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishWithEmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;

/**
 * "มีอะไรใหม่" release notice. All four endpoints are CEO/HR-only; the gate lives in
 * {@link ReleaseNoticeService} (not here) so the real-DB integration test runs the same check.
 *
 * <ul>
 *   <li>{@code POST /audience} — counts and per-person email status, writes nothing (status + confirm step).</li>
 *   <li>{@code POST /publish-with-email} — what the admin button does (2026-11-17): bell rows, then the email to
 *       everyone not yet emailed; idempotent; an email failure never undoes the bell.</li>
 *   <li>{@code POST /email} — the retry: emails whoever has not received it (never re-mails a sent one);
 *       {@code resendUnconfirmed: true} also re-sends mails with no recorded outcome.</li>
 *   <li>{@code POST /publish} — bell only, NO email. Not called by the current UI; kept so the bundle deployed
 *       before publish-with-email keeps doing exactly what its confirm says ("ไม่ส่งอีเมล"). Remove next release.</li>
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

    @PostMapping("/publish-with-email")
    PublishWithEmailResult publishWithEmail(@RequestBody Request request, HttpSession session) {
        return releaseNotices.publishWithEmail(sessions.requireUser(session), request);
    }

    @PostMapping("/email")
    EmailResult email(@RequestBody Request request, HttpSession session) {
        return releaseNotices.email(sessions.requireUser(session), request);
    }
}
