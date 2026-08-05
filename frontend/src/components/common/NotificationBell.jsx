import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from './Button.jsx';
import { Icon } from './Icon.jsx';

const TYPE_ICON = {
  SUBMITTED:       { name: 'fileText', color: '#f59e0b' },
  PICKED_UP:       { name: 'users',    color: '#3b82f6' },
  PRICE_PROPOSED:  { name: 'clock',    color: '#f59e0b' },
  APPROVED:        { name: 'check',    color: '#22c55e' },
  // #ef4444 (measures 3.76:1, fails AA) replaced with DESIGN.md's
  // documented danger red #dc2626 (4.83:1). Kept as a literal hex rather
  // than var(--color-danger): icon.color is string-concatenated with an
  // alpha suffix below (`icon.color + '1a'`) to derive the tinted circle
  // background, which only works with a literal hex, not a CSS var().
  REJECTED:        { name: 'close',    color: '#dc2626' },
  QUOTATION_ISSUED:{ name: 'fileText', color: '#22c55e' },
  CLOSED:          { name: 'check',    color: '#94a3b8' },
  CANCELLED:       { name: 'close',    color: '#94a3b8' },
};

function timeAgo(iso) {
  const diff = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (diff < 60) return 'เมื่อกี้';
  if (diff < 3600) return `${Math.floor(diff / 60)} นาทีที่แล้ว`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} ชั่วโมงที่แล้ว`;
  return `${Math.floor(diff / 86400)} วันที่แล้ว`;
}

export function NotificationBell({
  onNavigate,
  open: controlledOpen,
  onOpenChange,
}) {
  const queryClient = useQueryClient();
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  const ref = useRef(null);
  const triggerRef = useRef(null);
  const panelRef = useRef(null);
  const itemRefs = useRef([]);
  const panelId = 'notification-panel';
  const open = controlledOpen ?? uncontrolledOpen;

  const setOpen = useCallback((nextOpen) => {
    const resolvedOpen = typeof nextOpen === 'function' ? nextOpen(open) : nextOpen;
    if (controlledOpen === undefined) setUncontrolledOpen(resolvedOpen);
    onOpenChange?.(resolvedOpen);
  }, [controlledOpen, onOpenChange, open]);

  const closeAndRestore = useCallback(() => {
    setOpen(false);
    triggerRef.current?.focus();
  }, [setOpen]);

  // Polling moves from a manual setInterval to refetchInterval — same 30s
  // cadence, but the cache now lives at queryKeys.notifications() so any
  // mutation elsewhere (e.g. a ticket action) that invalidates that key also
  // refreshes this bell immediately instead of waiting up to 30s.
  const notificationsQuery = useQuery({
    queryKey: queryKeys.notifications(),
    queryFn: () => api.notifications.list().then((res) => (Array.isArray(res.notifications) ? res.notifications : [])),
    refetchInterval: 30_000,
  });
  // On error, fall back to an empty list — matches the original load()'s
  // catch-and-clear behavior (this component has no showToast prop to report through).
  const items = notificationsQuery.data ?? [];

  const markReadMutation = useMutation({
    mutationFn: (id) => api.notifications.markRead(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.notifications() }),
  });

  const markAllReadMutation = useMutation({
    mutationFn: (ids) => Promise.all(ids.map((id) => api.notifications.markRead(id))),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
      closeAndRestore();
    },
  });

  useEffect(() => {
    if (!open) return;
    itemRefs.current.filter(Boolean)[0]?.focus();

    function handlePointer(e) {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    }

    function handleKeyDown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeAndRestore();
        return;
      }

      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;

      const items = itemRefs.current.filter(Boolean);
      if (items.length === 0) return;
      event.preventDefault();

      if (event.key === 'Home') {
        items[0].focus();
        return;
      }
      if (event.key === 'End') {
        items[items.length - 1].focus();
        return;
      }

      const currentIndex = items.indexOf(document.activeElement);
      const delta = event.key === 'ArrowDown' ? 1 : -1;
      const nextIndex = currentIndex === -1
        ? (delta === 1 ? 0 : items.length - 1)
        : (currentIndex + delta + items.length) % items.length;
      items[nextIndex].focus();
    }

    document.addEventListener('mousedown', handlePointer);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointer);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [closeAndRestore, open, setOpen]);

  function handleClick(item) {
    if (!item.read) {
      markReadMutation.mutate(item.id);
    }
    setOpen(false);
    if (item.link) onNavigate(item.link);
  }

  function markAllRead() {
    const unread = items.filter((n) => !n.read).map((n) => n.id);
    if (unread.length === 0) return;
    markAllReadMutation.mutate(unread);
  }

  const unreadCount = items.filter((n) => !n.read).length;
  const triggerLabel = unreadCount > 0
    ? `การแจ้งเตือน ${unreadCount} รายการใหม่`
    : 'การแจ้งเตือน';

  return (
    <div ref={ref} className="relative">
      <Button
        ref={triggerRef}
        type="button"
        variant="icon"
        className="relative"
        onClick={() => setOpen((v) => !v)}
        title="การแจ้งเตือน"
        aria-label={triggerLabel}
        aria-haspopup="dialog"
        aria-controls={open ? panelId : undefined}
        aria-expanded={open}
      >
        <Icon name="bell" />
        {unreadCount > 0 && (
          <span className="absolute top-[2px] right-[2px] min-w-4 h-4 rounded-md bg-danger !text-surface !text-[10px] !font-extrabold !flex items-center justify-center px-[3px] py-0 leading-none pointer-events-none">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </Button>

      {open && (
        <div
          id={panelId}
          ref={panelRef}
          role="dialog"
          aria-label="การแจ้งเตือน"
          className="absolute top-[calc(100%+12px)] right-0 z-[200] w-[min(340px,calc(100vw-32px))] max-w-[calc(100vw-32px)] overflow-hidden rounded-md border border-border-subtle bg-surface shadow-[0_8px_24px_rgba(0,0,0,0.12)] max-[720px]:fixed max-[720px]:top-[74px] max-[720px]:right-4 max-[720px]:left-4 max-[720px]:w-auto max-[720px]:max-w-none"
          tabIndex={-1}
        >
          <div className="flex items-center justify-between py-3 px-4 border-b border-border">
            <strong className="text-md">การแจ้งเตือน</strong>
            {unreadCount > 0 && (
              <button
                ref={(node) => { itemRefs.current[0] = node; }}
                type="button"
                onClick={markAllRead}
                className="!text-xs text-info bg-transparent border-0 cursor-pointer p-0"
              >
                อ่านทั้งหมด
              </button>
            )}
          </div>

          <div className="max-h-[360px] overflow-y-auto">
            {items.length === 0 ? (
              <div className="py-7 px-4 text-center text-text-muted text-sm">
                ไม่มีการแจ้งเตือน
              </div>
            ) : items.map((item, index) => {
              // Literal hex (not var(--color-text-muted)): same alpha-concat
              // constraint as TYPE_ICON above. Kept in sync with the token's
              // new value (fix/ui-contrast-tokens).
              const icon = TYPE_ICON[item.type] ?? { name: 'bell', color: '#5c6b80' };
              return (
                <button
                  key={item.id}
                  ref={(node) => { itemRefs.current[index + 1] = node; }}
                  type="button"
                  onClick={() => handleClick(item)}
                  className={`w-full flex gap-3 items-start py-3 px-4 border-0 cursor-pointer text-left border-b border-surface-subtle transition-[background] duration-100 ${item.read ? 'bg-surface' : 'bg-info-row-active'}`}
                >
                  <span
                    className="w-8 h-8 rounded-full shrink-0 !flex items-center justify-center mt-px"
                    style={{ background: icon.color + '1a' }}
                  >
                    <Icon name={icon.name} size={15} style={{ color: icon.color }} />
                  </span>
                  <span className="flex-1 min-w-0">
                    <span className="!text-sm block leading-[1.45] !text-text">
                      {item.message}
                    </span>
                    <span className="!text-2xs !text-text-muted mt-[3px] block">
                      {item.ticketCode && <code className="mr-[6px] text-2xs">{item.ticketCode}</code>}
                      {timeAgo(item.createdAt)}
                    </span>
                  </span>
                  {!item.read && (
                    <span className="w-2 h-2 rounded-full bg-info shrink-0 mt-[6px]" />
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
