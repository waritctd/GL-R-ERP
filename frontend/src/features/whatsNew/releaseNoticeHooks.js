import { useLayoutEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { notificationsQueryOptions } from '../../components/common/NotificationBell.jsx';

/** hr.notification.type of the bell row ReleaseNoticeService publishes (ReleaseNoticePolicy.NOTIFICATION_TYPE). */
export const RELEASE_NOTE_TYPE = 'RELEASE_NOTE';

export function releaseLink(releaseId) {
  return `/whats-new/${releaseId}`;
}

/** The release id a bell row's link points at, or null when it is not a release link. */
export function releaseIdOfLink(link) {
  const prefix = releaseLink('');
  return typeof link === 'string' && link.startsWith(prefix) ? link.slice(prefix.length) : null;
}

/** The viewer's notifications, from the SAME cache entry the bell polls: no second request, and a mark-read here
 * updates the bell's badge too. */
export function useNotifications() {
  const query = useQuery(notificationsQueryOptions);
  return query.data ?? [];
}

/** Unread release-notice rows; narrowed to one release when `releaseId` is given. */
export function unreadReleaseNotices(notifications, releaseId) {
  return notifications.filter((n) => n.type === RELEASE_NOTE_TYPE && !n.read
    && (releaseId == null || n.link === releaseLink(releaseId)));
}

/**
 * Entrance reveal for a card: returns [ref, revealed]. `revealed` turns true when the element is on screen, and the
 * caller only THEN adds a `motion-safe:` entrance animation. Before that the element carries no motion class at
 * all, so it rests fully visible when IntersectionObserver is missing, blocked, or never fires.
 *
 * An element already on screen at mount is revealed in a layout effect (before first paint), so its animation
 * starts from the first frame instead of flashing visible → hidden → visible. Anything below the fold is observed
 * with a bottom margin, so it starts rising just before it scrolls in.
 */
export function useReveal() {
  const ref = useRef(null);
  const [revealed, setRevealed] = useState(false);

  useLayoutEffect(() => {
    const node = ref.current;
    if (!node || revealed || typeof IntersectionObserver === 'undefined') return undefined;
    const rect = node.getBoundingClientRect();
    const viewport = window.innerHeight || document.documentElement.clientHeight || 0;
    if (rect.height > 0 && rect.top < viewport && rect.bottom > 0) {
      setRevealed(true);
      return undefined;
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setRevealed(true);
        observer.disconnect();
      }
    }, { rootMargin: '0px 0px 15% 0px' });
    observer.observe(node);
    return () => observer.disconnect();
  }, [revealed]);

  return [ref, revealed];
}
