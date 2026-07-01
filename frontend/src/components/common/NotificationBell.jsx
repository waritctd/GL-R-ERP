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

export function NotificationBell({ onOpenTicket }) {
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
    if (item.ticketId) onOpenTicket(item.ticketId);
  }

  async function markAllRead() {
    const unread = items.filter((n) => !n.read);
    await Promise.all(unread.map((n) => api.notifications.markRead(n.id)));
    setItems((prev) => prev.map((n) => ({ ...n, read: true })));
  }

  const unreadCount = items.filter((n) => !n.read).length;

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button
        type="button"
        className="icon-button"
        onClick={() => setOpen((v) => !v)}
        title="การแจ้งเตือน"
        aria-label="การแจ้งเตือน"
        style={{ position: 'relative' }}
      >
        <Icon name="bell" />
        {unreadCount > 0 && (
          <span style={{
            position: 'absolute', top: 2, right: 2,
            minWidth: 16, height: 16, borderRadius: 8,
            background: '#ef4444', color: '#fff',
            fontSize: 10, fontWeight: 800,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            padding: '0 3px', lineHeight: 1,
            pointerEvents: 'none',
          }}>
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div style={{
          position: 'absolute', top: 'calc(100% + 8px)', right: 0,
          width: 340, background: '#fff',
          border: '1px solid #e2e8f0', borderRadius: 12,
          boxShadow: '0 8px 24px rgba(0,0,0,0.12)',
          zIndex: 200, overflow: 'hidden',
        }}>
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '12px 16px', borderBottom: '1px solid #e6eaf0',
          }}>
            <strong style={{ fontSize: 14 }}>การแจ้งเตือน</strong>
            {unreadCount > 0 && (
              <button
                type="button"
                onClick={markAllRead}
                style={{
                  fontSize: 12, color: '#1d4ed8', background: 'none',
                  border: 'none', cursor: 'pointer', padding: 0,
                }}
              >
                อ่านทั้งหมด
              </button>
            )}
          </div>

          <div style={{ maxHeight: 360, overflowY: 'auto' }}>
            {items.length === 0 ? (
              <div style={{ padding: '28px 16px', textAlign: 'center', color: '#94a3b8', fontSize: 13 }}>
                ไม่มีการแจ้งเตือน
              </div>
            ) : items.map((item) => {
              const icon = TYPE_ICON[item.type] ?? { name: 'bell', color: '#64748b' };
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => handleClick(item)}
                  style={{
                    width: '100%', display: 'flex', gap: 12, alignItems: 'flex-start',
                    padding: '12px 16px', border: 'none', cursor: 'pointer', textAlign: 'left',
                    background: item.read ? '#fff' : '#f0f6ff',
                    borderBottom: '1px solid #f1f5f9',
                    transition: 'background 0.1s',
                  }}
                >
                  <span style={{
                    width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
                    background: icon.color + '1a',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    marginTop: 1,
                  }}>
                    <Icon name={icon.name} size={15} style={{ color: icon.color }} />
                  </span>
                  <span style={{ flex: 1, minWidth: 0 }}>
                    <span style={{ fontSize: 13, display: 'block', lineHeight: 1.45, color: '#0f172a' }}>
                      {item.message}
                    </span>
                    <span style={{ fontSize: 11, color: '#94a3b8', marginTop: 3, display: 'block' }}>
                      {item.ticketCode && <code style={{ marginRight: 6, fontSize: 11 }}>{item.ticketCode}</code>}
                      {timeAgo(item.createdAt)}
                    </span>
                  </span>
                  {!item.read && (
                    <span style={{
                      width: 8, height: 8, borderRadius: '50%',
                      background: '#3b82f6', flexShrink: 0, marginTop: 6,
                    }} />
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
