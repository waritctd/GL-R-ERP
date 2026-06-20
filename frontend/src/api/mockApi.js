import { createDemoDatabase } from '../data/demoData.js';

const db = createDemoDatabase();
let sessionUser = null;

function delay(value) {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(structuredClone(value)), 140);
  });
}

function fail(message, status = 400) {
  const error = new Error(message);
  error.status = status;
  throw error;
}

function publicUser(user) {
  if (!user) return null;
  const { password, ...safe } = user;
  return safe;
}

function requireSession() {
  if (!sessionUser) fail('Not authenticated', 401);
  return sessionUser;
}

function hasRole(...roles) {
  const user = requireSession();
  if (!roles.includes(user.role)) fail('Forbidden', 403);
  return user;
}

function employeeWithRequestMeta(employee) {
  return {
    ...employee,
    pendingRequestCount: db.profileRequests.filter((request) => request.employeeId === employee.id && request.status === 'pending').length,
  };
}

function findEmployee(id) {
  const employee = db.employees.find((item) => item.id === Number(id));
  if (!employee) fail('Employee not found', 404);
  return employee;
}

function applyApprovedProfileRequest(request) {
  const employee = findEmployee(request.employeeId);
  if (request.fieldKey === 'phone') employee.phone = request.newValue;
  if (request.fieldKey === 'email') employee.email = request.newValue;
  if (request.fieldKey === 'address') employee.currentAddress = { ...employee.currentAddress, line1: request.newValue };
  if (request.fieldKey === 'emergency') {
    const [name, phone] = request.newValue.split('·').map((part) => part.trim());
    employee.emergencyContact = { ...employee.emergencyContact, name: name || request.newValue, phone: phone || employee.emergencyContact.phone };
  }
}

function createEmployeeRecord(payload) {
  const id = Math.max(...db.employees.map((employee) => employee.id)) + 1;
  const division = payload.divisionId || 'SAL';
  const department = payload.departmentTh || 'ขายปลีก';
  const statusId = payload.statusId || 'ACT';
  const statusMap = {
    ACT: ['ทำงานปกติ', 'success', true],
    PRB: ['ทดลองงาน', 'warning', true],
    RSG: ['ลาออก', 'danger', false],
  };
  const [statusTh, statusTone, active] = statusMap[statusId] ?? statusMap.ACT;

  return {
    id,
    code: payload.code || `GLR-${1000 + id}`,
    badge: payload.badge || `BC-${Date.now().toString().slice(-8)}`,
    nameTh: payload.nameTh,
    nameEn: payload.nameEn || payload.nameTh,
    nickName: payload.nickName || '',
    initials: payload.nameEn ? payload.nameEn.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase() : 'GL',
    avatarBg: '#e0e7ff',
    avatarFg: '#4338ca',
    titleTh: payload.titleTh || 'นาย',
    genderTh: payload.genderTh || 'ไม่ระบุ',
    birthDate: payload.birthDate || '1995-01-01',
    age: 31,
    nationality: 'ไทย',
    maritalStatus: 'โสด',
    email: payload.email,
    phone: payload.phone,
    divisionId: division,
    divisionTh: payload.divisionTh || division,
    divisionEn: '',
    departmentTh: department,
    positionTh: payload.positionTh || 'เจ้าหน้าที่',
    positionEn: '',
    level: payload.level || 'O2',
    locationTh: payload.locationTh || 'สำนักงานใหญ่ กรุงเทพฯ',
    statusId,
    statusTh,
    statusTone,
    active,
    payType: 'รายเดือน',
    salary: Number(payload.salary || 0),
    hireDate: payload.hireDate || new Date().toISOString().slice(0, 10),
    confirmationDate: payload.confirmationDate || null,
    reportsTo: payload.reportsTo || '-',
    bank: '',
    bankAccount: '',
    currentAddress: { line1: payload.address || '-', district: '', province: '', postalCode: '' },
    emergencyContact: { name: payload.emergencyName || '-', relationship: payload.emergencyRelationship || '-', phone: payload.emergencyPhone || '-' },
    education: [],
    assignments: [],
    salaryHistory: [],
    sensitive: {},
  };
}

export const api = {
  auth: {
    async login(payload) {
      const email = payload?.email?.trim().toLowerCase();
      const requestedRole = payload?.role;
      const user = requestedRole
        ? db.users.find((item) => item.role === requestedRole && item.active)
        : db.users.find((item) => item.email.toLowerCase() === email && item.active);

      if (!user) fail('Invalid email or inactive user', 401);
      if (!requestedRole && payload?.password !== user.password) fail('Invalid password', 401);

      sessionUser = user;
      return delay({ user: publicUser(user) });
    },
    async logout() {
      sessionUser = null;
      return delay({ ok: true });
    },
    async me() {
      return delay({ user: publicUser(requireSession()) });
    },
  },
  employees: {
    async list(params = {}) {
      hasRole('hr', 'director', 'admin');
      let employees = db.employees.map(employeeWithRequestMeta);
      if (params.search) {
        const query = params.search.toLowerCase();
        employees = employees.filter((employee) => (
          employee.nameTh.includes(params.search)
          || employee.nameEn.toLowerCase().includes(query)
          || employee.code.toLowerCase().includes(query)
          || employee.nickName.includes(params.search)
        ));
      }
      if (params.divisionId) employees = employees.filter((employee) => employee.divisionId === params.divisionId);
      if (params.departmentTh) employees = employees.filter((employee) => employee.departmentTh === params.departmentTh);
      if (params.statusId) employees = employees.filter((employee) => employee.statusId === params.statusId);
      if (params.active === 'true') employees = employees.filter((employee) => employee.active);
      if (params.active === 'false') employees = employees.filter((employee) => !employee.active);
      return delay({ employees });
    },
    async create(payload) {
      hasRole('hr', 'admin');
      const employee = createEmployeeRecord(payload);
      db.employees.unshift(employee);
      return delay({ employee });
    },
    async get(id) {
      const user = requireSession();
      const employee = findEmployee(id);
      if (user.role === 'employee' && user.employeeId !== employee.id) fail('Forbidden', 403);
      if (user.role === 'supervisor' && user.employeeId !== employee.id) fail('Forbidden', 403);
      return delay({ employee: employeeWithRequestMeta(employee) });
    },
    async update(id, payload) {
      hasRole('hr', 'admin');
      const employee = findEmployee(id);
      Object.assign(employee, payload);
      if (payload.statusId) {
        const status = { ACT: ['ทำงานปกติ', 'success', true], PRB: ['ทดลองงาน', 'warning', true], RSG: ['ลาออก', 'danger', false] }[payload.statusId];
        if (status) {
          employee.statusTh = status[0];
          employee.statusTone = status[1];
          employee.active = status[2];
        }
      }
      return delay({ employee: employeeWithRequestMeta(employee) });
    },
  },
  profileRequests: {
    async list() {
      const user = requireSession();
      const rows = user.role === 'hr' || user.role === 'admin'
        ? db.profileRequests
        : db.profileRequests.filter((request) => request.employeeId === user.employeeId);
      const profileRequests = rows.map((request) => ({
        ...request,
        employee: db.employees.find((employee) => employee.id === request.employeeId),
      }));
      return delay({ profileRequests });
    },
    async create(payload) {
      const user = hasRole('employee');
      const employee = findEmployee(user.employeeId);
      const request = {
        id: Math.max(...db.profileRequests.map((item) => item.id)) + 1,
        employeeId: employee.id,
        fieldKey: payload.fieldKey,
        fieldLabel: payload.fieldLabel,
        oldValue: payload.oldValue,
        newValue: payload.newValue,
        requestedBy: employee.nameTh,
        requestedAt: new Date().toISOString().slice(0, 10),
        status: 'pending',
      };
      db.profileRequests.unshift(request);
      return delay({ profileRequest: { ...request, employee } });
    },
    async update(id, payload) {
      hasRole('hr');
      const request = db.profileRequests.find((item) => item.id === Number(id));
      if (!request) fail('Profile request not found', 404);
      if (request.status !== 'pending') fail('Profile request has already been reviewed', 409);
      request.status = payload.status;
      request.reviewedAt = new Date().toISOString().slice(0, 10);
      if (request.status === 'approved') applyApprovedProfileRequest(request);
      return delay({ profileRequest: { ...request, employee: findEmployee(request.employeeId) } });
    },
  },
  users: {
    async list() {
      hasRole('admin');
      return delay({ users: db.users.map(publicUser) });
    },
    async create(payload) {
      hasRole('admin');
      const employee = payload.employeeId ? findEmployee(payload.employeeId) : null;
      const user = {
        id: Math.max(...db.users.map((item) => item.id)) + 1,
        email: payload.email,
        password: payload.password || 'demo1234',
        name: employee?.nameTh || payload.name,
        role: payload.role || 'employee',
        employeeId: employee?.id ?? payload.employeeId,
        active: true,
        createdAt: new Date().toISOString().slice(0, 10),
      };
      db.users.unshift(user);
      return delay({ user: publicUser(user) });
    },
    async update(id, payload) {
      hasRole('admin');
      const user = db.users.find((item) => item.id === Number(id));
      if (!user) fail('User not found', 404);
      Object.assign(user, payload);
      return delay({ user: publicUser(user) });
    },
  },
};
