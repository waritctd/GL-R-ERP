// Leave-surface IA rebuild, Phase A1 ("shell"). LeavePage.jsx was one flat, 1000+ line
// component serving three unrelated jobs at once -- track my own leave, decide someone
// else's, and (not at all) look up a rule -- in one unbroken vertical scroll, so an
// approver used to see the submit form sitting directly above the queue they were
// reviewing. This module is the tab metadata list for the new tabbed shell
// (LeaveSurfacePage.jsx): same `[{id,label,helper,isVisible}]` + `resolve*Tab`/
// `visible*TabIds`/`DEFAULT_*_TAB_ID` shape as ticketDetailTabs.js, so the page stays a
// thin shell around it.
//
// Phase A1 deliberately adds NO new rule disclosure. `rules` below is a placeholder
// panel only -- see LeaveSurfacePage.jsx's TODO(A3) at its render site.

import { hasPermission } from '../../app/permissions.js';

// Ported from the pre-A1 LeavePage.jsx's `canReviewRequest` (~L540) and `canManagerCancel`
// (~L545) -- the per-row decision of whether THIS user may approve/reject (canReview) or
// cancel (canManagerCancel) a given request. Both check the same thing LeaveService.
// canReviewEmployee/isDirectManager check server-side: canReviewAll (the role-level
// permission -- hr today) OR a stored managerEmployeeId FK match. NEVER read either of
// these as an authorization decision on its own -- they are the client's best guess at
// what the server will allow, used only to decide what to render; the server enforces
// the real gate on every mutation regardless. See CLAUDE.md: verify against the real
// Java service, never the mock.
//
// Phase A0 shipped (#485): LeaveService#list now runs every returned row through
// withCanReviewFlag unconditionally (see LeaveRequestDto.canReview's own Javadoc), so the REAL
// backend always supplies this field now -- `canReviewRequest` below already prefers it when
// present.
//
// Phase A4 finding: the fallback branch is NOT dead and must stay. mockApi.js's `leave`
// namespace (list/create/approve/reject/cancel) never sets a `canReview` field on any row it
// returns -- there is no mirror of withCanReviewFlag there. Under `VITE_USE_MOCKS=true` (this
// app's default verification surface, per CLAUDE.md) every request object therefore has
// `canReview === undefined`, and this file's own test fixtures (leaveSurfaceTabs.test.js,
// ReviewQueueTab.test.jsx) never set it either -- removing the fallback would make
// `canReviewRequest` return `Boolean(undefined)` (always false) for every one of those rows,
// silently emptying the "รอพิจารณา" tab under mocks and breaking both test suites. Keep this
// branch until mockApi.js's leave namespace is updated to mirror withCanReviewFlag too.
// `canManagerCancelRequest` below never read `canReview` in the first place (always the
// client-side approximation) -- there is no fallback to remove there either.
export function canReviewRequest(request, user, canReviewAll) {
  if (request?.canReview != null) return Boolean(request.canReview) && request?.status === 'SUBMITTED';
  return request?.status === 'SUBMITTED'
    && (canReviewAll || (request?.managerEmployeeId && Number(request.managerEmployeeId) === Number(user?.employeeId)));
}

export function canManagerCancelRequest(request, user, canReviewAll) {
  return ['SUBMITTED', 'APPROVED'].includes(request?.status)
    && (canReviewAll || (request?.managerEmployeeId && Number(request.managerEmployeeId) === Number(user?.employeeId)));
}

function userMayActOnAnyRequest(requests, user, canReviewAll) {
  return requests.some((request) => canReviewRequest(request, user, canReviewAll)
    || canManagerCancelRequest(request, user, canReviewAll));
}

export const LEAVE_SURFACE_TABS = [
  {
    id: 'me',
    label: 'ของฉัน',
    helper: 'โควตาและคำขอลาของคุณ',
    isVisible: () => true,
  },
  {
    id: 'review',
    label: 'รอพิจารณา',
    helper: 'คำขอลาที่รอคุณพิจารณา',
    // THE load-bearing rule of this phase. LeaveService.canReviewEmployee =
    // canReviewAll(user) || isDirectManager(employeeId, actorEmployeeId), and
    // REVIEW_ALL_ROLES = Set.of("hr") server-side -- so ANY direct manager may approve
    // their own reports' leave, not just HR. ROLE_PERMISSIONS.canReviewLeave (routes.js)
    // is ['hr'] only: that constant is the coarse "sees a review surface at all,
    // regardless of whose requests" UI signal the rest of this app uses for route
    // guarding, NOT the real per-request gate. Gating this TAB on canReviewLeave alone
    // would hide the queue from every department manager -- exactly the role that uses
    // it most day to day.
    //
    // So: visible when EITHER the role-level permission already says so (hr, an
    // unconditional "always may review something"), OR the currently-loaded request
    // list contains at least one row this user may actually act on (their own reports'
    // SUBMITTED/APPROVED requests) -- reusing the exact per-row predicates above.
    isVisible: (user, requests, canReviewAll) => canReviewAll || userMayActOnAnyRequest(requests, user, canReviewAll),
  },
  {
    id: 'rules',
    label: 'กฎการลา',
    helper: 'เงื่อนไขการลาแต่ละประเภท',
    // TODO(A3): fill this tab with real rule disclosure (per-leave-type policy: quota,
    // notice window, attachment requirement, etc). Phase A1 renders a placeholder only
    // -- see LeaveSurfacePage.jsx's own TODO(A3) comment at the render site. Always
    // visible (same as `me`): there is no role gate on reading the leave-rules copy
    // itself, only on reviewing OTHER people's requests.
    isVisible: () => true,
  },
];

export const DEFAULT_LEAVE_SURFACE_TAB_ID = 'me';

// Leave HR-submit gate (2026-08-03), owner ruling: "HR and บริหาร oversee leave but do not
// request it for themselves." Same role bucket LeaveService.SELF_SUBMIT_BLOCKED_ROLES gates on
// server-side (`ผู้บริหาร` -> hr, `ผู้บริหารระดับสูง` -> ceo -- see frontend/src/app/roles.js).
// This constant drives presentation ONLY (hiding the "ยื่นคำขอลา" CTA, defaulting the landing
// tab to "review") -- it is not itself an authorization decision. The backend gate in
// LeaveService#resolveTargetEmployee is what actually stops a self-submission; hiding a button
// here cannot be bypassed-around into a false sense of enforcement (a direct POST, or reaching
// the composer via a stale link/URL, still 403s server-side).
const LEAVE_QUEUE_ONLY_ROLES = new Set(['hr', 'ceo']);

/**
 * Presentation-only check ("should this user see their own submit-leave entry points at all"),
 * NOT the authorization rule -- see LEAVE_QUEUE_ONLY_ROLES's comment above. Used to hide the
 * page-header "ยื่นคำขอลา" CTA (LeaveSurfacePage.jsx) and MyLeaveTab's empty-state CTA for
 * hr/ceo actors, who oversee leave but do not file it for themselves.
 */
export function canSubmitOwnLeave(user) {
  return !LEAVE_QUEUE_ONLY_ROLES.has(user?.role);
}

/**
 * The tab an hr/ceo actor should land on absent an explicit (and currently visible) `?tab=` --
 * "รอพิจารณา" (review), not "ของฉัน" (me), since they oversee leave rather than file their own.
 * Every other role keeps the pre-existing DEFAULT_LEAVE_SURFACE_TAB_ID ("me").
 */
export function defaultLeaveSurfaceTabId(user) {
  return LEAVE_QUEUE_ONLY_ROLES.has(user?.role) ? 'review' : DEFAULT_LEAVE_SURFACE_TAB_ID;
}

/**
 * The ordered list of tab ids `user` may see right now, given the currently-loaded
 * `requests` (used only by `review`'s isVisible -- see its own comment above).
 * `requests` defaults to `[]` so a caller mid-first-load (no data yet) never throws;
 * the `review` tab simply stays hidden until either the role permission is known or a
 * loaded row proves it should show.
 */
export function visibleLeaveSurfaceTabIds(user, requests = []) {
  const canReviewAll = hasPermission(user?.role, 'canReviewLeave');
  return LEAVE_SURFACE_TABS
    .filter((tab) => tab.isVisible(user, requests, canReviewAll))
    .map((tab) => tab.id);
}

/**
 * `requestedId` if it is one of `visibleIds`, else `preferredDefaultId` if THAT is visible,
 * else `DEFAULT_LEAVE_SURFACE_TAB_ID` ("me", which is unconditionally visible to everyone --
 * see LEAVE_SURFACE_TABS's own `isVisible: () => true`, so this final fallback can never itself
 * resolve to a hidden tab). An absent, unknown, or currently-hidden `?tab=` value (a stale deep
 * link from before a role change, a typo, hand-editing the URL, or a link shared by someone who
 * *can* see `review`) never renders a blank/forbidden panel.
 *
 * <p>`preferredDefaultId` defaults to `DEFAULT_LEAVE_SURFACE_TAB_ID` (unchanged pre-A1
 * behaviour for every existing caller) -- LeaveSurfacePage.jsx passes
 * `defaultLeaveSurfaceTabId(user)` so an hr/ceo actor's own default resolves to "review", but
 * still degrades to "me" for the rare case that tab isn't currently visible to them (e.g. a
 * ceo actor -- not in ROLE_PERMISSIONS.canReviewLeave -- with zero actionable rows loaded yet).
 *
 * <p>Takes `visibleIds` directly (not `user`/`requests`) so a caller that already computed them
 * via `visibleLeaveSurfaceTabIds` above never pays for the computation twice.
 */
export function resolveLeaveSurfaceTab(requestedId, visibleIds, preferredDefaultId = DEFAULT_LEAVE_SURFACE_TAB_ID) {
  if (visibleIds.includes(requestedId)) return requestedId;
  if (visibleIds.includes(preferredDefaultId)) return preferredDefaultId;
  return DEFAULT_LEAVE_SURFACE_TAB_ID;
}
