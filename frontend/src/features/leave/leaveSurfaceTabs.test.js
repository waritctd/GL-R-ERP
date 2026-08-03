import { describe, expect, it } from 'vitest';
import {
  canManagerCancelRequest,
  canReviewRequest,
  DEFAULT_LEAVE_SURFACE_TAB_ID,
  LEAVE_SURFACE_TABS,
  resolveLeaveSurfaceTab,
  visibleLeaveSurfaceTabIds,
} from './leaveSurfaceTabs.js';

const employee = { role: 'employee', employeeId: 1 };
const nonHrManager = { role: 'employee', employeeId: 5, manager: true };
const hr = { role: 'hr', employeeId: 99 };

const submittedRequestUnderNonHrManager = {
  id: 501, status: 'SUBMITTED', managerEmployeeId: 5,
};
const submittedRequestUnderSomeoneElse = {
  id: 502, status: 'SUBMITTED', managerEmployeeId: 999,
};

describe('LEAVE_SURFACE_TABS', () => {
  it('declares the three ids, in order, with the expected Thai copy', () => {
    expect(LEAVE_SURFACE_TABS.map(({ id }) => id)).toEqual(['me', 'review', 'rules']);
    expect(LEAVE_SURFACE_TABS.map(({ label }) => label)).toEqual(['ของฉัน', 'รอพิจารณา', 'กฎการลา']);
    expect(LEAVE_SURFACE_TABS.map(({ helper }) => helper)).toEqual([
      'โควตาและคำขอลาของคุณ',
      'คำขอลาที่รอคุณพิจารณา',
      'เงื่อนไขการลาแต่ละประเภท',
    ]);
  });
});

describe('visibleLeaveSurfaceTabIds', () => {
  it('a plain employee with no reports does not see "review"', () => {
    expect(visibleLeaveSurfaceTabIds(employee, [])).toEqual(['me', 'rules']);
    // Still hidden even with OTHER people's requests loaded -- none of them are this
    // user's own reports.
    expect(visibleLeaveSurfaceTabIds(employee, [submittedRequestUnderSomeoneElse])).toEqual(['me', 'rules']);
  });

  it('a non-HR manager (not in ROLE_PERMISSIONS.canReviewLeave) DOES see "review" once a report\'s request loads', () => {
    // THE load-bearing case: canReviewLeave is ['hr'] only, but LeaveService.canReviewEmployee
    // grants ANY direct manager review rights over their own reports.
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [submittedRequestUnderNonHrManager]))
      .toEqual(['me', 'review', 'rules']);
  });

  it('a non-HR manager with no actionable request loaded yet does not see "review"', () => {
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [submittedRequestUnderSomeoneElse])).toEqual(['me', 'rules']);
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [])).toEqual(['me', 'rules']);
  });

  it('hr sees "review" unconditionally (canReviewAll), even with zero requests loaded', () => {
    expect(visibleLeaveSurfaceTabIds(hr, [])).toEqual(['me', 'review', 'rules']);
  });

  it('an APPROVED request a manager may still cancel also counts as actionable', () => {
    const approvedUnderManager = { id: 503, status: 'APPROVED', managerEmployeeId: 5 };
    expect(visibleLeaveSurfaceTabIds(nonHrManager, [approvedUnderManager])).toEqual(['me', 'review', 'rules']);
  });

  it('Phase A0: a server-supplied canReview=true row makes "review" visible even without a manager FK match', () => {
    const serverAuthoritative = { id: 504, status: 'SUBMITTED', managerEmployeeId: null, canReview: true };
    expect(visibleLeaveSurfaceTabIds(employee, [serverAuthoritative])).toEqual(['me', 'review', 'rules']);
  });
});

describe('resolveLeaveSurfaceTab', () => {
  it('keeps a tab id that is visible', () => {
    expect(resolveLeaveSurfaceTab('review', ['me', 'review', 'rules'])).toBe('review');
    expect(resolveLeaveSurfaceTab('rules', ['me', 'rules'])).toBe('rules');
  });

  it('falls back to "me" for an absent or unknown tab id', () => {
    expect(resolveLeaveSurfaceTab(null, ['me', 'rules'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
    expect(resolveLeaveSurfaceTab(undefined, ['me', 'rules'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
    expect(resolveLeaveSurfaceTab('not-a-real-tab', ['me', 'review', 'rules'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
  });

  it('falls back to "me" for a user who requests ?tab=review but cannot currently see it', () => {
    // e.g. a plain employee with no reports hand-editing the URL, or a stale deep link
    // shared by a manager to someone who isn't one.
    expect(resolveLeaveSurfaceTab('review', ['me', 'rules'])).toBe(DEFAULT_LEAVE_SURFACE_TAB_ID);
  });
});

describe('canReviewRequest / canManagerCancelRequest (ported from LeavePage.jsx)', () => {
  it('canReviewRequest requires SUBMITTED plus manager-FK match or canReviewAll', () => {
    expect(canReviewRequest(submittedRequestUnderNonHrManager, nonHrManager, false)).toBe(true);
    expect(canReviewRequest(submittedRequestUnderSomeoneElse, nonHrManager, false)).toBe(false);
    expect(canReviewRequest({ ...submittedRequestUnderSomeoneElse, status: 'APPROVED' }, hr, true)).toBe(false);
  });

  it('canManagerCancelRequest also allows an already-APPROVED request', () => {
    const approvedUnderManager = { id: 505, status: 'APPROVED', managerEmployeeId: 5 };
    expect(canManagerCancelRequest(approvedUnderManager, nonHrManager, false)).toBe(true);
    expect(canManagerCancelRequest({ ...approvedUnderManager, status: 'CANCELLED' }, nonHrManager, false)).toBe(false);
  });

  it('canReviewRequest prefers a present request.canReview over the client-side fallback', () => {
    expect(canReviewRequest({ status: 'REJECTED', canReview: true }, employee, false)).toBe(true);
    expect(canReviewRequest({ status: 'SUBMITTED', managerEmployeeId: 5, canReview: false }, nonHrManager, false)).toBe(false);
  });
});
