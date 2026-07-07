import { useCallback, useEffect, useRef, useState } from 'react';
import { hasPermission } from '../../app/permissions.js';
import { roleLabel } from '../../utils/format.js';
import { Avatar } from '../common/Avatar.jsx';
import { Icon } from '../common/Icon.jsx';
import { NotificationBell } from '../common/NotificationBell.jsx';
import { Sidebar } from './Sidebar.jsx';

export function AppShell({ user, employee, route, onRoute, onLogout, pendingRequestCount, onOpenTicket, children }) {
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const drawerRef = useRef(null);
  const menuButtonRef = useRef(null);
  const previousRouteRef = useRef(route);
  const drawerId = 'mobile-navigation-drawer';
  const navItems = [
    { route: 'dashboard', label: 'แดชบอร์ด', helper: 'Dashboard', icon: 'dashboard', show: true },
    { route: 'hr-dashboard', label: 'ภาพรวม HR', helper: 'HR overview', icon: 'home', show: hasPermission(user.role, 'canViewEmployees') },
    { route: 'ticket-dashboard', label: 'ภาพรวมใบขอราคา', helper: 'Ticket overview', icon: 'home', show: hasPermission(user.role, 'canViewTickets') },
    { route: 'tickets', label: 'ใบขอราคา', helper: 'Price requests', icon: 'fileText', show: hasPermission(user.role, 'canViewTickets') },
    { route: 'ceo-settings', label: 'ตั้งค่าราคา', helper: 'CEO price config', icon: 'setting', show: ['ceo', 'admin'].includes(user.role) },
    { route: 'commissions', label: 'ค่าคอมมิชชัน', helper: 'Commissions', icon: 'badgeDollar', show: hasPermission(user.role, 'canViewCommissions') },
    { route: 'payroll', label: 'เงินเดือน', helper: 'Payroll', icon: 'badgeDollar', show: hasPermission(user.role, 'canManagePayroll') },
    { route: 'employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', show: hasPermission(user.role, 'canViewEmployees') },
    { route: 'attendance', label: 'เวลาทำงาน', helper: 'Attendance', icon: 'calendar', show: true },
    { route: 'overtime', label: 'ล่วงเวลา', helper: 'Overtime', icon: 'clock', show: !!user.employeeId || hasPermission(user.role, 'canViewAllOvertime') },
    { route: 'leave', label: 'วันลา', helper: 'Leave', icon: 'clipboard', show: !!user.employeeId || hasPermission(user.role, 'canViewAllLeave') },
    { route: 'requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', show: hasPermission(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    { route: 'profile', label: 'ข้อมูลของฉัน', helper: 'My profile', icon: 'badge', show: !!user.employeeId },
    { route: 'myrequests', label: 'คำขอของฉัน', helper: 'My requests', icon: 'clock', show: hasPermission(user.role, 'canSubmitProfileRequests'), badge: pendingRequestCount },
  ].filter((item) => item.show);

  const closeDrawer = useCallback(() => setIsDrawerOpen(false), []);

  const handleRoute = useCallback((nextRoute) => {
    closeDrawer();
    onRoute(nextRoute);
  }, [closeDrawer, onRoute]);

  useEffect(() => {
    if (previousRouteRef.current !== route) {
      closeDrawer();
      previousRouteRef.current = route;
    }
  }, [closeDrawer, route]);

  useEffect(() => {
    if (!isDrawerOpen) return undefined;

    const previouslyFocused = document.activeElement;
    const menuButton = menuButtonRef.current;
    const focusableSelector = [
      'a[href]',
      'button:not([disabled])',
      'input:not([disabled])',
      'select:not([disabled])',
      'textarea:not([disabled])',
      '[tabindex]:not([tabindex="-1"])',
    ].join(',');

    const getFocusableItems = () => Array
      .from(drawerRef.current?.querySelectorAll(focusableSelector) || [])
      .filter((element) => element.getClientRects().length > 0);

    const focusableItems = getFocusableItems();
    (focusableItems[0] || drawerRef.current)?.focus();

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        closeDrawer();
        return;
      }

      if (event.key !== 'Tab') return;

      const items = getFocusableItems();
      if (items.length === 0) {
        event.preventDefault();
        return;
      }

      const firstItem = items[0];
      const lastItem = items[items.length - 1];

      if (event.shiftKey && document.activeElement === firstItem) {
        event.preventDefault();
        lastItem.focus();
      } else if (!event.shiftKey && document.activeElement === lastItem) {
        event.preventDefault();
        firstItem.focus();
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus();
      } else {
        menuButton?.focus();
      }
    };
  }, [closeDrawer, isDrawerOpen]);

  return (
    <div className="app-shell">
      <Sidebar
        id={drawerId}
        drawerRef={drawerRef}
        isDrawerOpen={isDrawerOpen}
        items={navItems}
        activeRoute={route}
        onRoute={handleRoute}
        user={user}
        employee={employee}
        onLogout={onLogout}
      />
      <button
        type="button"
        className={`mobile-drawer-backdrop ${isDrawerOpen ? 'is-open' : ''}`}
        aria-label="ปิดเมนู"
        onClick={closeDrawer}
        tabIndex={-1}
      />
      <main className="app-main">
        <header className="topbar">
          <button
            ref={menuButtonRef}
            className="icon-button mobile-nav-toggle"
            type="button"
            onClick={() => setIsDrawerOpen((open) => !open)}
            aria-label="เปิดเมนูนำทาง"
            aria-controls={drawerId}
            aria-expanded={isDrawerOpen}
          >
            <Icon name="menu" />
          </button>
          <div className="topbar-title">
            <span>GL&R HR</span>
            <small>{roleLabel(user.role)}</small>
          </div>
          <div className="topbar-user">
            <div className="topbar-user-text">
              <strong>{employee?.nameTh || user.name}</strong>
              <span>{user.email}</span>
            </div>
            <Avatar employee={employee} name={user.name} size="sm" />
            <NotificationBell onOpenTicket={onOpenTicket} />
            <button className="icon-button" type="button" onClick={onLogout} title="ออกจากระบบ" aria-label="ออกจากระบบ">
              <Icon name="logout" />
            </button>
          </div>
        </header>
        <div className="content-scroll">{children}</div>
      </main>
    </div>
  );
}
