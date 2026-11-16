package th.co.glr.hr.notification;

import java.util.List;

/**
 * Wire shapes for the "มีอะไรใหม่" release notice (ReleaseNoticeController).
 *
 * <p>The request carries the release's own content summary rather than the backend keeping a registry of
 * releases: the full notice (steps, screenshots, help text) lives in the frontend's content module
 * ({@code frontend/src/features/whatsNew/releases/}), and the backend needs only the release id (for the link
 * and the idempotency key), its title, and which change titles each role should hear about. A server-side
 * registry would be a second copy of that content to keep in step.
 */
public final class ReleaseNoticeDtos {

    private ReleaseNoticeDtos() {
    }

    /** One change in a release, reduced to what the bell row and the email say: its title and its audience. */
    public record Change(String title, List<String> roles) {
    }

    public record Request(String releaseId, String title, List<Change> changes) {
    }

    /** What publishing / emailing would do right now — the confirm step's numbers. {@code withoutEmail} counts
     * targeted employees not yet emailed who have no address on file (they are skipped, not recorded). */
    public record Audience(int targeted, int alreadyNotified, int pendingNotification,
                           int alreadyEmailed, int pendingEmail, int withoutEmail) {
    }

    public record PublishResult(int targeted, int inserted, int alreadyNotified) {
    }

    public record EmailResult(int targeted, int sent, int alreadyEmailed, int withoutEmail) {
    }
}
