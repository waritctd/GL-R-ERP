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
// mockApi.js's `leave` namespace (list/create/approve/reject/cancel, via buildLeaveRecord)
// now mirrors withCanReviewFlag too -- it stamps `canReview` on every row using the same
// canReviewLeave() predicate (hr bypass, else active-employee direct-manager FK match) the mock
// already gates approve/reject/cancel on. So under `VITE_USE_MOCKS=true` the server-authoritative
// branch above is exercised the same as against the real backend, and the fallback below is no
// longer load-bearing for mock-driven testing -- it remains only as defence for any caller that
// hands `canReviewRequest` a plain object with no `canReview` field at all (e.g. a hand-built
// fixture). `canManagerCancelRequest` below never read `canReview` in the first place (always the
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

// Bugfix (2026-08): the "ของฉัน" tab's own requestsQuery used to omit `employeeId` by
// default, and LeaveService#list -- for any actor outside VIEW_ALL_ROLES (hr/ceo) --
// treats an omitted employeeId as "self OR reports_to_employee_id = actor"
// (LeaveRepository#findRequests' `filter.managerEmployeeId()` branch). So a manager's
// direct reports' leave requests were genuinely served into a panel titled "MY leave
// requests". MyLeaveTab.jsx now always scopes to the actor's own employeeId; this
// `team` tab is the correctly-labelled, correctly-scoped home for the team-wide view
// that used to leak into `me`.
//
// `employeeOptions` is exactly api.leave.employees()'s response -- the SAME "self +
// direct reports" (or, for hr/ceo, every active employee) list TeamLeaveTab.jsx's own
// "ทุกคน" filter select gates on via `employeeOptions.length > 1`. Deliberately reused
// verbatim rather than a second computation, so the tab and its filter never disagree
// about whether this actor "has a team": if you can't see your team in one you
// shouldn't see it in the other.
//
// Deliberately NOT isDivisionManager(user) (app/permissions.js) -- that flag is
// `user.role === 'employee' && !!user.manager`, a position-based signal that is a
// DIFFERENT set of people than the reports_to_employee_id org-chart relationship this
// tab (and LeaveService#list itself) actually scope on. A sales_manager, or anyone else
// with direct reports who isn't formally a "division manager" by title, must still see
// this tab.
function hasTeamMembers(employeeOptions) {
  return employeeOptions.length > 1;
}

export const LEAVE_SURFACE_TABS = [
  {
    id: 'me',
    label: 'ของฉัน',
    helper: 'โควตาและคำขอลาของคุณ',
    // Hidden for hr/ceo (owner ruling, 2026-08-10). This tab is entirely about the viewer's OWN
    // leave -- their quota, their upcoming days, their history -- and hr/ceo do not file leave
    // through this system at all: `canSubmitOwnLeave` already hid both "ยื่นคำขอลา" CTAs for them,
    // and LeaveService#resolveTargetEmployee 403s a self-submission. What was left was a tab whose
    // primary action is forbidden, showing a quota they cannot spend here.
    //
    // Reuses `canSubmitOwnLeave` rather than a second LEAVE_QUEUE_ONLY_ROLES check so the hr/ceo
    // bucket has exactly one definition on this surface. (Referencing it above its own declaration
    // is safe: `isVisible` is only ever CALLED from visibleLeaveSurfaceTabIds, long after module
    // evaluation.)
    //
    // Presentation only -- like every other gate in this file. Hiding a tab is not an
    // authorization decision; the server enforces the real rule.
    isVisible: (user) => canSubmitOwnLeave(user),
  },
  {
    id: 'team',
    label: 'ลูกทีม',
    helper: 'การลาของทีมคุณ',
    // Review fix (2026-08): api.leave.employees() returns literally every active employee
    // for hr/ceo (VIEW_ALL_ROLES server-side, mirrored here by ROLE_PERMISSIONS.canViewAllLeave
    // -- routes.js), so hasTeamMembers is always true for them and this tab is correctly kept
    // visible (it's now the only date/status/employee-filtered browse-all surface hr/ceo have).
    // But "ลูกทีม"/"การลาของทีมคุณ" is factually wrong when the viewer is looking at the whole
    // company, not a set of direct reports -- labelFor/helperFor below swap in company-wide
    // copy for exactly that case, via resolveTabLabel/resolveTabHelper. A real division
    // manager (not hr/ceo) keeps the original label/helper above unchanged.
    labelFor: (user) => (hasPermission(user?.role, 'canViewAllLeave') ? 'พนักงานทั้งหมด' : 'ลูกทีม'),
    helperFor: (user) => (hasPermission(user?.role, 'canViewAllLeave') ? 'การลาของพนักงานทั้งหมด' : 'การลาของทีมคุณ'),
    // See hasTeamMembers' own comment above for the full reasoning. Visible to an actor who
    // currently has at least one other person (a direct report) in their api.leave.employees()
    // list -- OR unconditionally to hr/ceo, who can always view every employee's leave.
    //
    // The hr/ceo short-circuit is not just an optimisation, it is a BLANK-PAGE GUARD. `employeeOptions`
    // is `[]` until that query lands, so a purely data-driven check hides this tab on first paint.
    // That was survivable while "กฎการลา" existed (always visible, so the page always had at least
    // one tab), but the 2026-08-10 restructure removed that tab AND hid "ของฉัน" for hr/ceo. A ceo
    // is not in ROLE_PERMISSIONS.canReviewLeave either, so "รอพิจารณา" also needs loaded rows --
    // leaving a ceo with ZERO visible tabs for the duration of the first load, i.e. a blank page.
    // hr/ceo's own api.leave.employees() response is every active employee (VIEW_ALL_ROLES), so
    // this tab is always genuinely available to them; asserting that from the role rather than
    // waiting for the data to prove it removes both the flicker and the empty state.
    isVisible: (user, requests, canReviewAll, employeeOptions) => (
      hasPermission(user?.role, 'canViewAllLeave') || hasTeamMembers(employeeOptions)
    ),
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
  // The "กฎการลา" tab was REMOVED on 2026-08-10 (owner ruling). The §5 announcement it existed to
  // surface is now a permanent reference bar above the tabs (LeavePolicyBar.jsx), matching the
  // welfare page's own "ระเบียบสวัสดิการ (PDF)" bar -- reference material for every tab rather
  // than a destination of its own. The in-app §5 clause breakdown (RulesTab.jsx,
  // leavePolicySections.js) was dropped with it; the PDF is the authoritative text.
  //
  // ⚠️ It was also the ONLY unconditionally-visible tab, which several things quietly leaned on.
  // See `team`'s isVisible above (blank-page guard) and resolveLeaveSurfaceTab's fallback below.
];

/**
 * `tab.label`/`tab.helper` unless the tab declares a role-aware override
 * (`labelFor`/`helperFor` -- currently only `team`, see its own comment above), in which case
 * that override wins. Centralised here so every renderer of LEAVE_SURFACE_TABS copy (currently
 * LeaveSurfacePage.jsx's `<Tabs>` items) resolves the same way instead of reading `tab.label`
 * directly and missing the hr/ceo override.
 */
export function resolveTabLabel(tab, user) {
  return tab.labelFor ? tab.labelFor(user) : tab.label;
}

export function resolveTabHelper(tab, user) {
  return tab.helperFor ? tab.helperFor(user) : tab.helper;
}

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
 * `requests` (used only by `review`'s isVisible -- see its own comment above) and
 * `employeeOptions` (used only by `team`'s isVisible -- see hasTeamMembers' own comment
 * above). Both default to `[]` so a caller mid-first-load (no data yet) never throws;
 * `review`/`team` simply stay hidden until either the role permission is known or the
 * relevant data proves the tab should show.
 */
export function visibleLeaveSurfaceTabIds(user, requests = [], employeeOptions = []) {
  const canReviewAll = hasPermission(user?.role, 'canReviewLeave');
  return LEAVE_SURFACE_TABS
    .filter((tab) => tab.isVisible(user, requests, canReviewAll, employeeOptions))
    .map((tab) => tab.id);
}

/**
 * `requestedId` if it is one of `visibleIds`, else `preferredDefaultId` if THAT is visible, else
 * `DEFAULT_LEAVE_SURFACE_TAB_ID` if THAT is visible, else the first visible tab. An absent,
 * unknown, or currently-hidden `?tab=` value (a stale deep link from before a role change, a typo,
 * hand-editing the URL, or a link shared by someone who *can* see `review`) never renders a
 * blank/forbidden panel.
 *
 * <p>⚠️ The last two steps used to be one: this returned `DEFAULT_LEAVE_SURFACE_TAB_ID` ("me")
 * unconditionally, justified by "me is unconditionally visible to everyone … so this final
 * fallback can never itself resolve to a hidden tab". **That invariant no longer holds** -- as of
 * 2026-08-10 `me` is hidden for hr/ceo (see its `isVisible`), so for those two roles the old final
 * fallback would have returned a tab that is not rendered, leaving the page with every panel
 * inactive. `me` is still TRIED first among the fallbacks (it is the right default for everyone
 * who can see it), but it is now checked against `visibleIds` like anything else, with the first
 * visible tab as the genuine last resort.
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
  if (visibleIds.includes(DEFAULT_LEAVE_SURFACE_TAB_ID)) return DEFAULT_LEAVE_SURFACE_TAB_ID;
  // Last resort: the first visible tab. Returning DEFAULT_LEAVE_SURFACE_TAB_ID unconditionally
  // (what this did before 2026-08-10) is no longer safe -- 'me' is hidden for hr/ceo, so that
  // would name a tab with no rendered panel and blank the page. The old justification leaned on
  // 'rules' being unconditionally visible; that tab no longer exists either.
  return visibleIds[0] ?? DEFAULT_LEAVE_SURFACE_TAB_ID;
}
