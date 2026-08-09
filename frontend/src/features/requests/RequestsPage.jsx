import { useSearchParams } from 'react-router-dom';
import { PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { cn } from '../../utils/cn.js';
import { OvertimePanel } from '../overtime/OvertimePanel.jsx';
import { SpecialMoneyPanel } from '../specialmoney/SpecialMoneyPanel.jsx';

const TABS = [
  { key: 'ot', label: 'ล่วงเวลา' },
  { key: 'welfare', label: 'สวัสดิการ / เงินพิเศษ' },
];

// Combined "คำขอ" page: one shell, two tabs (overtime + welfare/special-money), tab state
// carried in the query string so a tab is linkable/shareable/back-button-safe
// (?tab=ot | ?tab=welfare, default ot).
//
// Route note: the spec for this slice asked for this page at `/requests`, but
// that path is already ProfileRequestsPage's HR review-queue route, with
// several existing call sites (AppShell nav, HrDashboard, EmployeeDashboard,
// permissions.js). Reusing it would mean rewriting those unrelated pages, so
// this page is mounted at `/employee-requests` instead (App.jsx) -- see this
// branch's handoff doc for the full writeup, including the resulting known
// gap: SpecialMoneyService's notification emails hardcode "/requests" as the
// deep link (backend, out of scope for this slice), so those still land on
// the profile-requests queue rather than here.
//
// A third tab (attendance-correction, feat/attendance-correction-request) used to live here,
// with the same role-conditional-content shape the two tabs below still use -- no separate
// review-queue page, since that feature is a single CEO-only review stage with no manager
// routing to split out. It moved to /attendance instead
// (fix/attendance-correction-on-attendance-page): an employee now presses a button on the
// attendance page and a request modal pops up, and the CEO review queue sits below that page's
// own daily table, next to the data an approval actually writes into. `activeTab` below falls
// back to 'ot' for anything other than 'welfare' -- including a stale `?tab=attendance-correction`
// bookmark or deep link -- rather than erroring, which is deliberate graceful degradation, not
// an oversight.
export function RequestsPage({ user, currentEmployee, showToast }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const rawTab = searchParams.get('tab');
  const activeTab = rawTab === 'welfare' ? 'welfare' : 'ot';

  function selectTab(key) {
    const next = new URLSearchParams(searchParams);
    if (key === 'ot') next.delete('tab');
    else next.set('tab', key);
    setSearchParams(next, { replace: false });
  }

  return (
    <PageStack>
      {/* Renamed from "คำขอ" (owner, 2026-08-10). Both tabs are claims for extra pay — OT hours
          and welfare/special money — so the bare "คำขอ" said nothing that distinguished this page
          from /leave or the ขอแก้ไขเวลา flow, which are also คำขอ. The subtitle no longer has to
          carry the whole meaning, so it drops "ในที่เดียว": with the correction tab gone to
          /attendance (#643) there are only two tabs left, and "in one place" was describing a
          three-tab page that no longer exists. */}
      <PageHeader title="คำขอเงินพิเศษ" subtitle="ค่าล่วงเวลา และสวัสดิการ/เงินพิเศษ" />

      <div role="tablist" aria-label="ประเภทคำขอ" className="flex gap-2 border-b border-border">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.key}
            className={cn(
              // `appearance-none bg-transparent border-x-0 border-t-0 border-solid` FIRST, for the
              // same reason Tabs.jsx spells it out: no Tailwind preflight ships here, so a bare
              // <button> keeps the UA's chrome — measured on this tablist before the fix, the
              // active tab was `border-top/left: 2px outset rgb(79,70,229)` and both tabs sat on
              // `background: rgb(239,239,239)`. `border-b-2 border-primary` cannot say "bottom
              // only" when the UA has already supplied 2px on all four sides: the primary colour
              // landed on every edge, so the selected tab rendered as a blue RECTANGLE instead of
              // an underline, on a grey button face. Zeroing the other three edges (and the face)
              // is what leaves just the underline this was always meant to be.
              'appearance-none bg-transparent border-x-0 border-t-0 border-solid',
              // `min-h-11` (44px) for the mobile touch floor. Measured at 390px these rendered
              // 79x41 and 146x41 — `py-2.5` on a 16px line box lands 3px short, which is invisible
              // on a desktop pointer and is the difference between a comfortable and a fiddly tap
              // on a phone. Height only: the tablist is a horizontal row, so widening would push
              // the two tabs toward wrapping, and WCAG 2.5.8 is satisfied by the 44px height plus
              // the row's own spacing.
              'min-h-11 px-4 py-2.5 text-sm font-bold border-b-2 -mb-px transition-colors',
              activeTab === tab.key
                ? 'border-primary text-primary'
                : 'border-transparent text-icon-muted hover:text-text',
            )}
            onClick={() => selectTab(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'ot' ? (
        <OvertimePanel user={user} currentEmployee={currentEmployee} showToast={showToast} />
      ) : (
        <SpecialMoneyPanel user={user} currentEmployee={currentEmployee} showToast={showToast} />
      )}
    </PageStack>
  );
}
