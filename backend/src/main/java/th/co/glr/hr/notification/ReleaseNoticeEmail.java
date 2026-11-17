package th.co.glr.hr.notification;

import java.util.List;

/**
 * One reader's release-notice email, as Thai copy decided by {@link ReleaseNoticePolicy#email} and rendered (HTML
 * escaped, plain-text alternative) by {@link NotificationEmailService#sendReleaseNotice}. Every string here is
 * plain text; none of it is markup. {@code moreLine} is null when every item is listed. {@code link} is the
 * portal-relative path ({@code /whats-new/<releaseId>}); the renderer prefixes {@code app.mail.app-base-url}.
 */
public record ReleaseNoticeEmail(String subject, String intro, List<Item> items, String moreLine, String outro,
                                 String ctaLabel, String link) {

    public ReleaseNoticeEmail {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** One entry: its title and its one-sentence reason (null when the release gave none). */
    public record Item(String title, String why) {
    }
}
