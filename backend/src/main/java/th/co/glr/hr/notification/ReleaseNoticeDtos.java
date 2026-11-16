package th.co.glr.hr.notification;

import java.util.List;

/**
 * Wire shapes for the "มีอะไรใหม่" release notice (ReleaseNoticeController).
 *
 * <p>The request carries the release's own content summary rather than the backend keeping a registry of
 * releases: the full notice (steps, screenshots, help text) lives in the frontend's content module
 * ({@code frontend/src/features/whatsNew/releases/}), and the backend needs only the release id (for the link
 * and the idempotency key), its title and date, and which change titles (each with its one-sentence "why") each
 * role should hear about. {@code publishPayload} in {@code audiences.js} builds it from the release file with the
 * same role filter the page uses. A server-side registry would be a second copy of that content to keep in step.
 */
public final class ReleaseNoticeDtos {

    private ReleaseNoticeDtos() {
    }

    /**
     * One change in a release, reduced to what the bell row and the email say: its title, its audience, and the
     * optional one-sentence reason shown under the title in the email ({@code why}, max 300 characters, added
     * 2026-11-17; null or blank = title only).
     */
    public record Change(String title, List<String> roles, String why) {
    }

    /**
     * {@code date} (ISO yyyy-MM-dd, optional, added 2026-11-17) is the release date the email subject names.
     * {@code resendUnconfirmed} (optional, added 2026-11-17) is honoured ONLY by {@code POST /email}: true also
     * re-sends to people whose earlier send has no recorded outcome after the in-flight window (they may get a
     * second copy, which the confirm says). Every other endpoint ignores it.
     */
    public record Request(String releaseId, String title, List<Change> changes, String date,
                          Boolean resendUnconfirmed) {
    }

    /**
     * What publishing / emailing would do right now — the confirm step's numbers. {@code alreadyEmailed} counts
     * the targets no email action will send to again (sent, still sending, or unconfirmed); {@code pendingEmail}
     * the targets the next send reaches (not yet emailed or failed, with an address); {@code withoutEmail} the
     * targets who would be mailed but have no address on file. {@code email} is the per-person truth.
     */
    public record Audience(int targeted, int alreadyNotified, int pendingNotification,
                           int alreadyEmailed, int pendingEmail, int withoutEmail, EmailStatus email) {
    }

    /**
     * Every targeted person in exactly one bucket (they sum to {@code targeted}):
     * <ul>
     *   <li>{@code sent}: the provider accepted the mail.</li>
     *   <li>{@code failed}: the provider refused it or threw; the next send retries them (address on file).</li>
     *   <li>{@code sending}: attempted, no outcome yet, younger than the in-flight window.</li>
     *   <li>{@code unconfirmed}: attempted, no outcome, older than the window (crash, lost outcome, or a row from
     *       before outcomes were recorded). Never counted as sent, never re-sent without an explicit opt-in.</li>
     *   <li>{@code notYetEmailed}: never attempted (or reset), address on file.</li>
     *   <li>{@code withoutEmail}: would be mailed but has no address on file.</li>
     * </ul>
     */
    public record EmailStatus(int sent, int failed, int sending, int unconfirmed, int notYetEmailed,
                              int withoutEmail, List<String> withoutEmailNames, List<String> failedNames,
                              List<String> unconfirmedNames) {
    }

    public record PublishResult(int targeted, int inserted, int alreadyNotified) {
    }

    /** {@code queued}: mails handed to the sender by this call. Their outcome arrives later (Audience.email). */
    public record EmailResult(int targeted, int queued, int alreadyEmailed, int withoutEmail) {
    }

    /** Publish + email in one press. {@code email} is null and {@code emailError} set when the email stage failed;
     * the bell rows are committed either way. */
    public record PublishWithEmailResult(int targeted, int inserted, int alreadyNotified, EmailResult email,
                                         String emailError) {
    }
}
