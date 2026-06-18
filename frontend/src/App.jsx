import { useEffect, useMemo, useRef, useState } from 'react';
import { api, ROLE_PERMISSIONS } from './api/index.js';
import { AppShell } from './components/layout/AppShell.jsx';
import { Toast } from './components/common/Toast.jsx';
import { LoginPage } from './features/auth/LoginPage.jsx';
import { HrDashboard } from './features/dashboard/HrDashboard.jsx';
import { EmployeeDashboard } from './features/dashboard/EmployeeDashboard.jsx';
import { EmployeeListPage } from './features/employees/EmployeeListPage.jsx';
import { EmployeeDetailPage } from './features/employees/EmployeeDetailPage.jsx';
import { ProfileRequestsPage } from './features/profileRequests/ProfileRequestsPage.jsx';
import { ProfilePage } from './features/profile/ProfilePage.jsx';
import { MyRequestsPage } from './features/profile/MyRequestsPage.jsx';
import { UserManagementPage } from './features/users/UserManagementPage.jsx';

function hasPermission(role, key) {
  return ROLE_PERMISSIONS[key].includes(role);
}

function allowedRoute(route, user) {
  if (!user) return 'dashboard';
  if (route === 'employees' && !hasPermission(user.role, 'canViewEmployees')) return 'dashboard';
  if (route === 'detail' && !hasPermission(user.role, 'canViewEmployees')) return 'dashboard';
  if (route === 'requests' && !hasPermission(user.role, 'canReviewProfileRequests')) return 'dashboard';
  if (route === 'users' && !hasPermission(user.role, 'canManageUsers')) return 'dashboard';
  if (route === 'myrequests' && user.role !== 'employee') return 'dashboard';
  return route;
}

export function App() {
  const [user, setUser] = useState(null);
  const [currentEmployee, setCurrentEmployee] = useState(null);
  const [employees, setEmployees] = useState([]);
  const [profileRequests, setProfileRequests] = useState([]);
  const [users, setUsers] = useState([]);
  const [route, setRoute] = useState('dashboard');
  const [selectedEmployee, setSelectedEmployee] = useState(null);
  const [loading, setLoading] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [toast, setToast] = useState(null);
  const toastTimer = useRef(null);

  const ownRequests = useMemo(
    () => profileRequests.filter((request) => request.employeeId === currentEmployee?.id),
    [profileRequests, currentEmployee],
  );
  const pendingCount = (user?.role === 'employee' ? ownRequests : profileRequests).filter((request) => request.status === 'pending').length;

  function showToast(kind, message) {
    window.clearTimeout(toastTimer.current);
    setToast({ kind, message });
    toastTimer.current = window.setTimeout(() => setToast(null), 3200);
  }

  async function loadData(nextUser, preferredRoute = route) {
    const safeRoute = allowedRoute(preferredRoute, nextUser);
    const currentEmployeePromise = api.employees.get(nextUser.employeeId)
      .then((response) => response.employee);
    const employeesPromise = hasPermission(nextUser.role, 'canViewEmployees')
      ? api.employees.list().then((response) => response.employees)
      : currentEmployeePromise.then((employee) => [employee]);
    const requestsPromise = api.profileRequests.list()
      .then((response) => response.profileRequests)
      .catch(() => []);
    const usersPromise = hasPermission(nextUser.role, 'canManageUsers')
      ? api.users.list().then((response) => response.users)
      : Promise.resolve([]);

    const [nextCurrentEmployee, nextEmployees, nextRequests, nextUsers] = await Promise.all([
      currentEmployeePromise,
      employeesPromise,
      requestsPromise,
      usersPromise,
    ]);

    setCurrentEmployee(nextCurrentEmployee);
    setEmployees(nextEmployees);
    setProfileRequests(nextRequests);
    setUsers(nextUsers);
    setRoute(safeRoute);
    return { nextCurrentEmployee, nextEmployees, nextRequests, nextUsers };
  }

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
    return () => {
      alive = false;
      window.clearTimeout(toastTimer.current);
    };
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

  async function handleLogout() {
    await api.auth.logout();
    setUser(null);
    setCurrentEmployee(null);
    setEmployees([]);
    setProfileRequests([]);
    setUsers([]);
    setRoute('dashboard');
    setSelectedEmployee(null);
  }

  function handleRoute(nextRoute) {
    setRoute(allowedRoute(nextRoute, user));
    if (nextRoute !== 'detail') setSelectedEmployee(null);
  }

  async function openEmployee(id) {
    const response = await api.employees.get(id);
    setSelectedEmployee(response.employee);
    setRoute('detail');
  }

  async function createEmployee(payload) {
    await api.employees.create(payload);
    await loadData(user, 'employees');
    showToast('success', 'เพิ่มพนักงานเรียบร้อย');
  }

  async function updateEmployee(id, payload) {
    const response = await api.employees.update(id, payload);
    setSelectedEmployee(response.employee);
    await loadData(user, 'detail');
    showToast('success', 'บันทึกข้อมูลพนักงานแล้ว');
  }

  async function createProfileRequest(payload) {
    await api.profileRequests.create(payload);
    await loadData(user, route);
    showToast('success', 'ส่งคำขอแก้ไขเรียบร้อย');
  }

  async function reviewProfileRequest(id, status) {
    await api.profileRequests.update(id, { status });
    await loadData(user, 'requests');
    showToast(status === 'approved' ? 'success' : 'info', status === 'approved' ? 'อนุมัติคำขอแล้ว' : 'ปฏิเสธคำขอแล้ว');
  }

  async function createUser(payload) {
    await api.users.create(payload);
    await loadData(user, 'users');
    showToast('success', 'สร้างผู้ใช้งานเรียบร้อย');
  }

  async function updateUser(id, payload) {
    await api.users.update(id, payload);
    await loadData(user, 'users');
    showToast('info', 'ปรับสถานะบัญชีแล้ว');
  }

  if (!user) {
    return (
      <>
        <LoginPage onLogin={handleLogin} loading={loading} error={loginError} />
        <Toast toast={toast} onDismiss={() => setToast(null)} />
      </>
    );
  }

  const screen = route === 'dashboard'
    ? user.role === 'employee'
      ? <EmployeeDashboard employee={currentEmployee} profileRequests={ownRequests} onRoute={handleRoute} />
      : <HrDashboard employee={currentEmployee} employees={employees} profileRequests={profileRequests} onRoute={handleRoute} />
    : route === 'employees'
      ? <EmployeeListPage user={user} employees={employees} onOpenEmployee={openEmployee} onCreateEmployee={createEmployee} />
      : route === 'detail'
        ? <EmployeeDetailPage user={user} employee={selectedEmployee} onBack={() => handleRoute('employees')} onUpdateEmployee={updateEmployee} />
        : route === 'requests'
          ? <ProfileRequestsPage profileRequests={profileRequests} onReview={reviewProfileRequest} />
          : route === 'users'
            ? <UserManagementPage users={users} employees={employees} onCreateUser={createUser} onUpdateUser={updateUser} />
            : route === 'myrequests'
              ? <MyRequestsPage profileRequests={ownRequests} onNewRequest={() => handleRoute('profile')} />
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
      >
        {screen}
      </AppShell>
      <Toast toast={toast} onDismiss={() => setToast(null)} />
      {loading ? <div className="loading-veil">Loading...</div> : null}
    </>
  );
}
