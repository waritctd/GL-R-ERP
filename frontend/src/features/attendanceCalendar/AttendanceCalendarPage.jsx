import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { Tabs, TabPanel } from '../../components/common/Tabs.jsx';
import { ATTENDANCE_CALENDAR_TABS, resolveAttendanceCalendarTab } from './attendanceCalendarTabs.js';
import { HolidaysTab } from './HolidaysTab.jsx';
import { WorkSchedulesTab } from './WorkSchedulesTab.jsx';
import { AssignmentsTab } from './AssignmentsTab.jsx';

const TAB_ID_PREFIX = 'attendance-calendar';

/**
 * PR #480 shipped a complete HR/CEO write API for hr.holiday and hr.work_schedule_assignment with
 * no UI at all — HR could not add a company holiday, correct a BOT-fetched one, reassign a
 * department's work schedule, or trigger the BOT import, and `hr.holiday` feeds both
 * `LeaveDayMath`'s working-day counting and attendance's HOLIDAY status. This page is that UI.
 *
 * Route-gated to hr/ceo only (`canManageAttendanceCalendar` — see app/permissions.js's PATH_GUARDS
 * and App.jsx's route registration), mirroring HolidayController / WorkScheduleController /
 * WorkScheduleAssignmentController's `requireAnyRole(user, "hr", "ceo")` exactly. That route guard
 * is FRONTEND GATING ONLY — the backend already enforces these role checks independently of
 * anything here; nothing in this page widens or narrows what the real Java services accept.
 *
 * Same `?tab=` shell convention as LeaveSurfacePage.jsx / TicketDetailPage.jsx: the URL is the
 * single source of truth for the active tab, written with `{ replace: true }` so switching tabs
 * never spams browser history, and an absent/unknown value falls back to วันหยุด via
 * `resolveAttendanceCalendarTab`.
 */
export function AttendanceCalendarPage({ showToast }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = resolveAttendanceCalendarTab(searchParams.get('tab'));

  function setActiveTab(tabId) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('tab', tabId);
      return next;
    }, { replace: true });
  }

  return (
    <PageStack>
      <PageHeader
        title="ปฏิทินวันหยุดและตารางเวลาทำงาน"
        subtitle="วันหยุดบริษัท ตารางเวลาทำงาน และการมอบหมายตารางให้พนักงาน/แผนก/ฝ่าย"
      />

      <Tabs
        items={ATTENDANCE_CALENDAR_TABS}
        value={activeTab}
        onChange={setActiveTab}
        ariaLabel="ปฏิทินวันหยุดและตารางเวลาทำงาน"
        idPrefix={TAB_ID_PREFIX}
      />

      <TabPanel id="holidays" idPrefix={TAB_ID_PREFIX} active={activeTab === 'holidays'}>
        <HolidaysTab showToast={showToast} />
      </TabPanel>

      <TabPanel id="workSchedules" idPrefix={TAB_ID_PREFIX} active={activeTab === 'workSchedules'}>
        <WorkSchedulesTab />
      </TabPanel>

      <TabPanel id="assignments" idPrefix={TAB_ID_PREFIX} active={activeTab === 'assignments'}>
        <AssignmentsTab showToast={showToast} />
      </TabPanel>
    </PageStack>
  );
}
