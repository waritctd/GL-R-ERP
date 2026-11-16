import { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { cn } from '../../utils/cn.js';
import { ROLE_LABEL, entriesForViewer } from './audiences.js';
import { releaseLink, unreadReleaseNotices, useNotifications } from './releaseNoticeHooks.js';
import { findRelease, latestRelease } from './releases/index.js';

const PANEL_ID = 'whats-new-panel';

/** The release an unread bell row points at, when that release is one this build knows; else the latest. */
function releaseToShow(unread) {
  for (const notice of unread) {
    const id = notice.link?.split('/')[2];
    const known = findRelease(id);
    if (known) return known;
  }
  return latestRelease();
}

/**
 * The "มีอะไรใหม่" pill beside the notification bell. A red dot shows while the viewer has an unread release notice
 * (a RELEASE_NOTE bell row); the dot clears when the release page is opened, which marks that row read. Clicking
 * opens a short list of the viewer's own changes with one way through to the page. Controlled like the bell
 * (`open`/`onOpenChange`) so AppShell keeps at most one topbar popover open.
 */
export function WhatsNewBubble({ user, onNavigate, open: controlledOpen, onOpenChange }) {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  const open = controlledOpen ?? uncontrolledOpen;
  const rootRef = useRef(null);
  const triggerRef = useRef(null);
  const notifications = useNotifications();
  const unread = unreadReleaseNotices(notifications);
  const release = releaseToShow(unread);

  const setOpen = useCallback((next) => {
    if (controlledOpen === undefined) setUncontrolledOpen(next);
    onOpenChange?.(next);
  }, [controlledOpen, onOpenChange]);

  const closeAndRestore = useCallback(() => {
    setOpen(false);
    triggerRef.current?.focus();
  }, [setOpen]);

  useEffect(() => {
    if (!open) return undefined;
    function handlePointer(event) {
      if (rootRef.current && !rootRef.current.contains(event.target)) setOpen(false);
    }
    function handleKeyDown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeAndRestore();
      }
    }
    document.addEventListener('mousedown', handlePointer);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointer);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [closeAndRestore, open, setOpen]);

  if (!release) return null;
  const entries = entriesForViewer(release, user);
  const hasUnread = unread.length > 0;

  function goToRelease() {
    setOpen(false);
    onNavigate(releaseLink(release.id));
  }

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setOpen(!open)}
        aria-label={hasUnread ? 'มีอะไรใหม่ (มีอัปเดตที่ยังไม่ได้อ่าน)' : 'มีอะไรใหม่'}
        aria-haspopup="dialog"
        aria-controls={open ? PANEL_ID : undefined}
        aria-expanded={open}
        className={cn(
          'relative inline-flex min-h-[40px] items-center gap-1.5 rounded-full border border-solid border-primary-bg bg-primary-bg px-3 text-sm font-bold text-primary',
          'cursor-pointer transition-[background-color,border-color,transform] duration-150 ease-out',
          'hover:border-primary/25 hover:bg-primary/15 active:scale-[0.97]',
          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary',
          'motion-reduce:transition-none motion-reduce:active:scale-100',
          'mobile:size-11 mobile:justify-center mobile:px-0',
        )}
      >
        <Icon name="sparkles" size={17} />
        <span className="mobile:hidden">มีอะไรใหม่</span>
        {hasUnread ? (
          <span
            data-testid="whats-new-unread-dot"
            aria-hidden="true"
            className="absolute -right-0.5 -top-0.5 size-2.5 rounded-full bg-danger ring-2 ring-surface motion-safe:animate-wn-pulse-once"
          />
        ) : null}
      </button>

      {open ? (
        <div
          id={PANEL_ID}
          role="dialog"
          aria-label="มีอะไรใหม่"
          className={cn(
            'absolute right-0 top-[calc(100%+12px)] z-[200] w-[min(360px,calc(100vw-32px))] overflow-hidden rounded-md border border-border-subtle bg-surface shadow-[0_18px_40px_rgba(15,23,42,0.18)]',
            'motion-safe:animate-wn-pop',
            'mobile:fixed mobile:left-4 mobile:right-4 mobile:top-[74px] mobile:w-auto',
          )}
        >
          <div className="border-b border-border px-4 pb-2.5 pt-3.5">
            <span className="block text-xs text-text-muted">อัปเดต {release.dateLabel}</span>
            <strong className="text-md text-text">สำหรับ{ROLE_LABEL[user?.role] ?? 'คุณ'}</strong>
          </div>
          {entries.length === 0 ? (
            // Same words as the page's empty state: a release can have nothing for this role (2026-11-17 for hr).
            <p className="m-0 px-4 py-3 text-sm text-text-muted">ฉบับนี้ไม่มีเรื่องเฉพาะแผนกของคุณ</p>
          ) : (
            <ul className="m-0 grid max-h-[50dvh] list-none gap-0.5 overflow-y-auto p-2">
              {entries.map((entry) => (
                <li key={entry.id} className="flex items-start gap-2.5 rounded-md p-2">
                  <span className="grid size-[30px] flex-none place-items-center rounded-md bg-primary-bg text-primary" aria-hidden="true">
                    <Icon name={entry.kind === 'new' ? 'plus' : 'refresh'} size={15} />
                  </span>
                  <span className="min-w-0 text-sm leading-snug text-text">
                    {entry.title}
                    <span className="mt-0.5 block text-xs text-text-muted">{entry.page} · {entry.steps.length} ขั้นตอน · มีภาพประกอบ</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
          <div className="flex items-center justify-between gap-2 px-4 pb-3.5 pt-2">
            <Button type="button" variant="text" size="sm" className="text-text-muted" onClick={closeAndRestore}>ไว้ทีหลัง</Button>
            <Button type="button" onClick={goToRelease}>ดูรายละเอียดและวิธีใช้</Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
