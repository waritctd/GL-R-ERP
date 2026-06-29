import { useEffect, useMemo, useState } from 'react';
import { api } from './api/index.js';
import { AppShell } from './components/layout/AppShell.jsx';
import { Toast } from './components/common/Toast.jsx';
import { LoginPage } from './features/auth/LoginPage.jsx';
import { ChangePasswordModal } from './features/auth/ChangePasswordModal.jsx';
import { HrDashboard } from './features/dashboard/HrDashboard.jsx';
import { EmployeeDashboard } from './features/dashboard/EmployeeDashboard.jsx';
import { TicketDashboard } from './features/dashboard/TicketDashboard.jsx';
import { EmployeeListPage } from './features/employees/EmployeeListPage.jsx';
import { EmployeeDetailPage } from './features/employees/EmployeeDetailPage.jsx';
import { ProfileRequestsPage } from './features/profileRequests/ProfileRequestsPage.jsx';
import { ProfilePage } from './features/profile/ProfilePage.jsx';
import { MyRequestsPage } from './features/profile/MyRequestsPage.jsx';
import { AttendancePage } from './features/attendance/AttendancePage.jsx';
import { TicketListPage } from './features/tickets/TicketListPage.jsx';
import { TicketDetailPage } from './features/tickets/TicketDetailPage.jsx';
import { CommissionPage } from './features/commissions/CommissionPage.jsx';
import { useHrData } from './hooks/useHrData.js';
import { useToast } from './hooks/useToast.js';
import { hasPermission } from './app/permissions.js';

export function App() {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [changingPassword, setChangingPassword] = useState(false);
  const [selectedTicket, setSelectedTicket] = useState(null);
  const { toast, showToast, dismissToast } = useToast();
  const {
    currentEmployee,
    employees,
    profileRequests,
    route,
    selectedEmployee,
    loadData,
    resetData,
    routeTo,
    openEmployee,
    createEmployee,
    updateEmployee,
    createProfileRequest,
    reviewProfileRequest,
  } = useHrData({ user, showToast });

  const ownRequests = useMemo(
    () => profileRequests.filter((request) => request.employeeId === currentEmployee?.id),
    [profileRequests, currentEmployee],
  );
  const isEmployeeExperience = hasPermission(user?.role, 'canUseEmployeeExperience');
  const pendingCount = (isEmployeeExperience ? ownRequests : profileRequests).filter((request) => request.status === 'pending').length;

  useEffect(() => {
    let alive = true;
    async function restoreSession() {
      try {
        const response = await api.auth.me();
        if (!alive) return;
        setUser(response.user);
        await loadData(response.user, 'dashboard');
      } catch {
        if (alive) setUser(null);
      }
    }
    restoreSession();
    return () => { alive = false; };
  }, []);

  async function handleLogin(payload) {
    setLoading(true);
    setLoginError('');
    try {
      const response = await api.auth.login(payload);
      setUser(response.user);
      await loadData(response.user, 'dashboard');
      showToast('success', 'เข้าสู่ระบบสำเร็จ');
    } catch (error) {
      setLoginError(error.message || 'เข้าสู่ระบบไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  async function handleChangePassword(payload) {
    setChangingPassword(true);
    try {
      const response = await api.auth.changePassword(payload);
      setUser(response.user);
      showToast('success', 'เปลี่ยนรหัสผ่านเรียบร้อย');
    } finally {
      setChangingPassword(false);
    }
  }

  async function handleLogout() {
    await api.auth.logout();
    setUser(null);
    setSelectedTicket(null);
    resetData();
  }

  function handleRoute(nextRoute) {
    if (nextRoute !== 'ticket-detail') setSelectedTicket(null);
    routeTo(nextRoute);
  }

  function openTicket(id) {
    setSelectedTicket(id);
    routeTo('ticket-detail');
  }

  if (!user) {
    return (
      <>
        <LoginPage onLogin={handleLogin} loading={loading} error={loginError} />
        <Toast toast={toast} onDismiss={dismissToast} />
      </>
    );
  }

  if (user.mustChangePassword) {
    return (
      <>
        <ChangePasswordModal
          forced
          loading={changingPassword}
          onSubmit={handleChangePassword}
          onLogout={handleLogout}
        />
        <Toast toast={toast} onDismiss={dismissToast} />
      </>
    );
  }

  const screen = route === 'dashboard'
    ? <EmployeeDashboard employee={currentEmployee} profileRequests={ownRequests} onRoute={handleRoute} />
    : route === 'hr-dashboard'
      ? <HrDashboard employee={currentEmployee} employees={employees} profileRequests={profileRequests} onRoute={handleRoute} />
    : route === 'ticket-dashboard'
      ? <TicketDashboard user={user} employee={currentEmployee} onOpenTicket={openTicket} showToast={showToast} />
    : route === 'employees'
      ? <EmployeeListPage user={user} employees={employees} onOpenEmployee={openEmployee} onCreateEmployee={createEmployee} />
      : route === 'detail'
        ? <EmployeeDetailPage user={user} employee={selectedEmployee} onBack={() => handleRoute('employees')} onUpdateEmployee={updateEmployee} />
        : route === 'requests'
          ? <ProfileRequestsPage profileRequests={profileRequests} onReview={reviewProfileRequest} />
          : route === 'myrequests'
            ? <MyRequestsPage profileRequests={ownRequests} onNewRequest={() => handleRoute('profile')} />
            : route === 'attendance'
              ? <AttendancePage user={user} employees={employees} showToast={showToast} />
              : route === 'commissions'
                ? <CommissionPage user={user} showToast={showToast} />
              : route === 'tickets'
                ? <TicketListPage user={user} onOpenTicket={openTicket} showToast={showToast} />
                : route === 'ticket-detail'
                  ? <TicketDetailPage user={user} ticketId={selectedTicket} onBack={() => handleRoute('tickets')} showToast={showToast} />
                  : <ProfilePage user={user} employee={currentEmployee} profileRequests={ownRequests} onCreateRequest={createProfileRequest} onRoute={handleRoute} />;

  return (
    <>
      <AppShell
        user={user}
        employee={currentEmployee}
        route={route}
        onRoute={handleRoute}
        onLogout={handleLogout}
        pendingRequestCount={pendingCount}
        onOpenTicket={openTicket}
      >
        {screen}
      </AppShell>
      <Toast toast={toast} onDismiss={dismissToast} />
      {loading ? <div className="loading-veil">Loading...</div> : null}
    </>
  );
}
