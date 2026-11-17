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
import { LeavePolicyBar } from './LeavePolicyBar.jsx';
import {
  canSubmitOwnLeave, defaultLeaveSurfaceTabId, LEAVE_SURFACE_TABS, resolveLeaveSurfaceTab,
  resolveTabHelper, resolveTabLabel, visibleLeaveSurfaceTabIds,
} from './leaveSurfaceTabs.js';
import { MyLeaveTab } from './MyLeaveTab.jsx';
import { ReviewQueueTab } from './ReviewQueueTab.jsx';
import { TeamLeaveTab } from './TeamLeaveTab.jsx';

const TAB_ID_PREFIX = 'leave-surface';

/** Leave-surface IA rebuild, Phase A1: the thin tabbed shell that replaces the pre-A1 LeavePage.jsx's single
 * 1000+ line component. */
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

  // Feeds ONLY the "รอพิจารณา" tab's visibility check (leaveSurfaceTabs.js's `review.isVisible`) -- an
  // unfiltered read so a manager whose only actionable request happens to fall outside whatever date range
  // MyLeaveTab's own filters currently have applied still sees the tab.
  const reviewSignalQuery = useQuery({
    queryKey: queryKeys.leaveRequests({}),
    queryFn: () => api.leave.list({}).then((response) => response.requests || []),
  });
  const reviewSignalRequests = useMemo(() => reviewSignalQuery.data ?? [], [reviewSignalQuery.data]);

  // Feeds ONLY the "ลูกทีม" tab's visibility check (leaveSurfaceTabs.js's `team.isVisible`, via hasTeamMembers)
  // -- shares its cache key with TeamLeaveTab.jsx's own employees query (both read
  // `queryKeys.leaveEmployees()`), so mounting that tab costs no extra request once this one has already landed.
  const teamSignalQuery = useQuery({
    queryKey: queryKeys.leaveEmployees(),
    queryFn: () => api.leave.employees().then((response) => response.employees || []),
  });
  const teamSignalEmployeeOptions = useMemo(() => teamSignalQuery.data ?? [], [teamSignalQuery.data]);

  const visibleTabIds = visibleLeaveSurfaceTabIds(user, reviewSignalRequests, teamSignalEmployeeOptions);
  // Plain (non-memoized) filter: `visibleTabIds` is itself freshly computed every render already (it's not
  // state), so memoizing this derived filter on top of it would only add an (unusable, non-primitive) dependency
  // without saving any real work -- LEAVE_SURFACE_TABS has at most 3 entries.
  const visibleTabs = LEAVE_SURFACE_TABS.filter((tab) => visibleTabIds.includes(tab.id));
  // Leave HR-submit gate (2026-08-03): hr/ceo actors land on "review" by default, not "me" -- see
  // defaultLeaveSurfaceTabId's own comment. Presentation only; the backend gate is the real rule
  // (LeaveService#resolveTargetEmployee).
  const activeTab = resolveLeaveSurfaceTab(searchParams.get('tab'), visibleTabIds, defaultLeaveSurfaceTabId(user));

  // The sticky header's "ยื่นคำขอลา" CTA (Phase A2, #485): navigates to the /leave/new composer, carrying the
  // currently-active tab so LeaveRequestPage can send the employee back to where they started (not always
  // "ของฉัน") on cancel/submit/back.
  const requestCtaRef = useRef(null);

  function openRequestForm() {
    navigate(`/leave/new?returnTab=${encodeURIComponent(activeTab)}`);
  }

  // Focus-restore: LeaveRequestPage navigates back here with `state.focusRequestCta` set (on cancel, back, or
  // successful submit) -- see its own comment. Consumed once via `replace` so a later reload/re-render of this
  // same location never re-fires the focus.
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
    // `relative` on PageStack: it is `.content-scroll`'s direct child and the only element between it and the
    // 16-field request form's `FileUploadField` (a visually-hidden `<input type="file" className="sr-only ...">`,
    // positioned `absolute` by that Tailwind utility -- FileUploadField.jsx:67).
    <PageStack className="relative">
      {/* Part 3 bug fix (owner's explicit, repeated request): this used to be `sticky top-0`. Reproduced exactly in
       * the browser (container top y=66, sticky pinned at y=94, elementFromPoint in the band returned page content,
       * not the header). */}
      <div>
        <PageHeader
          title="การลา"
          subtitle="ยื่นคำขอลา ตรวจโควตา และพิจารณาคำขอของทีมในที่เดียว"
          actions={(
            <>
              {/* Leave HR-submit gate (2026-08-03): hr/ceo oversee leave but do not request it for themselves (owner ruling)
               * -- hiding this CTA is presentation only, not the rule; LeaveService#resolveTargetEmployee is what actually
               * enforces it. */}
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
          items={visibleTabs.map((tab) => ({
            id: tab.id,
            label: resolveTabLabel(tab, user),
            helper: resolveTabHelper(tab, user),
          }))}
          value={activeTab}
          onChange={setActiveTab}
          ariaLabel="การลา"
          idPrefix={TAB_ID_PREFIX}
        />
      </div>

      {/* Replaces the "กฎการลา" tab (2026-08-10 restructure, owner ruling) -- the source document is reference
       * material for every tab, not a destination of its own, so it sits once above all of them rather than behind a
       * fourth tab nobody opens mid-task. */}
      <LeavePolicyBar />

      <TabPanel id="me" idPrefix={TAB_ID_PREFIX} active={activeTab === 'me'}>
        <MyLeaveTab
          user={user}
          currentEmployee={currentEmployee}
          showToast={showToast}
        />
      </TabPanel>

      <TabPanel id="team" idPrefix={TAB_ID_PREFIX} active={activeTab === 'team'}>
        <TeamLeaveTab user={user} showToast={showToast} />
      </TabPanel>

      <TabPanel id="review" idPrefix={TAB_ID_PREFIX} active={activeTab === 'review'}>
        <ReviewQueueTab user={user} showToast={showToast} />
      </TabPanel>
    </PageStack>
  );
}
