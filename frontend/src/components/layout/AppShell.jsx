import { hasPermission } from '../../app/permissions.js';
import { roleLabel } from '../../utils/format.js';
import { Avatar } from '../common/Avatar.jsx';
import { Icon } from '../common/Icon.jsx';
import { NotificationBell } from '../common/NotificationBell.jsx';
import { Sidebar } from './Sidebar.jsx';

export function AppShell({ user, employee, route, onRoute, onLogout, pendingRequestCount, onOpenTicket, children }) {
  const isSalesOnlyRole = hasPermission(user.role, 'canViewTickets') && !hasPermission(user.role, 'canViewEmployees');

  const navItems = [
    { route: 'dashboard', label: 'แดชบอร์ด', helper: 'Dashboard', icon: 'dashboard', show: true },
    { route: 'tickets', label: 'ใบขอราคา', helper: 'Price requests', icon: 'fileText', show: hasPermission(user.role, 'canViewTickets') },
    { route: 'employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', show: hasPermission(user.role, 'canViewEmployees') },
    { route: 'requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', show: hasPermission(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    { route: 'profile', label: 'ข้อมูลของฉัน', helper: 'My profile', icon: 'badge', show: !!user.employeeId },
    { route: 'myrequests', label: 'คำขอของฉัน', helper: 'My requests', icon: 'clock', show: hasPermission(user.role, 'canSubmitProfileRequests'), badge: pendingRequestCount },
  ].filter((item) => item.show);

  return (
    <div className="app-shell">
      <Sidebar items={navItems} activeRoute={route} onRoute={onRoute} user={user} employee={employee} onLogout={onLogout} />
      <main className="app-main">
        <header className="topbar">
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
            <button className="icon-button" type="button" onClick={onLogout} title="ออกจากระบบ">
              <Icon name="logout" />
            </button>
          </div>
        </header>
        <div className="content-scroll">{children}</div>
      </main>
    </div>
  );
}
