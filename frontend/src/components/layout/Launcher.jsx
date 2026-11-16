import { Link, useLocation } from 'react-router-dom';
import { cn } from '../../utils/cn.js';
import { Icon } from '../common/Icon.jsx';

const SECTIONS = [
  { key: undefined, label: 'หน้าหลัก' },
  { key: 'sales', label: 'งานขาย' },
  { key: 'hr', label: 'บุคคล' },
  { key: 'finance', label: 'การเงิน' },
  // A division manager's team entry points; 'self' stays their own self-service.
  { key: 'team', label: 'ทีมของฉัน' },
  { key: 'self', label: 'บุคคลของฉัน' },
  { key: 'settings', label: 'ตั้งค่า' },
];

const FOCUS_RING = 'focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]';

function isItemActive(item, pathname) {
  return pathname === item.path
    || pathname.startsWith(`${item.path}/`)
    || (item.match?.some((p) => pathname === p || pathname.startsWith(`${p}/`)) ?? false);
}

function LauncherLink({ item, pathname, onNavigate }) {
  // aria-label replaces the accessible name, so the unread count is folded into it and the visual badge hidden.
  const label = `${item.label} (${item.helper})${item.badge ? ` — ${item.badge} รายการใหม่` : ''}`;
  const active = isItemActive(item, pathname);
  return (
    <Link
      to={item.path}
      onClick={onNavigate}
      aria-label={label}
      aria-current={active ? 'page' : undefined}
      title={label}
      className={cn(
        'grid min-h-9 grid-cols-[18px_1fr_auto] items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sm text-text no-underline',
        'hover:bg-surface-hover',
        FOCUS_RING,
        active && 'bg-primary-bg font-bold text-primary',
      )}
    >
      <Icon name={item.icon} size={17} />
      <span className="min-w-0">{item.label}</span>
      {item.badge ? (
        <b aria-hidden="true" className="grid h-5 min-w-5 place-items-center rounded-full bg-accent-dark px-1 text-xs text-surface">
          {item.badge}
        </b>
      ) : null}
    </Link>
  );
}

export function Launcher({ id, panelRef, items, onNavigate }) {
  const { pathname } = useLocation();
  for (const item of items) {
    if (!SECTIONS.some((section) => section.key === item.group)) {
      const message = `Launcher: item ${item.path} has group "${item.group}", which is not a section in SECTIONS; it would not render.`;
      if (import.meta.env.DEV) throw new Error(message);
      console.error(message);
    }
  }
  const sections = SECTIONS
    .map((section) => ({ ...section, items: items.filter((item) => item.group === section.key) }))
    .filter((section) => section.items.length > 0);

  return (
    <nav
      id={id}
      ref={panelRef}
      aria-label="Primary navigation"
      tabIndex={-1}
      className={cn(
        'absolute left-0 top-[calc(100%+8px)] z-[200] w-[min(980px,calc(100vw-56px))] overflow-y-auto overscroll-contain',
        // Header mobile side padding is 16px (AppShell), so the wrapper's left edge sits 16px in: 100vw minus both gutters.
        'mobile:w-[calc(100vw-32px)]',
        'max-h-[calc(100dvh-var(--app-topbar-h)-24px)] rounded-md border border-border-subtle bg-surface p-4',
        'shadow-[0_18px_40px_rgba(15,23,42,0.18)]',
        'grid grid-cols-[repeat(auto-fit,minmax(180px,1fr))] gap-x-6 gap-y-4',
      )}
    >
      {sections.map((section) => {
        const headingId = `launcher-section-${section.key ?? 'home'}`;
        return (
          <section key={headingId} aria-labelledby={headingId} className="min-w-0">
            <h2 id={headingId} className="m-0 px-2.5 pb-1 text-2xs font-extrabold text-text-muted">{section.label}</h2>
            <ul className="m-0 grid list-none gap-0.5 p-0">
              {section.items.map((item) => (
                <li key={`${item.path}-${item.label}`}>
                  <LauncherLink item={item} pathname={pathname} onNavigate={onNavigate} />
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </nav>
  );
}
