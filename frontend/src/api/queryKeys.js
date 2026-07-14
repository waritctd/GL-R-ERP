// Central query-key factory for the shared server-state layer. Keeping keys in
// one place makes invalidation call sites unambiguous and typo-proof.
export const queryKeys = {
  currentEmployee: (id) => ['currentEmployee', id],
  employees: () => ['employees'],
  employeeDetail: (id) => ['employeeDetail', id],
  profileRequests: () => ['profileRequests'],
  dashboardSummary: () => ['dashboardSummary'],
  notifications: () => ['notifications'],
  leaveRequests: (filters = {}) => ['leave', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  leaveBalances: (employeeId, year) => ['leave', 'balances', employeeId, year],
  leaveEmployees: () => ['leave', 'employees'],
  leaveTypes: () => ['leave', 'types'],
  overtimeRequests: (filters = {}) => ['overtime', 'list', filters.from, filters.to, filters.status, filters.employeeId],
  overtimeEmployees: () => ['overtime', 'employees'],
  attendancePunches: (filters = {}) => ['attendance', 'punches', filters.from, filters.to, filters.employeeId, filters.limit],
  attendanceDevices: () => ['attendance', 'devices'],
  payrollCurrent: (payrollMonth) => ['payroll', 'current', payrollMonth],
};
