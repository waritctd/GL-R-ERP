// Tab metadata for the tabbed leave shell (LeaveSurfacePage.jsx): same
// `[{id,label,helper,isVisible}]` + `resolve*Tab`/`visible*TabIds`/`DEFAULT_*_TAB_ID` shape as
// ticketDetailTabs.js, so the page stays a thin shell. `rules` below is a placeholder panel only
// (see LeaveSurfacePage.jsx's TODO(A3) at its render site).

import { hasPermission } from '../../app/permissions.js';

// The per-row decision of whether THIS user may approve/reject (canReview) or cancel
// (canManagerCancel) a request. Both mirror LeaveService.canReviewEmployee/isDirectManager:
// canReviewAll (hr) OR a stored managerEmployeeId FK match. Never an authorization decision: they
// only choose what to render; the server enforces the real gate on every mutation (CLAUDE.md:
// verify against the real Java service, never the mock).
//
// LeaveService#list stamps `canReview` on every row (withCanReviewFlag; see
// LeaveRequestDto.canReview), and the mock does too via canReviewLeave(), so `canReviewRequest`
// prefers it. The client-side fallback remains only for a plain object with no `canReview` field
// (e.g. a hand-built fixture). `canManagerCancelRequest` never read `canReview`.
export function canReviewRequest(request, user, canReviewAll) {
  if (request?.canReview != null) return Boolean(request.canReview) && request?.status === 'SUBMITTED';
  return request?.status === 'SUBMITTED'
    && (canReviewAll || (request?.managerEmployeeId && Number(request.managerEmployeeId) === Number(user?.employeeId)));
}

// APPROVED only. On a pending request the reviewer's decision is approve-or-reject; "ยกเลิก" voids
// leave that was already granted, so offering it beside ปฏิเสธ gave two negative-looking buttons.
//
// This narrows what the CLIENT renders, not what the server allows: LeaveService#cancel still
// accepts a SUBMITTED request (and the requester's self-cancel on MyLeaveTab.jsx is a different
// affordance). Hiding a button is never the authorization rule.
export function canManagerCancelRequest(request, user, canReviewAll) {
  return request?.status === 'APPROVED'
    && (canReviewAll || (request?.managerEmployeeId && Number(request.managerEmployeeId) === Number(user?.employeeId)));
}

function userMayActOnAnyRequest(requests, user, canReviewAll) {
  return requests.some((request) => canReviewRequest(request, user, canReviewAll)
    || canManagerCancelRequest(request, user, canReviewAll));
}

// The "ของฉัน" tab always scopes to the actor's own employeeId, because LeaveService#list serves
// an omitted employeeId as "self OR reports_to_employee_id = actor" for anyone outside
// VIEW_ALL_ROLES (hr/ceo). This `team` tab is the home for the team-wide view.
//
// `employeeOptions` is exactly api.leave.employees()'s response, the same list TeamLeaveTab.jsx's
// "ทุกคน" filter gates on via `employeeOptions.length > 1`, so the tab and its filter never
// disagree about whether this actor "has a team".
//
// Not isDivisionManager(user) (app/permissions.js): that is a position-based signal, a different
// set of people than the reports_to_employee_id relationship LeaveService#list scopes on. A
// sales_manager with direct reports must still see this tab.
function hasTeamMembers(employeeOptions) {
  return employeeOptions.length > 1;
}

export const LEAVE_SURFACE_TABS = [
  {
    id: 'me',
    label: 'ของฉัน',
    helper: 'โควตาและคำขอลาของคุณ',
    // Hidden for hr/ceo: this tab is about the viewer's OWN leave, and hr/ceo do not file leave
    // (`canSubmitOwnLeave` hides both "ยื่นคำขอลา" CTAs, and LeaveService#resolveTargetEmployee 403s a
    // self-submission). Reuses `canSubmitOwnLeave` so the hr/ceo bucket has one definition here.
    // (Referencing it above its declaration is safe: `isVisible` is only called later.)
    //
    // Presentation only; the server enforces the real rule.
    isVisible: (user) => canSubmitOwnLeave(user),
  },
  {
    id: 'team',
    label: 'ลูกทีม',
    helper: 'การลาของทีมคุณ',
    // api.leave.employees() returns every active employee for hr/ceo (VIEW_ALL_ROLES, mirrored by
    // ROLE_PERMISSIONS.canViewAllLeave), so hasTeamMembers is always true for them. But "ลูกทีม" /
    // "การลาของทีมคุณ" is wrong when looking at the whole company, so labelFor/helperFor swap in
    // company-wide copy for that case, via resolveTabLabel/resolveTabHelper.
    labelFor: (user) => (hasPermission(user?.role, 'canViewAllLeave') ? 'พนักงานทั้งหมด' : 'ลูกทีม'),
    helperFor: (user) => (hasPermission(user?.role, 'canViewAllLeave') ? 'การลาของพนักงานทั้งหมด' : 'การลาของทีมคุณ'),
    // Visible to an actor with at least one other person in their api.leave.employees() list, OR
    // unconditionally to hr/ceo, who can always view every employee's leave.
    //
    // The hr/ceo short-circuit is a blank-page guard: `employeeOptions` is `[]` until that query
    // lands, so a purely data-driven check would hide this tab on first paint, and with "ของฉัน"
    // hidden for hr/ceo that can leave zero visible tabs. It stays unconditional so `team` covers hr
    // and any future role added to canViewAllLeave without canReviewLeave.
    isVisible: (user, requests, canReviewAll, employeeOptions) => (
      hasPermission(user?.role, 'canViewAllLeave') || hasTeamMembers(employeeOptions)
    ),
  },
  {
    id: 'review',
    label: 'รอพิจารณา',
    helper: 'คำขอลาที่รอคุณพิจารณา',
    // LeaveService.canReviewEmployee = canReviewAll(user) || isDirectManager(employeeId,
    // actorEmployeeId), and REVIEW_ALL_ROLES = Set.of("hr", "ceo") server-side, so ANY direct manager
    // may also approve their reports' leave. ROLE_PERMISSIONS.canReviewLeave (routes.js) is the coarse
    // "sees a review surface at all" signal for route guarding, not the per-request gate; gating this
    // tab on it alone would hide the queue from every department manager.
    //
    // So: visible when the role-level permission says so, OR the loaded request list has at least one
    // row this user may act on, using the per-row predicates above.
    isVisible: (user, requests, canReviewAll) => canReviewAll || userMayActOnAnyRequest(requests, user, canReviewAll),
  },
  // There is no "กฎการลา" tab: the §5 announcement is a permanent reference bar above the tabs
  // (LeavePolicyBar.jsx) and the PDF is the authoritative text. That tab was the only
  // unconditionally-visible one, which `team`'s isVisible (blank-page guard) and
  // resolveLeaveSurfaceTab's fallback below compensate for.
];

/** `tab.label`/`tab.helper` unless the tab declares a role-aware override (`labelFor`/`helperFor` -- currently
 * only `team`, see its own comment above), in which case that override wins. */
export function resolveTabLabel(tab, user) {
  return tab.labelFor ? tab.labelFor(user) : tab.label;
}

export function resolveTabHelper(tab, user) {
  return tab.helperFor ? tab.helperFor(user) : tab.helper;
}

export const DEFAULT_LEAVE_SURFACE_TAB_ID = 'me';

// hr and บริหาร oversee leave but do not request it for themselves. Same role bucket as
// LeaveService.SELF_SUBMIT_BLOCKED_ROLES (`ผู้บริหาร` -> hr, `ผู้บริหารระดับสูง` -> ceo; see
// frontend/src/app/roles.js). Presentation only (hides the "ยื่นคำขอลา" CTA, defaults the landing
// tab to "review"); LeaveService#resolveTargetEmployee is what stops a self-submission (a direct
// POST or a stale composer link still 403s).
const LEAVE_QUEUE_ONLY_ROLES = new Set(['hr', 'ceo']);

/**
 * Presentation-only check ("should this user see their own submit-leave entry points"), not the
 * authorization rule. Hides the page-header "ยื่นคำขอลา" CTA (LeaveSurfacePage.jsx) and
 * MyLeaveTab's empty-state CTA for hr/ceo.
 */
export function canSubmitOwnLeave(user) {
  return !LEAVE_QUEUE_ONLY_ROLES.has(user?.role);
}

/** The tab an hr/ceo actor should land on absent an explicit (and currently visible) `?tab=` -- "รอพิจารณา"
 * (review), not "ของฉัน" (me), since they oversee leave rather than file their own. Every other role keeps the
 * pre-existing DEFAULT_LEAVE_SURFACE_TAB_ID ("me"). */
export function defaultLeaveSurfaceTabId(user) {
  return LEAVE_QUEUE_ONLY_ROLES.has(user?.role) ? 'review' : DEFAULT_LEAVE_SURFACE_TAB_ID;
}

/**
 * The ordered tab ids `user` may see right now, given the loaded `requests` (used only by
 * `review`'s isVisible) and `employeeOptions` (used only by `team`'s isVisible). Both default to
 * `[]` so a caller mid-first-load never throws; `review`/`team` stay hidden until the role
 * permission or the data says otherwise.
 */
export function visibleLeaveSurfaceTabIds(user, requests = [], employeeOptions = []) {
  const canReviewAll = hasPermission(user?.role, 'canReviewLeave');
  return LEAVE_SURFACE_TABS
    .filter((tab) => tab.isVisible(user, requests, canReviewAll, employeeOptions))
    .map((tab) => tab.id);
}

/**
 * `requestedId` if it is in `visibleIds`, else `preferredDefaultId` if visible, else
 * `DEFAULT_LEAVE_SURFACE_TAB_ID` if visible, else the first visible tab. An absent, unknown, or
 * currently-hidden `?tab=` value (stale deep link, typo, link shared by someone who can see
 * `review`) never renders a blank/forbidden panel.
 *
 * <p>`me` is hidden for hr/ceo, so it is checked against `visibleIds` like any other fallback; the
 * first visible tab is the last resort.
 *
 * <p>`preferredDefaultId` defaults to `DEFAULT_LEAVE_SURFACE_TAB_ID`; LeaveSurfacePage.jsx passes
 * `defaultLeaveSurfaceTabId(user)` so hr/ceo default to "review", degrading to "me" if that tab is
 * not visible to them.
 *
 * <p>Takes `visibleIds` directly so a caller that already computed them via
 * `visibleLeaveSurfaceTabIds` does not compute twice.
 */
export function resolveLeaveSurfaceTab(requestedId, visibleIds, preferredDefaultId = DEFAULT_LEAVE_SURFACE_TAB_ID) {
  if (visibleIds.includes(requestedId)) return requestedId;
  if (visibleIds.includes(preferredDefaultId)) return preferredDefaultId;
  if (visibleIds.includes(DEFAULT_LEAVE_SURFACE_TAB_ID)) return DEFAULT_LEAVE_SURFACE_TAB_ID;
  // Last resort: the first visible tab. 'me' is hidden for hr/ceo, so returning
  // DEFAULT_LEAVE_SURFACE_TAB_ID unconditionally would name a tab with no rendered panel.
  return visibleIds[0] ?? DEFAULT_LEAVE_SURFACE_TAB_ID;
}
