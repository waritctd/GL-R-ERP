import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { Tabs, TabPanel } from '../../components/common/Tabs.jsx';
import { ATTENDANCE_CALENDAR_TABS, resolveAttendanceCalendarTab } from './attendanceCalendarTabs.js';
import { HolidaysTab } from './HolidaysTab.jsx';
import { WorkSchedulesTab } from './WorkSchedulesTab.jsx';
import { AssignmentsTab } from './AssignmentsTab.jsx';

const TAB_ID_PREFIX = 'attendance-calendar';

/** PR #480 shipped a complete HR/CEO write API for hr.holiday and hr.work_schedule_assignment with no UI at
 * all. This page is that UI. */
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
