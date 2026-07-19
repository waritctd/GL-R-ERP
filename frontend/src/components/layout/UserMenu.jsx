import { useEffect, useRef, useState } from 'react';
import { Avatar } from '../common/Avatar.jsx';
import { Icon } from '../common/Icon.jsx';

/**
 * Structure mirrors NotificationBell (open state, outside-click close, popover
 * anchored in the topbar) so the two header popovers behave identically. The
 * topbar sits outside `.content-scroll`, so absolute positioning is not clipped.
 */
export function UserMenu({ user, employee, canViewProfile, onNavigate, onLogout }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  const triggerRef = useRef(null);
  const itemRefs = useRef([]);

  useEffect(() => {
    if (!open) return undefined;
    function handlePointer(event) {
      if (ref.current && !ref.current.contains(event.target)) setOpen(false);
    }
    document.addEventListener('mousedown', handlePointer);
    return () => document.removeEventListener('mousedown', handlePointer);
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    itemRefs.current[0]?.focus();
    function handleKeyDown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        setOpen(false);
        triggerRef.current?.focus();
        return;
      }
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
      event.preventDefault();
      const items = itemRefs.current.filter(Boolean);
      if (items.length === 0) return;
      const current = items.indexOf(document.activeElement);
      const step = event.key === 'ArrowDown' ? 1 : -1;
      const next = (current + step + items.length) % items.length;
      items[next]?.focus();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open]);

  function select(action) {
    setOpen(false);
    action();
  }

  const itemClass = 'w-full flex items-center gap-3 py-0 px-4 min-h-11 border-0 bg-transparent'
    + ' cursor-pointer text-left !text-sm !text-text transition-[background] duration-100'
    + ' hover:bg-surface-hover focus-visible:outline-none focus-visible:bg-surface-hover';

  return (
    <div ref={ref} className="relative">
      <button
        ref={triggerRef}
        type="button"
        className="flex items-center justify-center min-w-11 min-h-11 border-0 bg-transparent cursor-pointer p-0 rounded-md focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]"
        onClick={() => setOpen((value) => !value)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="เมนูผู้ใช้"
      >
        <Avatar employee={employee} name={user.name} size="sm" />
      </button>

      {open && (
        <div
          role="menu"
          aria-label="เมนูผู้ใช้"
          className="absolute top-[calc(100%+8px)] right-0 w-[240px] bg-surface border border-border-subtle rounded-[12px] shadow-[0_8px_24px_rgba(0,0,0,0.12)] z-[200] overflow-hidden"
        >
          <div className="py-3 px-4 border-b border-border">
            <strong className="block !text-sm !text-text truncate">{employee?.nameTh || user.name}</strong>
            <span className="block !text-xs !text-text-muted truncate">{user.email}</span>
          </div>

          {canViewProfile && (
            <button
              ref={(node) => { itemRefs.current[0] = node; }}
              type="button"
              role="menuitem"
              className={itemClass}
              onClick={() => select(() => onNavigate('/profile'))}
            >
              <Icon name="badge" size={17} />
              ข้อมูลของฉัน
            </button>
          )}

          <div className="border-t border-border" />

          <button
            ref={(node) => { itemRefs.current[1] = node; }}
            type="button"
            role="menuitem"
            className={itemClass}
            onClick={() => select(onLogout)}
          >
            <Icon name="logout" size={17} />
            ออกจากระบบ
          </button>
        </div>
      )}
    </div>
  );
}
