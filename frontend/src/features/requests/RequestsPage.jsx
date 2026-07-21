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

// Combined "คำขอ" page: one shell, two tabs (overtime + welfare/special-money),
// tab state carried in the query string so a tab is linkable/shareable/back-
// button-safe (?tab=ot | ?tab=welfare, default ot).
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
      <PageHeader title="คำขอ" subtitle="ล่วงเวลา และ สวัสดิการ/เงินพิเศษ ในที่เดียว" />

      <div role="tablist" aria-label="ประเภทคำขอ" className="flex gap-2 border-b border-border">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.key}
            className={cn(
              'px-4 py-2.5 text-sm font-bold border-b-2 -mb-px transition-colors',
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
