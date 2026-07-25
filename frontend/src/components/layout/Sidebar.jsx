import { useEffect, useState } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { roleLabel } from '../../utils/format.js';
import { Avatar } from '../common/Avatar.jsx';
import { Button } from '../common/Button.jsx';
import { Icon } from '../common/Icon.jsx';

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
  const accessibleLabel = item.helper ? `${item.label} (${item.helper})` : item.label;

  return (
    <NavLink
      to={item.path}
      end={item.path === '/'}
      className={() => `nav-item ${isItemActive(item, pathname) ? 'active' : ''}`}
      aria-label={accessibleLabel}
      title={accessibleLabel}
    >
      <Icon name={item.icon} size={19} />
      <span>
        {item.label}
        <small>{item.helper}</small>
      </span>
      {item.badge ? <b>{item.badge}</b> : null}
    </NavLink>
  );
}

function useTabletRail() {
  const query = '(min-width: 721px) and (max-width: 1040px)';
  const [isTabletRail, setIsTabletRail] = useState(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const mediaQuery = window.matchMedia(query);
    const handleChange = (event) => setIsTabletRail(event.matches);

    setIsTabletRail(mediaQuery.matches);
    if (typeof mediaQuery.addEventListener === 'function') {
      mediaQuery.addEventListener('change', handleChange);
      return () => mediaQuery.removeEventListener('change', handleChange);
    }

    mediaQuery.addListener(handleChange);
    return () => mediaQuery.removeListener(handleChange);
  }, []);

  return isTabletRail;
}

export function Sidebar({ id, drawerRef, isDrawerOpen = false, items, user, employee, onLogout }) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const isTabletRail = useTabletRail();
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
      className={`sidebar ${isDrawerOpen ? 'is-mobile-drawer-open' : ''}`}
      tabIndex={-1}
    >
      <button className="brand" type="button" onClick={() => navigate('/')} aria-label="GL&R home" title="GL&R home">
        <span className="brand-mark">
          <i />
          <i />
          <i />
          <i />
        </span>
        <span>
          <strong>GL&R</strong>
          <small>HR Portal</small>
        </span>
      </button>

      <nav className="nav-list" aria-label="Primary navigation">
        {ungrouped.map((item) => (
          <NavItemLink key={item.path} item={item} pathname={pathname} />
        ))}

        {grouped.map((group) => {
          const isCollapsed = !isTabletRail && collapsedGroups.has(group.key);
          const panelId = `nav-group-panel-${group.key}`;
          return (
            <div key={group.key} className="nav-group">
              <button
                type="button"
                className="nav-group-header"
                aria-expanded={!isCollapsed}
                aria-controls={panelId}
                onClick={() => toggleGroup(group.key)}
                title={`${group.label} (${group.helper})`}
              >
                {group.label}
                <span className="nav-group-header-helper">{group.helper}</span>
                <Icon
                  name="chevronDown"
                  size={16}
                  className={`nav-group-chevron${isCollapsed ? ' is-collapsed' : ''}`}
                />
              </button>
              {/* Conditional render, not the `hidden` attribute: a sibling
                  `display` utility would silently out-specificity `[hidden]`
                  (this bit the first version of this component). Matches how
                  CollapsibleSection.jsx already unmounts its collapsed body. */}
              {!isCollapsed ? (
                <div id={panelId} role="group" aria-label={group.label} className="nav-group-panel">
                  {group.items.map((item) => (
                    <NavItemLink key={item.path} item={item} pathname={pathname} />
                  ))}
                </div>
              ) : null}
            </div>
          );
        })}
      </nav>

      <div className="sidebar-account">
        <Avatar employee={employee} name={user.name} size="sm" />
        <span>
          <strong>{employee?.nameTh || user.name}</strong>
          <small>{roleLabel(user.role)}</small>
        </span>
        <Button
          variant="icon"
          className="bg-transparent text-text-faint border-transparent"
          type="button"
          onClick={onLogout}
          title="ออกจากระบบ"
          aria-label="ออกจากระบบ"
        >
          <Icon name="logout" />
        </Button>
      </div>
    </aside>
  );
}
