// Tab metadata for /settings/attendance-calendar (attendance calendar admin — PR #480 shipped the
// HR/CEO write API for hr.holiday and hr.work_schedule_assignment with no UI at all; this branch is
// that UI). Mirrors features/tickets/ticketDetailTabs.js's "tab metadata lives in its own tested
// module" pattern, simplified: every tab here is visible to anyone who reaches the page at all,
// because the ROUTE itself is already gated to hr/ceo (canManageAttendanceCalendar — see
// app/permissions.js's PATH_GUARDS and api/routes.js's ROLE_PERMISSIONS, mirroring
// HolidayController/WorkScheduleController/WorkScheduleAssignmentController's
// requireAnyRole(user, "hr", "ceo") exactly). There is no narrower per-tab audience the way
// ticketDetailTabs.js has to express, so this module carries none of that machinery — just the
// ordered id/label/helper list and a safe-fallback resolver for the `?tab=` query param.
export const ATTENDANCE_CALENDAR_TABS = [
  { id: 'holidays', label: 'วันหยุด', helper: 'hr.holiday — ธปท. + บริษัท' },
  { id: 'workSchedules', label: 'ตารางงาน', helper: 'hr.work_schedule (อ่านอย่างเดียว)' },
  { id: 'assignments', label: 'การมอบหมาย', helper: 'hr.work_schedule_assignment' },
];

export const DEFAULT_ATTENDANCE_CALENDAR_TAB_ID = 'holidays';

/**
 * `tabId` if it is one of the known tabs, else `DEFAULT_ATTENDANCE_CALENDAR_TAB_ID` — an absent,
 * unknown, or hand-edited `?tab=` value never renders a blank panel, it silently falls back to
 * วันหยุด. Same fallback contract as `resolveTicketDetailTab`/`resolveLeaveSurfaceTab`.
 */
export function resolveAttendanceCalendarTab(tabId) {
  return ATTENDANCE_CALENDAR_TABS.some((tab) => tab.id === tabId)
    ? tabId
    : DEFAULT_ATTENDANCE_CALENDAR_TAB_ID;
}
