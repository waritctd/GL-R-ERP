import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Icon } from './Icon.jsx';

const TYPE_ICON = {
  SUBMITTED:       { name: 'fileText', color: '#f59e0b' },
  PICKED_UP:       { name: 'users',    color: '#3b82f6' },
  PRICE_PROPOSED:  { name: 'clock',    color: '#f59e0b' },
  APPROVED:        { name: 'check',    color: '#22c55e' },
  REJECTED:        { name: 'close',    color: '#ef4444' },
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

export function NotificationBell({ onNavigate }) {
  const [items, setItems] = useState([]);
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  async function load() {
    try {
      const res = await api.notifications.list();
      setItems(Array.isArray(res.notifications) ? res.notifications : []);
    } catch {
      setItems([]);
    }
  }

  useEffect(() => {
    load();
    const interval = setInterval(load, 30000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (!open) return;
    function handleClick(e) {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  async function handleClick(item) {
    if (!item.read) {
      await api.notifications.markRead(item.id);
      setItems((prev) => prev.map((n) => n.id === item.id ? { ...n, read: true } : n));
    }
    setOpen(false);
    if (item.link) onNavigate(item.link);
  }

  async function markAllRead() {
    const unread = items.filter((n) => !n.read);
    await Promise.all(unread.map((n) => api.notifications.markRead(n.id)));
    setItems((prev) => prev.map((n) => ({ ...n, read: true })));
  }

  const unreadCount = items.filter((n) => !n.read).length;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        className="icon-button relative"
        onClick={() => setOpen((v) => !v)}
        title="การแจ้งเตือน"
        aria-label="การแจ้งเตือน"
      >
        <Icon name="bell" />
        {unreadCount > 0 && (
          <span className="absolute top-[2px] right-[2px] min-w-4 h-4 rounded-md bg-[#ef4444] !text-surface !text-[10px] !font-extrabold !flex items-center justify-center px-[3px] py-0 leading-none pointer-events-none">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute top-[calc(100%+8px)] right-0 w-[340px] bg-surface border border-border-subtle rounded-[12px] shadow-[0_8px_24px_rgba(0,0,0,0.12)] z-[200] overflow-hidden">
          <div className="flex items-center justify-between py-3 px-4 border-b border-border">
            <strong className="text-md">การแจ้งเตือน</strong>
            {unreadCount > 0 && (
              <button
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
              <div className="py-7 px-4 text-center text-text-faint text-sm">
                ไม่มีการแจ้งเตือน
              </div>
            ) : items.map((item) => {
              const icon = TYPE_ICON[item.type] ?? { name: 'bell', color: '#64748b' };
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => handleClick(item)}
                  className={`w-full flex gap-3 items-start py-3 px-4 border-0 cursor-pointer text-left border-b border-surface-subtle transition-[background] duration-100 ${item.read ? 'bg-surface' : 'bg-[#f0f6ff]'}`}
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
                    <span className="!text-2xs !text-text-faint mt-[3px] block">
                      {item.ticketCode && <code className="mr-[6px] text-2xs">{item.ticketCode}</code>}
                      {timeAgo(item.createdAt)}
                    </span>
                  </span>
                  {!item.read && (
                    <span className="w-2 h-2 rounded-full bg-[#3b82f6] shrink-0 mt-[6px]" />
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
