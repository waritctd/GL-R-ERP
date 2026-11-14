import { describe, expect, it } from 'vitest';
import {
  canManagerCancelRequest,
  canReviewRequest,
  canSubmitOwnLeave,
  DEFAULT_LEAVE_SURFACE_TAB_ID,
  defaultLeaveSurfaceTabId,
  LEAVE_SURFACE_TABS,
  resolveLeaveSurfaceTab,
  resolveTabHelper,
  resolveTabLabel,
  visibleLeaveSurfaceTabIds,
} from './leaveSurfaceTabs.js';

const employee = { role: 'employee', employeeId: 1 };
const nonHrManager = { role: 'employee', employeeId: 5, manager: true };
const hr = { role: 'hr', employeeId: 99 };
const ceo = { role: 'ceo', employeeId: 1 };

const submittedRequestUnderNonHrManager = {
  id: 501, status: 'SUBMITTED', managerEmployeeId: 5,
};
const submittedRequestUnderSomeoneElse = {
  id: 502, status: 'SUBMITTED', managerEmployeeId: 999,
};

// api.leave.employees() shape: self + direct reports (hr/ceo: every active employee).
// Only `.length` matters to hasTeamMembers.
const selfOnlyEmployeeOptions = [
  {
    employeeId: 5, employeeName: 'หัวหน้างาน', self: true, directReport: false,
  },
];
const selfPlusReportEmployeeOptions = [
  {
    employeeId: 5, employeeName: 'หัวหน้างาน', self: true, directReport: false,
  },
  {
    employeeId: 6, employeeName: 'ลูกทีม', self: false, directReport: true,
  },
];

describe('LEAVE_SURFACE_TABS', () => {
  // Three tabs: the always-present LeavePolicyBar replaced "กฎการลา".
  it('declares the three ids, in order, with the expected Thai copy', () => {
    expect(LEAVE_SURFACE_TABS.map(({ id }) => id)).toEqual(['me', 'team', 'review']);
    expect(LEAVE_SURFACE_TABS.map(({ label }) => label)).toEqual(['ของฉัน', 'ลูกทีม', 'รอพิจารณา']);
    expect(LEAVE_SURFACE_TABS.map(({ helper }) => helper)).toEqual([
      'โควตาและคำขอลาของคุณ',
      'การลาของทีมคุณ',
      'คำขอลาที่รอคุณพิจารณา',
    ]);
  });
});

describe('visibleLeaveSurfaceTabIds', () => {
  it('a plain employee with no reports does not see "review" or "team"', () => {
    expect(visibleLeaveSurfaceTabIds(employee, [])).toEqual(['me']);
    // Still hidden even with OTHER people's requests loaded -- none of them are this user's own reports.
    expect(visibleLeaveSurfaceTabIds(employee, [submittedRequestUnderSomeoneElse])).toEqual(['me']);
    // ...and even with employeeOptions unset (the default -- a caller that never wired the signal through at all).
    expect(visibleLeaveSurfaceTabIds(employee, [], selfOnlyEmployeeOptions)).toEqual(['me']);
  });

  it('a non-HR manager (not in ROLE_PERMISSIONS.canReviewLeave) DOES see "review" once a report\'s request loads', () => {
    // THE load-bearing case: a plain division manager is not in canReviewLeave, but
    // LeaveService.canReviewEmployee grants any direct manager review rights over their reports.
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [submittedRequestUnderNonHrManager]))
      .toEqual(['me', 'review']);
  });

  it('a non-HR manager with no actionable request loaded yet does not see "review"', () => {
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [submittedRequestUnderSomeoneElse])).toEqual(['me']);
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [])).toEqual(['me']);
  });

  it('hr sees "team" and "review" with zero requests and zero employeeOptions loaded', () => {
    // No 'me' (hidden for hr/ceo), and 'team' is present despite empty `employeeOptions`:
    // hr/ceo get it from the role, not the data (the blank-page guard; see `team`'s isVisible).
    expect(visibleLeaveSurfaceTabIds(hr, [])).toEqual(['team', 'review']);
  });

  // hr/ceo oversee leave but never file it (LeaveService#resolveTargetEmployee 403s a
  // self-submission), so 'me' is hidden for them.
  describe('"me" is hidden for the queue-only roles (hr/ceo)', () => {
    it('hr and ceo do not get a "ของฉัน" tab', () => {
      expect(visibleLeaveSurfaceTabIds(hr, [])).not.toContain('me');
      expect(visibleLeaveSurfaceTabIds(ceo, [])).not.toContain('me');
    });

    it('every other role still gets it', () => {
      expect(visibleLeaveSurfaceTabIds(employee, [])).toContain('me');
      expect(visibleLeaveSurfaceTabIds(nonHrManager, [])).toContain('me');
    });

    // hr/ceo have no 'me', so `team` must short-circuit on role or the tab list is empty (blank
    // page) on first paint. Also guards a future queue-only role not in canReviewLeave.
    it('a ceo mid-first-load (no requests, no employeeOptions) still has at least one visible tab', () => {
      const visible = visibleLeaveSurfaceTabIds(ceo, [], []);
      expect(visible.length).toBeGreaterThan(0);
      expect(visible).toContain('team');
      expect(visible).toContain(resolveLeaveSurfaceTab(null, visible, defaultLeaveSurfaceTabId(ceo)));
    });

    // The final fallback must never be a hidden tab (e.g. 'me' for hr/ceo), or every panel is
    // inactive and the page is blank. Assert the resolved tab is always visible.
    it('never resolves to a hidden tab for hr/ceo, whatever ?tab= says', () => {
      const hrVisible = visibleLeaveSurfaceTabIds(hr, []);
      expect(hrVisible).not.toContain('me');

      // An explicit stale deep link to the now-hidden tab...
      expect(hrVisible).toContain(resolveLeaveSurfaceTab('me', hrVisible, 'review'));
      // ...an absent ?tab= with a preferred default that IS visible...
      expect(resolveLeaveSurfaceTab(null, hrVisible, 'review')).toBe('review');
      // ...and the last resort: no requested tab and a hidden preferred default.
      const resolved = resolveLeaveSurfaceTab(null, hrVisible, 'me');
      expect(hrVisible).toContain(resolved);
      expect(resolved).not.toBe('me');
    });
  });

  it('an APPROVED request a manager may still cancel also counts as actionable', () => {
    const approvedUnderManager = { id: 503, status: 'APPROVED', managerEmployeeId: 5 };
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [approvedUnderManager])).toEqual(['me', 'review']);
  });

  it('Phase A0: a server-supplied canReview=true row makes "review" visible even without a manager FK match', () => {
    const serverAuthoritative = { id: 504, status: 'SUBMITTED', managerEmployeeId: null, canReview: true };
    expect(visibleLeaveSurfaceTabIds(employee, [serverAuthoritative])).toEqual(['me', 'review']);
  });

  describe('"team" (2026-08 bugfix -- the correctly-scoped, correctly-labelled home for what used to leak into "me")', () => {
    it('is hidden when the actor\'s api.leave.employees() list is just themselves', () => {
      expect(visibleLeaveSurfaceTabIds(nonHrManager, [], selfOnlyEmployeeOptions)).toEqual(['me']);
    });

    it('is visible as soon as the actor has at least one other person (a direct report) in that list', () => {
      expect(visibleLeaveSurfaceTabIds(nonHrManager, [], selfPlusReportEmployeeOptions))
        .toEqual(['me', 'team']);
    });

    it('sits between "me" and "review" when both are visible', () => {
      expect(visibleLeaveSurfaceTabIds(nonHrManager, [submittedRequestUnderNonHrManager], selfPlusReportEmployeeOptions))
        .toEqual(['me', 'team', 'review']);
    });

    it('does NOT key off isDivisionManager-style user.manager -- a plain employee with reports in their employees() list still sees it', () => {
      // Deliberately no `manager: true` on this fixture -- hasTeamMembers reads employeeOptions, never
      // user.manager/role (aside from hr/ceo's own includeAll widening inside api.leave.employees() itself, which is
      // opaque to this function).
      const plainEmployeeWithReports = { role: 'employee', employeeId: 42 };
      expect(visibleLeaveSurfaceTabIds(plainEmployeeWithReports, [], selfPlusReportEmployeeOptions))
        .toEqual(['me', 'team']);
    });
  });
});

describe('resolveLeaveSurfaceTab', () => {
  it('keeps a tab id that is visible', () => {
    expect(resolveLeaveSurfaceTab('review', ['me', 'review'])).toBe('review');
    expect(resolveLeaveSurfaceTab('team', ['me', 'team'])).toBe('team');
  });

  it('falls back to "me" for an absent or unknown tab id', () => {
    expect(resolveLeaveSurfaceTab(null, ['me'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
    expect(resolveLeaveSurfaceTab(undefined, ['me'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
    expect(resolveLeaveSurfaceTab('not-a-real-tab', ['me', 'review'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
  });

  it('falls back to "me" for a user who requests ?tab=review but cannot currently see it', () => {
    // e.g. a plain employee with no reports hand-editing the URL, or a stale deep link shared by a manager to
    // someone who isn't one.
    expect(resolveLeaveSurfaceTab('review', ['me'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
  });

  // The optional third argument lets a caller (hr/ceo via defaultLeaveSurfaceTabId(user))
  // prefer a different landing tab than "me".
  it('prefers a supplied preferredDefaultId over DEFAULT_LEAVE_SURFACE_TAB_ID for an absent/unknown tab id', () => {
    expect(resolveLeaveSurfaceTab(null, ['me', 'review'], 'review')).toBe('review');
    expect(resolveLeaveSurfaceTab('not-a-real-tab', ['me', 'review'], 'review')).toBe('review');
  });

  it('degrades to DEFAULT_LEAVE_SURFACE_TAB_ID when preferredDefaultId is not currently visible', () => {
    // e.g. a queue-only role not in canReviewLeave with zero actionable rows, so "review" is
    // hidden. Uses a hand-built visibleIds list so it doesn't depend on which roles are in that array.
    expect(resolveLeaveSurfaceTab(null, ['me'], 'review')).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
  });

  it('still honours an explicitly requested, currently-visible tab over preferredDefaultId', () => {
    expect(resolveLeaveSurfaceTab('team', ['me', 'team', 'review'], 'review')).toBe('team');
  });
});

describe('canSubmitOwnLeave', () => {
  it('is false for hr and ceo -- they oversee leave but do not request it for themselves', () => {
    expect(canSubmitOwnLeave(hr)).toBe(false);
    expect(canSubmitOwnLeave(ceo)).toBe(false);
  });

  it('is true for a plain employee and a non-HR manager', () => {
    expect(canSubmitOwnLeave(employee)).toBe(true);
    expect(canSubmitOwnLeave(nonHrManager)).toBe(true);
  });

  it('is true (fail-open on presentation) for a missing/undefined user', () => {
    expect(canSubmitOwnLeave(undefined)).toBe(true);
    expect(canSubmitOwnLeave({})).toBe(true);
  });
});

describe('defaultLeaveSurfaceTabId', () => {
  it('is "review" for hr and ceo', () => {
    expect(defaultLeaveSurfaceTabId(hr)).toBe('review');
    expect(defaultLeaveSurfaceTabId(ceo)).toBe('review');
  });

  it('is DEFAULT_LEAVE_SURFACE_TAB_ID ("me") for every other role', () => {
    expect(defaultLeaveSurfaceTabId(employee)).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
    expect(defaultLeaveSurfaceTabId(nonHrManager)).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
  });
});

// hr/ceo get the whole company from api.leave.employees(), so they see the "team" tab, but
// "ลูกทีม" ("my team") would be wrong there. Keyed off the `team` tab entry directly to stay pure/fast.
describe('resolveTabLabel / resolveTabHelper (team tab role-aware copy)', () => {
  const teamTab = LEAVE_SURFACE_TABS.find((tab) => tab.id === 'team');

  it('an hr or ceo user sees "พนักงานทั้งหมด" copy, not "ลูกทีม"', () => {
    expect(resolveTabLabel(teamTab, hr)).toBe('พนักงานทั้งหมด');
    expect(resolveTabLabel(teamTab, ceo)).toBe('พนักงานทั้งหมด');
    expect(resolveTabHelper(teamTab, hr)).toBe('การลาของพนักงานทั้งหมด');
    expect(resolveTabHelper(teamTab, ceo)).toBe('การลาของพนักงานทั้งหมด');
  });

  it('a real division manager (non-hr/ceo, has direct reports) still sees "ลูกทีม" copy unchanged', () => {
    expect(resolveTabLabel(teamTab, nonHrManager)).toBe('ลูกทีม');
    expect(resolveTabHelper(teamTab, nonHrManager)).toBe('การลาของทีมคุณ');
  });

  it('a tab with no labelFor/helperFor override (e.g. "me") always returns the static copy', () => {
    const meTab = LEAVE_SURFACE_TABS.find((tab) => tab.id === 'me');
    expect(resolveTabLabel(meTab, hr)).toBe('ของฉัน');
    expect(resolveTabHelper(meTab, hr)).toBe('โควตาและคำขอลาของคุณ');
  });
});

describe('canReviewRequest / canManagerCancelRequest (ported from LeavePage.jsx)', () => {
  it('canReviewRequest requires SUBMITTED plus manager-FK match or canReviewAll', () => {
    expect(canReviewRequest(submittedRequestUnderNonHrManager, nonHrManager, false)).toBe(true);
    expect(canReviewRequest(submittedRequestUnderSomeoneElse, nonHrManager, false)).toBe(false);
    expect(canReviewRequest({ ...submittedRequestUnderSomeoneElse, status: 'APPROVED' }, hr, true)).toBe(false);
  });

  it('canManagerCancelRequest allows an already-APPROVED request', () => {
    const approvedUnderManager = { id: 505, status: 'APPROVED', managerEmployeeId: 5 };
    expect(canManagerCancelRequest(approvedUnderManager, nonHrManager, false)).toBe(true);
    expect(canManagerCancelRequest({ ...approvedUnderManager, status: 'CANCELLED' }, nonHrManager, false)).toBe(false);
  });

  // A pending row carries exactly two decisions (อนุมัติ / ปฏิเสธ); assert no third button,
  // for a manager AND a canReviewAll actor.
  it('canManagerCancelRequest refuses a still-SUBMITTED request', () => {
    expect(canManagerCancelRequest(submittedRequestUnderNonHrManager, nonHrManager, false)).toBe(false);
    expect(canManagerCancelRequest(submittedRequestUnderSomeoneElse, hr, true)).toBe(false);
  });

  it('canReviewRequest prefers a present request.canReview over the client-side fallback', () => {
    expect(canReviewRequest({ status: 'SUBMITTED', canReview: true }, employee, false)).toBe(true);
    expect(canReviewRequest({ status: 'SUBMITTED', managerEmployeeId: 5, canReview: false }, nonHrManager, false)).toBe(false);
  });

  it('canReviewRequest rejects a server-authoritative canReview=true on an already-decided request', () => {
    // Mirrors LeaveService.withCanReviewFlag, which computes canReview independent of status by design -- callers
    // must still gate on status themselves. Without this check, an already-decided (non-SUBMITTED) row would
    // render Approve/Reject buttons in ReviewQueueTab.
    expect(canReviewRequest({ status: 'REJECTED', canReview: true }, employee, false)).toBe(false);
    expect(canReviewRequest({ status: 'APPROVED', canReview: true }, employee, false)).toBe(false);
    expect(canReviewRequest({ status: 'CANCELLED', canReview: true }, employee, false)).toBe(false);
  });
});
