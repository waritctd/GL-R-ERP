import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { Tabs } from '../../components/common/Tabs.jsx';
import { SalesInsights } from './SalesInsights.jsx';

const ITEMS = [{ id: 'today', label: 'งานวันนี้' }, { id: 'insight', label: 'วิเคราะห์ยอดขาย' }];

/** The landing page's two-way switch; the view lives in `?view=insight` (absent = today's dashboard). */
function useInsightView() {
  const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'insight' ? 'insight' : 'today';
  const setView = (next) => setParams((prev) => {
    const copy = new URLSearchParams(prev);
    if (next === 'insight') copy.set('view', 'insight'); else copy.delete('view');
    return copy;
  });
  return [view, setView];
}

/**
 * Landing page with the today / insight switch: `renderToday(switcher)` draws today's dashboard, `?view=insight` the
 * sales-analytics snapshot. The tabpanel wraps the whole page body (Tabs points aria-controls at it).
 */
export function InsightLanding({ title, user, showToast, renderToday }) {
  const [view, setView] = useInsightView();
  const switcher = <Tabs items={ITEMS} value={view} onChange={setView} ariaLabel="มุมมองหน้าแรก" idPrefix="landing-view" onSurface />;
  return (
    <div role="tabpanel" id={`landing-view-panel-${view}`} aria-labelledby={`landing-view-tab-${view}`}>
      {view === 'insight' ? (
        <PageStack>
          <PageHeader title={title} actions={switcher} />
          <SalesInsights user={user} showToast={showToast} />
        </PageStack>
      ) : renderToday(switcher)}
    </div>
  );
}
