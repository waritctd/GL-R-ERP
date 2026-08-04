import { useEffect, useState } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { PRODUCT_MARK, PRODUCT_NAME, PRODUCT_PORTAL_LABEL } from '../../app/product.js';
import { cn } from '../../utils/cn.js';
import { Icon } from '../common/Icon.jsx';

// Shared with .nav-group-header below (Tailwind port of the legacy
// `.nav-item:focus-visible, .nav-group-header:focus-visible` rule): a solid
// 2px ring tuned for the dark sidebar surface, see styles.css's removed
// comment for why --color-teal-focus (not the app-wide indigo ring) is used
// here.
const SIDEBAR_FOCUS_RING = 'focus-visible:outline-none focus-visible:shadow-[0_0_0_2px_var(--color-sidebar-bg),0_0_0_4px_var(--color-teal-focus)]';

const NAV_GROUPS = [
  { key: 'sales', label: 'งานขาย', helper: 'Sales' },
  { key: 'hr', label: 'บุคคล', helper: 'HR' },
  { key: 'finance', label: 'การเงิน', helper: 'Finance & Payroll' },
  // Division-manager (non-sales) landing: approvals/roster/team-attendance for
  // the manager's own division. Distinct from 'self' below, which stays the
  // manager's own personal self-service (same as every other role).
  { key: 'team', label: 'ทีมของฉัน', helper: 'Team' },
  { key: 'self', label: 'บุคคลของฉัน', helper: 'Self-service' },
];

function isItemActive(item, pathname) {
  return pathname === item.path
    || pathname.startsWith(`${item.path}/`)
    || (item.match?.some((p) => pathname === p || pathname.startsWith(`${p}/`)) ?? false);
}

function NavItemLink({ item, pathname }) {
  // aria-label REPLACES the accessible name, so the unread badge rendered below
  // would otherwise stop being announced — a screen-reader user would lose the
  // count that sighted users can see. Fold it into the label instead.
  const accessibleLabel = [
    item.label,
    item.helper ? `(${item.helper})` : '',
    item.badge ? `— ${item.badge} รายการใหม่` : '',
  ].filter(Boolean).join(' ');

  const active = isItemActive(item, pathname);

  return (
    <NavLink
      to={item.path}
      end={item.path === '/'}
      className={() => cn(
        'grid min-h-12 w-full min-w-0 grid-cols-[22px_1fr_auto] items-center gap-[11px] rounded-md',
        'border-l-2 border-transparent py-2 pl-2 pr-2.5 text-left text-sidebar-text no-underline',
        SIDEBAR_FOCUS_RING,
        // `active` is a plain marker token (no CSS of its own left in
        // styles.css) kept only because AppShell.test.jsx pins the active
        // route highlight by asserting the link's className contains it.
        active && 'active border-indigo-ring bg-indigo-ring/15 text-surface',
      )}
      aria-label={accessibleLabel}
      title={accessibleLabel}
    >
      <Icon name={item.icon} size={19} />
      <span className="min-w-0 font-semibold">
        {item.label}
        <small className="block text-xs font-medium text-text-faint">{item.helper}</small>
      </span>
      {item.badge ? (
        <b
          aria-hidden="true"
          className="grid h-[22px] min-w-[22px] place-items-center rounded-full bg-accent-dark text-xs text-surface"
        >
          {item.badge}
        </b>
      ) : null}
    </NavLink>
  );
}

export function Sidebar({ id, drawerRef, isDrawerOpen = false, items }) {
  const { pathname } = useLocation();
  // Collapsed groups are the exception (default = expanded), so absence from
  // this set means "open" — nothing to persist for the common case.
  const [collapsedGroups, setCollapsedGroups] = useState(() => new Set());
  const toggleGroup = (key) => setCollapsedGroups((prev) => {
    const next = new Set(prev);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });

  const ungrouped = items.filter((item) => !item.group);
  const grouped = NAV_GROUPS
    .map((group) => ({ ...group, items: items.filter((item) => item.group === group.key) }))
    .filter((group) => group.items.length > 0);

  // If navigation lands on an item whose group is collapsed (a notification
  // deep-link, browser back/forward, the active-route highlight would
  // otherwise be hidden behind a closed section — force that one group open.
  useEffect(() => {
    const activeGroup = grouped.find((group) => group.items.some((item) => isItemActive(item, pathname)));
    if (activeGroup && collapsedGroups.has(activeGroup.key)) {
      setCollapsedGroups((prev) => {
        const next = new Set(prev);
        next.delete(activeGroup.key);
        return next;
      });
    }
    // Only re-run when the route changes — expanding a group the user just
    // collapsed by hand (no route change involved) would fight their click.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname]);

  return (
    <aside
      id={id}
      ref={drawerRef}
      className={cn(
        // `sidebar`/`is-mobile-drawer-open` carry no CSS of their own — kept
        // only because e2e/shared-shell.spec.js selects the drawer via
        // `.sidebar.is-mobile-drawer-open` and asserts its absence when closed.
        'sidebar',
        'flex w-[260px] flex-none flex-col pt-5 px-[14px] pb-[14px] bg-sidebar-bg text-sidebar-text',
        'nav-drawer:fixed nav-drawer:inset-y-0 nav-drawer:left-0 nav-drawer:right-auto nav-drawer:z-40',
        'nav-drawer:w-[min(300px,calc(100vw-40px))] nav-drawer:max-w-full nav-drawer:basis-auto nav-drawer:overflow-y-auto',
        'nav-drawer:shadow-[var(--shadow-lg-heavy)] motion-reduce:transition-none',
        isDrawerOpen
          ? 'is-mobile-drawer-open nav-drawer:translate-x-0 nav-drawer:visible nav-drawer:transition-[transform_180ms_ease]'
          : 'nav-drawer:-translate-x-full nav-drawer:invisible nav-drawer:transition-[transform_180ms_ease,visibility_0s_linear_180ms]',
      )}
      tabIndex={-1}
    >
      <Link
        className="flex items-center gap-3 border-0 bg-transparent px-2 pt-1 pb-[22px] text-left text-surface no-underline"
        to="/"
        aria-label={`${PRODUCT_NAME} home`}
        title={`${PRODUCT_NAME} home`}
      >
        <span className="grid h-[34px] w-[34px] flex-none grid-cols-2 gap-[3px]">
          <i className="rounded-sm bg-accent" />
          <i className="rounded-sm bg-primary-hover" />
          <i className="rounded-sm bg-icon-muted" />
          <i className="rounded-sm bg-accent" />
        </span>
        <span>
          <strong className="block text-xl leading-none">{PRODUCT_MARK}</strong>
          <small className="text-xs text-text-faint">{PRODUCT_PORTAL_LABEL}</small>
        </span>
      </Link>

      <nav className="grid min-h-0 gap-1 overflow-y-auto overscroll-contain pr-0.5" aria-label="Primary navigation">
        {ungrouped.map((item) => (
          <NavItemLink key={item.path} item={item} pathname={pathname} />
        ))}

        {grouped.map((group) => {
          // No width-specific special case here: at ≤1040px this component
          // renders inside the off-canvas drawer (see AppShell/styles.css),
          // which is a full-width surface with room for a real, clickable
          // group header — same expand/collapse state as desktop >1040px.
          // There is no longer a persistent tablet rail to special-case.
          const isCollapsed = collapsedGroups.has(group.key);
          const panelId = `nav-group-panel-${group.key}`;
          return (
            <div key={group.key} className="grid gap-1">
              <button
                type="button"
                className={cn(
                  'flex min-h-11 w-full items-center gap-2 rounded-md border-0 bg-transparent px-2.5 py-2 text-left',
                  'text-2xs font-extrabold uppercase tracking-normal text-text-faint hover:text-surface',
                  SIDEBAR_FOCUS_RING,
                )}
                aria-expanded={!isCollapsed}
                aria-controls={panelId}
                onClick={() => toggleGroup(group.key)}
                title={`${group.label} (${group.helper})`}
              >
                {group.label}
                <span className="ml-1 font-semibold normal-case tracking-normal opacity-70">{group.helper}</span>
                <Icon
                  name="chevronDown"
                  size={16}
                  className={cn('ml-auto flex-none transition-transform duration-150 ease-[ease] motion-reduce:transition-none', isCollapsed && '-rotate-90')}
                />
              </button>
              {/* Conditional render, not the `hidden` attribute: a sibling
                  `display` utility would silently out-specificity `[hidden]`
                  (this bit the first version of this component). Matches how
                  CollapsibleSection.jsx already unmounts its collapsed body. */}
              {!isCollapsed ? (
                <div id={panelId} role="group" aria-label={group.label} className="grid gap-1">
                  {group.items.map((item) => (
                    <NavItemLink key={item.path} item={item} pathname={pathname} />
                  ))}
                </div>
              ) : null}
            </div>
          );
        })}
      </nav>
    </aside>
  );
}
