import { useSearchParams } from 'react-router-dom';
import { Tabs } from '../../components/common/Tabs.jsx';

const ITEMS = [{ id: 'today', label: 'งานวันนี้' }, { id: 'insight', label: 'วิเคราะห์ยอดขาย' }];

/** The landing page's two-way switch; the view lives in `?view=insight` (absent = today's dashboard). */
export function useInsightView() {
  const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'insight' ? 'insight' : 'today';
  const setView = (next) => setParams((prev) => {
    const copy = new URLSearchParams(prev);
    if (next === 'insight') copy.set('view', 'insight'); else copy.delete('view');
    return copy;
  });
  return [view, setView];
}

/** The tabpanel the switch controls (Tabs points aria-controls at it): wraps the whole page body. */
export function ViewPanel({ view, children }) {
  return <div role="tabpanel" id={`landing-view-panel-${view}`} aria-labelledby={`landing-view-tab-${view}`}>{children}</div>;
}

export function InsightSwitch({ view, onChange }) {
  return <Tabs items={ITEMS} value={view} onChange={onChange} ariaLabel="มุมมองหน้าแรก" idPrefix="landing-view" onSurface />;
}
