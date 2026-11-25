import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { Tabs } from '../../components/common/Tabs.jsx';
import { SalesInsights } from './SalesInsights.jsx';
import { WeeklyReport } from './WeeklyReport.jsx';

const TODAY = { id: 'today', label: 'งานวันนี้' };
const REPORT = { id: 'report', label: 'รายงานสัปดาห์' };
const INSIGHT = { id: 'insight', label: 'วิเคราะห์ยอดขาย' };

/** The landing page's view switch; the view lives in `?view=report|insight` (absent = today's dashboard). Leaving the
 * report drops its own `week` / `rep` params. */
function useLandingView(allowed) {
  const [params, setParams] = useSearchParams();
  const asked = params.get('view');
  const view = allowed.includes(asked) ? asked : 'today';
  const setView = (next) => setParams((prev) => {
    const copy = new URLSearchParams(prev);
    if (next === 'today') copy.delete('view'); else copy.set('view', next);
    copy.delete('week');
    copy.delete('rep');
    return copy;
  });
  return [view, setView];
}

/**
 * Landing page with the today / weekly report / insight switch: `renderToday(switcher, panelProps)` draws today's
 * dashboard, `?view=report` the weekly report, `?view=insight` the sales-analytics snapshot (`insight={false}` for
 * roles without it, the sales rep). The tablist lives in the header; the tabpanel (`panelProps`) wraps only the body
 * below it, so the tablist is never inside the panel it controls.
 */
export function InsightLanding({ title, user, renderToday, insight = true }) {
  const items = insight ? [TODAY, REPORT, INSIGHT] : [TODAY, REPORT];
  const [view, setView] = useLandingView(items.map((item) => item.id));
  const switcher = <Tabs items={items} value={view} onChange={setView} ariaLabel="มุมมองหน้าแรก" idPrefix="landing-view" onSurface tabClassName="mobile:min-h-11" />;
  const panelProps = { role: 'tabpanel', id: `landing-view-panel-${view}`, 'aria-labelledby': `landing-view-tab-${view}` };
  if (view === 'today') return renderToday(switcher, panelProps);
  return (
    <PageStack>
      <PageHeader title={title} actions={switcher} />
      <div {...panelProps}>{view === 'report' ? <WeeklyReport user={user} /> : <SalesInsights user={user} />}</div>
    </PageStack>
  );
}
