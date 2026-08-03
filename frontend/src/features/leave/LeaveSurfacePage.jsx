import { useEffect, useMemo, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { Tabs, TabPanel } from '../../components/common/Tabs.jsx';
import {
  LEAVE_SURFACE_TABS, resolveLeaveSurfaceTab, visibleLeaveSurfaceTabIds,
} from './leaveSurfaceTabs.js';
import { MyLeaveTab } from './MyLeaveTab.jsx';
import { ReviewQueueTab } from './ReviewQueueTab.jsx';

const TAB_ID_PREFIX = 'leave-surface';

/**
 * Leave-surface IA rebuild, Phase A1: the thin tabbed shell that replaces the pre-A1
 * LeavePage.jsx's single 1000+ line component. Same convention as TicketDetailPage.jsx:
 * `?tab=` in the URL is the single source of truth (written with `{ replace: true }` so
 * switching tabs never spams browser history), an absent/unknown/currently-hidden value
 * falls back to the default via `resolveLeaveSurfaceTab`, and only the active `<TabPanel>`
 * mounts.
 */
export function LeaveSurfacePage({ user, currentEmployee, showToast }) {
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();

  function setActiveTab(tabId) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('tab', tabId);
      return next;
    }, { replace: true });
  }

  // Feeds ONLY the "รอพิจารณา" tab's visibility check (leaveSurfaceTabs.js's `review.isVisible`)
  // -- an unfiltered read so a manager whose only actionable request happens to fall outside
  // whatever date range MyLeaveTab's own filters currently have applied still sees the tab.
  // Shares its cache key with ReviewQueueTab.jsx's own query (both read
  // `queryKeys.leaveRequests({})`), so mounting that tab costs no extra request once this one has
  // already landed. TODO(A0): once GET /api/leave/review-summary lands, swap this for that
  // cheaper, purpose-built endpoint instead of the full request list.
  const reviewSignalQuery = useQuery({
    queryKey: queryKeys.leaveRequests({}),
    queryFn: () => api.leave.list({}).then((response) => response.requests || []),
  });
  const reviewSignalRequests = useMemo(() => reviewSignalQuery.data ?? [], [reviewSignalQuery.data]);

  const visibleTabIds = visibleLeaveSurfaceTabIds(user, reviewSignalRequests);
  // Plain (non-memoized) filter: `visibleTabIds` is itself freshly computed every render already
  // (it's not state), so memoizing this derived filter on top of it would only add an
  // (unusable, non-primitive) dependency without saving any real work -- LEAVE_SURFACE_TABS has
  // at most 3 entries.
  const visibleTabs = LEAVE_SURFACE_TABS.filter((tab) => visibleTabIds.includes(tab.id));
  const activeTab = resolveLeaveSurfaceTab(searchParams.get('tab'), visibleTabIds);

  // The sticky header's "ยื่นคำขอลา" CTA switches to the `me` tab (if not already there) and
  // focuses the request-form panel once it has mounted. TODO(A2): once the submit form moves to
  // its own `/leave/new` route, this becomes a plain navigate() and the pendingFocusRef/effect
  // pair below goes away entirely.
  const pendingFocusRef = useRef(false);
  const requestFormAnchorRef = useRef(null);

  function focusRequestForm() {
    const node = requestFormAnchorRef.current;
    if (!node) return;
    // Same defensive guard as TicketDetailPage.jsx's `focusFirstInvalid` -- `scrollIntoView` is
    // absent under jsdom (no test-environment stub), so an unguarded call breaks every test that
    // exercises this path rather than only skipping the (purely cosmetic) scroll.
    if (typeof node.scrollIntoView === 'function') node.scrollIntoView({ behavior: 'smooth', block: 'start' });
    node.focus();
  }

  function openRequestForm() {
    if (activeTab === 'me') {
      focusRequestForm();
      return;
    }
    pendingFocusRef.current = true;
    setActiveTab('me');
  }

  // Mirrors TicketDetailPage.jsx's pendingTabActionRef convention: `activeTab` only changes once
  // the URL update above has actually committed, and by then MyLeaveTab (and its form anchor ref)
  // has already mounted in the SAME commit -- so this always finds a live target, never a stale
  // one. Intentionally keyed on `activeTab` alone: the pending flag is read fresh from the ref
  // (never a dependency), so adding it would replay a stale queued focus on every unrelated render.
  useEffect(() => {
    if (activeTab === 'me' && pendingFocusRef.current) {
      pendingFocusRef.current = false;
      focusRequestForm();
    }
  }, [activeTab]);

  function refreshAll() {
    queryClient.invalidateQueries({ queryKey: ['leave'] });
  }

  return (
    // `relative` on PageStack: it is `.content-scroll`'s direct child and the
    // only element between it and the 16-field request form's
    // `FileUploadField` (a visually-hidden `<input type="file"
    // className="sr-only ...">`, positioned `absolute` by that Tailwind
    // utility -- FileUploadField.jsx:67). None of the wrappers in between
    // (form/section/TabPanel/PageStack/.content-scroll itself) set
    // `position`, so with no positioned ancestor the input's containing
    // block bubbled all the way up to the initial containing block (the
    // document) instead of stopping at `.content-scroll`. An
    // absolutely-positioned descendant is clipped and scrolled by its
    // *containing block*, which is a different ancestor-walk than "the
    // nearest ancestor with `overflow: auto`" -- `.content-scroll` itself
    // stays `position: static`, so it was never in that walk at all. Because
    // the field sits far down this page-length form, its static (auto-inset)
    // position placed it hundreds of px below the viewport, and browsers
    // count even visually-hidden (clip-rect, not display:none) elements
    // toward their containing block's scrollable overflow -- so `<html>`
    // gained that overflow and scrolled the whole document, dragging the
    // sticky header/tabs off-screen along with it even though they were
    // never unstuck relative to `.content-scroll` itself. Giving PageStack a
    // containing block here keeps that input's containing block inside
    // `.content-scroll`'s own scrolling box instead, so it stops leaking
    // into the document's scrollable overflow. Verified fix: toggling this
    // one declaration in devtools took documentElement.scrollHeight from
    // 1015 back to exactly 900 (== clientHeight) at 1440x900, with the input
    // still at the same in-page position -- just clipped/scrolled locally.
    <PageStack className="relative">
      <div className="sticky top-0 z-10 -mx-4 border-b border-border bg-surface px-4 sm:mx-0 sm:border-0 sm:px-0">
        <PageHeader
          title="การลา"
          subtitle="ยื่นคำขอลา ตรวจโควตา และพิจารณาคำขอของทีมในที่เดียว"
          actions={(
            <>
              <Button type="button" onClick={openRequestForm}>
                <Icon name="plus" />
                ยื่นคำขอลา
              </Button>
              <OverflowMenu
                label="การดำเนินการเพิ่มเติม"
                items={[
                  { key: 'refresh', label: 'รีเฟรช', icon: 'refresh', onSelect: refreshAll },
                ]}
              />
            </>
          )}
        />
        <Tabs
          items={visibleTabs.map(({ id, label, helper }) => ({ id, label, helper }))}
          value={activeTab}
          onChange={setActiveTab}
          ariaLabel="การลา"
          idPrefix={TAB_ID_PREFIX}
        />
      </div>

      <TabPanel id="me" idPrefix={TAB_ID_PREFIX} active={activeTab === 'me'}>
        <MyLeaveTab
          user={user}
          currentEmployee={currentEmployee}
          showToast={showToast}
          formAnchorRef={requestFormAnchorRef}
        />
      </TabPanel>

      <TabPanel id="review" idPrefix={TAB_ID_PREFIX} active={activeTab === 'review'}>
        <ReviewQueueTab user={user} showToast={showToast} />
      </TabPanel>

      <TabPanel id="rules" idPrefix={TAB_ID_PREFIX} active={activeTab === 'rules'}>
        {/* TODO(A3): replace this placeholder with the real per-leave-type rule disclosure
            (quota, notice window, attachment requirement, etc). Phase A1 deliberately adds NO new
            rule copy here -- see leaveSurfaceTabs.js's own comment on this tab. */}
        <StatePanel
          state="unavailable"
          title="หน้ากฎการลากำลังจะมา"
          description="เงื่อนไขการลาแต่ละประเภท (โควตา ระยะเวลาแจ้งล่วงหน้า เอกสารที่ต้องแนบ) จะแสดงที่นี่ในเวอร์ชันถัดไป"
        />
      </TabPanel>
    </PageStack>
  );
}
