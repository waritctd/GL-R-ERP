import { useEffect, useMemo, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Tabs, TabPanel } from '../../components/common/Tabs.jsx';
import {
  canSubmitOwnLeave, defaultLeaveSurfaceTabId, LEAVE_SURFACE_TABS, resolveLeaveSurfaceTab,
  visibleLeaveSurfaceTabIds,
} from './leaveSurfaceTabs.js';
import { MyLeaveTab } from './MyLeaveTab.jsx';
import { ReviewQueueTab } from './ReviewQueueTab.jsx';
import { RulesTab } from './RulesTab.jsx';

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
  const navigate = useNavigate();
  const location = useLocation();
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
  // Leave HR-submit gate (2026-08-03): hr/ceo actors land on "review" by default, not "me" --
  // see defaultLeaveSurfaceTabId's own comment. Presentation only; the backend gate is the real
  // rule (LeaveService#resolveTargetEmployee).
  const activeTab = resolveLeaveSurfaceTab(searchParams.get('tab'), visibleTabIds, defaultLeaveSurfaceTabId(user));

  // The sticky header's "ยื่นคำขอลา" CTA (Phase A2, #485): navigates to the /leave/new composer,
  // carrying the currently-active tab so LeaveRequestPage can send the employee back to where they
  // started (not always "ของฉัน") on cancel/submit/back.
  const requestCtaRef = useRef(null);

  function openRequestForm() {
    navigate(`/leave/new?returnTab=${encodeURIComponent(activeTab)}`);
  }

  // Focus-restore: LeaveRequestPage navigates back here with `state.focusRequestCta` set (on
  // cancel, back, or successful submit) -- see its own comment. Consumed once via `replace` so a
  // later reload/re-render of this same location never re-fires the focus.
  // Fires once per landing with `location.state.focusRequestCta` set; deliberately keyed on
  // `location.state` ALONE -- adding pathname/search/navigate would re-run this on every
  // unrelated render (navigate's identity is not guaranteed stable across renders) and replay a
  // stale queued focus.
  useEffect(() => {
    if (!location.state?.focusRequestCta) return;
    requestCtaRef.current?.focus();
    navigate(`${location.pathname}${location.search}`, { replace: true, state: {} });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.state]);

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
      {/* Part 3 bug fix (owner's explicit, repeated request): this used to be
          `sticky top-0`. `.content-scroll` has `padding-top: 28px`, and
          `position: sticky; top: 0` pins to that *content* box — so the
          header parked 28px BELOW the scroll container's real top edge, and
          that 28px band had no background of its own, letting scrolled
          content show through above the "pinned" header. Reproduced exactly
          in the browser (container top y=66, sticky pinned at y=94,
          elementFromPoint in the band returned page content, not the
          header). Now plain flow content — no sticky, no bleed
          compensation. */}
      <div>
        <PageHeader
          title="การลา"
          subtitle="ยื่นคำขอลา ตรวจโควตา และพิจารณาคำขอของทีมในที่เดียว"
          actions={(
            <>
              {/* Leave HR-submit gate (2026-08-03): hr/ceo oversee leave but do not request it
                  for themselves (owner ruling) -- hiding this CTA is presentation only, not the
                  rule; LeaveService#resolveTargetEmployee is what actually enforces it. */}
              {canSubmitOwnLeave(user) ? (
                <Button type="button" ref={requestCtaRef} onClick={openRequestForm}>
                  <Icon name="plus" />
                  ยื่นคำขอลา
                </Button>
              ) : null}
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
        />
      </TabPanel>

      <TabPanel id="review" idPrefix={TAB_ID_PREFIX} active={activeTab === 'review'}>
        <ReviewQueueTab user={user} showToast={showToast} />
      </TabPanel>

      <TabPanel id="rules" idPrefix={TAB_ID_PREFIX} active={activeTab === 'rules'}>
        <RulesTab />
      </TabPanel>
    </PageStack>
  );
}
