// Synthetic response of GET /api/people-insights (public repo: invented people, round numbers). Shared by the
// aggregation, page and mock-API tests. 3 = a time-exempt person: the real server sends NO attendance for them; the
// first row below is a deliberately stray one, to show the aggregation never counts it.
export const PEOPLE_FIXTURE = {
  asOf: '2026-04-10',
  year: 2026,
  scopes: [{ id: 'all', label: 'ทั้งบริษัท' }, { id: '7', label: 'ทีมทดสอบ' }],
  scope: { id: 'all', label: 'ทั้งบริษัท' },
  leaveTypes: [
    { code: 'SICK', nameTh: 'ลาป่วย', annualQuotaDays: 10 },
    { code: 'PERSONAL', nameTh: 'ลากิจ', annualQuotaDays: 4 },
    { code: 'VACATION', nameTh: 'ลาพักร้อน', annualQuotaDays: 6 },
  ],
  people: [
    { employeeId: 1, employeeCode: 'T001', nickname: 'เอ', timeExempt: false },
    { employeeId: 2, employeeCode: 'T002', nickname: 'บี', timeExempt: false },
    { employeeId: 3, employeeCode: 'T003', nickname: 'ซี', timeExempt: true },
  ],
  leave: [
    { employeeId: 1, month: '2026-03', leaveTypeCode: 'SICK', days: 2, requests: 1, sickEdgeDays: 1, lateFiled: 1, unpaidDays: 0 },
    { employeeId: 1, month: '2026-04', leaveTypeCode: 'SICK', days: 11, requests: 2, sickEdgeDays: 5, lateFiled: 0, unpaidDays: 0 },
    { employeeId: 2, month: '2026-04', leaveTypeCode: 'PERSONAL', days: 1, requests: 1, sickEdgeDays: 0, lateFiled: 0, unpaidDays: 1 },
    { employeeId: 3, month: '2026-04', leaveTypeCode: 'VACATION', days: 2, requests: 1, sickEdgeDays: 0, lateFiled: 0, unpaidDays: 0 },
  ],
  attendance: [
    { employeeId: 3, month: '2026-04', scanDays: 5, lateDays: 5, lateMinutes: 500, noCheckOut: 5, earlyLeave: 5, morningScanDays: 5, avgCheckInMinutes: 700 },
    { employeeId: 1, month: '2026-03', scanDays: 20, lateDays: 2, lateMinutes: 30, noCheckOut: 0, earlyLeave: 1, morningScanDays: 1, avgCheckInMinutes: 480 },
    { employeeId: 1, month: '2026-04', scanDays: 5, lateDays: 1, lateMinutes: 20, noCheckOut: 1, earlyLeave: 0, morningScanDays: 5, avgCheckInMinutes: 540 },
    { employeeId: 2, month: '2026-04', scanDays: 5, lateDays: 0, lateMinutes: 0, noCheckOut: 0, earlyLeave: 2, morningScanDays: 5, avgCheckInMinutes: 510 },
  ],
  // Days charged to the quota year (not the start-date year); one row per person x leave type.
  quotaUsage: [
    { employeeId: 1, leaveTypeCode: 'SICK', days: 13 },
    { employeeId: 2, leaveTypeCode: 'PERSONAL', days: 1 },
    { employeeId: 3, leaveTypeCode: 'VACATION', days: 2 },
  ],
  teams: [{ id: '7', label: 'ทีมทดสอบ', headNicknames: ['เอ'], memberIds: [2, 3] }],
};
