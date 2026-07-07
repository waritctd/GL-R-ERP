import { useState } from 'react';
import { api } from '../api/index.js';
import { allowedRoute, hasPermission } from '../app/permissions.js';

export function useHrData({ user, showToast }) {
  const [currentEmployee, setCurrentEmployee] = useState(null);
  const [employees, setEmployees] = useState([]);
  const [profileRequests, setProfileRequests] = useState([]);
  const [dashboardSummary, setDashboardSummary] = useState(null);
  const [route, setRoute] = useState('dashboard');
  const [selectedEmployee, setSelectedEmployee] = useState(null);

  async function loadData(nextUser, preferredRoute = route) {
    const safeRoute = allowedRoute(preferredRoute, nextUser);
    const currentEmployeePromise = nextUser.employeeId
      ? api.employees.get(nextUser.employeeId).then((response) => response.employee)
      : Promise.resolve(null);
    const employeesPromise = hasPermission(nextUser.role, 'canViewEmployees')
      ? api.employees.list().then((response) => response.employees)
      : currentEmployeePromise.then((employee) => (employee ? [employee] : []));
    const requestsPromise = api.profileRequests.list()
      .then((response) => response.profileRequests)
      .catch(() => []);
    const dashboardPromise = api.dashboard?.summary
      ? api.dashboard.summary()
        .then((response) => response?.summary ?? response ?? null)
        .catch(() => null)
      : Promise.resolve(null);

    const [nextCurrentEmployee, nextEmployees, nextRequests, nextDashboardSummary] = await Promise.all([
      currentEmployeePromise,
      employeesPromise,
      requestsPromise,
      dashboardPromise,
    ]);

    setCurrentEmployee(nextCurrentEmployee);
    setEmployees(nextEmployees);
    setProfileRequests(nextRequests);
    setDashboardSummary(nextDashboardSummary);
    setRoute(safeRoute);
    return { nextCurrentEmployee, nextEmployees, nextRequests, nextDashboardSummary };
  }

  function resetData() {
    setCurrentEmployee(null);
    setEmployees([]);
    setProfileRequests([]);
    setDashboardSummary(null);
    setRoute('dashboard');
    setSelectedEmployee(null);
  }

  function routeTo(nextRoute) {
    setRoute(allowedRoute(nextRoute, user));
    if (nextRoute !== 'detail') setSelectedEmployee(null);
  }

  async function openEmployee(id) {
    const response = await api.employees.get(id);
    setSelectedEmployee(response.employee);
    setRoute('detail');
  }

  async function createEmployee(payload) {
    const response = await api.employees.create(payload);
    setEmployees((current) => [response.employee, ...current]);
    setRoute(allowedRoute('employees', user));
    showToast('success', 'เพิ่มพนักงานเรียบร้อย');
  }

  async function updateEmployee(id, payload) {
    const response = await api.employees.update(id, payload);
    setSelectedEmployee(response.employee);
    setEmployees((current) => current.map((employee) => (employee.id === id ? response.employee : employee)));
    setCurrentEmployee((current) => (current?.id === id ? response.employee : current));
    showToast('success', 'บันทึกข้อมูลพนักงานแล้ว');
  }

  async function createProfileRequest(payload) {
    const response = await api.profileRequests.create(payload);
    setProfileRequests((current) => [response.profileRequest, ...current]);
    showToast('success', 'ส่งคำขอแก้ไขเรียบร้อย');
  }

  async function reviewProfileRequest(id, status) {
    const response = await api.profileRequests.update(id, { status });
    setProfileRequests((current) => current.map((request) => (request.id === id ? response.profileRequest : request)));
    showToast(status === 'approved' ? 'success' : 'info', status === 'approved' ? 'อนุมัติคำขอแล้ว' : 'ปฏิเสธคำขอแล้ว');
  }

  return {
    currentEmployee,
    employees,
    profileRequests,
    dashboardSummary,
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
  };
}
