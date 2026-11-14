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

// Combined "คำขอ" page: one shell, two tabs (overtime + welfare/special-money), tab state carried in the query
// string so a tab is linkable/shareable/back-button-safe (?tab=ot | ?tab=welfare, default ot).
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
      {/* Renamed from "คำขอ" (owner, 2026-08-10). The subtitle no longer has to carry the whole meaning, so it drops
       * "ในที่เดียว": with the correction tab gone to /attendance (#643) there are only two tabs left, and "in one
       * place" was describing a three-tab page that no longer exists. */}
      <PageHeader title="คำขอเงินพิเศษ" subtitle="ค่าล่วงเวลา และสวัสดิการ/เงินพิเศษ" />

      <div role="tablist" aria-label="ประเภทคำขอ" className="flex gap-2 border-b border-border">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.key}
            className={cn(
              // `appearance-none bg-transparent border-x-0 border-t-0 border-solid` FIRST, for the same reason Tabs.jsx
              // spells it out: no Tailwind preflight ships here, so a bare <button> keeps the UA's chrome.
              'appearance-none bg-transparent border-x-0 border-t-0 border-solid',
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
