import { ROLE_PERMISSIONS } from '../../api/index.js';
import { roleLabel } from '../../utils/format.js';
import { Avatar } from '../common/Avatar.jsx';
import { Icon } from '../common/Icon.jsx';
import { Sidebar } from './Sidebar.jsx';

function can(role, key) {
  return ROLE_PERMISSIONS[key].includes(role);
}

export function AppShell({ user, employee, route, onRoute, onLogout, pendingRequestCount, children }) {
  const navItems = [
    { route: 'dashboard', label: 'แดชบอร์ด', helper: 'Dashboard', icon: 'dashboard', show: true },
    { route: 'employees', label: 'พนักงานทั้งหมด', helper: 'Employees', icon: 'users', show: can(user.role, 'canViewEmployees') },
    { route: 'requests', label: 'คำขอแก้ไขข้อมูล', helper: 'Profile requests', icon: 'clipboard', show: can(user.role, 'canReviewProfileRequests'), badge: pendingRequestCount },
    { route: 'users', label: 'จัดการผู้ใช้งาน', helper: 'Users', icon: 'userCog', show: can(user.role, 'canManageUsers') },
    { route: 'profile', label: 'ข้อมูลของฉัน', helper: 'My profile', icon: 'badge', show: true },
    { route: 'myrequests', label: 'คำขอของฉัน', helper: 'My requests', icon: 'clock', show: user.role === 'employee', badge: pendingRequestCount },
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
