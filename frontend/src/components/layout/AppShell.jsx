import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { SALES_ENABLED } from '../../app/features.js';
import { hasPermission } from '../../app/permissions.js';
import { roleLabel } from '../../utils/format.js';
import { Avatar } from '../common/Avatar.jsx';
import { Button } from '../common/Button.jsx';
import { ErrorBoundary } from '../common/ErrorBoundary.jsx';
import { RouteFallback } from '../common/RouteFallback.jsx';
import { Icon } from '../common/Icon.jsx';
import { NotificationBell } from '../common/NotificationBell.jsx';
import { Sidebar } from './Sidebar.jsx';

export function AppShell({ user, employee, onLogout, pendingRequestCount }) {
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const drawerRef = useRef(null);
  const menuButtonRef = useRef(null);
  const location = useLocation();
  const navigate = useNavigate();
  const drawerId = 'mobile-navigation-drawer';
  const navItems = [
    { path: '/', label: 'แดชบอร์ด', helper: 'Dashboard', icon: 'dashboard', show: true },
    { path: '/hr', label: 'ภาพรวม HR', helper: 'HR overview', icon: 'home', show: hasPermission(user.role, 'canViewEmployees') },
    { path: '/ticket-overview', label: 'ภาพรวมใบขอราคา', helper: 'Ticket overview', icon: 'home', show: hasPermission(user.role, 'canViewTickets') && SALES_ENABLED },
    { path: '/tickets', label: 'ใบขอราคา', helper: 'Price requests', icon: 'fileText', show: hasPermission(user.role, 'canViewTickets') && SALES_ENABLED },
    { path: '/ceo-settings', label: 'ตั้งค่าราคา', helper: 'CEO price config', icon: 'setting', show: ['ceo', 'admin'].includes(user.role) && SALES_ENABLED },
    { path: '/commissions', label: 'ค่าคอมมิชชัน', helper: 'Commissions', icon: 'badgeDollar', show: hasPermission(user.role, 'canViewCommissions') && SALES_ENABLED },
    { path: '/payroll', label: 'เงินเดือน', helper: 'Payroll', icon: 'badgeDollar', show: hasPermission(user.role, 'canManagePayroll') },
    { path: '/employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', show: hasPermission(user.role, 'canViewEmployees') },
    { path: '/attendance', label: 'เวลาทำงาน', helper: 'Attendance', icon: 'calendar', show: true },
    { path: '/overtime', label: 'ล่วงเวลา', helper: 'Overtime', icon: 'clock', show: !!user.employeeId || hasPermission(user.role, 'canViewAllOvertime') },
    { path: '/leave', label: 'วันลา', helper: 'Leave', icon: 'clipboard', show: !!user.employeeId || hasPermission(user.role, 'canViewAllLeave') },
    { path: '/requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', show: hasPermission(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    { path: '/profile', label: 'ข้อมูลของฉัน', helper: 'My profile', icon: 'badge', show: !!user.employeeId },
    { path: '/my-requests', label: 'คำขอของฉัน', helper: 'My requests', icon: 'clock', show: hasPermission(user.role, 'canSubmitProfileRequests'), badge: pendingRequestCount },
  ].filter((item) => item.show);

  const closeDrawer = useCallback(() => setIsDrawerOpen(false), []);

  // Close the mobile drawer whenever the URL changes (navigation happened).
  useEffect(() => {
    closeDrawer();
  }, [closeDrawer, location.pathname]);

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
          <Button
            ref={menuButtonRef}
            variant="icon"
            className="!hidden max-[720px]:!inline-flex max-[720px]:flex-[0_0_44px]"
            type="button"
            onClick={() => setIsDrawerOpen((open) => !open)}
            aria-label="เปิดเมนูนำทาง"
            aria-controls={drawerId}
            aria-expanded={isDrawerOpen}
          >
            <Icon name="menu" />
          </Button>
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
            <NotificationBell onOpenTicket={(id) => navigate(`/tickets/${id}`)} />
            <Button variant="icon" type="button" onClick={onLogout} title="ออกจากระบบ" aria-label="ออกจากระบบ">
              <Icon name="logout" />
            </Button>
          </div>
        </header>
        <div className="content-scroll"><ErrorBoundary key={location.pathname}><Suspense fallback={<RouteFallback />}><Outlet /></Suspense></ErrorBoundary></div>
      </main>
    </div>
  );
}
